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
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.List;

/**
 * The standard (non-DSOC) frustum cull service the {@link Culler} provides to occlusion consumers (RDR-008 P4).
 *
 * <p>{@code DsocController} uses this as its fallback path: when DSOC is enabled but the Z-buffer is inactive (or the
 * auto-disable heuristic skips occlusion for the frame), the controller calls {@link #frustumCullVisibleStandard} to
 * get the baseline frustum cull result. The producer is the cull cluster, the consumer is the DSOC cluster — keeping
 * the two surfaces separate makes the DSOC ↔ standard-cull seam explicit and prevents the {@code FrustumGeometry}
 * DSOC consumer interface from accreting cluster-foreign methods (the P2 substantive-critic obligation).
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public interface FrustumCullProvider<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /** The standard non-DSOC frustum cull. */
    List<FrustumIntersection<ID, Content>> frustumCullVisibleStandard(Frustum3D frustum, Point3f cameraPosition);
}
