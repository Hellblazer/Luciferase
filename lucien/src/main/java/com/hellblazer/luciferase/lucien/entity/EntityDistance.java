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

    // Cached singletons (Luciferase-up7uz): the ordering depends only on `distance`, not on ID, so one instance
    // serves every ID type. Returning the SAME instance each call avoids per-call allocation AND lets the
    // PriorityQueue pool reuse queues by comparator identity on the hot k-NN path.
    private static final java.util.Comparator<EntityDistance<?>> MAX_HEAP = (a, b) -> Float.compare(b.distance,
                                                                                                    a.distance);
    private static final java.util.Comparator<EntityDistance<?>> MIN_HEAP = (a, b) -> Float.compare(a.distance,
                                                                                                    b.distance);

    /**
     * The shared max-heap (descending-distance) comparator — used in k-NN searches to maintain the k closest
     * entities. Returns a cached singleton instance (Luciferase-up7uz).
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <ID extends EntityID> java.util.Comparator<EntityDistance<ID>> maxHeapComparator() {
        return (java.util.Comparator) MAX_HEAP;
    }

    /**
     * The shared min-heap (ascending-distance) comparator. Returns a cached singleton instance (Luciferase-up7uz).
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <ID extends EntityID> java.util.Comparator<EntityDistance<ID>> minHeapComparator() {
        return (java.util.Comparator) MIN_HEAP;
    }

    /**
     * Compare by distance for sorting (natural ordering is ascending by distance)
     */
    @Override
    public int compareTo(EntityDistance<ID> other) {
        return Float.compare(this.distance, other.distance);
    }
}
