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
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityDistance;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * Façade operations the k-NN feature object consumes during search.
 *
 * <p>P3 (RDR-008) refines the seam architecture into per-cluster sub-interfaces; {@code KnnSearcher} depends only on
 * what it actually uses — the subclass-overridden geometry hooks ({@link #calculateSpatialIndex},
 * {@link #estimateNodeDistance}, {@link #shouldContinueKNNSearch}, {@link #getCellSizeAtLevel},
 * {@link #findNodesIntersectingBounds}, {@link #knnRequiresFullDomainSweep}, {@link #addNeighboringNodes}), the
 * façade's input-validation helper {@link #validateSpatialConstraints}, the cached entity-position lookup
 * {@link #getCachedEntityPosition}, and the façade's depth constant {@link #maxDepth}. The façade implements this
 * through a private inner class so the underlying methods keep their original ({@code protected}/abstract) visibility.
 *
 * <p>Note: {@link #getCachedEntityPosition} is duplicated on {@link com.hellblazer.luciferase.lucien.occlusion.FrustumGeometry}
 * by intent — each per-cluster sub-interface keeps its own narrow surface so the façade's private inner classes can
 * route the entity-cache lookup without growing a single fat callback. The duplication is one method declaration each;
 * if a third cluster needs the same lookup, factor it into a tiny shared sub-interface at that point (YAGNI until then).
 *
 * @param <Key> the spatial key type
 * @param <ID>  the entity identifier type
 * @author hal.hildebrand
 */
public interface KnnGeometry<Key extends SpatialKey<Key>, ID extends EntityID> {

    // ---- Subclass-overridden geometry hooks -----------------------------------------------------------------------

    /** Spatial key of the cell containing {@code position} at the given level (subclass geometry). */
    Key calculateSpatialIndex(Point3f position, byte level);

    /** Lower-bound distance estimate from {@code queryPoint} to the node at the given key (subclass geometry). */
    float estimateNodeDistance(Key nodeIndex, Point3f queryPoint);

    /**
     * Whether k-NN search should continue traversing through the given node given the current candidate frontier.
     * Used by the expanding-radius search to prune nodes outside the worst-current-candidate radius.
     */
    boolean shouldContinueKNNSearch(Key nodeIndex, Point3f queryPoint, PriorityQueue<EntityDistance<ID>> candidates);

    /**
     * World-space edge length of a cell at the given subdivision level. Returned as {@code float} so fractional-
     * coordinate indices (Prism) report a meaningful sub-unit cell size; integer-coordinate indices widen exactly to
     * {@code float} for all levels (cell sizes are powers of two below 2^24).
     */
    float getCellSizeAtLevel(byte level);

    /**
     * Spatial keys of all nodes intersecting {@code bounds}. Subclasses provide an efficient implementation using
     * their concrete SFC structure (e.g. Morton/TM interval walks via LITMAX/BIGMIN, or an O(n) scan for Prism).
     */
    Set<Key> findNodesIntersectingBounds(VolumeBounds bounds);

    /**
     * Whether {@code kNearestNeighbors} must perform a final full-domain sweep when the expanding-radius fallback
     * leaves it short of {@code k}. Default {@code false} for integer-SFC indices (Octree/Tetree/SFCArrayIndex) whose
     * SFC range-pruning path already covers the whole {@code maxDistance} sphere; Prism returns {@code true} because
     * its normalized [0,1) coordinate space defeats the incremental expansion (Luciferase-h65).
     */
    boolean knnRequiresFullDomainSweep();

    /** Push the neighbors of {@code nodeIndex} into {@code toVisit}, respecting {@code visitedNodes}. */
    void addNeighboringNodes(Key nodeIndex, Queue<Key> toVisit, Set<Key> visitedNodes);

    // ---- Façade helpers -------------------------------------------------------------------------------------------

    /** Validate that {@code position} satisfies the façade's spatial constraints (range, positivity, etc.). */
    void validateSpatialConstraints(Point3f position);

    /** Cached world position of the entity, or {@code null} if unknown. */
    Point3f getCachedEntityPosition(ID entityId);

    /** The façade's maximum subdivision depth (constant for the lifetime of the index). */
    byte maxDepth();
}
