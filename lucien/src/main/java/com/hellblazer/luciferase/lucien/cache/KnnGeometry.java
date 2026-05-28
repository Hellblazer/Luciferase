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
package com.hellblazer.luciferase.lucien.cache;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityDistance;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.PriorityQueue;

/**
 * Façade operations the k-NN feature object consumes during search.
 *
 * <p>P3 (RDR-008) refines the seam architecture into per-cluster sub-interfaces; {@code KnnSearcher} depends only on
 * what it actually uses — the subclass-overridden geometry hooks ({@link #calculateSpatialIndex},
 * {@link #estimateNodeDistance}, {@link #shouldContinueKNNSearch}) and the façade's input-validation helper
 * {@link #validateSpatialConstraints}. The façade implements this through a private inner class so the underlying
 * methods keep their original ({@code protected}/abstract) visibility.
 *
 * @param <Key> the spatial key type
 * @param <ID>  the entity identifier type
 * @author hal.hildebrand
 */
public interface KnnGeometry<Key extends SpatialKey<Key>, ID extends EntityID> {

    /** Spatial key of the cell containing {@code position} at the given level (subclass geometry). */
    Key calculateSpatialIndex(Point3f position, byte level);

    /** Lower-bound distance estimate from {@code queryPoint} to the node at the given key (subclass geometry). */
    float estimateNodeDistance(Key nodeIndex, Point3f queryPoint);

    /**
     * Whether k-NN search should continue traversing through the given node given the current candidate frontier.
     * Used by the expanding-radius search to prune nodes outside the worst-current-candidate radius.
     */
    boolean shouldContinueKNNSearch(Key nodeIndex, Point3f queryPoint, PriorityQueue<EntityDistance<ID>> candidates);

    /** Validate that {@code position} satisfies the façade's spatial constraints (range, positivity, etc.). */
    void validateSpatialConstraints(Point3f position);
}
