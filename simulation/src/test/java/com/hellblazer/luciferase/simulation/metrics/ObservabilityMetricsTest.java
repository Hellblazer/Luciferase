package com.hellblazer.luciferase.simulation.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for ObservabilityMetrics.
 * <p>
 * Validates:
 * - Frame utilization calculation (CPU % from frame time / target time)
 * - Neighbor count tracking across multiple bubbles
 * - Ghost sync latency recording
 * - Snapshot aggregation (average utilization, total neighbors)
 * - Thread safety (concurrent updates)
 * - Realistic scenarios (underutilized: 80ms/100ms = 80%)
 *
 * @author hal.hildebrand
 */
class ObservabilityMetricsTest {

    private ObservabilityMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new ObservabilityMetrics();
    }

    @Test
    void testRecordAnimatorFrame_PerfectUtilization() {
        // 100ms frame / 100ms target = 100% utilization
        var bubbleId = UUID.randomUUID();

        metrics.recordAnimatorFrame(bubbleId, 100_000_000L, 100_000_000L);  // Both in nanoseconds

        var snapshot = metrics.getSnapshot();
        assertEquals(1.0f, snapshot.avgAnimatorUtilization(), 0.01f,
                    "100ms frame / 100ms target should be 100% utilization");
        assertEquals(1, snapshot.activeBubbleCount(), "Should have 1 active bubble");
    }

    @Test
    void testRecordAnimatorFrame_Underutilized() {
        // 80ms frame / 100ms target = 80% utilization
        var bubbleId = UUID.randomUUID();

        metrics.recordAnimatorFrame(bubbleId, 80_000_000L, 100_000_000L);

        var snapshot = metrics.getSnapshot();
        assertEquals(0.80f, snapshot.avgAnimatorUtilization(), 0.01f,
                    "80ms frame / 100ms target should be 80% utilization");
    }

    @Test
    void testRecordAnimatorFrame_Overutilized() {
        // 150ms frame / 100ms target = 150% utilization (over budget)
        var bubbleId = UUID.randomUUID();

        metrics.recordAnimatorFrame(bubbleId, 150_000_000L, 100_000_000L);

        var snapshot = metrics.getSnapshot();
        assertEquals(1.50f, snapshot.avgAnimatorUtilization(), 0.01f,
                    "150ms frame / 100ms target should be 150% utilization");
    }

    @Test
    void testRecordAnimatorFrame_MultipleBubbles_AverageUtilization() {
        // Bubble 1: 100% utilization
        var bubble1 = UUID.randomUUID();
        metrics.recordAnimatorFrame(bubble1, 100_000_000L, 100_000_000L);

        // Bubble 2: 80% utilization
        var bubble2 = UUID.randomUUID();
        metrics.recordAnimatorFrame(bubble2, 80_000_000L, 100_000_000L);

        var snapshot = metrics.getSnapshot();
        // Average: (100% + 80%) / 2 = 90%
        assertEquals(0.90f, snapshot.avgAnimatorUtilization(), 0.01f,
                    "Average utilization should be (100% + 80%) / 2 = 90%");
        assertEquals(2, snapshot.activeBubbleCount(), "Should have 2 active bubbles");
    }

    @Test
    void testRecordNeighborCount_SingleBubble() {
        var bubbleId = UUID.randomUUID();

        metrics.recordNeighborCount(bubbleId, 5);

        var snapshot = metrics.getSnapshot();
        assertEquals(5, snapshot.totalVonNeighbors(), "Should have 5 total neighbors");
    }

    @Test
    void testRecordNeighborCount_MultipleBubbles() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var bubble3 = UUID.randomUUID();

        metrics.recordNeighborCount(bubble1, 3);
        metrics.recordNeighborCount(bubble2, 5);
        metrics.recordNeighborCount(bubble3, 2);

        var snapshot = metrics.getSnapshot();
        // Total: 3 + 5 + 2 = 10
        assertEquals(10, snapshot.totalVonNeighbors(), "Total neighbors should be 3 + 5 + 2 = 10");
        assertEquals(3, snapshot.activeBubbleCount(), "Should have 3 active bubbles");
    }

    @Test
    void testRecordNeighborCount_UpdateExisting() {
        var bubbleId = UUID.randomUUID();

        // Initial count
        metrics.recordNeighborCount(bubbleId, 3);
        var snapshot1 = metrics.getSnapshot();
        assertEquals(3, snapshot1.totalVonNeighbors(), "Initial count should be 3");

        // Update to new count
        metrics.recordNeighborCount(bubbleId, 7);
        var snapshot2 = metrics.getSnapshot();
        assertEquals(7, snapshot2.totalVonNeighbors(), "Updated count should be 7, not 3+7");
        assertEquals(1, snapshot2.activeBubbleCount(), "Should still have 1 bubble");
    }

    @Test
    void testRecordGhostLatency_SingleSample() {
        metrics.recordGhostLatency(50_000_000L);  // 50ms in nanoseconds

        var snapshot = metrics.getSnapshot();
        assertNotNull(snapshot.ghostSyncLatencyNs(), "Ghost latency should not be null");
        assertEquals(50_000_000L, snapshot.ghostSyncLatencyNs(), "Should record 50ms latency");
    }

    @Test
    void testRecordGhostLatency_MultipleSamples() {
        // Record multiple latencies
        metrics.recordGhostLatency(30_000_000L);  // 30ms
        metrics.recordGhostLatency(50_000_000L);  // 50ms
        metrics.recordGhostLatency(40_000_000L);  // 40ms

        var snapshot = metrics.getSnapshot();
        // Should track latest latency (integration with Task 2 will add averaging)
        assertNotNull(snapshot.ghostSyncLatencyNs(), "Ghost latency should not be null");
        assertTrue(snapshot.ghostSyncLatencyNs() > 0, "Should have positive latency");
    }

    @Test
    void testGetSnapshot_EmptyMetrics() {
        var snapshot = metrics.getSnapshot();

        assertEquals(0.0f, snapshot.avgAnimatorUtilization(), 0.01f,
                    "Empty metrics should have 0% utilization");
        assertEquals(0, snapshot.totalVonNeighbors(), "Empty metrics should have 0 neighbors");
        assertEquals(0, snapshot.activeBubbleCount(), "Empty metrics should have 0 bubbles");
        assertNull(snapshot.ghostSyncLatencyNs(), "Empty metrics should have null latency");
        assertTrue(snapshot.timestamp() > 0, "Timestamp should be set");
    }

    @Test
    void testThreadSafety_ConcurrentUpdates() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);

        // Create 10 bubbles, 10 updates each = 100 total updates
        List<UUID> bubbleIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bubbleIds.add(UUID.randomUUID());
        }

        // Submit concurrent updates
        for (int i = 0; i < 100; i++) {
            var bubbleId = bubbleIds.get(i % 10);
            executor.submit(() -> {
                try {
                    // Update frame time
                    metrics.recordAnimatorFrame(bubbleId, 90_000_000L, 100_000_000L);
                    // Update neighbor count
                    metrics.recordNeighborCount(bubbleId, 5);
                    // Update ghost latency
                    metrics.recordGhostLatency(40_000_000L);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all updates to complete
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All updates should complete within 5 seconds");

        // Verify snapshot is consistent (no exceptions, reasonable values)
        var snapshot = metrics.getSnapshot();
        assertEquals(10, snapshot.activeBubbleCount(), "Should have 10 unique bubbles");
        assertEquals(50, snapshot.totalVonNeighbors(), "Should have 10 bubbles * 5 neighbors = 50");
        assertEquals(0.90f, snapshot.avgAnimatorUtilization(), 0.01f,
                    "All bubbles at 90% utilization");
        assertNotNull(snapshot.ghostSyncLatencyNs(), "Should have ghost latency");

        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS), "Executor should terminate");
    }
}
