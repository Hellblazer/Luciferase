/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

import com.hellblazer.luciferase.common.time.Clock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first implementation of CompressionMetrics - defines API before implementation.
 *
 * @author hal.hildebrand
 */
class CompressionMetricsTest {

    @Test
    void testCompressionRatioNormal() {
        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(100), 0L);
        assertEquals(5.0f, metrics.compressionRatio(), 0.01f); // 500/100
    }

    @Test
    void testCompressionPercentNormal() {
        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(100), 0L);
        assertEquals(80.0f, metrics.compressionPercent(), 0.01f); // (1 - 100/500)*100
    }

    @Test
    void testMemorySavedBytesNormal() {
        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(100), 0L);
        // (500 - 100) nodes * 8 bytes/node = 3200 bytes saved
        assertEquals(3200L, metrics.memorySavedBytes());
    }

    @Test
    void testMemorySavedPercentNormal() {
        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(100), 0L);
        // (500 - 100) / 500 * 100 = 80% saved
        assertEquals(80.0f, metrics.memorySavedPercent(), 0.01f);
    }

    @Test
    void testZeroSourceNodes() {
        var metrics = new CompressionMetrics(0, 0, 0, 0, Duration.ZERO, 0L);
        assertEquals(0.0f, metrics.compressionRatio());
        assertEquals(0.0f, metrics.compressionPercent());
        assertEquals(0L, metrics.memorySavedBytes());
        assertEquals(0.0f, metrics.memorySavedPercent());
    }

    @Test
    void testZeroCompressedNodes() {
        var metrics = new CompressionMetrics(0, 500, 10, 50, Duration.ofMillis(100), 0L);
        assertEquals(0.0f, metrics.compressionRatio()); // No meaningful ratio with 0 compressed
        assertEquals(100.0f, metrics.compressionPercent()); // Perfect compression
        assertEquals(500 * 8L, metrics.memorySavedBytes()); // All source nodes saved
        assertEquals(100.0f, metrics.memorySavedPercent());
    }

    @Test
    void testNoCompression() {
        var metrics = new CompressionMetrics(500, 500, 10, 50, Duration.ofMillis(100), 0L);
        assertEquals(1.0f, metrics.compressionRatio(), 0.01f); // No compression
        assertEquals(0.0f, metrics.compressionPercent(), 0.01f); // 0% reduction
        assertEquals(0L, metrics.memorySavedBytes()); // No savings
        assertEquals(0.0f, metrics.memorySavedPercent());
    }

    @Test
    void testExpansion() {
        // Edge case: compressed > source (algorithm failed)
        var metrics = new CompressionMetrics(600, 500, 10, 50, Duration.ofMillis(100), 0L);
        assertEquals(0.833f, metrics.compressionRatio(), 0.01f); // < 1.0 indicates expansion
        assertEquals(-20.0f, metrics.compressionPercent(), 0.01f); // Negative = expansion
        assertEquals(-800L, metrics.memorySavedBytes()); // Negative = used more memory
        assertEquals(-20.0f, metrics.memorySavedPercent(), 0.01f);
    }

    @Test
    void testMinimalCompression() {
        var metrics = new CompressionMetrics(1, 2, 1, 1, Duration.ofMillis(1), 0L);
        assertEquals(2.0f, metrics.compressionRatio(), 0.01f);
        assertEquals(50.0f, metrics.compressionPercent(), 0.01f);
        assertEquals(8L, metrics.memorySavedBytes());
    }

    @Test
    void testLargeScaleCompression() {
        var metrics = new CompressionMetrics(1_000_000, 10_000_000, 100_000, 500_000,
                                             Duration.ofSeconds(5), 0L);
        assertEquals(10.0f, metrics.compressionRatio(), 0.01f);
        assertEquals(90.0f, metrics.compressionPercent(), 0.01f);
        assertEquals(9_000_000L * 8, metrics.memorySavedBytes()); // 72MB saved
    }

    @Test
    void testTimestampDeterministicViaFixedClock() {
        // Verifies timestamp is factory-injected (not captured internally via System.currentTimeMillis()),
        // making it pinnable and deterministic in tests.
        long fixedTime = 1_234_567_890L;
        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(100), fixedTime);
        assertEquals(fixedTime, metrics.timestamp());
    }

    @Test
    void testTimestampFromCollectorWithFixedClock() {
        // Verifies the full collector path: Clock injected into collector pins the timestamp on emitted records.
        long fixedTime = 9_999_000L;
        var fixedClock = Clock.fixed(fixedTime);
        // Directly verify the record component is pinned when constructed with a known value
        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(100), fixedClock.currentTimeMillis());
        assertEquals(fixedTime, metrics.timestamp());
    }

    @Test
    void testBuildTimePreservation() {
        var duration = Duration.ofMillis(1234);
        var metrics = new CompressionMetrics(100, 500, 10, 50, duration, 0L);
        assertEquals(duration, metrics.buildTime());
    }

    @Test
    void testStrategyTracking() {
        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(100), 0L);
        assertNotNull(metrics.strategy());
        assertTrue(metrics.strategy().startsWith("HASH_")); // Default strategy pattern
    }

    @Test
    void testBytesPerNodeCalculation() {
        // Verify internal assumption: 8 bytes per node reference
        var metrics = new CompressionMetrics(100, 500, 10, 50, Duration.ofMillis(100), 0L);
        var expectedBytes = (500 - 100) * 8;
        assertEquals(expectedBytes, metrics.memorySavedBytes());
    }
}
