package com.isotope.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The game versions Isotope has data for, in release order, and how to get from one to
 * another.
 *
 * <p>Tables only exist between neighbours (26.1.2 and 26.2, 26.2 and 26.3). A longer
 * trip is a chain of hops; going backwards uses the reverse tables.
 * {@code /isotope/versions.txt} lists the versions, each optionally followed by the
 * other version ids that share its API closely enough to use the same tables.
 */
public final class VersionGraph {
	private final List<String> versions = new ArrayList<>();
	private final Map<String, String> aliases = new HashMap<>();
	private final Map<String, RedirectTable> tables = new HashMap<>();
	private final Map<String, Hierarchy> hierarchies = new HashMap<>();

	public static VersionGraph load() throws IOException {
		VersionGraph g = new VersionGraph();

		try (BufferedReader r = open("/isotope/versions.txt")) {
			String line;

			while ((line = r.readLine()) != null) {
				int hash = line.indexOf('#');
				if (hash >= 0) line = line.substring(0, hash);
				String[] p = line.trim().split("\\s+");
				if (p[0].isEmpty()) continue;
				g.versions.add(p[0]);
				for (int i = 1; i < p.length; i++) g.aliases.put(p[i], p[0]);
			}
		}

		return g;
	}

	private static BufferedReader open(String resource) throws IOException {
		InputStream in = VersionGraph.class.getResourceAsStream(resource);
		if (in == null) throw new IOException("Isotope data file missing from the jar: " + resource);
		return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
	}

	public List<String> known() {
		return versions;
	}

	/** Every version id Isotope recognises, including the ones that borrow another version's tables. */
	public List<String> allIds() {
		List<String> ids = new ArrayList<>(versions);
		ids.addAll(aliases.keySet());
		return ids;
	}

	public String latest() {
		return versions.get(versions.size() - 1);
	}

	/** Maps any version id to the known version whose tables apply, or null. */
	public String normalize(String version) {
		if (version == null) return null;
		if (versions.contains(version)) return version;
		String alias = aliases.get(version);
		if (alias != null) return alias;

		// A hotfix Isotope has not heard of yet (26.3.1): hotfixes almost never change the API, so use 26.3's data.
		int dot = version.lastIndexOf('.');
		if (dot > 0 && version.indexOf('.') != dot) return normalize(version.substring(0, dot));
		return null;
	}

	/** The hops from {@code from} to {@code to} as {old, new} pairs; empty if they are the same. */
	public List<String[]> path(String from, String to) {
		int a = versions.indexOf(from);
		int b = versions.indexOf(to);
		List<String[]> hops = new ArrayList<>();
		if (a < 0 || b < 0) return hops;
		int step = a < b ? 1 : -1;
		for (int i = a; i != b; i += step) hops.add(new String[] {versions.get(i), versions.get(i + step)});
		return hops;
	}

	public RedirectTable table(String from, String to) throws IOException {
		String key = from + "_to_" + to;
		RedirectTable t = tables.get(key);

		if (t == null) {
			try (BufferedReader r = open("/isotope/redirects/" + key + ".redirects")) {
				t = RedirectTable.read(r);
			}

			tables.put(key, t);
		}

		return t;
	}

	public Hierarchy hierarchy(String version) throws IOException {
		Hierarchy h = hierarchies.get(version);

		if (h == null) {
			try (BufferedReader r = open("/isotope/hierarchy/" + version + ".hier")) {
				h = Hierarchy.read(r);
			}

			hierarchies.put(version, h);
		}

		return h;
	}
}
