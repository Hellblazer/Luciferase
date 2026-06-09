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

import java.util.Objects;
import java.util.SequencedSet;

/**
 * Deterministic committee selector using Fireflies view rings.
 * <p>
 * Wraps `context.bftSubset(viewDiadem)` to provide consistent committee selection
 * for a given view ID across all nodes in the cluster. The committee is determined
 * by the Fireflies ring topology and guarantees Byzantine fault tolerance.
 * <p>
 * Phase 7G Day 1: Committee Selector & Data Structures
 * <p>
 * DESIGN DECISIONS:
 * - Deterministic: Same view ID always produces same committee on all nodes
 * - BFT Safe: Uses context.bftSubset() which respects context.toleranceLevel()
 * - No Random Selection: Committee is derived from view ring topology
 *
 * @author hal.hildebrand
 */
public class ViewCommitteeSelector {

    private final DynamicContext<? extends Member> context;

    /**
     * Create a new ViewCommitteeSelector.
     *
     * @param context Delos DynamicContext with ring topology
     */
    public ViewCommitteeSelector(DynamicContext<? extends Member> context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    /**
     * Select committee for a given view.
     * <p>
     * Returns a deterministic committee based on the view's diadem (view ID).
     * The committee is selected using context.bftSubset() which:
     * - Uses Fireflies ring successors for the view diadem
     * - Guarantees BFT quorum (context.toleranceLevel() + 1)
     * - Is deterministic (same view ID → same committee on all nodes)
     *
     * @param viewDiadem View identifier (diadem from Fireflies view)
     * @return BFT committee members for this view
     */
    public SequencedSet<? extends Member> selectCommittee(Digest viewDiadem) {
        Objects.requireNonNull(viewDiadem, "viewDiadem must not be null");
        return context.bftSubset(viewDiadem);
    }

    /**
     * Check if a node is an <em>active</em> member of the current view.
     * <p>
     * Used for Byzantine input validation — verify the target/source node is a current-view active
     * member before admitting a migration proposal.
     * <p>
     * RDR-020 S6 (RDR-005 active-only invariant): this matches against {@code context.active()}, NOT
     * {@code context.allMembers()}. The all-members backing can still contain an evicted member that
     * has not yet been garbage-collected from the view; admitting it would let a proposal target a node
     * that is no longer participating. The resolver enforces active-only on the ownership side
     * ({@code MembershipView.activeMembers()}); this gate enforces the same invariant for the proposal's
     * <b>source/target node identity</b> at the validator.
     * <p>
     * Scope: this gate covers the source/target membership check only. Committee <em>composition</em>
     * ({@link #selectCommittee} → {@code context.bftSubset}) deliberately draws from the full ring,
     * NOT an active-only subset: committee selection must be deterministic across nodes (same view →
     * same committee), but {@code context.active()}/{@code isActive} is failure-detector state set by
     * per-node round timers ({@code View.gc} → {@code context.offline}), not synchronized at the view
     * boundary — so an active-only walk could yield different committees on different nodes (split-brain
     * quorum) and could under-size the committee below the BFT-safe threshold. The offline-member-as-
     * voter case is instead handled at vote-receipt in {@code CommitteeVotingProtocol.recordVote}
     * (drops inactive votes; the cluster stalls-and-retries rather than deciding under-quorum). This
     * was investigated and decided under Luciferase-0frcy.134 (active-only selection rejected as
     * unsound).
     *
     * @param nodeId Node identifier (member ID) to check
     * @return true if the node is a current-view active member, false otherwise
     */
    public boolean isNodeInView(Digest nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return context.active()
                      .anyMatch(member -> member.getId().equals(nodeId));
    }
}
