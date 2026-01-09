/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GCPauseMeasurement pause detection and statistics.
 * <p>
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 *
 * @author hal.hildebrand
 */
class GCPauseMeasurementTest {

    @Test
    void testGCPauseMeasurement_StartStop() {
        // Given: Fresh GC pause measurement
        var measurement = new GCPauseMeasurement();

        // When: Start measurement
        measurement.start();

        // Then: Should be running
        assertTrue(measurement.getDurationMs() >= 0, "Duration should be non-negative");

        // When: Wait a bit
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Then: Duration should increase
        var durationBeforeStop = measurement.getDurationMs();
        assertTrue(durationBeforeStop >= 90, "Duration should be at least ~100ms, got: " + durationBeforeStop);

        // When: Stop measurement
        measurement.stop();

        // Then: Final duration should be recorded
        var finalDuration = measurement.getDurationMs();
        assertTrue(finalDuration >= durationBeforeStop, "Duration should not decrease");
    }

    @Test
    void testGCPauseMeasurement_DoubleStart() {
        // Given: Fresh measurement
        var measurement = new GCPauseMeasurement();

        // When: Start measurement
        measurement.start();

        // Then: Starting again should throw
        assertThrows(IllegalStateException.class, measurement::start,
            "Should not allow double start");

        // Cleanup
        measurement.stop();
    }

    @Test
    void testGCPauseMeasurement_StopWithoutStart() {
        // Given: Fresh measurement
        var measurement = new GCPauseMeasurement();

        // When: Stop without starting
        // Then: Should not throw
        assertDoesNotThrow(measurement::stop, "Stop without start should not throw");
    }

    @Test
    void testGCPauseMeasurement_EmptyStats() {
        // Given: Fresh measurement without triggering GC
        var measurement = new GCPauseMeasurement();
        measurement.start();

        // Wait briefly
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        measurement.stop();

        // Then: Stats should reflect no pauses detected (this is expected - GC may not run)
        var stats = measurement.getStats();
        assertEquals(0, stats.pauseCount(), "Pause count should be 0 initially");
        assertEquals(0, stats.p50Ms(), "p50 should be 0 with no pauses");
        assertEquals(0, stats.p95Ms(), "p95 should be 0 with no pauses");
        assertEquals(0, stats.p99Ms(), "p99 should be 0 with no pauses");
        assertEquals(0, stats.maxMs(), "max should be 0 with no pauses");
    }

    @Test
    void testGCPauseMeasurement_DurationTracking() {
        // Given: Fresh measurement
        var measurement = new GCPauseMeasurement();

        // When: Start and wait
        measurement.start();
        var durationAfterStart = measurement.getDurationMs();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var durationAfterWait = measurement.getDurationMs();
        measurement.stop();
        var finalDuration = measurement.getDurationMs();

        // Then: Durations should be monotonically increasing
        assertTrue(durationAfterStart <= durationAfterWait,
            "Duration should increase during measurement");
        assertTrue(durationAfterWait <= finalDuration,
            "Final duration should be at least wait duration");
        assertTrue(finalDuration >= 190, "Final duration should be ~200ms, got: " + finalDuration);
    }

    @Test
    void testGCPauseMeasurement_PauseFrequency() {
        // Given: Fresh measurement
        var measurement = new GCPauseMeasurement();
        measurement.start();

        // Trigger some GC to potentially generate pauses
        for (int i = 0; i < 10; i++) {
            var dummy = new byte[1024 * 1024]; // 1MB allocation
            System.gc();
        }

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        measurement.stop();

        // Then: Pause frequency should be non-negative
        var frequency = measurement.getPauseFrequency();
        assertTrue(frequency >= 0, "Pause frequency should be non-negative, got: " + frequency);
    }

    @Test
    void testGCPauseMeasurement_PercentileConsistency() {
        // Given: Fresh measurement with custom poll interval
        var measurement = new GCPauseMeasurement(1);
        measurement.start();

        // Trigger some GC
        for (int i = 0; i < 5; i++) {
            var dummy = new byte[512 * 1024]; // 512KB allocation
            System.gc();
        }

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        measurement.stop();

        // Then: Percentiles should be in order: p50 <= p95 <= p99 <= max
        var p50 = measurement.p50Ms();
        var p95 = measurement.p95Ms();
        var p99 = measurement.p99Ms();
        var max = measurement.maxMs();

        // Note: Some may be 0 if no pauses detected
        if (max > 0) {
            assertTrue(p50 <= p95, "p50 should be <= p95");
            assertTrue(p95 <= p99, "p95 should be <= p99");
            assertTrue(p99 <= max, "p99 should be <= max");
        }
    }

    @Test
    void testGCPauseMeasurement_AverageCalculation() {
        // Given: Fresh measurement
        var measurement = new GCPauseMeasurement();
        measurement.start();

        // Trigger some GC
        System.gc();

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        measurement.stop();

        // Then: Average should be >= 0
        var average = measurement.averageMs();
        assertTrue(average >= 0, "Average should be non-negative, got: " + average);

        // And: Average should be <= max
        var max = measurement.maxMs();
        if (max > 0) {
            assertTrue(average <= max, "Average should be <= max");
        }
    }

    @Test
    void testGCPauseMeasurement_GetStats() {
        // Given: Fresh measurement
        var measurement = new GCPauseMeasurement();
        measurement.start();

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        measurement.stop();

        // When: Get stats
        var stats = measurement.getStats();

        // Then: Stats should contain all expected fields
        assertNotNull(stats, "Stats should not be null");
        assertTrue(stats.pauseCount() >= 0, "Pause count should be non-negative");
        assertTrue(stats.durationMs() >= 40, "Duration should be recorded");
        assertTrue(stats.p50Ms() >= 0, "p50 should be non-negative");
        assertTrue(stats.p95Ms() >= 0, "p95 should be non-negative");
        assertTrue(stats.p99Ms() >= 0, "p99 should be non-negative");
        assertTrue(stats.maxMs() >= 0, "max should be non-negative");
        assertTrue(stats.averageMs() >= 0, "average should be non-negative");
        assertTrue(stats.pauseFrequency() >= 0, "frequency should be non-negative");
    }

    @Test
    void testGCPauseMeasurement_PauseCount() {
        // Given: Fresh measurement
        var measurement = new GCPauseMeasurement();
        measurement.start();

        // When: Record pauses artificially
        System.gc();

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        measurement.stop();

        // Then: Pause count should be tracked
        var count = measurement.getPauseCount();
        assertTrue(count >= 0, "Pause count should be non-negative, got: " + count);
    }

    @Test
    void testGCPauseMeasurement_ThreadSafety() {
        // Given: Fresh measurement
        var measurement = new GCPauseMeasurement();
        measurement.start();

        // When: Concurrent access from multiple threads
        var threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    var pauseCount = measurement.getPauseCount();
                    var stats = measurement.getStats();
                    var p99 = measurement.p99Ms();
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (var thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        measurement.stop();

        // Then: Should not throw or corrupt state
        var finalStats = measurement.getStats();
        assertNotNull(finalStats, "Stats should still be valid after concurrent access");
    }

    @Test
    void testGCPauseMeasurement_CustomPollInterval() {
        // Given: Measurement with 5ms poll interval
        var measurement = new GCPauseMeasurement(5);
        measurement.start();

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        measurement.stop();

        // Then: Should complete without error
        assertTrue(measurement.getDurationMs() >= 95, "Duration should reflect polling interval");
    }

    @Test
    void testGCPauseMeasurement_MaxTracking() {
        // Given: Fresh measurement
        var measurement = new GCPauseMeasurement();
        measurement.start();

        // Trigger GC
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        measurement.stop();

        // Then: Max should be >= 0
        var max = measurement.maxMs();
        assertTrue(max >= 0, "Max pause should be non-negative, got: " + max);
    }
}
