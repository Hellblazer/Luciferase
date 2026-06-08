/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.ownership;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.List;

/**
 * Deterministic, view-derived spatial ownership function.
 * <p>
 * Maps a spatial key to the cluster member that owns the corresponding region,
 * given the current set of active Fireflies members. The function is a
 * <em>pure function</em> of its inputs: identical inputs on any node produce
 * identical outputs, with no replicated state, no gossip, and no coordinator.
 * Ownership auto-rebalances when the active member set changes (view change).
 * <p>
 * The recommended implementation is <strong>rendezvous / highest-random-weight
 * (HRW) hashing</strong>: for each active member {@code m}, compute a stable
 * 64-bit weight {@code w(key, m)} and return the member with the maximum weight,
 * breaking ties deterministically (e.g. by {@link Digest} natural order) so the
 * result is identical on every node.
 * <p>
 * <b>Security invariant (RDR-005 / B4):</b> {@code activeMembers} must be the
 * <em>active-only</em> set ({@code DynamicContext.active()} /
 * {@code MembershipView.activeMembers()}). Never pass the full all-members set.
 * An evicted-but-not-GC'd member still holds a valid certificate; admitting it
 * via this function would let a removed node be named as an owner.
 */
public interface SpatialOwnershipFunction {

    /**
     * Return the cluster member that owns the region represented by {@code key},
     * given the current active member set.
     * <p>
     * The result is deterministic: for the same {@code key} and {@code activeMembers}
     * (same elements, regardless of list order) every call on every node returns the
     * same {@link Digest}.
     *
     * @param key           the spatial key identifying the region; must not be {@code null}
     * @param activeMembers the current <em>active-only</em> Fireflies member digests;
     *                      must not be {@code null}
     * @return the {@link Digest} of the owning member — always a member present in
     *         {@code activeMembers}
     * @throws IllegalStateException if {@code activeMembers} is empty (fail-loud: no
     *                               owner can be determined)
     * @throws NullPointerException  if {@code key} or {@code activeMembers} is {@code null}
     */
    Digest owner(TetreeKey<?> key, List<Digest> activeMembers);
}
