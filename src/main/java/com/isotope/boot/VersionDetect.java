package com.isotope.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import com.isotope.model.VersionGraph;

/**
 * Reads a mod's {@code depends.minecraft} to narrow down which game versions it could
 * have been built for.
 *
 * <p>This only narrows. Metadata is often vague ({@code ">=26.1"} accepts every version
 * there will ever be), so the final pick is made from evidence in the bytecode: see
 * {@code JarRewriter.detectSource}.
 */
public final class VersionDetect {
	private VersionDetect() { }

	/**
	 * Returns the known versions (oldest first) that the dependency allows. An absent or
	 * unreadable dependency allows all of them.
	 */
	public static Function<Object, List<String>> allowedVersions(VersionGraph graph) {
		return dep -> {
			List<String> specs = new ArrayList<>();
			if (dep instanceof String s) specs.add(s);
			else if (dep instanceof List<?> l) for (Object o : l) if (o instanceof String s) specs.add(s);
			if (specs.isEmpty()) return new ArrayList<>(graph.known());
			List<VersionPredicate> predicates = new ArrayList<>();

			for (String spec : specs) {
				try {
					predicates.add(VersionPredicate.parse(spec));
				} catch (Exception e) {
					return new ArrayList<>(graph.known());
				}
			}

			List<String> allowed = new ArrayList<>();

			for (String known : graph.known()) {
				// A known version counts if it, or any id that shares its tables (26.1 for 26.1.2), matches.
				for (String id : graph.allIds()) {
					if (!known.equals(graph.normalize(id)) || allowed.contains(known)) continue;

					try {
						Version v = Version.parse(id);
						for (VersionPredicate p : predicates) if (p.test(v)) allowed.add(known); // a list means "any of these"
					} catch (Exception e) {
						// unparseable id: skip
					}
				}
			}

			return allowed;
		};
	}
}
