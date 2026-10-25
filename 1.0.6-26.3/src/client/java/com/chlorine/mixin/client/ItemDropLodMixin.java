package com.chlorine.mixin.client;

import com.chlorine.Chlorine;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws distant dropped stacks as a single model.
 *
 * ItemClusterRenderState.count is how many copies of the item model
 * vanilla draws for one dropped stack (more items = more copies, up to 5).
 * Past a few blocks the extra copies are indistinguishable from one, but
 * mob and crop farms can have hundreds of stacks on screen. Setting count
 * to 1 after extraction means submit() draws one copy. The stack itself,
 * its item count and pickup behavior are untouched; this is render-only.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemDropLodMixin {

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("TAIL"))
    private void chlorine$collapseDistantStacks(
            ItemEntity entity,
            ItemEntityRenderState state,
            float partialTick,
            CallbackInfo ci) {
        if (!Chlorine.CONFIG.enableItemDropLod || state.count <= 1) {
            return;
        }

        // distanceToCameraSq is filled in by EntityRenderer.extractRenderState,
        // which runs at the start of this method.
        double limit = Chlorine.CONFIG.itemDropLodDistance;
        if (state.distanceToCameraSq > limit * limit) {
            state.count = 1;
        }
    }
}
