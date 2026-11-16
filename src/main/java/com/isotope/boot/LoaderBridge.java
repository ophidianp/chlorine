package com.isotope.boot;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

import com.isotope.link.LiveIndex;

/**
 * Everything that touches Fabric Loader's internals lives here, and only here.
 *
 * <p>Fabric Loader has no public way to add a mod after discovery, so this reaches into
 * {@code net.fabricmc.loader.impl}. That is unsupported territory: a future loader
 * release can rename any of it. Every call is therefore wrapped by {@link Bootstrap} so
 * that a mismatch disables Isotope with a clear log line instead of breaking the game.
 * Written against Fabric Loader 0.19.5 and checked against 0.20.1 (same code).
 */
final class LoaderBridge {
	private LoaderBridge() { }

	static String minecraftVersion() {
		return FabricLoaderImpl.INSTANCE.getModContainer("minecraft")
				.map(m -> m.getMetadata().getVersion().getFriendlyString())
				.orElseGet(() -> FabricLoaderImpl.INSTANCE.getRawGameVersion());
	}

	static String isotopeVersion() {
		return FabricLoaderImpl.INSTANCE.getModContainer("isotope")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	// ---------------------------------------------------------------- what exists right now

	/**
	 * The game's libraries (DataFixerUpper, Brigadier, Gson, ...). The class loader only gets
	 * them once the game is unlocked, which is after Isotope runs, so they are read straight
	 * from the jar files the game provider found. The field names differ between loader
	 * versions, so this simply takes every collection of paths the provider holds.
	 */
	static final class Libraries implements LiveIndex.Source, AutoCloseable {
		private final List<ZipFile> jars = new ArrayList<>();
		private final List<Path> dirs = new ArrayList<>();

		Libraries() {
			java.util.Set<Path> seen = new java.util.LinkedHashSet<>();

			try {
				Object provider = FabricLoaderImpl.INSTANCE.getGameProvider();

				for (Class<?> c = provider.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
					for (Field f : c.getDeclaredFields()) {
						if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !Collection.class.isAssignableFrom(f.getType())) continue;
						f.setAccessible(true);

						if (f.get(provider) instanceof Collection<?> col) {
							for (Object o : col) if (o instanceof Path p) seen.add(p);
						}
					}
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				// Without libraries the check is less complete, not wrong: unknown classes are not reported as game breakage.
			}

			try {
				seen.addAll(FabricLauncherBase.getLauncher().getClassPath());
			} catch (RuntimeException e) {
				// same as above
			}

			for (Path p : seen) {
				try {
					if (Files.isDirectory(p)) dirs.add(p);
					else if (Files.isRegularFile(p)) jars.add(new ZipFile(p.toFile()));
				} catch (IOException | RuntimeException e) {
					// not a readable jar
				}
			}
		}

		@Override
		public byte[] find(String internalName) {
			String file = internalName + ".class";

			for (ZipFile zip : jars) {
				ZipEntry e = zip.getEntry(file);
				if (e == null) continue;

				try (InputStream in = zip.getInputStream(e)) {
					return in.readAllBytes();
				} catch (IOException ex) {
					// try the next jar
				}
			}

			return readFrom(dirs, internalName);
		}

		@Override
		public void close() {
			for (ZipFile zip : jars) {
				try {
					zip.close();
				} catch (IOException e) {
					// nothing useful to do
				}
			}
		}
	}

	/** Game jar, installed mods, libraries and JDK, as raw class bytes. */
	static LiveIndex liveIndex(Libraries libraries) {
		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
		List<Path> gameRoots = new ArrayList<>();
		List<Path> modRoots = new ArrayList<>();

		for (ModContainer mod : loader.getAllMods()) {
			try {
				(mod.getMetadata().getId().equals("minecraft") ? gameRoots : modRoots).addAll(mod.getRootPaths());
			} catch (RuntimeException e) {
				// A mod whose jar cannot be opened simply contributes nothing.
			}
		}

		ClassLoader knot = FabricLauncherBase.getLauncher().getTargetClassLoader();
		List<LiveIndex.Source> sources = new ArrayList<>();
		// The game jar is deliberately read as a file: the class loader refuses game classes this early.
		sources.add(name -> isGame(name) ? readFrom(gameRoots, name) : null);
		sources.add(name -> isGame(name) ? null : readFrom(modRoots, name));
		sources.add(libraries);
		sources.add(name -> readResource(knot, name));
		sources.add(name -> readResource(ClassLoader.getSystemClassLoader(), name));
		sources.add(name -> isGame(name) ? readFrom(modRoots, name) : readFrom(gameRoots, name));
		return new LiveIndex(sources);
	}

	private static boolean isGame(String name) {
		return name.startsWith("net/minecraft/") || name.startsWith("com/mojang/");
	}

	private static byte[] readFrom(List<Path> roots, String internalName) {
		String file = internalName + ".class";

		for (Path root : roots) {
			try {
				Path p = root.resolve(file);
				if (Files.isRegularFile(p)) return Files.readAllBytes(p);
			} catch (IOException | RuntimeException e) {
				// try the next root
			}
		}

		return null;
	}

	private static byte[] readResource(ClassLoader cl, String internalName) {
		try (InputStream in = cl.getResourceAsStream(internalName + ".class")) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- adding mods to the loader

	record Candidate(Path jar, LoaderModMetadata meta) { }

	static Candidate readCandidate(Path jar) throws Exception {
		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) throw new IOException("no fabric.mod.json in " + jar.getFileName());

			try (InputStream in = zip.getInputStream(entry)) {
				LoaderModMetadata meta = ModMetadataParser.parseMetadata(in, jar.toString(), Collections.emptyList(),
						new VersionOverrides(), new DependencyOverrides(loader.getConfigDir()), loader.isDevelopmentEnvironment());
				return new Candidate(jar, meta);
			}
		}
	}

	/**
	 * Decides which rewritten jars actually get loaded. The loader's own resolver has already
	 * run and cannot be re-run, so this applies the same basic rules by hand:
	 * wrong side (client-only mod on a server) - skip; id already installed - the installed
	 * one wins; two copies of the same id - newest wins; a required dependency is missing -
	 * skip, because loading it would only crash later with a worse message.
	 */
	static List<Candidate> resolve(List<Candidate> all, List<String> messages) {
		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
		Map<String, Candidate> chosen = new LinkedHashMap<>();

		for (Candidate c : all) {
			String id = c.meta().getId();

			if (!c.meta().loadsInEnvironment(loader.getEnvironmentType())) {
				messages.add(id + ": skipped, it is for the other side (" + c.meta().getEnvironment() + ")");
				continue;
			}

			if (loader.isModLoaded(id) || c.meta().getProvides().stream().anyMatch(loader::isModLoaded)) {
				// Typical for libraries bundled inside an old mod: the real, current one is already installed.
				messages.add(id + ": not added, a native copy is already installed");
				continue;
			}

			Candidate other = chosen.get(id);
			if (other == null || c.meta().getVersion().compareTo(other.meta().getVersion()) > 0) chosen.put(id, c);
		}

		boolean changed = true;

		while (changed) {
			changed = false;

			for (Candidate c : new ArrayList<>(chosen.values())) {
				for (ModDependency dep : c.meta().getDependencies()) {
					if (dep.getKind() != ModDependency.Kind.DEPENDS) continue;
					Version present = versionOf(dep.getModId(), chosen);

					if (present == null) {
						messages.add(c.meta().getId() + ": NOT LOADED, it needs '" + dep.getModId() + "', which is not installed");
						chosen.remove(c.meta().getId());
						changed = true;
						break;
					}

					if (!dep.matches(present)) {
						messages.add(c.meta().getId() + ": wants " + dep.getModId() + " " + dep.getVersionRequirements()
								+ " but " + present.getFriendlyString() + " is installed; loading anyway");
					}
				}
			}
		}

		return new ArrayList<>(chosen.values());
	}

	/**
	 * Mod ids that were renamed. Older mods depend on "fabric"; Fabric API stopped answering
	 * to that name and is now only "fabric-api". Same mod, so treat the old id as satisfied.
	 */
	private static final Map<String, String> RENAMED_IDS = Map.of("fabric", "fabric-api");

	private static Version versionOf(String modId, Map<String, Candidate> chosen) {
		ModContainer loaded = FabricLoaderImpl.INSTANCE.getModContainer(modId).orElse(null);
		if (loaded == null && RENAMED_IDS.containsKey(modId)) loaded = FabricLoaderImpl.INSTANCE.getModContainer(RENAMED_IDS.get(modId)).orElse(null);
		if (loaded != null) return loaded.getMetadata().getVersion();

		for (Candidate c : chosen.values()) {
			if (c.meta().getId().equals(modId) || c.meta().getProvides().contains(modId)) return c.meta().getVersion();
		}

		return null;
	}

	/**
	 * Registers the jars as real mods.
	 *
	 * <p>The loader is, at this moment, in the middle of a {@code for} loop over its mod list
	 * (that loop is what instantiated {@link IsotopeAdapter}). Adding to that list would break
	 * the loop, so a new, longer list is swapped into the field instead: the running loop
	 * finishes on the old list, and every later step (entrypoints, access wideners, mixins,
	 * the mod list shown in game) reads the new one.
	 */
	@SuppressWarnings("unchecked")
	static void inject(List<Candidate> candidates) throws Exception {
		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
		Field modsField = FabricLoaderImpl.class.getDeclaredField("mods");
		Field modMapField = FabricLoaderImpl.class.getDeclaredField("modMap");
		Field adapterMapField = FabricLoaderImpl.class.getDeclaredField("adapterMap");
		Method createPlain = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
		modsField.setAccessible(true);
		modMapField.setAccessible(true);
		adapterMapField.setAccessible(true);
		createPlain.setAccessible(true);

		List<ModContainerImpl> mods = new ArrayList<>((List<ModContainerImpl>) modsField.get(loader));
		Map<String, ModContainerImpl> modMap = (Map<String, ModContainerImpl>) modMapField.get(loader);
		Map<String, LanguageAdapter> adapterMap = (Map<String, LanguageAdapter>) adapterMapField.get(loader);
		List<ModContainerImpl> added = new ArrayList<>();

		for (Candidate c : candidates) {
			ModCandidateImpl candidate = (ModCandidateImpl) createPlain.invoke(null, List.of(c.jar()), c.meta(), false, Collections.emptyList());
			ModContainerImpl container = new ModContainerImpl(candidate);
			mods.add(container);
			added.add(container);
			modMap.put(c.meta().getId(), container);
			for (String provides : c.meta().getProvides()) modMap.put(provides, container);
			FabricLauncherBase.getLauncher().addToClassPath(c.jar());
		}

		modsField.set(loader, mods);

		// The loop that is running will not visit the new mods, so set up their language adapters here.
		for (ModContainerImpl mod : added) {
			for (Map.Entry<String, String> la : mod.getInfo().getLanguageAdapterDefinitions().entrySet()) {
				if (adapterMap.containsKey(la.getKey())) continue;
				adapterMap.put(la.getKey(), (LanguageAdapter) Class.forName(la.getValue(), true,
						FabricLauncherBase.getLauncher().getTargetClassLoader()).getDeclaredConstructor().newInstance());
			}
		}
	}
}
