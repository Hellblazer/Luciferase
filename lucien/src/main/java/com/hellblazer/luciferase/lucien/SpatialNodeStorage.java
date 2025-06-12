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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Collection;

/**
 * Common interface for spatial index nodes that store entity IDs. This interface abstracts the storage mechanism for
 * both Octree and Tetree nodes.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @author hal.hildebrand
 */
public interface SpatialNodeStorage<ID extends EntityID> {

    /**
     * Add an entity ID to this node
     *
     * @param entityId the entity ID to add
     * @return true if the node should be split (exceeds threshold)
     */
    boolean addEntity(ID entityId);

    /**
     * Clear all entities from this node
     */
    void clearEntities();

    /**
     * Check if this node contains a specific entity
     *
     * @param entityId the entity ID to check
     * @return true if the entity is in this node
     */
    boolean containsEntity(ID entityId);

    /**
     * Get the number of entities in this node
     *
     * @return entity count
     */
    int getEntityCount();

    /**
     * Get all entity IDs in this node
     *
     * @return collection of entity IDs (unmodifiable)
     */
    Collection<ID> getEntityIds();

    /**
     * Check if this node is empty (no entities)
     *
     * @return true if no entities are stored
     */
    boolean isEmpty();

    /**
     * Remove an entity ID from this node
     *
     * @param entityId the entity ID to remove
     * @return true if the entity was found and removed
     */
    boolean removeEntity(ID entityId);

    /**
     * Check if this node should be split based on entity count
     *
     * @return true if the node exceeds the split threshold
     */
    boolean shouldSplit();
}
