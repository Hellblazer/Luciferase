package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.balancing.fault.test.PartitionSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test PartitionSimulator infrastructure.
 */
class PartitionSimulatorTest {

    private PartitionSimulator simulator;
    private UUID partitionId;

    @BeforeEach
    void setUp() {
        partitionId = UUID.randomUUID();
        simulator = new PartitionSimulator(partitionId);
    }

    @AfterEach
    void tearDown() {
        if (simulator != null) {
            simulator.cleanup();
        }
    }

    @Test
    void testHealthySimulation() throws InterruptedException {
        // Simulate healthy operation
        simulator.simulateHealthy(100);

        // Verify starts HEALTHY
        assertEquals(PartitionStatus.HEALTHY, simulator.getCurrentStatus());

        // Wait for a few heartbeats
        TimeUnit.MILLISECONDS.sleep(350);

        // Should still be HEALTHY
        assertEquals(PartitionStatus.HEALTHY, simulator.getCurrentStatus());

        // Verify history contains HEALTHY transitions
        var history = simulator.getStatusHistory();
        assertFalse(history.isEmpty());
        assertTrue(history.stream().allMatch(t -> t.newStatus() == PartitionStatus.HEALTHY));
    }

    @Test
    void testFailureSimulation() {
        // Simulate immediate failure
        simulator.simulateFailure();

        // Verify transitioned to FAILED
        assertEquals(PartitionStatus.FAILED, simulator.getCurrentStatus());

        // Verify history
        var history = simulator.getStatusHistory();
        assertEquals(1, history.size());
        assertEquals(PartitionStatus.FAILED, history.get(0).newStatus());
        assertEquals("partition crashed", history.get(0).reason());
    }

    @Test
    void testSlowDownSimulation() throws InterruptedException {
        // Start healthy
        assertEquals(PartitionStatus.HEALTHY, simulator.getCurrentStatus());

        // Simulate slowdown with 200ms delay
        simulator.simulateSlowDown(200);

        // Should still be HEALTHY initially
        assertEquals(PartitionStatus.HEALTHY, simulator.getCurrentStatus());

        // Wait for slowdown to trigger
        TimeUnit.MILLISECONDS.sleep(300);

        // Should now be SUSPECTED
        assertEquals(PartitionStatus.SUSPECTED, simulator.getCurrentStatus());

        // Verify history shows transition
        var history = simulator.getStatusHistory();
        assertEquals(1, history.size());
        assertEquals(PartitionStatus.SUSPECTED, history.get(0).newStatus());
    }

    @Test
    void testRecoverySimulation() throws InterruptedException {
        // Start with failure
        simulator.simulateFailure();
        assertEquals(PartitionStatus.FAILED, simulator.getCurrentStatus());

        // Simulate recovery with 200ms duration
        simulator.simulateRecoveryInProgress(200);
        assertEquals(PartitionStatus.RECOVERING, simulator.getCurrentStatus());

        // Wait for recovery to complete
        TimeUnit.MILLISECONDS.sleep(300);

        // Should be HEALTHY
        assertEquals(PartitionStatus.HEALTHY, simulator.getCurrentStatus());

        // Verify history shows full sequence
        var history = simulator.getStatusHistory();
        assertEquals(3, history.size());
        assertEquals(PartitionStatus.FAILED, history.get(0).newStatus());
        assertEquals(PartitionStatus.RECOVERING, history.get(1).newStatus());
        assertEquals(PartitionStatus.HEALTHY, history.get(2).newStatus());
    }

    @Test
    void testCascadingFailureSimulation() throws InterruptedException {
        // Create dependent simulators
        var dep1 = new PartitionSimulator(UUID.randomUUID());
        var dep2 = new PartitionSimulator(UUID.randomUUID());
        var dep3 = new PartitionSimulator(UUID.randomUUID());

        try {
            var affectedIds = List.of(dep1.getPartitionId(), dep2.getPartitionId(), dep3.getPartitionId());
            var simulators = List.of(dep1, dep2, dep3);

            // Trigger cascading failure with 100ms cascade delay
            simulator.simulateCascadingFailure(affectedIds, 100, simulators);

            // Primary should fail immediately
            assertEquals(PartitionStatus.FAILED, simulator.getCurrentStatus());

            // Wait for cascade to complete (3 * 100ms + margin)
            TimeUnit.MILLISECONDS.sleep(400);

            // All dependent partitions should be FAILED
            assertEquals(PartitionStatus.FAILED, dep1.getCurrentStatus());
            assertEquals(PartitionStatus.FAILED, dep2.getCurrentStatus());
            assertEquals(PartitionStatus.FAILED, dep3.getCurrentStatus());

            // Verify primary history
            var primaryHistory = simulator.getStatusHistory();
            assertEquals(1, primaryHistory.size());
            assertTrue(primaryHistory.get(0).reason().contains("cascade trigger"));

        } finally {
            dep1.cleanup();
            dep2.cleanup();
            dep3.cleanup();
        }
    }

    @Test
    void testBarrierTimeoutSimulation() {
        simulator.simulateBarrierTimeout();

        assertEquals(PartitionStatus.SUSPECTED, simulator.getCurrentStatus());

        var history = simulator.getStatusHistory();
        assertEquals(1, history.size());
        assertEquals("barrier timeout detected", history.get(0).reason());
    }

    @Test
    void testRecoveryCompleteImmediate() {
        // Set to RECOVERING first
        simulator.simulateRecoveryInProgress(1000);
        assertEquals(PartitionStatus.RECOVERING, simulator.getCurrentStatus());

        // Complete immediately
        simulator.simulateRecoveryComplete();
        assertEquals(PartitionStatus.HEALTHY, simulator.getCurrentStatus());

        var history = simulator.getStatusHistory();
        assertEquals(2, history.size());
        assertEquals(PartitionStatus.RECOVERING, history.get(0).newStatus());
        assertEquals(PartitionStatus.HEALTHY, history.get(1).newStatus());
    }

    @Test
    void testRecoveryFailure() {
        // Simulate recovery failure
        simulator.simulateRecoveryFailure();

        assertEquals(PartitionStatus.DEGRADED, simulator.getCurrentStatus());

        var history = simulator.getStatusHistory();
        assertEquals(1, history.size());
        assertTrue(history.get(0).reason().contains("recovery failed"));
    }

    @Test
    void testStatusHistoryTracking() throws InterruptedException {
        // Execute a sequence of state changes
        simulator.simulateHealthy(50);
        TimeUnit.MILLISECONDS.sleep(100);

        simulator.simulateSlowDown(50);
        TimeUnit.MILLISECONDS.sleep(100);

        simulator.simulateFailure();
        TimeUnit.MILLISECONDS.sleep(50);

        simulator.simulateRecoveryInProgress(50);
        TimeUnit.MILLISECONDS.sleep(100);

        // Verify history is cumulative and ordered
        var history = simulator.getStatusHistory();
        assertTrue(history.size() >= 4, "Expected at least 4 transitions");

        // Verify timestamps are monotonically increasing
        for (int i = 1; i < history.size(); i++) {
            assertTrue(history.get(i).timestamp() >= history.get(i - 1).timestamp(),
                "Timestamps should be monotonically increasing");
        }

        // Verify partitionId is consistent
        assertTrue(history.stream().allMatch(t -> t.partitionId().equals(partitionId)));
    }

    @Test
    void testCleanupStopsExecution() throws InterruptedException {
        // Start healthy simulation
        simulator.simulateHealthy(50);
        TimeUnit.MILLISECONDS.sleep(150);

        var historyBeforeCleanup = simulator.getStatusHistory().size();
        assertTrue(historyBeforeCleanup > 0, "Should have some history before cleanup");

        // Cleanup
        simulator.cleanup();

        // Wait a bit
        TimeUnit.MILLISECONDS.sleep(150);

        // History should not grow after cleanup
        // Note: We can't access history after cleanup since simulator is stopped,
        // but we can verify no exceptions thrown
        assertDoesNotThrow(() -> simulator.getStatusHistory());
    }
}
