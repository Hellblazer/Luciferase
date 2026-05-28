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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.stream.Stream;

/**
 * The unified callback seam through which RDR-008 feature objects reach their owning {@code AbstractSpatialIndex}
 * façade.
 *
 * <p>RDR-008 §Decision names a single {@code SpatialGeometry<Key>} interface, implemented by the façade and handed to
 * each cohesive collaborator ({@code DsocController}, {@code GhostCoordinator}, {@code KnnSearcher}, …) alongside the
 * shared {@link SpatialIndexCore}. It carries the façade-resident operations a collaborator needs but does not own:
 * the subclass-overridden geometry template hooks (e.g. {@link #getFrustumTraversalOrder},
 * {@link #doesFrustumIntersectNode}) plus the concrete spatial helpers that remain on the façade or belong to a
 * not-yet-extracted cluster (e.g. {@link #frustumCullVisibleStandard}, which is the frustum/cull cluster's — P4).
 * The façade supplies the implementation through a private inner class, so the underlying methods keep their original
 * ({@code private}/{@code protected}/abstract) visibility — no public widening.
 *
 * <p>The interface grows by one phase's worth of operations as each feature object is extracted; collaborators call
 * only the subset they need. This is the deliberate "one named seam" the RDR chose over per-feature callbacks.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public interface SpatialGeometry<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    // ---- Frustum / cull geometry (consumed by DsocController; P4 will re-home the providers) ---------------------

    /** Spatial keys of nodes potentially intersecting the frustum, in front-to-back traversal order. */
    Stream<Key> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition);

    /** Whether the frustum intersects the node at the given key (subclass-specific geometry test). */
    boolean doesFrustumIntersectNode(Key nodeIndex, Frustum3D frustum);

    /** World-space bounds of the node at the given key, or {@code null} if not computable. */
    EntityBounds computeNodeBounds(Key nodeIndex);

    /** Cached world position of the entity, or {@code null} if unknown. */
    Point3f getCachedEntityPosition(ID entityId);

    /** The standard (non-DSOC) frustum cull — the fallback when DSOC is skipped or its Z-buffer is inactive. */
    List<FrustumIntersection<ID, Content>> frustumCullVisibleStandard(Frustum3D frustum, Point3f cameraPosition);

    // ---- k-nearest-neighbor query (consumed by GhostCoordinator; P3 will re-home the provider) -------------------

    /** k-nearest entities to {@code queryPoint}, optionally bounded by {@code maxDistance}. */
    List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance);
}
