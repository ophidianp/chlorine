package com.isotope.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import com.isotope.model.ClassInfo;
import com.isotope.rewrite.ModClass;
import com.isotope.rewrite.Selector;

/**
 * Pre-flight check for mixins: do the things a mixin points at still exist?
 *
 * <p>Mixins are the most fragile part of any mod, because they hook into the middle of
 * game methods by name. This checks the cheap, certain part: the target class exists,
 * the target method exists, shadowed members exist. It cannot tell whether an
 * {@code @At} still matches an instruction inside that method - only running the game
 * shows that.
 */
public final class MixinCheck {
	private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
	private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";

	private MixinCheck() { }

	/** Returns the mixin's targets that exist; an empty list means the mixin has nothing left to apply to. */
	public static List<String> check(byte[] classBytes, LiveIndex index, Report report) {
		return check(classBytes, index, report, false);
	}

	public static List<String> check(byte[] classBytes, LiveIndex index, Report report, boolean dedicatedServer) {
		ModClass mc = ModClass.scan(classBytes);
		List<String> present = new ArrayList<>();
		if (!mc.isMixin()) return present;
		List<ClassInfo> targets = new ArrayList<>();

		for (String t : mc.mixinTargets) {
			ClassInfo info = index.get(t);

			if (info == null) {
				// A mixin into another mod's class is normally guarded by that mod being installed.
				if (dedicatedServer && Linker.isClientOnly(t)) report.problem(Report.Kind.CLIENT_ONLY, Linker.pretty(t), mc.name);
				else if (Linker.isGameName(t)) report.problem(Report.Kind.MIXIN_TARGET, "class " + Linker.pretty(t), mc.name);
			} else {
				targets.add(info);
				present.add(t);
			}
		}

		if (targets.isEmpty()) return present;
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		for (MethodNode mn : cn.methods) {
			for (AnnotationNode an : both(mn.visibleAnnotations, mn.invisibleAnnotations)) {
				if (an.desc.equals(SHADOW)) {
					String name = stripPrefix(mn.name, an);
					if (!hasMember(index, targets, ClassInfo.methodKey(name, mn.desc), true)) {
						report.problem(Report.Kind.MIXIN_TARGET, "@Shadow method " + name + mn.desc + " in " + names(targets), mc.name);
					}
				} else if (an.desc.equals(OVERWRITE)) {
					if (!hasMember(index, targets, ClassInfo.methodKey(mn.name, mn.desc), true)) {
						report.problem(Report.Kind.MIXIN_TARGET, "@Overwrite method " + mn.name + mn.desc + " in " + names(targets), mc.name);
					}
				} else if (an.desc.startsWith("Lorg/spongepowered/asm/mixin/") || an.desc.contains("/mixinextras/")) {
					injectorMethods(an, targets, mc.name, report);
				}
			}
		}

		for (FieldNode fn : cn.fields) {
			for (AnnotationNode an : both(fn.visibleAnnotations, fn.invisibleAnnotations)) {
				if (!an.desc.equals(SHADOW)) continue;
				String name = stripPrefix(fn.name, an);

				if (!hasMember(index, targets, ClassInfo.fieldKey(name, fn.desc), false)) {
					report.problem(Report.Kind.MIXIN_TARGET, "@Shadow field " + name + " : " + fn.desc + " in " + names(targets), mc.name);
				}
			}
		}

		return present;
	}

	/** {@code @Inject(method = "tick")} and friends: the named method must be declared by a target. */
	private static void injectorMethods(AnnotationNode an, List<ClassInfo> targets, String user, Report report) {
		if (an.values == null || an.desc.endsWith("/Definition;")) return;

		for (int i = 0; i + 1 < an.values.size(); i += 2) {
			if (!"method".equals(an.values.get(i))) continue;
			Object v = an.values.get(i + 1);
			List<?> list = v instanceof List<?> l ? l : List.of(v);

			for (Object o : list) {
				if (!(o instanceof String str)) continue;
				Selector s = Selector.parse(str);
				if (s == null || s.owner != null || s.name.isEmpty() || !s.quantifier.isEmpty() || s.fieldDesc) continue;
				boolean found = false;

				for (ClassInfo t : targets) {
					for (Map.Entry<String, Integer> m : t.methods.entrySet()) {
						String key = m.getKey();

						if (s.desc != null ? key.equals(s.name + s.desc) : key.startsWith(s.name + "(")) {
							found = true;
							break;
						}
					}

					if (found) break;
				}

				if (!found) {
					report.problem(Report.Kind.MIXIN_TARGET, "method " + str + " in " + names(targets)
							+ "  (" + simpleAnnotation(an.desc) + " has nothing to inject into)", user);
				}
			}
		}
	}

	private static boolean hasMember(LiveIndex index, List<ClassInfo> targets, String key, boolean method) {
		for (ClassInfo t : targets) {
			ClassInfo c = t;
			int depth = 0;

			// Shadows may refer to a member inherited by the target.
			while (c != null && depth++ < 64) {
				if ((method ? c.methods : c.fields).containsKey(key)) return true;
				ClassInfo next = c.superName == null ? null : index.get(c.superName);
				if (next == null && c.superName != null) return true; // unreadable parent: do not accuse
				c = next;
			}
		}

		return false;
	}

	private static String stripPrefix(String name, AnnotationNode shadow) {
		String prefix = "shadow$";

		if (shadow.values != null) {
			for (int i = 0; i + 1 < shadow.values.size(); i += 2) {
				if ("prefix".equals(shadow.values.get(i)) && shadow.values.get(i + 1) instanceof String p) prefix = p;
			}
		}

		return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
	}

	private static String names(List<ClassInfo> targets) {
		StringBuilder sb = new StringBuilder();

		for (ClassInfo t : targets) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(Linker.pretty(t.name));
		}

		return sb.toString();
	}

	private static String simpleAnnotation(String desc) {
		return "@" + desc.substring(desc.lastIndexOf('/') + 1, desc.length() - 1);
	}

	private static List<AnnotationNode> both(List<AnnotationNode> a, List<AnnotationNode> b) {
		List<AnnotationNode> out = new ArrayList<>();
		if (a != null) out.addAll(a);
		if (b != null) out.addAll(b);
		return out;
	}

	/** Lenient mode: turns an explicit {@code require = N} into {@code require = 0} so a miss is logged, not fatal. */
	public static boolean relaxRequire(ClassNode cn) {
		boolean changed = false;

		for (MethodNode mn : cn.methods) {
			for (AnnotationNode an : both(mn.visibleAnnotations, mn.invisibleAnnotations)) {
				if (an.values == null || !(an.desc.startsWith("Lorg/spongepowered/asm/mixin/") || an.desc.contains("/mixinextras/"))) continue;

				for (int i = 0; i + 1 < an.values.size(); i += 2) {
					if ("require".equals(an.values.get(i)) && an.values.get(i + 1) instanceof Integer n && n > 0) {
						an.values.set(i + 1, 0);
						changed = true;
					}
				}
			}
		}

		return changed;
	}

}
