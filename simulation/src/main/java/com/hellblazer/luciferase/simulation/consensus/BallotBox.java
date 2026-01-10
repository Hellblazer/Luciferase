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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vote tracking for consensus decisions (ballots).
 *
 * Manages voting on proposals with quorum-based decision making.
 * Thread-safe for concurrent vote recording.
 *
 * VOTING MODEL:
 * - Each proposal has a quorum requirement (typically majority or all nodes)
 * - Votes are recorded with one-vote-per-node constraint
 * - Decision made when quorum reached (all votes yes/no = decided early)
 * - Idempotent: recording same vote twice has no effect
 *
 * @author hal.hildebrand
 */
public class BallotBox {

    private static final Logger log = LoggerFactory.getLogger(BallotBox.class);

    private final UUID nodeId;
    private final int quorumSize;

    // Proposal ID → Ballot tracking
    private final ConcurrentHashMap<String, Ballot> ballots = new ConcurrentHashMap<>();

    /**
     * Create ballot box for consensus voting.
     *
     * @param nodeId Current node UUID
     * @param quorumSize Number of votes required for quorum (typically N/2+1)
     */
    public BallotBox(UUID nodeId, int quorumSize) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        if (quorumSize <= 0) {
            throw new IllegalArgumentException("quorumSize must be positive");
        }
        this.quorumSize = quorumSize;
        log.debug("BallotBox created for node {} (quorumSize={})", nodeId, quorumSize);
    }

    /**
     * Register a new proposal for voting.
     *
     * @param proposalId Unique proposal identifier
     * @param proposalType Type of proposal (for logging)
     */
    public void registerProposal(String proposalId, String proposalType) {
        Objects.requireNonNull(proposalId, "proposalId must not be null");
        Objects.requireNonNull(proposalType, "proposalType must not be null");

        ballots.putIfAbsent(proposalId, new Ballot(proposalId, proposalType));
        log.debug("Proposal registered: {} (type={})", proposalId, proposalType);
    }

    /**
     * Record a yes vote on a proposal.
     *
     * @param proposalId Which proposal to vote on
     * @param voterId UUID of voting node
     * @return true if vote was recorded (first vote from this node), false if duplicate
     */
    public boolean recordYesVote(String proposalId, UUID voterId) {
        Objects.requireNonNull(proposalId, "proposalId must not be null");
        Objects.requireNonNull(voterId, "voterId must not be null");

        var ballot = ballots.computeIfAbsent(proposalId, id -> new Ballot(id, "unknown"));
        boolean recorded = ballot.yesVotes.putIfAbsent(voterId, true) == null;

        if (recorded) {
            log.debug("Yes vote recorded: proposal={}, voter={}, total_yes={}",
                     proposalId, voterId, ballot.yesVotes.size());
        }

        return recorded;
    }

    /**
     * Record a no vote on a proposal.
     *
     * @param proposalId Which proposal to vote on
     * @param voterId UUID of voting node
     * @return true if vote was recorded (first vote from this node), false if duplicate
     */
    public boolean recordNoVote(String proposalId, UUID voterId) {
        Objects.requireNonNull(proposalId, "proposalId must not be null");
        Objects.requireNonNull(voterId, "voterId must not be null");

        var ballot = ballots.computeIfAbsent(proposalId, id -> new Ballot(id, "unknown"));
        boolean recorded = ballot.noVotes.putIfAbsent(voterId, true) == null;

        if (recorded) {
            log.debug("No vote recorded: proposal={}, voter={}, total_no={}",
                     proposalId, voterId, ballot.noVotes.size());
        }

        return recorded;
    }

    /**
     * Check if a proposal has achieved quorum approval (all yes votes).
     *
     * @param proposalId Which proposal to check
     * @return true if yes votes >= quorumSize
     */
    public boolean isApproved(String proposalId) {
        Objects.requireNonNull(proposalId, "proposalId must not be null");

        var ballot = ballots.get(proposalId);
        if (ballot == null) {
            return false;
        }

        return ballot.yesVotes.size() >= quorumSize;
    }

    /**
     * Check if a proposal is rejected (all no votes exceed majority).
     *
     * @param proposalId Which proposal to check
     * @return true if no votes > remaining possible yes votes
     */
    public boolean isRejected(String proposalId) {
        Objects.requireNonNull(proposalId, "proposalId must not be null");

        var ballot = ballots.get(proposalId);
        if (ballot == null) {
            return false;
        }

        // Rejected if no votes alone exceed what yes votes could achieve
        int maxPossibleYes = quorumSize;
        return ballot.noVotes.size() > maxPossibleYes;
    }

    /**
     * Get decision state of a proposal.
     *
     * @param proposalId Which proposal to check
     * @return PENDING, APPROVED, or REJECTED
     */
    public DecisionState getDecisionState(String proposalId) {
        Objects.requireNonNull(proposalId, "proposalId must not be null");

        if (isApproved(proposalId)) {
            return DecisionState.APPROVED;
        }
        if (isRejected(proposalId)) {
            return DecisionState.REJECTED;
        }
        return DecisionState.PENDING;
    }

    /**
     * Get current vote counts for a proposal.
     *
     * @param proposalId Which proposal
     * @return Vote counts (yes, no, abstain)
     */
    public VoteCounts getVoteCounts(String proposalId) {
        Objects.requireNonNull(proposalId, "proposalId must not be null");

        var ballot = ballots.get(proposalId);
        if (ballot == null) {
            return new VoteCounts(0, 0, quorumSize);
        }

        int yes = ballot.yesVotes.size();
        int no = ballot.noVotes.size();
        int abstain = quorumSize - yes - no;

        return new VoteCounts(yes, no, abstain);
    }

    /**
     * Clear all votes for a proposal (for retry scenarios).
     *
     * @param proposalId Which proposal to clear
     */
    public void clearProposal(String proposalId) {
        Objects.requireNonNull(proposalId, "proposalId must not be null");
        ballots.remove(proposalId);
        log.debug("Proposal cleared: {}", proposalId);
    }

    /**
     * Get all active proposals.
     *
     * @return Set of proposal IDs currently being voted on
     */
    public Set<String> getActiveProposals() {
        return new HashSet<>(ballots.keySet());
    }

    /**
     * Decision state for a proposal.
     */
    public enum DecisionState {
        PENDING,  // Still collecting votes
        APPROVED, // Quorum achieved for yes
        REJECTED  // Rejected (too many no votes)
    }

    /**
     * Vote count summary for a proposal.
     */
    public static class VoteCounts {
        public final int yesVotes;
        public final int noVotes;
        public final int abstainingVotes;

        public VoteCounts(int yesVotes, int noVotes, int abstainingVotes) {
            this.yesVotes = yesVotes;
            this.noVotes = noVotes;
            this.abstainingVotes = abstainingVotes;
        }

        @Override
        public String toString() {
            return "VoteCounts{" + "yes=" + yesVotes + ", no=" + noVotes + ", abstain=" + abstainingVotes + '}';
        }
    }

    /**
     * Internal ballot tracking for a proposal.
     */
    private static class Ballot {
        final String proposalId;
        final String proposalType;
        final ConcurrentHashMap<UUID, Boolean> yesVotes = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, Boolean> noVotes = new ConcurrentHashMap<>();

        Ballot(String proposalId, String proposalType) {
            this.proposalId = proposalId;
            this.proposalType = proposalType;
        }
    }
}
