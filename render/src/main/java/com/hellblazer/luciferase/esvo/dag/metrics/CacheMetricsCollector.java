/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe cache metrics collector.
 * <p>
 * Uses atomic operations for concurrent hit/miss/eviction tracking without locking.
 *
 * @author hal.hildebrand
 */
public class CacheMetricsCollector {

    private final AtomicLong hitCount      = new AtomicLong(0);
    private final AtomicLong missCount     = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);

    /**
     * Record a cache hit (thread-safe)
     */
    public void recordHit() {
        hitCount.incrementAndGet();
    }

    /**
     * Record a cache miss (thread-safe)
     */
    public void recordMiss() {
        missCount.incrementAndGet();
    }

    /**
     * Record a cache eviction (thread-safe)
     */
    public void recordEviction() {
        evictionCount.incrementAndGet();
    }

    /**
     * Get current metrics snapshot (thread-safe)
     *
     * @return immutable metrics snapshot
     */
    public CacheMetrics getMetrics() {
        return new CacheMetrics(hitCount.get(), missCount.get(), evictionCount.get());
    }

    /**
     * Reset all metrics to zero (thread-safe)
     */
    public void reset() {
        hitCount.set(0);
        missCount.set(0);
        evictionCount.set(0);
    }
}
