/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

// SPDX-License-Identifier: AGPL-3.0-or-later

package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.List;

/**
 * Transport-agnostic interface for synchronous ghost-element requests between distributed processes.
 *
 * <p>lucien core depends on this interface rather than on a concrete gRPC transport, so the gRPC
 * implementation ({@code GhostServiceClient}) can be moved out of the core module without touching core code.
 *
 * <p>Returns domain {@link GhostElement} objects rather than proto types, keeping the core module
 * free of protobuf dependencies. Mirrors the pattern established by {@link GhostChannel} for
 * batched push-style ghost exchange.
 *
 * @param <Key>     the type of spatial key
 * @param <ID>      the type of entity identifier
 * @param <Content> the type of content stored in entities
 *
 * @author Hal Hildebrand
 */
public interface GhostExchange<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /**
     * Synchronously request ghost elements from a remote process.
     *
     * @param targetRank   the rank of the target process
     * @param treeId       the tree ID to request ghosts for
     * @param ghostType    the type of ghosts to request
     * @param boundaryKeys specific boundary keys to request
     * @return list of ghost elements, or {@code null} if the request fails
     */
    List<GhostElement<Key, ID, Content>> requestGhostElements(
            int targetRank, long treeId, GhostType ghostType, List<Key> boundaryKeys);
}
