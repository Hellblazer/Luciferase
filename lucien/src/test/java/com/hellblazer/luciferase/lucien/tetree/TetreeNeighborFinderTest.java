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
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TetreeNeighborFinder. Validates face neighbor finding algorithms.
 *
 * @author hal.hildebrand
 */
public class TetreeNeighborFinderTest {

    private TetreeNeighborFinder neighborFinder;

    @BeforeEach
    public void setUp() {
        neighborFinder = new TetreeNeighborFinder();
    }

    @Test
    public void testAreNeighbors() {
        // Create two tetrahedra
        Tet tet1 = new Tet(100, 100, 100, (byte) 2, (byte) 0);

        // Find a neighbor
        Tet neighbor = neighborFinder.findFaceNeighbor(tet1, 0);

        if (neighbor != null) {
            // Should be neighbors
            assertTrue(neighborFinder.areNeighbors(tet1, neighbor), "Should detect neighbors correctly");

            // Should be symmetric
            assertTrue(neighborFinder.areNeighbors(neighbor, tet1), "Neighbor relationship should be symmetric");
        }

        // Non-adjacent tetrahedra should not be neighbors
        Tet farTet = new Tet(900, 900, 900, (byte) 2, (byte) 0);
        assertFalse(neighborFinder.areNeighbors(tet1, farTet), "Far tetrahedra should not be neighbors");
    }

    @Test
    public void testBoundaryNeighbors() {
        // Create a tetrahedron at the boundary
        Tet boundaryTet = new Tet(0, 0, 0, (byte) 2, (byte) 0);

        // Find all neighbors
        List<Tet> neighbors = neighborFinder.findAllNeighbors(boundaryTet);

        // Boundary tetrahedron should have fewer than 4 neighbors
        assertTrue(neighbors.size() < 4, "Boundary tetrahedron should have fewer than 4 neighbors");
        assertTrue(neighbors.size() > 0, "Should have at least one neighbor");
    }

    @Test
    public void testFindAllNeighbors() {
        // Create a tetrahedron truly in the interior of the domain (far from boundaries)
        Tet tet = new Tet(1048576, 1048576, 1048576, (byte) 3, (byte) 0);

        // Find all neighbors
        List<Tet> neighbors = neighborFinder.findAllNeighbors(tet);

        // Interior tetrahedron should have 4 neighbors
        assertEquals(4, neighbors.size(), "Interior tetrahedron should have 4 neighbors");

        // All neighbors should be unique
        assertEquals(neighbors.size(), neighbors.stream().distinct().count(), "All neighbors should be unique");

        // All neighbors should be adjacent
        for (Tet neighbor : neighbors) {
            assertTrue(areAdjacent(tet, neighbor), "All neighbors should be adjacent");
        }
    }

    @Test
    public void testFindFaceNeighbor() {
        // Create a tetrahedron
        Tet tet = new Tet(100, 100, 100, (byte) 2, (byte) 0);

        // Find neighbors across all faces
        for (int face = 0; face < 4; face++) {
            Tet neighbor = neighborFinder.findFaceNeighbor(tet, face);

            if (neighbor != null) {
                // Verify neighbor is at same level
                assertEquals(tet.l(), neighbor.l(), "Neighbor should be at same level");

                // Verify neighbor is adjacent
                assertTrue(areAdjacent(tet, neighbor), "Neighbor should be adjacent");

                // Verify neighbor has different type (due to face crossing)
                // This depends on the face and types involved
            }
        }
    }

    @Test
    public void testFindNeighborsAtLevel() {
        // Create a tetrahedron at level 3
        Tet tet = new Tet(256, 256, 256, (byte) 3, (byte) 0);

        // Find neighbors at same level
        List<Tet> sameLevelNeighbors = neighborFinder.findNeighborsAtLevel(tet, (byte) 3);
        assertFalse(sameLevelNeighbors.isEmpty(), "Should find neighbors at same level");

        // Find neighbors at coarser level
        List<Tet> coarserNeighbors = neighborFinder.findNeighborsAtLevel(tet, (byte) 2);

        // All coarser neighbors should be at level 2
        for (Tet neighbor : coarserNeighbors) {
            assertEquals(2, neighbor.l(), "Coarser neighbors should be at requested level");
        }
    }

    @Test
    public void testFindNeighborsWithinDistance() {
        // Create a tetrahedron
        Tet tet = new Tet(512, 512, 512, (byte) 4, (byte) 0);

        // Distance 0 should return only the tetrahedron itself
        List<Tet> distance0 = neighborFinder.findNeighborsWithinDistance(tet, 0);
        assertEquals(1, distance0.size(), "Distance 0 should return only the starting tetrahedron");
        assertEquals(tet, distance0.get(0));

        // Distance 1 should return tet plus immediate neighbors
        List<Tet> distance1 = neighborFinder.findNeighborsWithinDistance(tet, 1);
        assertTrue(distance1.size() > 1, "Distance 1 should include immediate neighbors");
        assertTrue(distance1.contains(tet), "Should include starting tetrahedron");

        // Distance 2 should include more tetrahedra
        List<Tet> distance2 = neighborFinder.findNeighborsWithinDistance(tet, 2);
        assertTrue(distance2.size() > distance1.size(), "Distance 2 should include more tetrahedra than distance 1");

        // All distance 1 tets should be in distance 2
        for (Tet d1 : distance1) {
            assertTrue(distance2.contains(d1), "Distance 2 should include all distance 1 tetrahedra");
        }
    }

    @Test
    public void testFindSharedFace() {
        // Create a tetrahedron
        Tet tet = new Tet(256, 256, 256, (byte) 3, (byte) 0);

        // For each face, find neighbor and verify shared face
        for (int face = 0; face < 4; face++) {
            Tet neighbor = neighborFinder.findFaceNeighbor(tet, face);

            if (neighbor != null) {
                // Find shared face from tet's perspective
                int sharedFace = neighborFinder.findSharedFace(tet, neighbor);
                assertEquals(face, sharedFace, "Should find correct shared face");

                // Find shared face from neighbor's perspective
                int reverseSharedFace = neighborFinder.findSharedFace(neighbor, tet);
                assertTrue(reverseSharedFace >= 0, "Should find shared face from neighbor's perspective");
            }
        }
    }

    @Test
    public void testInvalidFaceIndex() {
        Tet tet = new Tet(100, 100, 100, (byte) 2, (byte) 0);

        // Test invalid face indices
        assertThrows(IllegalArgumentException.class, () -> neighborFinder.findFaceNeighbor(tet, -1),
                     "Should throw for negative face index");

        assertThrows(IllegalArgumentException.class, () -> neighborFinder.findFaceNeighbor(tet, 4),
                     "Should throw for face index >= 4");
    }

    @Test
    public void testNeighborTypeTransitions() {
        // Test that neighbor types follow connectivity tables
        for (byte type = 0; type < 6; type++) {
            Tet tet = new Tet(512, 512, 512, (byte) 4, type);

            for (int face = 0; face < 4; face++) {
                Tet neighbor = neighborFinder.findFaceNeighbor(tet, face);

                if (neighbor != null) {
                    // Check if neighbor type matches expected from connectivity table
                    byte expectedType = TetreeConnectivity.getFaceNeighborType(type, face);
                    assertEquals(expectedType, neighbor.type(),
                                 "Neighbor type should match connectivity table for type " + type + " face " + face);
                }
            }
        }
    }

    // Helper method to check if two tetrahedra are adjacent
    private boolean areAdjacent(Tet tet1, Tet tet2) {
        // Two tetrahedra are adjacent if they share a face
        // In tetrahedral grids, neighbors can be:
        // 1. In the same grid cell with different types
        // 2. In adjacent grid cells 

        int dx = Math.abs(tet1.x() - tet2.x());
        int dy = Math.abs(tet1.y() - tet2.y());
        int dz = Math.abs(tet1.z() - tet2.z());

        int cellSize = tet1.length();

        // Case 1: Same grid cell, different types (common in tetrahedral grids)
        if (dx == 0 && dy == 0 && dz == 0 && tet1.type() != tet2.type()) {
            return true;
        }

        // Case 2: Adjacent grid cells
        return (dx == cellSize && dy == 0 && dz == 0) || (dx == 0 && dy == cellSize && dz == 0) || (dx == 0 && dy == 0
                                                                                                    && dz == cellSize)
        || (dx == cellSize && dy == cellSize && dz == 0) || (dx == cellSize && dy == 0 && dz == cellSize) || (dx == 0
                                                                                                              && dy
        == cellSize && dz == cellSize) || (dx == cellSize && dy == cellSize && dz == cellSize);
    }
}
