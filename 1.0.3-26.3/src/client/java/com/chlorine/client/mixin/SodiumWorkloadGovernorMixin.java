package com.chlorine.client.mixin;

import com.chlorine.client.SodiumWorkloadGovernor;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    @Inject(method = "finalizeRenderLists", at = @At("HEAD"))
    private void chlorine$observeBuilderPressure(
            net.minecraft.client.Camera camera,
            net.caffeinemc.mods.sodium.client.render.viewport.Viewport viewport,
            net.caffeinemc.mods.sodium.client.util.FogParameters fogParameters,
            boolean updateImmediately,
            CallbackInfo ci
    ) {
        SodiumWorkloadGovernor.observeBuilderPressure((RenderSectionManager) (Object) this);
    }

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
