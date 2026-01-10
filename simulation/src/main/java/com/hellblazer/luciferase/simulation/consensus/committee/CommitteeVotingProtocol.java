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
import com.hellblazer.delos.membership.Member;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Committee voting protocol FSM for proposal consensus.
 * <p>
 * States:
 * - PROPOSAL_PENDING: Waiting for quorum (initial state)
 * - QUORUM_ACHIEVED: Consensus reached (terminal state)
 * - TIMEOUT_EXPIRED: Voting deadline exceeded (terminal state)
 * - ROLLBACK_DUE_TO_VIEW_CHANGE: View changed, abort pending proposals (terminal state)
 * <p>
 * Thread-safe: ConcurrentHashMap for proposal tracking, CommitteeBallotBox handles vote aggregation.
 * <p>
 * Phase 7G Day 2: Voting Protocol & Ballot Box
 *
 * @author hal.hildebrand
 */
public class CommitteeVotingProtocol {

    private final DynamicContext<Member> context;
    private final CommitteeConfig config;
    private final ScheduledExecutorService scheduler;
    private final CommitteeBallotBox ballotBox;
    private final ConcurrentHashMap<UUID, ProposalState> proposals = new ConcurrentHashMap<>();

    public CommitteeVotingProtocol(DynamicContext<Member> context, CommitteeConfig config,
                                   ScheduledExecutorService scheduler) {
        this.context = context;
        this.config = config;
        this.scheduler = scheduler;
        this.ballotBox = new CommitteeBallotBox(context);
    }

    /**
     * Request consensus for a migration proposal.
     * <p>
     * Returns a future that completes when:
     * - Quorum reached (YES or NO majority) → true/false
     * - Timeout expires → TimeoutException
     * - View change → IllegalStateException
     *
     * @param proposal  the migration proposal
     * @param committee set of committee member IDs
     * @return future that completes with consensus result (true=approved, false=rejected)
     */
    public CompletableFuture<Boolean> requestConsensus(MigrationProposal proposal, Set<Digest> committee) {
        var state = new ProposalState(proposal, committee);
        proposals.put(proposal.proposalId(), state);

        // Schedule timeout handler
        var timeoutFuture = scheduler.schedule(
            () -> handleTimeout(proposal.proposalId()),
            config.votingTimeoutSeconds(),
            TimeUnit.SECONDS
        );
        state.timeoutTask = timeoutFuture;

        // Return the ballot box result future
        return ballotBox.getResult(proposal.proposalId());
    }

    /**
     * Record a vote from a committee member.
     * <p>
     * When quorum is reached, the ballot box automatically completes the result future.
     *
     * @param vote the committee vote
     */
    public void recordVote(Vote vote) {
        var state = proposals.get(vote.proposalId());
        if (state == null) {
            // Vote for unknown proposal (possibly already completed/timed out)
            return;
        }

        // Verify vote is from committee member
        if (!state.committee.contains(vote.voterId())) {
            // Vote from non-committee member (ignore)
            return;
        }

        // Verify view ID matches proposal
        if (!vote.viewId().equals(state.proposal.viewId())) {
            // Vote from different view (ignore)
            return;
        }

        // Add vote to ballot box (will complete future if quorum reached)
        ballotBox.addVote(vote.proposalId(), vote);

        // If quorum reached, cancel timeout
        var result = ballotBox.getResult(vote.proposalId());
        if (result.isDone() && state.timeoutTask != null) {
            state.timeoutTask.cancel(false);
        }
    }

    /**
     * Check if quorum is reached for a given vote count.
     * <p>
     * Uses KerlDHT formula: context.size() == 1 ? 1 : context.toleranceLevel() + 1
     *
     * @param voteCount number of votes
     * @return true if quorum reached
     */
    public boolean isQuorumReached(long voteCount) {
        var majority = context.size() == 1 ? 1 : context.toleranceLevel() + 1;
        return voteCount >= majority;
    }

    /**
     * Handle timeout: voting deadline exceeded without reaching quorum.
     * <p>
     * Completes the result future with TimeoutException.
     *
     * @param proposalId which proposal timed out
     */
    public void handleTimeout(UUID proposalId) {
        var state = proposals.remove(proposalId);
        if (state == null) {
            return;  // Already completed or rolled back
        }

        var result = ballotBox.getResult(proposalId);
        if (!result.isDone()) {
            result.completeExceptionally(new TimeoutException("Voting timeout after " + config.votingTimeoutSeconds() + " seconds"));
        }

        ballotBox.clear(proposalId);
    }

    /**
     * Rollback pending proposals for old view.
     * <p>
     * Called when view changes. Aborts all pending proposals with different viewId.
     *
     * @param newViewId the new view ID
     */
    public void rollbackOnViewChange(Digest newViewId) {
        proposals.forEach((proposalId, state) -> {
            if (!state.proposal.viewId().equals(newViewId)) {
                // This proposal is from old view, abort it
                var result = ballotBox.getResult(proposalId);
                if (!result.isDone()) {
                    result.completeExceptionally(new IllegalStateException("Proposal aborted due to view change"));
                }

                // Cancel timeout task
                if (state.timeoutTask != null) {
                    state.timeoutTask.cancel(false);
                }

                // Clean up
                proposals.remove(proposalId);
                ballotBox.clear(proposalId);
            }
        });
    }

    /**
     * Internal state for a single proposal.
     */
    private static class ProposalState {
        final MigrationProposal proposal;
        final Set<Digest> committee;
        ScheduledFuture<?> timeoutTask;

        ProposalState(MigrationProposal proposal, Set<Digest> committee) {
            this.proposal = proposal;
            this.committee = committee;
        }
    }
}
