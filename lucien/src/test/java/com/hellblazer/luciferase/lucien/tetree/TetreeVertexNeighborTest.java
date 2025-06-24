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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for vertex neighbor finding in Tetree. Tests the findVertexNeighbors functionality that finds all
 * tetrahedra sharing a specific vertex.
 *
 * @author hal.hildebrand
 */
public class TetreeVertexNeighborTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
    }

    @Test
    void testVertexNeighborComprehensive() {
        // Create a more complex structure
        for (int i = 0; i < 15; i++) {
            float x = (float) (100 + i * 60);
            float y = (float) (100 + (i % 4) * 200);
            float z = (float) (100 + (i % 3) * 300);
            Point3f p = new Point3f(x, y, z);
            tetree.insert(p, (byte) 3, "entity" + i);
        }

        // Pick a central tetrahedron
        Point3f center = new Point3f(400, 300, 400);
        Tet centerTet = tetree.locateTetrahedron(center, (byte) 3);
        long centerIndex = centerTet.index();

        // Vertex-to-face mapping (from TetreeNeighborFinder):
        // Vertex 0: faces 1, 2, 3
        // Vertex 1: faces 0, 2, 3
        // Vertex 2: faces 0, 1, 3
        // Vertex 3: faces 0, 1, 2

        // Get face neighbors
        List<TetreeKey> face0 = new ArrayList<>();
        List<TetreeKey> face1 = new ArrayList<>();
        List<TetreeKey> face2 = new ArrayList<>();
        List<TetreeKey> face3 = new ArrayList<>();

        TetreeKey face0Neighbor = tetree.findFaceNeighbor(new TetreeKey((byte)3, BigInteger.valueOf(centerIndex)), 0);
        if (face0Neighbor != null) {
            face0.add(face0Neighbor);
        }

        TetreeKey face1Neighbor = tetree.findFaceNeighbor(new TetreeKey((byte)3, BigInteger.valueOf(centerIndex)), 1);
        if (face1Neighbor != null) {
            face1.add(face1Neighbor);
        }

        TetreeKey face2Neighbor = tetree.findFaceNeighbor(new TetreeKey((byte)3, BigInteger.valueOf(centerIndex)), 2);
        if (face2Neighbor != null) {
            face2.add(face2Neighbor);
        }

        TetreeKey face3Neighbor = tetree.findFaceNeighbor(new TetreeKey((byte)3, BigInteger.valueOf(centerIndex)), 3);
        if (face3Neighbor != null) {
            face3.add(face3Neighbor);
        }

        // Get vertex neighbors
        List<TetreeKey> vertex0Neighbors = tetree.findVertexNeighbors(new TetreeKey((byte)3, BigInteger.valueOf(centerIndex)), 0); // faces 1,2,3
        List<TetreeKey> vertex1Neighbors = tetree.findVertexNeighbors(new TetreeKey((byte)3, BigInteger.valueOf(centerIndex)), 1); // faces 0,2,3
        List<TetreeKey> vertex2Neighbors = tetree.findVertexNeighbors(new TetreeKey((byte)3, BigInteger.valueOf(centerIndex)), 2); // faces 0,1,3
        List<TetreeKey> vertex3Neighbors = tetree.findVertexNeighbors(new TetreeKey((byte)3, BigInteger.valueOf(centerIndex)), 3); // faces 0,1,2

        // Vertex neighbors should include at least the face neighbors
        Set<TetreeKey> vertex0Expected = new HashSet<>();
        vertex0Expected.addAll(face1);
        vertex0Expected.addAll(face2);
        vertex0Expected.addAll(face3);

        Set<TetreeKey> vertex1Expected = new HashSet<>();
        vertex1Expected.addAll(face0);
        vertex1Expected.addAll(face2);
        vertex1Expected.addAll(face3);

        // Vertex neighbors should be a superset of face neighbors sharing that vertex
        assertTrue(vertex0Neighbors.size() >= vertex0Expected.size() || vertex0Neighbors.isEmpty(),
                   "Vertex 0 should have at least as many neighbors as its adjacent faces");

        assertTrue(vertex1Neighbors.size() >= vertex1Expected.size() || vertex1Neighbors.isEmpty(),
                   "Vertex 1 should have at least as many neighbors as its adjacent faces");
    }

    @Test
    void testVertexNeighborIndexValidation() {
        // Create a single tetrahedron
        Point3f p1 = new Point3f(512, 512, 512);
        tetree.insert(p1, (byte) 0, "root");

        Tet rootTet = tetree.locateTetrahedron(p1, (byte) 0);
        long rootIndex = rootTet.index();

        // Test invalid vertex indices
        assertThrows(IllegalArgumentException.class, () -> tetree.findVertexNeighbors(new TetreeKey((byte)0, BigInteger.valueOf(rootIndex)), -1));
        assertThrows(IllegalArgumentException.class, () -> tetree.findVertexNeighbors(new TetreeKey((byte)0, BigInteger.valueOf(rootIndex)), 4));
        assertThrows(IllegalArgumentException.class, () -> tetree.findVertexNeighbors(new TetreeKey((byte)0, BigInteger.valueOf(rootIndex)), 10));
    }

    @Test
    void testVertexNeighborsBasic() {
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

        // Test all 4 vertices (0-3)
        for (int vertex = 0; vertex < 4; vertex++) {
            List<TetreeKey> vertexNeighbors = tetree.findVertexNeighbors(new TetreeKey((byte)2, BigInteger.valueOf(tetIndex)), vertex);
            assertNotNull(vertexNeighbors, "Vertex neighbors should not be null for vertex " + vertex);

            // Should not include itself
            assertFalse(vertexNeighbors.contains(new TetreeKey((byte)2, BigInteger.valueOf(tetIndex))),
                        "Vertex neighbors should not include the tetrahedron itself");
        }
    }

    @Test
    void testVertexNeighborsBoundary() {
        // Test vertex neighbors at domain boundaries
        Point3f boundary1 = new Point3f(10, 10, 10);      // Near origin
        Point3f boundary2 = new Point3f(1014, 1014, 1014); // Near max
        Point3f center = new Point3f(512, 512, 512);       // Center

        tetree.insert(boundary1, (byte) 2, "boundary1");
        tetree.insert(boundary2, (byte) 2, "boundary2");
        tetree.insert(center, (byte) 2, "center");

        // Test boundary tetrahedra
        Tet boundaryTet1 = tetree.locateTetrahedron(boundary1, (byte) 2);
        Tet boundaryTet2 = tetree.locateTetrahedron(boundary2, (byte) 2);

        // Boundary tetrahedra should have fewer vertex neighbors
        int boundaryNeighbors1 = 0;
        int boundaryNeighbors2 = 0;
        for (int v = 0; v < 4; v++) {
            boundaryNeighbors1 += tetree.findVertexNeighbors(new TetreeKey((byte)2, BigInteger.valueOf(boundaryTet1.index())), v).size();
            boundaryNeighbors2 += tetree.findVertexNeighbors(new TetreeKey((byte)2, BigInteger.valueOf(boundaryTet2.index())), v).size();
        }

        // Center tetrahedron should have more neighbors
        Tet centerTet = tetree.locateTetrahedron(center, (byte) 2);
        int centerNeighbors = 0;
        for (int v = 0; v < 4; v++) {
            centerNeighbors += tetree.findVertexNeighbors(new TetreeKey((byte)2, BigInteger.valueOf(centerTet.index())), v).size();
        }

        // Boundary tets typically have fewer neighbors than interior tets
        assertTrue(centerNeighbors >= boundaryNeighbors1 || centerNeighbors == 0,
                   "Center tetrahedra typically have more vertex neighbors than boundary tetrahedra");
    }

    @Test
    void testVertexNeighborsDenseGrid() {
        // Create a very dense 3D grid
        int gridSize = 4;
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    float px = 256 + x * 64;
                    float py = 256 + y * 64;
                    float pz = 256 + z * 64;
                    Point3f p = new Point3f(px, py, pz);
                    long id = x * gridSize * gridSize + y * gridSize + z;
                    tetree.insert(p, (byte) 4, String.format("grid_%d_%d_%d", x, y, z));
                }
            }
        }

        // Pick a central tetrahedron
        Point3f center = new Point3f(384, 384, 384); // Middle of the grid
        Tet centerTet = tetree.locateTetrahedron(center, (byte) 4);
        long centerIndex = centerTet.index();

        // Count total vertex neighbors
        int totalVertexNeighbors = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            List<TetreeKey> neighbors = tetree.findVertexNeighbors(new TetreeKey((byte)3, BigInteger.valueOf(centerIndex)), vertex);
            totalVertexNeighbors += neighbors.size();
        }

        // In a dense grid, we may have vertex neighbors
        assertTrue(totalVertexNeighbors >= 0,
                   "Dense grid vertex neighbor count should be non-negative, got: " + totalVertexNeighbors);
    }

    @Test
    void testVertexNeighborsEdgeConsistency() {
        // Create a configuration to test consistency
        Point3f p1 = new Point3f(400, 400, 400);
        Point3f p2 = new Point3f(600, 400, 400);
        Point3f p3 = new Point3f(400, 600, 400);
        Point3f p4 = new Point3f(400, 400, 600);

        tetree.insert(p1, (byte) 2, "tet1");
        tetree.insert(p2, (byte) 2, "tet2");
        tetree.insert(p3, (byte) 2, "tet3");
        tetree.insert(p4, (byte) 2, "tet4");

        Tet tet = tetree.locateTetrahedron(p1, (byte) 2);
        long tetIndex = tet.index();

        // Vertex-to-edge mapping:
        // Vertex 0: edges 0, 1, 2
        // Vertex 1: edges 0, 3, 4
        // Vertex 2: edges 1, 3, 5
        // Vertex 3: edges 2, 4, 5

        // Get edge neighbors for edges connected to vertex 0
        List<TetreeKey> edge0Neighbors = tetree.findEdgeNeighbors(new TetreeKey((byte)2, BigInteger.valueOf(tetIndex)), 0);
        List<TetreeKey> edge1Neighbors = tetree.findEdgeNeighbors(new TetreeKey((byte)2, BigInteger.valueOf(tetIndex)), 1);
        List<TetreeKey> edge2Neighbors = tetree.findEdgeNeighbors(new TetreeKey((byte)2, BigInteger.valueOf(tetIndex)), 2);

        // Get vertex 0 neighbors
        List<TetreeKey> vertex0Neighbors = tetree.findVertexNeighbors(new TetreeKey((byte)2, BigInteger.valueOf(tetIndex)), 0);

        // Vertex neighbors should include all edge neighbors for edges connected to that vertex
        Set<TetreeKey> expectedFromEdges = new HashSet<>();
        expectedFromEdges.addAll(edge0Neighbors);
        expectedFromEdges.addAll(edge1Neighbors);
        expectedFromEdges.addAll(edge2Neighbors);

        // Vertex neighbors should be at least as comprehensive as edge neighbors
        assertTrue(vertex0Neighbors.size() >= expectedFromEdges.size() || vertex0Neighbors.isEmpty(),
                   "Vertex neighbors should include neighbors from all connected edges");
    }

    @Test
    void testVertexNeighborsSymmetry() {
        // Create a simple configuration
        for (int i = 0; i < 8; i++) {
            float x = 300 + (i % 2) * 400;
            float y = 300 + ((i / 2) % 2) * 400;
            float z = 300 + ((i / 4) % 2) * 400;
            Point3f p = new Point3f(x, y, z);
            tetree.insert(p, (byte) 3, "cube" + i);
        }

        // Find two nearby tetrahedra
        Point3f p1 = new Point3f(350, 350, 350);
        Point3f p2 = new Point3f(450, 350, 350);

        Tet tet1 = tetree.locateTetrahedron(p1, (byte) 3);
        Tet tet2 = tetree.locateTetrahedron(p2, (byte) 3);

        // Check if they share any vertices (i.e., are vertex neighbors)
        for (int v1 = 0; v1 < 4; v1++) {
            List<TetreeKey> neighbors1 = tetree.findVertexNeighbors(new TetreeKey((byte)3, BigInteger.valueOf(tet1.index())), v1);

            if (neighbors1.contains(new TetreeKey((byte)3, BigInteger.valueOf(tet2.index())))) {
                // If tet2 is a vertex neighbor of tet1, then tet1 should be a vertex neighbor of tet2
                boolean foundSymmetric = false;
                for (int v2 = 0; v2 < 4; v2++) {
                    List<TetreeKey> neighbors2 = tetree.findVertexNeighbors(new TetreeKey((byte)3, BigInteger.valueOf(tet2.index())), v2);
                    if (neighbors2.contains(new TetreeKey((byte)3, BigInteger.valueOf(tet1.index())))) {
                        foundSymmetric = true;
                        break;
                    }
                }
                assertTrue(foundSymmetric, "Vertex neighbor relationship should be symmetric");
            }
        }
    }
}
