/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

/**
 * Immutable cache performance metrics snapshot.
 *
 * @param hitCount      Number of cache hits
 * @param missCount     Number of cache misses
 * @param evictionCount Number of cache evictions
 * @author hal.hildebrand
 */
public record CacheMetrics(long hitCount, long missCount, long evictionCount) {

    /**
     * Calculate cache hit rate
     *
     * @return hit rate as ratio (0.0 to 1.0)
     */
    public float hitRate() {
        var total = hitCount + missCount;
        if (total == 0) {
            return 0.0f;
        }
        return (float) hitCount / total;
    }
}
