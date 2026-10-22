package com.chlorine;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;

import java.util.IdentityHashMap;
import java.util.Map;

public final class FluidTickGovernor {
    private static final Map<ServerLevel, Integer> COUNTS = new IdentityHashMap<>();

    private FluidTickGovernor() {
    }

    public static synchronized void beginServerTick() {
        COUNTS.clear();
    }

    public static synchronized boolean shouldDefer(ServerLevel level, Fluid fluid) {
        if (!Chlorine.CONFIG.enableFluidTickGovernor || Chlorine.CONFIG.maxFluidTicksPerTick <= 0) {
            return false;
        }
        int count = COUNTS.getOrDefault(level, 0);
        if (count >= Chlorine.CONFIG.maxFluidTicksPerTick) {
            return true;
        }
        COUNTS.put(level, count + 1);
        return false;
    }
}
