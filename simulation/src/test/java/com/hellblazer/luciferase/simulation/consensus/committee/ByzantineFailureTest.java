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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * CRITICAL: Byzantine Fault Tolerance validation tests.
 * <p>
 * These tests verify that the system tolerates f Byzantine nodes where:
 * f = toleranceLevel (from DynamicContext)
 * <p>
 * Byzantine tolerance formula (from KerlDHT):
 * quorum = context.size() == 1 ? 1 : context.toleranceLevel() + 1
 * <p>
 * Test scenarios:
 * - 2 nodes: toleranceLevel=0, quorum=1, tolerates 0 Byzantine
 * - 5 nodes: toleranceLevel=2, quorum=3, tolerates 2 Byzantine
 * - 7 nodes: toleranceLevel=3, quorum=4, tolerates 3 Byzantine
 * <p>
 * Phase 7G Day 2: Voting Protocol & Ballot Box
 *
 * @author hal.hildebrand
 */
class ByzantineFailureTest {

    private DynamicContext<Member> mockContext;
    private Digest viewId;

    @BeforeEach
    void setUp() {
        mockContext = Mockito.mock(DynamicContext.class);
        viewId = DigestAlgorithm.DEFAULT.getOrigin();
    }

    /**
     * 2 nodes with 1 Byzantine: toleranceLevel=0, quorum=1.
     * <p>
     * In 2-node system with quorum=1, whichever vote arrives first wins.
     * This test demonstrates that NO vote reaches quorum first.
     */
    @Test
    void testTwoNodeWithOneByzantine() throws Exception {
        // 2 nodes → toleranceLevel=0, quorum=1
        when(mockContext.size()).thenReturn(2);
        when(mockContext.toleranceLevel()).thenReturn(0);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // 1 NO vote (reaches quorum=1 immediately)
        var vote1 = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-0"), false, viewId);
        ballotBox.addVote(proposalId, vote1);

        // Future should complete immediately with quorum=1
        var result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result, "First vote (NO) reaches quorum=1 in 2-node system");

        // Second vote doesn't matter (future already completed)
        var vote2 = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("voter-1"), true, viewId);
        ballotBox.addVote(proposalId, vote2);  // This is ignored
    }

    /**
     * 5 nodes with 2 Byzantine: toleranceLevel=2, quorum=3.
     * <p>
     * 2 Byzantine NO votes should be overcome by 3 honest YES votes.
     */
    @Test
    void testFiveNodeWithTwoByzantine() throws Exception {
        // 5 nodes → toleranceLevel=2, quorum=3
        when(mockContext.size()).thenReturn(5);
        when(mockContext.toleranceLevel()).thenReturn(2);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // 2 Byzantine NO votes (at tolerance limit)
        for (int i = 0; i < 2; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("byzantine-" + i), false, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        // 3 honest YES votes (exactly at quorum)
        for (int i = 0; i < 3; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("honest-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "3 honest YES votes should overcome 2 Byzantine NO votes (quorum=3, f=2)");
    }

    /**
     * 7 nodes with 3 Byzantine: toleranceLevel=3, quorum=4.
     * <p>
     * This is the standard Byzantine tolerance case.
     * 3 Byzantine NO votes should be overcome by 4 honest YES votes.
     */
    @Test
    void testSevenNodeWithThreeByzantine() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4
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
        assertTrue(result, "4 honest YES votes should overcome 3 Byzantine NO votes (quorum=4, f=3)");
    }

    /**
     * Byzantine votes cannot skew result if they are in the minority.
     * <p>
     * Even if all Byzantine nodes vote for one option, the honest majority wins.
     */
    @Test
    void testByzantineCannotSkewResult() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // 3 Byzantine YES votes (trying to force YES)
        for (int i = 0; i < 3; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("byzantine-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        // 4 honest NO votes (honest majority wins)
        for (int i = 0; i < 4; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("honest-" + i), false, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result, "4 honest NO votes should override 3 Byzantine YES votes");
    }

    /**
     * If Byzantine nodes exactly equal the tolerance limit but vote split,
     * honest nodes still determine the result.
     */
    @Test
    void testByzantineSplitVote() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4
        // 3 Byzantine nodes split (2 YES, 1 NO)
        // 4 honest nodes vote YES → YES wins
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // 2 Byzantine YES votes
        for (int i = 0; i < 2; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("byzantine-yes-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        // 1 Byzantine NO vote
        var byzantineNo = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("byzantine-no"), false, viewId);
        ballotBox.addVote(proposalId, byzantineNo);

        // 4 honest YES votes
        for (int i = 0; i < 4; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("honest-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "4 honest + 2 Byzantine YES votes = 6 YES total (exceeds quorum=4)");
    }

    /**
     * Edge case: Exactly quorum reached with Byzantine votes included.
     * <p>
     * If Byzantine votes align with honest majority, they don't harm consensus.
     */
    @Test
    void testByzantineVotesAlignedWithHonest() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4
        // 1 honest YES + 3 Byzantine YES = 4 total (quorum) → YES wins
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // 1 honest YES vote
        var honestVote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("honest"), true, viewId);
        ballotBox.addVote(proposalId, honestVote);

        // 3 Byzantine YES votes (aligned with honest)
        for (int i = 0; i < 3; i++) {
            var vote = new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("byzantine-" + i), true, viewId);
            ballotBox.addVote(proposalId, vote);
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "Byzantine votes aligned with honest should not prevent consensus");
    }
}
