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

import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Interface for filtering which collisions should be processed.
 * Useful for implementing collision layers, groups, or masks.
 *
 * @param <ID> The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * 
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface CollisionFilter<ID extends EntityID, Content> {
    
    /**
     * Determine if a collision should be processed
     *
     * @param collision the collision pair to evaluate
     * @return true if the collision should be processed, false to ignore it
     */
    boolean shouldProcess(CollisionPair<ID, Content> collision);
    
    /**
     * Create a filter that processes all collisions
     */
    static <ID extends EntityID, Content> CollisionFilter<ID, Content> all() {
        return collision -> true;
    }
    
    /**
     * Create a filter that processes no collisions
     */
    static <ID extends EntityID, Content> CollisionFilter<ID, Content> none() {
        return collision -> false;
    }
    
    /**
     * Create a filter based on entity IDs
     */
    static <ID extends EntityID, Content> CollisionFilter<ID, Content> byEntityIds(
            BiPredicate<ID, ID> predicate) {
        return collision -> predicate.test(collision.entityId1(), collision.entityId2());
    }
    
    /**
     * Create a filter that only processes collisions involving specific entities
     */
    static <ID extends EntityID, Content> CollisionFilter<ID, Content> involving(Set<ID> entityIds) {
        return collision -> entityIds.contains(collision.entityId1()) || 
                           entityIds.contains(collision.entityId2());
    }
    
    /**
     * Create a filter that excludes collisions between specific entities
     */
    static <ID extends EntityID, Content> CollisionFilter<ID, Content> excluding(Set<ID> entityIds) {
        return collision -> !(entityIds.contains(collision.entityId1()) && 
                             entityIds.contains(collision.entityId2()));
    }
    
    /**
     * Create a filter based on content type
     */
    static <ID extends EntityID, Content> CollisionFilter<ID, Content> byContentType(
            Class<? extends Content> type) {
        return collision -> type.isInstance(collision.content1()) || 
                           type.isInstance(collision.content2());
    }
    
    /**
     * Combine two filters with AND logic
     */
    default CollisionFilter<ID, Content> and(CollisionFilter<ID, Content> other) {
        return collision -> this.shouldProcess(collision) && other.shouldProcess(collision);
    }
    
    /**
     * Combine two filters with OR logic
     */
    default CollisionFilter<ID, Content> or(CollisionFilter<ID, Content> other) {
        return collision -> this.shouldProcess(collision) || other.shouldProcess(collision);
    }
    
    /**
     * Negate this filter
     */
    default CollisionFilter<ID, Content> negate() {
        return collision -> !this.shouldProcess(collision);
    }
}