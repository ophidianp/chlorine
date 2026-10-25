package com.chlorine.mixin.client;

import com.chlorine.Chlorine;
import com.chlorine.client.RenderPressure;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shortens how far block entities draw while under render pressure.
 *
 * Chests, banners, skulls, beds, shulker boxes and similar block entities
 * are full entity-style models drawn every frame out to 64 blocks.
 * EntityCulling already skips the ones hidden behind walls; this covers
 * visible ones far away, and only while FPS is low (RenderPressure).
 *
 * Wraps the renderer's own shouldRender() check rather than replacing it,
 * so vanilla's (and any other mod's) culling still runs first. Renderers
 * with a longer view distance than the default 64 (beacons, end gateways)
 * or that render off screen are never shortened. Sodium extracts block
 * entities through this same dispatcher method, so it applies there too.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityLodMixin {
    private static final int VANILLA_DEFAULT_VIEW_DISTANCE = 64;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(
            method = "tryExtractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;shouldRender(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/phys/Vec3;)Z"))
    private boolean chlorine$shortenUnderPressure(
            BlockEntityRenderer renderer,
            BlockEntity blockEntity,
            Vec3 cameraPos,
            Operation<Boolean> original) {
        if (!original.call(renderer, blockEntity, cameraPos)) {
            return false;
        }
        if (!Chlorine.CONFIG.enableBlockEntityLod
                || !RenderPressure.isActive()
                || renderer.shouldRenderOffScreen()
                || renderer.getViewDistance() > VANILLA_DEFAULT_VIEW_DISTANCE) {
            return true;
        }

        BlockPos pos = blockEntity.getBlockPos();
        double dx = cameraPos.x - (pos.getX() + 0.5);
        double dy = cameraPos.y - (pos.getY() + 0.5);
        double dz = cameraPos.z - (pos.getZ() + 0.5);
        double limit = Chlorine.CONFIG.blockEntityLodDistance;
        return dx * dx + dy * dy + dz * dz <= limit * limit;
    }
}
