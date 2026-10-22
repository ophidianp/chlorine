package com.chlorine.client;

import com.chlorine.Chlorine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;

/**
 * Coordinates Sodium-specific workload controls without replacing Sodium's
 * scheduler. The governor only asks Sodium to use its queued path when a
 * sustained low-FPS window coincides with a measured builder backlog.
 */
public final class SodiumWorkloadGovernor {
    private static int[] fpsSamples = new int[0];
    private static int fpsSampleCount;
    private static int fpsSampleIndex;
    private static long fpsSampleTotal;
    private static boolean sodiumPresent;
    private static double lastCameraX;
    private static double lastCameraY;
    private static double lastCameraZ;
    private static boolean lastCameraPositionAvailable;
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
            throw new IllegalStateException("Chlorine 1.0.5 requires Sodium 0.9.2 or newer");
        }
        Chlorine.LOGGER.info("Sodium detected; adaptive workload governors enabled");
    }

    public static void tick(Minecraft client) {
        if (!sodiumPresent || client.level == null) {
            clearSamples();
            lastCameraPositionAvailable = false;
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
        ensureSampleCapacity(window);
        addFpsSample(client.getFps());

        Entity cameraEntity = client.getCameraEntity();
        if (cameraEntity == null) {
            cameraEntity = client.player;
        }
        double cameraX = cameraEntity == null ? 0.0 : cameraEntity.getX();
        double cameraY = cameraEntity == null ? 0.0 : cameraEntity.getY();
        double cameraZ = cameraEntity == null ? 0.0 : cameraEntity.getZ();
        cameraStable = lastCameraPositionAvailable
                && squaredDistance(cameraX, cameraY, cameraZ)
                <= square(Math.max(0.01, Chlorine.CONFIG.sodiumCullingMovementThreshold));
        lastCameraX = cameraX;
        lastCameraY = cameraY;
        lastCameraZ = cameraZ;
        lastCameraPositionAvailable = true;

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
        if (!sodiumPresent || manager == null || !hasAnyGovernorEnabled()) {
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
        if (fpsSampleCount < window) {
            return false;
        }

        return averageFps() < Math.max(10, Chlorine.CONFIG.sodiumLowFpsThreshold);
    }

    private static double averageFps() {
        if (fpsSampleCount == 0) {
            return 0.0;
        }

        return (double) fpsSampleTotal / fpsSampleCount;
    }

    private static void ensureSampleCapacity(int window) {
        if (fpsSamples.length == window) {
            return;
        }

        fpsSamples = new int[window];
        fpsSampleCount = 0;
        fpsSampleIndex = 0;
        fpsSampleTotal = 0;
    }

    private static void addFpsSample(int fps) {
        if (fpsSampleCount == fpsSamples.length) {
            fpsSampleTotal -= fpsSamples[fpsSampleIndex];
        } else {
            fpsSampleCount++;
        }

        fpsSamples[fpsSampleIndex] = fps;
        fpsSampleTotal += fps;
        fpsSampleIndex++;
        if (fpsSampleIndex == fpsSamples.length) {
            fpsSampleIndex = 0;
        }
    }

    private static void clearSamples() {
        fpsSampleCount = 0;
        fpsSampleIndex = 0;
        fpsSampleTotal = 0;
    }

    private static double squaredDistance(double x, double y, double z) {
        double dx = x - lastCameraX;
        double dy = y - lastCameraY;
        double dz = z - lastCameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double square(double value) {
        return value * value;
    }
}
