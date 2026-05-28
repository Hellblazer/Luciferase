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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.stream.Stream;

/**
 * The callback surface a {@link DsocController} needs from its owning spatial-index façade.
 *
 * <p>RDR-008 P1 extracts the Dynamic Scene Occlusion Culling cluster out of {@code AbstractSpatialIndex} into
 * {@link DsocController}. The DSOC traversal still relies on a handful of façade operations that belong to other
 * clusters (the frustum/cull traversal hooks — which are subclass-overridden template methods — and the cached
 * entity-position lookup), or that are deferred to a later phase (the standard, non-DSOC frustum cull which remains
 * the fallback path). Rather than widen those methods' visibility, the façade supplies them through this interface;
 * the façade's implementation is a private inner class, so the underlying methods keep their original (often
 * {@code private}/{@code protected}/abstract) visibility.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public interface DsocCallback<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

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
}
