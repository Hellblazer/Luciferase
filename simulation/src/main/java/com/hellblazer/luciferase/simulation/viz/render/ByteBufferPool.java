/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ByteBuffer pool for reducing GC pressure in WebSocket binary frame encoding.
 * <p>
 * Pools buffers in size buckets (1KB, 4KB, 16KB, 64KB) for efficient reuse.
 * Thread-safe via ConcurrentLinkedQueue.
 * <p>
 * Luciferase-8db0: ByteBuffer pooling optimization.
 *
 * @author hal.hildebrand
 */
public class ByteBufferPool {

    // Size buckets for pooled buffers
    private static final int SIZE_1KB = 1024;
    private static final int SIZE_4KB = 4 * 1024;
    private static final int SIZE_16KB = 16 * 1024;
    private static final int SIZE_64KB = 64 * 1024;

    // Pools for each size bucket
    private final ConcurrentLinkedQueue<ByteBuffer> pool1KB = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ByteBuffer> pool4KB = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ByteBuffer> pool16KB = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ByteBuffer> pool64KB = new ConcurrentLinkedQueue<>();

    // Pool size limits
    private final int maxPoolSize;

    // Pool size tracking
    private final AtomicInteger pool1KBSize = new AtomicInteger(0);
    private final AtomicInteger pool4KBSize = new AtomicInteger(0);
    private final AtomicInteger pool16KBSize = new AtomicInteger(0);
    private final AtomicInteger pool64KBSize = new AtomicInteger(0);

    // Statistics
    private final AtomicInteger borrowCount = new AtomicInteger(0);
    private final AtomicInteger returnCount = new AtomicInteger(0);
    private final AtomicInteger allocCount = new AtomicInteger(0);

    /**
     * Create buffer pool with default size limit (100 buffers per bucket).
     */
    public ByteBufferPool() {
        this(100);
    }

    /**
     * Create buffer pool with specified size limit.
     *
     * @param maxPoolSize Maximum buffers per size bucket
     */
    public ByteBufferPool(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    /**
     * Borrow a ByteBuffer of at least the requested capacity.
     * <p>
     * Returns pooled buffer if available, otherwise allocates new buffer.
     *
     * @param minCapacity Minimum required capacity in bytes
     * @return ByteBuffer with capacity >= minCapacity (cleared and ready for use)
     */
    public ByteBuffer borrow(int minCapacity) {
        borrowCount.incrementAndGet();

        ByteBuffer buffer = null;

        if (minCapacity <= SIZE_1KB) {
            buffer = pool1KB.poll();
            if (buffer == null) {
                buffer = ByteBuffer.allocate(SIZE_1KB);
                allocCount.incrementAndGet();
            } else {
                pool1KBSize.decrementAndGet();
            }
        } else if (minCapacity <= SIZE_4KB) {
            buffer = pool4KB.poll();
            if (buffer == null) {
                buffer = ByteBuffer.allocate(SIZE_4KB);
                allocCount.incrementAndGet();
            } else {
                pool4KBSize.decrementAndGet();
            }
        } else if (minCapacity <= SIZE_16KB) {
            buffer = pool16KB.poll();
            if (buffer == null) {
                buffer = ByteBuffer.allocate(SIZE_16KB);
                allocCount.incrementAndGet();
            } else {
                pool16KBSize.decrementAndGet();
            }
        } else if (minCapacity <= SIZE_64KB) {
            buffer = pool64KB.poll();
            if (buffer == null) {
                buffer = ByteBuffer.allocate(SIZE_64KB);
                allocCount.incrementAndGet();
            } else {
                pool64KBSize.decrementAndGet();
            }
        } else {
            // Too large for pooling - allocate directly
            buffer = ByteBuffer.allocate(minCapacity);
            allocCount.incrementAndGet();
        }

        buffer.clear();
        return buffer;
    }

    /**
     * Return a ByteBuffer to the pool for reuse.
     * <p>
     * Buffer is cleared and returned to appropriate size bucket if pool not full.
     *
     * @param buffer ByteBuffer to return (must not be null)
     */
    public void returnBuffer(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }

        returnCount.incrementAndGet();

        int capacity = buffer.capacity();
        buffer.clear();

        if (capacity == SIZE_1KB && pool1KBSize.get() < maxPoolSize) {
            pool1KB.offer(buffer);
            pool1KBSize.incrementAndGet();
        } else if (capacity == SIZE_4KB && pool4KBSize.get() < maxPoolSize) {
            pool4KB.offer(buffer);
            pool4KBSize.incrementAndGet();
        } else if (capacity == SIZE_16KB && pool16KBSize.get() < maxPoolSize) {
            pool16KB.offer(buffer);
            pool16KBSize.incrementAndGet();
        } else if (capacity == SIZE_64KB && pool64KBSize.get() < maxPoolSize) {
            pool64KB.offer(buffer);
            pool64KBSize.incrementAndGet();
        }
        // Else: buffer discarded (pool full or non-standard size)
    }

    /**
     * Get pool statistics for monitoring.
     *
     * @return PoolStats record with current pool state
     */
    public PoolStats getStats() {
        return new PoolStats(
            pool1KBSize.get(),
            pool4KBSize.get(),
            pool16KBSize.get(),
            pool64KBSize.get(),
            borrowCount.get(),
            returnCount.get(),
            allocCount.get()
        );
    }

    /**
     * Clear all pooled buffers (for testing/cleanup).
     */
    public void clear() {
        pool1KB.clear();
        pool4KB.clear();
        pool16KB.clear();
        pool64KB.clear();
        pool1KBSize.set(0);
        pool4KBSize.set(0);
        pool16KBSize.set(0);
        pool64KBSize.set(0);
    }

    /**
     * Pool statistics record.
     */
    public record PoolStats(
        int pool1KBSize,
        int pool4KBSize,
        int pool16KBSize,
        int pool64KBSize,
        int borrowCount,
        int returnCount,
        int allocCount
    ) {
        /**
         * Calculate pool hit rate (percentage of borrows that used pooled buffer).
         */
        public double hitRate() {
            if (borrowCount == 0) {
                return 0.0;
            }
            return 100.0 * (borrowCount - allocCount) / borrowCount;
        }

        /**
         * Total pooled buffers across all buckets.
         */
        public int totalPooled() {
            return pool1KBSize + pool4KBSize + pool16KBSize + pool64KBSize;
        }
    }
}
