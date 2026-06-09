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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic test double for {@link BubbleOwnershipResolver}.
 * <p>
 * Seeded at construction time with:
 * <ul>
 *   <li>A fixed {@code bubbleId → TetreeKey} map (simulating the bubble grid)</li>
 *   <li>A fixed active member {@link Digest} list</li>
 *   <li>A designated local member {@link Digest}</li>
 *   <li>A fixed {@code nodeId → Digest} map (for {@link #memberDigestForNode})</li>
 * </ul>
 * Drives deterministic unit tests without any real Fireflies infrastructure.
 * Uses a {@link RendezvousOwnershipFunction} internally so HRW convergence properties
 * are exercised through the resolver as well.
 * <p>
 * <b>Note on active/all distinction:</b> this test double uses the same set for both
 * active members and all members because tests using it do not exercise that distinction.
 * This equivalence is intentional and documented per the {@code MembershipView.activeMembers()}
 * contract.
 */
public final class StubBubbleOwnershipResolver implements BubbleOwnershipResolver {

    private static final SpatialOwnershipFunction HRW = new RendezvousOwnershipFunction();

    private final Map<UUID, TetreeKey<?>> bubbleGrid;
    private final List<Digest> activeMembers;
    private final Digest localMember;
    private final Map<UUID, Digest> nodeIdToDigest;

    /**
     * Construct a seeded test double.
     *
     * @param bubbleGrid      fixed {@code bubbleId → TetreeKey} mapping; must not be {@code null}
     * @param activeMembers   fixed active member digests; must not be {@code null}
     * @param localMember     this process's own member {@link Digest}; must not be {@code null}
     * @param nodeIdToDigest  fixed {@code nodeId → Digest} mapping; must not be {@code null}
     */
    public StubBubbleOwnershipResolver(Map<UUID, TetreeKey<?>> bubbleGrid,
                                       List<Digest> activeMembers,
                                       Digest localMember,
                                       Map<UUID, Digest> nodeIdToDigest) {
        this.bubbleGrid = Map.copyOf(Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null"));
        this.activeMembers = List.copyOf(Objects.requireNonNull(activeMembers, "activeMembers must not be null"));
        this.localMember = Objects.requireNonNull(localMember, "localMember must not be null");
        this.nodeIdToDigest = Map.copyOf(Objects.requireNonNull(nodeIdToDigest, "nodeIdToDigest must not be null"));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Resolves via the seeded bubble grid then delegates to the HRW function over the
     * seeded active member set.
     */
    @Override
    public Digest resolveOwningMember(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "bubbleId must not be null");
        var key = bubbleGrid.get(bubbleId);
        if (key == null) {
            throw new IllegalStateException(
                "Bubble not in grid (test double): " + bubbleId);
        }
        // activeMembers list may be empty → HRW throws with a clear message
        return HRW.owner(key, activeMembers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Digest localMember() {
        return localMember;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Digest memberDigestForNode(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        var digest = nodeIdToDigest.get(nodeId);
        if (digest == null) {
            throw new IllegalStateException(
                "No member found for node UUID (test double): " + nodeId);
        }
        return digest;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Tests membership against the seeded active member list (this double uses the same set for
     * active and all members — see the class note).
     */
    @Override
    public boolean isActiveMember(Digest member) {
        Objects.requireNonNull(member, "member must not be null");
        return activeMembers.contains(member);
    }
}
