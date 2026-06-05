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
     * Committee strict-majority quorum bounded by the committee that can actually vote
     * (Luciferase-ltxta, Luciferase-7wzml.195).
     * <p>
     * Formula: {@code n == 2 ? 1 : n/2 + 1}
     * <ul>
     *   <li>n=2: 1  — special-cased owner-vote design (INTENTIONAL: a single owner vote
     *       approves its own migration; TwoNodeIntegrationTest documents this contract)
     *   <li>n=3 (odd):  3/2+1 = 1+1 = 2
     *   <li>n=4 (even): 4/2+1 = 2+1 = 3  (strict majority — eliminates arrival-order race)
     *   <li>n=5 (odd):  5/2+1 = 2+1 = 3
     *   <li>n=6 (even): 6/2+1 = 3+1 = 4
     *   <li>n=7 (odd):  7/2+1 = 3+1 = 4
     *   <li>n=8 (even): 8/2+1 = 4+1 = 5
     * </ul>
     * <p>
     * For ODD n: {@code n/2+1 == (n-1)/2+1} (integer division: same result as prior formula).
     * For EVEN n (n≥4): {@code n/2+1 > (n-1)/2+1} — quorum is now a TRUE strict majority,
     * so a perfect n/2-vs-n/2 split (e.g. {2Y, 2N} for n=4) can NEVER reach quorum from
     * either side. The outcome is deterministically no-decision regardless of vote arrival
     * order — the exact nondeterminism .195 was meant to eliminate.
     * <p>
     * Why the old {@code (n-1)/2+1} formula was wrong for even n:
     * For n=4 it gave quorum=2. A burst of 2 YES votes would reach quorum=2 at 2-0 (before
     * any NO arrives), deciding YES. A burst of 2 NO votes would decide NO. The same vote
     * SET {2Y, 2N} produced different decisions depending purely on arrival order —
     * a consensus violation. The tie-break guard in {@link #completeIfQuorum} does NOT
     * catch this: it only fires when {@code yesCount == noCount} at check-time, which
     * never happens in the 2-0 burst scenario.
     * <p>
     * With the corrected formula (n=4 quorum=3): neither the YES burst nor the NO burst
     * reaches quorum; both orderings ({2Y then 2N} and {2N then 2Y}) produce no-decision
     * → timeout → re-proposal. The tie-break guard is complementary and correct — it
     * prevents an equal-tally from deciding even when quorum is set to a non-strict value
     * by some future configuration, but it is not sufficient on its own for even-n.
     * <p>
     * Package-private for unit testing (QuorumCalculationTest, CommitteeBallotBoxTest).
     */
    static int committeeQuorum(int committeeSize) {
        return committeeSize == 2 ? 1 : (committeeSize / 2 + 1);
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
     * <p>
     * Split-tally determinism (Luciferase-7wzml.195): when yesCount == noCount the method
     * returns without completing — a split tally is not a majority and deterministically
     * yields no decision (fall-through to timeout). With the corrected {@code n/2+1} quorum
     * (see {@link #committeeQuorum}), an even-N split (e.g. {2Y,2N} for n=4) can never
     * reach quorum from either side, so the arrival-order race is eliminated at the quorum
     * level. The tie-break guard here is a complementary safety net — it prevents
     * an equal tally from deciding even under unusual quorum configurations.
     */
    private void completeIfQuorum(UUID proposalId, VoteState state) {
        if (state.result.isDone()) {
            return;  // Already completed
        }

        // Quorum bounded by the committee that can actually vote when registered
        // (Luciferase-ltxta); otherwise fall back to the full-cluster context formula.
        var committeeSize = proposalCommitteeSize.get(proposalId);
        var majority = committeeSize != null
            ? committeeQuorum(committeeSize)
            : (context.size() == 1 ? 1 : context.toleranceLevel() + 1);

        // Tally YES and NO counts directly — no stream needed since Multiset is at most 2-element.
        var yesCount = state.votes.count(Boolean.TRUE);
        var noCount = state.votes.count(Boolean.FALSE);

        // Tie-break rule: a split tally (yesCount == noCount) is NOT a majority.
        // Do NOT complete — fall through to timeout for a deterministic no-decision.
        // This prevents nondeterministic max() results when both sides hit quorum simultaneously.
        if (yesCount == noCount) {
            return;
        }

        // Determine the leading side and whether it has reached quorum.
        if (yesCount > noCount && yesCount >= majority) {
            state.result.complete(Boolean.TRUE);
        } else if (noCount > yesCount && noCount >= majority) {
            state.result.complete(Boolean.FALSE);
        }
        // Otherwise: neither side has a strict majority at quorum — wait for more votes.
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
