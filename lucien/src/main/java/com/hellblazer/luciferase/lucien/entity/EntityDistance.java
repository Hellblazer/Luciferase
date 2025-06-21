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
package com.hellblazer.luciferase.lucien.entity;

/**
 * Represents an entity and its distance from a query point. Used primarily in k-nearest neighbor searches and proximity
 * queries.
 *
 * @param <ID>     The type of EntityID used for entity identification
 * @param entityId The ID of the entity
 * @param distance The distance from the query point to the entity
 * @author hal.hildebrand
 */
public record EntityDistance<ID extends EntityID>(ID entityId, float distance)
implements Comparable<EntityDistance<ID>> {

    /**
     * Create a comparator for max heap (descending order) Used in k-NN searches to maintain the k closest entities
     */
    public static <ID extends EntityID> java.util.Comparator<EntityDistance<ID>> maxHeapComparator() {
        return (a, b) -> Float.compare(b.distance, a.distance);
    }

    /**
     * Create a comparator for min heap (ascending order)
     */
    public static <ID extends EntityID> java.util.Comparator<EntityDistance<ID>> minHeapComparator() {
        return (a, b) -> Float.compare(a.distance, b.distance);
    }

    /**
     * Compare by distance for sorting (natural ordering is ascending by distance)
     */
    @Override
    public int compareTo(EntityDistance<ID> other) {
        return Float.compare(this.distance, other.distance);
    }
}
