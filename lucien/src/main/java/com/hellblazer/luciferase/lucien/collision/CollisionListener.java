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
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.SpatialIndex.CollisionPair;
import com.hellblazer.luciferase.lucien.entity.EntityID;

/**
 * Interface for handling collision events.
 * Implement this interface to receive collision notifications.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * 
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface CollisionListener<ID extends EntityID, Content> {
    
    /**
     * Called when a collision is detected between two entities.
     * 
     * @param collision the collision pair with all collision details
     * @param response the calculated collision response (may be modified)
     * @return true to apply the response, false to ignore this collision
     */
    boolean onCollision(CollisionPair<ID, Content> collision, CollisionResponse response);
    
    /**
     * Default listener that accepts all collisions
     */
    static <ID extends EntityID, Content> CollisionListener<ID, Content> acceptAll() {
        return (collision, response) -> true;
    }
    
    /**
     * Create a filtered listener that only processes collisions matching a predicate
     */
    static <ID extends EntityID, Content> CollisionListener<ID, Content> filtered(
            CollisionListener<ID, Content> listener,
            CollisionFilter<ID, Content> filter) {
        return (collision, response) -> {
            if (filter.shouldProcess(collision)) {
                return listener.onCollision(collision, response);
            }
            return false;
        };
    }
}