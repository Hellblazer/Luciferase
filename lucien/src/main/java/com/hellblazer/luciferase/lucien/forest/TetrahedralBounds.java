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
import com.hellblazer.luciferase.lucien.tetree.Tet;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * TreeBounds implementation for tetrahedral regions.
 * Uses exact tetrahedral containment testing via Tet.containsUltraFast().
 *
 * This bound type is used when AdaptiveForest subdivides using the TETRAHEDRAL strategy.
 * It provides exact geometric containment (not AABB approximation) and supports both
 * S0-S5 characteristic tetrahedra (from cubic subdivision) and Bey subdivision children.
 *
 * CRITICAL: containsPoint() uses Tet.containsUltraFast() for EXACT tetrahedral geometry.
 * Do NOT use AABB approximation for containment - that is only valid for toAABB().
 *
 * @param tet the tetrahedron defining this region
 * @author hal.hildebrand
 */
public record TetrahedralBounds(Tet tet) implements TreeBounds {

    @Override
    public boolean containsPoint(float x, float y, float z) {
        // Use EXACT tetrahedral containment - NOT AABB approximation
        return tet.containsUltraFast(x, y, z);
    }

    @Override
    public EntityBounds toAABB() {
        // Compute bounding AABB from tet vertices
        // Used ONLY for ghost layer compatibility and broad-phase culling
        Point3i[] coords = tet.coordinates();

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (var c : coords) {
            minX = Math.min(minX, c.x);
            minY = Math.min(minY, c.y);
            minZ = Math.min(minZ, c.z);
            maxX = Math.max(maxX, c.x);
            maxY = Math.max(maxY, c.y);
            maxZ = Math.max(maxZ, c.z);
        }

        return new EntityBounds(
            new Point3f(minX, minY, minZ),
            new Point3f(maxX, maxY, maxZ)
        );
    }

    @Override
    public float volume() {
        // Volume of tetrahedron: |det(v1-v0, v2-v0, v3-v0)| / 6
        Point3i[] v = tet.coordinates();

        // Compute edge vectors from v0
        float v1x = v[1].x - v[0].x;
        float v1y = v[1].y - v[0].y;
        float v1z = v[1].z - v[0].z;

        float v2x = v[2].x - v[0].x;
        float v2y = v[2].y - v[0].y;
        float v2z = v[2].z - v[0].z;

        float v3x = v[3].x - v[0].x;
        float v3y = v[3].y - v[0].y;
        float v3z = v[3].z - v[0].z;

        // Compute determinant using scalar triple product
        float det = v1x * (v2y * v3z - v2z * v3y) -
                    v1y * (v2x * v3z - v2z * v3x) +
                    v1z * (v2x * v3y - v2y * v3x);

        return Math.abs(det) / 6.0f;
    }

    /**
     * Compute the centroid of this tetrahedron.
     * Formula: (v0 + v1 + v2 + v3) / 4
     *
     * CRITICAL: This is NOT the same as cube center (anchor + h/2).
     * Tetrahedral centroid is the average of the 4 vertices.
     *
     * @return the centroid point
     */
    public Point3f centroid() {
        Point3i[] v = tet.coordinates();

        return new Point3f(
            (v[0].x + v[1].x + v[2].x + v[3].x) / 4.0f,
            (v[0].y + v[1].y + v[2].y + v[3].y) / 4.0f,
            (v[0].z + v[1].z + v[2].z + v[3].z) / 4.0f
        );
    }
}
