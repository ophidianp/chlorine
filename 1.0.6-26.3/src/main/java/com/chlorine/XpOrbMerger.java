package com.chlorine;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Periodically merges nearby ExperienceOrb entities near each player —
 * same idea as ItemMerger, for XP orbs instead of item stacks. Grinders
 * and farms routinely leave dozens of small floating orbs ticking
 * individually; vanilla already combines orbs awarded at the same instant
 * (see ExperienceOrb.award), but orbs already in flight toward the player
 * don't merge with each other.
 *
 * === SAFETY NOTE ===
 * Unlike item stacks, an orb's XP value isn't reachable through a public
 * API in the versions I'm familiar with — merging two orbs means reading
 * and combining a private `value` field. Getting that field's name wrong
 * for 26.2 would be a correctness bug, not just a compile error (it could
 * only fail *after* shipping, at runtime, and losing a player's XP is a
 * much worse failure than a feature quietly not working). To make that
 * failure mode impossible: this uses reflection to look up the field by
 * name, and permanently disables itself (falls back to doing nothing) the
 * first time that lookup or an update fails, for the rest of that game
 * session. No orb is ever discarded unless the other orb's value was
 * successfully increased first. If merging never seems to activate, open
 * net.minecraft.world.entity.ExperienceOrb in your IDE, confirm the real
 * field name, and update VALUE_FIELD_NAME below.
 */
public final class XpOrbMerger {
    private static final String VALUE_FIELD_NAME = "value";
    private static Field valueField;
    private static boolean fieldLookupAttempted = false;
    private static boolean disabled = false;

    private int ticksUntilNextPass = 0;
    private final Deque<ServerPlayer> pendingPlayers = new ArrayDeque<>();
    private MergeWork pendingWork;

    private XpOrbMerger() {
    }

    public static void register() {
        XpOrbMerger merger = new XpOrbMerger();
        ServerTickEvents.END_SERVER_TICK.register(merger::onServerTick);
    }

    private void onServerTick(MinecraftServer server) {
        if (!Chlorine.CONFIG.enableXpOrbMerging || disabled) {
            return;
        }
        if (pendingPlayers.isEmpty() && pendingWork == null && --ticksUntilNextPass > 0) {
            return;
        }

        double scanRadius = Chlorine.CONFIG.xpMergeScanRadius;
        double mergeDistSq = Chlorine.CONFIG.xpMergeRadius * Chlorine.CONFIG.xpMergeRadius;
        if (pendingPlayers.isEmpty() && pendingWork == null) {
            for (ServerLevel level : server.getAllLevels()) {
                pendingPlayers.addAll(level.players());
            }
        }

        long deadline = System.nanoTime()
                + (long) (Math.max(0.1, Chlorine.CONFIG.xpMergeBudgetMillis) * 1_000_000.0);
        while (System.nanoTime() < deadline) {
            if (pendingWork != null) {
                if (!pendingWork.process(deadline)) {
                    break;
                }
                pendingWork = null;
                continue;
            }
            ServerPlayer player = pendingPlayers.pollFirst();
            if (player == null) {
                ticksUntilNextPass = Math.max(20, Chlorine.CONFIG.xpMergeIntervalTicks);
                return;
            }
                if (player.isRemoved() || !(player.level() instanceof ServerLevel level)) {
                continue;
            }
                AABB box = player.getBoundingBox().inflate(scanRadius);
                List<ExperienceOrb> orbs = level.getEntitiesOfClass(ExperienceOrb.class, box);
            pendingWork = new MergeWork(orbs, mergeDistSq);
        }
    }

    private final class MergeWork {
        private final List<ExperienceOrb> orbs;
        private final EntitySpatialIndex<ExperienceOrb> spatialIndex;
        private final double mergeDistSq;
        private int index;

        private MergeWork(List<ExperienceOrb> orbs, double mergeDistSq) {
            this.orbs = orbs;
            this.mergeDistSq = mergeDistSq;
            this.spatialIndex = new EntitySpatialIndex<>(orbs, Math.sqrt(mergeDistSq));
        }

        private boolean process(long deadline) {
        for (; index < orbs.size(); index++) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            ExperienceOrb a = orbs.get(index);
            if (!a.isAlive()) {
                spatialIndex.remove(a);
                continue;
            }

            for (ExperienceOrb b : spatialIndex.nearbyAfter(index)) {
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                if (!b.isAlive()) {
                    spatialIndex.remove(b);
                    continue;
                }
                if (a.distanceToSqr(b) > mergeDistSq) {
                    continue;
                }

                Integer valueA = getValue(a);
                Integer valueB = getValue(b);
                if (valueA == null || valueB == null) {
                    disableSelf("couldn't read ExperienceOrb's value field");
                    return false;
                }
                if (!setValue(a, valueA + valueB)) {
                    disableSelf("couldn't write ExperienceOrb's value field");
                    return false;
                }
                b.discard();
                spatialIndex.remove(b);
            }
        }
        return true;
        }
    }

    private static void disableSelf(String reason) {
        disabled = true;
        Chlorine.LOGGER.warn(
            "Chlorine: {} — disabling XP orb merging for this session. No XP was lost; the feature just won't run.",
            reason
        );
    }

    private static Integer getValue(ExperienceOrb orb) {
        Field f = valueField();
        if (f == null) {
            return null;
        }
        try {
            return f.getInt(orb);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean setValue(ExperienceOrb orb, int newValue) {
        Field f = valueField();
        if (f == null) {
            return false;
        }
        try {
            f.setInt(orb, newValue);
            return true;
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    private static Field valueField() {
        if (fieldLookupAttempted) {
            return valueField;
        }
        fieldLookupAttempted = true;
        try {
            Field f = ExperienceOrb.class.getDeclaredField(VALUE_FIELD_NAME);
            f.setAccessible(true);
            valueField = f;
        } catch (NoSuchFieldException | SecurityException e) {
            valueField = null;
        }
        return valueField;
    }
}
