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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Manages deferred insertions for bulk operations in spatial indices.
 * This class buffers insertions to improve performance during rapid bulk insert scenarios
 * by batching index updates and reducing synchronization overhead.
 *
 * @param <ID>      The type of EntityID used
 * @param <Content> The type of content stored
 * @author hal.hildebrand
 */
public class DeferredInsertionManager<ID extends EntityID, Content> {

    /**
     * Represents a deferred insertion operation
     */
    public static class DeferredInsertion<ID extends EntityID, Content> {
        public final ID           entityId;
        public final Point3f      position;
        public final byte         level;
        public final Content      content;
        public final EntityBounds bounds;
        public final long         timestamp;

        public DeferredInsertion(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds) {
            this.entityId = entityId;
            this.position = new Point3f(position); // defensive copy
            this.level = level;
            this.content = content;
            this.bounds = bounds;
            this.timestamp = System.nanoTime();
        }
    }

    /**
     * Configuration for deferred insertion behavior
     */
    public static class DeferredInsertionConfig {
        // Maximum number of insertions to buffer before auto-flush
        private int     maxBatchSize        = 1000;
        // Maximum time in milliseconds before auto-flush
        private long    maxFlushDelayMillis = 100;
        // Whether to auto-flush on query operations
        private boolean autoFlushOnQuery    = true;
        // Minimum batch size for efficient bulk processing
        private int     minBatchSize        = 10;

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public DeferredInsertionConfig setMaxBatchSize(int maxBatchSize) {
            if (maxBatchSize <= 0) {
                throw new IllegalArgumentException("Max batch size must be positive");
            }
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        public long getMaxFlushDelayMillis() {
            return maxFlushDelayMillis;
        }

        public DeferredInsertionConfig setMaxFlushDelayMillis(long maxFlushDelayMillis) {
            if (maxFlushDelayMillis < 0) {
                throw new IllegalArgumentException("Max flush delay must be non-negative");
            }
            this.maxFlushDelayMillis = maxFlushDelayMillis;
            return this;
        }

        public boolean isAutoFlushOnQuery() {
            return autoFlushOnQuery;
        }

        public DeferredInsertionConfig setAutoFlushOnQuery(boolean autoFlushOnQuery) {
            this.autoFlushOnQuery = autoFlushOnQuery;
            return this;
        }

        public int getMinBatchSize() {
            return minBatchSize;
        }

        public DeferredInsertionConfig setMinBatchSize(int minBatchSize) {
            if (minBatchSize <= 0) {
                throw new IllegalArgumentException("Min batch size must be positive");
            }
            this.minBatchSize = minBatchSize;
            return this;
        }
    }

    // Buffered insertions
    private final List<DeferredInsertion<ID, Content>>        buffer;
    // Configuration
    private final DeferredInsertionConfig                      config;
    // Lock for thread safety
    private final ReadWriteLock                                lock;
    // Callback to perform actual insertions
    private final Consumer<List<DeferredInsertion<ID, Content>>> flushCallback;
    // Timer for scheduled flushes
    private final Timer                                        flushTimer;
    // Flag to track if a flush is scheduled
    private volatile boolean                                   flushScheduled = false;
    // Statistics
    private final AtomicInteger                                totalInsertions = new AtomicInteger(0);
    private final AtomicInteger                                totalFlushes    = new AtomicInteger(0);
    private final AtomicInteger                                totalBatches    = new AtomicInteger(0);

    /**
     * Creates a new DeferredInsertionManager with default configuration
     *
     * @param flushCallback callback to perform actual insertions
     */
    public DeferredInsertionManager(Consumer<List<DeferredInsertion<ID, Content>>> flushCallback) {
        this(flushCallback, new DeferredInsertionConfig());
    }

    /**
     * Creates a new DeferredInsertionManager with specified configuration
     *
     * @param flushCallback callback to perform actual insertions
     * @param config        configuration for deferred insertion behavior
     */
    public DeferredInsertionManager(Consumer<List<DeferredInsertion<ID, Content>>> flushCallback,
                                    DeferredInsertionConfig config) {
        this.flushCallback = Objects.requireNonNull(flushCallback);
        this.config = Objects.requireNonNull(config);
        this.buffer = new ArrayList<>(config.getMaxBatchSize());
        this.lock = new ReentrantReadWriteLock();
        this.flushTimer = new Timer("DeferredInsertionFlush", true);
    }

    /**
     * Adds an insertion to the buffer
     *
     * @param entityId entity ID
     * @param position position in space
     * @param level    level in the spatial hierarchy
     * @param content  content to store
     * @param bounds   optional entity bounds
     * @return true if the insertion triggered an immediate flush
     */
    public boolean deferInsert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds) {
        DeferredInsertion<ID, Content> insertion = new DeferredInsertion<>(entityId, position, level, content, bounds);
        boolean shouldFlush = false;

        lock.writeLock().lock();
        try {
            buffer.add(insertion);
            totalInsertions.incrementAndGet();

            // Check if we should auto-flush based on batch size
            if (buffer.size() >= config.getMaxBatchSize()) {
                shouldFlush = true;
            } else if (!flushScheduled && config.getMaxFlushDelayMillis() > 0) {
                // Schedule a delayed flush
                scheduleFlush();
            }
        } finally {
            lock.writeLock().unlock();
        }

        if (shouldFlush) {
            flush();
            return true;
        }
        return false;
    }

    /**
     * Flushes all pending insertions
     *
     * @return number of insertions flushed
     */
    public int flush() {
        List<DeferredInsertion<ID, Content>> toFlush;

        lock.writeLock().lock();
        try {
            if (buffer.isEmpty()) {
                return 0;
            }

            toFlush = new ArrayList<>(buffer);
            buffer.clear();
            flushScheduled = false;
        } finally {
            lock.writeLock().unlock();
        }

        // Perform the flush outside the lock
        if (!toFlush.isEmpty()) {
            totalFlushes.incrementAndGet();
            totalBatches.addAndGet(toFlush.size());
            flushCallback.accept(toFlush);
            return toFlush.size();
        }

        return 0;
    }

    /**
     * Flushes pending insertions if auto-flush on query is enabled
     */
    public void flushIfNeeded() {
        if (config.isAutoFlushOnQuery() && hasPendingInsertions()) {
            flush();
        }
    }

    /**
     * Checks if there are pending insertions
     *
     * @return true if there are insertions waiting to be flushed
     */
    public boolean hasPendingInsertions() {
        lock.readLock().lock();
        try {
            return !buffer.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the number of pending insertions
     *
     * @return count of buffered insertions
     */
    public int getPendingCount() {
        lock.readLock().lock();
        try {
            return buffer.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets a snapshot of current statistics
     *
     * @return map of statistics
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("totalInsertions", totalInsertions.get());
        stats.put("totalFlushes", totalFlushes.get());
        stats.put("totalBatches", totalBatches.get());
        stats.put("pendingInsertions", getPendingCount());
        stats.put("averageBatchSize", totalFlushes.get() > 0 ? totalBatches.get() / totalFlushes.get() : 0);
        return stats;
    }

    /**
     * Resets statistics counters
     */
    public void resetStatistics() {
        totalInsertions.set(0);
        totalFlushes.set(0);
        totalBatches.set(0);
    }

    /**
     * Shuts down the manager and flushes any remaining insertions
     */
    public void shutdown() {
        flushTimer.cancel();
        flush();
    }

    /**
     * Gets the current configuration
     *
     * @return configuration
     */
    public DeferredInsertionConfig getConfig() {
        return config;
    }

    /**
     * Schedules a delayed flush
     */
    private void scheduleFlush() {
        flushScheduled = true;
        flushTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                flush();
            }
        }, config.getMaxFlushDelayMillis());
    }
}