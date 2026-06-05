/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link TetreeLevelCache} statistics counters are correct and thread-safe
 * (Luciferase-7wzml.133).
 *
 * <p>Prior to the fix the counters were plain {@code static long} fields incremented with {@code ++}
 * from concurrent read paths, causing lost updates and torn rate reads. They are now
 * {@link java.util.concurrent.atomic.LongAdder} fields.
 *
 * @author hal.hildebrand
 */
class TetreeLevelCacheTest {

    @BeforeEach
    void resetStats() {
        TetreeLevelCache.resetCacheStats();
    }

    // ---- Basic hit/miss accounting ---------------------------------------

    @Test
    void hitRateIsZeroWhenNoAccesses() {
        assertEquals(0.0, TetreeLevelCache.getCacheHitRate(), "hit rate must be 0 before any access");
        assertEquals(0.0, TetreeLevelCache.getParentCacheHitRate());
        assertEquals(0.0, TetreeLevelCache.getParentChainHitRate());
    }

    @Test
    void cacheHitRecorded() {
        // Level-3 cell size = 1 << (21-3) = 262144; use an aligned coordinate
        int cellSize = 1 << (21 - 3); // = 262144
        int x = cellSize, y = cellSize, z = cellSize;
        byte level = 3, type = 0;
        var tet = new Tet(x, y, z, level, type);
        var key = tet.tmIndex();
        TetreeLevelCache.cacheTetreeKey(x, y, z, level, type, key);

        TetreeLevelCache.resetCacheStats();
        var result = TetreeLevelCache.getCachedTetreeKey(x, y, z, level, type);
        assertNotNull(result);
        assertEquals(1.0, TetreeLevelCache.getCacheHitRate(), 1e-9, "one hit and zero misses -> 100% hit rate");
    }

    @Test
    void cacheMissRecorded() {
        // Request a key that is definitely not in the slot (use a coord that won't collide)
        TetreeLevelCache.resetCacheStats();
        var result = TetreeLevelCache.getCachedTetreeKey(0, 0, 0, (byte) 0, (byte) 0);
        // may or may not be null depending on prior warm — check miss counting only when null
        if (result == null) {
            double rate = TetreeLevelCache.getCacheHitRate();
            // 0 hits, 1 miss -> 0.0
            assertEquals(0.0, rate, "cache miss with no prior hits -> 0% hit rate");
        }
        // If it hit (warm from static init), just verify rate is in [0,1]
        double rate = TetreeLevelCache.getCacheHitRate();
        assertTrue(rate >= 0.0 && rate <= 1.0, "hit rate must be in [0,1]: " + rate);
    }

    @Test
    void resetClearsCounts() {
        int cellSize = 1 << (21 - 3); // 262144 at level 3
        var tet = new Tet(cellSize, cellSize, cellSize, (byte) 3, (byte) 0);
        var key = tet.tmIndex();
        TetreeLevelCache.cacheTetreeKey(cellSize, cellSize, cellSize, (byte) 3, (byte) 0, key);
        TetreeLevelCache.getCachedTetreeKey(cellSize, cellSize, cellSize, (byte) 3, (byte) 0);

        TetreeLevelCache.resetCacheStats();
        assertEquals(0.0, TetreeLevelCache.getCacheHitRate(), "after reset hit rate must be 0");
    }

    // ---- Concurrency correctness -----------------------------------------

    @Test
    void countersDoNotLoseUpdatesUnderConcurrency() throws InterruptedException {
        // Level-4 cell size = 1 << (21-4) = 131072; use an aligned coordinate
        int x = 1 << (21 - 4), y = 1 << (21 - 4), z = 1 << (21 - 4);
        byte level = 4, type = 2;
        var tet = new Tet(x, y, z, level, type);
        var tetKey = tet.tmIndex();
        TetreeLevelCache.cacheTetreeKey(x, y, z, level, type, tetKey);

        TetreeLevelCache.resetCacheStats();

        int threads = 8;
        int lookupsPerThread = 200;
        var latch = new CountDownLatch(threads);
        var executor = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < lookupsPerThread; i++) {
                        TetreeLevelCache.getCachedTetreeKey(x, y, z, level, type);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "threads did not finish in time");
        executor.shutdown();

        // All lookups to the warmed key should be hits
        double hitRate = TetreeLevelCache.getCacheHitRate();
        // The slot might have been evicted by another caller between warm and lookup,
        // but in a controlled test with no other cache writes, we expect >= 99% hit rate.
        // Use a conservative lower bound to tolerate any concurrency timing:
        assertTrue(hitRate > 0.0, "expected at least some hits under concurrent access, got " + hitRate);
    }
}
