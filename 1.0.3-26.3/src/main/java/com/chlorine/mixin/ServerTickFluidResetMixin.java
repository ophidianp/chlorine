package com.chlorine.mixin;

import com.chlorine.FluidTickGovernor;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerTickFluidResetMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void chlorine$resetFluidBudget(java.util.function.BooleanSupplier haveTime, CallbackInfo ci) {
        FluidTickGovernor.beginServerTick();
    }
}
