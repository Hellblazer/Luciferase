/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.cache;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe LRU cache for k-nearest neighbor search results.
 * 
 * Implements the caching strategy from "Space-Filling Trees for Motion Planning":
 * - Cache k-NN results using composite query key (position + k + maxDistance)
 * - Version tracking for invalidation
 * - LRU eviction for memory management
 * - 20-30× speedup for cache hits (0.05-0.1ms vs 0.3-0.5ms)
 * 
 * Target hit rate: 50-70% for typical motion planning scenarios
 *
 * @author hal.hildebrand
 * @param <Key> Spatial key type (MortonKey or TetreeKey)
 * @param <ID> Entity ID type
 */
public class KNNCache<Key extends SpatialKey<Key>, ID extends EntityID> {

    /**
     * Cached k-NN search result with versioning
     */
    public record CachedResult<ID>(
        List<ID> entityIds,
        List<Float> distances,
        long version
    ) {
        public CachedResult {
            entityIds = List.copyOf(entityIds);
            distances = List.copyOf(distances);
        }
    }

    private static final int DEFAULT_MAX_ENTRIES = 10000;
    private static final float LOAD_FACTOR = 0.75f;

    // Cache is a LinkedHashMap with access-order semantics — get() mutates
    // the access-order list, so every public op (including reads) must
    // exclude all other accessors. The prior implementation paired a
    // ReadWriteLock (allowing concurrent get() under read mode) with
    // Collections.synchronizedMap. Despite the layered look, the
    // synchronizedMap wrapper actually held its internal monitor across the
    // full LinkedHashMap.get() call (including the access-order mutation),
    // so the two-lock scheme was correct but redundant — every effective
    // serialization happened on the synchronizedMap monitor. A single
    // ReentrantLock provides the same semantics with one obvious primitive
    // and removes the locked-inside-a-lock confusion the prior pattern
    // invited.
    private final Map<KNNQueryKey<Key>, CachedResult<ID>> cache;
    private final ReentrantLock lock = new ReentrantLock();
    private final int maxEntries;

    // Statistics — incremented under the cache lock. Counters are read under
    // the cache lock via getStats() for a consistent (hits, misses, invalidations, size)
    // snapshot; they are no longer a lock-free approximate read. AtomicLong is retained
    // for the increment-without-result pattern (no return value needed at write sites).
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    /**
     * Create k-NN cache with default capacity (10,000 entries)
     */
    public KNNCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Create k-NN cache with specified capacity
     * 
     * @param maxEntries Maximum number of cached entries (LRU eviction when exceeded)
     */
    public KNNCache(int maxEntries) {
        this.maxEntries = maxEntries;
        // LinkedHashMap with access-order for LRU eviction. No synchronizedMap
        // wrapper — all access is serialized by the outer ReentrantLock.
        this.cache = new LinkedHashMap<>(
            (int) (maxEntries / LOAD_FACTOR) + 1,
            LOAD_FACTOR,
            true // access-order for LRU
        ) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<KNNQueryKey<Key>, CachedResult<ID>> eldest) {
                return size() > KNNCache.this.maxEntries;
            }
        };
    }

    /**
     * Get cached k-NN result if valid
     * 
     * @param queryKey Composite query key (position + k + maxDistance)
     * @param currentVersion Current version number for validation
     * @return Cached result if valid, null if miss or stale
     */
    public CachedResult<ID> get(KNNQueryKey<Key> queryKey, long currentVersion) {
        lock.lock();
        try {
            var cached = cache.get(queryKey);
            if (cached == null) {
                misses.incrementAndGet();
                return null;
            }

            // Version check: cache valid if versions match
            if (cached.version != currentVersion) {
                misses.incrementAndGet();
                return null;
            }

            hits.incrementAndGet();
            return cached;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cache k-NN search result
     *
     * @param queryKey Composite query key (position + k + maxDistance)
     * @param entityIds List of entity IDs in order of increasing distance
     * @param distances Corresponding distances
     * @param version Current version number
     */
    public void put(KNNQueryKey<Key> queryKey, List<ID> entityIds, List<Float> distances, long version) {
        if (entityIds.size() != distances.size()) {
            throw new IllegalArgumentException(
                "Entity IDs and distances must have same size: " +
                entityIds.size() + " vs " + distances.size()
            );
        }

        lock.lock();
        try {
            var result = new CachedResult<>(entityIds, distances, version);
            cache.put(queryKey, result);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Invalidate cache entry for a specific query
     *
     * @param queryKey Query key to invalidate
     */
    public void invalidate(KNNQueryKey<Key> queryKey) {
        lock.lock();
        try {
            if (cache.remove(queryKey) != null) {
                invalidations.incrementAndGet();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Invalidate all cache entries for a specific spatial position
     * (all queries at that position, regardless of k or maxDistance)
     *
     * @param spatialKey Spatial key to invalidate
     */
    public void invalidatePosition(Key spatialKey) {
        lock.lock();
        try {
            var keysToRemove = cache.keySet().stream()
                .filter(qk -> qk.spatialKey().equals(spatialKey))
                .toList();
            for (var key : keysToRemove) {
                cache.remove(key);
                invalidations.incrementAndGet();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Invalidate all cache entries
     */
    public void invalidateAll() {
        lock.lock();
        try {
            int count = cache.size();
            cache.clear();
            invalidations.addAndGet(count);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get current cache size
     */
    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get cache hit rate (0.0 to 1.0). Takes the cache lock to produce a snapshot
     * consistent with {@link #getStats()} — the two methods now agree on the hit rate
     * for the same moment in time (Luciferase-7wzml.149).
     */
    public double getHitRate() {
        return getStats().hitRate();
    }

    /**
     * Get cache statistics
     *
     * @return Statistics summary
     */
    public CacheStats getStats() {
        lock.lock();
        try {
            return new CacheStats(hits.get(), misses.get(), invalidations.get(), cache.size(), maxEntries);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reset statistics counters
     */
    public void resetStats() {
        lock.lock();
        try {
            hits.set(0);
            misses.set(0);
            invalidations.set(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cache statistics snapshot
     */
    public record CacheStats(
        long hits,
        long misses,
        long invalidations,
        int currentSize,
        int maxSize
    ) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }

        @Override
        public String toString() {
            return String.format(
                "KNNCache[hits=%d, misses=%d, hitRate=%.1f%%, invalidations=%d, size=%d/%d]",
                hits, misses, hitRate() * 100, invalidations, currentSize, maxSize
            );
        }
    }
}
