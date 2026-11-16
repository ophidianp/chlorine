package com.isotope.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What Isotope did to one mod and what it could not fix.
 *
 * <p>Problems are grouped by "what is missing" with the classes that use it, so the
 * list reads like a to-do list for whoever ports the mod properly.
 */
public final class Report {
	public enum Kind {
		MISSING_CLASS("class no longer exists", true),
		MISSING_METHOD("method no longer exists (or its signature changed)", true),
		MISSING_FIELD("field no longer exists (or its type changed)", true),
		SHAPE_CHANGED("class changed shape - the JVM refuses to load these classes at all", true),
		NOT_IMPLEMENTED("abstract method the mod does not implement (new or changed)", true),
		MIXIN_TARGET("mixin target is gone", true),
		NOT_INSTALLED("classes from a mod or library that is not installed (often an optional integration)", false),
		CLIENT_ONLY("client classes, which a dedicated server does not have (not checked here; test on a client)", false);

		public final String title;
		public final boolean serious;

		Kind(String title, boolean serious) {
			this.title = title;
			this.serious = serious;
		}
	}

	public final String modLabel;
	public String modId = "?";
	public String fromVersion = "?";
	public String toVersion = "?";
	public int classesTotal;
	public int classesRewritten;
	public int referencesRedirected;
	public int callsRepaired;
	public int mixinsDropped;
	public final List<String> notes = new ArrayList<>();
	/** Removed game APIs whose uses were replaced with a default value. */
	public final Set<String> stubs = new TreeSet<>();
	private final Map<Kind, Map<String, Set<String>>> problems = new LinkedHashMap<>();

	public Report(String modLabel) {
		this.modLabel = modLabel;
	}

	public void problem(Kind kind, String subject, String usedIn) {
		problems.computeIfAbsent(kind, k -> new LinkedHashMap<>())
				.computeIfAbsent(subject, k -> new TreeSet<>())
				.add(usedIn.replace('/', '.'));
	}

	/**
	 * True when some class of the mod cannot even be defined on this game version (it extends
	 * something that became final, sealed or an interface). Loading that class throws
	 * IncompatibleClassChangeError, and for registries and world generation that happens at
	 * startup, taking the whole game down.
	 */
	public boolean hasFatal() {
		Map<String, Set<String>> shape = problems.get(Kind.SHAPE_CHANGED);
		return (shape != null && !shape.isEmpty()) || !startup.isEmpty();
	}

	/** Problems on code that certainly runs at startup: method -> what it needs that is missing. */
	public final Map<String, List<String>> startup = new LinkedHashMap<>();

	public String fatalReason() {
		Map<String, Set<String>> shape = problems.get(Kind.SHAPE_CHANGED);
		if (shape != null && !shape.isEmpty()) return "some of its classes can no longer be loaded (see \"class changed shape\")";
		return "its initializer uses something that no longer exists, so it would crash every launch (see \"fails at startup\")";
	}

	public int seriousCount() {
		int n = 0;

		for (Map.Entry<Kind, Map<String, Set<String>>> e : problems.entrySet()) {
			if (e.getKey().serious) n += e.getValue().size();
		}

		return n;
	}

	public String verdict() {
		int n = seriousCount();
		if (n == 0) return "links cleanly against " + toVersion + " - every game reference resolved";
		return n + " reference" + (n == 1 ? "" : "s") + " could not be resolved - the mod may crash or lose features where it uses them";
	}

	public String oneLine() {
		return modId + " (" + fromVersion + " -> " + toVersion + "): " + referencesRedirected + " references redirected in "
				+ classesRewritten + "/" + classesTotal + " classes; " + verdict();
	}

	public String render() {
		StringBuilder sb = new StringBuilder();
		sb.append("== ").append(modId).append("  [").append(modLabel).append("]\n");
		sb.append("   built for Minecraft ").append(fromVersion).append(", running on ").append(toVersion).append('\n');
		sb.append("   ").append(referencesRedirected).append(" references redirected, ").append(classesRewritten).append(" of ")
				.append(classesTotal).append(" classes rewritten");
		if (callsRepaired > 0) sb.append(", ").append(callsRepaired).append(" calls repaired (class <-> interface)");
		if (mixinsDropped > 0) sb.append(", ").append(mixinsDropped).append(" mixins dropped");
		sb.append('\n');
		sb.append("   verdict: ").append(verdict()).append('\n');
		for (String n : notes) sb.append("   note: ").append(n).append('\n');

		if (!startup.isEmpty()) {
			sb.append("\n   fails at startup - reached from the mod's initializer (").append(startup.size()).append("):\n");

			for (Map.Entry<String, List<String>> e : startup.entrySet()) {
				sb.append("     - ").append(e.getKey()).append(" needs:\n");
				for (String p : new java.util.LinkedHashSet<>(e.getValue())) sb.append("         ").append(p).append('\n');
			}
		}

		if (!stubs.isEmpty()) {
			sb.append("\n   removed game APIs - uses now get null / 0 instead (").append(stubs.size()).append("):\n");
			for (String st : stubs) sb.append("     - ").append(st).append('\n');
		}

		for (Map.Entry<Kind, Map<String, Set<String>>> e : problems.entrySet()) {
			sb.append("\n   ").append(e.getKey().title).append(" (").append(e.getValue().size()).append("):\n");

			for (Map.Entry<String, Set<String>> p : e.getValue().entrySet()) {
				sb.append("     - ").append(p.getKey()).append('\n');
				int shown = 0;

				for (String user : p.getValue()) {
					if (shown++ == 3) {
						sb.append("         ... and ").append(p.getValue().size() - 3).append(" more\n");
						break;
					}

					sb.append("         used in ").append(user).append('\n');
				}
			}
		}

		return sb.append('\n').toString();
	}
}
