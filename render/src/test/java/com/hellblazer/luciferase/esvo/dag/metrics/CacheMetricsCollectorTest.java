/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first implementation of CacheMetricsCollector - thread-safe cache performance tracking.
 *
 * @author hal.hildebrand
 */
class CacheMetricsCollectorTest {

    @Test
    void testInitialState() {
        var collector = new CacheMetricsCollector();
        var metrics = collector.getMetrics();

        assertEquals(0, metrics.hitCount());
        assertEquals(0, metrics.missCount());
        assertEquals(0, metrics.evictionCount());
        assertEquals(0.0f, metrics.hitRate(), 0.01f);
    }

    @Test
    void testHitTracking() {
        var collector = new CacheMetricsCollector();
        collector.recordHit();
        collector.recordHit();
        collector.recordHit();

        var metrics = collector.getMetrics();
        assertEquals(3, metrics.hitCount());
        assertEquals(0, metrics.missCount());
        assertEquals(1.0f, metrics.hitRate(), 0.01f); // 100% hit rate
    }

    @Test
    void testMissTracking() {
        var collector = new CacheMetricsCollector();
        collector.recordMiss();
        collector.recordMiss();

        var metrics = collector.getMetrics();
        assertEquals(0, metrics.hitCount());
        assertEquals(2, metrics.missCount());
        assertEquals(0.0f, metrics.hitRate(), 0.01f); // 0% hit rate
    }

    @Test
    void testEvictionTracking() {
        var collector = new CacheMetricsCollector();
        collector.recordEviction();
        collector.recordEviction();
        collector.recordEviction();

        var metrics = collector.getMetrics();
        assertEquals(3, metrics.evictionCount());
    }

    @Test
    void testHitRateCalculation() {
        var collector = new CacheMetricsCollector();
        collector.recordHit();
        collector.recordHit();
        collector.recordMiss();

        var metrics = collector.getMetrics();
        assertEquals(2, metrics.hitCount());
        assertEquals(1, metrics.missCount());
        assertEquals(2.0f / 3.0f, metrics.hitRate(), 0.01f); // 66.7% hit rate
    }

    @Test
    void testMixedOperations() {
        var collector = new CacheMetricsCollector();
        collector.recordHit();
        collector.recordMiss();
        collector.recordEviction();
        collector.recordHit();
        collector.recordMiss();
        collector.recordEviction();
        collector.recordHit();

        var metrics = collector.getMetrics();
        assertEquals(3, metrics.hitCount());
        assertEquals(2, metrics.missCount());
        assertEquals(2, metrics.evictionCount());
        assertEquals(3.0f / 5.0f, metrics.hitRate(), 0.01f); // 60% hit rate
    }

    @Test
    void testResetFunctionality() {
        var collector = new CacheMetricsCollector();
        collector.recordHit();
        collector.recordMiss();
        collector.recordEviction();

        collector.reset();

        var metrics = collector.getMetrics();
        assertEquals(0, metrics.hitCount());
        assertEquals(0, metrics.missCount());
        assertEquals(0, metrics.evictionCount());
        assertEquals(0.0f, metrics.hitRate());
    }

    @Test
    void testConcurrentHits() throws InterruptedException {
        var collector = new CacheMetricsCollector();
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    collector.recordHit();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        var metrics = collector.getMetrics();
        assertEquals(100, metrics.hitCount());
    }

    @Test
    void testConcurrentMisses() throws InterruptedException {
        var collector = new CacheMetricsCollector();
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    collector.recordMiss();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        var metrics = collector.getMetrics();
        assertEquals(100, metrics.missCount());
    }

    @Test
    void testConcurrentEvictions() throws InterruptedException {
        var collector = new CacheMetricsCollector();
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    collector.recordEviction();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        var metrics = collector.getMetrics();
        assertEquals(100, metrics.evictionCount());
    }

    @Test
    void testConcurrentMixedOperations() throws InterruptedException {
        var collector = new CacheMetricsCollector();
        var executor = Executors.newFixedThreadPool(20);
        var latch = new CountDownLatch(300);

        // 100 hits, 100 misses, 100 evictions
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    collector.recordHit();
                } finally {
                    latch.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    collector.recordMiss();
                } finally {
                    latch.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    collector.recordEviction();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        var metrics = collector.getMetrics();
        assertEquals(100, metrics.hitCount());
        assertEquals(100, metrics.missCount());
        assertEquals(100, metrics.evictionCount());
        assertEquals(0.5f, metrics.hitRate(), 0.01f); // 50% hit rate
    }

    @Test
    void testZeroDivisionInHitRate() {
        var collector = new CacheMetricsCollector();
        var metrics = collector.getMetrics();
        assertEquals(0.0f, metrics.hitRate()); // No operations = 0% hit rate
    }

    @Test
    void testGetMetricsDoesNotModifyState() {
        var collector = new CacheMetricsCollector();
        collector.recordHit();
        collector.recordMiss();

        var metrics1 = collector.getMetrics();
        var metrics2 = collector.getMetrics();

        assertEquals(metrics1.hitCount(), metrics2.hitCount());
        assertEquals(metrics1.missCount(), metrics2.missCount());
        assertEquals(metrics1.hitRate(), metrics2.hitRate(), 0.01f);
    }

    @Test
    void testResetDuringConcurrentAccess() throws InterruptedException {
        var collector = new CacheMetricsCollector();
        var executor = Executors.newFixedThreadPool(10);

        // Start recording operations
        for (int i = 0; i < 50; i++) {
            executor.submit(collector::recordHit);
        }

        // Reset in the middle
        Thread.sleep(10);
        collector.reset();

        // Continue recording
        for (int i = 0; i < 50; i++) {
            executor.submit(collector::recordMiss);
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Should have some misses, but state after reset should be consistent
        var metrics = collector.getMetrics();
        assertTrue(metrics.missCount() > 0);
        assertTrue(metrics.hitCount() >= 0); // May have hits before reset
    }
}
