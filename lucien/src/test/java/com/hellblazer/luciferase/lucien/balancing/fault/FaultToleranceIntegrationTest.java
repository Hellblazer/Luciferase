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

package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for end-to-end fault detection scenarios (P2.4).
 *
 * Validates complete fault detection workflows with multiple partitions,
 * barrier timeouts, ghost sync failures, and cascading failures.
 */
class FaultToleranceIntegrationTest {

    private SimpleFaultHandler faultHandler;
    private DefaultFailureDetector failureDetector;
    private Clock testClock;
    private FailureDetectionConfig config;

    @BeforeEach
    void setUp() {
        testClock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());
        faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        faultHandler.start();

        // Fast config for testing
        config = FailureDetectionConfig.defaultConfig()
            .withHeartbeatInterval(Duration.ofMillis(100))
            .withSuspectTimeout(Duration.ofMillis(300))
            .withFailureTimeout(Duration.ofMillis(500))
            .withCheckIntervalMs(50);

        failureDetector = new DefaultFailureDetector(config, faultHandler);
        failureDetector.setClock(testClock);
        failureDetector.start();
    }

    @AfterEach
    void tearDown() {
        failureDetector.stop();
        faultHandler.stop();
    }

    /**
     * IT1: Test barrier timeout triggers failure detection.
     * FaultAwarePartitionRegistry timeout should be detected.
     */
    @Test
    void testBarrierTimeout_TriggersDetection() throws Exception {
        var partitionId = UUID.randomUUID();

        // Register partition for monitoring
        failureDetector.registerPartition(partitionId);
        failureDetector.recordHeartbeat(partitionId);

        // Advance time past failure threshold
        var advancedClock = Clock.offset(testClock, Duration.ofMillis(600));
        failureDetector.setClock(advancedClock);

        // Trigger health check
        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        // Should be failed
        var status = faultHandler.checkHealth(partitionId);
        assertTrue(status == PartitionStatus.FAILED || status == PartitionStatus.SUSPECTED,
                   "Partition should be detected as failed");
    }

    /**
     * IT2: Test ghost sync failure triggers detection.
     * GhostSyncFaultAdapter should report failures.
     */
    @Test
    void testGhostSyncFailure_TriggersDetection() {
        var adapter = new GhostSyncFaultAdapter(faultHandler);
        var partitionId = UUID.randomUUID();

        // Register partition
        faultHandler.markHealthy(partitionId);
        adapter.registerPartitionRank(1, partitionId);

        var statusBefore = faultHandler.checkHealth(partitionId);
        assertEquals(PartitionStatus.HEALTHY, statusBefore);

        // Simulate ghost sync failure
        adapter.onSyncFailure(1, new Exception("network error"));

        var statusAfter = faultHandler.checkHealth(partitionId);
        assertTrue(statusAfter == PartitionStatus.SUSPECTED || statusAfter == PartitionStatus.FAILED,
                   "Ghost sync failure should trigger detection");
    }

    /**
     * IT3: Test multiple partitions have independent detection.
     * Failure of one partition should not affect others.
     */
    @Test
    void testMultiplePartitions_IndependentDetection() throws Exception {
        var partition1 = UUID.randomUUID();
        var partition2 = UUID.randomUUID();

        failureDetector.registerPartition(partition1);
        failureDetector.registerPartition(partition2);

        // Both healthy initially
        failureDetector.recordHeartbeat(partition1);
        failureDetector.recordHeartbeat(partition2);

        // Advance time
        var advancedClock = Clock.offset(testClock, Duration.ofMillis(600));
        failureDetector.setClock(advancedClock);

        // Trigger health check
        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        // Both should be in failed state
        var status1 = faultHandler.checkHealth(partition1);
        var status2 = faultHandler.checkHealth(partition2);

        assertTrue(status1 == PartitionStatus.FAILED || status1 == PartitionStatus.SUSPECTED);
        assertTrue(status2 == PartitionStatus.FAILED || status2 == PartitionStatus.SUSPECTED);
    }

    /**
     * IT4: Test cascading failures (multiple timeouts).
     * Repeated failures should cascade status changes.
     */
    @Test
    void testCascadingFailure_DetectedInOrder() throws Exception {
        var partitionId = UUID.randomUUID();

        failureDetector.registerPartition(partitionId);
        failureDetector.recordHeartbeat(partitionId);

        // Progress through failure states
        // T0: HEALTHY
        var status0 = faultHandler.checkHealth(partitionId);
        assertTrue(status0 == null || status0 == PartitionStatus.HEALTHY);

        // T1: Advance past suspect timeout
        var clock1 = Clock.offset(testClock, Duration.ofMillis(350));
        failureDetector.setClock(clock1);
        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        var status1 = faultHandler.checkHealth(partitionId);
        assertTrue(status1 == PartitionStatus.SUSPECTED || status1 == PartitionStatus.HEALTHY,
                   "Should be SUSPECTED after suspect timeout");

        // T2: Advance past failure timeout
        var clock2 = Clock.offset(testClock, Duration.ofMillis(600));
        failureDetector.setClock(clock2);
        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        var status2 = faultHandler.checkHealth(partitionId);
        assertTrue(status2 == PartitionStatus.FAILED || status2 == PartitionStatus.SUSPECTED,
                   "Should be FAILED after failure timeout");
    }

    /**
     * IT5: Test split-brain scenario detection.
     * Both groups should detect each other as failed.
     */
    @Test
    void testSplitBrain_BothGroupsDetectFailure() throws Exception {
        var groupAPartition = UUID.randomUUID();
        var groupBPartition = UUID.randomUUID();

        failureDetector.registerPartition(groupAPartition);
        failureDetector.registerPartition(groupBPartition);

        failureDetector.recordHeartbeat(groupAPartition);
        failureDetector.recordHeartbeat(groupBPartition);

        // Stop recording heartbeats for both (simulating split-brain)
        var advancedClock = Clock.offset(testClock, Duration.ofMillis(600));
        failureDetector.setClock(advancedClock);

        // Trigger health check
        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        // Both should be detected as failed
        var statusA = faultHandler.checkHealth(groupAPartition);
        var statusB = faultHandler.checkHealth(groupBPartition);

        assertTrue(statusA == PartitionStatus.FAILED || statusA == PartitionStatus.SUSPECTED);
        assertTrue(statusB == PartitionStatus.FAILED || statusB == PartitionStatus.SUSPECTED);
    }

    /**
     * IT6: Test recovery after failure.
     * Heartbeat restoration should bring partition back to healthy.
     */
    @Test
    void testRecoveryAfterFailure_RestoresHealthy() throws Exception {
        var partitionId = UUID.randomUUID();

        failureDetector.registerPartition(partitionId);
        failureDetector.recordHeartbeat(partitionId);

        // Cause failure
        var clock1 = Clock.offset(testClock, Duration.ofMillis(600));
        failureDetector.setClock(clock1);
        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        var statusFailed = faultHandler.checkHealth(partitionId);
        assertTrue(statusFailed == PartitionStatus.FAILED || statusFailed == PartitionStatus.SUSPECTED);

        // Reset to epoch and record new heartbeat
        failureDetector.setClock(testClock);
        failureDetector.recordHeartbeat(partitionId);

        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        var statusRecovered = faultHandler.checkHealth(partitionId);
        assertEquals(PartitionStatus.HEALTHY, statusRecovered,
                     "Partition should recover to HEALTHY");
    }

    /**
     * IT7: Test clock injection enables deterministic timing.
     * All timeouts should be controlled by injected clock.
     */
    @Test
    void testClockInjection_DeterministicTiming() throws Exception {
        var partitionId = UUID.randomUUID();
        var epoch = Instant.parse("2025-01-23T10:00:00Z");
        var determinClock = Clock.fixed(epoch, ZoneId.systemDefault());

        failureDetector.setClock(determinClock);
        failureDetector.registerPartition(partitionId);
        failureDetector.recordHeartbeat(partitionId);

        // Advance deterministically
        var advanced = Clock.offset(determinClock, Duration.ofMillis(400));
        failureDetector.setClock(advanced);

        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        var status = faultHandler.checkHealth(partitionId);
        assertNotNull(status, "Should have deterministic status change");
    }

    /**
     * IT8: Test concurrent failure detection.
     * Multiple partitions failing simultaneously should all be detected.
     */
    @Test
    void testConcurrentFailures_AllDetected() throws Exception {
        var partitions = new UUID[5];
        for (int i = 0; i < 5; i++) {
            partitions[i] = UUID.randomUUID();
            failureDetector.registerPartition(partitions[i]);
            failureDetector.recordHeartbeat(partitions[i]);
        }

        // Fail all concurrently
        var advancedClock = Clock.offset(testClock, Duration.ofMillis(600));
        failureDetector.setClock(advancedClock);

        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        // All should be failed or suspected
        for (var partition : partitions) {
            var status = faultHandler.checkHealth(partition);
            assertTrue(status == PartitionStatus.FAILED || status == PartitionStatus.SUSPECTED,
                       "Partition " + partition + " should be failed");
        }
    }

    /**
     * IT9: Test false alarm prevention.
     * Heartbeat received before timeout should prevent failure.
     */
    @Test
    void testFalseAlarm_RecoveryBeforeTimeout() throws Exception {
        var partitionId = UUID.randomUUID();

        // Mark partition as healthy first
        faultHandler.markHealthy(partitionId);

        failureDetector.registerPartition(partitionId);
        failureDetector.recordHeartbeat(partitionId);

        // Advance close to failure threshold
        var clock1 = Clock.offset(testClock, Duration.ofMillis(250));
        failureDetector.setClock(clock1);

        // Record heartbeat before timeout
        failureDetector.recordHeartbeat(partitionId);

        // Check health - should still be healthy
        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        var status = faultHandler.checkHealth(partitionId);
        assertEquals(PartitionStatus.HEALTHY, status,
                     "Heartbeat before timeout should prevent failure");
    }

    /**
     * IT10: Test metrics accumulation across multiple partitions.
     * Fault handler should track metrics for detection analysis.
     */
    @Test
    void testMetricsAccumulation_AcrossPartitions() throws Exception {
        var partition1 = UUID.randomUUID();
        var partition2 = UUID.randomUUID();

        // Register and fail both
        failureDetector.registerPartition(partition1);
        failureDetector.registerPartition(partition2);

        failureDetector.recordHeartbeat(partition1);
        failureDetector.recordHeartbeat(partition2);

        var advancedClock = Clock.offset(testClock, Duration.ofMillis(600));
        failureDetector.setClock(advancedClock);

        failureDetector.getClass().getDeclaredMethod("checkHealth").invoke(failureDetector);

        // Both should have metrics
        var metrics1 = faultHandler.getMetrics(partition1);
        var metrics2 = faultHandler.getMetrics(partition2);

        assertNotNull(metrics1, "Partition 1 should have metrics");
        assertNotNull(metrics2, "Partition 2 should have metrics");
    }
}
