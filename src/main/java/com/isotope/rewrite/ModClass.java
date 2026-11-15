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

	private ModClass(String name, String superName, String[] interfaces, List<String> mixinTargets) {
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

		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				header[0] = name;
				header[1] = superName;
				ifaces[0] = interfaces;
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

		return new ModClass(header[0], header[1], ifaces[0], targets);
	}
}
