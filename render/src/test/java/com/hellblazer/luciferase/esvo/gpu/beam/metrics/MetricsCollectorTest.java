/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsCollector interface contract.
 * Validates frame lifecycle, thread safety, and snapshot immutability.
 *
 * @author hal.hildebrand
 */
class MetricsCollectorTest {

    /**
     * T24: testStartEndFrame - Frame lifecycle works correctly.
     * Verifies that startFrame/endFrame properly bracket a frame and
     * metrics are accumulated correctly.
     */
    @Test
    void testStartEndFrame() {
        // Given: A metrics collector
        MetricsCollector collector = new DefaultMetricsCollector();

        // When: Recording metrics within a frame
        collector.startFrame();

        // Record some metrics
        collector.recordGPUTiming(1_000_000L);  // 1ms
        collector.recordDispatchMetrics(DispatchMetrics.from(10, 8, 2));

        collector.endFrame();

        // Then: Snapshot contains accumulated metrics
        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot, "Snapshot should not be null");
        assertTrue(snapshot.avgFrameTimeMs() > 0, "Frame time should be recorded");
        assertEquals(10, snapshot.dispatch().totalDispatches(), "Total dispatches should match");
    }

    /**
     * T25: testThreadSafety - Concurrent access is safe.
     * Verifies that multiple threads can record metrics and retrieve snapshots
     * concurrently without data corruption.
     */
    @Test
    void testThreadSafety() throws InterruptedException {
        // Given: A metrics collector
        MetricsCollector collector = new DefaultMetricsCollector();

        var threadCount = 10;
        var iterationsPerThread = 100;
        var latch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        // When: Multiple threads record metrics concurrently
        for (int i = 0; i < threadCount; i++) {
            var threadId = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        // Half the threads write, half read
                        if (threadId % 2 == 0) {
                            collector.startFrame();
                            collector.recordGPUTiming(100_000L);
                            collector.recordDispatchMetrics(DispatchMetrics.from(1, 1, 0));
                            collector.endFrame();
                        } else {
                            var snapshot = collector.getSnapshot();
                            assertNotNull(snapshot, "Snapshot should not be null");
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Then: All threads complete without errors
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        assertEquals(0, errors.get(), "No errors should occur during concurrent access");
    }

    /**
     * T26: testSnapshotImmutable - Snapshot is immutable.
     * Verifies that snapshots are immutable records and modifications
     * to the collector don't affect previous snapshots.
     */
    @Test
    void testSnapshotImmutable() {
        // Given: A metrics collector with initial metrics
        MetricsCollector collector = new DefaultMetricsCollector();

        collector.startFrame();
        collector.recordGPUTiming(1_000_000L);
        collector.recordDispatchMetrics(DispatchMetrics.from(5, 3, 2));
        collector.endFrame();

        var snapshot1 = collector.getSnapshot();
        var snapshot1Dispatches = snapshot1.dispatch().totalDispatches();

        // When: Recording more metrics
        collector.startFrame();
        collector.recordGPUTiming(2_000_000L);
        collector.recordDispatchMetrics(DispatchMetrics.from(10, 8, 2));
        collector.endFrame();

        var snapshot2 = collector.getSnapshot();

        // Then: Original snapshot is unchanged (immutability test)
        assertEquals(snapshot1Dispatches, snapshot1.dispatch().totalDispatches(),
                    "Original snapshot should be unchanged");

        // Snapshots have different values due to aggregation
        assertNotEquals(snapshot1.avgFrameTimeMs(), snapshot2.avgFrameTimeMs(),
                       "Snapshots should have different frame times");

        // Second snapshot aggregates both frames: 5 + 10 = 15
        assertEquals(15, snapshot2.dispatch().totalDispatches(),
                    "New snapshot should aggregate all frames in window");
    }

    /**
     * T26b: testMultipleStartEndFrames - Multiple frames aggregate correctly.
     */
    @Test
    void testMultipleStartEndFrames() {
        // Given: A metrics collector
        MetricsCollector collector = new DefaultMetricsCollector();

        // When: Recording multiple frames
        for (int i = 0; i < 5; i++) {
            collector.startFrame();
            collector.recordGPUTiming((i + 1) * 1_000_000L);  // 1ms, 2ms, 3ms, 4ms, 5ms
            collector.recordDispatchMetrics(DispatchMetrics.from(i + 1, i, 1));
            collector.endFrame();
        }

        // Then: Metrics are aggregated across frames
        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot, "Snapshot should not be null");
        assertTrue(snapshot.avgFrameTimeMs() > 0, "Average frame time should be computed");
        assertTrue(snapshot.currentFps() > 0, "FPS should be computed from multiple frames");
    }
}
