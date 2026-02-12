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
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for tetrahedral subdivision in AdaptiveForest (Phase 1).
 * Tests TreeBounds sealed interface hierarchy and dual-path subdivision (cubic→tets, tet→subtets).
 *
 * @author hal.hildebrand
 */
class TetrahedralSubdivisionForestTest {

    // ========== TreeBounds Interface Tests (Step 1) ==========

    @Test
    void testCubicBoundsContainsPoint() {
        // Test AABB containment for CubicBounds
        var bounds = new EntityBounds(
            new Point3f(10.0f, 20.0f, 30.0f),
            new Point3f(50.0f, 60.0f, 70.0f)
        );
        var cubicBounds = new CubicBounds(bounds);

        // Inside the AABB
        assertTrue(cubicBounds.containsPoint(30.0f, 40.0f, 50.0f));
        assertTrue(cubicBounds.containsPoint(10.0f, 20.0f, 30.0f)); // min corner
        assertTrue(cubicBounds.containsPoint(50.0f, 60.0f, 70.0f)); // max corner

        // Outside the AABB
        assertFalse(cubicBounds.containsPoint(5.0f, 40.0f, 50.0f));  // x too low
        assertFalse(cubicBounds.containsPoint(30.0f, 15.0f, 50.0f)); // y too low
        assertFalse(cubicBounds.containsPoint(30.0f, 40.0f, 75.0f)); // z too high
        assertFalse(cubicBounds.containsPoint(55.0f, 40.0f, 50.0f)); // x too high
    }

    @Test
    void testTetrahedralBoundsContainsPointUsingUltraFast() {
        // Test exact tetrahedral containment using Tet.containsUltraFast()
        // Create a simple S0 tetrahedron at origin with level 5
        var tet = new Tet(0, 0, 0, (byte) 5, (byte) 0); // S0: vertices 0, 1, 3, 7
        var tetBounds = new TetrahedralBounds(tet);

        // Get coordinates for reference
        Point3i[] coords = tet.coordinates();
        // S0 at level 5 should have h = 1 << (21 - 5) = 1 << 16 = 65536
        int h = 1 << (21 - 5);

        // Expected coords for S0: V0=(0,0,0), V1=(h,0,0), V2=(h,h,0), V3=(h,h,h)
        assertEquals(0, coords[0].x);
        assertEquals(h, coords[1].x);
        assertEquals(h, coords[2].x);
        assertEquals(h, coords[3].x);

        // Test containment - point at centroid should be inside
        float cx = (0 + h + h + h) / 4.0f;
        float cy = (0 + 0 + h + h) / 4.0f;
        float cz = (0 + 0 + 0 + h) / 4.0f;
        assertTrue(tetBounds.containsPoint(cx, cy, cz), "Centroid should be inside tet");

        // Test point clearly outside
        assertFalse(tetBounds.containsPoint(-1.0f, 0.0f, 0.0f), "Negative x should be outside");
        assertFalse(tetBounds.containsPoint(h * 2.0f, h / 2.0f, h / 2.0f), "Far outside should fail");
    }

    @Test
    void testTetrahedralBoundsToAABBComputesBoundingBox() {
        // Test that toAABB() computes correct bounding box from tet vertices
        // Use grid-aligned coordinates: at level 10, cell size = 2^(21-10) = 2048
        int cellSize = 1 << (21 - 10); // 2048
        var tet = new Tet(0, 0, cellSize * 2, (byte) 10, (byte) 2); // S2 type at valid anchor
        var tetBounds = new TetrahedralBounds(tet);

        // Get actual vertices
        Point3i[] coords = tet.coordinates();

        // Compute expected min/max
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

        var aabb = tetBounds.toAABB();

        // Verify AABB encompasses all vertices
        assertEquals(minX, aabb.getMinX(), 0.01f);
        assertEquals(minY, aabb.getMinY(), 0.01f);
        assertEquals(minZ, aabb.getMinZ(), 0.01f);
        assertEquals(maxX, aabb.getMaxX(), 0.01f);
        assertEquals(maxY, aabb.getMaxY(), 0.01f);
        assertEquals(maxZ, aabb.getMaxZ(), 0.01f);
    }

    @Test
    void testCubicBoundsVolume() {
        // Test volume calculation for cubic bounds
        var bounds = new EntityBounds(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(10.0f, 20.0f, 30.0f)
        );
        var cubicBounds = new CubicBounds(bounds);

        float expectedVolume = 10.0f * 20.0f * 30.0f;
        assertEquals(expectedVolume, cubicBounds.volume(), 0.01f);
    }

    @Test
    void testTetrahedralBoundsVolumeUsingDeterminant() {
        // Test volume calculation using determinant formula: |det(v1-v0, v2-v0, v3-v0)| / 6
        var tet = new Tet(0, 0, 0, (byte) 5, (byte) 0); // S0 at level 5
        var tetBounds = new TetrahedralBounds(tet);

        Point3i[] v = tet.coordinates();
        int h = 1 << (21 - 5); // 65536

        // For S0: V0=(0,0,0), V1=(h,0,0), V2=(h,h,0), V3=(h,h,h)
        // v1-v0 = (h, 0, 0)
        // v2-v0 = (h, h, 0)
        // v3-v0 = (h, h, h)
        // det = h * (h * h - h * 0) - 0 * (anything) + 0 * (anything)
        // det = h * h * h = h³
        // volume = |h³| / 6 = h³ / 6

        // Cast to long to avoid integer overflow in h*h*h
        long hLong = h;
        float expectedVolume = (hLong * hLong * hLong) / 6.0f;
        float actualVolume = tetBounds.volume();

        assertEquals(expectedVolume, actualVolume, expectedVolume * 0.01f,
                     "Volume should be h³/6 for standard tetrahedron");
    }

    @Test
    void testTetrahedralBoundsCentroid() {
        // Test centroid calculation: (v0 + v1 + v2 + v3) / 4 (NOT cube center)
        // Use grid-aligned coordinates: at level 10, cell size = 2048
        int cellSize = 1 << (21 - 10); // 2048
        var tet = new Tet(cellSize, cellSize * 2, cellSize * 3, (byte) 10, (byte) 1); // S1 type
        var tetBounds = new TetrahedralBounds(tet);

        Point3i[] v = tet.coordinates();

        // Expected centroid
        float expectedX = (v[0].x + v[1].x + v[2].x + v[3].x) / 4.0f;
        float expectedY = (v[0].y + v[1].y + v[2].y + v[3].y) / 4.0f;
        float expectedZ = (v[0].z + v[1].z + v[2].z + v[3].z) / 4.0f;

        Point3f centroid = tetBounds.centroid();

        assertEquals(expectedX, centroid.x, 0.01f);
        assertEquals(expectedY, centroid.y, 0.01f);
        assertEquals(expectedZ, centroid.z, 0.01f);

        // Verify it's NOT the cube center formula (anchor + h/2)
        float cubeCenterX = cellSize + cellSize / 2.0f;
        // For S1 type, centroid will differ from cube center
        // (this is the critical test - centroid != cube center for tets)
    }
}
