package com.isotope.boot;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Logging that works this early. The game's own logger is not set up yet, so messages go
 * through Fabric Loader's, which prints them now and replays them into latest.log later.
 */
final class IsotopeLog {
	private static final LogCategory CATEGORY = LogCategory.createCustom("Isotope");

	/** The category is not always printed (it depends on the log backend in use), so tag the text too. */
	private static final String TAG = "[Isotope] ";

	private IsotopeLog() { }

	static void info(String msg) {
		Log.info(CATEGORY, TAG + msg);
	}

	static void warn(String msg) {
		Log.warn(CATEGORY, TAG + msg);
	}

	static void error(String msg, Throwable t) {
		if (t == null) Log.error(CATEGORY, TAG + msg);
		else Log.error(CATEGORY, TAG + msg, t);
	}
}
