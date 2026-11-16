package com.isotope.rewrite;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/** Runs one class through one hop: mixin strings first (old names), then the bytecode remap. */
public final class ClassRewriter {
	private ClassRewriter() { }

	/** Returns the same array when nothing in the class needed changing. */
	public static byte[] rewrite(byte[] classBytes, Hop hop, int[] changes) {
		return rewrite(classBytes, hop, changes, null);
	}

	/** @param stubsUsed collects a description of each stub applied (may be null) */
	public static byte[] rewrite(byte[] classBytes, Hop hop, int[] changes, java.util.Set<String> stubsUsed) {
		int before = changes[0];
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		MixinStrings.process(node, hop, changes);

		// COMPUTE_MAXS only recounts stack depth (needed when a POP is inserted after a call that
		// used to return void). Stack map frames are kept as written; renaming never invalidates them.
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(new HopRemapper(writer, hop, changes, stubsUsed));
		return changes[0] == before ? classBytes : writer.toByteArray();
	}
}
