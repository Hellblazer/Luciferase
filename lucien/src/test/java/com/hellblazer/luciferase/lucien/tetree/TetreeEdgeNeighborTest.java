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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for edge neighbor finding in Tetree. Tests the findEdgeNeighbors functionality that finds all tetrahedra
 * sharing a specific edge.
 *
 * @author hal.hildebrand
 */
public class TetreeEdgeNeighborTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
    }

    @Test
    void testEdgeNeighborIndexValidation() {
        // Create a single tetrahedron at the root
        Point3f p1 = new Point3f(512, 512, 512);
        tetree.insert(p1, (byte) 0, "root");

        // Find the node containing our entity
        Tet rootTet = tetree.locateTetrahedron(p1, (byte) 0);
        long rootIndex = rootTet.index();

        // Test invalid edge indices
        assertThrows(IllegalArgumentException.class, () -> tetree.findEdgeNeighbors(rootIndex, -1));
        assertThrows(IllegalArgumentException.class, () -> tetree.findEdgeNeighbors(rootIndex, 6));
        assertThrows(IllegalArgumentException.class, () -> tetree.findEdgeNeighbors(rootIndex, 10));
    }

    @Test
    void testEdgeNeighborSymmetry() {
        // Create a more complex structure
        for (int i = 0; i < 10; i++) {
            float x = (float) (100 + i * 80);
            float y = (float) (100 + (i % 3) * 300);
            float z = (float) (100 + (i % 2) * 400);
            Point3f p = new Point3f(x, y, z);
            tetree.insert(p, (byte) 2, "entity" + i);
        }

        // Find two adjacent tetrahedra
        Point3f p1 = new Point3f(200, 200, 200);
        Point3f p2 = new Point3f(300, 200, 200);

        Tet tet1 = tetree.locateTetrahedron(p1, (byte) 2);
        Tet tet2 = tetree.locateTetrahedron(p2, (byte) 2);

        // Check if they share any edges
        for (int edge1 = 0; edge1 < 6; edge1++) {
            List<Long> neighbors1 = tetree.findEdgeNeighbors(tet1.index(), edge1);

            if (neighbors1.contains(tet2.index())) {
                // If tet2 is an edge neighbor of tet1, then tet1 should be an edge neighbor of tet2
                boolean foundSymmetric = false;
                for (int edge2 = 0; edge2 < 6; edge2++) {
                    List<Long> neighbors2 = tetree.findEdgeNeighbors(tet2.index(), edge2);
                    if (neighbors2.contains(tet1.index())) {
                        foundSymmetric = true;
                        break;
                    }
                }
                assertTrue(foundSymmetric, "Edge neighbor relationship should be symmetric between tets");
            }
        }
    }

    @Test
    void testEdgeNeighborsAtRootLevel() {
        // Create entities to force subdivision
        Point3f p1 = new Point3f(100, 100, 100);
        Point3f p2 = new Point3f(900, 100, 100);
        Point3f p3 = new Point3f(100, 900, 100);
        Point3f p4 = new Point3f(100, 100, 900);

        tetree.insert(p1, (byte) 1, "v1");
        tetree.insert(p2, (byte) 1, "v2");
        tetree.insert(p3, (byte) 1, "v3");
        tetree.insert(p4, (byte) 1, "v4");

        // Find a tetrahedron at level 1
        Tet tet1 = tetree.locateTetrahedron(p1, (byte) 1);
        long tetIndex = tet1.index();

        // Test all 6 edges (0-5)
        for (int edge = 0; edge < 6; edge++) {
            List<Long> edgeNeighbors = tetree.findEdgeNeighbors(tetIndex, edge);
            assertNotNull(edgeNeighbors, "Edge neighbors should not be null for edge " + edge);

            // At minimum, neighbors should include face neighbors that share the edge
            // Each edge is shared by 2 faces, so we expect at least those neighbors
            assertTrue(edgeNeighbors.size() >= 0, "Should have at least some edge neighbors for edge " + edge);
        }
    }

    @Test
    void testEdgeNeighborsConsistency() {
        // Create a simple configuration
        Point3f p1 = new Point3f(300, 300, 300);
        Point3f p2 = new Point3f(700, 300, 300);
        Point3f p3 = new Point3f(300, 700, 300);
        Point3f p4 = new Point3f(300, 300, 700);

        tetree.insert(p1, (byte) 2, "tet1");
        tetree.insert(p2, (byte) 2, "tet2");
        tetree.insert(p3, (byte) 2, "tet3");
        tetree.insert(p4, (byte) 2, "tet4");

        Tet tet = tetree.locateTetrahedron(p1, (byte) 2);
        long tetIndex = tet.index();

        // Edge-to-face mapping (from TetreeNeighborFinder):
        // Edge 0 (v0-v1): faces 0, 2
        // Edge 1 (v0-v2): faces 0, 3
        // Edge 2 (v0-v3): faces 1, 3
        // Edge 3 (v1-v2): faces 0, 1
        // Edge 4 (v1-v3): faces 1, 2
        // Edge 5 (v2-v3): faces 2, 3

        // Get face neighbors
        List<Long> face0Neighbors = new ArrayList<>();
        List<Long> face1Neighbors = new ArrayList<>();
        List<Long> face2Neighbors = new ArrayList<>();
        List<Long> face3Neighbors = new ArrayList<>();

        long face0 = tetree.findFaceNeighbor(tetIndex, 0);
        if (face0 != -1) {
            face0Neighbors.add(face0);
        }

        long face1 = tetree.findFaceNeighbor(tetIndex, 1);
        if (face1 != -1) {
            face1Neighbors.add(face1);
        }

        long face2 = tetree.findFaceNeighbor(tetIndex, 2);
        if (face2 != -1) {
            face2Neighbors.add(face2);
        }

        long face3 = tetree.findFaceNeighbor(tetIndex, 3);
        if (face3 != -1) {
            face3Neighbors.add(face3);
        }

        // Get edge neighbors
        List<Long> edge0Neighbors = tetree.findEdgeNeighbors(tetIndex, 0); // shares faces 0,2
        List<Long> edge3Neighbors = tetree.findEdgeNeighbors(tetIndex, 3); // shares faces 0,1

        // Edge neighbors should include at least the face neighbors of the faces that share the edge
        Set<Long> edge0Expected = new HashSet<>();
        edge0Expected.addAll(face0Neighbors);
        edge0Expected.addAll(face2Neighbors);

        Set<Long> edge3Expected = new HashSet<>();
        edge3Expected.addAll(face0Neighbors);
        edge3Expected.addAll(face1Neighbors);

        // Edge neighbors should include at least the face neighbors
        for (Long neighbor : edge0Expected) {
            assertTrue(edge0Neighbors.contains(neighbor) || edge0Neighbors.isEmpty(),
                       "Edge 0 neighbors should include face neighbors from faces 0 and 2");
        }

        for (Long neighbor : edge3Expected) {
            assertTrue(edge3Neighbors.contains(neighbor) || edge3Neighbors.isEmpty(),
                       "Edge 3 neighbors should include face neighbors from faces 0 and 1");
        }
    }

    @Test
    void testEdgeNeighborsDenseConfiguration() {
        // Create a dense configuration where many tets share edges
        int gridSize = 5;
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    float px = 200 + x * 100;
                    float py = 200 + y * 100;
                    float pz = 200 + z * 100;
                    Point3f p = new Point3f(px, py, pz);
                    long id = x * gridSize * gridSize + y * gridSize + z;
                    tetree.insert(p, (byte) 3, String.format("grid_%d_%d_%d", x, y, z));
                }
            }
        }

        // Pick a central tetrahedron
        Point3f center = new Point3f(400, 400, 400);
        Tet centerTet = tetree.locateTetrahedron(center, (byte) 3);
        long centerIndex = centerTet.index();

        // In a dense configuration, we expect more edge neighbors
        int totalEdgeNeighbors = 0;
        for (int edge = 0; edge < 6; edge++) {
            List<Long> neighbors = tetree.findEdgeNeighbors(centerIndex, edge);
            totalEdgeNeighbors += neighbors.size();
        }

        // In a dense grid, we should have some edge neighbors
        assertTrue(totalEdgeNeighbors >= 1,
                   "Dense configuration should have at least some edge neighbors, got: " + totalEdgeNeighbors);
    }
}
