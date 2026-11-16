package com.isotope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import com.isotope.link.Linker;
import com.isotope.link.LiveIndex;
import com.isotope.link.Report;
import com.isotope.model.Hierarchy;
import com.isotope.model.RedirectTable;
import com.isotope.rewrite.AccessWideners;
import com.isotope.rewrite.ClassRewriter;
import com.isotope.rewrite.Hop;
import com.isotope.rewrite.ModClass;
import com.isotope.rewrite.Selector;
import com.isotope.util.Json;

/**
 * Exercises the rewrite on small hand-built classes. The "game" here is three made-up
 * classes ({@code net/minecraft/Base}, {@code Sub extends Base}, {@code Util}) so each
 * rule kind can be checked in isolation.
 */
class RewriteTest {
	private static final String TABLE = """
			isotope-redirects 1
			from A
			to B
			c net/minecraft/Util net/minecraft/util/Helper
			m net/minecraft/Base getX ()I x
			m net/minecraft/Base secret ()V hidden private
			m net/minecraft/Base build ()V build ()Lnet/minecraft/Base;
			m net/minecraft/Base widen ()Lnet/minecraft/Sub; widen ()Lnet/minecraft/Base; cast
			m net/minecraft/Callback run (I)V accept
			f net/minecraft/Base count I total
			""";

	private static Hop hop(byte[]... modClasses) throws Exception {
		RedirectTable table = RedirectTable.read(new StringReader(TABLE));
		Hierarchy old = Hierarchy.read(new StringReader("""
				net/minecraft/Base -
				net/minecraft/Sub net/minecraft/Base
				net/minecraft/Util -
				net/minecraft/Callback! -
				"""));
		Map<String, ModClass> mods = new HashMap<>();

		for (byte[] b : modClasses) {
			ModClass mc = ModClass.scan(b);
			mods.put(mc.name, mc);
		}

		return new Hop(table, old, mods);
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	private static MethodNode method(ClassNode cn, String name) {
		return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static List<AbstractInsnNode> insns(MethodNode mn) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) if (i.getOpcode() >= 0) out.add(i);
		return out;
	}

	/** class mod/Caller { static void go(Sub s) { ...the given calls... } } */
	private static byte[] caller(java.util.function.Consumer<MethodVisitor> body) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/Caller", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "go", "(Lnet/minecraft/Sub;)V", null, null);
		mv.visitCode();
		body.accept(mv);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void renameIsFoundThroughTheSubclassNamedInBytecode() throws Exception {
		byte[] in = caller(mv -> {
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Sub", "getX", "()I", false); // declared on Base
			mv.visitInsn(Opcodes.POP);
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/Sub", "count", "I");
			mv.visitInsn(Opcodes.POP);
		});
		int[] changes = new int[1];
		ClassNode out = node(ClassRewriter.rewrite(in, hop(in), changes));
		List<AbstractInsnNode> i = insns(method(out, "go"));
		assertEquals("x", ((MethodInsnNode) i.get(1)).name);
		assertEquals("net/minecraft/Sub", ((MethodInsnNode) i.get(1)).owner, "the owner the mod named is kept");
		assertEquals("total", ((FieldInsnNode) i.get(4)).name);
		assertTrue(changes[0] >= 2);
	}

	@Test
	void privateRulesAreNotInheritedBySubclasses() throws Exception {
		byte[] in = caller(mv -> {
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Sub", "secret", "()V", false);
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Base", "secret", "()V", false);
		});
		ClassNode out = node(ClassRewriter.rewrite(in, hop(in), new int[1]));
		List<AbstractInsnNode> i = insns(method(out, "go"));
		assertEquals("secret", ((MethodInsnNode) i.get(1)).name);
		assertEquals("hidden", ((MethodInsnNode) i.get(3)).name);
	}

	@Test
	void movedClassIsRenamedEverywhereIncludingStrings() throws Exception {
		byte[] in = caller(mv -> {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/Util", "now", "()J", false);
			mv.visitInsn(Opcodes.POP2);
			mv.visitLdcInsn("net.minecraft.Util");
			mv.visitInsn(Opcodes.POP);
			mv.visitLdcInsn("just some text about net.minecraft.Util");
			mv.visitInsn(Opcodes.POP);
		});
		ClassNode out = node(ClassRewriter.rewrite(in, hop(in), new int[1]));
		List<AbstractInsnNode> i = insns(method(out, "go"));
		assertEquals("net/minecraft/util/Helper", ((MethodInsnNode) i.get(0)).owner);
		assertEquals("net.minecraft.util.Helper", ((org.objectweb.asm.tree.LdcInsnNode) i.get(2)).cst);
		assertEquals("just some text about net.minecraft.Util", ((org.objectweb.asm.tree.LdcInsnNode) i.get(4)).cst, "prose is never touched");
	}

	@Test
	void changedReturnTypeIsPoppedOrCast() throws Exception {
		byte[] in = caller(mv -> {
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Base", "build", "()V", false);
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Base", "widen", "()Lnet/minecraft/Sub;", false);
			mv.visitInsn(Opcodes.POP);
		});
		ClassNode out = node(ClassRewriter.rewrite(in, hop(in), new int[1]));
		List<AbstractInsnNode> i = insns(method(out, "go"));
		assertEquals("()Lnet/minecraft/Base;", ((MethodInsnNode) i.get(1)).desc);
		assertEquals(Opcodes.POP, i.get(2).getOpcode(), "void became a return value: it is dropped");
		assertEquals("()Lnet/minecraft/Base;", ((MethodInsnNode) i.get(4)).desc);
		assertEquals(Opcodes.CHECKCAST, i.get(5).getOpcode(), "wider return type: cast back to what the mod expects");
	}

	@Test
	void untouchedClassComesBackAsTheSameBytes() throws Exception {
		byte[] in = caller(mv -> mv.visitInsn(Opcodes.NOP));
		assertSame(in, ClassRewriter.rewrite(in, hop(in), new int[1]));
	}

	/** {@literal @}Mixin(Base.class) class mod/BaseMixin { @Shadow int count; @Inject(method="getX", at=@At(target=...)) void hook() } */
	private static byte[] mixin() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/BaseMixin", null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor value = mixin.visitArray("value");
		value.visit(null, Type.getObjectType("net/minecraft/Base"));
		value.visitEnd();
		mixin.visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null)
				.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "hook", "()V", null, null);
		AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, "getX");
		methods.visit(null, "getX()I");
		methods.visitEnd();
		AnnotationVisitor ats = inject.visitArray("at");
		AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "INVOKE");
		at.visit("target", "Lnet/minecraft/Util;now()J");
		at.visitEnd();
		ats.visitEnd();
		inject.visit("require", 1);
		inject.visitEnd();
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, "mod/BaseMixin", "count", "I");
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void mixinStringsAndShadowsFollowTheTarget() throws Exception {
		byte[] in = mixin();
		ClassNode out = node(ClassRewriter.rewrite(in, hop(in), new int[1]));
		assertEquals("total", out.fields.get(0).name, "@Shadow field is renamed with the target's field");
		MethodNode hook = method(out, "hook");
		assertEquals("total", ((FieldInsnNode) insns(hook).get(1)).name, "and so is the code that reads it");
		AnnotationNode inject = hook.visibleAnnotations.get(0);
		assertEquals(List.of("x", "x()I"), inject.values.get(1));
		AnnotationNode at = (AnnotationNode) ((List<?>) inject.values.get(3)).get(0);
		assertEquals("Lnet/minecraft/util/Helper;now()J", at.values.get(3));
	}

	@Test
	void selectorRoundTrips() {
		for (String s : List.of("tick", "tick()V", "Lnet/minecraft/Foo;bar(I)V", "Lnet/minecraft/Foo;count:I", "render*", "net.minecraft.Foo.bar()V", "<init>")) {
			assertEquals(s, Selector.parse(s).toString());
		}

		Selector s = Selector.parse("Lnet/minecraft/Foo;bar(I)V");
		assertEquals("net/minecraft/Foo", s.owner);
		assertEquals("bar", s.name);
		assertEquals("(I)V", s.desc);
		assertNull(Selector.parse("@MyAnnotation"));
	}

	@Test
	void accessWidenerLinesAreRemapped() throws Exception {
		String in = "accessWidener v2 named\n# comment\naccessible class net/minecraft/Util\naccessible method net/minecraft/Base getX ()I\nmutable field net/minecraft/Base count I\naccessible method net/minecraft/Base other ()V\n";
		String out = AccessWideners.remap(in, hop(), new int[1]);
		assertTrue(out.contains("accessible\tclass\tnet/minecraft/util/Helper"));
		assertTrue(out.contains("accessible\tmethod\tnet/minecraft/Base\tx\t()I"));
		assertTrue(out.contains("mutable\tfield\tnet/minecraft/Base\ttotal\tI"));
		assertTrue(out.contains("accessible method net/minecraft/Base other ()V"), "untouched lines stay byte-identical");
		assertTrue(out.startsWith("accessWidener v2 named\n# comment\n"));
	}

	@Test
	void linkerReportsWhatIsGoneAndRepairsInterfaceCalls() throws Exception {
		// The "live game": Base is now an interface with only x(); Gone does not exist.
		ClassWriter base = new ClassWriter(0);
		base.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "net/minecraft/Base", null, "java/lang/Object", null);
		base.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "x", "()I", null, null).visitEnd();
		base.visitEnd();
		Map<String, byte[]> live = new HashMap<>();
		live.put("net/minecraft/Base", base.toByteArray());

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/User", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "go", "(Lnet/minecraft/Base;)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Base", "x", "()I", false); // compiled when Base was a class
		mv.visitInsn(Opcodes.POP);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Base", "removed", "()V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/Base", "toString", "()Ljava/lang/String;", false);
		mv.visitInsn(Opcodes.POP);
		mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/Gone");
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		byte[] user = cw.toByteArray();
		live.put("mod/User", user);

		List<LiveIndex.Source> sources = List.of(live::get, n -> {
			try (var in = ClassLoader.getSystemResourceAsStream(n + ".class")) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException e) {
				return null;
			}
		});
		Report report = new Report("test.jar");
		byte[] out = new Linker(new LiveIndex(sources), report).check(user);
		String text = report.render();
		assertTrue(text.contains("net.minecraft.Base.removed()V"), text);
		assertTrue(text.contains("net.minecraft.Gone"), text);
		assertFalse(text.contains("toString"), "Object methods are callable on interfaces");
		assertEquals(3, report.callsRepaired);
		MethodInsnNode call = (MethodInsnNode) insns(method(node(out), "go")).get(1);
		assertEquals(Opcodes.INVOKEINTERFACE, call.getOpcode());
		assertTrue(call.itf);
		assertEquals(2, report.seriousCount());
	}

	@Test
	void redirectTableRoundTripsAndInnerClassesFollowOuter() throws Exception {
		RedirectTable t = RedirectTable.read(new StringReader(TABLE));
		assertEquals("net/minecraft/util/Helper$Inner", t.mapClass("net/minecraft/Util$Inner"));
		assertEquals("net/minecraft/Other", t.mapClass("net/minecraft/Other"));
		StringWriter w = new StringWriter();
		t.write(w, List.of("test"));
		RedirectTable again = RedirectTable.read(new StringReader(w.toString()));
		assertEquals(t.methodCount(), again.methodCount());
		assertTrue(again.method("net/minecraft/Base", "secret", "()V").priv());
		assertTrue(again.method("net/minecraft/Base", "widen", "()Lnet/minecraft/Sub;").cast());
		assertNotNull(again.field("net/minecraft/Base", "count", "I"));
	}

	@Test
	void jsonKeepsOrderAndToleratesCommentsAndTrailingCommas() {
		Map<String, Object> m = Json.parseObject("{ // comment\n \"b\": [1, 2.5, true, null,], \"a\": {\"x\": \"y\\n\"}, }");
		assertEquals(List.of("b", "a"), new ArrayList<>(m.keySet()));
		assertEquals(m, Json.parseObject(Json.write(m)));
	}
}
