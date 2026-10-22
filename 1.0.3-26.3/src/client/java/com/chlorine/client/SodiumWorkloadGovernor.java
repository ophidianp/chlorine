package com.chlorine.client;

import com.chlorine.Chlorine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Coordinates Sodium-specific workload controls without replacing Sodium's
 * scheduler. All pressure is applied by asking Sodium to use its queued path
 * instead of its synchronous render-list path for non-urgent work.
 */
public final class SodiumWorkloadGovernor {
    private static final Deque<Integer> FPS_SAMPLES = new ArrayDeque<>();
    private static boolean sodiumPresent;
    private static Vec3 lastCameraPosition;
    private static boolean cameraStable;

    private SodiumWorkloadGovernor() {
    }

    public static void initialize() {
        sodiumPresent = FabricLoader.getInstance().isModLoaded("sodium");
        if (!sodiumPresent) {
            throw new IllegalStateException("Chlorine 1.0.3 requires Sodium 0.9.2 or newer");
        }
        Chlorine.LOGGER.info("Sodium detected; adaptive workload governors enabled");
    }

    public static void tick(Minecraft client) {
        if (!sodiumPresent || client.level == null) {
            FPS_SAMPLES.clear();
            lastCameraPosition = null;
            cameraStable = false;
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
    }

    public static boolean shouldDeferImmediateUpdates() {
        if (!sodiumPresent || !hasAnyGovernorEnabled() || !hasLowFrameRate()) {
            return false;
        }

        // Adaptive culling is only allowed to participate while the camera is
        // stable; moving cameras must keep Sodium's normal visibility path.
        boolean cullingPressure = Chlorine.CONFIG.enableSodiumAdaptiveCulling && cameraStable;
        boolean schedulingPressure = Chlorine.CONFIG.enableSodiumAdaptiveScheduling;
        boolean workerPressure = Chlorine.CONFIG.enableSodiumWorkerTuning;
        boolean terrainPressure = Chlorine.CONFIG.enableSodiumTerrainScaling;
        return schedulingPressure || cullingPressure || workerPressure || terrainPressure;
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

        return FPS_SAMPLES.stream().mapToInt(Integer::intValue).average().orElse(0.0)
                < Math.max(10, Chlorine.CONFIG.sodiumLowFpsThreshold);
    }

    private static double square(double value) {
        return value * value;
    }
}
