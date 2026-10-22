package com.chlorine.client;

import com.chlorine.Chlorine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Coordinates Sodium-specific workload controls without replacing Sodium's
 * scheduler. The governor only asks Sodium to use its queued path when a
 * sustained low-FPS window coincides with a measured builder backlog.
 */
public final class SodiumWorkloadGovernor {
    private static final Deque<Integer> FPS_SAMPLES = new ArrayDeque<>();
    private static boolean sodiumPresent;
    private static Vec3 lastCameraPosition;
    private static boolean cameraStable;
    private static int queuedJobs;
    private static int busyThreads;
    private static int totalThreads;
    private static boolean pressureSampleAvailable;
    private static int pressureTicks;
    private static int relaxedTicks;
    private static boolean pressureMode;

    private SodiumWorkloadGovernor() {
    }

    public static void initialize() {
        sodiumPresent = FabricLoader.getInstance().isModLoaded("sodium");
        if (!sodiumPresent) {
            throw new IllegalStateException("Chlorine 1.0.4 requires Sodium 0.9.2 or newer");
        }
        Chlorine.LOGGER.info("Sodium detected; adaptive workload governors enabled");
    }

    public static void tick(Minecraft client) {
        if (!sodiumPresent || client.level == null) {
            FPS_SAMPLES.clear();
            lastCameraPosition = null;
            cameraStable = false;
            queuedJobs = 0;
            busyThreads = 0;
            totalThreads = 0;
            pressureSampleAvailable = false;
            pressureTicks = 0;
            relaxedTicks = 0;
            pressureMode = false;
            return;
        }
        if (!hasAnyGovernorEnabled()) {
            pressureTicks = 0;
            relaxedTicks = 0;
            pressureMode = false;
            pressureSampleAvailable = false;
            return;
        }

        int window = Math.max(10, Chlorine.CONFIG.sodiumWorkloadWindowTicks);
        FPS_SAMPLES.addLast(client.getFps());
        while (FPS_SAMPLES.size() > window) {
            FPS_SAMPLES.removeFirst();
        }

        Vec3 cameraPosition = client.getCameraEntity() == null
                ? client.player != null ? client.player.position() : Vec3.ZERO
                : client.getCameraEntity().position();
        cameraStable = lastCameraPosition != null
                && cameraPosition.distanceToSqr(lastCameraPosition)
                <= square(Math.max(0.01, Chlorine.CONFIG.sodiumCullingMovementThreshold));
        lastCameraPosition = cameraPosition;

        boolean queuePressure = pressureSampleAvailable
                && (queuedJobs >= Math.max(1, Chlorine.CONFIG.sodiumBuilderQueueThreshold)
                || (totalThreads > 0 && busyThreads >= totalThreads && queuedJobs > 0));
        boolean sustainedPressure = hasLowFrameRate() && queuePressure && cameraStable;
        int hysteresis = Math.max(2, Chlorine.CONFIG.sodiumPressureHysteresisTicks);

        if (sustainedPressure) {
            pressureTicks = Math.min(hysteresis, pressureTicks + 1);
            relaxedTicks = 0;
        } else {
            pressureTicks = 0;
            relaxedTicks = Math.min(hysteresis, relaxedTicks + 1);
        }

        if (!pressureMode && pressureTicks >= hysteresis) {
            pressureMode = true;
        } else if (pressureMode
                && (averageFps() >= Math.max(10, Chlorine.CONFIG.sodiumRecoveryFps)
                || relaxedTicks >= hysteresis)) {
            pressureMode = false;
        }
        pressureSampleAvailable = false;
    }

    /**
     * Samples Sodium's existing builder counters at the render-list boundary.
     * These are read-only public metrics and do not change Sodium's queue.
     */
    public static void observeBuilderPressure(RenderSectionManager manager) {
        if (!sodiumPresent || manager == null) {
            return;
        }

        var builder = manager.getBuilder();
        queuedJobs = builder.getScheduledJobCount();
        busyThreads = builder.getBusyThreadCount();
        totalThreads = builder.getTotalThreadCount();
        pressureSampleAvailable = true;
    }

    public static boolean shouldDeferImmediateUpdates() {
        if (!sodiumPresent || !hasAnyGovernorEnabled()) {
            return false;
        }

        return pressureMode;
    }

    private static boolean hasAnyGovernorEnabled() {
        return Chlorine.CONFIG.enableSodiumAdaptiveScheduling
                || Chlorine.CONFIG.enableSodiumAdaptiveCulling
                || Chlorine.CONFIG.enableSodiumWorkerTuning
                || Chlorine.CONFIG.enableSodiumTerrainScaling;
    }

    private static boolean hasLowFrameRate() {
        int window = Math.max(10, Chlorine.CONFIG.sodiumWorkloadWindowTicks);
        if (FPS_SAMPLES.size() < window) {
            return false;
        }

        return averageFps() < Math.max(10, Chlorine.CONFIG.sodiumLowFpsThreshold);
    }

    private static double averageFps() {
        if (FPS_SAMPLES.isEmpty()) {
            return 0.0;
        }

        long total = 0;
        for (int fps : FPS_SAMPLES) {
            total += fps;
        }
        return (double) total / FPS_SAMPLES.size();
    }

    private static double square(double value) {
        return value * value;
    }
}
