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
 * Tests for DefaultMetricsCollector implementation.
 * Validates GPU timing recording, dispatch metrics aggregation,
 * and concurrent recording safety.
 *
 * @author hal.hildebrand
 */
class DefaultMetricsCollectorTest {

    /**
     * T27: testRecordGPUTiming - GPU timing is aggregated correctly.
     * Verifies that GPU kernel timing is recorded and reflected in snapshots.
     */
    @Test
    void testRecordGPUTiming() {
        // Given: A default metrics collector
        var collector = new DefaultMetricsCollector();

        // When: Recording GPU timing within a frame
        collector.startFrame();

        // Simulate kernel execution time (1ms)
        collector.recordGPUTiming(1_000_000L);

        // Small delay to ensure frame has measurable time
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        collector.endFrame();

        // Then: Snapshot contains frame timing
        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot, "Snapshot should not be null");
        assertTrue(snapshot.avgFrameTimeMs() > 0, "Frame time should be recorded");
        assertTrue(snapshot.currentFps() > 0, "FPS should be computed");
    }

    /**
     * T28: testRecordDispatchMetrics - Dispatch metrics are aggregated.
     * Verifies that dispatch metrics are properly accumulated across frames.
     */
    @Test
    void testRecordDispatchMetrics() {
        // Given: A default metrics collector
        var collector = new DefaultMetricsCollector();

        // When: Recording dispatch metrics across multiple frames
        // Frame 1: 10 total (8 batch, 2 single)
        collector.startFrame();
        collector.recordDispatchMetrics(DispatchMetrics.from(10, 8, 2));
        collector.endFrame();

        // Frame 2: 15 total (12 batch, 3 single)
        collector.startFrame();
        collector.recordDispatchMetrics(DispatchMetrics.from(15, 12, 3));
        collector.endFrame();

        // Then: Snapshot aggregates both frames (25 total, 20 batch, 5 single)
        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot, "Snapshot should not be null");
        assertEquals(25, snapshot.dispatch().totalDispatches(),
                    "Total dispatches should be sum of both frames");
        assertEquals(20, snapshot.dispatch().batchDispatches(),
                    "Batch dispatches should be sum of both frames");
        assertEquals(5, snapshot.dispatch().singleRayDispatches(),
                    "Single ray dispatches should be sum of both frames");
    }

    /**
     * T29: testConcurrentRecording - Multiple threads can read snapshots safely.
     * Verifies that concurrent snapshot retrieval is thread-safe while
     * a single thread records frames (expected usage pattern).
     */
    @Test
    void testConcurrentRecording() throws InterruptedException {
        // Given: A default metrics collector
        var collector = new DefaultMetricsCollector();

        var readerThreadCount = 10;
        var iterationsPerThread = 100;
        var latch = new CountDownLatch(1 + readerThreadCount);
        var errors = new AtomicInteger(0);
        var stopRecording = new AtomicInteger(0);

        // When: Single writer thread records metrics
        new Thread(() -> {
            try {
                while (stopRecording.get() == 0) {
                    collector.startFrame();
                    collector.recordGPUTiming(100_000L);
                    collector.recordDispatchMetrics(DispatchMetrics.from(2, 1, 1));

                    // Small delay to ensure frames have measurable time
                    Thread.sleep(1);

                    collector.endFrame();
                }
            } catch (Exception e) {
                errors.incrementAndGet();
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        }).start();

        // And: Multiple reader threads retrieve snapshots concurrently
        for (int i = 0; i < readerThreadCount; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        var snapshot = collector.getSnapshot();
                        assertNotNull(snapshot, "Snapshot should not be null");

                        // Small delay between reads
                        Thread.sleep(1);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for readers to complete
        Thread.sleep(150);
        stopRecording.set(1);

        // Then: All threads complete without errors
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        assertEquals(0, errors.get(), "No errors should occur during concurrent access");
    }

    /**
     * T29b: testBeamTreeStatsRecording - BeamTree stats are recorded.
     */
    @Test
    void testBeamTreeStatsRecording() {
        // Given: A default metrics collector
        var collector = new DefaultMetricsCollector();

        // When: Recording BeamTree stats
        collector.startFrame();

        var coherence = new CoherenceSnapshot(0.75, 0.5, 0.95, 100, 5);
        collector.recordBeamTreeStats(coherence);
        collector.recordDispatchMetrics(DispatchMetrics.from(10, 8, 2));

        collector.endFrame();

        // Then: Snapshot contains BeamTree stats
        var snapshot = collector.getSnapshot();
        assertNotNull(snapshot, "Snapshot should not be null");
        assertNotNull(snapshot.coherence(), "Coherence should not be null");
        assertEquals(0.75, snapshot.coherence().averageCoherence(), 0.01,
                    "Average coherence should match");
    }

    /**
     * T29c: testReset - Reset clears all metrics.
     */
    @Test
    void testReset() {
        // Given: A collector with recorded metrics
        var collector = new DefaultMetricsCollector();

        collector.startFrame();
        collector.recordGPUTiming(1_000_000L);
        collector.recordDispatchMetrics(DispatchMetrics.from(10, 8, 2));
        collector.endFrame();

        var snapshotBefore = collector.getSnapshot();
        assertTrue(snapshotBefore.dispatch().totalDispatches() > 0,
                  "Should have dispatches before reset");

        // When: Resetting the collector
        collector.reset();

        // Then: Snapshot is empty
        var snapshotAfter = collector.getSnapshot();
        assertEquals(0, snapshotAfter.dispatch().totalDispatches(),
                    "Dispatches should be zero after reset");
        assertEquals(0.0, snapshotAfter.avgFrameTimeMs(),
                    "Frame time should be zero after reset");
    }
}
