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
package com.hellblazer.luciferase.esvo.dag.cache;

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe LRU cache for DAG octree data.
 *
 * <p>Automatically evicts least-recently-used entries when capacity is reached.
 * Tracks access statistics (hits, misses, evictions) and memory usage.
 *
 * <h3>Thread Safety</h3>
 * <p>All operations are thread-safe using read-write locks for concurrent access.
 *
 * <h3>Memory Tracking</h3>
 * <p>Estimates memory usage as: sum of node counts × 8 bytes per node
 * (size of ESVONodeUnified).
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * var cache = new DAGCache(100, CacheEvictionPolicy.LRU);
 *
 * cache.put("scene1", dagData);
 * var data = cache.get("scene1"); // Cache hit
 *
 * var stats = cache.getStats();
 * System.out.printf("Hit rate: %.1f%%\n", stats.hitRate() * 100);
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class DAGCache {
    private static final int BYTES_PER_NODE = 8; // ESVONodeUnified size

    private final int maxSize;
    private final CacheEvictionPolicy policy;
    private final Map<String, DAGOctreeData> cache;
    private final ReadWriteLock lock;

    private final AtomicLong hitCount;
    private final AtomicLong missCount;
    private final AtomicLong evictionCount;
    private final AtomicLong totalAccessTimeNs;
    private final AtomicLong accessCount;

    /**
     * Create a DAG cache with the specified capacity and eviction policy.
     *
     * @param maxSize maximum number of entries (must be > 0)
     * @param policy eviction policy (must not be null, currently only LRU is implemented)
     * @throws IllegalArgumentException if maxSize <= 0
     * @throws NullPointerException if policy is null
     */
    public DAGCache(int maxSize, CacheEvictionPolicy policy) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        Objects.requireNonNull(policy, "policy cannot be null");

        this.maxSize = maxSize;
        this.policy = policy;
        this.lock = new ReentrantReadWriteLock();

        // LinkedHashMap with access-order for LRU
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, DAGOctreeData> eldest) {
                if (size() > maxSize) {
                    evictionCount.incrementAndGet();
                    return true;
                }
                return false;
            }
        };

        this.hitCount = new AtomicLong(0);
        this.missCount = new AtomicLong(0);
        this.evictionCount = new AtomicLong(0);
        this.totalAccessTimeNs = new AtomicLong(0);
        this.accessCount = new AtomicLong(0);
    }

    /**
     * Add or update an entry in the cache.
     *
     * <p>If the cache is full, the least-recently-used entry will be evicted.
     *
     * @param key cache key (must not be null)
     * @param value DAG data (must not be null)
     * @throws NullPointerException if key or value is null
     */
    public void put(String key, DAGOctreeData value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");

        var startTime = System.nanoTime();
        lock.writeLock().lock();
        try {
            cache.put(key, value);
        } finally {
            lock.writeLock().unlock();
            recordAccessTime(startTime);
        }
    }

    /**
     * Retrieve an entry from the cache.
     *
     * <p>Updates access time for LRU tracking.
     *
     * @param key cache key (must not be null)
     * @return cached data, or null if not found
     * @throws NullPointerException if key is null
     */
    public DAGOctreeData get(String key) {
        Objects.requireNonNull(key, "key cannot be null");

        var startTime = System.nanoTime();
        lock.writeLock().lock(); // Write lock because access order changes
        try {
            var value = cache.get(key);

            if (value != null) {
                hitCount.incrementAndGet();
            } else {
                missCount.incrementAndGet();
            }

            return value;
        } finally {
            lock.writeLock().unlock();
            recordAccessTime(startTime);
        }
    }

    /**
     * Manually evict the least-recently-used entry.
     *
     * <p>No-op if cache is empty.
     */
    public void evict() {
        lock.writeLock().lock();
        try {
            if (!cache.isEmpty()) {
                var iterator = cache.entrySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                    evictionCount.incrementAndGet();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Clear all entries from the cache.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            cache.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get cache statistics.
     *
     * @return current statistics snapshot
     */
    public CacheStats getStats() {
        var avgAccessTimeMs = 0.0f;
        var count = accessCount.get();
        if (count > 0) {
            avgAccessTimeMs = totalAccessTimeNs.get() / (float) count / 1_000_000.0f;
        }

        return new CacheStats(
            hitCount.get(),
            missCount.get(),
            evictionCount.get(),
            avgAccessTimeMs
        );
    }

    /**
     * Get current cache size.
     *
     * @return number of entries in cache
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
     * Estimate total memory usage in bytes.
     *
     * <p>Calculated as: sum of node counts × 8 bytes per node.
     *
     * @return estimated memory usage in bytes
     */
    public long estimatedMemoryBytes() {
        lock.readLock().lock();
        try {
            var totalNodes = 0L;
            for (var data : cache.values()) {
                totalNodes += data.nodeCount();
            }
            return totalNodes * BYTES_PER_NODE;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Record access time for statistics.
     */
    private void recordAccessTime(long startTimeNs) {
        var elapsedNs = System.nanoTime() - startTimeNs;
        totalAccessTimeNs.addAndGet(elapsedNs);
        accessCount.incrementAndGet();
    }
}
