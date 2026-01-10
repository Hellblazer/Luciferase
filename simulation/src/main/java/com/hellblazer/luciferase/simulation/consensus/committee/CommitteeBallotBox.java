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
import com.hellblazer.delos.membership.Member;

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

    public CommitteeBallotBox(DynamicContext<Member> context) {
        this.context = context;
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
            // Add vote to multiset (YES or NO)
            state.votes.add(vote.approved());

            // Check if quorum reached (KerlDHT pattern)
            completeIfQuorum(state);
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
     * Clear all vote state for a proposal.
     * <p>
     * Used after decision is made to clean up memory.
     *
     * @param proposalId which proposal
     */
    public void clear(UUID proposalId) {
        proposals.remove(proposalId);
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
    private void completeIfQuorum(VoteState state) {
        if (state.result.isDone()) {
            return;  // Already completed
        }

        // Find the vote option (YES or NO) with the most votes
        var max = state.votes.entrySet()
                             .stream()
                             .max(Ordering.natural().onResultOf(Multiset.Entry::getCount))
                             .orElse(null);

        // Calculate quorum using KerlDHT formula
        var majority = context.size() == 1 ? 1 : context.toleranceLevel() + 1;

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
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
    }
}
