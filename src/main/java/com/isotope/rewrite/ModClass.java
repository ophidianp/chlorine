package com.isotope.rewrite;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * What Isotope needs to know about one class of the mod being rewritten: its parents and,
 * if it is a mixin, which game classes it is mixed into.
 */
public final class ModClass {
	public static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

	public final String name;
	public final String superName;
	public final String[] interfaces;
	/** Internal names of the {@code @Mixin} targets; empty for ordinary classes. */
	public final List<String> mixinTargets;
	/** Members this class declares itself: {@code name+desc} for methods, {@code name:desc} for fields. */
	public final java.util.Set<String> declared;

	private ModClass(String name, String superName, String[] interfaces, List<String> mixinTargets, java.util.Set<String> declared) {
		this.declared = declared;
		this.name = name;
		this.superName = superName;
		this.interfaces = interfaces == null ? new String[0] : interfaces;
		this.mixinTargets = mixinTargets;
	}

	public boolean isMixin() {
		return !mixinTargets.isEmpty();
	}

	public static ModClass scan(byte[] classBytes) {
		String[] header = new String[2];
		String[][] ifaces = new String[1][];
		List<String> targets = new ArrayList<>();
		java.util.Set<String> declared = new java.util.HashSet<>();

		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				header[0] = name;
				header[1] = superName;
				ifaces[0] = interfaces;
			}

			@Override
			public org.objectweb.asm.FieldVisitor visitField(int access, String fname, String fdesc, String signature, Object value) {
				declared.add(fname + ':' + fdesc);
				return null;
			}

			@Override
			public org.objectweb.asm.MethodVisitor visitMethod(int access, String mname, String mdesc, String signature, String[] exceptions) {
				declared.add(mname + mdesc);
				return null;
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (!descriptor.equals(MIXIN_DESC)) return null;

				// @Mixin(value = {Foo.class}, targets = {"net.minecraft.Bar"})
				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitArray(String arrayName) {
						return new AnnotationVisitor(Opcodes.ASM9) {
							@Override
							public void visit(String ignored, Object value) {
								if (value instanceof Type t) targets.add(t.getInternalName());
								else if (value instanceof String s) targets.add(s.replace('.', '/'));
							}
						};
					}
				};
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		return new ModClass(header[0], header[1], ifaces[0], targets, declared);
	}
}
