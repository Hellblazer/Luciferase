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

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Ordering;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Committee ballot box for vote aggregation using KerlDHT pattern.
 * <p>
 * CRITICAL: This class implements the exact same quorum pattern as KerlDHT:
 * <pre>
 * var majority = context.size() == 1 ? 1 : context.toleranceLevel() + 1;
 * </pre>
 * <p>
 * Reference: /Users/hal.hildebrand/git/Delos/thoth/src/main/java/.../KerlDHT.java lines 805-834
 * <p>
 * Byzantine Fault Tolerance:
 * - Uses HashMultiset for vote collection (YES/NO counts)
 * - Finds max vote count (YES or NO)
 * - Completes future when max count >= quorum
 * - Thread-safe: ConcurrentHashMap for proposal tracking
 * <p>
 * Phase 7G Day 2: Voting Protocol & Ballot Box
 *
 * @author hal.hildebrand
 */
public class CommitteeBallotBox {

    private final DynamicContext<Member> context;
    private final ConcurrentHashMap<UUID, VoteState> proposals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer>   proposalCommitteeSize = new ConcurrentHashMap<>();

    public CommitteeBallotBox(DynamicContext<Member> context) {
        this.context = context;
    }

    /**
     * Register the size of the committee that may vote on {@code proposalId}.
     * <p>
     * Quorum must be bounded by the number of members who can actually vote. The BFT
     * committee is a small subset (typically 7–9) drawn from the full cluster, so
     * deriving quorum from the full-cluster {@link DynamicContext} produced a threshold
     * (e.g. 34 for a 100-node cluster) that the committee could never reach — every
     * migration silently timed out (Luciferase-ltxta). When a committee size is
     * registered for a proposal, {@link #completeIfQuorum} uses the committee-relative
     * BFT formula instead of the full-cluster context. Absent a registration the legacy
     * context-based formula is retained (used by mock-driven unit tests).
     *
     * @param proposalId    the proposal whose committee size is being declared
     * @param committeeSize number of distinct members eligible to vote
     */
    public void registerCommittee(UUID proposalId, int committeeSize) {
        if (committeeSize > 0) {
            proposalCommitteeSize.put(proposalId, committeeSize);
        }
    }

    /**
     * Add a vote for a proposal.
     * <p>
     * When quorum is reached (using KerlDHT formula), the result future completes.
     *
     * @param proposalId which proposal
     * @param vote       the vote (YES or NO)
     */
    public void addVote(UUID proposalId, Vote vote) {
        var state = proposals.computeIfAbsent(proposalId, id -> new VoteState());

        synchronized (state) {
            // Voter-identity deduplication: each committee member contributes
            // exactly one vote. Reject (silently) any repeat vote from a voter
            // that has already been counted for this proposal. Without this guard
            // a single Byzantine member could submit the same vote N times and
            // drive the tally to quorum unilaterally, breaking the BFT guarantee
            // (quorum = toleranceLevel()+1 is only sound under one-vote-per-member).
            if (!state.seenVoters.add(vote.voterId())) {
                return;
            }

            // Add vote to multiset (YES or NO)
            state.votes.add(vote.approved());

            // Check if quorum reached (KerlDHT pattern)
            completeIfQuorum(proposalId, state);
        }
    }

    /**
     * Get the result future for a proposal.
     * <p>
     * The future completes when quorum is reached (YES or NO majority).
     *
     * @param proposalId which proposal
     * @return future that completes with true (YES majority) or false (NO majority)
     */
    public CompletableFuture<Boolean> getResult(UUID proposalId) {
        return proposals.computeIfAbsent(proposalId, id -> new VoteState()).result;
    }

    /**
     * Get the result future for a proposal WITHOUT creating a new entry.
     * <p>
     * Unlike {@link #getResult(UUID)}, this never resurrects state for a proposal
     * that has already been {@link #clear(UUID) cleared} (e.g. by a concurrent
     * view-change rollback). Callers on the abort path — such as timeout handling —
     * must use this to avoid creating a "zombie" {@code VoteState} and then
     * completing it with a misleading outcome (Luciferase-0frcy.92).
     *
     * @param proposalId which proposal
     * @return the existing result future, or empty if no state is tracked
     */
    public Optional<CompletableFuture<Boolean>> getResultIfPresent(UUID proposalId) {
        var state = proposals.get(proposalId);
        return state == null ? Optional.empty() : Optional.of(state.result);
    }

    /**
     * Clear all vote state for a proposal.
     * <p>
     * Used after decision is made to clean up memory.
     *
     * @param proposalId which proposal
     */
    public void clear(UUID proposalId) {
        proposals.remove(proposalId);
        proposalCommitteeSize.remove(proposalId);
    }

    /**
     * Committee simple-majority quorum bounded by the committee that can actually vote
     * (Luciferase-ltxta).
     * <p>
     * The formula is {@code floor((n-1)/2) + 1} — a <em>committee simple-majority quorum</em>,
     * NOT a Byzantine (n/3) quorum. It tolerates {@code floor((n-1)/2)} crash faults and,
     * combined with the per-voter deduplication in {@link #addVote} (one vote per member),
     * prevents single-voter ballot stuffing. It does <em>not</em> by itself tolerate
     * {@code floor((n-1)/3)} equivocating Byzantine voters; raising the threshold to the
     * BFT {@code (n-1)/3 + 1} form is deliberately out of scope here (would require separate
     * fault-model analysis). Safety is not weakened relative to the previous wording — this
     * quorum is {@code >=} the BFT minimum — only the "BFT formula" label was inaccurate.
     * <p>
     * It coincides numerically with the project's {@code toleranceLevel()+1} convention
     * because DynamicContext.toleranceLevel() in this codebase is {@code floor((n-1)/2)}
     * (see QuorumCalculationTest's size→tolerance table: 7→3, 9→4).
     */
    private static int committeeQuorum(int committeeSize) {
        return committeeSize <= 1 ? 1 : ((committeeSize - 1) / 2) + 1;
    }

    /**
     * CRITICAL: KerlDHT pattern for quorum checking.
     * <p>
     * Reference: KerlDHT.java lines 805-834
     * <pre>
     * var max = gathered.entrySet().stream()
     *     .max(Ordering.natural().onResultOf(Multiset.Entry::getCount))
     *     .orElse(null);
     * var majority = context.size() == 1 ? 1 : context.toleranceLevel() + 1;
     * if (max != null && max.getCount() >= majority) {
     *     result.complete(max.getElement());
     * }
     * </pre>
     */
    private void completeIfQuorum(UUID proposalId, VoteState state) {
        if (state.result.isDone()) {
            return;  // Already completed
        }

        // Find the vote option (YES or NO) with the most votes
        var max = state.votes.entrySet()
                             .stream()
                             .max(Ordering.natural().onResultOf(Multiset.Entry::getCount))
                             .orElse(null);

        // Quorum bounded by the committee that can actually vote when registered
        // (Luciferase-ltxta); otherwise fall back to the full-cluster context formula.
        var committeeSize = proposalCommitteeSize.get(proposalId);
        var majority = committeeSize != null
            ? committeeQuorum(committeeSize)
            : (context.size() == 1 ? 1 : context.toleranceLevel() + 1);

        // If max vote count >= quorum, complete with that vote option
        if (max != null && max.getCount() >= majority) {
            state.result.complete(max.getElement());
        }
    }

    /**
     * Internal state for a single proposal.
     * <p>
     * Thread-safety: synchronized access in addVote()
     */
    private static class VoteState {
        final HashMultiset<Boolean> votes = HashMultiset.create();
        final Set<Digest>           seenVoters = new HashSet<>();
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
    }
}
