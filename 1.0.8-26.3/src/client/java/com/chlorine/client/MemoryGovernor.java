package com.chlorine.client;

import com.chlorine.Chlorine;
import com.chlorine.CollisionCache;
import net.minecraft.client.Minecraft;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

/**
 * Heap-pressure handling. The JVM owns garbage collection, so this does three
 * small things around it: trim Chlorine's caches near a full heap, request one
 * GC when a world is left, and warn when post-GC memory keeps growing.
 */
public final class MemoryGovernor {
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final long GC_COOLDOWN_MILLIS = 60_000L;
    // Consecutive checks where retained memory rose before a leak is suspected.
    private static final int LEAK_STREAK = 6;
    private static final long LEAK_MIN_GROWTH_BYTES = 8L * 1024 * 1024;

    private static int ticks;
    private static boolean wasInWorld;
    private static long lastGcMillis;
    private static long lastRetained = -1;
    private static long streakStart;
    private static int streak;
    private static boolean leakWarned;

    private MemoryGovernor() {
    }

    public static void tick(Minecraft client) {
        if (!Chlorine.CONFIG.enableMemoryGovernor) {
            return;
        }
        boolean inWorld = client.level != null;
        if (wasInWorld && !inWorld) {
            onWorldLeft();
        }
        wasInWorld = inWorld;

        if (++ticks < CHECK_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        trimIfNeeded();
        if (inWorld && Chlorine.CONFIG.enableLeakWatch) {
            watchLeak();
        }
    }

    private static void trimIfNeeded() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        if (max > 0 && used * 100 >= max * Chlorine.CONFIG.memoryTrimHeapPercent) {
            CollisionCache.clear();
        }
    }

    private static void onWorldLeft() {
        // A new world will load different data; drop what belonged to this one.
        CollisionCache.clear();
        lastRetained = -1;
        streak = 0;
        leakWarned = false;
        long now = System.currentTimeMillis();
        if (!Chlorine.CONFIG.enableGcOnWorldLeave || now - lastGcMillis < GC_COOLDOWN_MILLIS) {
            return;
        }
        lastGcMillis = now;
        // System.gc() blocks its caller, so keep it off the render thread.
        Thread t = new Thread(System::gc, "Chlorine-GC");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Memory still in use right after the last collection is what the game
     * actually holds on to. If it only goes up across many checks, something
     * is retaining objects.
     */
    private static void watchLeak() {
        long retained = retainedAfterGc();
        if (retained < 0) {
            return;
        }
        if (lastRetained >= 0 && retained > lastRetained) {
            if (streak == 0) {
                streakStart = lastRetained;
            }
            streak++;
        } else if (retained < lastRetained) {
            streak = 0;
        }
        lastRetained = retained;
        if (!leakWarned && streak >= LEAK_STREAK && retained - streakStart >= LEAK_MIN_GROWTH_BYTES) {
            leakWarned = true;
            Chlorine.LOGGER.warn("Memory kept after garbage collection grew {} MB over the last {} checks; "
                            + "something may be leaking (possibly another mod).",
                    (retained - streakStart) / (1024 * 1024), streak);
        }
    }

    private static long retainedAfterGc() {
        long total = 0;
        boolean found = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            MemoryUsage usage = pool.getCollectionUsage();
            if (usage != null) {
                total += usage.getUsed();
                found = true;
            }
        }
        return found ? total : -1;
    }
}
