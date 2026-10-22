package com.chlorine.client.mixin;

import com.chlorine.util.ChlorineMath;
import net.minecraft.client.particle.FallingParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FallingParticle.class)
public abstract class FallingParticleMathMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Ljava/lang/Math;sin(D)D"), require = 0)
    private double chlorine$fastSin(double radians) {
        return ChlorineMath.sin(radians);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Ljava/lang/Math;cos(D)D"), require = 0)
    private double chlorine$fastCos(double radians) {
        return ChlorineMath.cos(radians);
    }
}
