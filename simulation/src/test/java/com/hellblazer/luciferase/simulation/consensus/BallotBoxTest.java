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

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for BallotBox vote tracking and quorum-based decision making.
 *
 * @author hal.hildebrand
 */
class BallotBoxTest {

    private static final Logger log = LoggerFactory.getLogger(BallotBoxTest.class);

    /**
     * Verify BallotBox creation with valid quorum size.
     */
    @Test
    void testBallotBoxInitialization() {
        var nodeId = UUID.randomUUID();
        var quorumSize = 3;

        var ballotBox = new BallotBox(nodeId, quorumSize);

        assertThat(ballotBox).isNotNull();
        assertThat(ballotBox.getActiveProposals()).isEmpty();
    }

    /**
     * Verify invalid quorum size throws exception.
     */
    @Test
    void testInvalidQuorumSizeThrowsException() {
        var nodeId = UUID.randomUUID();

        // Zero quorum should fail
        assertThatThrownBy(() -> new BallotBox(nodeId, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("quorumSize must be positive");

        // Negative quorum should fail
        assertThatThrownBy(() -> new BallotBox(nodeId, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("quorumSize must be positive");

        // Null nodeId should fail
        assertThatThrownBy(() -> new BallotBox(null, 3))
            .isInstanceOf(NullPointerException.class);
    }

    /**
     * Verify proposal registration with idempotency (registering same proposal twice is safe).
     */
    @Test
    void testRegisterProposal() {
        var nodeId = UUID.randomUUID();
        var ballotBox = new BallotBox(nodeId, 3);

        var proposalId = "proposal-1";
        var proposalType = "ENTITY_OWNERSHIP";

        // Register proposal
        ballotBox.registerProposal(proposalId, proposalType);

        assertThat(ballotBox.getActiveProposals()).containsExactly(proposalId);
        assertThat(ballotBox.getDecisionState(proposalId)).isEqualTo(BallotBox.DecisionState.PENDING);

        // Register same proposal again - should be idempotent
        ballotBox.registerProposal(proposalId, proposalType);
        assertThat(ballotBox.getActiveProposals()).containsExactly(proposalId);
    }

    /**
     * Verify vote recording returns true on first vote, false on duplicate.
     */
    @Test
    void testRecordYesVote() {
        var nodeId = UUID.randomUUID();
        var ballotBox = new BallotBox(nodeId, 3);

        var proposalId = "proposal-1";
        var voter1 = UUID.randomUUID();
        var voter2 = UUID.randomUUID();

        // First vote should be recorded
        assertThat(ballotBox.recordYesVote(proposalId, voter1)).isTrue();

        // Duplicate vote from same voter should be ignored
        assertThat(ballotBox.recordYesVote(proposalId, voter1)).isFalse();

        // Vote from different voter should be recorded
        assertThat(ballotBox.recordYesVote(proposalId, voter2)).isTrue();

        var counts = ballotBox.getVoteCounts(proposalId);
        assertThat(counts.yesVotes).isEqualTo(2);
        assertThat(counts.noVotes).isEqualTo(0);
    }

    /**
     * Verify no vote recording works similarly to yes votes.
     */
    @Test
    void testRecordNoVote() {
        var nodeId = UUID.randomUUID();
        var ballotBox = new BallotBox(nodeId, 3);

        var proposalId = "proposal-1";
        var voter1 = UUID.randomUUID();
        var voter2 = UUID.randomUUID();

        // First no vote should be recorded
        assertThat(ballotBox.recordNoVote(proposalId, voter1)).isTrue();

        // Duplicate no vote from same voter should be ignored
        assertThat(ballotBox.recordNoVote(proposalId, voter1)).isFalse();

        // No vote from different voter should be recorded
        assertThat(ballotBox.recordNoVote(proposalId, voter2)).isTrue();

        var counts = ballotBox.getVoteCounts(proposalId);
        assertThat(counts.yesVotes).isEqualTo(0);
        assertThat(counts.noVotes).isEqualTo(2);
    }

    /**
     * Verify proposal approved when yes quorum reached.
     */
    @Test
    void testApprovedWhenYesQuorumReached() {
        var nodeId = UUID.randomUUID();
        var quorumSize = 3;
        var ballotBox = new BallotBox(nodeId, quorumSize);

        var proposalId = "proposal-1";
        ballotBox.registerProposal(proposalId, "TEST");

        // Initially pending
        assertThat(ballotBox.getDecisionState(proposalId)).isEqualTo(BallotBox.DecisionState.PENDING);
        assertThat(ballotBox.isApproved(proposalId)).isFalse();

        // Record yes votes until quorum reached
        var voter1 = UUID.randomUUID();
        var voter2 = UUID.randomUUID();
        var voter3 = UUID.randomUUID();

        ballotBox.recordYesVote(proposalId, voter1);
        assertThat(ballotBox.isApproved(proposalId)).isFalse(); // 1/3

        ballotBox.recordYesVote(proposalId, voter2);
        assertThat(ballotBox.isApproved(proposalId)).isFalse(); // 2/3

        ballotBox.recordYesVote(proposalId, voter3);
        assertThat(ballotBox.isApproved(proposalId)).isTrue(); // 3/3 - quorum reached

        assertThat(ballotBox.getDecisionState(proposalId)).isEqualTo(BallotBox.DecisionState.APPROVED);
    }

    /**
     * Verify proposal rejected when no votes exceed possible yes votes.
     */
    @Test
    void testRejectedWhenNoExceedsMax() {
        var nodeId = UUID.randomUUID();
        var quorumSize = 3;
        var ballotBox = new BallotBox(nodeId, quorumSize);

        var proposalId = "proposal-1";
        ballotBox.registerProposal(proposalId, "TEST");

        // Record enough no votes to make approval impossible
        var voter1 = UUID.randomUUID();
        var voter2 = UUID.randomUUID();
        var voter3 = UUID.randomUUID();
        var voter4 = UUID.randomUUID();

        ballotBox.recordNoVote(proposalId, voter1);
        assertThat(ballotBox.isRejected(proposalId)).isFalse(); // 1 no vote

        ballotBox.recordNoVote(proposalId, voter2);
        assertThat(ballotBox.isRejected(proposalId)).isFalse(); // 2 no votes

        ballotBox.recordNoVote(proposalId, voter3);
        assertThat(ballotBox.isRejected(proposalId)).isFalse(); // 3 no votes (exactly at quorum)

        ballotBox.recordNoVote(proposalId, voter4);
        assertThat(ballotBox.isRejected(proposalId)).isTrue(); // 4 no votes > quorum (3)

        assertThat(ballotBox.getDecisionState(proposalId)).isEqualTo(BallotBox.DecisionState.REJECTED);
    }

    /**
     * Verify proposal remains PENDING while collecting votes.
     */
    @Test
    void testPendingStateWithoutDecision() {
        var nodeId = UUID.randomUUID();
        var quorumSize = 3;
        var ballotBox = new BallotBox(nodeId, quorumSize);

        var proposalId = "proposal-1";
        ballotBox.registerProposal(proposalId, "TEST");

        // Initially pending
        assertThat(ballotBox.getDecisionState(proposalId)).isEqualTo(BallotBox.DecisionState.PENDING);

        // Record some votes but not enough for decision
        var voter1 = UUID.randomUUID();
        var voter2 = UUID.randomUUID();

        ballotBox.recordYesVote(proposalId, voter1);
        ballotBox.recordNoVote(proposalId, voter2);

        // Should still be pending (1 yes, 1 no, quorum = 3)
        assertThat(ballotBox.getDecisionState(proposalId)).isEqualTo(BallotBox.DecisionState.PENDING);
        assertThat(ballotBox.isApproved(proposalId)).isFalse();
        assertThat(ballotBox.isRejected(proposalId)).isFalse();

        var counts = ballotBox.getVoteCounts(proposalId);
        assertThat(counts.yesVotes).isEqualTo(1);
        assertThat(counts.noVotes).isEqualTo(1);
        assertThat(counts.abstainingVotes).isEqualTo(1); // quorum - yes - no = 3 - 1 - 1 = 1
    }

    /**
     * Verify clearing proposal removes all votes and state.
     */
    @Test
    void testClearProposalResetsState() {
        var nodeId = UUID.randomUUID();
        var ballotBox = new BallotBox(nodeId, 3);

        var proposalId = "proposal-1";
        ballotBox.registerProposal(proposalId, "TEST");

        // Record some votes
        var voter1 = UUID.randomUUID();
        var voter2 = UUID.randomUUID();
        ballotBox.recordYesVote(proposalId, voter1);
        ballotBox.recordNoVote(proposalId, voter2);

        assertThat(ballotBox.getActiveProposals()).containsExactly(proposalId);

        var counts = ballotBox.getVoteCounts(proposalId);
        assertThat(counts.yesVotes).isEqualTo(1);
        assertThat(counts.noVotes).isEqualTo(1);

        // Clear proposal
        ballotBox.clearProposal(proposalId);

        assertThat(ballotBox.getActiveProposals()).isEmpty();

        // After clearing, should report as if no votes (0 yes, 0 no)
        var countsAfterClear = ballotBox.getVoteCounts(proposalId);
        assertThat(countsAfterClear.yesVotes).isEqualTo(0);
        assertThat(countsAfterClear.noVotes).isEqualTo(0);
        assertThat(countsAfterClear.abstainingVotes).isEqualTo(3); // quorum size
    }

    /**
     * Verify getActiveProposals returns correct set of proposals being voted on.
     */
    @Test
    void testGetActiveProposals() {
        var nodeId = UUID.randomUUID();
        var ballotBox = new BallotBox(nodeId, 3);

        // Initially no proposals
        assertThat(ballotBox.getActiveProposals()).isEmpty();

        // Register multiple proposals
        var proposal1 = "proposal-1";
        var proposal2 = "proposal-2";
        var proposal3 = "proposal-3";

        ballotBox.registerProposal(proposal1, "TYPE1");
        ballotBox.registerProposal(proposal2, "TYPE2");
        ballotBox.registerProposal(proposal3, "TYPE3");

        assertThat(ballotBox.getActiveProposals()).containsExactlyInAnyOrder(proposal1, proposal2, proposal3);

        // Clear one proposal
        ballotBox.clearProposal(proposal2);

        assertThat(ballotBox.getActiveProposals()).containsExactlyInAnyOrder(proposal1, proposal3);

        // Clear remaining
        ballotBox.clearProposal(proposal1);
        ballotBox.clearProposal(proposal3);

        assertThat(ballotBox.getActiveProposals()).isEmpty();
    }
}
