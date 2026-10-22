package com.chlorine.client.mixin;

import com.chlorine.util.ChlorineMath;
import net.minecraft.client.particle.NoteParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NoteParticle.class)
public abstract class NoteParticleMathMixin {
    @Redirect(method = "getQuadSize", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;sin(D)F"), require = 0)
    private float chlorine$fastSin(double radians) {
        return ChlorineMath.sin(radians);
    }
}
