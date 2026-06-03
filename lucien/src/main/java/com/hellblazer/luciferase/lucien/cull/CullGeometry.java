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
package com.hellblazer.luciferase.lucien.cull;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.stream.Stream;

/**
 * Façade operations the frustum/plane/ray {@link Culler} consumes (RDR-008 P4).
 *
 * <p>Following the P3 per-cluster sub-interface refinement (RDR-008 §Decision item 1 P3 refinement), each feature
 * object depends only on the narrow surface it actually uses — for the bundled cull cluster that surface is the seven
 * subclass-overridden traversal/intersect/distance hooks (each cluster's three) plus the two cached entity accessors
 * and the spanning-policy flag. The façade implements this through a private inner class so the underlying methods
 * keep their original visibility.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public interface CullGeometry<Key extends SpatialKey<Key>, ID extends EntityID, Content>
extends com.hellblazer.luciferase.lucien.SpatialIndexGeometry<ID>,
        com.hellblazer.luciferase.lucien.CachedEntityBoundsGeometry<ID> {

    // ===== Frustum subclass hooks =====

    /** Spatial keys of nodes potentially intersecting the frustum, in front-to-back traversal order. */
    Stream<Key> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition);

    /** Whether the frustum intersects the node at the given key (subclass-specific geometry test). */
    boolean doesFrustumIntersectNode(Key nodeIndex, Frustum3D frustum);

    // ===== Plane subclass hooks =====

    /** Spatial keys of nodes potentially intersecting the plane, in traversal order. */
    Stream<Key> getPlaneTraversalOrder(Plane3D plane);

    /** Whether the plane intersects the node at the given key (subclass-specific geometry test). */
    boolean doesPlaneIntersectNode(Key nodeIndex, Plane3D plane);

    // ===== Ray subclass hooks =====

    /** Spatial keys of nodes potentially intersecting the ray, in ray-distance traversal order. */
    Stream<Key> getRayTraversalOrder(Ray3D ray);

    /** Whether the ray intersects the node at the given key (subclass-specific geometry test). */
    boolean doesRayIntersectNode(Key nodeIndex, Ray3D ray);

    /** Distance from the ray origin to the node's entry point, or {@code Float.MAX_VALUE} if it misses. */
    float getRayNodeIntersectionDistance(Key nodeIndex, Ray3D ray);

    // ===== Cached accessors + spanning =====

    // getCachedEntityPosition is inherited from SpatialIndexGeometry (Luciferase-rk8hv).

    // getCachedEntityBounds is inherited from CachedEntityBoundsGeometry (Luciferase-rk8hv).

    /** Whether the spanning policy is enabled — drives the bounded-entity sweep in ray queries. */
    boolean isSpanningEnabled();
}
