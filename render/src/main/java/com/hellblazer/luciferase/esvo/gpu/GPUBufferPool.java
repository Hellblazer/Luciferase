/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvo.gpu;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Buffer pool for GPU memory allocation with LRU eviction.
 *
 * <p>Manages a pool of reusable GPU buffers to reduce allocation overhead
 * and fragmentation. Supports multiple buffer size classes with LRU
 * eviction when memory pressure is detected.
 *
 * <p>Key features:
 * <ul>
 *   <li>Size-class based pooling (powers of 2)</li>
 *   <li>LRU eviction of least recently used buffers</li>
 *   <li>Thread-safe allocation and release</li>
 *   <li>Statistics tracking for monitoring</li>
 * </ul>
 *
 * @see GPUMemoryManager
 */
public class GPUBufferPool {

    /**
     * Represents a pooled buffer allocation.
     */
    public record PooledBuffer(
        String id,
        long sizeBytes,
        long sizeClass,
        long allocatedAtNs,
        long lastAccessNs
    ) {
        /**
         * Updates last access time.
         */
        public PooledBuffer touch() {
            return new PooledBuffer(id, sizeBytes, sizeClass, allocatedAtNs, System.nanoTime());
        }
    }

    /**
     * Statistics for buffer pool monitoring.
     */
    public record PoolStats(
        int activeBuffers,
        int freeBuffers,
        long activeBytes,
        long freeBytes,
        long allocations,
        long deallocations,
        long evictions,
        long hitCount,
        long missCount
    ) {
        /**
         * Returns hit rate (0.0 to 1.0).
         */
        public double hitRate() {
            long total = hitCount + missCount;
            return total > 0 ? (double) hitCount / total : 0.0;
        }
    }

    // Size class boundaries (powers of 2)
    private static final long MIN_SIZE_CLASS = 64 * 1024;  // 64 KB
    private static final long MAX_SIZE_CLASS = 256 * 1024 * 1024;  // 256 MB

    private final long maxPoolBytes;
    private final Map<String, PooledBuffer> activeBuffers;
    private final Map<Long, LinkedList<PooledBuffer>> freeBuffersByClass;
    private final ReentrantReadWriteLock lock;

    // Statistics
    private final AtomicLong totalActiveBytes;
    private final AtomicLong totalFreeBytes;
    private final AtomicLong allocations;
    private final AtomicLong deallocations;
    private final AtomicLong evictions;
    private final AtomicLong hitCount;
    private final AtomicLong missCount;

    /**
     * Creates a buffer pool with the specified maximum size.
     *
     * @param maxPoolBytes maximum bytes the pool can hold
     */
    public GPUBufferPool(long maxPoolBytes) {
        if (maxPoolBytes <= 0) {
            throw new IllegalArgumentException("Max pool bytes must be positive");
        }

        this.maxPoolBytes = maxPoolBytes;
        this.activeBuffers = new ConcurrentHashMap<>();
        this.freeBuffersByClass = new HashMap<>();
        this.lock = new ReentrantReadWriteLock();

        this.totalActiveBytes = new AtomicLong(0);
        this.totalFreeBytes = new AtomicLong(0);
        this.allocations = new AtomicLong(0);
        this.deallocations = new AtomicLong(0);
        this.evictions = new AtomicLong(0);
        this.hitCount = new AtomicLong(0);
        this.missCount = new AtomicLong(0);

        // Initialize size class buckets
        for (long size = MIN_SIZE_CLASS; size <= MAX_SIZE_CLASS; size *= 2) {
            freeBuffersByClass.put(size, new LinkedList<>());
        }
    }

    /**
     * Allocates a buffer from the pool or creates a new one.
     *
     * @param requestedBytes requested buffer size
     * @return pooled buffer, or null if pool is exhausted and eviction failed
     */
    public PooledBuffer allocate(long requestedBytes) {
        if (requestedBytes <= 0) {
            throw new IllegalArgumentException("Requested bytes must be positive");
        }

        long sizeClass = getSizeClass(requestedBytes);
        String id = UUID.randomUUID().toString();
        long now = System.nanoTime();

        lock.writeLock().lock();
        try {
            // Try to reuse from free pool
            var freeList = freeBuffersByClass.get(sizeClass);
            if (freeList != null && !freeList.isEmpty()) {
                var reused = freeList.removeFirst();
                totalFreeBytes.addAndGet(-reused.sizeBytes());

                var buffer = new PooledBuffer(id, requestedBytes, sizeClass, now, now);
                activeBuffers.put(id, buffer);
                totalActiveBytes.addAndGet(requestedBytes);
                hitCount.incrementAndGet();
                allocations.incrementAndGet();
                return buffer;
            }

            // Need to allocate new buffer
            missCount.incrementAndGet();

            // Check if we have room
            long totalUsed = totalActiveBytes.get() + totalFreeBytes.get();
            if (totalUsed + sizeClass > maxPoolBytes) {
                // Try to evict to make room
                if (!evictToMakeRoom(sizeClass)) {
                    return null;  // Cannot allocate
                }
            }

            // Create new buffer
            var buffer = new PooledBuffer(id, requestedBytes, sizeClass, now, now);
            activeBuffers.put(id, buffer);
            totalActiveBytes.addAndGet(requestedBytes);
            allocations.incrementAndGet();
            return buffer;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Releases a buffer back to the pool for reuse.
     *
     * @param bufferId ID of the buffer to release
     * @return true if buffer was found and released
     */
    public boolean release(String bufferId) {
        lock.writeLock().lock();
        try {
            var buffer = activeBuffers.remove(bufferId);
            if (buffer == null) {
                return false;
            }

            totalActiveBytes.addAndGet(-buffer.sizeBytes());
            deallocations.incrementAndGet();

            // Add to free pool
            var freeList = freeBuffersByClass.get(buffer.sizeClass());
            if (freeList != null) {
                freeList.addLast(buffer.touch());
                totalFreeBytes.addAndGet(buffer.sizeBytes());
            }

            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Updates access time for a buffer (touch for LRU).
     *
     * @param bufferId ID of the buffer
     */
    public void touch(String bufferId) {
        var buffer = activeBuffers.get(bufferId);
        if (buffer != null) {
            activeBuffers.put(bufferId, buffer.touch());
        }
    }

    /**
     * Evicts buffers to free the specified amount of memory.
     *
     * @param bytesNeeded bytes to free
     * @return bytes actually freed
     */
    public long evict(long bytesNeeded) {
        lock.writeLock().lock();
        try {
            return evictToMakeRoom(bytesNeeded) ? bytesNeeded : 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Clears all buffers from the pool.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            activeBuffers.clear();
            for (var list : freeBuffersByClass.values()) {
                list.clear();
            }
            totalActiveBytes.set(0);
            totalFreeBytes.set(0);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns current pool statistics.
     */
    public PoolStats getStats() {
        lock.readLock().lock();
        try {
            int freeCount = 0;
            for (var list : freeBuffersByClass.values()) {
                freeCount += list.size();
            }

            return new PoolStats(
                activeBuffers.size(),
                freeCount,
                totalActiveBytes.get(),
                totalFreeBytes.get(),
                allocations.get(),
                deallocations.get(),
                evictions.get(),
                hitCount.get(),
                missCount.get()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns total bytes currently in use (active + free).
     */
    public long getTotalBytes() {
        return totalActiveBytes.get() + totalFreeBytes.get();
    }

    /**
     * Returns maximum pool capacity.
     */
    public long getMaxPoolBytes() {
        return maxPoolBytes;
    }

    // Private helpers

    /**
     * Rounds size up to nearest size class (power of 2).
     */
    private long getSizeClass(long bytes) {
        if (bytes <= MIN_SIZE_CLASS) {
            return MIN_SIZE_CLASS;
        }
        if (bytes >= MAX_SIZE_CLASS) {
            return MAX_SIZE_CLASS;
        }

        // Find next power of 2
        long sizeClass = MIN_SIZE_CLASS;
        while (sizeClass < bytes) {
            sizeClass *= 2;
        }
        return sizeClass;
    }

    /**
     * Attempts to evict free buffers to make room for new allocation.
     * Must be called with write lock held.
     *
     * @param bytesNeeded bytes to free
     * @return true if enough space was freed
     */
    private boolean evictToMakeRoom(long bytesNeeded) {
        long freed = 0;

        // First, evict from free pool (LRU order - oldest first)
        for (var entry : freeBuffersByClass.entrySet()) {
            var freeList = entry.getValue();
            while (!freeList.isEmpty() && freed < bytesNeeded) {
                var evicted = freeList.removeFirst();
                freed += evicted.sizeBytes();
                totalFreeBytes.addAndGet(-evicted.sizeBytes());
                evictions.incrementAndGet();
            }
            if (freed >= bytesNeeded) {
                break;
            }
        }

        return freed >= bytesNeeded;
    }
}
