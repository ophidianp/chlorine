package com.chlorine.mixin.client;

import com.chlorine.Chlorine;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Deque;

/**
 * Time-budgets the client light queue once it has a large backlog.
 *
 * Vanilla's pollLightUpdates() runs max(10, size / 10) queued jobs while
 * fewer than 1000 are waiting, but once 1000+ pile up (new chunks streaming
 * in during elytra flight, a teleport, joining a world) it runs ALL of them
 * in one call. That one long frame is what shows up as a 1% low.
 *
 * Above that threshold this runs jobs until the time budget is used up,
 * with a minimum per call so the backlog always shrinks. Jobs still run in
 * the same FIFO order, so chunk light enable/disable ordering is unchanged;
 * new chunks may just appear unlit for a few frames longer. Below the
 * threshold vanilla's own path runs untouched.
 */
@Mixin(ClientLevel.class)
public abstract class LightUpdateSmoothingMixin {
    private static final int VANILLA_DRAIN_ALL_THRESHOLD = 1000;

    @Shadow
    @Final
    private Deque<Runnable> lightUpdateQueue;

    @Inject(method = "pollLightUpdates", at = @At("HEAD"), cancellable = true)
    private void chlorine$budgetLightBacklog(CallbackInfo ci) {
        if (!Chlorine.CONFIG.enableLightUpdateSmoothing
                || lightUpdateQueue.size() < VANILLA_DRAIN_ALL_THRESHOLD) {
            return;
        }

        int minimum = Math.max(10, Chlorine.CONFIG.minLightUpdatesPerFrame);
        long budgetNanos = (long) (Math.max(0.1, Chlorine.CONFIG.lightUpdateBudgetMillis) * 1_000_000.0);
        long deadline = System.nanoTime() + budgetNanos;
        int ran = 0;
        Runnable job;
        while ((job = lightUpdateQueue.poll()) != null) {
            job.run();
            ran++;
            // Check the clock every 16 jobs; nanoTime isn't free.
            if (ran >= minimum && (ran & 15) == 0 && System.nanoTime() >= deadline) {
                break;
            }
        }
        ci.cancel();
    }
}
