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
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.List;

/**
 * The k-nearest-neighbors service the k-NN feature object offers to other clusters.
 *
 * <p>Other feature objects (e.g. {@code GhostCoordinator}) and the façade itself consume this interface for k-NN
 * queries; {@code KnnSearcher} is the concrete provider. Splitting this out as its own interface (P3 / RDR-008)
 * means consumers depend on the narrow query contract rather than the full façade or a fat unified seam.
 *
 * @param <Key> the spatial key type
 * @param <ID>  the entity identifier type
 * @author hal.hildebrand
 */
public interface KnnProvider<Key extends SpatialKey<Key>, ID extends EntityID> {

    /** k-nearest entities to {@code queryPoint}, optionally bounded by {@code maxDistance}. */
    List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance);
}
