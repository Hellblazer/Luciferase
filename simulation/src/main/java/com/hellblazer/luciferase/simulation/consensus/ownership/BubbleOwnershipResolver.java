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

import java.util.UUID;

/**
 * Resolves bubble and node identities to Fireflies cluster member {@link Digest}s.
 * <p>
 * This is the node-identity boundary layer for migration consensus (RDR-020).
 * Migration proposals carry two distinct node roles, and both are resolved here:
 * <ul>
 *   <li><b>Source node = local member (possession).</b> The process that initiates a migration
 *       physically holds the entity/bubble, so the source is authoritative by possession.
 *       Obtained via {@link #localMember()}.</li>
 *   <li><b>Target node = HRW owner of the destination region.</b> The intended owner of the
 *       destination bubble's spatial key, computed via a {@link SpatialOwnershipFunction}.
 *       Obtained via {@link #resolveOwningMember(UUID)}.</li>
 * </ul>
 * <p>
 * All methods fail loud ({@link IllegalStateException}) rather than returning a silently-rejectable
 * {@link Digest}. The consensus layer ({@code ViewCommitteeConsensus.validateProposal}) expects
 * real, current-view member digests; returning an unresolvable digest would produce a proposal
 * that is silently rejected at the validity gate, masking the identity gap (RDR-020 Gap 2).
 * <p>
 * <b>Security invariant (RDR-005):</b> implementations must use the <em>active-only</em>
 * ({@code DynamicContext.active()}) member set for the ownership computation, never the
 * all-members backing. See {@code MembershipView.activeMembers()} javadoc.
 */
public interface BubbleOwnershipResolver {

    /**
     * Resolve the current-view Fireflies member that owns the bubble identified by
     * {@code bubbleId}.
     * <p>
     * Resolution steps:
     * <ol>
     *   <li>Resolve {@code bubbleId} → {@link com.hellblazer.luciferase.lucien.tetree.TetreeKey}
     *       via the bubble grid (fail loud if not found).</li>
     *   <li>Read the <em>active-only</em> member set.</li>
     *   <li>Delegate to {@link SpatialOwnershipFunction#owner(com.hellblazer.luciferase.lucien.tetree.TetreeKey, java.util.List)}.</li>
     *   <li>Verify the result is a current-view member (fail loud if not).</li>
     * </ol>
     *
     * @param bubbleId the bubble's UUID; must not be {@code null}
     * @return the owning member's {@link Digest}; never {@code null}
     * @throws IllegalStateException if the bubble is not in the grid, if the active
     *                               member set is empty, or if the resolved owner is not
     *                               a current-view member
     */
    Digest resolveOwningMember(UUID bubbleId);

    /**
     * Return this process's own Fireflies member {@link Digest}.
     * <p>
     * Always the source node for any migration this process initiates, because this
     * process physically holds the source bubble (possession semantics).
     *
     * @return this node's member {@link Digest}; never {@code null}
     */
    Digest localMember();

    /**
     * Resolve a canonical node {@link UUID} to its Fireflies member {@link Digest}.
     * <p>
     * Node UUIDs are derived canonically from their member digest via
     * {@code FirefliesMemberLookup.digestToUuid} / {@code NodeBootstrap.resolveNodeId} (B4).
     * Use this method only for validating an explicit node-UUID hint (e.g. from
     * {@code DistributedBubbleNode.initiateRemoteMigration}); not for the ownership path
     * (use {@link #resolveOwningMember(UUID)} for that).
     *
     * @param nodeId the canonical node UUID; must not be {@code null}
     * @return the member's {@link Digest}; never {@code null}
     * @throws IllegalStateException if no member with this node UUID is found
     */
    Digest memberDigestForNode(UUID nodeId);
}
