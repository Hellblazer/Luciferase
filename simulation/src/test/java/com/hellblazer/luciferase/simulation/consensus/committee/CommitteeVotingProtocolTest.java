/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus.committee;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for CommitteeVotingProtocol FSM.
 * <p>
 * States:
 * - PROPOSAL_PENDING: Waiting for quorum
 * - QUORUM_ACHIEVED: Consensus reached
 * - TIMEOUT_EXPIRED: Voting deadline exceeded
 * - ROLLBACK_DUE_TO_VIEW_CHANGE: View changed, abort pending proposals
 * <p>
 * Phase 7G Day 2: Voting Protocol & Ballot Box
 *
 * @author hal.hildebrand
 */
class CommitteeVotingProtocolTest {
    // Fixed-base clock for proposal/vote timestamps (determinism mandate, Luciferase-ze0eq).
    private static final TestClock ZE0EQ_CLOCK = new TestClock(1_000L);

    private DynamicContext<Member> mockContext;
    private CommitteeConfig config;
    private ScheduledExecutorService scheduler;
    private Digest viewId;

    @BeforeEach
    void setUp() {
        mockContext = Mockito.mock(DynamicContext.class);
        config = CommitteeConfig.defaultConfig();
        scheduler = Executors.newScheduledThreadPool(2);
        viewId = DigestAlgorithm.DEFAULT.getOrigin();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Initial state for new proposal should be PROPOSAL_PENDING.
     */
    @Test
    void testProposalPending() {
        // 7 nodes → toleranceLevel=3, quorum=4
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var protocol = new CommitteeVotingProtocol(mockContext, config, scheduler);
        var proposal = createProposal();
        var committee = createCommittee(7);

        var future = protocol.requestConsensus(proposal, committee);

        assertFalse(future.isDone(), "New proposal should be in PROPOSAL_PENDING state");
    }

    /**
     * Regression for Luciferase-ltxta: a small BFT committee drawn from a LARGE cluster
     * must reach consensus with committee-sized votes. Pre-fix the ballot box derived
     * quorum from the full-cluster context (size=100, toleranceLevel=33 → quorum 34),
     * which a 7-member committee could never reach — every migration silently timed out.
     */
    @Test
    void testSmallCommitteeReachesQuorumInLargeCluster() throws Exception {
        // Full cluster is 100 nodes; the ballot box must NOT use this for quorum.
        when(mockContext.size()).thenReturn(100);
        when(mockContext.toleranceLevel()).thenReturn(33);

        var protocol = new CommitteeVotingProtocol(mockContext, config, scheduler);
        var proposal = createProposal();
        var committee = createCommittee(7);  // committee majority = (7-1)/2 + 1 = 4

        var future = protocol.requestConsensus(proposal, committee);

        // 4 distinct committee YES votes reach the committee-relative quorum.
        for (int i = 0; i < 4; i++) {
            protocol.recordVote(new Vote(
                proposal.proposalId(), DigestAlgorithm.DEFAULT.digest("member-" + i), true, viewId));
        }

        var result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
            () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(result, "Committee-sized YES majority must reach quorum despite a 100-node cluster context");
    }

    /**
     * Future should complete when quorum is reached.
     */
    @Test
    void testQuorumReachedCompletesConsensus() throws Exception {
        // 3 nodes → toleranceLevel=1, quorum=2
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);

        var protocol = new CommitteeVotingProtocol(mockContext, config, scheduler);
        var proposal = createProposal();
        var committee = createCommittee(3);

        var future = protocol.requestConsensus(proposal, committee);

        // Add 2 YES votes (quorum) - use committee member IDs
        for (int i = 0; i < 2; i++) {
            var vote = new Vote(proposal.proposalId(), DigestAlgorithm.DEFAULT.digest("member-" + i), true, viewId);
            protocol.recordVote(vote);
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Future should complete with true when quorum reached");
    }

    /**
     * Consensus should time out after configured voting timeout (5 seconds default).
     */
    @Test
    void testTimeoutAbortsPendingProposal() throws Exception {
        // 3 nodes → toleranceLevel=1, quorum=2
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);

        // Override config with short timeout for testing
        var shortConfig = CommitteeConfig.newBuilder().votingTimeoutSeconds(1).build();
        var protocol = new CommitteeVotingProtocol(mockContext, shortConfig, scheduler);
        var proposal = createProposal();
        var committee = createCommittee(3);

        var future = protocol.requestConsensus(proposal, committee);

        // Add only 1 vote (not quorum) - use committee member ID
        var vote = new Vote(proposal.proposalId(), DigestAlgorithm.DEFAULT.digest("member-0"), true, viewId);
        protocol.recordVote(vote);

        // Wait for timeout
        try {
            future.get(2, TimeUnit.SECONDS);
            fail("Should have timed out");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException, "Should timeout with TimeoutException");
        }
    }

    /**
     * View change should rollback pending proposals for old view.
     */
    @Test
    void testViewChangeRollsbackProposal() throws Exception {
        // 3 nodes → toleranceLevel=1, quorum=2
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);

        var protocol = new CommitteeVotingProtocol(mockContext, config, scheduler);
        var oldViewId = DigestAlgorithm.DEFAULT.getOrigin();
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            oldViewId,
            ZE0EQ_CLOCK.currentTimeMillis()
        );
        var committee = createCommittee(3);

        var future = protocol.requestConsensus(proposal, committee);

        // Trigger view change
        var newViewId = DigestAlgorithm.DEFAULT.digest("new-view");
        protocol.rollbackOnViewChange(newViewId);

        // Future should be completed exceptionally
        try {
            future.get(1, TimeUnit.SECONDS);
            fail("Should have been rolled back");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalStateException, "Should fail with IllegalStateException");
            assertTrue(e.getCause().getMessage().contains("view change"), "Error message should mention view change");
        }
    }

    /**
     * Multiple proposals should be tracked independently.
     */
    @Test
    void testMultipleProposalsIndependent() throws Exception {
        // 3 nodes → toleranceLevel=1, quorum=2
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);

        var protocol = new CommitteeVotingProtocol(mockContext, config, scheduler);
        var committee = createCommittee(3);

        // Start two proposals
        var proposal1 = createProposal();
        var proposal2 = createProposal();
        var future1 = protocol.requestConsensus(proposal1, committee);
        var future2 = protocol.requestConsensus(proposal2, committee);

        // Complete proposal 1 - use committee member IDs
        for (int i = 0; i < 2; i++) {
            var vote = new Vote(proposal1.proposalId(), DigestAlgorithm.DEFAULT.digest("member-" + i), true, viewId);
            protocol.recordVote(vote);
        }

        // Proposal 1 should complete
        assertTrue(future1.get(1, TimeUnit.SECONDS), "Proposal 1 should complete");

        // Proposal 2 should still be pending
        assertFalse(future2.isDone(), "Proposal 2 should still be pending");

        // Complete proposal 2 - use different committee member IDs
        for (int i = 0; i < 2; i++) {
            var vote = new Vote(proposal2.proposalId(), DigestAlgorithm.DEFAULT.digest("member-" + i), false, viewId);
            protocol.recordVote(vote);
        }

        // Proposal 2 should complete with different result
        assertFalse(future2.get(1, TimeUnit.SECONDS), "Proposal 2 should complete with false");
    }

    /**
     * Future should resolve to correct Boolean value (YES majority → true, NO majority → false).
     */
    @Test
    void testFutureCompletionWithCorrectResult() throws Exception {
        // 5 nodes → toleranceLevel=2, quorum=3
        when(mockContext.size()).thenReturn(5);
        when(mockContext.toleranceLevel()).thenReturn(2);

        var protocol = new CommitteeVotingProtocol(mockContext, config, scheduler);
        var committee = createCommittee(5);

        // Test YES majority - use committee member IDs
        var proposal1 = createProposal();
        var future1 = protocol.requestConsensus(proposal1, committee);
        for (int i = 0; i < 3; i++) {
            var vote = new Vote(proposal1.proposalId(), DigestAlgorithm.DEFAULT.digest("member-" + i), true, viewId);
            protocol.recordVote(vote);
        }
        assertTrue(future1.get(1, TimeUnit.SECONDS), "YES majority should return true");

        // Test NO majority - use committee member IDs
        var proposal2 = createProposal();
        var future2 = protocol.requestConsensus(proposal2, committee);
        for (int i = 0; i < 3; i++) {
            var vote = new Vote(proposal2.proposalId(), DigestAlgorithm.DEFAULT.digest("member-" + i), false, viewId);
            protocol.recordVote(vote);
        }
        assertFalse(future2.get(1, TimeUnit.SECONDS), "NO majority should return false");
    }

    /**
     * Regression for Luciferase-7wzml.196: quorum-completing vote concurrent with view-change rollback.
     * <p>
     * If recordVote() drives the ballot box to quorum (clears the VoteState) at the same time
     * rollbackOnViewChange() iterates its snapshot, the rollback path must NOT resurrect a new
     * zombie VoteState via getResult(computeIfAbsent) and complete it exceptionally.
     * <p>
     * Assertion:
     * - If the future completed normally (quorum path won) → no spurious abort, result is true.
     * - If the future completed exceptionally (rollback path won) → must be IllegalStateException
     *   "view change", NOT a zombie future that never completes.
     * - Either way the future must be done within the timeout — no blocking zombie.
     */
    @Test
    void testConcurrentQuorumAndViewChangeNoZombieFuture() throws Exception {
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);

        // Use a single-thread scheduler so timeout fires are serialised (no interference).
        var protocol = new CommitteeVotingProtocol(mockContext, config, scheduler);
        var oldViewId = DigestAlgorithm.DEFAULT.getOrigin();
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            oldViewId,
            ZE0EQ_CLOCK.currentTimeMillis()
        );
        // committee of 3: quorum = (3-1)/2 + 1 = 2 votes needed
        var committee = createCommittee(3);
        var committeeIds = new java.util.ArrayList<>(committee);

        var future = protocol.requestConsensus(proposal, committee);

        // Use a CountDownLatch so both operations start as simultaneously as possible.
        var startGate = new CountDownLatch(1);
        var newViewId = DigestAlgorithm.DEFAULT.digest("new-view");

        // Thread 1: supply the quorum-completing second vote
        var voteThread = new Thread(() -> {
            try {
                startGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Vote 1 (pre-gate so quorum needs one more)
            protocol.recordVote(new Vote(proposal.proposalId(), committeeIds.get(0), true, oldViewId));
            // Vote 2 — this one completes the quorum and triggers cleanup
            protocol.recordVote(new Vote(proposal.proposalId(), committeeIds.get(1), true, oldViewId));
        });

        // Thread 2: trigger the view-change rollback
        var rollbackThread = new Thread(() -> {
            try {
                startGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            protocol.rollbackOnViewChange(newViewId);
        });

        voteThread.start();
        rollbackThread.start();
        startGate.countDown();   // release both threads simultaneously

        // assertTimeoutPreemptively fails the test if either thread is still alive after 2 s
        // (raw join(2000)+isDone silently passes on deadlock — stuck threads are not detected).
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            voteThread.join();
            rollbackThread.join();
        }, "Concurrent vote+rollback threads must complete within 2 s — possible deadlock");
        assertEquals(Thread.State.TERMINATED, voteThread.getState(), "voteThread must be TERMINATED");
        assertEquals(Thread.State.TERMINATED, rollbackThread.getState(), "rollbackThread must be TERMINATED");

        // The future MUST be done — never a blocking zombie.
        assertTrue(future.isDone(), "Future must not be a zombie — must complete regardless of interleaving");

        // Determine which path won and assert the outcome is coherent.
        if (future.isCompletedExceptionally()) {
            // Rollback path won: must be the expected view-change exception, not an NPE
            // or some internal error from resurrected state.
            var ex = assertThrows(ExecutionException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
            assertInstanceOf(IllegalStateException.class, ex.getCause(),
                "Exceptional completion must be an IllegalStateException (view change)");
            assertTrue(ex.getCause().getMessage().contains("view change"),
                "Exception message must reference view change");
        } else {
            // Quorum path won: result must be true (all votes were YES).
            assertTrue(future.get(100, TimeUnit.MILLISECONDS),
                "Quorum-completing path must yield true");
        }
    }

    // Helper methods

    private MigrationProposal createProposal() {
        return new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            viewId,
            ZE0EQ_CLOCK.currentTimeMillis()
        );
    }

    private Set<Digest> createCommittee(int size) {
        var committee = new HashSet<Digest>();
        for (int i = 0; i < size; i++) {
            committee.add(DigestAlgorithm.DEFAULT.digest("member-" + i));
        }
        return committee;
    }
}
