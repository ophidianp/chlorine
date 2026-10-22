package com.chlorine.client.mixin;

import com.chlorine.client.SodiumWorkloadGovernor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Sodium's public render-list boundary. Deferring this boolean leaves the
 * normal asynchronous builder and culling queues entirely under Sodium's
 * control while preventing a low-FPS burst from forcing synchronous work.
 */
@Mixin(
        targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
        remap = false
)
public abstract class SodiumWorkloadGovernorMixin {
    @ModifyVariable(
            method = "finalizeRenderLists",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private boolean chlorine$deferSynchronousTerrainWork(boolean updateImmediately) {
        return updateImmediately && !SodiumWorkloadGovernor.shouldDeferImmediateUpdates();
    }
}
