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
     * Voter-identity deduplication: a single Byzantine member that submits the
     * same vote N times must NOT reach quorum on its own. Each member contributes
     * exactly one vote; repeats are silently rejected and cannot move the tally.
     */
    @Test
    void testSingleVoterRepeatVotesCannotReachQuorum() throws Exception {
        // 7 nodes → toleranceLevel=3, quorum=4
        when(mockContext.size()).thenReturn(7);
        when(mockContext.toleranceLevel()).thenReturn(3);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // One Byzantine voter submits the SAME (proposalId, voterId) vote 10 times.
        var byzantine = DigestAlgorithm.DEFAULT.digest("byzantine-solo");
        for (int i = 0; i < 10; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId, byzantine, true, viewId));
        }

        assertFalse(future.isDone(),
                    "A single voter casting 10 identical votes must NOT reach quorum (need 4 distinct voters)");

        // Three additional DISTINCT honest voters bring the YES tally to exactly 4.
        for (int i = 0; i < 3; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("honest-" + i), true, viewId));
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "4 DISTINCT YES voters (1 Byzantine + 3 honest) reach quorum");
    }

    /**
     * A duplicate vote from an already-counted voter must not flip a tally that a
     * distinct set of voters would otherwise control.
     */
    @Test
    void testDuplicateVoteDoesNotOutweighDistinctVoters() throws Exception {
        // 5 nodes → toleranceLevel=2, quorum=3
        when(mockContext.size()).thenReturn(5);
        when(mockContext.toleranceLevel()).thenReturn(2);

        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        var future = ballotBox.getResult(proposalId);

        // One voter spams NO five times — must count as a single NO.
        var spammer = DigestAlgorithm.DEFAULT.digest("no-spammer");
        for (int i = 0; i < 5; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId, spammer, false, viewId));
        }
        assertFalse(future.isDone(), "5 repeat NO votes from one voter must not reach quorum=3");

        // Three distinct YES voters reach quorum; YES wins despite the NO spam.
        for (int i = 0; i < 3; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId, DigestAlgorithm.DEFAULT.digest("yes-" + i), true, viewId));
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "3 distinct YES voters reach quorum; the single NO-spammer counts once and loses");
    }

    // -------------------------------------------------------------------------
    // Split-tally determinism tests (Luciferase-7wzml.195)
    // New formula: n==2 ? 1 : n/2+1 (strict majority for n>=3)
    // -------------------------------------------------------------------------

    /**
     * DETERMINISM PROOF for even n=4 (Luciferase-7wzml.195).
     * <p>
     * New quorum: n/2+1 = 3 for n=4. A perfect {2Y,2N} split cannot reach quorum=3
     * from either side, regardless of arrival order. Both orderings of the SAME vote
     * SET must produce the SAME result (no-decision), proving arrival-order independence.
     * <p>
     * This is the key regression: the old formula gave quorum=2 for n=4, so a 2-YES
     * burst decided YES at 2-0 before any NO arrived. The same vote SET {2Y,2N} produced
     * YES or NO depending purely on which faction's 2 votes landed first — a consensus
     * violation. The tie-break guard did NOT catch this (it only fires at yesCount==noCount,
     * never reached in the 2-0 burst scenario).
     */
    @Test
    void testEvenN4SplitBothOrderingsProduceNodecision() {
        // Order A: 2 YES then 2 NO — must NOT decide
        {
            var ballotBox = new CommitteeBallotBox(mockContext);
            var proposalId = UUID.randomUUID();
            ballotBox.registerCommittee(proposalId, 4); // quorum = 4/2+1 = 3
            var future = ballotBox.getResult(proposalId);
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-0"), true, viewId));
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-1"), true, viewId));
            // With old formula (quorum=2), YES would have decided here at 2-0.
            assertFalse(future.isDone(),
                "n=4 order-A: 2-YES burst must NOT reach quorum=3 — old quorum=2 would have decided YES here");
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-0"), false, viewId));
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-1"), false, viewId));
            assertFalse(future.isDone(),
                "n=4 order-A: full {2Y,2N} split must NOT decide (same vote set → same no-decision)");
        }
        // Order B: 2 NO then 2 YES — must also NOT decide (same vote set, different arrival order)
        {
            var ballotBox = new CommitteeBallotBox(mockContext);
            var proposalId = UUID.randomUUID();
            ballotBox.registerCommittee(proposalId, 4); // quorum = 4/2+1 = 3
            var future = ballotBox.getResult(proposalId);
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-0"), false, viewId));
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-1"), false, viewId));
            // With old formula (quorum=2), NO would have decided here at 2-0.
            assertFalse(future.isDone(),
                "n=4 order-B: 2-NO burst must NOT reach quorum=3 — old quorum=2 would have decided NO here");
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-0"), true, viewId));
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-1"), true, viewId));
            assertFalse(future.isDone(),
                "n=4 order-B: full {2Y,2N} split must NOT decide (same vote set → same no-decision)");
        }
    }

    /**
     * Even committee n=4, strict majority: 3 YES + 1 NO → YES decides.
     * With quorum=3 (n/2+1), YES reaches quorum and outnumbers NO.
     */
    @Test
    void testEvenN4StrictMajorityYesDecides() throws Exception {
        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        ballotBox.registerCommittee(proposalId, 4); // quorum = 4/2+1 = 3
        var future = ballotBox.getResult(proposalId);

        // 1 NO, then 3 YES
        ballotBox.addVote(proposalId, new Vote(proposalId,
            DigestAlgorithm.DEFAULT.digest("no-0"), false, viewId));
        assertFalse(future.isDone(), "1 NO alone must not complete");
        ballotBox.addVote(proposalId, new Vote(proposalId,
            DigestAlgorithm.DEFAULT.digest("yes-0"), true, viewId));
        ballotBox.addVote(proposalId, new Vote(proposalId,
            DigestAlgorithm.DEFAULT.digest("yes-1"), true, viewId));
        assertFalse(future.isDone(), "2 YES vs 1 NO must not complete (quorum=3 not reached)");
        ballotBox.addVote(proposalId, new Vote(proposalId,
            DigestAlgorithm.DEFAULT.digest("yes-2"), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "n=4: 3 YES vs 1 NO must decide YES (quorum=3 reached, YES > NO)");
    }

    /**
     * Even committee n=8: quorum = 8/2+1 = 5 (strict majority, not 4).
     * DETERMINISM PROOF: both orderings of {4Y,4N} must produce no-decision.
     * Run 20 independent instances each ordering → all no-decision (never random).
     */
    @Test
    void testEvenN8SplitBothOrderingsProduceNodecision() {
        for (int run = 0; run < 20; run++) {
            // Order A: 4 YES then 4 NO
            {
                var ballotBox = new CommitteeBallotBox(mockContext);
                var proposalId = UUID.randomUUID();
                ballotBox.registerCommittee(proposalId, 8); // quorum = 8/2+1 = 5
                for (int i = 0; i < 4; i++) {
                    ballotBox.addVote(proposalId, new Vote(proposalId,
                        DigestAlgorithm.DEFAULT.digest("yes-" + i + "-run-" + run), true, viewId));
                }
                assertFalse(ballotBox.getResult(proposalId).isDone(),
                    "Run " + run + " order-A: 4-YES burst must NOT reach quorum=5");
                for (int i = 0; i < 4; i++) {
                    ballotBox.addVote(proposalId, new Vote(proposalId,
                        DigestAlgorithm.DEFAULT.digest("no-" + i + "-run-" + run), false, viewId));
                }
                assertFalse(ballotBox.getResult(proposalId).isDone(),
                    "Run " + run + " order-A: {4Y,4N} split must NOT decide (no-decision)");
            }
            // Order B: 4 NO then 4 YES
            {
                var ballotBox = new CommitteeBallotBox(mockContext);
                var proposalId = UUID.randomUUID();
                ballotBox.registerCommittee(proposalId, 8); // quorum = 8/2+1 = 5
                for (int i = 0; i < 4; i++) {
                    ballotBox.addVote(proposalId, new Vote(proposalId,
                        DigestAlgorithm.DEFAULT.digest("no-" + i + "-run-" + run), false, viewId));
                }
                assertFalse(ballotBox.getResult(proposalId).isDone(),
                    "Run " + run + " order-B: 4-NO burst must NOT reach quorum=5");
                for (int i = 0; i < 4; i++) {
                    ballotBox.addVote(proposalId, new Vote(proposalId,
                        DigestAlgorithm.DEFAULT.digest("yes-" + i + "-run-" + run), true, viewId));
                }
                assertFalse(ballotBox.getResult(proposalId).isDone(),
                    "Run " + run + " order-B: {4Y,4N} split must NOT decide (no-decision)");
            }
        }
    }

    /**
     * Even committee n=8: quorum = 8/2+1 = 5.
     * Tie-break guard: interleaved 4-YES/4-NO at 4-4 tally — neither at quorum=5,
     * tie-break guard fires on equal count. Then 5th YES breaks tie and reaches quorum.
     */
    @Test
    void testEvenCommitteeTieBreakGuardAndQuorum() {
        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        ballotBox.registerCommittee(proposalId, 8); // quorum = 8/2+1 = 5

        var future = ballotBox.getResult(proposalId);

        // Interleave: 4 YES/NO pairs — 4-4 tie, neither at quorum=5
        for (int i = 0; i < 4; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-" + i), true, viewId));
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-" + i), false, viewId));
        }
        // 4-4 tie, neither at quorum=5
        assertFalse(future.isDone(), "4-YES/4-NO tie (neither at quorum=5) must not complete");

        // 5th YES breaks tie and reaches quorum=5 (YES=5 > NO=4)
        ballotBox.addVote(proposalId, new Vote(proposalId,
            DigestAlgorithm.DEFAULT.digest("yes-4"), true, viewId));
        assertTrue(future.isDone(), "5th YES reaches quorum=5 with YES > NO, must complete");
        try {
            assertTrue(future.get(0, java.util.concurrent.TimeUnit.MILLISECONDS),
                "5-YES/4-NO: must decide YES");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Even committee n=8: quorum = 8/2+1 = 5.
     * A 5-YES / 3-NO tally: 5 YES >= quorum=5 and YES > NO → must complete with TRUE.
     */
    @Test
    void testEvenCommitteeMajorityYesDecides() throws Exception {
        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        ballotBox.registerCommittee(proposalId, 8); // even, quorum = 8/2+1 = 5

        var future = ballotBox.getResult(proposalId);

        // 3 NO votes first — below quorum=5
        for (int i = 0; i < 3; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-" + i), false, viewId));
        }
        assertFalse(future.isDone(), "3 NO votes alone must not complete (quorum=5)");

        // 5 YES votes reach quorum=5 and outnumber NO (5 > 3)
        for (int i = 0; i < 5; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-" + i), true, viewId));
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "5 YES vs 3 NO with n=8 (quorum=5): must decide YES");
    }

    /**
     * Even committee n=8: quorum = 8/2+1 = 5.
     * A 3-YES / 5-NO tally: 5 NO >= quorum=5 and NO > YES → must complete with FALSE.
     */
    @Test
    void testEvenCommitteeMajorityNoDecides() throws Exception {
        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        ballotBox.registerCommittee(proposalId, 8); // even, quorum = 8/2+1 = 5

        var future = ballotBox.getResult(proposalId);

        // 5 NO votes reach quorum=5
        for (int i = 0; i < 5; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-" + i), false, viewId));
        }
        // 3 YES below quorum=5
        for (int i = 0; i < 3; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-" + i), true, viewId));
        }

        var result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result, "5 NO vs 3 YES with n=8 (quorum=5): must decide NO");
    }

    /**
     * Odd committee n=7: quorum = 7/2+1 = 4. For ODD n, n/2+1 == (n-1)/2+1 (same result).
     * The new formula leaves odd-n quorum unchanged.
     * Splits (3-YES/3-NO) cannot reach quorum since quorum=4.
     * Majority (4-YES) must decide correctly.
     */
    @Test
    void testOddCommitteeUnaffectedByFix() throws Exception {
        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        ballotBox.registerCommittee(proposalId, 7); // odd, quorum = 7/2+1 = 4

        var future = ballotBox.getResult(proposalId);

        // 3 YES, 3 NO — neither at quorum=4, future not done
        for (int i = 0; i < 3; i++) {
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-" + i), true, viewId));
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-" + i), false, viewId));
        }
        assertFalse(future.isDone(), "3-YES/3-NO under n=7 must not complete (quorum=4 not reached)");

        // 4th YES pushes YES to 4, reaching quorum=4 with YES > NO
        ballotBox.addVote(proposalId, new Vote(proposalId,
            DigestAlgorithm.DEFAULT.digest("yes-3"), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "4th YES vote on n=7 odd committee reaches quorum=4 and must decide YES");
    }

    /**
     * Two-node cluster (n=2): quorum = 1 (special-cased owner-vote design).
     * n=2 is special-cased to 1 (not n/2+1=2) so a single owner vote approves its
     * own migration. This is the INTENTIONAL contract (TwoNodeIntegrationTest documents it).
     */
    @Test
    void testTwoNodeClusterSingleVoteApproves() throws Exception {
        var ballotBox = new CommitteeBallotBox(mockContext);
        var proposalId = UUID.randomUUID();
        ballotBox.registerCommittee(proposalId, 2); // n=2, quorum = 1 (special case)

        var future = ballotBox.getResult(proposalId);

        // Single YES vote from owner — must reach quorum=1 immediately
        ballotBox.addVote(proposalId, new Vote(proposalId,
            DigestAlgorithm.DEFAULT.digest("owner"), true, viewId));

        var result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result, "n=2 cluster: owner's single YES vote must reach quorum=1 and approve migration");
    }

    /**
     * Even committee n=4: quorum = 4/2+1 = 3.
     * Validates non-deciding partial tallies and the deciding majority case.
     */
    @Test
    void testEvenCommitteeN4SplitAndMajority() throws Exception {
        // Partial tally: 1-YES/1-NO — both below quorum=3, must not complete
        {
            var ballotBox = new CommitteeBallotBox(mockContext);
            var proposalId = UUID.randomUUID();
            ballotBox.registerCommittee(proposalId, 4); // quorum = 4/2+1 = 3
            var future = ballotBox.getResult(proposalId);
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-0"), true, viewId));
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-0"), false, viewId));
            assertFalse(future.isDone(), "n=4: 1-YES/1-NO (below quorum=3) must not complete");
        }
        // Strict majority: 1-NO then 3-YES → 3rd YES reaches quorum=3 with YES > NO
        {
            var ballotBox = new CommitteeBallotBox(mockContext);
            var proposalId = UUID.randomUUID();
            ballotBox.registerCommittee(proposalId, 4);
            var future = ballotBox.getResult(proposalId);
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("no-0"), false, viewId));
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-0"), true, viewId));
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-1"), true, viewId));
            assertFalse(future.isDone(), "n=4: 2-YES/1-NO must not complete yet (quorum=3 not reached)");
            ballotBox.addVote(proposalId, new Vote(proposalId,
                DigestAlgorithm.DEFAULT.digest("yes-2"), true, viewId));
            var result = future.get(1, TimeUnit.SECONDS);
            assertTrue(result, "n=4: 3-YES/1-NO must decide YES (quorum=3 reached, YES > NO)");
        }
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
