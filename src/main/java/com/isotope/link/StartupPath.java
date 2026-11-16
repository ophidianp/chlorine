package com.isotope.link;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Finds the problems that sit on code which certainly runs while the game starts.
 *
 * <p>Fabric calls every mod's initializer during startup, before the main menu or the
 * server's "Done". So a missing class or method in {@code onInitialize}, or in anything it
 * calls, or in a static initializer of a class it touches, is not "may crash later": it
 * is a crash on every launch. This follows the mod's own call graph from its entrypoints
 * and collects the {@link Linker} problems it meets.
 *
 * <p>A virtual call ({@code helper.doThing()}) is followed into the overriding methods of
 * mod classes that the mod creates somewhere ({@code new FabricHelper()}): the usual
 * "platform helper" pattern of multi-loader mods. Classes the mod never creates are
 * ignored, which keeps false alarms down.
 *
 * <p>Lambdas are followed only when created in a static initializer, where they are nearly
 * always codec builders that run immediately.
 *
 * <p>It errs towards silence: it does not follow other lambdas or event callbacks (they run
 * when the event fires, which may or may not be at startup) or reflection. What it reports is
 * reached unless an {@code if} in the mod skips it.
 */
public final class StartupPath {
	private StartupPath() { }

	private static final class Info {
		String superName;
		String[] interfaces = new String[0];
		final Set<String> methods = new HashSet<>();
		/** method "name+desc" -> things it calls / touches, as "owner name+desc". */
		final Map<String, List<String>> edges = new HashMap<>();
		/** Mod classes this class creates with {@code new}. */
		final Set<String> instantiates = new HashSet<>();
	}

	/**
	 * @param ownClasses       the mod's classes (internal name -> bytes, after rewriting)
	 * @param entrypointClasses classes named in the mod's entrypoints for this side
	 * @param problemsByMethod from {@link Linker#problemsByMethod}
	 * @return method ("owner.name") -> problems, in the order they are reached; empty if none
	 */
	public static Map<String, List<String>> find(Map<String, byte[]> ownClasses, List<String> entrypointClasses,
			Map<String, List<String>> problemsByMethod, boolean client) {
		Map<String, Info> infos = new HashMap<>();
		Set<String> created = new HashSet<>();
		Map<String, List<String>> subtypes = new HashMap<>();

		for (String c : ownClasses.keySet()) {
			Info info = info(c, ownClasses, infos);
			if (info == null) continue;
			created.addAll(info.instantiates);
			if (info.superName != null) subtypes.computeIfAbsent(info.superName, k -> new ArrayList<>()).add(c);
			for (String i : info.interfaces) subtypes.computeIfAbsent(i, k -> new ArrayList<>()).add(c);
		}

		Deque<String> queue = new ArrayDeque<>();
		Set<String> seen = new HashSet<>();
		Map<String, List<String>> found = new LinkedHashMap<>();

		for (String ep : entrypointClasses) {
			queue.add(ep + " <clinit>()V");
			queue.add(ep + " <init>()V");
			Info info = infos.get(ep);
			if (info == null) continue;

			// Initializer methods, whatever their parameters: Fabric's onInitialize(), an entrypoint
			// library's onInitialize(ModContainer), init(), onPreLaunch()...
			for (String m : info.methods) {
				String name = m.substring(0, m.indexOf('('));
				String lower = name.toLowerCase(java.util.Locale.ROOT);
				boolean initLike = lower.startsWith("oninit") || lower.equals("init") || lower.equals("initialize") || lower.equals("onprelaunch");
				if (!initLike || (client ? lower.contains("server") : lower.contains("client"))) continue;
				queue.add(ep + ' ' + m);
			}
		}

		while (!queue.isEmpty()) {
			String key = queue.poll();

			if (key.startsWith("V")) {
				// Virtual call: the declared target, plus overrides in subclasses the mod creates.
				key = key.substring(1);
				int sp = key.indexOf(' ');
				String m = key.substring(sp + 1);

				for (String sub : allSubtypes(key.substring(0, sp), subtypes)) {
					Info si = infos.get(sub);
					if (si != null && created.contains(sub) && si.methods.contains(m)) queue.add(sub + ' ' + m);
				}
			}

			int space = key.indexOf(' ');
			String owner = key.substring(0, space);
			String method = key.substring(space + 1);
			String declaring = resolve(owner, method, ownClasses, infos);
			if (declaring == null) continue; // not the mod's code (game, library) or not declared anywhere
			String real = declaring + ' ' + method;
			if (!seen.add(real)) continue;
			List<String> problems = problemsByMethod.get(real);
			if (problems != null && !problems.isEmpty()) found.put(declaring.replace('/', '.') + "." + method.substring(0, method.indexOf('(')), problems);
			queue.add(declaring + " <clinit>()V"); // running any method means its class was initialised
			queue.addAll(infos.get(declaring).edges.getOrDefault(method, List.of()));
		}

		return found;
	}

	private static Set<String> allSubtypes(String cls, Map<String, List<String>> subtypes) {
		Set<String> out = new HashSet<>();
		Deque<String> todo = new ArrayDeque<>(subtypes.getOrDefault(cls, List.of()));

		while (!todo.isEmpty()) {
			String c = todo.poll();
			if (out.add(c)) todo.addAll(subtypes.getOrDefault(c, List.of()));
		}

		return out;
	}

	/** Finds which mod class declares {@code method} when called on {@code owner} (walks superclasses). */
	private static String resolve(String owner, String method, Map<String, byte[]> own, Map<String, Info> infos) {
		String c = owner;

		for (int depth = 0; c != null && depth < 32; depth++) {
			Info info = info(c, own, infos);
			if (info == null) return null;
			if (info.methods.contains(method)) return c;
			if (method.startsWith("<")) return null; // constructors and static init are not inherited
			c = info.superName;
		}

		return null;
	}

	private static Info info(String cls, Map<String, byte[]> own, Map<String, Info> infos) {
		if (infos.containsKey(cls)) return infos.get(cls);
		byte[] bytes = own.get(cls);
		Info info = null;

		if (bytes != null) {
			Info in = new Info();
			info = in;

			try {
				new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
					@Override
					public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
						in.superName = superName;
						if (interfaces != null) in.interfaces = interfaces;
					}

					@Override
					public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
						String self = name + descriptor;
						in.methods.add(self);
						List<String> edges = new ArrayList<>();
						in.edges.put(self, edges);

						return new MethodVisitor(Opcodes.ASM9) {
							@Override
							public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
								if (!own.containsKey(owner)) return;
								boolean virtual = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
								edges.add((virtual ? "V" : "") + owner + ' ' + mname + mdesc);
							}

							@Override
							public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
								if ((opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) && own.containsKey(owner)) edges.add(owner + " <clinit>()V");
							}

							@Override
							public void visitInvokeDynamicInsn(String iname, String idesc, org.objectweb.asm.Handle bsm, Object... args) {
								// Lambdas made in a static initializer are nearly always run right away
								// (RecordCodecBuilder.mapCodec(i -> ...) and similar), so follow them there.
								// Elsewhere they are usually callbacks for later and are not followed.
								if (!name.equals("<clinit>") || !bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || args.length < 2) return;
								if (args[1] instanceof org.objectweb.asm.Handle h && own.containsKey(h.getOwner())) edges.add(h.getOwner() + ' ' + h.getName() + h.getDesc());
							}

							@Override
							public void visitTypeInsn(int opcode, String type) {
								if (opcode == Opcodes.NEW && own.containsKey(type)) {
									edges.add(type + " <clinit>()V");
									in.instantiates.add(type);
								}
							}
						};
					}
				}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			} catch (RuntimeException e) {
				info = null;
			}
		}

		infos.put(cls, info);
		return info;
	}
}
