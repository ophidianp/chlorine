package com.isotope.rewrite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import com.isotope.link.Linker;
import com.isotope.link.LiveIndex;
import com.isotope.link.MixinCheck;
import com.isotope.link.Report;
import com.isotope.model.VersionGraph;
import com.isotope.util.Json;

/**
 * Turns one mod jar built for another game version into jars that load on this one.
 *
 * <p>Steps, in order:
 * <ol>
 * <li>unpack the jar and any jars nested inside it (libraries a mod bundles);</li>
 * <li>work out which game version it was built for;</li>
 * <li>for each hop towards the running version, rewrite every class, mixin string and
 *     access widener through that hop's redirect table;</li>
 * <li>link-check the result against the real game and note what is still missing;</li>
 * <li>relax the mod's mixin configs so one dead hook does not take the game down;</li>
 * <li>patch {@code fabric.mod.json} (drop the Minecraft version pin) and repack.</li>
 * </ol>
 * The input jar is never modified. Nothing here touches Fabric Loader, so the whole
 * pipeline can be unit tested without a game.
 */
public final class JarRewriter {
	public static final class Options {
		/** Version actually running, normalised to one the {@link VersionGraph} knows (or null if unknown). */
		public String targetVersion;
		/** What to show in the report for the running version (e.g. "26.3.1"). */
		public String targetLabel;
		/** Set when the user put the jar in a version-named folder; overrides detection. */
		public String forcedSource;
		public boolean lenientMixins = true;
		/** True on a dedicated server, where the game's client classes are absent on purpose. */
		public boolean dedicatedServer;
		public VersionGraph graph;
		public LiveIndex index;
		/**
		 * Given the mod's {@code depends.minecraft} value (String, List or null), returns the known
		 * versions it allows, oldest first. The pick among them is made from the bytecode.
		 */
		public Function<Object, List<String>> allowedVersions;
		public String isotopeVersion = "dev";
	}

	public static final class Result {
		public final List<Path> jars = new ArrayList<>();
		public Report report;
	}

	/** One jar's contents: the mod itself or a jar nested inside it. */
	private static final class Unit {
		String label;
		final Map<String, byte[]> entries = new LinkedHashMap<>();
		Map<String, Object> json;
		String id = "unknown";
		String version = "0";
	}

	private JarRewriter() { }

	public static Result rewrite(Path input, Path outDir, Options o) throws IOException {
		Report report = new Report(input.getFileName().toString());
		List<Unit> units = new ArrayList<>();
		loadUnit(Files.readAllBytes(input), input.getFileName().toString(), units);
		Unit root = units.get(0);
		if (root.json == null) throw new IOException("not a Fabric mod (no fabric.mod.json)");
		report.modId = root.id;

		// --- which version is this mod from?
		Object mcDep = dependsOn(root.json, "minecraft");
		String source = o.forcedSource;

		if (source == null) {
			List<String> allowed = o.allowedVersions != null ? o.allowedVersions.apply(mcDep) : new ArrayList<>(o.graph.known());
			source = detectSource(units, allowed, o.graph, report);
		}

		String target = o.targetVersion;
		report.toVersion = o.targetLabel != null ? o.targetLabel : String.valueOf(target);

		if (source == null) {
			report.fromVersion = mcDep == null ? "unspecified" : String.valueOf(mcDep);
			report.notes.add("could not tell which 26.x version this mod was built for, so nothing was redirected; "
					+ "put the jar in a folder named after its version (for example isotope/mods/26.1.2/) to tell Isotope");
		} else {
			report.fromVersion = source;
		}

		// --- rewrite, one hop at a time
		int[] changes = new int[1];
		Set<String> touched = new HashSet<>();

		if (source != null && target != null) {
			for (String[] hopVersions : o.graph.path(source, target)) {
				Map<String, ModClass> modClasses = new HashMap<>();

				for (Unit u : units) {
					for (Map.Entry<String, byte[]> e : u.entries.entrySet()) {
						if (isClass(e.getKey())) {
							ModClass mc = scanQuietly(e.getValue());
							if (mc != null) modClasses.putIfAbsent(mc.name, mc);
						}
					}
				}

				Hop hop = new Hop(o.graph.table(hopVersions[0], hopVersions[1]), o.graph.hierarchy(hopVersions[0]), modClasses);

				for (Unit u : units) {
					for (Map.Entry<String, byte[]> e : u.entries.entrySet()) {
						if (!isClass(e.getKey())) continue;

						try {
							byte[] out = ClassRewriter.rewrite(e.getValue(), hop, changes, report.stubs);

							if (out != e.getValue()) {
								e.setValue(out);
								touched.add(u.label + "!" + e.getKey());
							}
						} catch (RuntimeException ex) {
							report.notes.add("could not rewrite " + e.getKey() + " (" + ex + "); left as it was");
						}
					}

					for (String key : new String[] {"accessWidener", "classTweaker"}) {
						if (u.json != null && u.json.get(key) instanceof String path && u.entries.containsKey(path)) {
							String text = new String(u.entries.get(path), StandardCharsets.UTF_8);
							String mapped = AccessWideners.remap(text, hop, changes);
							if (!mapped.equals(text)) u.entries.put(path, mapped.getBytes(StandardCharsets.UTF_8));
						}
					}
				}
			}
		} else if (source != null) {
			report.notes.add("this Minecraft version is newer than Isotope's tables; nothing was redirected");
		}

		report.referencesRedirected = changes[0];
		report.classesRewritten = touched.size();

		// --- link check against what is really there
		Map<String, byte[]> ownClasses = new HashMap<>();

		for (Unit u : units) {
			for (Map.Entry<String, byte[]> e : u.entries.entrySet()) {
				if (isClass(e.getKey()) && !e.getKey().startsWith("META-INF/")) {
					ownClasses.putIfAbsent(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
				}
			}
		}

		// Multi-release jars keep newer builds of a class under META-INF/versions/<java>/. The JVM
		// (Java 25 here) picks the highest one, so the check must look at that copy too.
		for (int java = 9; java <= 25; java++) {
			String prefix = "META-INF/versions/" + java + "/";

			for (Unit u : units) {
				for (Map.Entry<String, byte[]> e : u.entries.entrySet()) {
					if (isClass(e.getKey()) && e.getKey().startsWith(prefix)) {
						ownClasses.put(e.getKey().substring(prefix.length(), e.getKey().length() - 6), e.getValue());
					}
				}
			}
		}

		LiveIndex index = o.index.withFirst(ownClasses::get);
		Linker linker = new Linker(index, report, o.dedicatedServer);
		Set<String> deadMixins = new HashSet<>();

		for (Unit u : units) {
			for (Map.Entry<String, byte[]> e : u.entries.entrySet()) {
				if (!isClass(e.getKey())) continue;
				report.classesTotal++;

				try {
					e.setValue(linker.check(e.getValue()));
					ModClass mc = ModClass.scan(e.getValue());

					if (mc.isMixin()) {
						List<String> present = MixinCheck.check(e.getValue(), index, report, o.dedicatedServer);
						// On a server, a mixin into a client class is simply not for this side; the mod's
						// own config already keeps it in the "client" list, so leave it alone.
						boolean allGame = mc.mixinTargets.stream().allMatch(t -> Linker.isGameName(t)
								&& !(o.dedicatedServer && Linker.isClientOnly(t)));
						if (present.isEmpty() && allGame) deadMixins.add(mc.name);
						if (o.lenientMixins) e.setValue(relaxRequire(e.getValue()));
					}
				} catch (RuntimeException ex) {
					report.notes.add("could not check " + e.getKey() + " (" + ex + ")");
				}
			}
		}

		// --- what certainly runs at startup
		List<String> entrypoints = new ArrayList<>();

		for (Unit u : units) {
			if (u.json != null) collectEntrypoints(u.json, o.dedicatedServer, entrypoints);
		}

		report.startup.putAll(com.isotope.link.StartupPath.find(ownClasses, entrypoints, linker.problemsByMethod, !o.dedicatedServer));

		// --- metadata
		for (Unit u : units) {
			if (u.json == null) continue;
			patchMixinConfigs(u, deadMixins, o.lenientMixins, report);
			patchModJson(u, source, report.toVersion, o.isotopeVersion);
			stripSignatures(u);
		}

		// --- write
		Files.createDirectories(outDir);
		Result result = new Result();
		result.report = report;
		Set<String> usedNames = new HashSet<>();

		for (Unit u : units) {
			if (u.json == null) continue; // a nested jar that is not a mod cannot be loaded by Fabric anyway
			String base = sanitize(u.id + "-" + u.version);
			String name = base;
			for (int n = 2; !usedNames.add(name); n++) name = base + "_" + n;
			Path out = outDir.resolve(name + ".jar");
			writeJar(out, u);
			result.jars.add(out);
		}

		return result;
	}

	// ---------------------------------------------------------------- which version is it from

	/**
	 * Picks the version the mod was compiled against, from evidence: every game class the
	 * bytecode names is looked up in each candidate version's class list, and the version
	 * missing the fewest of them wins. Hundreds of classes move or vanish between
	 * releases, so a mod that touches any of them gives itself away. A tie (the mod only
	 * uses classes every candidate has) goes to the oldest candidate: authors set the
	 * minimum to what they built against, and for a tie it makes no difference anyway.
	 */
	private static String detectSource(List<Unit> units, List<String> allowed, VersionGraph graph, Report report) throws IOException {
		if (allowed.isEmpty()) return null;
		if (allowed.size() == 1) return allowed.get(0);
		java.util.Set<String> referenced = new java.util.HashSet<>();
		java.util.Set<String> own = new java.util.HashSet<>();

		for (Unit u : units) {
			for (Map.Entry<String, byte[]> e : u.entries.entrySet()) {
				if (!isClass(e.getKey())) continue;

				try {
					ClassReader r = new ClassReader(e.getValue());
					own.add(r.getClassName());
					char[] buf = new char[r.getMaxStringLength()];

					// Every class a class file refers to sits in its constant pool as a CONSTANT_Class entry (tag 7).
					for (int i = 1; i < r.getItemCount(); i++) {
						int offset = r.getItem(i);
						if (offset == 0 || r.b[offset - 1] != 7) continue;
						String name = r.readUTF8(offset, buf);
						while (name.startsWith("[")) name = name.substring(1);
						if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
						if (name.startsWith("net/minecraft/") || name.startsWith("com/mojang/blaze3d/") || name.startsWith("com/mojang/renderpearl/")) referenced.add(name);
					}
				} catch (RuntimeException ex) {
					// an unreadable class contributes no evidence
				}
			}
		}

		referenced.removeAll(own); // a mixin into a class the mod itself ships proves nothing
		String best = null;
		int bestMissing = Integer.MAX_VALUE;
		StringBuilder evidence = new StringBuilder();

		for (String v : allowed) {
			com.isotope.model.Hierarchy h = graph.hierarchy(v);
			int missing = 0;
			for (String c : referenced) if (!h.contains(c)) missing++;
			if (evidence.length() > 0) evidence.append(", ");
			evidence.append(v).append(": ").append(missing).append(" missing");

			if (missing < bestMissing) { // strict: ties keep the older version
				bestMissing = missing;
				best = v;
			}
		}

		report.notes.add("detected as a " + best + " mod from the " + referenced.size() + " game classes it uses (" + evidence + ")");
		return best;
	}

	// ---------------------------------------------------------------- loading

	@SuppressWarnings("unchecked")
	private static void loadUnit(byte[] jarBytes, String label, List<Unit> units) throws IOException {
		Unit u = new Unit();
		u.label = label;
		units.add(u);

		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
			ZipEntry e;

			while ((e = zip.getNextEntry()) != null) {
				if (e.isDirectory()) continue;
				u.entries.put(e.getName(), zip.readAllBytes());
			}
		}

		byte[] meta = u.entries.get("fabric.mod.json");
		if (meta == null) return;

		try {
			u.json = Json.parseObject(new String(meta, StandardCharsets.UTF_8));
		} catch (RuntimeException ex) {
			throw new IOException("unreadable fabric.mod.json in " + label + ": " + ex.getMessage());
		}

		if (u.json.get("id") instanceof String id) u.id = id;
		if (u.json.get("version") instanceof String v) u.version = v;

		// Jars bundled inside the mod ("jar-in-jar"). They get the same treatment, as separate jars.
		if (u.json.get("jars") instanceof List<?> jars) {
			for (Object j : jars) {
				if (j instanceof Map<?, ?> m && ((Map<String, Object>) m).get("file") instanceof String file) {
					byte[] nested = u.entries.remove(file);
					if (nested != null) loadUnit(nested, label + "!" + file, units);
				}
			}
		}
	}

	private static ModClass scanQuietly(byte[] bytes) {
		try {
			return ModClass.scan(bytes);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static boolean isClass(String entryName) {
		return entryName.endsWith(".class") && !entryName.endsWith("module-info.class");
	}

	@SuppressWarnings("unchecked")
	private static Object dependsOn(Map<String, Object> json, String id) {
		return json.get("depends") instanceof Map<?, ?> deps ? ((Map<String, Object>) deps).get(id) : null;
	}

	/**
	 * Classes named by the entrypoints that certainly run at startup on this side: Fabric's own
	 * {@code main}, {@code preLaunch} and {@code server}/{@code client}, plus init-style keys of
	 * entrypoint libraries ({@code yumi:init}, {@code yumi:dedicated_server_init}, ...). Keys for
	 * integrations ({@code modmenu}, {@code jei_mod_plugin}) only run if that other mod is present,
	 * so they are left out.
	 */
	@SuppressWarnings("unchecked")
	private static void collectEntrypoints(Map<String, Object> json, boolean server, List<String> out) {
		if (!(json.get("entrypoints") instanceof Map<?, ?> eps)) return;

		for (String key : ((Map<String, Object>) eps).keySet()) {
			String k = key.toLowerCase(java.util.Locale.ROOT);
			boolean fabric = k.equals("main") || k.equals("prelaunch") || k.equals(server ? "server" : "client");
			boolean initStyle = k.endsWith("init") && !(server ? k.contains("client") : k.contains("server"));
			if (!fabric && !initStyle) continue;
			if (!(((Map<String, Object>) eps).get(key) instanceof List<?> list)) continue;

			for (Object e : list) {
				String value = e instanceof String str ? str : e instanceof Map<?, ?> m && ((Map<String, Object>) m).get("value") instanceof String v ? v : null;
				if (value == null) continue;
				int colons = value.indexOf("::");
				out.add((colons >= 0 ? value.substring(0, colons) : value).replace('.', '/'));
			}
		}
	}

	// ---------------------------------------------------------------- patching

	private static byte[] relaxRequire(byte[] classBytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);
		if (!MixinCheck.relaxRequire(cn)) return classBytes;
		ClassWriter w = new ClassWriter(0);
		cn.accept(w);
		return w.toByteArray();
	}

	/**
	 * Mixin configs list the mod's mixin classes. Two edits:
	 * mixins whose target class no longer exists are taken off the list (Mixin would abort on
	 * them), and in lenient mode the config is marked non-required with a default
	 * {@code require} of 0, so a hook that no longer fits is skipped with a log line instead
	 * of crashing the game.
	 */
	@SuppressWarnings("unchecked")
	private static void patchMixinConfigs(Unit u, Set<String> deadMixins, boolean lenient, Report report) {
		if (!(u.json.get("mixins") instanceof List<?> configs)) return;

		for (Object c : configs) {
			String path = c instanceof String s ? s : c instanceof Map<?, ?> m && ((Map<String, Object>) m).get("config") instanceof String s2 ? s2 : null;
			if (path == null || !u.entries.containsKey(path)) continue;
			Map<String, Object> cfg;

			try {
				cfg = Json.parseObject(new String(u.entries.get(path), StandardCharsets.UTF_8));
			} catch (RuntimeException ex) {
				report.notes.add("could not read mixin config " + path + " (" + ex.getMessage() + "); left as it was");
				continue;
			}

			boolean changed = false;
			String pkg = cfg.get("package") instanceof String p ? p.replace('.', '/') + "/" : "";

			for (String listKey : new String[] {"mixins", "client", "server"}) {
				if (!(cfg.get(listKey) instanceof List<?> list)) continue;
				List<Object> kept = new ArrayList<>();

				for (Object name : list) {
					if (name instanceof String n && deadMixins.contains(pkg + n.replace('.', '/'))) {
						report.mixinsDropped++;
						changed = true;
					} else {
						kept.add(name);
					}
				}

				if (kept.size() != list.size()) cfg.put(listKey, kept);
			}

			if (lenient) {
				cfg.put("required", Boolean.FALSE);
				Map<String, Object> injectors = cfg.get("injectors") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
				injectors.put("defaultRequire", 0L);
				cfg.put("injectors", injectors);
				changed = true;
			}

			if (changed) u.entries.put(path, Json.write(cfg).getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Fabric refuses a mod whose {@code depends.minecraft} does not match the running game.
	 * That pin is exactly what Isotope exists to get past, so it is removed (and remembered
	 * under {@code custom.isotope} so the mod list can still show where the mod came from).
	 */
	@SuppressWarnings("unchecked")
	private static void patchModJson(Unit u, String source, String target, String isotopeVersion) {
		Map<String, Object> json = u.json;
		Object original = null;

		for (String section : new String[] {"depends", "breaks", "conflicts"}) {
			if (json.get(section) instanceof Map<?, ?> m) {
				Object removed = ((Map<String, Object>) m).remove("minecraft");
				if (section.equals("depends")) original = removed;
			}
		}

		json.remove("jars"); // nested jars are written out as their own files
		Map<String, Object> custom = json.get("custom") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
		Map<String, Object> mark = new LinkedHashMap<>();
		mark.put("rewritten_by", "Isotope " + isotopeVersion);
		mark.put("built_for", source == null ? "unknown" : source);
		mark.put("running_on", target);
		if (original != null) mark.put("original_minecraft_dependency", original);
		custom.put("isotope", mark);
		json.put("custom", custom);
		u.entries.put("fabric.mod.json", Json.write(json).getBytes(StandardCharsets.UTF_8));
	}

	/** A signed jar whose classes were changed fails verification; drop the signature files and per-file digests. */
	private static void stripSignatures(Unit u) {
		u.entries.keySet().removeIf(n -> n.startsWith("META-INF/")
				&& (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")));
		byte[] mf = u.entries.get("META-INF/MANIFEST.MF");
		if (mf == null) return;

		try {
			Manifest manifest = new Manifest(new ByteArrayInputStream(mf));
			if (manifest.getEntries().isEmpty()) return;
			manifest.getEntries().clear();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			manifest.write(out);
			u.entries.put("META-INF/MANIFEST.MF", out.toByteArray());
		} catch (IOException ignored) {
			// An odd manifest is not worth failing over.
		}
	}

	private static String sanitize(String s) {
		return s.replaceAll("[^A-Za-z0-9._+-]", "_");
	}

	private static void writeJar(Path out, Unit u) throws IOException {
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");

		try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os)) {
			for (Map.Entry<String, byte[]> e : u.entries.entrySet()) {
				ZipEntry ze = new ZipEntry(e.getKey());
				ze.setTime(315532800000L); // fixed timestamp: same input, same output bytes
				zip.putNextEntry(ze);
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}

		Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
