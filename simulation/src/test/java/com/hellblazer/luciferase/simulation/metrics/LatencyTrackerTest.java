/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for LatencyTracker.
 * <p>
 * Validates latency recording, percentile calculation, ring buffer behavior, and thread safety.
 *
 * @author hal.hildebrand
 */
class LatencyTrackerTest {

    private LatencyTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new LatencyTracker();
    }

    /**
     * Test 1: Record a single latency sample and verify basic stats.
     */
    @Test
    void testRecordLatency() {
        tracker.record(1_000_000); // 1ms

        var stats = tracker.getStats();

        assertEquals(1_000_000, stats.minLatencyNs(), "Min should match recorded value");
        assertEquals(1_000_000, stats.maxLatencyNs(), "Max should match recorded value");
        assertEquals(1_000_000.0, stats.avgLatencyNs(), 0.01, "Avg should match recorded value");
        assertEquals(1, stats.sampleCount(), "Sample count should be 1");
    }

    /**
     * Test 2: Verify empty stats when no samples recorded.
     */
    @Test
    void testGetStatsEmpty() {
        var stats = tracker.getStats();

        assertEquals(Long.MAX_VALUE, stats.minLatencyNs(), "Min should be MAX_VALUE for empty tracker");
        assertEquals(0, stats.maxLatencyNs(), "Max should be 0 for empty tracker");
        assertEquals(0.0, stats.avgLatencyNs(), 0.01, "Avg should be 0 for empty tracker");
        assertEquals(0, stats.p50LatencyNs(), "P50 should be 0 for empty tracker");
        assertEquals(0, stats.p99LatencyNs(), "P99 should be 0 for empty tracker");
        assertEquals(0, stats.sampleCount(), "Sample count should be 0");
    }

    /**
     * Test 3: Verify stats with multiple samples.
     */
    @Test
    void testGetStatsWithSamples() {
        tracker.record(1_000_000);  // 1ms
        tracker.record(2_000_000);  // 2ms
        tracker.record(3_000_000);  // 3ms
        tracker.record(4_000_000);  // 4ms
        tracker.record(5_000_000);  // 5ms

        var stats = tracker.getStats();

        assertEquals(1_000_000, stats.minLatencyNs(), "Min should be 1ms");
        assertEquals(5_000_000, stats.maxLatencyNs(), "Max should be 5ms");
        assertEquals(3_000_000.0, stats.avgLatencyNs(), 0.01, "Avg should be 3ms");
        assertEquals(5, stats.sampleCount(), "Sample count should be 5");
    }

    /**
     * Test 4: Verify minimum latency tracking.
     */
    @Test
    void testMinLatency() {
        tracker.record(5_000_000);
        tracker.record(2_000_000);
        tracker.record(3_000_000);
        tracker.record(1_000_000);  // Min

        var stats = tracker.getStats();

        assertEquals(1_000_000, stats.minLatencyNs(), "Min should be 1ms");
    }

    /**
     * Test 5: Verify maximum latency tracking.
     */
    @Test
    void testMaxLatency() {
        tracker.record(1_000_000);
        tracker.record(5_000_000);  // Max
        tracker.record(2_000_000);
        tracker.record(3_000_000);

        var stats = tracker.getStats();

        assertEquals(5_000_000, stats.maxLatencyNs(), "Max should be 5ms");
    }

    /**
     * Test 6: Verify average latency calculation.
     */
    @Test
    void testAvgLatency() {
        tracker.record(10_000_000);
        tracker.record(20_000_000);
        tracker.record(30_000_000);

        var stats = tracker.getStats();

        assertEquals(20_000_000.0, stats.avgLatencyNs(), 0.01, "Avg should be 20ms");
    }

    /**
     * Test 7: Verify P50 (median) percentile calculation.
     */
    @Test
    void testP50Calculation() {
        // Add 101 samples (odd count for clear median)
        for (int i = 1; i <= 101; i++) {
            tracker.record(i * 1_000_000L); // 1ms to 101ms
        }

        var stats = tracker.getStats();

        // P50 should be around 51ms (median of 1-101)
        // Allow ±2ms tolerance for percentile estimation
        assertTrue(Math.abs(stats.p50LatencyNs() - 51_000_000) <= 2_000_000,
                   "P50 should be around 51ms, got: " + (stats.p50LatencyNs() / 1_000_000.0) + "ms");
    }

    /**
     * Test 8: Verify P99 percentile calculation.
     */
    @Test
    void testP99Calculation() {
        // Add 100 samples (1ms to 100ms)
        for (int i = 1; i <= 100; i++) {
            tracker.record(i * 1_000_000L);
        }

        var stats = tracker.getStats();

        // P99 should be 99ms (99th percentile of 1-100)
        // Allow ±2ms tolerance for percentile estimation
        assertTrue(Math.abs(stats.p99LatencyNs() - 99_000_000) <= 2_000_000,
                   "P99 should be around 99ms, got: " + (stats.p99LatencyNs() / 1_000_000.0) + "ms");
    }

    /**
     * Test 9: Verify ring buffer sliding window behavior.
     * <p>
     * Ring buffer should keep only last 1000 samples and correctly wrap around.
     */
    @Test
    void testSlidingWindow() {
        // Fill beyond window size (1000 samples)
        for (int i = 1; i <= 1500; i++) {
            tracker.record(i * 1_000_000L);
        }

        var stats = tracker.getStats();

        // Should only have last 1000 samples (501-1500)
        // Min should be ~501ms, Max should be 1500ms
        assertTrue(stats.minLatencyNs() >= 500_000_000, "Min should be >= 500ms after window wrap");
        assertEquals(1500_000_000, stats.maxLatencyNs(), "Max should be 1500ms");
        assertTrue(stats.sampleCount() >= 1000, "Sample count should reflect all recorded samples");

        // P50 should be around 1000ms (median of 501-1500)
        assertTrue(Math.abs(stats.p50LatencyNs() - 1000_000_000) <= 10_000_000,
                   "P50 should be around 1000ms after window wrap");
    }

    /**
     * Test 10: Verify thread-safe concurrent recording.
     */
    @Test
    void testConcurrentRecording() throws InterruptedException {
        var numThreads = 10;
        var samplesPerThread = 100;
        var latch = new CountDownLatch(numThreads);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        try {
            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < samplesPerThread; i++) {
                            tracker.record((threadId * samplesPerThread + i + 1) * 1_000_000L);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");

            var stats = tracker.getStats();

            // Should have recorded all samples
            assertTrue(stats.sampleCount() >= numThreads * samplesPerThread,
                       "Should have at least " + (numThreads * samplesPerThread) + " samples");

            // Min and max should be within expected range
            assertTrue(stats.minLatencyNs() >= 1_000_000, "Min should be >= 1ms");
            assertTrue(stats.maxLatencyNs() <= 1000_000_000, "Max should be <= 1000ms");

            // Stats should be valid
            assertTrue(stats.avgLatencyNs() > 0, "Average should be positive");
            assertTrue(stats.p50LatencyNs() > 0, "P50 should be positive");
            assertTrue(stats.p99LatencyNs() > 0, "P99 should be positive");

        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate");
        }
    }
}
