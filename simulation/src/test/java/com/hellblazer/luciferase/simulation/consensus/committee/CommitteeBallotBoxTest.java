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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for CommitteeBallotBox - validates BFT correctness using KerlDHT pattern.
 * <p>
 * CRITICAL: This test class validates the quorum formula:
 * {@code majority = context.size() == 1 ? 1 : context.toleranceLevel() + 1}
 * <p>
 * Byzantine Fault Tolerance:
 * - 7 nodes: toleranceLevel=3, quorum=4 (tolerates 3 Byzantine)
 * - 5 nodes: toleranceLevel=2, quorum=3 (tolerates 2 Byzantine)
 * - 3 nodes: toleranceLevel=1, quorum=2 (tolerates 1 Byzantine)
 * - 1 node:  toleranceLevel=0, quorum=1 (special case)
 * <p>
 * Phase 7G Day 2: Voting Protocol & Ballot Box
 *
 * @author hal.hildebrand
 */
class CommitteeBallotBoxTest {

    private DynamicContext<Member> mockContext;
    private Digest viewId;

    @BeforeEach
    void setUp() {
        mockContext = Mockito.mock(DynamicContext.class);
        viewId = DigestAlgorithm.DEFAULT.getOrigin();
    }

    /**
     * Single node needs exactly 1 vote (special case in quorum formula).
     * Formula: context.size() == 1 ? 1 : context.toleranceLevel() + 1
     */
    @Test
    void testSingleNodeQuorumImmediate() throws Exception {
        // 1 node → toleranceLevel=0, quorum=1 (special case)
        when(mockContext.size()).thenReturn(1);
        when(mockContext.toleranceLevel()).thenReturn(0);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // Single YES vote should immediately complete
        var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.getOrigin(), true, viewId);
        ballotBox.addVote(proposalId, vote);

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Single node should reach quorum with 1 vote");
    }

    /**
     * 7 nodes with toleranceLevel=3 need 4 votes for quorum.
     * This is the standard Byzantine tolerance case.
     */
    @Test
    void testSevenNodeQuorumFourVotes() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // Add 3 YES votes - should NOT complete yet
        for (int i = 0; i < 3; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }
        assertFalse(future.isDone(), "3 votes should not reach quorum (need 4)");

        // Add 4th YES vote - should complete
        var vote4 = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-3"), true, viewId);
        ballotBox.addVote(proposalId, vote4);

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "7 nodes should reach quorum with 4 YES votes");
    }

    /**
     * 3 nodes with toleranceLevel=1 need 2 votes for quorum.
     */
    @Test
    void testThreeNodeQuorumTwoVotes() throws Exception {
        // 3 nodes → toleranceLevel=1, quorum=2
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // Add 1 YES vote - should NOT complete yet
        var vote1 = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-0"), true, viewId);
        ballotBox.addVote(proposalId, vote1);
        assertFalse(future.isDone(), "1 vote should not reach quorum (need 2)");

        // Add 2nd YES vote - should complete
        var vote2 = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-1"), true, viewId);
        ballotBox.addVote(proposalId, vote2);

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "3 nodes should reach quorum with 2 YES votes");
    }

    /**
     * 5 nodes with toleranceLevel=2 need 3 votes for quorum.
     */
    @Test
    void testFiveNodeQuorumThreeVotes() throws Exception {
        // 5 nodes → toleranceLevel=2, quorum=3
        when(mockContext.size()).thenReturn(5);
        when(mockContext.toleranceLevel()).thenReturn(2);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // Add 2 YES votes - should NOT complete yet
        for (int i = 0; i < 2; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }
        assertFalse(future.isDone(), "2 votes should not reach quorum (need 3)");

        // Add 3rd YES vote - should complete
        var vote3 = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-2"), true, viewId);
        ballotBox.addVote(proposalId, vote3);

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "5 nodes should reach quorum with 3 YES votes");
    }

    /**
     * When majority votes YES, result should be TRUE.
     */
    @Test
    void testMajorityYesVotes() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // Add 4 YES votes (quorum)
        for (int i = 0; i < 4; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Majority YES votes should return true");
    }

    /**
     * When majority votes NO, result should be FALSE.
     */
    @Test
    void testMajorityNoVotes() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // Add 4 NO votes (quorum)
        for (int i = 0; i < 4; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-" + i), false, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result, "Majority NO votes should return false");
    }

    /**
     * Byzantine fault tolerance: 7 nodes with 2 Byzantine faulty nodes.
     * 5 honest votes for YES should achieve consensus (quorum=4).
     */
    @Test
    void testNoByzantineFailure() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4 (tolerates 3 Byzantine)
        // Scenario: 2 Byzantine nodes vote NO, 5 honest nodes vote YES → YES wins
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // 2 Byzantine NO votes
        for (int i = 0; i < 2; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("byzantine-" + i), false, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        // 5 honest YES votes (exceeds quorum=4)
        for (int i = 0; i < 5; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("honest-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "5 YES votes should overcome 2 Byzantine NO votes");
    }

    /**
     * Byzantine fault tolerance at the edge: all 4 required votes are YES,
     * despite 3 Byzantine NO votes (exactly at tolerance limit).
     */
    @Test
    void testByzantineFailureTolerated() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4 (tolerates 3 Byzantine)
        // Scenario: Exactly 3 Byzantine NO votes, 4 honest YES votes → YES wins
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // 3 Byzantine NO votes (at tolerance limit)
        for (int i = 0; i < 3; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("byzantine-" + i), false, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        // 4 honest YES votes (exactly at quorum)
        for (int i = 0; i < 4; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("honest-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "4 YES votes should reach quorum despite 3 Byzantine NO votes (at tolerance limit)");
    }

    /**
     * Test cleanup after decision.
     */
    @Test
    void testClearAfterDecision() throws Exception {
        // 3 nodes → toleranceLevel=1, quorum=2
        when(mockContext.size()).thenReturn(3);
        when(mockContext.toleranceLevel()).thenReturn(1);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future1 = ballotBox.getResult(proposalId);

        // Reach quorum
        for (int i = 0; i < 2; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }
        future1.get(1, TimeUnit.SECONDS);

        // Clear
        ballotBox.clear(proposalId);

        // New future for same proposal should be fresh
        var future2 = ballotBox.getResult(proposalId);
        assertFalse(future2.isDone(), "New future after clear should not be completed");
    }
}
