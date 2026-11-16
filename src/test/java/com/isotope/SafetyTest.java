package com.isotope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import com.isotope.link.Linker;
import com.isotope.link.LiveIndex;
import com.isotope.link.Report;
import com.isotope.link.StartupPath;
import com.isotope.model.Hierarchy;
import com.isotope.model.RedirectTable;
import com.isotope.rewrite.ClassRewriter;
import com.isotope.rewrite.Hop;
import com.isotope.rewrite.ModClass;

/** The parts of Isotope that decide whether a rewritten mod is safe to load. */
class SafetyTest {
	private static byte[] cls(String name, String superName, String[] interfaces, int access, java.util.function.Consumer<ClassWriter> body) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, access, name, null, superName, interfaces);
		body.accept(cw);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void method(ClassWriter cw, int access, String name, String desc, java.util.function.Consumer<MethodVisitor> code) {
		MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
		mv.visitCode();
		code.accept(mv);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static Hop stubHop(byte[]... mods) throws Exception {
		RedirectTable t = RedirectTable.read(new StringReader("""
				isotope-redirects 1
				s m net/minecraft/Base gone ()Ljava/lang/Object; removed for the test
				s f net/minecraft/Base CODEC Ljava/lang/Object; removed for the test
				"""));
		Hierarchy h = Hierarchy.read(new StringReader("net/minecraft/Base -\nnet/minecraft/Sub net/minecraft/Base\n"));
		Map<String, ModClass> m = new HashMap<>();
		for (byte[] b : mods) m.put(ModClass.scan(b).name, ModClass.scan(b));
		return new Hop(t, h, m);
	}

	private static List<Integer> opcodes(byte[] bytes, String method) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		MethodNode mn = cn.methods.stream().filter(x -> x.name.equals(method)).findFirst().orElseThrow();
		List<Integer> out = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) if (i.getOpcode() >= 0) out.add(i.getOpcode());
		return out;
	}

	@Test
	void stubReplacesCallsAndFieldReadsOfRemovedApisWithNull() throws Exception {
		byte[] in = cls("mod/User", "java/lang/Object", null, Opcodes.ACC_PUBLIC, cw -> method(cw, Opcodes.ACC_STATIC, "go", "()V", mv -> {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/Sub", "gone", "()Ljava/lang/Object;", false); // found through Sub's parent
			mv.visitInsn(Opcodes.POP);
			mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/Sub", "CODEC", "Ljava/lang/Object;");
			mv.visitInsn(Opcodes.POP);
			mv.visitInsn(Opcodes.RETURN);
		}));
		Set<String> used = new HashSet<>();
		byte[] out = ClassRewriter.rewrite(in, stubHop(in), new int[1], used);
		assertEquals(List.of(Opcodes.ACONST_NULL, Opcodes.POP, Opcodes.ACONST_NULL, Opcodes.POP, Opcodes.RETURN), opcodes(out, "go"));
		assertEquals(2, used.size());
	}

	@Test
	void aModsOwnFieldIsNeverStubbed() throws Exception {
		byte[] block = cls("mod/MyBlock", "net/minecraft/Base", null, Opcodes.ACC_PUBLIC, cw -> {
			cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC, "CODEC", "Ljava/lang/Object;", null, null).visitEnd();
			method(cw, Opcodes.ACC_STATIC, "go", "()V", mv -> {
				mv.visitFieldInsn(Opcodes.GETSTATIC, "mod/MyBlock", "CODEC", "Ljava/lang/Object;");
				mv.visitInsn(Opcodes.POP);
				mv.visitInsn(Opcodes.RETURN);
			});
		});
		assertEquals(List.of(Opcodes.GETSTATIC, Opcodes.POP, Opcodes.RETURN), opcodes(ClassRewriter.rewrite(block, stubHop(block), new int[1]), "go"));
	}

	private static LiveIndex index(Map<String, byte[]> live) {
		return new LiveIndex(List.of(live::get, n -> {
			try (var in = ClassLoader.getSystemResourceAsStream(n + ".class")) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException e) {
				return null;
			}
		}));
	}

	@Test
	void implementingANowSealedInterfaceIsFatal() {
		ClassWriter sealed = new ClassWriter(0);
		sealed.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "net/minecraft/Holder", null, "java/lang/Object", null);
		sealed.visitPermittedSubclass("net/minecraft/Holder$Direct");
		sealed.visitEnd();
		Map<String, byte[]> live = new HashMap<>();
		live.put("net/minecraft/Holder", sealed.toByteArray());
		byte[] mod = cls("mod/MyHolder", "java/lang/Object", new String[] {"net/minecraft/Holder"}, Opcodes.ACC_PUBLIC, cw -> { });
		live.put("mod/MyHolder", mod);
		Report report = new Report("t.jar");
		new Linker(index(live), report).check(mod);
		assertTrue(report.render().contains("is now sealed"), report.render());
		assertTrue(report.hasFatal());
	}

	@Test
	void startupPathFollowsInitializerIntoCreatedOverridesOnly() {
		// onInitialize -> Helper.run() (virtual) -> HelperImpl.run() calls a game method that is gone.
		// UnusedImpl overrides run() too, but the mod never creates it, so it must not be blamed.
		Map<String, byte[]> own = new HashMap<>();
		own.put("mod/Init", cls("mod/Init", "java/lang/Object", null, Opcodes.ACC_PUBLIC, cw -> method(cw, Opcodes.ACC_PUBLIC, "onInitialize", "()V", mv -> {
			mv.visitTypeInsn(Opcodes.NEW, "mod/HelperImpl");
			mv.visitInsn(Opcodes.DUP);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "mod/HelperImpl", "<init>", "()V", false);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "mod/Helper", "run", "()V", false);
			mv.visitInsn(Opcodes.RETURN);
		})));
		own.put("mod/Helper", cls("mod/Helper", "java/lang/Object", null, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
				cw -> cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd()));
		for (String impl : List.of("mod/HelperImpl", "mod/UnusedImpl")) {
			own.put(impl, cls(impl, "mod/Helper", null, Opcodes.ACC_PUBLIC, cw -> method(cw, Opcodes.ACC_PUBLIC, "run", "()V", mv -> mv.visitInsn(Opcodes.RETURN))));
		}

		Map<String, List<String>> problems = new HashMap<>();
		problems.put("mod/HelperImpl run()V", List.of("net.minecraft.Gone"));
		problems.put("mod/UnusedImpl run()V", List.of("net.minecraft.AlsoGone"));
		Map<String, List<String>> found = StartupPath.find(own, List.of("mod/Init"), problems, false);
		assertEquals(Set.of("mod.HelperImpl.run"), found.keySet());
		assertFalse(found.containsKey("mod.UnusedImpl.run"));
	}
}
