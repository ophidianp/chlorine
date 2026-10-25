package com.chlorine.mixin;

import com.chlorine.AutosaveSmoothing;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the start and end of the periodic autosave so AutosaveChunkLimitMixin
 * only limits that save and nothing else.
 */
@Mixin(MinecraftServer.class)
public abstract class AutosaveWindowMixin {

    @Inject(method = "autoSave", at = @At("HEAD"))
    private void chlorine$beginAutosave(CallbackInfo ci) {
        AutosaveSmoothing.begin();
    }

    @Inject(method = "autoSave", at = @At("RETURN"))
    private void chlorine$endAutosave(CallbackInfo ci) {
        AutosaveSmoothing.end();
    }
}
