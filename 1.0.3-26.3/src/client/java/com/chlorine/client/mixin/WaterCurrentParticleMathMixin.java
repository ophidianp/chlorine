package com.chlorine.client.mixin;

import com.chlorine.util.ChlorineMath;
import net.minecraft.client.particle.WaterCurrentDownParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WaterCurrentDownParticle.class)
public abstract class WaterCurrentParticleMathMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;sin(D)F"), require = 0)
    private float chlorine$fastSin(double radians) {
        return ChlorineMath.sin(radians);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;cos(D)F"), require = 0)
    private float chlorine$fastCos(double radians) {
        return ChlorineMath.cos(radians);
    }
}
