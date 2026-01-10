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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FailureRecoveryTest - Tests leader failure detection and recovery.
 * <p>
 * Validates:
 * - Initial leader election works
 * - Follower detects leader failure (no heartbeat after timeout)
 * - Automatic reelection completes
 * - Failed node rejoins cleanly as follower
 * - No split-brain (only 1 leader at any time)
 * - Consistent state across all nodes
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class FailureRecoveryTest {

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
     * Tests leader failure and recovery scenario.
     * <p>
     * Scenario:
     * 1. Start 3 nodes, verify initial leader election
     * 2. Simulate leader failure by stopping leader
     * 3. Wait for follower election timeout + detection delay
     * 4. Verify node2 or node3 detects leader failure
     * 5. Verify new leader elected from remaining nodes
     * 6. Restart original leader
     * 7. Verify original leader joins as follower (recognizes higher term)
     * 8. All 3 nodes have valid leader
     */
    @Test
    void testLeaderFailureAndRecovery() throws Exception {
        // Given: 3 ConsensusCoordinator instances
        var node1Id = UUID.randomUUID();
        var node2Id = UUID.randomUUID();
        var node3Id = UUID.randomUUID();
        var quorumSize = 2; // Majority of 3

        var coordinator1 = new ConsensusCoordinator(node1Id, quorumSize, 0);
        var coordinator2 = new ConsensusCoordinator(node2Id, quorumSize, 0);
        var coordinator3 = new ConsensusCoordinator(node3Id, quorumSize, 0);

        coordinators.add(coordinator1);
        coordinators.add(coordinator2);
        coordinators.add(coordinator3);

        // Wire up peer communication (in-process for testing)
        coordinator1.setPeerCommunicator(new TestPeerCommunicator(node1Id, coordinators));
        coordinator2.setPeerCommunicator(new TestPeerCommunicator(node2Id, coordinators));
        coordinator3.setPeerCommunicator(new TestPeerCommunicator(node3Id, coordinators));

        // When: Start all 3 coordinators
        coordinator1.start();
        coordinator2.start();
        coordinator3.start();

        // Wait for initial election to complete
        var electionLatch = new CountDownLatch(1);
        var maxWaitMs = 3000L;

        var startTime = System.currentTimeMillis();
        ConsensusCoordinator initialLeader = null;
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            initialLeader = findLeader(coordinator1, coordinator2, coordinator3);
            if (initialLeader != null) {
                electionLatch.countDown();
                break;
            }
            Thread.sleep(100);
        }

        // Then: Verify initial election
        assertThat(electionLatch.await(1, TimeUnit.SECONDS))
            .as("Initial leader election should complete")
            .isTrue();

        assertThat(initialLeader)
            .as("Should have exactly one initial leader")
            .isNotNull();

        var initialLeaderCount = countLeaders(coordinator1, coordinator2, coordinator3);
        assertThat(initialLeaderCount)
            .as("Exactly 1 initial LEADER")
            .isEqualTo(1);

        // Record initial leader ID
        var initialLeaderId = initialLeader.getNodeId();

        // Simulate leader failure
        initialLeader.stop();
        coordinators.remove(initialLeader);

        // Wait for failure detection + reelection
        // Election timeout is 1000ms, remaining nodes need to:
        // 1. Detect missing heartbeats (1000ms timeout)
        // 2. Start new election
        // 3. Collect votes and transition to leader
        // Allow extra time for potential split votes requiring re-election
        var reelectionMaxWaitMs = 5000L;

        // Verify new leader elected
        var reelectionLatch = new CountDownLatch(1);
        startTime = System.currentTimeMillis();
        ConsensusCoordinator newLeader = null;
        while (System.currentTimeMillis() - startTime < reelectionMaxWaitMs) {
            var remainingCoordinators = coordinators.toArray(new ConsensusCoordinator[0]);
            newLeader = findLeader(remainingCoordinators);
            if (newLeader != null) {
                reelectionLatch.countDown();
                break;
            }
            Thread.sleep(100);
        }

        assertThat(reelectionLatch.await(1, TimeUnit.SECONDS))
            .as("New leader should be elected after failure")
            .isTrue();

        assertThat(newLeader)
            .as("Should have exactly one new leader")
            .isNotNull();

        var newLeaderCount = countLeaders(coordinators.toArray(new ConsensusCoordinator[0]));
        assertThat(newLeaderCount)
            .as("Exactly 1 new LEADER after failover")
            .isEqualTo(1);

        // Verify new leader is different from failed leader
        assertThat(newLeader.getNodeId())
            .as("New leader should be different node")
            .isNotEqualTo(initialLeaderId);

        // Restart original leader
        var recoveredLeader = new ConsensusCoordinator(initialLeaderId, quorumSize, 0);
        coordinators.add(recoveredLeader);
        // Wire up peer communication for recovered leader
        recoveredLeader.setPeerCommunicator(new TestPeerCommunicator(initialLeaderId, coordinators));
        recoveredLeader.start();

        // Wait for recovered node to join as follower
        Thread.sleep(1500);

        // Verify recovered node joined as follower (recognizes higher term)
        var recoveredState = recoveredLeader.getState();
        assertThat(recoveredState)
            .as("Recovered node should join as FOLLOWER or CANDIDATE, not LEADER")
            .isIn(ElectionState.FOLLOWER, ElectionState.CANDIDATE);

        // Verify still only 1 leader (no split-brain)
        var finalLeaderCount = countLeaders(coordinators.toArray(new ConsensusCoordinator[0]));
        assertThat(finalLeaderCount)
            .as("Still exactly 1 LEADER after recovery (no split-brain)")
            .isEqualTo(1);

        // Verify all 3 nodes are running
        assertThat(coordinators)
            .as("All 3 nodes should be running")
            .hasSize(3);

        for (var coordinator : coordinators) {
            assertThat(coordinator.isRunning())
                .as("Coordinator %s should be running", coordinator.getNodeId())
                .isTrue();
        }

        // Cleanup
        for (var coordinator : coordinators) {
            coordinator.stop();
        }
    }

    private int countLeaders(ConsensusCoordinator... coordinators) {
        var count = 0;
        for (var c : coordinators) {
            if (c.getState() == ElectionState.LEADER) {
                count++;
            }
        }
        return count;
    }

    private ConsensusCoordinator findLeader(ConsensusCoordinator... coordinators) {
        for (var c : coordinators) {
            if (c.getState() == ElectionState.LEADER) {
                return c;
            }
        }
        return null;
    }
}
