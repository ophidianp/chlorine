package com.chlorine.client;

import com.chlorine.Chlorine;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.minecraft.client.Minecraft;

/**
 * Applies the GPU-friendly Sodium options that are safe to change after
 * Sodium has initialized. This deliberately does not touch Sodium's entity
 * culling option or worker count; those are separate user-facing policies.
 */
public final class SodiumGpuProfile {
    private static boolean applied;

    private SodiumGpuProfile() {
    }

    public static void applyIfEnabled(Minecraft client) {
        if (applied || !Chlorine.CONFIG.enableSodiumGpuProfile) {
            return;
        }
        applied = true;

        SodiumOptions options = SodiumClientMod.options();
        boolean changed = false;

        if (!options.performance.useFogOcclusion) {
            options.performance.useFogOcclusion = true;
            changed = true;
        }
        if (!options.performance.useBlockFaceCulling) {
            options.performance.useBlockFaceCulling = true;
            changed = true;
        }
        if (!options.performance.animateOnlyVisibleTextures) {
            options.performance.animateOnlyVisibleTextures = true;
            changed = true;
        }

        if (changed) {
            try {
                SodiumOptions.writeToDisk(options);
                Chlorine.LOGGER.info(
                        "Applied Sodium GPU profile: fog occlusion, block-face culling, and visible-texture animation"
                );
            } catch (Exception e) {
                Chlorine.LOGGER.warn("Sodium GPU profile changed in memory but could not be saved", e);
            }
        }
    }
}
