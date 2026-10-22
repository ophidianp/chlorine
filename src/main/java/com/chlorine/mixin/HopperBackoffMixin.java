package com.chlorine.mixin;

import com.chlorine.Chlorine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a conservative backoff before distant hoppers enter their server tick
 * transfer path. Nearby hoppers and vanilla cooldown handling are unchanged.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBackoffMixin {
    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private static void chlorine$backoffDistantHopper(
            Level level,
            BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            HopperBlockEntity hopper,
            CallbackInfo ci) {
        if (!Chlorine.CONFIG.enableDistantHopperBackoff) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        Player nearest = serverLevel.getNearestPlayer(
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5,
                Chlorine.CONFIG.aiActiveRadius,
                entity -> true
        );
        if (nearest != null) {
            return;
        }

        int interval = Math.max(1, Chlorine.CONFIG.hopperBackoffInterval);
        if ((level.getGameTime() + pos.asLong()) % interval != 0) {
            ci.cancel();
        }
    }
}
