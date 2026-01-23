package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PartitionView} interface contract.
 */
class PartitionViewTest {

    /**
     * Simple test implementation of PartitionView.
     */
    private static class TestPartitionView implements PartitionView {
        private final UUID partitionId;
        private final PartitionStatus status;
        private final long lastSeenMs;
        private final int nodeCount;
        private final int healthyNodes;
        private final FaultMetrics metrics;

        TestPartitionView(UUID partitionId, PartitionStatus status, long lastSeenMs,
                          int nodeCount, int healthyNodes, FaultMetrics metrics) {
            this.partitionId = partitionId;
            this.status = status;
            this.lastSeenMs = lastSeenMs;
            this.nodeCount = nodeCount;
            this.healthyNodes = healthyNodes;
            this.metrics = metrics;
        }

        @Override
        public UUID partitionId() {
            return partitionId;
        }

        @Override
        public PartitionStatus status() {
            return status;
        }

        @Override
        public long lastSeenMs() {
            return lastSeenMs;
        }

        @Override
        public int nodeCount() {
            return nodeCount;
        }

        @Override
        public int healthyNodes() {
            return healthyNodes;
        }

        @Override
        public FaultMetrics metrics() {
            return metrics;
        }
    }

    @Test
    void testInterfaceContract() {
        var id = UUID.randomUUID();
        var metrics = FaultMetrics.zero();
        var view = new TestPartitionView(
            id,
            PartitionStatus.HEALTHY,
            System.currentTimeMillis(),
            5,
            5,
            metrics
        );

        assertEquals(id, view.partitionId());
        assertEquals(PartitionStatus.HEALTHY, view.status());
        assertTrue(view.lastSeenMs() > 0);
        assertEquals(5, view.nodeCount());
        assertEquals(5, view.healthyNodes());
        assertEquals(metrics, view.metrics());
    }

    @Test
    void testHealthyPartition() {
        var view = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.HEALTHY,
            System.currentTimeMillis(),
            10,
            10,
            FaultMetrics.zero()
        );

        assertEquals(PartitionStatus.HEALTHY, view.status());
        assertEquals(10, view.nodeCount());
        assertEquals(10, view.healthyNodes());
    }

    @Test
    void testSuspectedPartition() {
        var now = System.currentTimeMillis();
        var view = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.SUSPECTED,
            now - 3000,  // Last seen 3 seconds ago
            10,
            10,
            FaultMetrics.zero()
        );

        assertEquals(PartitionStatus.SUSPECTED, view.status());
        assertTrue(now - view.lastSeenMs() >= 3000);
    }

    @Test
    void testFailedPartition() {
        var view = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.FAILED,
            System.currentTimeMillis() - 10000,
            10,
            0,  // All nodes failed
            FaultMetrics.zero().withIncrementedFailureCount()
        );

        assertEquals(PartitionStatus.FAILED, view.status());
        assertEquals(0, view.healthyNodes());
        assertEquals(1, view.metrics().failureCount());
    }

    @Test
    void testRecoveringPartition() {
        var view = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.RECOVERING,
            System.currentTimeMillis(),
            10,
            7,  // Partially recovered
            FaultMetrics.zero().withIncrementedRecoveryAttempts()
        );

        assertEquals(PartitionStatus.RECOVERING, view.status());
        assertEquals(7, view.healthyNodes());
        assertEquals(10, view.nodeCount());
        assertEquals(1, view.metrics().recoveryAttempts());
    }

    @Test
    void testDegradedPartition() {
        var view = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.DEGRADED,
            System.currentTimeMillis(),
            10,
            5,  // Half healthy
            FaultMetrics.zero()
        );

        assertEquals(PartitionStatus.DEGRADED, view.status());
        assertEquals(5, view.healthyNodes());
        assertTrue(view.healthyNodes() < view.nodeCount());
    }

    @Test
    void testMetricsIntegration() {
        var metrics = new FaultMetrics(
            150,  // detectionLatencyMs
            5000, // recoveryLatencyMs
            3,    // failureCount
            5,    // recoveryAttempts
            4,    // successfulRecoveries
            1     // failedRecoveries
        );

        var view = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.HEALTHY,
            System.currentTimeMillis(),
            10,
            10,
            metrics
        );

        assertEquals(metrics, view.metrics());
        assertEquals(150, view.metrics().detectionLatencyMs());
        assertEquals(5000, view.metrics().recoveryLatencyMs());
    }

    @Test
    void testTimestampSemantics() {
        var now = System.currentTimeMillis();

        var view1 = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.HEALTHY,
            now - 1000,
            10,
            10,
            FaultMetrics.zero()
        );

        var view2 = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.HEALTHY,
            now,
            10,
            10,
            FaultMetrics.zero()
        );

        assertTrue(view1.lastSeenMs() < view2.lastSeenMs());
        assertEquals(1000, view2.lastSeenMs() - view1.lastSeenMs(), 10);
    }

    @Test
    void testZeroNodes() {
        var view = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.FAILED,
            System.currentTimeMillis(),
            0,
            0,
            FaultMetrics.zero()
        );

        assertEquals(0, view.nodeCount());
        assertEquals(0, view.healthyNodes());
    }

    @Test
    void testPartialNodeFailure() {
        var view = new TestPartitionView(
            UUID.randomUUID(),
            PartitionStatus.DEGRADED,
            System.currentTimeMillis(),
            10,
            3,  // 70% nodes failed
            FaultMetrics.zero()
        );

        assertEquals(10, view.nodeCount());
        assertEquals(3, view.healthyNodes());
        assertTrue(view.healthyNodes() < view.nodeCount());
    }
}
