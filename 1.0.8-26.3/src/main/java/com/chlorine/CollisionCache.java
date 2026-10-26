package com.chlorine;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class CollisionCache {
    private static final Map<Entity, Entry> ENTRIES = new WeakHashMap<>();
    private static long worldVersion;

    private CollisionCache() {
    }

    public static synchronized List<VoxelShape> get(Entity entity, AABB box) {
        if (!Chlorine.CONFIG.enableCollisionCache || !eligible(entity)) {
            return null;
        }
        Entry entry = ENTRIES.get(entity);
        if (entry != null && entry.version == worldVersion && entry.box.equals(box)) {
            return entry.shapes;
        }
        return null;
    }

    public static synchronized void put(Entity entity, AABB box, List<VoxelShape> shapes) {
        if (!Chlorine.CONFIG.enableCollisionCache || !eligible(entity)) {
            return;
        }
        if (ENTRIES.size() > 2048) {
            ENTRIES.clear();
        }
        ENTRIES.put(entity, new Entry(worldVersion, box, Collections.unmodifiableList(shapes)));
    }

    /** Drops every cached entry; used by the memory governor under heap pressure. */
    public static synchronized void clear() {
        ENTRIES.clear();
    }

    public static synchronized void invalidate() {
        worldVersion++;
        if (worldVersion == Long.MAX_VALUE) {
            worldVersion = 0;
            ENTRIES.clear();
        }
    }

    private static boolean eligible(Entity entity) {
        return entity != null && !entity.isRemoved() && entity.getDeltaMovement().lengthSqr() < 1.0E-8;
    }

    private record Entry(long version, AABB box, List<VoxelShape> shapes) {
    }
}
