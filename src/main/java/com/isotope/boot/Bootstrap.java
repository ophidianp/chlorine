package com.isotope.boot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

import com.isotope.link.LiveIndex;
import com.isotope.model.VersionGraph;
import com.isotope.rewrite.JarRewriter;

/**
 * One Isotope run, at game start:
 * find the jars in {@code isotope/mods}, rewrite each (or reuse last launch's result),
 * hand the results to Fabric Loader, and write {@code isotope/report.txt}.
 *
 * <p>Nothing in here may take the game down. Whatever goes wrong is logged and the game
 * starts without the affected mod.
 */
final class Bootstrap {
	private static boolean ran;

	private Bootstrap() { }

	private record Input(Path jar, String forcedVersion) { }

	static synchronized void run() {
		if (ran) return;
		ran = true;

		try {
			run0();
		} catch (Throwable t) {
			IsotopeLog.error("Isotope could not run and is disabled for this launch. The game is unaffected; "
					+ "mods in isotope/mods were not loaded.", t);
		}
	}

	private static void run0() throws Exception {
		FabricLoader loader = FabricLoader.getInstance();
		Path base = loader.getGameDir().resolve("isotope");
		Path modsDir = base.resolve("mods");
		Path cacheDir = base.resolve("cache");
		Files.createDirectories(modsDir);
		writeReadme(base.resolve("README.txt"));
		Config config = Config.load(loader.getConfigDir().resolve("isotope.properties"));

		if (!config.enabled) {
			IsotopeLog.info("disabled in config/isotope.properties");
			return;
		}

		VersionGraph graph = VersionGraph.load();
		List<Input> inputs = findInputs(modsDir, graph);

		if (inputs.isEmpty()) {
			IsotopeLog.info("no mods in " + modsDir + " - nothing to do");
			return;
		}

		long start = System.nanoTime();
		String running = LoaderBridge.minecraftVersion();
		String target = graph.normalize(running);
		String isotopeVersion = LoaderBridge.isotopeVersion();

		if (target == null) {
			IsotopeLog.warn("Minecraft " + running + " is not a version Isotope has tables for (" + graph.known()
					+ "). Mods will be checked and loaded, but nothing can be redirected.");
		}

		LiveIndex index = LoaderBridge.liveIndex();
		List<LoaderBridge.Candidate> candidates = new ArrayList<>();
		StringBuilder reportText = new StringBuilder();
		reportText.append("Isotope ").append(isotopeVersion).append(" on Minecraft ").append(running).append("\n")
				.append("This file is rewritten on every launch. 'used in' lists the mod's classes that would hit the problem.\n\n");
		Set<Path> liveCacheDirs = new HashSet<>();

		for (Input in : inputs) {
			String label = modsDir.relativize(in.jar()).toString();

			try {
				byte[] bytes = Files.readAllBytes(in.jar());
				String key = hash(bytes, isotopeVersion + "|" + running + "|" + config.lenientMixins + "|" + in.forcedVersion());
				Path outDir = cacheDir.resolve(stripJar(in.jar().getFileName().toString()) + "-" + key);
				Path done = outDir.resolve("isotope.done");
				liveCacheDirs.add(outDir);
				List<Path> jars = new ArrayList<>();

				if (config.cache && Files.exists(done)) {
					for (String name : Files.readAllLines(done, StandardCharsets.UTF_8)) {
						if (name.startsWith("summary: ")) IsotopeLog.info(name.substring(9) + "  (cached)");
						else if (!name.isBlank()) jars.add(outDir.resolve(name));
					}

					reportText.append(Files.readString(outDir.resolve("report.txt"), StandardCharsets.UTF_8));
				} else {
					deleteTree(outDir);
					JarRewriter.Options o = new JarRewriter.Options();
					o.graph = graph;
					o.index = index;
					o.targetVersion = target;
					o.targetLabel = running;
					o.forcedSource = in.forcedVersion();
					o.lenientMixins = config.lenientMixins;
					o.isotopeVersion = isotopeVersion;
					o.sourceDetector = LoaderBridge.sourceDetector(graph, target);
					JarRewriter.Result result = JarRewriter.rewrite(in.jar(), outDir, o);
					jars.addAll(result.jars);
					String rendered = result.report.render();
					Files.writeString(outDir.resolve("report.txt"), rendered, StandardCharsets.UTF_8);
					List<String> marker = new ArrayList<>();
					for (Path j : jars) marker.add(j.getFileName().toString());
					marker.add("summary: " + result.report.oneLine());
					Files.write(done, marker, StandardCharsets.UTF_8); // written last: marks the folder complete
					reportText.append(rendered);
					IsotopeLog.info(result.report.oneLine());
				}

				for (Path jar : jars) candidates.add(LoaderBridge.readCandidate(jar));
			} catch (Throwable t) {
				IsotopeLog.error("could not process " + label + "; it will not be loaded", t);
				reportText.append("== ").append(label).append("\n   FAILED: ").append(t).append("\n\n");
			}
		}

		List<String> messages = new ArrayList<>();
		List<LoaderBridge.Candidate> accepted = LoaderBridge.resolve(candidates, messages);
		for (String m : messages) IsotopeLog.info(m);
		LoaderBridge.inject(accepted);

		if (!messages.isEmpty()) {
			reportText.append("Loading decisions:\n");
			for (String m : messages) reportText.append("   ").append(m).append('\n');
		}

		Files.writeString(base.resolve("report.txt"), reportText.toString(), StandardCharsets.UTF_8);
		cleanCache(cacheDir, liveCacheDirs);
		List<String> ids = new ArrayList<>();
		for (LoaderBridge.Candidate c : accepted) ids.add(c.meta().getId() + " " + c.meta().getVersion().getFriendlyString());
		IsotopeLog.info("added " + accepted.size() + " mod" + (accepted.size() == 1 ? "" : "s") + " in "
				+ (System.nanoTime() - start) / 1_000_000 + " ms: " + ids + ". Details: isotope/report.txt");
	}

	/** Jars directly in isotope/mods are auto-detected; jars in isotope/mods/&lt;version&gt;/ are declared to be for that version. */
	private static List<Input> findInputs(Path modsDir, VersionGraph graph) throws IOException {
		List<Input> inputs = new ArrayList<>();

		try (Stream<Path> top = Files.list(modsDir)) {
			for (Path p : top.sorted().toList()) {
				if (Files.isDirectory(p)) {
					String forced = graph.normalize(p.getFileName().toString());

					if (forced == null) {
						IsotopeLog.warn("ignoring folder isotope/mods/" + p.getFileName() + ": not a version Isotope knows " + graph.allIds());
						continue;
					}

					try (Stream<Path> inner = Files.list(p)) {
						for (Path j : inner.sorted().toList()) if (isJar(j)) inputs.add(new Input(j, forced));
					}
				} else if (isJar(p)) {
					inputs.add(new Input(p, null));
				}
			}
		}

		return inputs;
	}

	private static boolean isJar(Path p) {
		return Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".jar");
	}

	private static String stripJar(String name) {
		String base = name.substring(0, name.length() - 4).replaceAll("[^A-Za-z0-9._+-]", "_");
		return base.length() > 60 ? base.substring(0, 60) : base;
	}

	private static String hash(byte[] jar, String salt) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		md.update(jar);
		md.update(salt.getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder();
		byte[] d = md.digest();
		for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
		return sb.toString();
	}

	/** Removes cache folders that no jar in isotope/mods maps to any more. Only ever deletes inside isotope/cache. */
	private static void cleanCache(Path cacheDir, Set<Path> live) {
		if (!Files.isDirectory(cacheDir)) return;

		try (Stream<Path> dirs = Files.list(cacheDir)) {
			for (Path d : dirs.toList()) {
				if (Files.isDirectory(d) && !live.contains(d)) deleteTree(d);
			}
		} catch (IOException e) {
			// Stale cache is harmless.
		}
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) return;

		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}

	private static void writeReadme(Path file) {
		if (Files.exists(file)) return;

		try {
			Files.writeString(file, """
					Isotope - run Fabric mods built for a different Minecraft 26.x version.

					mods/            Put mods for OTHER game versions here (not in the normal mods folder:
					                 Fabric rejects those before Isotope gets a chance to run).
					mods/26.1.2/     Optional. A jar in a folder named after a version is treated as built
					                 for that version, whatever its own metadata says.
					report.txt       Rewritten on every launch: what was redirected and what could not be.
					cache/           Rewritten jars. Safe to delete at any time.

					Mods that already support your game version belong in the normal mods folder.
					""", StandardCharsets.UTF_8);
		} catch (IOException e) {
			// Purely informational.
		}
	}
}
