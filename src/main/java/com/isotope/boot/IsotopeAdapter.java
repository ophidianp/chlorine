package com.isotope.boot;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/**
 * Isotope's way in.
 *
 * <p>Isotope has to add mods to the game before Fabric Loader finishes setting mods up.
 * Normal entrypoints (even {@code preLaunch}) run far too late: by then the loader has
 * already decided which mods exist, registered their entrypoints and handed their mixin
 * configs to Mixin. A <i>language adapter</i> is the one piece of a mod the loader
 * instantiates in the middle of that setup - after mod discovery, before entrypoints,
 * access wideners and mixins. So Isotope declares one in its {@code fabric.mod.json} and
 * does its work in the constructor. It is never used as an actual language adapter.
 */
public final class IsotopeAdapter implements LanguageAdapter {
	public IsotopeAdapter() {
		Bootstrap.run();
	}

	@Override
	public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
		throw new LanguageAdapterException("'isotope' is not a real language adapter; it only exists to start Isotope early");
	}
}
