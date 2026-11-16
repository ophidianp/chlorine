package com.isotope.rewrite;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

import com.isotope.model.RedirectTable.Member;

/**
 * Applies one {@link Hop} to one class.
 *
 * <p>ASM's {@link ClassRemapper} already knows every place a class file can mention a
 * class, method or field (instructions, descriptors, generic signatures, annotations,
 * stack frames, ...) and asks a {@link Remapper} what the new name is. Isotope's part is
 * answering those questions from the redirect table, plus three things plain renaming
 * cannot express:
 * <ol>
 * <li>calls to a method whose <i>signature</i> changed compatibly (the call is re-pointed
 *     and the result is popped or cast as needed);</li>
 * <li>lambdas: {@code invokedynamic} carries the interface method name as a plain string
 *     that ASM does not connect to any owner;</li>
 * <li>class names inside string constants ({@code Class.forName("net.minecraft...")}).</li>
 * </ol>
 */
public final class HopRemapper extends ClassRemapper {
	private final Hop hop;
	/** Counts what was changed, for the report. */
	public final int[] changes;

	/** Descriptions of the stubs that were applied, for the report (may be null). */
	private final java.util.Set<String> stubsUsed;

	public HopRemapper(ClassVisitor downstream, Hop hop, int[] changes) {
		this(downstream, hop, changes, null);
	}

	public HopRemapper(ClassVisitor downstream, Hop hop, int[] changes, java.util.Set<String> stubsUsed) {
		super(Opcodes.ASM9, downstream, new Names(hop, changes));
		this.hop = hop;
		this.changes = changes;
		this.stubsUsed = stubsUsed;
	}

	/** Pushes the "nothing" value of a type: null, 0, 0L, 0f, 0d, or nothing for void. */
	private static void pushDefault(MethodVisitor mv, Type t) {
		switch (t.getSort()) {
		case Type.VOID -> { }
		case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
		case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
		case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
		case Type.OBJECT, Type.ARRAY -> mv.visitInsn(Opcodes.ACONST_NULL);
		default -> mv.visitInsn(Opcodes.ICONST_0);
		}
	}

	private static void pop(MethodVisitor mv, Type t) {
		mv.visitInsn(t.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
	}

	private void noteStub(com.isotope.model.RedirectTable.Stub st) {
		changes[0]++;
		if (stubsUsed != null) stubsUsed.add(st.owner().replace('/', '.') + "." + st.name() + (st.field() ? "" : "(...)") + " - " + st.reason());
	}

	private static final class Names extends Remapper {
		private final Hop hop;
		private final int[] changes;

		Names(Hop hop, int[] changes) {
			super(Opcodes.ASM9);
			this.hop = hop;
			this.changes = changes;
		}

		@Override
		public String map(String internalName) {
			String mapped = hop.table.mapClass(internalName);
			if (!mapped.equals(internalName)) changes[0]++;
			return mapped;
		}

		@Override
		public String mapMethodName(String owner, String name, String descriptor) {
			Member rule = hop.resolveMethod(owner, name, descriptor);
			if (rule == null || rule.newName().equals(name)) return name;
			changes[0]++;
			return rule.newName();
		}

		@Override
		public String mapFieldName(String owner, String name, String descriptor) {
			Member rule = hop.resolveField(owner, name, descriptor);
			if (rule == null) return name;
			changes[0]++;
			return rule.newName();
		}

		@Override
		public String mapRecordComponentName(String owner, String name, String descriptor) {
			return mapFieldName(owner, name, descriptor);
		}
	}

	@Override
	protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
		return new MethodRemapper(api, methodVisitor, remapper) {
			@Override
			public void visitMethodInsn(int opcodeAndSource, String owner, String name, String descriptor, boolean isInterface) {
				com.isotope.model.RedirectTable.Stub stub = mv == null ? null : hop.resolveStub(false, owner, name, descriptor);

				if (stub != null) {
					// Removed API: drop the arguments (last first), the receiver, and push a default result.
					Type[] args = Type.getArgumentTypes(descriptor);
					for (int k = args.length - 1; k >= 0; k--) pop(mv, args[k]);
					if ((opcodeAndSource & 0xFF) != Opcodes.INVOKESTATIC) mv.visitInsn(Opcodes.POP);
					pushDefault(mv, Type.getReturnType(remapper.mapMethodDesc(descriptor)));
					noteStub(stub);
					return;
				}

				Member rule = hop.resolveMethod(owner, name, descriptor);

				if (rule != null && rule.newDesc() != null && mv != null) {
					String plain = remapper.mapMethodDesc(descriptor);

					if (!plain.equals(rule.newDesc())) {
						// The signature changed in a way the caller cannot notice. Emit the call with the
						// new signature straight to the output, skipping ASM's own (rename-only) mapping.
						mv.visitMethodInsn(opcodeAndSource, remapper.mapType(owner), rule.newName(), rule.newDesc(), isInterface);
						Type oldRet = Type.getReturnType(plain);
						Type newRet = Type.getReturnType(rule.newDesc());

						if (oldRet.getSort() == Type.VOID && newRet.getSort() != Type.VOID) {
							// The old method returned nothing; throw the new result away.
							mv.visitInsn(newRet.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
						} else if (rule.cast() && !oldRet.equals(newRet)) {
							// The new method returns a wider type; cast back to what the mod expects.
							mv.visitTypeInsn(Opcodes.CHECKCAST, oldRet.getSort() == Type.ARRAY ? oldRet.getDescriptor() : oldRet.getInternalName());
						}

						changes[0]++;
						return;
					}
				}

				super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
			}

			@Override
			public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
				com.isotope.model.RedirectTable.Stub stub = mv == null ? null : hop.resolveStub(true, owner, name, descriptor);

				if (stub != null) {
					Type t = Type.getType(remapper.mapDesc(descriptor));

					switch (opcode) {
					case Opcodes.GETSTATIC -> pushDefault(mv, t);
					case Opcodes.GETFIELD -> {
						mv.visitInsn(Opcodes.POP);
						pushDefault(mv, t);
					}
					case Opcodes.PUTSTATIC -> pop(mv, t);
					default -> { // PUTFIELD: value, then receiver
						pop(mv, t);
						mv.visitInsn(Opcodes.POP);
					}
					}

					noteStub(stub);
					return;
				}

				super.visitFieldInsn(opcode, owner, name, descriptor);
			}

			@Override
			public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
				// A lambda or method reference: `name` is the single abstract method of the interface
				// being implemented, the interface is the call site's return type, and the method's
				// (erased) signature is the first bootstrap argument.
				if (bootstrapMethodHandle.getOwner().equals("java/lang/invoke/LambdaMetafactory")
						&& bootstrapMethodArguments.length > 0 && bootstrapMethodArguments[0] instanceof Type samType
						&& samType.getSort() == Type.METHOD) {
					Type iface = Type.getReturnType(descriptor);

					if (iface.getSort() == Type.OBJECT) {
						Member rule = hop.resolveMethod(iface.getInternalName(), name, samType.getDescriptor());

						if (rule != null && !rule.newName().equals(name)) {
							name = rule.newName();
							changes[0]++;
						}
					}
				}

				super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
			}

			@Override
			public void visitLdcInsn(Object value) {
				if (value instanceof String s && looksLikeGameClass(s)) {
					boolean dotted = s.indexOf('/') < 0;
					String internal = dotted ? s.replace('.', '/') : s;
					String mapped = hop.table.mapClass(internal);

					if (!mapped.equals(internal)) {
						value = dotted ? mapped.replace('/', '.') : mapped;
						changes[0]++;
					}
				}

				super.visitLdcInsn(value);
			}
		};
	}

	/** Cheap filter so ordinary strings are never touched: only exact, fully qualified game class names. */
	private static boolean looksLikeGameClass(String s) {
		if (s.length() < 16 || s.length() > 200) return false;
		if (!(s.startsWith("net.minecraft.") || s.startsWith("net/minecraft/") || s.startsWith("com.mojang.") || s.startsWith("com/mojang/"))) return false;

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!(Character.isJavaIdentifierPart(c) || c == '.' || c == '/')) return false;
		}

		return true;
	}
}
