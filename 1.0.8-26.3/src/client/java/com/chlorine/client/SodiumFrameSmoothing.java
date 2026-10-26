package com.chlorine.client;

import com.chlorine.Chlorine;
import net.minecraft.client.Minecraft;

/**
 * Decisions for the Sodium frame-time patches in SodiumFrameSmoothingMixin.
 *
 * Low FPS comes from RenderPressure, the same flag the 1.0.6 LOD features
 * use. The upload budget also tracks how long we have been in the current
 * world, so the first seconds after joining keep Sodium's full budget and
 * terrain still loads in quickly.
 */
public final class SodiumFrameSmoothing {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static int ticksInWorld;

    private SodiumFrameSmoothing() {
    }

    public static void tick(Minecraft client) {
        ticksInWorld = client.level == null ? 0 : Math.min(Integer.MAX_VALUE - 1, ticksInWorld + 1);
    }

    /**
     * Sodium computed max(averageFrame * 0.3, 10 ms). Recompute it with our
     * lower floor once the join grace period is over. The 30% share of the
     * frame is kept, so at low FPS (long frames) the budget is unchanged.
     */
    public static long uploadBudget(long sodiumBudget, long averageFrameNanos) {
        if (!Chlorine.CONFIG.enableSodiumAdaptiveUploadBudget
                || ticksInWorld < Math.max(0, Chlorine.CONFIG.sodiumUploadBudgetGraceSeconds) * 20) {
            return sodiumBudget;
        }
        long floor = (long) (Math.max(0.5, Chlorine.CONFIG.sodiumMinUploadBudgetMillis) * NANOS_PER_MILLI);
        long budget = Math.max((long) (averageFrameNanos * 0.3f), floor);
        return Math.min(sodiumBudget, budget);
    }

    /** Sodium stores these distances squared; return our pressure radius squared, never larger than Sodium's. */
    public static float rebuildDistanceSquared(float sodiumSquared) {
        return scaled(sodiumSquared, Chlorine.CONFIG.sodiumPressureRebuildDistance);
    }

    public static float presentDistanceSquared(float sodiumSquared) {
        return scaled(sodiumSquared, Chlorine.CONFIG.sodiumPressurePresentDistance);
    }

    public static boolean forceAsyncCulling() {
        return Chlorine.CONFIG.enableSodiumAsyncCullingUnderPressure && RenderPressure.isActive();
    }

    private static float scaled(float sodiumSquared, double blocks) {
        if (!Chlorine.CONFIG.enableSodiumNearbyRebuildScaling || !RenderPressure.isActive()) {
            return sodiumSquared;
        }
        float radius = (float) Math.max(0.0, blocks);
        return Math.min(sodiumSquared, radius * radius);
    }
}
