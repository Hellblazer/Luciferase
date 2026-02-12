/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;

/**
 * Sealed interface representing the spatial bounds of a tree in an AdaptiveForest.
 * Trees can be bounded by either cubic (AABB) or tetrahedral geometry.
 *
 * This interface provides polymorphic containment testing that dispatches to the correct
 * geometric algorithm based on the bound type:
 * - CubicBounds uses AABB intersection test
 * - TetrahedralBounds uses exact tetrahedral containment (Tet.containsUltraFast)
 *
 * The sealed interface ensures compile-time exhaustiveness checking when pattern matching
 * and prevents invalid bound types from being introduced.
 *
 * @author hal.hildebrand
 */
public sealed interface TreeBounds permits CubicBounds, TetrahedralBounds {

    /**
     * Test if a point is contained within these bounds.
     * The implementation dispatches to the appropriate geometric algorithm:
     * - AABB test for CubicBounds
     * - Exact tetrahedral containment for TetrahedralBounds
     *
     * @param x X coordinate of the point
     * @param y Y coordinate of the point
     * @param z Z coordinate of the point
     * @return true if the point is contained within these bounds
     */
    boolean containsPoint(float x, float y, float z);

    /**
     * Convert these bounds to an axis-aligned bounding box.
     *
     * For CubicBounds: Returns the bounds directly (already AABB)
     * For TetrahedralBounds: Computes the bounding AABB from tet vertices
     *
     * This method is intended ONLY for:
     * - Ghost layer compatibility (which requires AABB)
     * - Broad-phase culling before exact containment tests
     *
     * Do NOT use this for containment testing - use containsPoint() instead.
     *
     * @return an EntityBounds representing the AABB of these bounds
     */
    EntityBounds toAABB();

    /**
     * Calculate the volume of these bounds.
     *
     * For CubicBounds: width * height * depth
     * For TetrahedralBounds: |det(v1-v0, v2-v0, v3-v0)| / 6
     *
     * @return the volume in cubic units
     */
    float volume();
}
