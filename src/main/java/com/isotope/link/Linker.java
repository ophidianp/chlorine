package com.isotope.link;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import com.isotope.model.ClassInfo;

/**
 * The pre-flight check: does every reference in a rewritten class resolve against the
 * classes that will really be there?
 *
 * <p>The JVM links lazily. A call to a method that no longer exists does not fail when
 * the mod loads; it throws {@code NoSuchMethodError} the first time that line runs,
 * maybe an hour into a play session. The linker here does the same resolution the JVM
 * would do, for every instruction, up front, and writes what would fail into the
 * {@link Report}.
 *
 * <p>It also repairs one thing: when a game class became an interface (or the other
 * way round) the JVM demands a different call instruction ({@code invokeinterface} vs
 * {@code invokevirtual}). The call is otherwise identical, so it is fixed in place.
 */
public final class Linker {
	private static final int FOUND = 0;
	private static final int MISSING = 1;
	private static final int UNKNOWN = 2;

	private final LiveIndex index;
	private final Report report;
	/** owner name desc -> {status, access} */
	private final Map<String, int[]> memberCache = new HashMap<>();

	public Linker(LiveIndex index, Report report) {
		this.index = index;
		this.report = report;
	}

	public static boolean isGameName(String internalName) {
		return internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/") || internalName.startsWith("net/fabricmc/");
	}

	/** Checks one class; returns the same array unless a call had to be repaired. */
	public byte[] check(byte[] classBytes) {
		ClassReader reader = new ClassReader(classBytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		int[] repaired = new int[1];
		String[] self = new String[1];

		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				self[0] = name;
				checkShape(name, access, superName, interfaces);
				super.visit(version, access, name, signature, superName, interfaces);
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				return new MethodVisitor(Opcodes.ASM9, mv) {
					@Override
					public void visitTypeInsn(int opcode, String type) {
						checkClass(type, self[0]);
						super.visitTypeInsn(opcode, type);
					}

					@Override
					public void visitMultiANewArrayInsn(String desc, int numDimensions) {
						checkClass(desc, self[0]);
						super.visitMultiANewArrayInsn(desc, numDimensions);
					}

					@Override
					public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
						if (type != null) checkClass(type, self[0]);
						super.visitTryCatchBlock(start, end, handler, type);
					}

					@Override
					public void visitLdcInsn(Object value) {
						if (value instanceof Type t && (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY)) {
							checkClass(t.getSort() == Type.ARRAY ? t.getDescriptor() : t.getInternalName(), self[0]);
						}

						super.visitLdcInsn(value);
					}

					@Override
					public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
						if (checkClass(owner, self[0])) {
							int[] r = findMember(owner, fname, fdesc, false);

							if (r[0] == MISSING) {
								report.problem(Report.Kind.MISSING_FIELD, pretty(owner) + "." + fname + " : " + fdesc, self[0]);
							} else if (r[0] == FOUND) {
								boolean isStatic = (r[1] & Opcodes.ACC_STATIC) != 0;
								boolean wantsStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;

								if (isStatic != wantsStatic) {
									report.problem(Report.Kind.MISSING_FIELD, pretty(owner) + "." + fname + " : " + fdesc
											+ (isStatic ? "  (is now static)" : "  (is no longer static)"), self[0]);
								}
							}
						}

						super.visitFieldInsn(opcode, owner, fname, fdesc);
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean isInterface) {
						if (checkClass(owner, self[0]) && owner.charAt(0) != '[' && !isSignaturePolymorphic(owner)) {
							int[] r = findMember(owner, mname, mdesc, true);

							if (r[0] == MISSING) {
								report.problem(Report.Kind.MISSING_METHOD, pretty(owner) + "." + mname + mdesc, self[0]);
							} else if (r[0] == FOUND) {
								boolean isStatic = (r[1] & Opcodes.ACC_STATIC) != 0;

								if (isStatic != (opcode == Opcodes.INVOKESTATIC)) {
									report.problem(Report.Kind.MISSING_METHOD, pretty(owner) + "." + mname + mdesc
											+ (isStatic ? "  (is now static)" : "  (is no longer static)"), self[0]);
								}
							}

							// Class <-> interface: same call, different instruction.
							ClassInfo ownerInfo = index.get(owner);

							if (ownerInfo != null && ownerInfo.isInterface() != isInterface) {
								isInterface = ownerInfo.isInterface();
								if (opcode == Opcodes.INVOKEVIRTUAL && isInterface) opcode = Opcodes.INVOKEINTERFACE;
								else if (opcode == Opcodes.INVOKEINTERFACE && !isInterface) opcode = Opcodes.INVOKEVIRTUAL;
								repaired[0]++;
							}
						}

						super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
					}

					@Override
					public void visitInvokeDynamicInsn(String iname, String idesc, Handle bsm, Object... args) {
						// Lambdas and method references.
						if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && args.length > 0 && args[0] instanceof Type sam
								&& sam.getSort() == Type.METHOD) {
							Type iface = Type.getReturnType(idesc);

							if (iface.getSort() == Type.OBJECT && checkClass(iface.getInternalName(), self[0])) {
								int[] r = findMember(iface.getInternalName(), iname, sam.getDescriptor(), true);

								if (r[0] == MISSING) {
									report.problem(Report.Kind.MISSING_METHOD, pretty(iface.getInternalName()) + "." + iname + sam.getDescriptor()
											+ "  (implemented by a lambda)", self[0]);
								}
							}
						}

						for (int i = 0; i < args.length; i++) {
							if (args[i] instanceof Handle h) args[i] = checkHandle(h, self[0], repaired);
						}

						super.visitInvokeDynamicInsn(iname, idesc, bsm, args);
					}
				};
			}
		}, 0);

		checkAbstractMethods(self[0]);
		report.callsRepaired += repaired[0];
		return repaired[0] > 0 ? writer.toByteArray() : classBytes;
	}

	private Handle checkHandle(Handle h, String user, int[] repaired) {
		String owner = h.getOwner();
		if (!checkClass(owner, user) || owner.charAt(0) == '[') return h;
		int tag = h.getTag();

		if (tag <= Opcodes.H_PUTSTATIC) { // field handles
			if (findMember(owner, h.getName(), h.getDesc(), false)[0] == MISSING) {
				report.problem(Report.Kind.MISSING_FIELD, pretty(owner) + "." + h.getName() + " : " + h.getDesc(), user);
			}

			return h;
		}

		if (findMember(owner, h.getName(), h.getDesc(), true)[0] == MISSING) {
			report.problem(Report.Kind.MISSING_METHOD, pretty(owner) + "." + h.getName() + h.getDesc() + "  (method reference)", user);
		}

		ClassInfo ownerInfo = index.get(owner);

		if (ownerInfo != null && ownerInfo.isInterface() != h.isInterface()) {
			boolean itf = ownerInfo.isInterface();
			if (tag == Opcodes.H_INVOKEVIRTUAL && itf) tag = Opcodes.H_INVOKEINTERFACE;
			else if (tag == Opcodes.H_INVOKEINTERFACE && !itf) tag = Opcodes.H_INVOKEVIRTUAL;
			repaired[0]++;
			return new Handle(tag, owner, h.getName(), h.getDesc(), itf);
		}

		return h;
	}

	/** MethodHandle.invoke and friends accept any signature; there is nothing to resolve. */
	private static boolean isSignaturePolymorphic(String owner) {
		return owner.equals("java/lang/invoke/MethodHandle") || owner.equals("java/lang/invoke/VarHandle");
	}

	/** Returns true if the class exists (so its members can be checked). */
	private boolean checkClass(String internalNameOrArray, String user) {
		String name = internalNameOrArray;

		if (name.charAt(0) == '[') {
			Type element = Type.getType(name).getElementType();
			if (element.getSort() != Type.OBJECT) return true; // int[] and friends
			name = element.getInternalName();
		}

		if (index.get(name) != null) return true;
		report.problem(isGameName(name) ? Report.Kind.MISSING_CLASS : Report.Kind.NOT_INSTALLED, pretty(name), user);
		return false;
	}

	private void checkShape(String name, int access, String superName, String[] interfaces) {
		if (superName != null) {
			ClassInfo sup = index.get(superName);

			if (sup == null) {
				report.problem(isGameName(superName) ? Report.Kind.MISSING_CLASS : Report.Kind.NOT_INSTALLED, pretty(superName), name);
			} else if (sup.isInterface() && (access & Opcodes.ACC_INTERFACE) == 0) {
				report.problem(Report.Kind.SHAPE_CHANGED, pretty(superName) + " is now an interface, but the mod extends it as a class", name);
			} else if ((sup.access & Opcodes.ACC_FINAL) != 0) {
				report.problem(Report.Kind.SHAPE_CHANGED, pretty(superName) + " is now final, but the mod extends it", name);
			}
		}

		if (interfaces != null) {
			for (String i : interfaces) {
				ClassInfo info = index.get(i);

				if (info == null) {
					report.problem(isGameName(i) ? Report.Kind.MISSING_CLASS : Report.Kind.NOT_INSTALLED, pretty(i), name);
				} else if (!info.isInterface()) {
					report.problem(Report.Kind.SHAPE_CHANGED, pretty(i) + " is now a class, but the mod implements it as an interface", name);
				}
			}
		}
	}

	/**
	 * Resolves a member the way the JVM does: the named class first, then its parents.
	 * {@code UNKNOWN} means a parent could not be read, so nothing can be concluded.
	 */
	private int[] findMember(String owner, String name, String desc, boolean method) {
		String key = owner + ' ' + name + ' ' + desc + (method ? " m" : " f");
		int[] cached = memberCache.get(key);
		if (cached != null) return cached;
		int[] result = new int[] {MISSING, 0};
		boolean unknown = walk(owner, method ? ClassInfo.methodKey(name, desc) : ClassInfo.fieldKey(name, desc), method, result, new HashSet<>());
		if (result[0] != FOUND && unknown) result[0] = UNKNOWN;
		memberCache.put(key, result);
		return result;
	}

	/** Returns true if some parent was unreadable. */
	private boolean walk(String cls, String key, boolean method, int[] result, Set<String> seen) {
		if (cls == null || !seen.add(cls)) return false;
		ClassInfo c = index.get(cls);
		if (c == null) return true;
		Integer access = (method ? c.methods : c.fields).get(key);

		if (access != null) {
			result[0] = FOUND;
			result[1] = access;
			return false;
		}

		boolean unknown = walk(c.superName, key, method, result, seen);
		if (result[0] == FOUND) return false;

		for (String i : c.interfaces) {
			unknown |= walk(i, key, method, result, seen);
			if (result[0] == FOUND) return false;
		}

		// Interfaces implicitly have Object's methods (toString() can be called on any interface type).
		if (c.isInterface() && method) {
			unknown |= walk("java/lang/Object", key, true, result, seen);
			if (result[0] == FOUND) return false;
		}

		return unknown;
	}

	/**
	 * A concrete mod class must implement every abstract method of its game parents. When a
	 * game method gained a parameter, the mod's old override no longer counts, and the first
	 * call throws AbstractMethodError. Find those up front.
	 */
	private void checkAbstractMethods(String className) {
		ClassInfo self = index.get(className);
		if (self == null || self.isInterface() || self.isAbstract()) return;
		Set<String> implemented = new HashSet<>();
		Map<String, String> required = new LinkedHashMap<>();
		Set<String> interfaces = new LinkedHashSet<>();
		ClassInfo c = self;

		while (c != null) {
			for (Map.Entry<String, Integer> m : c.methods.entrySet()) {
				int acc = m.getValue();
				if ((acc & Opcodes.ACC_STATIC) != 0) continue;
				if ((acc & Opcodes.ACC_ABSTRACT) == 0) implemented.add(m.getKey());
				else if (!implemented.contains(m.getKey())) required.putIfAbsent(m.getKey(), c.name);
			}

			for (String i : c.interfaces) if (!collectInterfaces(i, interfaces)) return; // unreadable parent: cannot judge
			if (c.superName == null) break;
			ClassInfo next = index.get(c.superName);
			if (next == null) return;
			c = next;
		}

		Set<String> defaults = new HashSet<>();

		for (String i : interfaces) {
			ClassInfo info = index.get(i);

			for (Map.Entry<String, Integer> m : info.methods.entrySet()) {
				int acc = m.getValue();
				if ((acc & Opcodes.ACC_STATIC) != 0) continue;
				if ((acc & Opcodes.ACC_ABSTRACT) == 0) defaults.add(m.getKey());
				else required.putIfAbsent(m.getKey(), i);
			}
		}

		for (Map.Entry<String, String> r : required.entrySet()) {
			if (implemented.contains(r.getKey()) || defaults.contains(r.getKey())) continue;
			if (!isGameName(r.getValue())) continue; // only speak for the game's API
			report.problem(Report.Kind.NOT_IMPLEMENTED, pretty(r.getValue()) + "." + r.getKey(), className);
		}
	}

	private boolean collectInterfaces(String name, Set<String> out) {
		if (out.contains(name)) return true;
		ClassInfo info = index.get(name);
		if (info == null) return false;
		out.add(name);
		for (String i : info.interfaces) if (!collectInterfaces(i, out)) return false;
		return true;
	}

	static String pretty(String internalName) {
		return internalName.replace('/', '.');
	}
}
