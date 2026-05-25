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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Lucien-core port for requesting cross-partition refinement (RDR-007 Phase 0 Inc3).
 *
 * <p>Inverts the dependency from the concrete grpc {@code BalanceCoordinatorClient}: core balancing logic
 * depends on this domain interface, and the grpc adapter implements it, converting between domain types
 * and proto on the wire. Split from {@link ViolationExchange} so the violation-aggregation path (which
 * needs only {@code Key}) is not forced to carry {@code ID}/{@code Content} type parameters.
 *
 * @param <Key> the spatial key type (MortonKey, TetreeKey, etc.)
 * @param <ID> the entity ID type
 * @param <Content> the content type stored with entities
 * @author hal.hildebrand
 */
public interface RefinementExchange<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /**
     * Request refinement from a remote partition asynchronously.
     *
     * @param targetRank the rank of the target partition
     * @param treeId the tree ID to request refinement for
     * @param roundNumber the balance round number
     * @param treeLevel the tree level requiring refinement
     * @param boundaryKeys the domain boundary keys to refine (may be empty)
     * @return a future completing with the domain refinement response
     */
    CompletableFuture<RefinementResponse<Key, ID, Content>> requestRefinementAsync(
        int targetRank, long treeId, int roundNumber, int treeLevel, List<Key> boundaryKeys);
}
