/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for FailureDetector heartbeat-based failure detection.
 *
 * @author hal.hildebrand
 */
class FailureDetectorTest {

    private static final Logger log = LoggerFactory.getLogger(FailureDetectorTest.class);

    private final List<FailureDetector> detectors = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (var detector : detectors) {
            detector.close();
        }
        detectors.clear();
    }

    /**
     * Verify new detector reports leaders as healthy initially after registration.
     */
    @Test
    void testInitializationHealthy() {
        var nodeId = UUID.randomUUID();
        var leaderId = UUID.randomUUID();

        var detector = createDetector(nodeId, 100, 1000, 3);

        // Register node and immediately record heartbeat
        detector.registerNode(leaderId);
        detector.recordHeartbeat(leaderId);

        // Should not be suspected initially
        assertThat(detector.isSuspected(leaderId)).isFalse();
        assertThat(detector.isDead(leaderId)).isFalse();

        var statuses = detector.getNodeStatuses();
        assertThat(statuses).containsKey(leaderId);
        assertThat(statuses.get(leaderId)).isEqualTo(FailureDetector.NodeStatus.ALIVE);
    }

    /**
     * Verify recordHeartbeat() updates internal timestamp for node.
     */
    @Test
    void testHeartbeatRecordingUpdatesTime() {
        var nodeId = UUID.randomUUID();
        var leaderId = UUID.randomUUID();

        var detector = createDetector(nodeId, 100, 1000, 3);

        // Record initial heartbeat
        detector.recordHeartbeat(leaderId);
        assertThat(detector.isSuspected(leaderId)).isFalse();

        // Record another heartbeat - should keep healthy
        detector.recordHeartbeat(leaderId);
        assertThat(detector.isSuspected(leaderId)).isFalse();

        var statuses = detector.getNodeStatuses();
        assertThat(statuses.get(leaderId)).isEqualTo(FailureDetector.NodeStatus.ALIVE);
    }

    /**
     * Verify leader becomes unhealthy after timeout expires without heartbeat.
     */
    @Test
    void testMissedHeartbeatDetectedAfterTimeout() throws InterruptedException {
        var nodeId = UUID.randomUUID();
        var leaderId = UUID.randomUUID();

        // Short timeout for fast test: 200ms timeout, 50ms check interval, 2 failures
        var detector = createDetector(nodeId, 50, 200, 2);

        // Record initial heartbeat
        detector.recordHeartbeat(leaderId);
        assertThat(detector.isSuspected(leaderId)).isFalse();

        // Wait for timeout to expire (200ms + margin)
        Thread.sleep(300);

        // Wait for monitoring task to detect failure (check interval 50ms + margin)
        Thread.sleep(100);

        // Should now be suspected (first failure)
        assertThat(detector.isSuspected(leaderId)).isTrue();

        // Wait for second failure check
        Thread.sleep(100);

        // Should now be DEAD after 2 consecutive failures
        assertThat(detector.isDead(leaderId)).isTrue();
    }

    /**
     * Verify frequent heartbeats keep leader healthy indefinitely.
     */
    @Test
    void testRepeatedHeartbeatsKeepHealthy() throws InterruptedException {
        var nodeId = UUID.randomUUID();
        var leaderId = UUID.randomUUID();

        var detector = createDetector(nodeId, 50, 200, 3);

        // Send heartbeats repeatedly for 500ms
        for (int i = 0; i < 10; i++) {
            detector.recordHeartbeat(leaderId);
            Thread.sleep(50);
        }

        // Should remain healthy throughout
        assertThat(detector.isSuspected(leaderId)).isFalse();
        assertThat(detector.isDead(leaderId)).isFalse();
    }

    /**
     * Verify detector can track multiple leaders independently.
     */
    @Test
    void testMultipleLeadersTracked() throws InterruptedException {
        var nodeId = UUID.randomUUID();
        var leader1 = UUID.randomUUID();
        var leader2 = UUID.randomUUID();

        var detector = createDetector(nodeId, 50, 200, 2);

        // Send initial heartbeats to both leaders
        detector.recordHeartbeat(leader1);
        detector.recordHeartbeat(leader2);

        assertThat(detector.isSuspected(leader1)).isFalse();
        assertThat(detector.isSuspected(leader2)).isFalse();

        // Keep leader1 healthy with repeated heartbeats, let leader2 timeout
        for (int i = 0; i < 5; i++) {
            Thread.sleep(80); // Wait 80ms
            detector.recordHeartbeat(leader1); // Keep leader1 alive
        }

        // Wait for leader2 to be detected as failed (monitoring task runs every 50ms)
        Thread.sleep(100);

        // leader1 should be healthy (got heartbeats every 80ms < 200ms timeout)
        // leader2 should be suspected (no heartbeat for ~400-500ms > 200ms timeout)
        assertThat(detector.isSuspected(leader1)).isFalse();
        assertThat(detector.isSuspected(leader2)).isTrue();
    }

    /**
     * Verify leader becomes healthy again after heartbeat resumes.
     */
    @Test
    void testFailureDetectionRecovers() throws InterruptedException {
        var nodeId = UUID.randomUUID();
        var leaderId = UUID.randomUUID();

        var detector = createDetector(nodeId, 50, 200, 2);

        // Initial heartbeat
        detector.recordHeartbeat(leaderId);
        assertThat(detector.isSuspected(leaderId)).isFalse();

        // Let it timeout
        Thread.sleep(300);
        Thread.sleep(100); // Wait for monitoring task

        assertThat(detector.isSuspected(leaderId)).isTrue();

        // Resume heartbeat
        detector.recordHeartbeat(leaderId);

        // Should immediately recover
        assertThat(detector.isSuspected(leaderId)).isFalse();
        assertThat(detector.isDead(leaderId)).isFalse();
    }

    /**
     * Verify null leader ID throws exception.
     */
    @Test
    void testNullLeaderIdThrowsException() {
        var nodeId = UUID.randomUUID();
        var detector = createDetector(nodeId, 100, 1000, 3);

        assertThatThrownBy(() -> detector.recordHeartbeat(null))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> detector.isSuspected(null))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> detector.isDead(null))
            .isInstanceOf(NullPointerException.class);
    }

    /**
     * Verify concurrent heartbeat recording is thread-safe.
     */
    @Test
    void testConcurrentHeartbeatRecording() throws InterruptedException {
        var nodeId = UUID.randomUUID();
        var leaderId = UUID.randomUUID();

        var detector = createDetector(nodeId, 50, 1000, 3);

        // Record heartbeats concurrently from multiple threads
        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        detector.recordHeartbeat(leaderId);
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Should remain healthy throughout concurrent updates
        assertThat(detector.isSuspected(leaderId)).isFalse();
        assertThat(detector.isDead(leaderId)).isFalse();
    }

    // Helper method to create detector and track for cleanup
    private FailureDetector createDetector(UUID nodeId, long intervalMs, long timeoutMs, int maxFailures) {
        var detector = new FailureDetector(nodeId, intervalMs, timeoutMs, maxFailures);
        detectors.add(detector);
        return detector;
    }
}
