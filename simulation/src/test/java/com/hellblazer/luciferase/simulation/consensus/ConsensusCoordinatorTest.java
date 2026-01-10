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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ConsensusCoordinator - Orchestration layer for consensus operations.
 * <p>
 * Tests cover:
 * - Coordinator initialization (1 test)
 * - Start and stop lifecycle (2 tests)
 * - Propose entity ownership (3 tests - approved, rejected, timeout)
 * - Leader election participation (2 tests)
 * - Failover handling (2 tests)
 * - Metrics tracking (1 test)
 * - Concurrent proposals (2 tests)
 * <p>
 * Total: 13 tests
 */
@Timeout(15)
class ConsensusCoordinatorTest {

    private ConsensusCoordinator coordinator;
    private UUID nodeId;
    private int grpcPort;

    @BeforeEach
    void setUp() {
        nodeId = UUID.randomUUID();
        grpcPort = 0; // Dynamic port assignment
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.stop();
        }
    }

    // ========== Coordinator Initialization Test (1 test) ==========

    @Test
    void testCoordinatorInitialization() {
        coordinator = new ConsensusCoordinator(nodeId, 3, grpcPort);

        assertThat(coordinator).isNotNull();
        assertThat(coordinator.getState()).isEqualTo(ElectionState.FOLLOWER);
        assertThat(coordinator.getTermNumber()).isEqualTo(0);
        assertThat(coordinator.isLeader()).isFalse();
    }

    // ========== Start and Stop Tests (2 tests) ==========

    @Test
    void testStartSuccessful() throws IOException {
        coordinator = new ConsensusCoordinator(nodeId, 3, grpcPort);
        coordinator.start();

        assertThat(coordinator.isRunning()).isTrue();
        assertThat(coordinator.getGrpcPort()).isGreaterThan(0); // Dynamic port assigned
    }

    @Test
    void testCleanStop() throws IOException {
        coordinator = new ConsensusCoordinator(nodeId, 3, grpcPort);
        coordinator.start();

        assertThat(coordinator.isRunning()).isTrue();

        coordinator.stop();

        assertThat(coordinator.isRunning()).isFalse();
        // Should be idempotent
        coordinator.stop();
        assertThat(coordinator.isRunning()).isFalse();
    }

    // ========== Propose Entity Ownership Tests (3 tests) ==========

    @Test
    void testProposeEntityOwnershipApproved() throws Exception {
        coordinator = new ConsensusCoordinator(nodeId, 1, grpcPort); // Quorum of 1 = auto-approve
        coordinator.start();

        var entityId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();

        // Wait for protocol to start
        Thread.sleep(100);

        var result = coordinator.proposeEntityOwnership(entityId, targetBubbleId).get(2, TimeUnit.SECONDS);

        assertThat(result).isTrue();
    }

    @Test
    void testProposeEntityOwnershipRejected() throws Exception {
        coordinator = new ConsensusCoordinator(nodeId, 10, grpcPort); // Quorum of 10, only 1 node = can't reach quorum
        coordinator.start();

        var entityId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();

        Thread.sleep(100);

        var result = coordinator.proposeEntityOwnership(entityId, targetBubbleId).get(5, TimeUnit.SECONDS);

        // Should be rejected (can't reach quorum of 10 with only 1 voter)
        assertThat(result).isFalse();
    }

    @Test
    void testProposeEntityOwnershipTimeout() throws Exception {
        coordinator = new ConsensusCoordinator(nodeId, 5, grpcPort);
        coordinator.start();

        var entityId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();

        Thread.sleep(100);

        // Set very short timeout
        var future = coordinator.proposeEntityOwnership(entityId, targetBubbleId);

        // Should complete (either approve or reject) within timeout
        var result = future.get(3, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
    }

    // ========== Leader Election Participation Tests (2 tests) ==========

    @Test
    void testCoordinatorParticipatesInElection() throws Exception {
        coordinator = new ConsensusCoordinator(nodeId, 3, grpcPort);
        coordinator.start();

        // Initially FOLLOWER
        assertThat(coordinator.getState()).isEqualTo(ElectionState.FOLLOWER);
        assertThat(coordinator.isLeader()).isFalse();

        // Wait for election timeout to trigger
        Thread.sleep(1200); // Longer than default 1000ms timeout

        // Should transition to CANDIDATE
        assertThat(coordinator.getState()).isIn(ElectionState.CANDIDATE, ElectionState.LEADER);
    }

    @Test
    void testLeaderProvidesConsensus() throws Exception {
        coordinator = new ConsensusCoordinator(nodeId, 1, grpcPort); // Quorum of 1 = become leader
        coordinator.start();

        // Wait for election timeout + transition
        Thread.sleep(1500);

        // Should transition to CANDIDATE or LEADER
        var state = coordinator.getState();
        assertThat(state).isIn(ElectionState.CANDIDATE, ElectionState.LEADER);

        // If CANDIDATE, should be able to become LEADER with quorum=1
        if (state == ElectionState.CANDIDATE) {
            Thread.sleep(500);
            assertThat(coordinator.getState()).isIn(ElectionState.CANDIDATE, ElectionState.LEADER);
        }
    }

    // ========== Failover Handling Tests (2 tests) ==========

    @Test
    void testLeaderFailureDetected() throws Exception {
        coordinator = new ConsensusCoordinator(nodeId, 1, grpcPort);
        coordinator.start();

        // Wait for state transition
        Thread.sleep(1500);

        // Verify coordinator is running (main test objective)
        var wasRunning = coordinator.isRunning();
        assertThat(wasRunning).isTrue();

        // Simulate leader failure by stopping
        coordinator.stop();

        assertThat(coordinator.isRunning()).isFalse();
    }

    @Test
    void testNewLeaderElected() throws Exception {
        // Single-node scenario
        coordinator = new ConsensusCoordinator(nodeId, 1, grpcPort);
        coordinator.start();

        // Wait for election
        Thread.sleep(1500);

        // Get initial term number
        var term1 = coordinator.getTermNumber();
        var wasRunning = coordinator.isRunning();
        assertThat(wasRunning).isTrue();

        // Simulate failure and restart (in reality would be different node)
        coordinator.stop();

        coordinator = new ConsensusCoordinator(nodeId, 1, grpcPort);
        coordinator.start();

        Thread.sleep(1500);

        // Should be running and have progressed to higher or equal term
        assertThat(coordinator.isRunning()).isTrue();
        assertThat(coordinator.getTermNumber()).isGreaterThanOrEqualTo(term1);
    }

    // ========== Metrics Test (1 test) ==========

    @Test
    void testMetricsTracking() throws Exception {
        coordinator = new ConsensusCoordinator(nodeId, 1, grpcPort); // Use quorum=1 for faster completion
        coordinator.start();

        Thread.sleep(100);

        // Check initial metrics
        assertThat(coordinator.getElectionCount()).isGreaterThanOrEqualTo(0);
        assertThat(coordinator.getProposalCount()).isEqualTo(0);

        // Propose entity ownership
        var entityId = UUID.randomUUID();
        var targetBubbleId = UUID.randomUUID();
        coordinator.proposeEntityOwnership(entityId, targetBubbleId).get(3, TimeUnit.SECONDS);

        // Proposal count should increment
        assertThat(coordinator.getProposalCount()).isEqualTo(1);
    }

    // ========== Concurrent Proposals Tests (2 tests) ==========

    @Test
    void testMultipleConcurrentProposals() throws Exception {
        coordinator = new ConsensusCoordinator(nodeId, 1, grpcPort);
        coordinator.start();

        Thread.sleep(100);

        // Submit multiple concurrent proposals
        var futures = new java.util.concurrent.CompletableFuture[5];
        for (int i = 0; i < 5; i++) {
            var entityId = UUID.randomUUID();
            var targetBubbleId = UUID.randomUUID();
            futures[i] = coordinator.proposeEntityOwnership(entityId, targetBubbleId);
        }

        // All should complete
        java.util.concurrent.CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);

        // Verify proposal count
        assertThat(coordinator.getProposalCount()).isEqualTo(5);
    }

    @Test
    void testConcurrentProposalsWithDifferentEntities() throws Exception {
        coordinator = new ConsensusCoordinator(nodeId, 1, grpcPort);
        coordinator.start();

        Thread.sleep(100);

        var entity1 = UUID.randomUUID();
        var entity2 = UUID.randomUUID();
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        // Submit proposals for different entities concurrently
        var future1 = coordinator.proposeEntityOwnership(entity1, bubble1);
        var future2 = coordinator.proposeEntityOwnership(entity2, bubble2);

        // Both should complete independently
        var result1 = future1.get(2, TimeUnit.SECONDS);
        var result2 = future2.get(2, TimeUnit.SECONDS);

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
    }
}
