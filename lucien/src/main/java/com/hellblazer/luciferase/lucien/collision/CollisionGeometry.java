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

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Façade operations the {@link CollisionEngine} consumes (RDR-008 P5).
 *
 * <p>Following the P3 per-cluster sub-interface refinement (RDR-008 §Decision item 1 P3 refinement), each feature
 * object depends only on the narrow surface it actually uses — for the collision cluster that surface is the two
 * subclass-overridden traversal hooks ({@link #findNodesIntersectingBounds} and {@link #addNeighboringNodes}), the
 * façade-resident range-query composed of the same two hooks, and the two cached entity accessors. The façade
 * implements this through a private inner class so the underlying methods keep their original visibility.
 *
 * <p><b>Naming-discipline exception.</b> Sibling sub-interfaces {@code FrustumGeometry}, {@code CullGeometry}, and
 * {@code KnnGeometry} expose only bare spatial predicates and subclass hooks; this interface additionally exposes
 * {@link #spatialRangeQuery} (a composed query) and the two entity-cache accessors. The composed query is required
 * because a direct call to the {@code findNodesIntersectingBounds(VolumeBounds)} subclass hook skips the
 * {@code doesNodeIntersectVolume} filter that {@code spatialRangeQuery} applies — a regression caught on the first
 * P5 verification pass (Octree spanning-entity collision tests dropped silently). The entity-cache accessors are
 * included to avoid a separate two-method seam. The P5 review noted the resulting label drift; future cluster
 * extractions should hold the line and only add composed queries when an equivalent regression makes the case.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public interface CollisionGeometry<Key extends SpatialKey<Key>, ID extends EntityID, Content>
extends com.hellblazer.luciferase.lucien.SpatialIndexGeometry<ID> {

    // ===== Subclass hooks =====

    /**
     * Spatial keys of nodes potentially intersecting the given volume bounds (subclass-specific spatial query).
     */
    Set<Key> findNodesIntersectingBounds(VolumeBounds bounds);

    /**
     * Expand the neighbor frontier from the given node into {@code toVisit}, tracking visited keys in
     * {@code visitedNodes} (subclass-specific topology).
     */
    void addNeighboringNodes(Key nodeIndex, Queue<Key> toVisit, Set<Key> visitedNodes);

    // ===== Façade-resident composed query =====

    /**
     * Stream of (key, node) entries in the spatial index that match the volume bounds. The façade composes this
     * over {@link #findNodesIntersectingBounds} and the subclass-specific containment/intersection predicates;
     * the collision cluster consumes it for {@code findCollisionsInRegion} and several internal sweeps.
     */
    Stream<Map.Entry<Key, SpatialNodeImpl<ID>>> spatialRangeQuery(VolumeBounds bounds, boolean includeIntersecting);

    // ===== Cached accessors =====

    // getCachedEntityPosition is inherited from SpatialIndexGeometry (Luciferase-rk8hv).

    /** Cached world bounds of the entity, or {@code null} for point entities. */
    EntityBounds getCachedEntityBounds(ID entityId);
}
