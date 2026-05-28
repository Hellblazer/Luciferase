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
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.stream.Stream;

/**
 * Frustum/cull façade operations the DSOC feature object consumes.
 *
 * <p>P3 (RDR-008) refined the seam architecture from a single unified callback (the P2 {@code SpatialGeometry}, now
 * deleted) into per-cluster sub-interfaces. {@code DsocController} depends only on what it actually uses — the
 * subclass-overridden frustum traversal/intersection hooks, the node-bounds helper, and the cached entity-position
 * lookup. The façade implements this through a private inner class so the underlying methods keep their original
 * visibility.
 *
 * <p>P4 (RDR-008) re-homed the standard non-DSOC cull fallback into the new {@code lucien.cull.Culler} feature object;
 * {@code DsocController} consumes it through a separate
 * {@link com.hellblazer.luciferase.lucien.cull.FrustumCullProvider} sub-interface so this DSOC consumer interface
 * stays narrow.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public interface FrustumGeometry<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /** Spatial keys of nodes potentially intersecting the frustum, in front-to-back traversal order. */
    Stream<Key> getFrustumTraversalOrder(Frustum3D frustum, Point3f cameraPosition);

    /** Whether the frustum intersects the node at the given key (subclass-specific geometry test). */
    boolean doesFrustumIntersectNode(Key nodeIndex, Frustum3D frustum);

    /** World-space bounds of the node at the given key, or {@code null} if not computable. */
    EntityBounds computeNodeBounds(Key nodeIndex);

    /** Cached world position of the entity, or {@code null} if unknown. */
    Point3f getCachedEntityPosition(ID entityId);
}
