/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.causality;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import com.hellblazer.luciferase.simulation.ghost.GhostPhysicsMetrics;
import com.hellblazer.luciferase.simulation.ghost.GhostStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GhostPhysicsPerformanceTest - Phase 7D.2 Part 2 Phase C Tests.
 * <p>
 * Tests performance metrics tracking for ghost physics operations:
 * <ul>
 *   <li>Metrics initialization to zero</li>
 *   <li>Count incrementing for operations (updateGhost, removeGhost)</li>
 *   <li>Latency recording for operations</li>
 *   <li>Average latency calculation</li>
 *   <li>Performance targets (< 100ms for 1000 ghosts)</li>
 *   <li>Concurrent operations without metric loss (AtomicLong correctness)</li>
 * </ul>
 * <p>
 * Quality target: 9.0+/10 (BDD style, clear assertions, comprehensive coverage).
 *
 * @author hal.hildebrand
 */
class GhostPhysicsPerformanceTest {

    private GhostStateManager ghostStateManager;
    private GhostPhysicsMetrics metrics;
    private BubbleBounds bounds;
    private UUID sourceBubbleId;

    @BeforeEach
    void setUp() {
        // Arrange: Create root TetreeKey at level 10 and convert to bounds
        var rootKey = TetreeKey.create((byte) 10, 0L, 0L);
        bounds = BubbleBounds.fromTetreeKey(rootKey);

        // Create GhostStateManager with 1000 ghost limit
        ghostStateManager = new GhostStateManager(bounds, 1000);

        // Create metrics and attach to manager
        metrics = new GhostPhysicsMetrics();
        ghostStateManager.setMetrics(metrics);

        // Test source bubble ID
        sourceBubbleId = UUID.randomUUID();
    }

    /**
     * Test 1: Metrics initialized to zero.
     * <p>
     * Scenario: Create fresh GhostPhysicsMetrics instance.
     * Expected: All counters and latencies start at zero.
     */
    @Test
    void testMetricsInitializedToZero() {
        // Arrange: Fresh metrics (created in setUp)

        // Act: Read initial values

        // Assert: All metrics start at zero
        assertEquals(0L, metrics.getUpdateGhostCount(), "updateGhostCount should start at 0");
        assertEquals(0L, metrics.getRemoveGhostCount(), "removeGhostCount should start at 0");
        assertEquals(0L, metrics.getReconciliationCount(), "reconciliationCount should start at 0");
        assertEquals(0L, metrics.getUpdateGhostLatency(), "updateGhostLatency should start at 0");
        assertEquals(0L, metrics.getRemoveGhostLatency(), "removeGhostLatency should start at 0");
    }

    /**
     * Test 2: updateGhost() increments updateGhostCount.
     * <p>
     * Scenario: Call updateGhost() multiple times.
     * Expected: updateGhostCount increments for each call.
     */
    @Test
    void testUpdateGhostIncrementsCount() {
        // Arrange
        var entityId = new StringEntityID("entity-metrics-1");
        var position = new Point3f(1.0f, 1.0f, 1.0f);
        var velocity = new Point3f(5.0f, 5.0f, 5.0f);
        var timestamp = System.currentTimeMillis();

        // Act: Update ghost 5 times
        for (int i = 0; i < 5; i++) {
            var event = new EntityUpdateEvent(entityId, position, velocity, timestamp + i, 0L);
            ghostStateManager.updateGhost(sourceBubbleId, event);
        }

        // Assert: Count should be 5
        assertEquals(5L, metrics.getUpdateGhostCount(), "updateGhostCount should be 5 after 5 updates");
    }

    /**
     * Test 3: removeGhost() increments removeGhostCount.
     * <p>
     * Scenario: Create ghost, then remove it.
     * Expected: removeGhostCount increments.
     */
    @Test
    void testRemoveGhostIncrementsCount() {
        // Arrange: Create 3 ghosts
        for (int i = 0; i < 3; i++) {
            var entityId = new StringEntityID("entity-remove-" + i);
            var position = new Point3f(1.0f + i, 1.0f, 1.0f);
            var velocity = new Point3f(5.0f, 5.0f, 5.0f);
            var timestamp = System.currentTimeMillis();
            var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 0L);
            ghostStateManager.updateGhost(sourceBubbleId, event);
        }

        // Act: Remove all 3 ghosts
        for (int i = 0; i < 3; i++) {
            var entityId = new StringEntityID("entity-remove-" + i);
            ghostStateManager.removeGhost(entityId);
        }

        // Assert: Remove count should be 3
        assertEquals(3L, metrics.getRemoveGhostCount(), "removeGhostCount should be 3 after 3 removals");
    }

    /**
     * Test 4: Latency recorded for operations.
     * <p>
     * Scenario: Perform updateGhost() and removeGhost() operations.
     * Expected: Latency values increase (non-zero after operations).
     */
    @Test
    void testLatencyRecorded() {
        // Arrange
        var entityId = new StringEntityID("entity-latency");
        var position = new Point3f(1.0f, 1.0f, 1.0f);
        var velocity = new Point3f(5.0f, 5.0f, 5.0f);
        var timestamp = System.currentTimeMillis();

        // Act: Update ghost
        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 0L);
        ghostStateManager.updateGhost(sourceBubbleId, event);

        // Remove ghost
        ghostStateManager.removeGhost(entityId);

        // Assert: Latencies should be non-zero
        assertTrue(metrics.getUpdateGhostLatency() > 0L, "updateGhostLatency should be > 0 after update");
        assertTrue(metrics.getRemoveGhostLatency() > 0L, "removeGhostLatency should be > 0 after remove");
    }

    /**
     * Test 5: Average latency calculation correct.
     * <p>
     * Scenario: Perform multiple operations, calculate average.
     * Expected: Average latency = total latency / count.
     */
    @Test
    void testAverageLatencyCalculation() {
        // Arrange: Perform 10 update operations
        for (int i = 0; i < 10; i++) {
            var entityId = new StringEntityID("entity-avg-" + i);
            var position = new Point3f(1.0f + i, 1.0f, 1.0f);
            var velocity = new Point3f(5.0f, 5.0f, 5.0f);
            var timestamp = System.currentTimeMillis();
            var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 0L);
            ghostStateManager.updateGhost(sourceBubbleId, event);
        }

        // Act: Calculate average
        var avgUpdateLatency = metrics.getUpdateGhostAverage();

        // Assert: Average should be total / count
        var expectedAvg = metrics.getUpdateGhostLatency() / 10L;
        assertEquals(expectedAvg, avgUpdateLatency, "Average latency should match total / count");
        assertTrue(avgUpdateLatency > 0L, "Average latency should be positive");
    }

    /**
     * Test 6: Average latency < 100ms (hard target).
     * <p>
     * Scenario: Perform 1000 ghost operations.
     * Expected: Average operation latency < 100ms (hard target), stretch goal < 0.1ms.
     */
    @Test
    void testPerformanceTarget() {
        // Arrange: Perform 1000 operations
        var startTime = System.nanoTime();

        for (int i = 0; i < 1000; i++) {
            var entityId = new StringEntityID("entity-perf-" + i);
            var position = new Point3f(1.0f + i % 100, 1.0f + i / 100, 1.0f);
            var velocity = new Point3f(5.0f, 5.0f, 5.0f);
            var timestamp = System.currentTimeMillis();
            var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 0L);
            ghostStateManager.updateGhost(sourceBubbleId, event);
        }

        var totalTime = System.nanoTime() - startTime;

        // Act: Calculate average latency in milliseconds
        var avgLatencyNs = metrics.getUpdateGhostAverage();
        var avgLatencyMs = avgLatencyNs / 1_000_000.0;

        // Assert: Hard target < 100ms
        assertTrue(avgLatencyMs < 100.0, String.format(
            "Average latency should be < 100ms (hard target), got %.3fms", avgLatencyMs
        ));

        // Stretch goal (informational, not required)
        if (avgLatencyMs < 0.1) {
            System.out.println(String.format("STRETCH GOAL MET: Average latency %.6fms < 0.1ms", avgLatencyMs));
        }

        // Also check total time for 1000 operations
        var totalTimeMs = totalTime / 1_000_000.0;
        assertTrue(totalTimeMs < 100.0, String.format(
            "Total time for 1000 operations should be < 100ms, got %.3fms", totalTimeMs
        ));
    }

    /**
     * Test 7: Concurrent operations don't lose metric counts (AtomicLong correctness).
     * <p>
     * Scenario: Multiple threads concurrently updating ghosts.
     * Expected: Total count = sum of all thread operations (no lost updates).
     */
    @Test
    void testConcurrentOperationsPreserveMetrics() throws InterruptedException {
        // Arrange
        var threadCount = 10;
        var operationsPerThread = 100;
        var latch = new CountDownLatch(threadCount);
        var errorCount = new AtomicInteger(0);

        // Act: Spawn threads to update ghosts concurrently
        for (int t = 0; t < threadCount; t++) {
            var threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        var entityId = new StringEntityID("thread-" + threadId + "-entity-" + i);
                        var position = new Point3f(1.0f + i, 1.0f, 1.0f);
                        var velocity = new Point3f(5.0f, 5.0f, 5.0f);
                        var timestamp = System.currentTimeMillis();
                        var event = new EntityUpdateEvent(entityId, position, velocity, timestamp, 0L);
                        ghostStateManager.updateGhost(sourceBubbleId, event);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for all threads
        latch.await();

        // Assert: Total count should equal threadCount × operationsPerThread
        var expectedCount = threadCount * operationsPerThread;
        assertEquals(expectedCount, metrics.getUpdateGhostCount(),
            "Concurrent operations should not lose count updates");
        assertEquals(0, errorCount.get(), "No errors should occur during concurrent operations");
    }
}
