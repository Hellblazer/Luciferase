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
 * ThreeNodeConsensusTest - Integration test for 3-node consensus with majority voting.
 * <p>
 * Validates:
 * - Leader election with majority voting (2/3 quorum)
 * - Leader broadcasts heartbeats to followers
 * - Leader failure detection
 * - New leader election from remaining nodes
 * - No split-brain scenarios
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class ThreeNodeConsensusTest {

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
     * Tests 3-node majority voting and leader failover.
     * <p>
     * Expected behavior:
     * - Exactly 1 LEADER and 2 FOLLOWERs
     * - Quorum size is 2 (majority of 3)
     * - All nodes vote in election
     * - Follower detects leader failure after timeout
     * - Remaining nodes elect new leader
     * - No split-brain scenarios
     */
    @Test
    void testThreeNodeMajorityVoting() throws Exception {
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
            var leaderCount = countLeaders(coordinator1, coordinator2, coordinator3);
            var followerCount = countFollowers(coordinator1, coordinator2, coordinator3);

            if (leaderCount == 1 && followerCount == 2) {
                initialLeader = findLeader(coordinator1, coordinator2, coordinator3);
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
            .as("Should have exactly one leader")
            .isNotNull();

        var leaderCount = countLeaders(coordinator1, coordinator2, coordinator3);
        var followerCount = countFollowers(coordinator1, coordinator2, coordinator3);

        assertThat(leaderCount)
            .as("Exactly 1 LEADER")
            .isEqualTo(1);

        assertThat(followerCount)
            .as("Exactly 2 FOLLOWERs")
            .isEqualTo(2);

        // Simulate leader failure
        initialLeader.stop();
        coordinators.remove(initialLeader);

        // Wait for new leader election
        var reelectionLatch = new CountDownLatch(1);
        startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            var remainingCoordinators = coordinators.toArray(new ConsensusCoordinator[0]);
            var newLeaderCount = countLeaders(remainingCoordinators);
            var newFollowerCount = countFollowers(remainingCoordinators);

            if (newLeaderCount == 1 && newFollowerCount == 1) {
                reelectionLatch.countDown();
                break;
            }

            Thread.sleep(100);
        }

        // Verify new leader elected
        assertThat(reelectionLatch.await(1, TimeUnit.SECONDS))
            .as("New leader should be elected after failure")
            .isTrue();

        var remainingCoordinators = coordinators.toArray(new ConsensusCoordinator[0]);
        var newLeaderCount = countLeaders(remainingCoordinators);
        var newFollowerCount = countFollowers(remainingCoordinators);

        assertThat(newLeaderCount)
            .as("Exactly 1 new LEADER after failover")
            .isEqualTo(1);

        assertThat(newFollowerCount)
            .as("Exactly 1 FOLLOWER after failover")
            .isEqualTo(1);

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

    private int countFollowers(ConsensusCoordinator... coordinators) {
        var count = 0;
        for (var c : coordinators) {
            if (c.getState() == ElectionState.FOLLOWER) {
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
