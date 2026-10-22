package com.chlorine.mixin;

import com.chlorine.FluidTickGovernor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class FluidTickGovernorMixin {
    @Inject(method = "tickFluid", at = @At("HEAD"), cancellable = true, require = 0)
    private void chlorine$limitFluidTicks(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (FluidTickGovernor.shouldDefer(level, fluid)) {
            level.getFluidTicks().schedule(new ScheduledTick<>(
                    fluid, pos, level.getOverworldClockTime() + 1L, level.nextSubTickCount()));
            ci.cancel();
        }
    }
}
