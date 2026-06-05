/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.internal;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for frequently accessed entity data to reduce repeated lookups.
 * <p>
 * <b>Eviction is NOT LRU</b> (Luciferase-lsy13): the backing store is a {@link java.util.concurrent.ConcurrentHashMap},
 * which tracks neither access nor insertion order. When the cache exceeds its limit, {@code evictOldest()} bulk-removes
 * roughly 25% of entries in the map's (arbitrary) iteration order — there is no recency information, so "oldest" is a
 * misnomer. This is a cheap pressure-relief valve, not a recency policy. Implementing real LRU would require an
 * access-ordered structure (e.g. a synchronized access-order LinkedHashMap) and per-entry access tracking.
 * <p>
 * <b>Size-bound coherence (Luciferase-7wzml.127)</b>: size is read directly from {@link ConcurrentHashMap#size()}
 * rather than a separately-maintained counter, eliminating TOCTOU drift between the counter and the map. Eviction loops
 * until {@code cache.size() <= maxSize}, bounding transient overshoot to at most {@code (threads - 1)} entries beyond
 * {@code maxSize} in the worst case — the same bound that any lock-free design must accept for a hot path.
 *
 * @param <ID> The entity ID type
 * @author hal.hildebrand
 */
public class EntityCache<ID extends EntityID> {

    private final ConcurrentHashMap<ID, CachedEntityData> cache;
    private final int                                      maxSize;

    // Statistics for monitoring
    private final java.util.concurrent.atomic.AtomicInteger hits   = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger misses = new java.util.concurrent.atomic.AtomicInteger();

    public EntityCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0, got " + maxSize);
        }
        this.maxSize = maxSize;
        this.cache = new ConcurrentHashMap<>(maxSize);
    }
    
    /**
     * Get cached entity bounds
     */
    public EntityBounds getBounds(ID entityId) {
        var data = cache.get(entityId);
        if (data != null) {
            hits.incrementAndGet();
            return data.bounds;
        }
        misses.incrementAndGet();
        return null;
    }
    
    /**
     * Get cached entity position
     */
    public Point3f getPosition(ID entityId) {
        var data = cache.get(entityId);
        if (data != null) {
            hits.incrementAndGet();
            return data.position;
        }
        misses.incrementAndGet();
        return null;
    }
    
    /**
     * Cache entity data. If the cache is at or above {@code maxSize}, eviction is attempted before insertion. Under
     * concurrent puts, transient overshoot of at most {@code (concurrentPutters - 1)} entries is possible and
     * acceptable (lock-free design bound). Eviction loops until the map is back within bounds or the iterator is
     * exhausted.
     */
    public void put(ID entityId, Point3f position, EntityBounds bounds) {
        // Drive size check off cache.size() — single source of truth, no separate counter.
        // Evict while at or over capacity so there is always room for the incoming entry.
        // Under concurrent puts, transient overshoot of at most (concurrentPutters - 1) entries
        // past maxSize is possible (all threads pass the check before any eviction completes) —
        // an unavoidable lock-free bound.
        while (cache.size() >= maxSize) {
            evictOldest();
        }

        cache.put(entityId, new CachedEntityData(position, bounds));
    }
    
    /**
     * Remove entity from cache
     */
    public void remove(ID entityId) {
        cache.remove(entityId);
    }

    /**
     * Clear the cache
     */
    public void clear() {
        cache.clear();
    }
    
    /**
     * Get cache statistics. {@code size} is the live {@link ConcurrentHashMap#size()} value (consistent with the
     * eviction policy). {@code hits} and {@code misses} are read non-atomically as three separate reads; the individual
     * counters are accurate but the snapshot is not a single point-in-time view — acceptable for monitoring use.
     */
    public CacheStats getStats() {
        return new CacheStats(hits.get(), misses.get(), cache.size());
    }
    
    /**
     * Reset statistics
     */
    public void resetStats() {
        hits.set(0);
        misses.set(0);
    }
    
    private void evictOldest() {
        // NOT LRU (Luciferase-lsy13): ConcurrentHashMap has no access/insertion order, so this removes ~25% of
        // entries in arbitrary iteration order — a pressure-relief valve, not a recency eviction.
        int toRemove = Math.max(1, maxSize / 4);
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext() && toRemove > 0) {
            iterator.next();
            iterator.remove();
            toRemove--;
        }
    }
    
    /**
     * Cached entity data
     */
    private static class CachedEntityData {
        final Point3f position;
        final EntityBounds bounds;
        
        CachedEntityData(Point3f position, EntityBounds bounds) {
            this.position = position != null ? new Point3f(position) : null;
            this.bounds = bounds;
        }
    }
    
    /**
     * Cache statistics
     */
    public record CacheStats(int hits, int misses, int size) {
        public double hitRate() {
            int total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
    }
}