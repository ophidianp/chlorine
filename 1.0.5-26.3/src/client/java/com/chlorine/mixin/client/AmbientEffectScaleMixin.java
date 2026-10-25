package com.chlorine.mixin.client;

import com.chlorine.Chlorine;
import com.chlorine.client.RenderPressure;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Samples fewer blocks for ambient block effects while under render pressure.
 *
 * ClientLevel.animateTick() loops 667 times per client tick, and each pass
 * picks one random block within 16 blocks and one within 32 blocks, reads
 * its block and fluid state, and lets it spawn effects (torch flames,
 * dripping water, leaf particles, ...). That's 1334 random block lookups a
 * tick even when nothing is spawned. This scales the 667 loop count.
 *
 * Only active while RenderPressure says FPS is low; otherwise vanilla's
 * count is returned unchanged.
 */
@Mixin(ClientLevel.class)
public abstract class AmbientEffectScaleMixin {
    private static final int MIN_SAMPLES = 64;

    @ModifyExpressionValue(method = "animateTick(III)V", at = @At(value = "CONSTANT", args = "intValue=667"))
    private int chlorine$scaleAmbientSamples(int original) {
        if (!Chlorine.CONFIG.enableAdaptiveAmbientEffects || !RenderPressure.isActive()) {
            return original;
        }
        double scale = Math.max(0.1, Math.min(1.0, Chlorine.CONFIG.ambientEffectsLowFpsScale));
        return Math.max(MIN_SAMPLES, (int) (original * scale));
    }
}
