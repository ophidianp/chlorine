package com.chlorine.client;

/**
 * Shared "the client is struggling" flag for the LOD features (ambient
 * block effects, block entity LOD). Updated once per client tick from
 * Minecraft's own FPS counter, which is already a one-second average.
 *
 * Uses hysteresis: pressure starts below the threshold and only ends once
 * FPS is 10 above it, so features don't flip on and off every second when
 * FPS hovers right at the threshold.
 */
public final class RenderPressure {
    private static final int RECOVERY_MARGIN_FPS = 10;
    private static boolean active;

    private RenderPressure() {
    }

    public static void update(int fps, int threshold) {
        if (fps <= 0) {
            return;
        }
        if (active) {
            active = fps < threshold + RECOVERY_MARGIN_FPS;
        } else {
            active = fps < threshold;
        }
    }

    public static boolean isActive() {
        return active;
    }
}
