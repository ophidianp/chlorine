package com.isotope.boot;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Logging that works this early. The game's own logger is not set up yet, so messages go
 * through Fabric Loader's, which prints them now and replays them into latest.log later.
 */
final class IsotopeLog {
	private static final LogCategory CATEGORY = LogCategory.createCustom("Isotope");

	private IsotopeLog() { }

	static void info(String msg) {
		Log.info(CATEGORY, msg);
	}

	static void warn(String msg) {
		Log.warn(CATEGORY, msg);
	}

	static void error(String msg, Throwable t) {
		if (t == null) Log.error(CATEGORY, msg);
		else Log.error(CATEGORY, msg, t);
	}
}
