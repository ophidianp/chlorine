package com.chlorine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Indexes entities by position so merge passes only compare candidates in
 * nearby grid cells. Candidate order matches the original entity list.
 *
 * Allocation-free on the hot path: cells are packed into a single long key
 * (no per-lookup record objects), buckets are primitive int lists (no
 * Integer boxing), and removal just flags the entity instead of editing
 * hash sets. Each bucket is filled in ascending index order, so it is
 * already sorted; only the merged result of the 27 neighbouring buckets
 * needs sorting.
 *
 * Cell coordinates are packed as x:26 bits | z:26 bits | y:12 bits. That
 * covers the +/-30M world border and any realistic build height. If an
 * entity somehow lands outside that range the key can alias another
 * cell, which only adds extra candidates; callers already distance-check
 * every candidate, so correctness is unaffected.
 */
final class EntitySpatialIndex<T extends Entity> {
    private final List<T> entities;
    private final double cellSize;
    private final Long2ObjectOpenHashMap<IntArrayList> buckets = new Long2ObjectOpenHashMap<>();
    private final IdentityHashMap<T, Integer> indexes = new IdentityHashMap<>();
    private final boolean[] removed;

    private final IntArrayList candidateScratch = new IntArrayList();
    private final List<T> nearbyScratch = new ArrayList<>();

    EntitySpatialIndex(List<T> entities, double searchRadius) {
        this.entities = entities;
        this.cellSize = Math.max(1.0, searchRadius);
        this.removed = new boolean[entities.size()];

        for (int i = 0; i < entities.size(); i++) {
            T entity = entities.get(i);
            indexes.put(entity, i);
            long key = key(cellCoord(entity.getX()), cellCoord(entity.getY()), cellCoord(entity.getZ()));
            IntArrayList bucket = buckets.get(key);
            if (bucket == null) {
                bucket = new IntArrayList(4);
                buckets.put(key, bucket);
            }
            bucket.add(i);
        }
    }

    List<T> nearbyAfter(int index) {
        T entity = entities.get(index);
        long cx = cellCoord(entity.getX());
        long cy = cellCoord(entity.getY());
        long cz = cellCoord(entity.getZ());
        candidateScratch.clear();
        nearbyScratch.clear();

        for (long x = cx - 1; x <= cx + 1; x++) {
            for (long y = cy - 1; y <= cy + 1; y++) {
                for (long z = cz - 1; z <= cz + 1; z++) {
                    IntArrayList bucket = buckets.get(key(x, y, z));
                    if (bucket == null) {
                        continue;
                    }
                    for (int i = 0, n = bucket.size(); i < n; i++) {
                        int candidate = bucket.getInt(i);
                        if (candidate > index && !removed[candidate]) {
                            candidateScratch.add(candidate);
                        }
                    }
                }
            }
        }

        Arrays.sort(candidateScratch.elements(), 0, candidateScratch.size());
        for (int i = 0, n = candidateScratch.size(); i < n; i++) {
            nearbyScratch.add(entities.get(candidateScratch.getInt(i)));
        }
        return nearbyScratch;
    }

    void remove(T entity) {
        Integer index = indexes.remove(entity);
        if (index != null) {
            removed[index] = true;
        }
    }

    private long cellCoord(double position) {
        return (long) Math.floor(position / cellSize);
    }

    private static long key(long x, long y, long z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }
}
