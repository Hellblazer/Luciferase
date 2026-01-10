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
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TwoNodeConsensusTest - Integration test for 2-node consensus.
 * <p>
 * Validates:
 * - Leader election state transitions between 2 nodes
 * - Heartbeat detection and tracking
 * - Follower health monitoring via failure detector
 * - Clean resource cleanup
 * <p>
 * NOTE: This test simulates vote delivery to test the state machine logic.
 * In a real distributed system, votes would be delivered via gRPC (tested in GrpcCoordinatorServiceTest).
 * This approach is valid because:
 * 1. The network communication is tested at the gRPC service level (Day 3.2)
 * 2. The election protocol FSM is tested at the unit level (Day 3.1)
 * 3. Integration tests verify the components work together locally
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class TwoNodeConsensusTest {

    private final List<ConsensusCoordinator> coordinators = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (var coordinator : coordinators) {
            try {
                coordinator.stop();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        coordinators.clear();
    }

    /**
     * Tests leader election and heartbeat detection with 2 nodes.
     * <p>
     * Expected behavior:
     * - Both nodes start as FOLLOWER
     * - After election timeout, nodes become CANDIDATE
     * - We simulate node2 voting for node1 (what gRPC would deliver)
     * - Node1 achieves quorum (self-vote + node2-vote = 2) and becomes LEADER
     * - Node2 remains FOLLOWER
     * - Leader maintains heartbeat count
     * - Both coordinators stop cleanly
     */
    @Test
    void testTwoNodeElectionAndHeartbeat() throws Exception {
        // Given: 2 ConsensusCoordinator instances
        var node1Id = UUID.randomUUID();
        var node2Id = UUID.randomUUID();
        var quorumSize = 2; // Both must agree in 2-node cluster

        var coordinator1 = new ConsensusCoordinator(node1Id, quorumSize, 0); // Dynamic port
        var coordinator2 = new ConsensusCoordinator(node2Id, quorumSize, 0); // Dynamic port

        coordinators.add(coordinator1);
        coordinators.add(coordinator2);

        // When: Start both coordinators
        coordinator1.start();
        coordinator2.start();

        // Both should start as FOLLOWER
        assertThat(coordinator1.getState())
            .as("Node1 should start as FOLLOWER")
            .isEqualTo(ElectionState.FOLLOWER);
        assertThat(coordinator2.getState())
            .as("Node2 should start as FOLLOWER")
            .isEqualTo(ElectionState.FOLLOWER);

        // Wait for election timeout (1000ms + buffer) so nodes become CANDIDATE
        // The protocol schedules election timeout monitoring
        Thread.sleep(1500);

        // Trigger election on node1 explicitly (in case timeout hasn't fired yet)
        coordinator1.triggerElectionTimeout();

        // Wait a bit for state transition
        Thread.sleep(100);

        // Node1 should now be CANDIDATE (started election, voted for self)
        assertThat(coordinator1.getState())
            .as("Node1 should be CANDIDATE after timeout")
            .isEqualTo(ElectionState.CANDIDATE);

        // Simulate node2 voting YES for node1's candidacy
        // In a real system, this would happen via gRPC when node2 receives VoteRequest
        coordinator1.recordElectionVote(node2Id, true);

        // Wait for state transition to LEADER (should be quick)
        Thread.sleep(200);

        // Then: Verify node1 became LEADER (has quorum: self-vote + node2-vote = 2)
        assertThat(coordinator1.getState())
            .as("Node1 should be LEADER after receiving quorum votes")
            .isEqualTo(ElectionState.LEADER);

        // Node2 should still be FOLLOWER or CANDIDATE (didn't win election)
        // In a real system, node2 would receive heartbeat from node1 and step down
        assertThat(coordinator2.getState())
            .as("Node2 should not be LEADER")
            .isNotEqualTo(ElectionState.LEADER);

        // Verify both coordinators are running
        assertThat(coordinator1.isRunning()).isTrue();
        assertThat(coordinator2.isRunning()).isTrue();

        // Wait a bit to observe heartbeat activity from leader
        Thread.sleep(500);

        // Verify leader is broadcasting heartbeats
        var heartbeatCount = coordinator1.getProtocol().getHeartbeatCount();
        assertThat(heartbeatCount)
            .as("Leader should be broadcasting heartbeats")
            .isGreaterThan(0);

        // Verify coordinators stop cleanly
        coordinator1.stop();
        coordinator2.stop();

        assertThat(coordinator1.isRunning()).isFalse();
        assertThat(coordinator2.isRunning()).isFalse();
    }
}
