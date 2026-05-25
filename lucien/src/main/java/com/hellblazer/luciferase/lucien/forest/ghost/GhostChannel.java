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

package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.concurrent.CompletableFuture;

/**
 * Transport-agnostic channel for batched ghost-element exchange between distributed processes.
 *
 * <p>lucien core ({@link com.hellblazer.luciferase.lucien.AbstractSpatialIndex} and
 * {@link DistributedGhostManager}) depends on this interface rather than on a concrete gRPC
 * transport, so the gRPC implementation ({@link GrpcGhostChannel}) can be moved out of the
 * core module without touching core.
 *
 * @param <Key>     the type of spatial key
 * @param <ID>      the type of entity identifier
 * @param <Content> the type of content stored in entities
 *
 * @author Hal Hildebrand
 */
public interface GhostChannel<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /**
     * Queue a ghost element for batched transmission to a target process.
     *
     * @param targetRank the rank of the target process
     * @param element    the ghost element to send
     */
    void queueGhost(int targetRank, GhostElement<Key, ID, Content> element);

    /**
     * Flush queued ghosts to a specific target process.
     *
     * @param targetRank the rank of the target process
     * @return a future that completes when the flush finishes
     */
    CompletableFuture<Void> flushToTarget(int targetRank);

    /**
     * @return the total number of queued ghosts across all targets
     */
    int getTotalPendingCount();

    /**
     * Clear all queued ghosts without sending them.
     */
    void clear();

    /**
     * @return the rank of this process
     */
    int getCurrentRank();

    /**
     * @return the tree identifier this channel is bound to
     */
    long getTreeId();

    /**
     * @return the type of ghosts this channel transmits
     */
    GhostType getGhostType();
}
