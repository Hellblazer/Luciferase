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

import com.hellblazer.luciferase.common.time.Clock;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * GPU memory quota tracker and byte-accounting gate.
 *
 * <p>Tracks byte quotas for logical GPU memory allocations within a
 * configurable capacity limit. Allocations and releases adjust
 * {@code AtomicLong} counters; no actual GPU handle (cl_mem,
 * MemorySegment, etc.) is created or freed here.
 *
 * <p>Size-class buckets allow the accounting layer to round requests up to
 * powers-of-2 boundaries and enforce a true LRU eviction policy over the
 * tracked quota when the limit is approached. Eviction selects the idle slot
 * with the smallest {@code lastAccessNs} across all size classes until enough
 * bytes are reclaimed.
 *
 * <p>Key features:
 * <ul>
 *   <li>Size-class based quota accounting (powers of 2)</li>
 *   <li>LRU eviction — least-recently-accessed idle slot evicted first</li>
 *   <li>Thread-safe counter adjustment</li>
 *   <li>Statistics tracking for monitoring</li>
 * </ul>
 *
 * @see GPUMemoryManager
 */
public class GPUMemoryAccountant {

    /**
     * Represents a tracked quota slot (metadata only — no GPU handle).
     */
    public record PooledBuffer(
        String id,
        long sizeBytes,
        long sizeClass,
        long allocatedAtNs,
        long lastAccessNs
    ) {
        /**
         * Updates last access time to the given nanosecond timestamp.
         *
         * @param nowNs nanosecond timestamp (from an injected clock)
         */
        public PooledBuffer touch(long nowNs) {
            return new PooledBuffer(id, sizeBytes, sizeClass, allocatedAtNs, nowNs);
        }
    }

    /**
     * Statistics for quota accounting monitoring.
     *
     * <p><b>Important:</b> {@code hitCount} and {@code hitRate()} measure
     * <em>quota-slot budget reuse</em>, not GPU buffer-object reuse. A "hit"
     * means an allocation request found a previously-released quota slot of
     * the matching size class and reused its byte-accounting entry, avoiding
     * a fresh counter increment. No actual GPU handle (cl_mem, MemorySegment,
     * etc.) is created or reused — this class is byte-accounting only.
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
         * Returns the quota-slot reuse rate (0.0 to 1.0).
         *
         * <p>This is the fraction of allocations that reused a previously-released
         * accounting slot rather than minting a fresh one. It does <em>not</em>
         * reflect GPU buffer-object reuse efficiency.
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

    // Clock for deterministic testing
    private volatile Clock clock = Clock.system();

    /**
     * Creates a quota tracker with the specified byte ceiling.
     *
     * @param maxPoolBytes maximum bytes the accountant will permit active at once
     */
    public GPUMemoryAccountant(long maxPoolBytes) {
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
     * Injects a clock for deterministic testing.
     *
     * @param clock the clock to use for allocation and LRU timestamps
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Registers a quota slot for the requested byte count.
     *
     * <p>No GPU memory is allocated. The returned {@link PooledBuffer} is a
     * metadata record tracking the quota claim. Returns {@code null} if the
     * byte ceiling would be exceeded and eviction of idle slots cannot free
     * enough quota.
     *
     * @param requestedBytes requested byte count
     * @return quota record, or null if quota is exhausted
     */
    public PooledBuffer allocate(long requestedBytes) {
        if (requestedBytes <= 0) {
            throw new IllegalArgumentException("Requested bytes must be positive");
        }

        long sizeClass = getSizeClass(requestedBytes);
        String id = UUID.randomUUID().toString();
        long now = clock.nanoTime();

        lock.writeLock().lock();
        try {
            // Quota slot available in this size class — reuse its byte budget
            var freeList = freeBuffersByClass.get(sizeClass);
            if (freeList != null && !freeList.isEmpty()) {
                var reused = freeList.removeFirst();
                totalFreeBytes.addAndGet(-reused.sizeBytes());

                // Mint a fresh quota record (new id/timestamps); actual GPU memory
                // allocation is the caller's responsibility.
                var buffer = new PooledBuffer(id, requestedBytes, sizeClass, now, now);
                activeBuffers.put(id, buffer);
                totalActiveBytes.addAndGet(requestedBytes);
                hitCount.incrementAndGet();
                allocations.incrementAndGet();
                return buffer;
            }

            // No idle slot in this size class — register a new quota entry
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
     * Releases a quota slot by its ID, adjusting the active-byte counter.
     *
     * <p>No GPU memory is freed here. The slot is moved from active to idle
     * so its quota can be reclaimed by a subsequent eviction pass.
     *
     * @param bufferId ID of the quota slot to release
     * @return true if the slot was found and released
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

            // Park slot as idle so its byte budget can be reclaimed by eviction
            var freeList = freeBuffersByClass.get(buffer.sizeClass());
            if (freeList != null) {
                freeList.addLast(buffer.touch(clock.nanoTime()));
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
     * <p>Uses {@link ConcurrentHashMap#computeIfPresent} so the read-then-write
     * is a single atomic operation on the map entry.  A plain
     * {@code get}/{@code put} sequence (the prior implementation) was a
     * non-atomic read-modify-write: a concurrent {@link #release} could remove
     * the entry between the {@code get} and the {@code put}, causing the
     * released entry to be resurrected in {@code activeBuffers} — corrupting
     * accounting and skewing {@code totalActiveBytes}.
     *
     * <p>No explicit lock is needed: {@code ConcurrentHashMap.computeIfPresent}
     * guarantees atomicity for the key's bucket and will silently no-op when
     * the key is absent (i.e. already released).
     *
     * @param bufferId ID of the buffer to touch
     */
    public void touch(String bufferId) {
        final long now = clock.nanoTime();
        activeBuffers.computeIfPresent(bufferId, (id, buffer) -> buffer.touch(now));
    }

    /**
     * Evicts idle quota slots to reclaim at least {@code bytesNeeded} from the tracked quota.
     *
     * @param bytesNeeded quota bytes to reclaim
     * @return quota bytes actually reclaimed
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
     * Clears all quota slots and resets byte counters to zero.
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
     * Returns current quota accounting statistics.
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
     * Returns total tracked quota bytes (active + idle).
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
     * Attempts to evict idle quota slots to reclaim enough quota for a new entry.
     * Must be called with write lock held.
     *
     * <p>Uses true LRU eviction: on each iteration, selects the idle slot with
     * the smallest {@code lastAccessNs} across all size classes and removes it.
     * This ensures that a buffer re-acquired and re-released more recently will
     * never be evicted before an older idle slot, regardless of insertion order.
     *
     * @param bytesNeeded quota bytes to reclaim
     * @return true if enough quota was reclaimed
     */
    private boolean evictToMakeRoom(long bytesNeeded) {
        long freed = 0;

        while (freed < bytesNeeded) {
            // Find the globally least-recently-accessed idle slot across all size classes
            LinkedList<PooledBuffer> lruList = null;
            PooledBuffer lruCandidate = null;

            for (var freeList : freeBuffersByClass.values()) {
                if (freeList.isEmpty()) {
                    continue;
                }
                // Find the entry with the minimum lastAccessNs in this size class
                PooledBuffer classMin = null;
                for (var buf : freeList) {
                    if (classMin == null || buf.lastAccessNs() < classMin.lastAccessNs()) {
                        classMin = buf;
                    }
                }
                if (classMin != null && (lruCandidate == null || classMin.lastAccessNs() < lruCandidate.lastAccessNs())) {
                    lruCandidate = classMin;
                    lruList = freeList;
                }
            }

            if (lruCandidate == null) {
                break; // No more idle slots
            }

            lruList.remove(lruCandidate);
            freed += lruCandidate.sizeBytes();
            totalFreeBytes.addAndGet(-lruCandidate.sizeBytes());
            evictions.incrementAndGet();
        }

        return freed >= bytesNeeded;
    }
}
