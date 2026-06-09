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

    /**
     * View-change containment lock (Luciferase-0frcy.95).
     * <p>
     * Serializes the {@code requestConsensus} register-step against
     * {@code rollbackOnViewChange}'s snapshot so a new proposal cannot be inserted
     * into {@link #proposals} after the rollback snapshot was taken yet still belong
     * to the old view. Held only for the O(1) view re-check + put / the snapshot read,
     * never across voting or scheduling.
     */
    private final Object viewLock = new Object();

    /**
     * Most recent view observed via {@link #rollbackOnViewChange}. {@code null} until
     * the first view change. Read under {@link #viewLock} on the register path so a
     * proposal whose viewId no longer matches the current view is rejected at the
     * point of {@code proposals.put} rather than escaping into the old view's tally.
     */
    private volatile Digest currentView;

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

        // View-change containment (Luciferase-0frcy.95): re-check the view and insert
        // under viewLock so this put cannot race past a concurrent rollback snapshot.
        // If a view change has already been recorded and this proposal belongs to an
        // older view, reject it immediately rather than letting it be voted on in a
        // stale context (the snapshot in rollbackOnViewChange would have missed it).
        // Zombie-future containment (Luciferase-0frcy.C1): the view re-check, the
        // proposals.put, the committee registration, AND the capture of the ballot-box
        // result future must all be atomic with respect to rollbackOnViewChange (which
        // snapshots + clears under the same viewLock). If registerCommittee/getResult ran
        // outside the lock, a concurrent rollback could complete+clear this proposal in
        // the gap, and the subsequent getResult would computeIfAbsent a fresh, never-
        // completing VoteState — a zombie future that blocks the caller forever. Holding
        // the lock through getResult guarantees either (a) we capture the real (possibly
        // later exceptionally-completed) future before any rollback can interleave, or
        // (b) the rollback ran first, recorded the new view, and we reject below.
        final CompletableFuture<Boolean> resultFuture;
        synchronized (viewLock) {
            if (currentView != null && !currentView.equals(proposal.viewId())) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Proposal " + proposal.proposalId() + " rejected: viewId " + proposal.viewId()
                    + " does not match current view " + currentView));
            }
            proposals.put(proposal.proposalId(), state);

            // Bound quorum by the committee that can actually vote, not the full cluster
            // (Luciferase-ltxta). Without this the ballot box derives quorum from the
            // full-cluster context and no committee-sized tally can ever reach it.
            ballotBox.registerCommittee(proposal.proposalId(), committee.size());

            // Capture the real result future atomically under the lock so a concurrent
            // rollback cannot interleave between the put and this read.
            resultFuture = ballotBox.getResult(proposal.proposalId());
        }

        // Schedule timeout handler (outside the lock — never hold viewLock across the
        // scheduler call). If a rollback already settled the proposal, handleTimeout
        // finds no state and is a no-op.
        var timeoutFuture = scheduler.schedule(
            () -> handleTimeout(proposal.proposalId()),
            config.votingTimeoutSeconds(),
            TimeUnit.SECONDS
        );
        state.timeoutTask = timeoutFuture;

        return resultFuture;
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

        // yagnw.1: Verify the voter is a current-view ACTIVE member. selectCommittee draws the
        // committee from context.bftSubset, which walks the full ring — an evicted-but-not-GC'd
        // member can still sit in the committee set. S6 tightened the source/target identity gate
        // (isNodeInView) to active-only; this gate stops such a member from contributing a vote
        // toward quorum. Fails closed: an offline committee member's vote is dropped, never counted.
        //
        // Liveness tradeoff (known, by design): the quorum denominator is fixed at committee
        // formation time from committee.size() (which includes offline members); this guard drops
        // their votes but does NOT shrink the threshold. So if more than floor(n/2) of the selected
        // committee are simultaneously offline, no set of active votes can reach quorum and the
        // proposal times out (then retries in the next view). This is the conservative choice: it
        // preserves a genuine majority-of-the-original-committee requirement (safety) at the cost of
        // liveness under heavy churn, rather than shrinking the committee toward the unsafe
        // committeeQuorum(2)==1 floor.
        //
        // 0frcy.134 (decided): active-only committee SELECTION (bftSubset(viewDiadem, isActive)) was
        // investigated as the alternative and REJECTED — context.isActive is failure-detector state
        // set by per-node round timers (View.gc → context.offline), not synchronized at the view
        // boundary, so two nodes could select different committees (split-brain quorum) and the
        // committee could shrink below the BFT-safe size. Deterministic full-ring selection + this
        // vote-receipt drop is the correct design; the liveness-under-churn stall is the safe failure
        // mode (stall-and-retry, never decide under-quorum).
        if (!context.isActive(vote.voterId())) {
            // Vote from an inactive (evicted-but-not-GC'd) committee member (ignore)
            return;
        }

        // Verify view ID matches proposal
        if (!vote.viewId().equals(state.proposal.viewId())) {
            // Vote from different view (ignore)
            return;
        }

        // Add vote to ballot box (will complete future if quorum reached)
        ballotBox.addVote(vote.proposalId(), vote);

        // If quorum reached, cancel timeout and free the ProposalState (Luciferase-zwyf2). The
        // normal success path previously left the entry in `proposals` (and its VoteState in the
        // ballot box) forever — only handleTimeout/rollbackOnViewChange removed entries — so every
        // completed proposal leaked under sustained migration load. Mirror handleTimeout's cleanup.
        var result = ballotBox.getResult(vote.proposalId());
        if (result.isDone()) {
            if (state.timeoutTask != null) {
                state.timeoutTask.cancel(false);
            }
            proposals.remove(vote.proposalId());
            ballotBox.clear(vote.proposalId());
        }
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

        // Use getResultIfPresent (NOT getResult) so a proposal already cleared by a
        // concurrent view-change rollback is not resurrected into a zombie VoteState
        // and then completed with a misleading "Voting timeout" outcome
        // (Luciferase-0frcy.92). If the state is gone, the rollback already settled it.
        var result = ballotBox.getResultIfPresent(proposalId);
        if (result.isPresent()) {
            if (!result.get().isDone()) {
                result.get().completeExceptionally(
                    new TimeoutException("Voting timeout after " + config.votingTimeoutSeconds() + " seconds"));
            }
            ballotBox.clear(proposalId);
        }
    }

    /**
     * Rollback pending proposals for old view.
     * <p>
     * Called when view changes. Aborts all pending proposals with different viewId.
     * Virtual Synchrony guarantee: All proposals from old views are atomically rolled back.
     *
     * @param newViewId the new view ID
     */
    public void rollbackOnViewChange(Digest newViewId) {
        // Atomically record the new view and snapshot the proposals to roll back under
        // viewLock (Luciferase-0frcy.95). Recording currentView BEFORE the snapshot,
        // while holding the same lock requestConsensus uses for its put, closes the
        // TOCTOU window: any concurrent requestConsensus either (a) completes its put
        // before this block runs and is therefore in the snapshot, or (b) runs after
        // and observes the new currentView, rejecting a stale-view proposal outright.
        final java.util.List<java.util.Map.Entry<UUID, ProposalState>> proposalsToRollback;
        synchronized (viewLock) {
            currentView = newViewId;
            proposalsToRollback = proposals.entrySet().stream()
                .filter(e -> !e.getValue().proposal.viewId().equals(newViewId))
                .toList();
        }

        // Process rollbacks
        for (var entry : proposalsToRollback) {
            var proposalId = entry.getKey();
            var state = entry.getValue();

            // Mirror handleTimeout: use getResultIfPresent (NOT getResult) so a proposal
            // already cleared by a concurrent quorum-completing vote (recordVote cleanup
            // :173-180 runs WITHOUT viewLock) is not resurrected into a zombie VoteState
            // and then completed exceptionally with a misleading "view change" outcome
            // (Luciferase-0frcy.92 / Luciferase-7wzml.196).
            // If Optional is empty the quorum path already settled+cleared this proposal;
            // skip the abort but still cancel the timeout task and remove from proposals.
            var result = ballotBox.getResultIfPresent(proposalId);
            if (result.isPresent()) {
                if (!result.get().isDone()) {
                    result.get().completeExceptionally(new IllegalStateException(
                        "Proposal aborted due to view change from view " + state.proposal.viewId()));
                }
                ballotBox.clear(proposalId);
            }

            // Cancel scheduled timeout task regardless (safe even if already cancelled)
            if (state.timeoutTask != null) {
                state.timeoutTask.cancel(false);
            }

            // Remove from tracking (safe now that we're not iterating)
            proposals.remove(proposalId);
        }
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
