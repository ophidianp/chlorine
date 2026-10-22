package com.chlorine.mixin;

import com.chlorine.CollisionCache;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class CollisionCacheInvalidationMixin {
    @Inject(method = "setBlock", at = @At("HEAD"), require = 0)
    private void chlorine$invalidate(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                     CallbackInfoReturnable<Boolean> cir) {
        CollisionCache.invalidate();
    }
}
