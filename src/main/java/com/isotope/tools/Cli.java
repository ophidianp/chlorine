package com.isotope.tools;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.isotope.boot.VersionDetect;
import com.isotope.link.LiveIndex;
import com.isotope.model.VersionGraph;
import com.isotope.rewrite.JarRewriter;

/**
 * Runs Isotope's rewrite and pre-flight check without starting the game.
 *
 * <pre>
 * java -cp isotope.jar:asm.jar:asm-commons.jar:asm-tree.jar:fabric-loader.jar com.isotope.tools.Cli \
 *     --game 26.3-client.jar --target 26.3 [--from 26.1.2] [--lib fabric-api.jar]... --out outDir  mod.jar...
 * </pre>
 * {@code --game} is the Minecraft jar the mods should run on; {@code --lib} adds jars the
 * mods depend on (other mods, libraries) so references into them can be checked too. Jars
 * nested inside a {@code --lib} are searched as well. Prints each mod's report.
 */
public final class Cli {
	public static void main(String[] args) throws IOException {
		List<Path> classpath = new ArrayList<>();
		List<Path> mods = new ArrayList<>();
		String target = null;
		String from = null;
		Path out = null;
		boolean lenient = true;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			case "--game", "--lib" -> classpath.add(Paths.get(args[++i]));
			case "--target" -> target = args[++i];
			case "--from" -> from = args[++i];
			case "--out" -> out = Paths.get(args[++i]);
			case "--strict-mixins" -> lenient = false;
			default -> mods.add(Paths.get(args[i]));
			}
		}

		if (classpath.isEmpty() || target == null || out == null || mods.isEmpty()) {
			System.err.println("usage: Cli --game <minecraft.jar> --target <version> [--from <version>] [--lib <jar>]... --out <dir> <mod.jar>...");
			System.exit(2);
		}

		Map<String, byte[]> classes = new HashMap<>();
		for (Path p : classpath) index(Files.readAllBytes(p), classes);
		List<LiveIndex.Source> sources = new ArrayList<>();
		sources.add(classes::get);
		sources.add(Cli::jdkClass);
		VersionGraph graph = VersionGraph.load();
		String normalized = graph.normalize(target);
		int problems = 0;

		for (Path mod : mods) {
			JarRewriter.Options o = new JarRewriter.Options();
			o.graph = graph;
			o.index = new LiveIndex(sources);
			o.targetVersion = normalized;
			o.targetLabel = target;
			o.forcedSource = from == null ? null : graph.normalize(from);
			o.lenientMixins = lenient;
			o.allowedVersions = VersionDetect.allowedVersions(graph);

			try {
				JarRewriter.Result r = JarRewriter.rewrite(mod, out.resolve(mod.getFileName().toString().replaceAll("\\.jar$", "")), o);
				System.out.print(r.report.render());
				problems += r.report.seriousCount();
			} catch (IOException | RuntimeException e) {
				System.out.println("== " + mod.getFileName() + "\n   FAILED: " + e + "\n");
				problems++;
			}
		}

		System.exit(problems == 0 ? 0 : 1);
	}

	/** Reads every class in a jar, and in jars nested inside it, into memory. */
	private static void index(byte[] jar, Map<String, byte[]> classes) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
			ZipEntry e;

			while ((e = zip.getNextEntry()) != null) {
				String n = e.getName();

				if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
					classes.putIfAbsent(n.substring(0, n.length() - 6), zip.readAllBytes());
				} else if (n.endsWith(".jar")) {
					index(zip.readAllBytes(), classes);
				}
			}
		}
	}

	private static byte[] jdkClass(String internalName) {
		try (InputStream in = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException e) {
			return null;
		}
	}
}
