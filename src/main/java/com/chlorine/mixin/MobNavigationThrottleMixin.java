package com.chlorine.mixin;

import com.chlorine.Chlorine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reduces path-following work for mobs that are outside the active player
 * radius. The option is off by default because path cadence can affect
 * farms or custom mob behavior in ways that the AI throttle does not.
 */
@Mixin(Mob.class)
public abstract class MobNavigationThrottleMixin {
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void chlorine$throttleDistantNavigation(CallbackInfo ci) {
        if (!Chlorine.CONFIG.enableDistantMobNavigationThrottle) {
            return;
        }

        Mob self = (Mob) (Object) this;
        if (!(self.level() instanceof ServerLevel level)) {
            return;
        }

        Player nearest = level.getNearestPlayer(self, Chlorine.CONFIG.aiActiveRadius);
        if (nearest != null) {
            return;
        }

        int interval = Math.max(1, Chlorine.CONFIG.navigationThrottleInterval);
        if ((level.getGameTime() + self.getId()) % interval != 0) {
            ci.cancel();
        }
    }
}
