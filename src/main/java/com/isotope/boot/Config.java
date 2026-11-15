package com.isotope.boot;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** {@code config/isotope.properties}. Created with defaults and explanations on first run. */
final class Config {
	boolean enabled = true;
	boolean lenientMixins = true;
	boolean cache = true;

	private static final String DEFAULTS = """
			# Isotope settings. Delete this file to get the defaults back.

			# Master switch. false = Isotope does nothing and mods in isotope/mods are ignored.
			enabled=true

			# When a rewritten mod's mixin no longer fits the game:
			#   true  = skip that one hook and keep going (the feature behind it will not work)
			#   false = let Mixin treat it as fatal, exactly as it would for a normal mod
			lenient-mixins=true

			# Reuse the rewritten jars from the last launch when nothing changed.
			# Turn off only when debugging Isotope itself.
			cache=true
			""";

	static Config load(Path file) {
		Config c = new Config();

		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8);
				return c;
			}

			Properties p = new Properties();

			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
			}

			c.enabled = bool(p, "enabled", true);
			c.lenientMixins = bool(p, "lenient-mixins", true);
			c.cache = bool(p, "cache", true);
		} catch (IOException e) {
			IsotopeLog.warn("could not read " + file + " (" + e + "); using defaults");
		}

		return c;
	}

	private static boolean bool(Properties p, String key, boolean def) {
		String v = p.getProperty(key);
		return v == null ? def : v.trim().equalsIgnoreCase("true");
	}
}
