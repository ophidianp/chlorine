package com.chlorine.client.mixin;

import com.chlorine.client.SodiumFrameSmoothing;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Frame-time patches inside Sodium's RenderSectionManager (Sodium 0.9.2).
 *
 * These touch Sodium internals rather than its API, so every injector uses
 * require = 0: if a future Sodium version changes the code, the patch is
 * skipped and Sodium keeps its own behaviour instead of crashing the game.
 *
 * 1. updateChunks() limits chunk uploads per frame to
 *    max(averageFrame * 0.3, 10 ms). We lower that 10 ms floor.
 * 2. scheduleRebuild() and isSectionImmediatePresentationCandidate() read
 *    NEARBY_REBUILD_DISTANCE (16 blocks) and IMMEDIATE_PRESENT_DISTANCE
 *    (64 blocks); sections inside them are rebuilt or shown on the main
 *    thread right away. While FPS is low we shrink both radii.
 * 3. finalizeRenderLists() asks AsyncCameraTimingControl whether to cull
 *    synchronously (it says yes when the camera moves fast). While FPS is
 *    low we answer no, so culling stays on Sodium's background thread.
 */
@Mixin(
        targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
        remap = false
)
public abstract class SodiumFrameSmoothingMixin {
    @Shadow
    private long averageFrameDuration;

    @ModifyExpressionValue(
            method = "updateChunks",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(JJ)J", ordinal = 0),
            require = 0
    )
    private long chlorine$adaptiveUploadBudget(long sodiumBudget) {
        return SodiumFrameSmoothing.uploadBudget(sodiumBudget, averageFrameDuration);
    }

    @ModifyExpressionValue(
            method = {"scheduleRebuild", "isSectionImmediatePresentationCandidate"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;NEARBY_REBUILD_DISTANCE:F"
            ),
            require = 0
    )
    private float chlorine$nearbyRebuildDistance(float sodiumSquared) {
        return SodiumFrameSmoothing.rebuildDistanceSquared(sodiumSquared);
    }

    @ModifyExpressionValue(
            method = "isSectionImmediatePresentationCandidate",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;IMMEDIATE_PRESENT_DISTANCE:F"
            ),
            require = 0
    )
    private float chlorine$immediatePresentDistance(float sodiumSquared) {
        return SodiumFrameSmoothing.presentDistanceSquared(sodiumSquared);
    }

    @ModifyExpressionValue(
            method = "finalizeRenderLists",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/AsyncCameraTimingControl;getShouldRenderSync(Lnet/minecraft/client/Camera;)Z"
            ),
            require = 0
    )
    private boolean chlorine$asyncCullingUnderPressure(boolean renderSync) {
        return renderSync && !SodiumFrameSmoothing.forceAsyncCulling();
    }
}
