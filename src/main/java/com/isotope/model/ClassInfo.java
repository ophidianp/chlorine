package com.isotope.model;

import java.util.LinkedHashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The shape of one class: its name, parents and member signatures, with no code.
 *
 * <p>Everything Isotope decides ("does this method still exist?", "is this now an
 * interface?", "which parent declares this?") is answered from these. Names are JVM
 * internal names ({@code net/minecraft/world/level/Level}).
 */
public final class ClassInfo {
	public final String name;
	/** Null only for java/lang/Object. */
	public final String superName;
	public final String[] interfaces;
	public final int access;
	/** Key: {@code name + desc}, e.g. {@code tick()V}. Value: access flags. */
	public final Map<String, Integer> methods;
	/** Key: {@code name + ':' + desc}, e.g. {@code level:Lnet/minecraft/world/level/Level;}. */
	public final Map<String, Integer> fields;
	/** For a sealed class or interface: the only classes allowed to extend / implement it. Empty if not sealed. */
	public final java.util.List<String> permittedSubclasses;

	public ClassInfo(String name, String superName, String[] interfaces, int access,
			Map<String, Integer> methods, Map<String, Integer> fields) {
		this.name = name;
		this.superName = superName;
		this.interfaces = interfaces == null ? new String[0] : interfaces;
		this.access = access;
		this.methods = methods;
		this.fields = fields;
		this.permittedSubclasses = java.util.List.of();
	}

	private ClassInfo(ClassInfo base, java.util.List<String> permitted) {
		this.name = base.name;
		this.superName = base.superName;
		this.interfaces = base.interfaces;
		this.access = base.access;
		this.methods = base.methods;
		this.fields = base.fields;
		this.permittedSubclasses = permitted;
	}

	public boolean isSealed() {
		return !permittedSubclasses.isEmpty();
	}

	public boolean isInterface() {
		return (access & Opcodes.ACC_INTERFACE) != 0;
	}

	public boolean isAbstract() {
		return (access & Opcodes.ACC_ABSTRACT) != 0;
	}

	public static String methodKey(String name, String desc) {
		return name + desc;
	}

	public static String fieldKey(String name, String desc) {
		return name + ':' + desc;
	}

	/** Reads only the structure of a class file; method bodies are skipped, so this is cheap. */
	public static ClassInfo read(byte[] classBytes) {
		Map<String, Integer> methods = new LinkedHashMap<>();
		Map<String, Integer> fields = new LinkedHashMap<>();
		String[] header = new String[2];
		String[][] ifaces = new String[1][];
		java.util.List<String> permitted = new java.util.ArrayList<>();
		int[] acc = new int[1];

		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				header[0] = name;
				header[1] = superName;
				ifaces[0] = interfaces;
				acc[0] = access;
			}

			@Override
			public void visitPermittedSubclass(String permittedSubclass) {
				permitted.add(permittedSubclass);
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				fields.put(fieldKey(name, descriptor), access);
				return null;
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				methods.put(methodKey(name, descriptor), access);
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		ClassInfo info = new ClassInfo(header[0], header[1], ifaces[0], acc[0], methods, fields);
		return permitted.isEmpty() ? info : new ClassInfo(info, java.util.List.copyOf(permitted));
	}
}
