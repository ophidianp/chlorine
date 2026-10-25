package com.chlorine.mixin;

import com.chlorine.AutosaveSmoothing;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Spreads the periodic autosave over several ticks.
 *
 * With flush=false (the autosave case), ChunkMap.saveAllChunks() clears the
 * per-chunk save cooldowns and calls saveChunkIfNeeded() on every loaded
 * chunk in the same tick, serializing every changed chunk at once. That's
 * the stutter you feel every 5 minutes.
 *
 * During an autosave this lets the first N saves through, then hands every
 * further unsaved chunk to vanilla's own background saver by adding it to
 * chunksToEagerlySave. ChunkMap.tick() already drains that set at 20
 * chunks per tick, and it only drops a chunk once it is saved or no longer
 * unsaved, so nothing is lost; it is just written a few seconds later.
 *
 * The flush=true path (shutdown, /save-all flush) doesn't call
 * saveChunkIfNeeded, and AutosaveSmoothing only limits while inside
 * MinecraftServer.autoSave(), so every other save behaves exactly as vanilla.
 */
@Mixin(ChunkMap.class)
public abstract class AutosaveChunkLimitMixin {

    @Shadow
    @Final
    private LongSet chunksToEagerlySave;

    @WrapOperation(
            method = "saveAllChunks(Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;saveChunkIfNeeded(Lnet/minecraft/server/level/ChunkHolder;J)Z"))
    private boolean chlorine$limitAutosaveBurst(
            ChunkMap self,
            ChunkHolder holder,
            long now,
            Operation<Boolean> original) {
        if (!AutosaveSmoothing.isLimiting() || AutosaveSmoothing.hasBudget()) {
            boolean saved = original.call(self, holder, now);
            if (saved) {
                AutosaveSmoothing.recordSaved();
            }
            return saved;
        }

        ChunkAccess chunk = holder.getLatestChunk();
        if (chunk != null && chunk.isUnsaved()) {
            chunksToEagerlySave.add(holder.getPos().pack());
            AutosaveSmoothing.recordDeferred();
        }
        return false;
    }
}
