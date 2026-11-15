package com.isotope.rewrite;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import com.isotope.model.RedirectTable.Member;

/**
 * Rewrites the game names that mixins carry as plain strings.
 *
 * <p>A mixin says where to inject with text, not with real references:
 * <pre>
 * {@literal @}Inject(method = "tick", at = {@literal @}At(value = "INVOKE",
 *         target = "Lnet/minecraft/world/entity/Entity;getX()D"))
 * </pre>
 * A bytecode remapper never sees those as names, so they are handled here, before the
 * main remap, while everything still has its old name. On 26.x there are no refmaps
 * (nothing is obfuscated), so these strings are the whole story.
 *
 * <p>Covered: {@code @Mixin(targets)}, the {@code method} list of every Mixin and
 * MixinExtras injector, {@code @At(target)} at any nesting depth, MixinExtras
 * {@code @Definition(method/field)}, and {@code @Accessor} / {@code @Invoker} names.
 * {@code @Shadow} and {@code @Overwrite} members need nothing here: they are real
 * members of the mixin class and {@link Hop} renames them through the mixin's targets.
 */
public final class MixinStrings {
	private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
	private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

	private final Hop hop;
	private final int[] changes;
	private final List<String> targets;
	private final String className;

	private MixinStrings(Hop hop, int[] changes, List<String> targets, String className) {
		this.hop = hop;
		this.changes = changes;
		this.targets = targets;
		this.className = className;
	}

	public static void process(ClassNode cn, Hop hop, int[] changes) {
		ModClass mc = hop.modClass(cn.name);
		if (mc == null || !mc.isMixin()) return;
		MixinStrings self = new MixinStrings(hop, changes, mc.mixinTargets, cn.name);

		for (AnnotationNode an : all(cn.visibleAnnotations, cn.invisibleAnnotations)) {
			if (an.desc.equals(ModClass.MIXIN_DESC)) self.mixinTargets(an);
		}

		for (MethodNode mn : cn.methods) {
			for (AnnotationNode an : all(mn.visibleAnnotations, mn.invisibleAnnotations)) {
				if (an.desc.equals(ACCESSOR_DESC)) self.accessor(an, mn, false);
				else if (an.desc.equals(INVOKER_DESC)) self.accessor(an, mn, true);
				else self.walk(an);
			}
		}

		for (FieldNode fn : cn.fields) {
			for (AnnotationNode an : all(fn.visibleAnnotations, fn.invisibleAnnotations)) self.walk(an);
		}
	}

	private static List<AnnotationNode> all(List<AnnotationNode> a, List<AnnotationNode> b) {
		List<AnnotationNode> out = new ArrayList<>();
		if (a != null) out.addAll(a);
		if (b != null) out.addAll(b);
		return out;
	}

	private static boolean isMixinAnnotation(String desc) {
		return desc.startsWith("Lorg/spongepowered/asm/mixin/") || desc.contains("/mixinextras/");
	}

	/** {@code @Mixin(targets = "net.minecraft.Foo")}: classes named by string because they are not public. */
	private void mixinTargets(AnnotationNode an) {
		if (an.values == null) return;

		for (int i = 0; i + 1 < an.values.size(); i += 2) {
			if (!"targets".equals(an.values.get(i)) || !(an.values.get(i + 1) instanceof List<?> list)) continue;
			List<Object> out = new ArrayList<>();

			for (Object o : list) {
				if (o instanceof String s) {
					boolean dotted = s.indexOf('/') < 0;
					String mapped = hop.table.mapClass(s.replace('.', '/'));
					String result = dotted ? mapped.replace('/', '.') : mapped;
					if (!result.equals(s)) changes[0]++;
					out.add(result);
				} else {
					out.add(o);
				}
			}

			an.values.set(i + 1, out);
		}
	}

	@SuppressWarnings("unchecked")
	private void walk(AnnotationNode an) {
		if (an.values == null) return;
		boolean mixin = isMixinAnnotation(an.desc);

		for (int i = 0; i + 1 < an.values.size(); i += 2) {
			String key = (String) an.values.get(i);
			Object value = an.values.get(i + 1);

			if (value instanceof AnnotationNode nested) {
				walk(nested);
			} else if (value instanceof List<?> list) {
				List<Object> out = (List<Object>) list;

				for (int k = 0; k < out.size(); k++) {
					Object o = out.get(k);

					if (o instanceof AnnotationNode nested) {
						walk(nested);
					} else if (mixin && o instanceof String s && isSelectorKey(an.desc, key)) {
						out.set(k, selector(s));
					}
				}
			} else if (mixin && value instanceof String s && isSelectorKey(an.desc, key)) {
				an.values.set(i + 1, selector(s));
			}
		}
	}

	/** Which annotation attributes hold a member reference. */
	private static boolean isSelectorKey(String annotationDesc, String key) {
		if (key.equals("method")) return true; // every injector, and @Definition
		if (key.equals("target")) return annotationDesc.equals(AT_DESC);
		if (key.equals("field")) return annotationDesc.endsWith("/Definition;");
		return false;
	}

	/**
	 * Remaps one member reference in Mixin's syntax: {@code [Lowner;|owner.]name[*|+|{n}][(desc)|:desc]}.
	 * Without an owner it refers to the mixin's own targets.
	 */
	String selector(String sel) {
		Selector s = Selector.parse(sel);
		if (s == null) return sel; // exotic syntax: leave alone

		if (s.isBareClass()) { // "net/minecraft/Foo", used by @At("NEW")
			String mapped = hop.table.mapClass(s.name);
			if (!mapped.equals(s.name)) changes[0]++;
			return mapped;
		}

		String oldDesc = s.desc;
		if (s.desc != null) s.desc = hop.mapDesc(s.desc);
		List<String> owners = s.owner != null ? List.of(s.owner) : targets;

		if (!s.name.isEmpty() && !s.name.equals("<init>") && !s.name.equals("<clinit>")) {
			for (String o : owners) {
				Member rule;

				if (oldDesc != null) {
					rule = s.fieldDesc ? hop.resolveField(o, s.name, oldDesc) : hop.resolveMethod(o, s.name, oldDesc);
				} else {
					// No signature given: only safe if every overload with this name agrees on the new name.
					rule = unanimous(hop.table.methodsNamed(o, s.name));
					if (rule == null) rule = unanimous(hop.table.fieldsNamed(o, s.name));
				}

				if (rule != null) {
					s.name = rule.newName();
					if (oldDesc != null && rule.newDesc() != null) s.desc = rule.newDesc();
					break;
				}
			}
		} else if (s.name.equals("<init>") && oldDesc != null && s.owner != null) {
			Member rule = hop.resolveMethod(s.owner, s.name, oldDesc);
			if (rule != null && rule.newDesc() != null) s.desc = rule.newDesc();
		}

		if (s.owner != null) s.owner = hop.table.mapClass(s.owner);
		String result = s.toString();
		if (!result.equals(sel)) changes[0]++;
		return result;
	}

	private static Member unanimous(List<Member> rules) {
		Member pick = null;

		for (Member m : rules) {
			if (pick != null && !pick.newName().equals(m.newName())) return null;
			pick = m;
		}

		return pick == null || pick.isIdentity() ? null : pick;
	}

	/**
	 * {@code @Accessor} / {@code @Invoker} usually take their target from the method's own
	 * name ({@code getFoo} means field {@code foo}). If the field was renamed, or the accessor
	 * method itself is about to be renamed, pin the target explicitly so Mixin does not have
	 * to guess from a name that no longer fits.
	 */
	private void accessor(AnnotationNode an, MethodNode mn, boolean invoker) {
		String explicit = null;
		int valueIndex = -1;

		if (an.values != null) {
			for (int i = 0; i + 1 < an.values.size(); i += 2) {
				if ("value".equals(an.values.get(i)) && an.values.get(i + 1) instanceof String s && !s.isEmpty()) {
					explicit = s;
					valueIndex = i + 1;
				}
			}
		}

		String target = explicit != null ? explicit : inferAccessorTarget(mn.name, invoker);
		if (target == null) return;
		String newTarget = target;

		for (String t : targets) {
			Member rule;

			if (invoker) {
				rule = target.equals("<init>") ? null : hop.resolveMethod(t, target, mn.desc);
			} else {
				Type ret = Type.getReturnType(mn.desc);
				Type[] args = Type.getArgumentTypes(mn.desc);
				String fieldDesc = ret.getSort() != Type.VOID ? ret.getDescriptor() : args.length == 1 ? args[0].getDescriptor() : null;
				rule = fieldDesc == null ? null : hop.resolveField(t, target, fieldDesc);
			}

			if (rule != null) {
				newTarget = rule.newName();
				break;
			}
		}

		boolean methodWillBeRenamed = hop.resolveMethod(className, mn.name, mn.desc) != null;
		if (newTarget.equals(target) && !(explicit == null && methodWillBeRenamed)) return;

		if (valueIndex >= 0) {
			an.values.set(valueIndex, newTarget);
		} else {
			if (an.values == null) an.values = new ArrayList<>();
			an.values.add("value");
			an.values.add(newTarget);
		}

		changes[0]++;
	}

	/** Mirrors Mixin's own rule: strip get/set/is (or call/invoke), then lower-case the first letter. */
	static String inferAccessorTarget(String methodName, boolean invoker) {
		String[] prefixes = invoker ? new String[] {"call", "invoke"} : new String[] {"get", "set", "is"};

		for (String p : prefixes) {
			if (methodName.startsWith(p) && methodName.length() > p.length() && Character.isUpperCase(methodName.charAt(p.length()))) {
				String rest = methodName.substring(p.length());
				int md = rest.indexOf("_$md");
				if (md > 0) rest = rest.substring(0, md);
				if (rest.equals(rest.toUpperCase())) return rest; // CONSTANT_STYLE names keep their case
				return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
			}
		}

		return null;
	}
}
