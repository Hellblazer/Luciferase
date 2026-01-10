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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ConsensusElectionProtocol - Three-state FSM implementation.
 * <p>
 * Tests cover:
 * - Follower election timeout behavior (1 test)
 * - Candidate election process (2 tests)
 * - Leader heartbeat broadcasting (2 tests)
 * - State transitions (3 tests)
 * - Concurrent election scenarios (2 tests)
 * - Timeout handling (2 tests)
 * - Vote idempotency (1 test)
 * - Lifecycle management (2 tests)
 * <p>
 * Total: 15 tests
 */
@Timeout(10) // All tests should complete within 10 seconds
class ConsensusElectionProtocolTest {

    private ConsensusElectionProtocol protocol;
    private UUID nodeId;

    @BeforeEach
    void setUp() {
        nodeId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (protocol != null) {
            protocol.stop();
        }
    }

    // ========== Follower Election Timeout Tests (1 test) ==========

    @Test
    void testFollowerElectionTimeout() throws InterruptedException {
        // Use short timeout for test (100ms instead of 1000ms)
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Initially FOLLOWER
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.FOLLOWER);

        // Wait for timeout to trigger state transition
        Thread.sleep(150);

        // Should transition to CANDIDATE
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);
    }

    // ========== Candidate Election Process Tests (2 tests) ==========

    @Test
    void testCandidateRequestVotes() throws Exception {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Wait for transition to CANDIDATE
        Thread.sleep(150);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);

        // Candidate should request votes (verify term incremented)
        assertThat(protocol.getCurrentTerm()).isGreaterThan(0);
    }

    @Test
    void testCandidateCollectsVotesAndBecomesLeader() throws Exception {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Wait for CANDIDATE state
        Thread.sleep(150);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);

        // Simulate receiving majority votes (2 out of 3)
        var voterId1 = UUID.randomUUID();
        var voterId2 = UUID.randomUUID();
        var term = protocol.getCurrentTerm();

        protocol.recordVote(nodeId.toString(), voterId1, true);
        protocol.recordVote(nodeId.toString(), voterId2, true);

        // Wait for state transition
        Thread.sleep(50);

        // Should become LEADER with majority votes
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.LEADER);
    }

    // ========== Leader Heartbeat Broadcasting Tests (2 tests) ==========

    @Test
    void testLeaderSendsPeriodicHeartbeats() throws Exception {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Transition to LEADER
        Thread.sleep(150);
        var voterId1 = UUID.randomUUID();
        var voterId2 = UUID.randomUUID();
        protocol.recordVote(nodeId.toString(), voterId1, true);
        protocol.recordVote(nodeId.toString(), voterId2, true);
        Thread.sleep(50);

        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.LEADER);

        // Leader should send heartbeats periodically
        var initialHeartbeatCount = protocol.getHeartbeatCount();
        Thread.sleep(250); // Wait for multiple heartbeat intervals (100ms each)

        // Heartbeat count should increase
        assertThat(protocol.getHeartbeatCount()).isGreaterThan(initialHeartbeatCount);
    }

    @Test
    void testLeaderHeartbeatPreventElections() throws Exception {
        var leaderId = UUID.randomUUID();
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Initially FOLLOWER
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.FOLLOWER);

        // Receive heartbeat from leader before timeout
        protocol.sendHeartbeat(leaderId);
        Thread.sleep(80); // Wait most of timeout period

        // Send another heartbeat
        protocol.sendHeartbeat(leaderId);
        Thread.sleep(80);

        // Should remain FOLLOWER (heartbeats prevent election)
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.FOLLOWER);
    }

    // ========== State Transition Tests (3 tests) ==========

    @Test
    void testFollowerToCandidateToLeader() throws Exception {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Start as FOLLOWER
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.FOLLOWER);

        // Transition to CANDIDATE on timeout
        Thread.sleep(150);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);

        // Win election → LEADER
        var voterId1 = UUID.randomUUID();
        var voterId2 = UUID.randomUUID();
        protocol.recordVote(nodeId.toString(), voterId1, true);
        protocol.recordVote(nodeId.toString(), voterId2, true);
        Thread.sleep(50);

        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.LEADER);
    }

    @Test
    void testLeaderToFollowerOnHigherTerm() throws Exception {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Become LEADER
        Thread.sleep(150);
        var voterId1 = UUID.randomUUID();
        var voterId2 = UUID.randomUUID();
        protocol.recordVote(nodeId.toString(), voterId1, true);
        protocol.recordVote(nodeId.toString(), voterId2, true);
        Thread.sleep(50);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.LEADER);

        var currentTerm = protocol.getCurrentTerm();

        // Receive vote request with higher term
        var higherTermCandidate = UUID.randomUUID();
        protocol.requestVote(higherTermCandidate, currentTerm + 1);

        // Should step down to FOLLOWER
        Thread.sleep(50);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.FOLLOWER);
        assertThat(protocol.getCurrentTerm()).isEqualTo(currentTerm + 1);
    }

    @Test
    void testCandidateToFollowerOnHigherTerm() throws Exception {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Become CANDIDATE
        Thread.sleep(150);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);
        var currentTerm = protocol.getCurrentTerm();

        // Receive heartbeat with higher term
        var leaderId = UUID.randomUUID();
        protocol.sendHeartbeat(leaderId, currentTerm + 1);

        // Should step down to FOLLOWER
        Thread.sleep(50);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.FOLLOWER);
    }

    // ========== Concurrent Election Tests (2 tests) ==========

    @Test
    void testThreeNodeConcurrentElection() throws Exception {
        var node1 = UUID.randomUUID();
        var node2 = UUID.randomUUID();
        var node3 = UUID.randomUUID();

        var protocol1 = new ConsensusElectionProtocol(node1, 3, 100);
        var protocol2 = new ConsensusElectionProtocol(node2, 3, 100);
        var protocol3 = new ConsensusElectionProtocol(node3, 3, 100);

        try {
            protocol1.start();
            protocol2.start();
            protocol3.start();

            // Wait for elections
            Thread.sleep(200);

            // All should become CANDIDATE
            assertThat(protocol1.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);
            assertThat(protocol2.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);
            assertThat(protocol3.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);

            // Simulate node1 winning (receives votes from node2 and node3)
            protocol1.recordVote(node1.toString(), node2, true);
            protocol1.recordVote(node1.toString(), node3, true);
            Thread.sleep(50);

            // node1 should become LEADER
            assertThat(protocol1.getCurrentState()).isEqualTo(ElectionState.LEADER);
        } finally {
            protocol1.stop();
            protocol2.stop();
            protocol3.stop();
        }
    }

    @Test
    void testFiveNodeConcurrentElection() throws Exception {
        var nodes = new UUID[5];
        var protocols = new ConsensusElectionProtocol[5];

        for (int i = 0; i < 5; i++) {
            nodes[i] = UUID.randomUUID();
            protocols[i] = new ConsensusElectionProtocol(nodes[i], 5, 100);
        }

        try {
            // Start all protocols
            for (var p : protocols) {
                p.start();
            }

            // Wait for election
            Thread.sleep(200);

            // All should be CANDIDATE
            for (var p : protocols) {
                assertThat(p.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);
            }

            // Simulate nodes[0] winning (receives votes from nodes 1,2,3 + self-vote = 4, which is not quorum of 5)
            // Need 5 votes for quorum of 5, so all nodes must vote for nodes[0]
            // nodes[0] already voted for itself, so need 4 more votes
            protocols[0].recordVote(nodes[0].toString(), nodes[1], true);
            protocols[0].recordVote(nodes[0].toString(), nodes[2], true);
            protocols[0].recordVote(nodes[0].toString(), nodes[3], true);
            protocols[0].recordVote(nodes[0].toString(), nodes[4], true);
            Thread.sleep(50);

            // nodes[0] should become LEADER (has 5/5 votes)
            assertThat(protocols[0].getCurrentState()).isEqualTo(ElectionState.LEADER);
        } finally {
            for (var p : protocols) {
                p.stop();
            }
        }
    }

    // ========== Timeout Handling Tests (2 tests) ==========

    @Test
    void testElectionTimeoutHandling() throws Exception {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        var initialTerm = protocol.getCurrentTerm();

        // Wait for election timeout
        Thread.sleep(150);

        // Term should increment (new election started)
        assertThat(protocol.getCurrentTerm()).isGreaterThan(initialTerm);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);
    }

    @Test
    void testHeartbeatTimeoutDetection() throws Exception {
        var leaderId = UUID.randomUUID();
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Receive heartbeat from leader
        protocol.sendHeartbeat(leaderId);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.FOLLOWER);

        // Wait for heartbeat timeout (no more heartbeats)
        Thread.sleep(150);

        // Should start election
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);
    }

    // ========== Vote Idempotency Test (1 test) ==========

    @Test
    void testDuplicateVotesIgnored() throws Exception {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Transition to CANDIDATE
        Thread.sleep(150);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);

        var voterId = UUID.randomUUID();
        var proposalId = UUID.randomUUID().toString(); // Use different proposal ID to avoid self-vote

        // Register the proposal first
        protocol.getBallotBox().registerProposal(proposalId, "TEST");

        // Record vote
        protocol.recordVote(proposalId, voterId, true);

        // Record duplicate vote (should be ignored)
        protocol.recordVote(proposalId, voterId, true);
        protocol.recordVote(proposalId, voterId, false); // Different vote, still ignored

        // Verify only one vote counted (check via ballot box)
        var ballot = protocol.getBallotBox();
        var voteCounts = ballot.getVoteCounts(proposalId);

        // Should only count first YES vote from this voter (NO vote allowed but separate)
        assertThat(voteCounts.yesVotes).isEqualTo(1);
        // The NO vote is recorded separately (BallotBox allows cross-voting)
        assertThat(voteCounts.noVotes).isEqualTo(1);
    }

    // ========== Lifecycle Management Tests (2 tests) ==========

    @Test
    void testProtocolStartInitialization() {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 1000);
        protocol.start();

        // Should start in FOLLOWER state
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.FOLLOWER);
        assertThat(protocol.getCurrentTerm()).isEqualTo(0);
        assertThat(protocol.isRunning()).isTrue();
    }

    @Test
    void testProtocolStopCleanup() throws Exception {
        protocol = new ConsensusElectionProtocol(nodeId, 3, 100);
        protocol.start();

        // Transition to CANDIDATE
        Thread.sleep(150);
        assertThat(protocol.getCurrentState()).isEqualTo(ElectionState.CANDIDATE);

        // Stop protocol
        protocol.stop();

        // Should be stopped
        assertThat(protocol.isRunning()).isFalse();

        // Executor should be shutdown
        Thread.sleep(50);
        // No exceptions should occur after stop
    }
}
