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
            System.currentTimeMillis()
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
     * Test isQuorumReached() method directly.
     */
    @Test
    void testIsQuorumReached() {
        // 7 nodes → toleranceLevel=3, quorum=4
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var protocol = new CommitteeVotingProtocol(mockContext, config, scheduler);

        assertFalse(protocol.isQuorumReached(3), "3 votes should not reach quorum (need 4)");
        assertTrue(protocol.isQuorumReached(4), "4 votes should reach quorum");
        assertTrue(protocol.isQuorumReached(5), "5 votes should exceed quorum");
    }

    // Helper methods

    private MigrationProposal createProposal() {
        return new MigrationProposal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DigestAlgorithm.DEFAULT.digest("source"),
            DigestAlgorithm.DEFAULT.digest("target"),
            viewId,
            System.currentTimeMillis()
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
