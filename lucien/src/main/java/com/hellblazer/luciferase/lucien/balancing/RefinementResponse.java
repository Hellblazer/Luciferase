/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;

import java.util.List;

/**
 * Pure-domain refinement response for cross-partition balance (RDR-007 Phase 0 Inc3).
 *
 * <p>Replaces the protobuf {@code RefinementResponse} message at the lucien-core boundary.
 * Ghost elements are carried as already-deserialized domain {@link GhostElement} instances; the protobuf
 * {@code GhostElement} deserialization happens in the grpc adapter.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public record RefinementResponse<Key extends SpatialKey<Key>, ID extends EntityID, Content>(
    int requesterRank,
    int responderRank,
    long responderTreeId,
    int roundNumber,
    List<GhostElement<Key, ID, Content>> ghostElements,
    boolean needsFurtherRefinement,
    long timestamp
) {
    public RefinementResponse {
        ghostElements = List.copyOf(ghostElements);
    }

    /**
     * An empty response carrying proto3-default field values. Replaces the proto
     * {@code RefinementResponse.getDefaultInstance()} used as the coordinator's exceptionally-fallback:
     * no ghost elements and {@code needsFurtherRefinement == false}.
     *
     * @return an empty refinement response
     */
    public static <Key extends SpatialKey<Key>, ID extends EntityID, Content>
    RefinementResponse<Key, ID, Content> empty() {
        return new RefinementResponse<>(0, 0, 0L, 0, List.of(), false, 0L);
    }

    /**
     * Returns true iff this response equals the {@link #empty()} sentinel (all fields at proto3 defaults).
     *
     * <p>Used by SIG-1 convergence guard: a round containing any empty response (unreachable peer mapped
     * to {@code empty()} by the adapter) must NOT update {@code lastRoundIndicatedConvergence}, because
     * the empty response's {@code needsFurtherRefinement == false} would otherwise prematurely signal
     * convergence when a peer is simply unreachable.
     *
     * @return true if this is an empty/sentinel response
     */
    public boolean isEmpty() {
        return requesterRank == 0
            && responderRank == 0
            && responderTreeId == 0L
            && roundNumber == 0
            && ghostElements.isEmpty()
            && !needsFurtherRefinement
            && timestamp == 0L;
    }
}
