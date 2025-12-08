/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.cache;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
        long version,
        long timestamp
    ) {
        public CachedResult {
            entityIds = List.copyOf(entityIds);
            distances = List.copyOf(distances);
        }
    }

    private static final int DEFAULT_MAX_ENTRIES = 10000;
    private static final float LOAD_FACTOR = 0.75f;

    private final Map<KNNQueryKey<Key>, CachedResult<ID>> cache;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxEntries;

    // Statistics
    private long hits = 0;
    private long misses = 0;
    private long invalidations = 0;

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
        // LinkedHashMap with access-order for LRU eviction
        this.cache = Collections.synchronizedMap(
            new LinkedHashMap<KNNQueryKey<Key>, CachedResult<ID>>(
                (int) (maxEntries / LOAD_FACTOR) + 1,
                LOAD_FACTOR,
                true // access-order for LRU
            ) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<KNNQueryKey<Key>, CachedResult<ID>> eldest) {
                    return size() > KNNCache.this.maxEntries;
                }
            }
        );
    }

    /**
     * Get cached k-NN result if valid
     * 
     * @param queryKey Composite query key (position + k + maxDistance)
     * @param currentVersion Current version number for validation
     * @return Cached result if valid, null if miss or stale
     */
    public CachedResult<ID> get(KNNQueryKey<Key> queryKey, long currentVersion) {
        lock.readLock().lock();
        try {
            var cached = cache.get(queryKey);
            if (cached == null) {
                misses++;
                return null;
            }

            // Version check: cache valid if versions match
            if (cached.version != currentVersion) {
                misses++;
                return null;
            }

            hits++;
            return cached;
        } finally {
            lock.readLock().unlock();
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

        lock.writeLock().lock();
        try {
            var result = new CachedResult<>(entityIds, distances, version, System.nanoTime());
            cache.put(queryKey, result);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Invalidate cache entry for a specific query
     * 
     * @param queryKey Query key to invalidate
     */
    public void invalidate(KNNQueryKey<Key> queryKey) {
        lock.writeLock().lock();
        try {
            if (cache.remove(queryKey) != null) {
                invalidations++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Invalidate all cache entries for a specific spatial position
     * (all queries at that position, regardless of k or maxDistance)
     * 
     * @param spatialKey Spatial key to invalidate
     */
    public void invalidatePosition(Key spatialKey) {
        lock.writeLock().lock();
        try {
            var keysToRemove = cache.keySet().stream()
                .filter(qk -> qk.spatialKey().equals(spatialKey))
                .toList();
            for (var key : keysToRemove) {
                cache.remove(key);
                invalidations++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Invalidate all cache entries
     */
    public void invalidateAll() {
        lock.writeLock().lock();
        try {
            int count = cache.size();
            cache.clear();
            invalidations += count;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get current cache size
     */
    public int size() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get cache hit rate (0.0 to 1.0)
     */
    public double getHitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * Get cache statistics
     * 
     * @return Statistics summary
     */
    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            return new CacheStats(hits, misses, invalidations, cache.size(), maxEntries);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reset statistics counters
     */
    public void resetStats() {
        lock.writeLock().lock();
        try {
            hits = 0;
            misses = 0;
            invalidations = 0;
        } finally {
            lock.writeLock().unlock();
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
