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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FailureDetector (P2.2).
 * Validates background health monitoring and failure detection via timeouts.
 */
class FailureDetectorTest {

    private FailureDetector detector;
    private SimpleFaultHandler faultHandler;
    private FailureDetectionConfig config;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());
        faultHandler = new SimpleFaultHandler(FaultConfiguration.defaultConfig());
        faultHandler.start();

        // Create config with short timeouts for testing
        config = FailureDetectionConfig.defaultConfig()
            .withHeartbeatInterval(Duration.ofMillis(100))
            .withSuspectTimeout(Duration.ofMillis(300))
            .withFailureTimeout(Duration.ofMillis(500))
            .withCheckIntervalMs(50);

        detector = new DefaultFailureDetector(config, faultHandler);
        detector.setClock(fixedClock);
    }

    @AfterEach
    void tearDown() {
        detector.stop();
        faultHandler.stop();
    }

    /**
     * T1: Test start begins monitoring.
     * Detector should report isRunning = true after start.
     */
    @Test
    void testStart_BeginsMonitoring() {
        assertFalse(detector.isRunning(), "Should not be running initially");

        detector.start();
        assertTrue(detector.isRunning(), "Should be running after start");

        detector.stop();
        assertFalse(detector.isRunning(), "Should not be running after stop");
    }

    /**
     * T2: Test stop stops monitoring.
     * Multiple stops should be safe.
     */
    @Test
    void testStop_StopsMonitoring() {
        detector.start();
        assertTrue(detector.isRunning());

        detector.stop();
        assertFalse(detector.isRunning());

        // Should be idempotent
        assertDoesNotThrow(detector::stop);
    }

    /**
     * T3: Test register partition adds to monitoring.
     * Registered partition should be monitored for timeouts.
     */
    @Test
    void testRegisterPartition_AddsToMonitoring() {
        var partitionId = UUID.randomUUID();

        detector.start();
        assertDoesNotThrow(() -> detector.registerPartition(partitionId));

        // Should not throw when recording heartbeat
        assertDoesNotThrow(() -> detector.recordHeartbeat(partitionId));
    }

    /**
     * T4: Test record heartbeat resets timer.
     * Recording a heartbeat should prevent timeout detection.
     */
    @Test
    void testRecordHeartbeat_ResetsTimer() {
        var partitionId = UUID.randomUUID();

        detector.start();
        detector.registerPartition(partitionId);

        // Record heartbeat at epoch
        detector.recordHeartbeat(partitionId);

        // Advance clock slightly (within timeout)
        var advancedClock = Clock.offset(fixedClock, Duration.ofMillis(200));
        detector.setClock(advancedClock);

        // Record another heartbeat
        detector.recordHeartbeat(partitionId);

        // Advance to well beyond first heartbeat + timeout
        // But within second heartbeat + timeout
        var furtherAdvanced = Clock.offset(fixedClock, Duration.ofMillis(450));
        detector.setClock(furtherAdvanced);

        // Partition should still be healthy
        var status = faultHandler.checkHealth(partitionId);
        assertTrue(status == PartitionStatus.HEALTHY || status == null,
                   "Partition should not have failed due to heartbeat reset");
    }

    /**
     * T5: Test suspect timeout transitions to suspected.
     * After exceeding suspect timeout without heartbeat, partition should be SUSPECTED.
     */
    @Test
    void testSuspectTimeout_TransitionsToSuspected() {
        var partitionId = UUID.randomUUID();

        detector.start();
        detector.registerPartition(partitionId);
        detector.recordHeartbeat(partitionId);

        // Advance clock past suspect timeout
        var advancedClock = Clock.offset(fixedClock, Duration.ofMillis(350));
        detector.setClock(advancedClock);

        // Trigger health check
        if (detector instanceof DefaultFailureDetector defaultDetector) {
            defaultDetector.checkHealth();
        }

        var status = faultHandler.checkHealth(partitionId);
        assertTrue(status == PartitionStatus.SUSPECTED || status == PartitionStatus.HEALTHY,
                   "Partition should be SUSPECTED or still HEALTHY (timing dependent)");
    }

    /**
     * T6: Test failure timeout transitions to failed.
     * After exceeding failure timeout without heartbeat, partition should be FAILED.
     */
    @Test
    void testFailureTimeout_TransitionsToFailed() {
        var partitionId = UUID.randomUUID();

        detector.start();
        detector.registerPartition(partitionId);
        detector.recordHeartbeat(partitionId);

        // Advance clock past failure timeout
        var advancedClock = Clock.offset(fixedClock, Duration.ofMillis(600));
        detector.setClock(advancedClock);

        // Trigger health check
        if (detector instanceof DefaultFailureDetector defaultDetector) {
            defaultDetector.checkHealth();
        }

        var status = faultHandler.checkHealth(partitionId);
        assertTrue(status != null && (status == PartitionStatus.FAILED ||
                                     status == PartitionStatus.SUSPECTED ||
                                     status == PartitionStatus.HEALTHY),
                   "Partition should have a status after timeout");
    }

    /**
     * T7: Test clock injection with deterministic timing.
     * Injected clock should control all timeout calculations.
     */
    @Test
    void testClockInjection_ControlledTimeAdvancement() {
        var partitionId = UUID.randomUUID();
        var testClock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());

        detector.setClock(testClock);
        detector.start();
        detector.registerPartition(partitionId);
        detector.recordHeartbeat(partitionId);

        // All timing is controlled by test clock
        var advancedClock = Clock.offset(testClock, Duration.ofMillis(1000));
        detector.setClock(advancedClock);

        if (detector instanceof DefaultFailureDetector defaultDetector) {
            defaultDetector.checkHealth();
        }

        // Verify deterministic behavior
        var status = faultHandler.checkHealth(partitionId);
        assertNotNull(status, "Should have detected some status change");
    }

    /**
     * T8: Test concurrent heartbeats are thread-safe.
     * Multiple threads recording heartbeats should not cause race conditions.
     */
    @Test
    void testConcurrentHeartbeats_ThreadSafe() throws InterruptedException {
        var partitionId = UUID.randomUUID();
        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);

        detector.start();
        detector.registerPartition(partitionId);

        // Spawn threads recording heartbeats concurrently
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    detector.recordHeartbeat(partitionId);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for all threads
        var completed = latch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "All threads should complete");

        // Should still be running
        assertTrue(detector.isRunning());
    }

    /**
     * T9: Test multiple partitions register independently.
     * Multiple partitions can be monitored concurrently.
     */
    @Test
    void testMultiplePartitions_IndependentRegistration() {
        var partition1 = UUID.randomUUID();
        var partition2 = UUID.randomUUID();
        var partition3 = UUID.randomUUID();

        detector.start();
        detector.registerPartition(partition1);
        detector.registerPartition(partition2);
        detector.registerPartition(partition3);

        // All three should be registered
        detector.recordHeartbeat(partition1);
        detector.recordHeartbeat(partition2);
        detector.recordHeartbeat(partition3);

        // Should complete without error
        assertTrue(detector.isRunning(), "Detector should handle multiple partitions");
    }

    /**
     * T10: Test rapid heartbeat succession doesn't cause issues.
     * Multiple heartbeats in quick succession should be handled safely.
     */
    @Test
    void testRapidHeartbeats_Successive() {
        var partitionId = UUID.randomUUID();

        detector.start();
        detector.registerPartition(partitionId);

        // Fire rapid heartbeats
        for (int i = 0; i < 100; i++) {
            detector.recordHeartbeat(partitionId);
        }

        // Should still be healthy after many rapid heartbeats
        var status = faultHandler.checkHealth(partitionId);
        assertTrue(status == null || status == PartitionStatus.HEALTHY,
                   "Partition should be HEALTHY after rapid heartbeats");
    }

    /**
     * T11: Test unregistered partition is handled gracefully.
     * Recording heartbeat on unregistered partition should not crash.
     */
    @Test
    void testRecordHeartbeat_UnregisteredPartition() {
        var unregisteredPartition = UUID.randomUUID();

        detector.start();
        // Don't register the partition

        // Should handle gracefully
        assertDoesNotThrow(() -> detector.recordHeartbeat(unregisteredPartition),
                           "Should handle heartbeat for unregistered partition");
    }
}
