/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.octree.MortonKey.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for O(1) Morton neighbor lookup functionality.
 *
 * This test suite validates the neighbor() method which provides constant-time
 * neighbor lookup using pre-computed direction tables and bit manipulation,
 * replacing the O(log n) decode/encode approach.
 *
 * Tests cover:
 * - Face neighbors (6 directions: +X, -X, +Y, -Y, +Z, -Z)
 * - Edge neighbors (12 directions)
 * - Vertex neighbors (8 directions)
 * - Boundary conditions (neighbors at domain edges)
 * - Symmetry properties (A.neighbor(+X).neighbor(-X) == A)
 * - Multiple refinement levels
 *
 * @author hal.hildebrand
 */
class MortonKeyNeighborTest {

    // =========================
    // Face Neighbor Tests (6)
    // =========================

    @Test
    void testFaceNeighborPositiveX() {
        // Cell at (10, 10, 10) at level 10
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.POSITIVE_X);

        // Should be (11, 10, 10) at level 10
        assertNotNull(neighbor, "Neighbor should exist");
        assertEquals(key.getLevel(), neighbor.getLevel(), "Neighbor should be at same level");

        var coords = decodeCoords(neighbor);
        assertEquals(11, coords[0], "X coordinate should be +1");
        assertEquals(10, coords[1], "Y coordinate unchanged");
        assertEquals(10, coords[2], "Z coordinate unchanged");
    }

    @Test
    void testFaceNeighborNegativeX() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.NEGATIVE_X);

        assertNotNull(neighbor);
        assertEquals(key.getLevel(), neighbor.getLevel());

        var coords = decodeCoords(neighbor);
        assertEquals(9, coords[0], "X coordinate should be -1");
        assertEquals(10, coords[1], "Y coordinate unchanged");
        assertEquals(10, coords[2], "Z coordinate unchanged");
    }

    @Test
    void testFaceNeighborPositiveY() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.POSITIVE_Y);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(10, coords[0], "X coordinate unchanged");
        assertEquals(11, coords[1], "Y coordinate should be +1");
        assertEquals(10, coords[2], "Z coordinate unchanged");
    }

    @Test
    void testFaceNeighborNegativeY() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.NEGATIVE_Y);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(10, coords[0], "X coordinate unchanged");
        assertEquals(9, coords[1], "Y coordinate should be -1");
        assertEquals(10, coords[2], "Z coordinate unchanged");
    }

    @Test
    void testFaceNeighborPositiveZ() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.POSITIVE_Z);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(10, coords[0], "X coordinate unchanged");
        assertEquals(10, coords[1], "Y coordinate unchanged");
        assertEquals(11, coords[2], "Z coordinate should be +1");
    }

    @Test
    void testFaceNeighborNegativeZ() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.NEGATIVE_Z);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(10, coords[0], "X coordinate unchanged");
        assertEquals(10, coords[1], "Y coordinate unchanged");
        assertEquals(9, coords[2], "Z coordinate should be -1");
    }

    // =========================
    // Edge Neighbor Tests (12)
    // =========================

    @Test
    void testEdgeNeighborPosXPosY() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.POS_X_POS_Y);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(11, coords[0], "+X edge");
        assertEquals(11, coords[1], "+Y edge");
        assertEquals(10, coords[2], "Z unchanged");
    }

    @Test
    void testEdgeNeighborPosXNegY() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.POS_X_NEG_Y);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(11, coords[0], "+X edge");
        assertEquals(9, coords[1], "-Y edge");
        assertEquals(10, coords[2], "Z unchanged");
    }

    @Test
    void testEdgeNeighborNegXPosY() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.NEG_X_POS_Y);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(9, coords[0], "-X edge");
        assertEquals(11, coords[1], "+Y edge");
        assertEquals(10, coords[2], "Z unchanged");
    }

    @Test
    void testEdgeNeighborNegXNegY() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.NEG_X_NEG_Y);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(9, coords[0], "-X edge");
        assertEquals(9, coords[1], "-Y edge");
        assertEquals(10, coords[2], "Z unchanged");
    }

    @Test
    void testAllEdgeNeighborsXY() {
        // Test all 4 XY-plane edge neighbors
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);

        var dirs = new Direction[] {
            Direction.POS_X_POS_Y, Direction.POS_X_NEG_Y,
            Direction.NEG_X_POS_Y, Direction.NEG_X_NEG_Y
        };

        for (var dir : dirs) {
            var neighbor = key.neighbor(dir);
            assertNotNull(neighbor, "Edge neighbor should exist: " + dir);
            assertEquals(key.getLevel(), neighbor.getLevel());
        }
    }

    @Test
    void testAllEdgeNeighborsXZ() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);

        var dirs = new Direction[] {
            Direction.POS_X_POS_Z, Direction.POS_X_NEG_Z,
            Direction.NEG_X_POS_Z, Direction.NEG_X_NEG_Z
        };

        for (var dir : dirs) {
            var neighbor = key.neighbor(dir);
            assertNotNull(neighbor, "Edge neighbor should exist: " + dir);
            assertEquals(key.getLevel(), neighbor.getLevel());
        }
    }

    @Test
    void testAllEdgeNeighborsYZ() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);

        var dirs = new Direction[] {
            Direction.POS_Y_POS_Z, Direction.POS_Y_NEG_Z,
            Direction.NEG_Y_POS_Z, Direction.NEG_Y_NEG_Z
        };

        for (var dir : dirs) {
            var neighbor = key.neighbor(dir);
            assertNotNull(neighbor, "Edge neighbor should exist: " + dir);
            assertEquals(key.getLevel(), neighbor.getLevel());
        }
    }

    // ============================
    // Vertex Neighbor Tests (8)
    // ============================

    @Test
    void testVertexNeighborPosXPosYPosZ() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.POS_X_POS_Y_POS_Z);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(11, coords[0], "+X vertex");
        assertEquals(11, coords[1], "+Y vertex");
        assertEquals(11, coords[2], "+Z vertex");
    }

    @Test
    void testVertexNeighborNegXNegYNegZ() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);
        var neighbor = key.neighbor(Direction.NEG_X_NEG_Y_NEG_Z);

        assertNotNull(neighbor);
        var coords = decodeCoords(neighbor);
        assertEquals(9, coords[0], "-X vertex");
        assertEquals(9, coords[1], "-Y vertex");
        assertEquals(9, coords[2], "-Z vertex");
    }

    @Test
    void testAllVertexNeighbors() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);

        var dirs = new Direction[] {
            Direction.POS_X_POS_Y_POS_Z, Direction.POS_X_POS_Y_NEG_Z,
            Direction.POS_X_NEG_Y_POS_Z, Direction.POS_X_NEG_Y_NEG_Z,
            Direction.NEG_X_POS_Y_POS_Z, Direction.NEG_X_POS_Y_NEG_Z,
            Direction.NEG_X_NEG_Y_POS_Z, Direction.NEG_X_NEG_Y_NEG_Z
        };

        for (var dir : dirs) {
            var neighbor = key.neighbor(dir);
            assertNotNull(neighbor, "Vertex neighbor should exist: " + dir);
            assertEquals(key.getLevel(), neighbor.getLevel());
        }
    }

    // ================================
    // Boundary Condition Tests
    // ================================

    @Test
    void testBoundaryAtOrigin() {
        // Cell at (0, 0, 0) has no neighbors in negative directions
        var key = MortonKey.fromCellIndices(0, 0, 0, (byte) 10);

        assertNull(key.neighbor(Direction.NEGATIVE_X), "No neighbor below X=0");
        assertNull(key.neighbor(Direction.NEGATIVE_Y), "No neighbor below Y=0");
        assertNull(key.neighbor(Direction.NEGATIVE_Z), "No neighbor below Z=0");

        // But should have positive direction neighbors
        assertNotNull(key.neighbor(Direction.POSITIVE_X), "Should have +X neighbor");
        assertNotNull(key.neighbor(Direction.POSITIVE_Y), "Should have +Y neighbor");
        assertNotNull(key.neighbor(Direction.POSITIVE_Z), "Should have +Z neighbor");
    }

    @Test
    void testBoundaryAtMaxCoordinate() {
        // Cell at maximum valid cell index for this level
        byte level = 10;
        int cellSize = Constants.lengthAtLevel(level);
        // Max cell index at level 10: (2^21 / cellSize) - 1 = (2^21 / 2^11) - 1 = 2^10 - 1 = 1023
        int maxCellIndex = (Constants.MAX_COORD + 1) / cellSize - 1;

        var key = MortonKey.fromCellIndices(maxCellIndex, maxCellIndex, maxCellIndex, level);

        assertNull(key.neighbor(Direction.POSITIVE_X), "No neighbor beyond max X");
        assertNull(key.neighbor(Direction.POSITIVE_Y), "No neighbor beyond max Y");
        assertNull(key.neighbor(Direction.POSITIVE_Z), "No neighbor beyond max Z");

        // But should have negative direction neighbors
        assertNotNull(key.neighbor(Direction.NEGATIVE_X), "Should have -X neighbor");
        assertNotNull(key.neighbor(Direction.NEGATIVE_Y), "Should have -Y neighbor");
        assertNotNull(key.neighbor(Direction.NEGATIVE_Z), "Should have -Z neighbor");
    }

    @Test
    void testBoundaryCornerNegativeOctant() {
        // Corner at origin - 7 out of 26 neighbors are out of bounds
        var key = MortonKey.fromCellIndices(0, 0, 0, (byte) 10);

        // All 8 negative octant vertex/edge neighbors should be null
        assertNull(key.neighbor(Direction.NEGATIVE_X));
        assertNull(key.neighbor(Direction.NEGATIVE_Y));
        assertNull(key.neighbor(Direction.NEGATIVE_Z));
        assertNull(key.neighbor(Direction.NEG_X_NEG_Y));
        assertNull(key.neighbor(Direction.NEG_X_NEG_Z));
        assertNull(key.neighbor(Direction.NEG_Y_NEG_Z));
        assertNull(key.neighbor(Direction.NEG_X_NEG_Y_NEG_Z));
    }

    // ================================
    // Symmetry and Consistency Tests
    // ================================

    @Test
    void testNeighborSymmetryPosXNegX() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);

        var posX = key.neighbor(Direction.POSITIVE_X);
        assertNotNull(posX);

        var backToKey = posX.neighbor(Direction.NEGATIVE_X);
        assertNotNull(backToKey);

        assertEquals(key, backToKey, "A.neighbor(+X).neighbor(-X) should equal A");
    }

    @Test
    void testNeighborSymmetryPosYNegY() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);

        var posY = key.neighbor(Direction.POSITIVE_Y);
        var backToKey = posY.neighbor(Direction.NEGATIVE_Y);

        assertEquals(key, backToKey, "A.neighbor(+Y).neighbor(-Y) should equal A");
    }

    @Test
    void testNeighborSymmetryPosZNegZ() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);

        var posZ = key.neighbor(Direction.POSITIVE_Z);
        var backToKey = posZ.neighbor(Direction.NEGATIVE_Z);

        assertEquals(key, backToKey, "A.neighbor(+Z).neighbor(-Z) should equal A");
    }

    @Test
    void testNeighborSymmetryAllDirections() {
        var key = MortonKey.fromCellIndices(10, 10, 10, (byte) 10);

        // Test face neighbors
        testSymmetry(key, Direction.POSITIVE_X, Direction.NEGATIVE_X);
        testSymmetry(key, Direction.POSITIVE_Y, Direction.NEGATIVE_Y);
        testSymmetry(key, Direction.POSITIVE_Z, Direction.NEGATIVE_Z);

        // Test edge neighbors
        testSymmetry(key, Direction.POS_X_POS_Y, Direction.NEG_X_NEG_Y);
        testSymmetry(key, Direction.POS_X_NEG_Y, Direction.NEG_X_POS_Y);
        testSymmetry(key, Direction.POS_X_POS_Z, Direction.NEG_X_NEG_Z);
        testSymmetry(key, Direction.POS_X_NEG_Z, Direction.NEG_X_POS_Z);
        testSymmetry(key, Direction.POS_Y_POS_Z, Direction.NEG_Y_NEG_Z);
        testSymmetry(key, Direction.POS_Y_NEG_Z, Direction.NEG_Y_POS_Z);

        // Test vertex neighbors
        testSymmetry(key, Direction.POS_X_POS_Y_POS_Z, Direction.NEG_X_NEG_Y_NEG_Z);
        testSymmetry(key, Direction.POS_X_POS_Y_NEG_Z, Direction.NEG_X_NEG_Y_POS_Z);
        testSymmetry(key, Direction.POS_X_NEG_Y_POS_Z, Direction.NEG_X_POS_Y_NEG_Z);
        testSymmetry(key, Direction.POS_X_NEG_Y_NEG_Z, Direction.NEG_X_POS_Y_POS_Z);
    }

    private void testSymmetry(MortonKey key, Direction forward, Direction backward) {
        var neighbor = key.neighbor(forward);
        if (neighbor != null) {
            var backToKey = neighbor.neighbor(backward);
            assertEquals(key, backToKey,
                String.format("Symmetry failed: %s -> %s", forward, backward));
        }
    }

    // ================================
    // Multi-Level Tests
    // ================================

    @Test
    void testNeighborsAtDifferentLevels() {
        // Test that neighbor lookup works correctly at different refinement levels
        for (byte level = 5; level <= 15; level++) {
            // Use coordinates that are valid at this level
            // At each level, we need cell indices that won't overflow
            int cellSize = Constants.lengthAtLevel(level);
            int maxCellIndex = (Constants.MAX_COORD + 1) / cellSize - 1;
            int testCoord = Math.min(10, maxCellIndex - 1); // Use 10 if possible, otherwise max - 1

            var key = MortonKey.fromCellIndices(testCoord, testCoord, testCoord, level);

            var posX = key.neighbor(Direction.POSITIVE_X);
            assertNotNull(posX, "Level " + level + " should have +X neighbor");
            assertEquals(level, posX.getLevel(), "Neighbor should be at same level");

            var coords = decodeCoords(posX);
            assertEquals(testCoord + 1, coords[0], "Level " + level + " +X coordinate");
        }
    }

    @Test
    void testNeighborsAtCoarseLevel() {
        // At level 0 (root), there's only one cell - no neighbors
        var root = MortonKey.getRoot();

        // Root cell spans entire domain, so any neighbor lookup should return null
        assertNull(root.neighbor(Direction.POSITIVE_X), "Root has no neighbors");
        assertNull(root.neighbor(Direction.NEGATIVE_X), "Root has no neighbors");
        assertNull(root.neighbor(Direction.POSITIVE_Y), "Root has no neighbors");
    }

    @Test
    void testNeighborsAtFineLevel() {
        // Test at maximum refinement level
        byte maxLevel = Constants.getMaxRefinementLevel();
        var key = MortonKey.fromCellIndices(1000, 1000, 1000, maxLevel);

        var posX = key.neighbor(Direction.POSITIVE_X);
        assertNotNull(posX, "Max level should have neighbors");
        assertEquals(maxLevel, posX.getLevel());

        var coords = decodeCoords(posX);
        assertEquals(1001, coords[0], "Fine level +X neighbor");
    }

    // ================================
    // Consistency with Current API
    // ================================

    @Test
    void testConsistencyWithMortonNeighborDetector() {
        // The new neighbor() method should produce results consistent with
        // the existing MortonNeighborDetector (which uses decode/encode)

        // This test will be implemented after neighbor() is working
        // to verify consistency with the old O(log n) approach
    }

    // ================================
    // Helper Methods
    // ================================

    /**
     * Decode Morton key to cell indices at the key's level.
     */
    private int[] decodeCoords(MortonKey key) {
        var rawCoords = MortonCurve.decode(key.getMortonCode());
        var cellSize = Constants.lengthAtLevel(key.getLevel());

        // Convert world coordinates to cell indices
        return new int[] {
            rawCoords[0] / cellSize,
            rawCoords[1] / cellSize,
            rawCoords[2] / cellSize
        };
    }
}
