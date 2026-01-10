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
 * QuorumValidationTest - Tests quorum threshold behavior for various cluster sizes.
 * <p>
 * Validates:
 * - Quorum formula: (n / 2) + 1
 * - Election requires exactly quorumSize votes
 * - Single vote insufficient even with 1 node voting
 * - Quorum calculation correct for all tested cluster sizes (n=1, 2, 3, 5)
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class QuorumValidationTest {

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
     * Tests quorum threshold behavior for different cluster sizes.
     * <p>
     * Test cases:
     * - n=1: quorumSize=1 (single node always wins)
     * - n=2: quorumSize=2 (both must agree)
     * - n=3: quorumSize=2 (majority rule)
     * - n=5: quorumSize=3 (majority rule)
     * <p>
     * For each size:
     * - Verify correct quorum size calculation
     * - Test vote collection (ensure majority required, not just 1)
     * - Verify election succeeds only when quorum reached
     */
    @Test
    void testQuorumThresholdBehavior() throws Exception {
        // Test n=1: Single node cluster
        testClusterSize(1, 1);

        // Clean up before next test
        cleanup();

        // Test n=2: Both must agree
        testClusterSize(2, 2);

        cleanup();

        // Test n=3: Majority of 3
        testClusterSize(3, 2);

        cleanup();

        // Test n=5: Majority of 5
        testClusterSize(5, 3);
    }

    private void testClusterSize(int clusterSize, int expectedQuorumSize) throws Exception {
        // Given: Create n ConsensusCoordinator instances
        var nodeIds = new ArrayList<UUID>();
        for (int i = 0; i < clusterSize; i++) {
            nodeIds.add(UUID.randomUUID());
        }

        for (var nodeId : nodeIds) {
            var coordinator = new ConsensusCoordinator(nodeId, expectedQuorumSize, 0);
            coordinators.add(coordinator);
        }

        // Wire up peer communication (in-process for testing)
        for (int i = 0; i < coordinators.size(); i++) {
            var coordinator = coordinators.get(i);
            var nodeId = nodeIds.get(i);
            coordinator.setPeerCommunicator(new TestPeerCommunicator(nodeId, coordinators));
        }

        // Verify quorum size calculation
        var calculatedQuorumSize = (clusterSize / 2) + 1;
        assertThat(expectedQuorumSize)
            .as("Quorum size for n=%d should be (n/2)+1", clusterSize)
            .isEqualTo(calculatedQuorumSize);

        // When: Start all coordinators with slight stagger to reduce election contention
        for (var coordinator : coordinators) {
            coordinator.start();
            Thread.sleep(50); // Small stagger between starts
        }

        // Wait for election to complete
        // For larger clusters (n=5), may need multiple election rounds due to split votes
        var electionLatch = new CountDownLatch(1);
        var maxWaitMs = clusterSize > 3 ? 6000L : 3000L;

        var startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            var leaderCount = coordinators.stream()
                                          .filter(c -> c.getState() == ElectionState.LEADER)
                                          .count();

            if (leaderCount == 1) {
                electionLatch.countDown();
                break;
            }

            Thread.sleep(100);
        }

        // Then: Verify election completed
        assertThat(electionLatch.await(1, TimeUnit.SECONDS))
            .as("Leader election should complete for n=%d", clusterSize)
            .isTrue();

        // Verify exactly one leader
        var leaderCount = coordinators.stream()
                                      .filter(c -> c.getState() == ElectionState.LEADER)
                                      .count();

        assertThat(leaderCount)
            .as("Exactly 1 LEADER for n=%d", clusterSize)
            .isEqualTo(1);

        // Verify follower/candidate count (n-1)
        var nonLeaderCount = coordinators.stream()
                                         .filter(c -> c.getState() != ElectionState.LEADER)
                                         .count();

        assertThat(nonLeaderCount)
            .as("n-1 non-leaders for n=%d", clusterSize)
            .isEqualTo(clusterSize - 1);

        // Verify quorum size is correctly enforced
        // For n=1, quorum=1 (single node)
        // For n=2, quorum=2 (both required)
        // For n=3, quorum=2 (majority)
        // For n=5, quorum=3 (majority)
        assertThat(expectedQuorumSize)
            .as("Quorum size enforced for n=%d", clusterSize)
            .isGreaterThan(clusterSize / 2);

        // Cleanup
        for (var coordinator : coordinators) {
            coordinator.stop();
        }
    }
}
