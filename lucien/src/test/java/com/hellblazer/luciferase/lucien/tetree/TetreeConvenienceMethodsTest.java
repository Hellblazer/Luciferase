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

import com.hellblazer.luciferase.lucien.Spatial.Sphere;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for convenience methods in Tetree.
 * Tests findNeighborsWithinDistance, findCommonAncestor, and other helper methods.
 *
 * @author hal.hildebrand
 */
public class TetreeConvenienceMethodsTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
    }

    @Test
    void testFindNeighborsWithinDistance() {
        // Create a cluster of entities
        Point3f center = new Point3f(500, 500, 500);
        tetree.insert(center, (byte) 3, "center");

        // Add nearby entities at various distances
        float[] distances = {50, 100, 150, 200, 250};
        long id = 10;
        for (float dist : distances) {
            for (int angle = 0; angle < 4; angle++) {
                float x = center.x + dist * (float)Math.cos(angle * Math.PI / 2);
                float y = center.y + dist * (float)Math.sin(angle * Math.PI / 2);
                Point3f p = new Point3f(x, y, center.z);
                tetree.insert(p, (byte) 3, "dist" + dist + "_angle" + angle);
            }
        }

        // Find the center tetrahedron
        Tet centerTet = tetree.locateTetrahedron(center, (byte) 3);
        long centerIndex = centerTet.index();

        // Test finding neighbors within different distances
        Set<TetreeNodeImpl<LongEntityID>> within100 = tetree.findNeighborsWithinDistance(centerIndex, 100f);
        Set<TetreeNodeImpl<LongEntityID>> within200 = tetree.findNeighborsWithinDistance(centerIndex, 200f);
        Set<TetreeNodeImpl<LongEntityID>> within300 = tetree.findNeighborsWithinDistance(centerIndex, 300f);

        // Should find more nodes as distance increases
        assertTrue(within100.size() <= within200.size(), 
            "Larger radius should find at least as many neighbors");
        assertTrue(within200.size() <= within300.size(), 
            "Even larger radius should find at least as many neighbors");

        // Verify we found some nodes
        assertFalse(within100.isEmpty(), "Should find at least some nodes within distance");
    }

    @Test
    void testFindNeighborsWithinDistanceEmpty() {
        // Test with empty tree
        Tet rootTet = new Tet(0, 0, 0, (byte)0, (byte)0);
        Set<TetreeNodeImpl<LongEntityID>> neighbors = tetree.findNeighborsWithinDistance(rootTet.index(), 100f);
        
        assertNotNull(neighbors);
        assertTrue(neighbors.isEmpty(), "Empty tree should return empty neighbor set");
    }

    @Test
    void testFindNeighborsWithinDistanceZeroRadius() {
        // Create some entities
        Point3f p1 = new Point3f(300, 300, 300);
        tetree.insert(p1, (byte) 2, "entity1");

        Tet tet = tetree.locateTetrahedron(p1, (byte) 2);
        
        // Zero radius should only find the node itself (if it exists)
        Set<TetreeNodeImpl<LongEntityID>> neighbors = tetree.findNeighborsWithinDistance(tet.index(), 0f);
        
        // Should find at most the node itself
        assertTrue(neighbors.size() <= 1, "Zero radius should find at most the node itself");
    }

    @Test
    void testFindCommonAncestor() {
        // Create entities to build a tree structure
        Point3f p1 = new Point3f(100, 100, 100);
        Point3f p2 = new Point3f(200, 100, 100);
        Point3f p3 = new Point3f(100, 200, 100);
        Point3f p4 = new Point3f(900, 900, 900);

        tetree.insert(p1, (byte) 3, "tet1");
        tetree.insert(p2, (byte) 3, "tet2");
        tetree.insert(p3, (byte) 3, "tet3");
        tetree.insert(p4, (byte) 3, "tet4");

        // Find tetrahedra at specific levels
        Tet tet1 = tetree.locateTetrahedron(p1, (byte) 3);
        Tet tet2 = tetree.locateTetrahedron(p2, (byte) 3);
        Tet tet3 = tetree.locateTetrahedron(p3, (byte) 3);
        Tet tet4 = tetree.locateTetrahedron(p4, (byte) 3);

        // Test common ancestor of nearby tets
        long ancestor12 = tetree.findCommonAncestor(tet1.index(), tet2.index());
        long ancestor13 = tetree.findCommonAncestor(tet1.index(), tet3.index());
        long ancestor14 = tetree.findCommonAncestor(tet1.index(), tet4.index());

        // Nearby tets should have a closer common ancestor than distant ones
        byte level12 = Tet.tetLevelFromIndex(ancestor12);
        byte level14 = Tet.tetLevelFromIndex(ancestor14);
        
        assertTrue(level12 >= level14, 
            "Nearby tets should have a common ancestor at same or higher level than distant tets");

        // Test with single tet
        long selfAncestor = tetree.findCommonAncestor(tet1.index());
        assertEquals(tet1.index(), selfAncestor, 
            "Common ancestor of single tet should be itself");

        // Test with empty array
        long emptyAncestor = tetree.findCommonAncestor();
        assertEquals(0L, emptyAncestor, "Empty array should return root (0)");
    }

    @Test
    void testFindCommonAncestorMultiple() {
        // Create a configuration where we know the structure
        Point3f[] points = {
            new Point3f(100, 100, 100),
            new Point3f(150, 100, 100),
            new Point3f(100, 150, 100),
            new Point3f(150, 150, 100),
            new Point3f(800, 800, 800)
        };

        for (int i = 0; i < points.length; i++) {
            tetree.insert(points[i], (byte) 4, "entity" + i);
        }

        // Get tets at high level for precision
        Tet[] tets = new Tet[points.length];
        for (int i = 0; i < points.length; i++) {
            tets[i] = tetree.locateTetrahedron(points[i], (byte) 4);
        }

        // First 4 tets are close together, should have nearby common ancestor
        long ancestor0123 = tetree.findCommonAncestor(
            tets[0].index(), tets[1].index(), tets[2].index(), tets[3].index());

        // All 5 tets include a distant one, should have lower level ancestor
        long ancestorAll = tetree.findCommonAncestor(
            tets[0].index(), tets[1].index(), tets[2].index(), 
            tets[3].index(), tets[4].index());

        byte level0123 = Tet.tetLevelFromIndex(ancestor0123);
        byte levelAll = Tet.tetLevelFromIndex(ancestorAll);

        assertTrue(level0123 >= levelAll, 
            "Clustered tets should have higher level common ancestor than mixed with distant tet");
    }

    @Test
    void testFindNeighborsWithinDistanceBoundary() {
        // Test behavior at domain boundaries
        Point3f boundary = new Point3f(50, 50, 50);  // Near origin
        Point3f farBoundary = new Point3f(950, 950, 950);  // Near max

        tetree.insert(boundary, (byte) 2, "boundary");
        tetree.insert(farBoundary, (byte) 2, "farBoundary");

        // Add some nearby entities
        for (int i = 0; i < 5; i++) {
            Point3f p = new Point3f(
                boundary.x + i * 20,
                boundary.y + i * 20,
                boundary.z + i * 20
            );
            tetree.insert(p, (byte) 2, "near" + i);
        }

        Tet boundaryTet = tetree.locateTetrahedron(boundary, (byte) 2);
        Set<TetreeNodeImpl<LongEntityID>> neighbors = tetree.findNeighborsWithinDistance(boundaryTet.index(), 100f);

        // Should find some neighbors even at boundary
        assertFalse(neighbors.isEmpty(), "Should find neighbors even at domain boundary");

        // Verify the search respects domain boundaries
        assertTrue(neighbors.size() >= 1, "Should find at least one neighbor node");
    }

    @Test
    void testConvenienceMethodsIntegration() {
        // Test integration of multiple convenience methods
        // Create a structured layout
        Point3f[] centers = {
            new Point3f(200, 200, 200),
            new Point3f(800, 200, 200),
            new Point3f(200, 800, 200),
            new Point3f(800, 800, 200)
        };

        // Create clusters around each center
        long id = 1;
        for (int c = 0; c < centers.length; c++) {
            Point3f center = centers[c];
            for (int i = 0; i < 5; i++) {
                float offset = i * 30;
                Point3f p = new Point3f(
                    center.x + offset,
                    center.y + offset,
                    center.z
                );
                tetree.insert(p, (byte) 3, "cluster" + c + "_entity" + i);
                id++;
            }
        }

        // Find common ancestor of opposite corners
        Tet corner1 = tetree.locateTetrahedron(centers[0], (byte) 3);
        Tet corner2 = tetree.locateTetrahedron(centers[3], (byte) 3);
        long commonAncestor = tetree.findCommonAncestor(corner1.index(), corner2.index());

        // Find neighbors within distance from one corner
        Set<TetreeNodeImpl<LongEntityID>> nearbyNodes = tetree.findNeighborsWithinDistance(corner1.index(), 200f);

        // Use stream API to count entities in nearby nodes
        long nearbyEntityCount = nearbyNodes.stream()
            .mapToLong(node -> node.getEntityIds().size())
            .sum();

        assertTrue(nearbyEntityCount >= 5, "Should find at least the entities in the corner cluster");

        // Verify common ancestor is at a reasonable level
        byte ancestorLevel = Tet.tetLevelFromIndex(commonAncestor);
        assertTrue(ancestorLevel <= 2, "Common ancestor of opposite corners should be at low level");
    }
}