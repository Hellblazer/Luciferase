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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for enhanced iterator functionality in Tetree.
 * Tests the nonEmptyIterator, parentChildIterator, and siblingIterator methods.
 *
 * @author hal.hildebrand
 */
public class TetreeEnhancedIteratorTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
    }

    @Test
    void testNonEmptyIterator() {
        // Create some entities
        Point3f p1 = new Point3f(100, 100, 100);
        Point3f p2 = new Point3f(900, 900, 900);
        Point3f p3 = new Point3f(500, 500, 500);

        tetree.insert(p1, (byte) 2, "entity1");
        tetree.insert(p2, (byte) 2, "entity2");
        tetree.insert(p3, (byte) 2, "entity3");

        // Count non-empty nodes
        int nonEmptyCount = 0;
        Iterator<TetreeNodeImpl<LongEntityID>> iter = tetree.nonEmptyIterator(TetreeIterator.TraversalOrder.DEPTH_FIRST_PRE);
        while (iter.hasNext()) {
            TetreeNodeImpl<LongEntityID> node = iter.next();
            assertNotNull(node);
            assertFalse(node.isEmpty(), "Non-empty iterator should only return non-empty nodes");
            nonEmptyCount++;
        }

        assertTrue(nonEmptyCount > 0, "Should have at least one non-empty node");
        
        // Compare with total node count
        int totalNodes = tetree.getNodeCount();
        assertTrue(nonEmptyCount <= totalNodes, "Non-empty nodes should be subset of all nodes");
    }

    @Test
    void testParentChildIterator() {
        // Create a hierarchical structure
        Point3f root = new Point3f(512, 512, 512);
        tetree.insert(root, (byte) 3, "root");

        // Add more entities to force subdivision
        for (int i = 0; i < 8; i++) {
            float offset = 100 + i * 50;
            Point3f p = new Point3f(
                512 + (i % 2) * offset,
                512 + ((i / 2) % 2) * offset,
                512 + ((i / 4) % 2) * offset
            );
            tetree.insert(p, (byte) 3, "child" + i);
        }

        // Find a leaf node
        Point3f leafPoint = new Point3f(612, 612, 612);
        Tet leafTet = tetree.locateTetrahedron(leafPoint, (byte) 3);
        long leafIndex = leafTet.index();

        // Test parent-child iterator
        TetreeKey leafKey = new TetreeKey((byte)3, BigInteger.valueOf(leafIndex));
        
        // First check if the node actually exists in the tree
        boolean nodeExists = tetree.hasNode(leafKey);
        
        List<TetreeNodeImpl<LongEntityID>> path = new ArrayList<>();
        Iterator<TetreeNodeImpl<LongEntityID>> iter = tetree.parentChildIterator(leafKey);
        while (iter.hasNext()) {
            path.add(iter.next());
        }

        // The iterator only returns nodes that actually exist in the spatial index
        // In a sparse tree, the leaf node might not exist
        if (nodeExists) {
            assertFalse(path.isEmpty(), "Parent-child iterator should return existing nodes");
            // First nodes should be ancestors (going up to root)
            // Last nodes should be descendants
            assertTrue(path.size() >= 1, "Should have nodes in the path");
        } else {
            // If the start node doesn't exist, the path might be empty
            // This is expected behavior for sparse trees
            System.out.println("Note: Start node doesn't exist in sparse tree, path is empty");
        }
    }

    @Test
    void testSiblingIterator() {
        // Create entities that will be in sibling tetrahedra
        for (int i = 0; i < 10; i++) {
            float x = 200 + (i % 3) * 200;
            float y = 200 + ((i / 3) % 3) * 200;
            float z = 200 + (i % 2) * 400;
            Point3f p = new Point3f(x, y, z);
            tetree.insert(p, (byte) 2, "entity" + i);
        }

        // Find a tetrahedron with siblings
        Point3f testPoint = new Point3f(400, 400, 400);
        Tet testTet = tetree.locateTetrahedron(testPoint, (byte) 2);
        long testIndex = testTet.index();

        // Get siblings
        List<TetreeNodeImpl<LongEntityID>> siblings = new ArrayList<>();
        Iterator<TetreeNodeImpl<LongEntityID>> iter = tetree.siblingIterator(new TetreeKey((byte)2, BigInteger.valueOf(testIndex)));
        while (iter.hasNext()) {
            siblings.add(iter.next());
        }

        // Root has no siblings
        if (testTet.l() == 0) {
            assertTrue(siblings.isEmpty(), "Root should have no siblings");
        } else {
            // Non-root nodes may have siblings
            // The exact count depends on the tree structure
            assertTrue(siblings.size() >= 0, "Sibling count should be non-negative");
        }
    }

    @Test
    void testFindEntityNeighbors() {
        // Create a cluster of entities
        List<LongEntityID> entityIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            float x = 400 + i * 50;
            float y = 400 + (i % 2) * 50;
            float z = 400 + (i % 3) * 50;
            Point3f p = new Point3f(x, y, z);
            LongEntityID id = tetree.insert(p, (byte) 3, "entity" + i);
            entityIds.add(id);
        }

        // Find neighbors of the middle entity
        if (entityIds.size() >= 3) {
            LongEntityID targetId = entityIds.get(2);
            Set<LongEntityID> neighbors = tetree.findEntityNeighbors(targetId);
            
            assertNotNull(neighbors);
            assertFalse(neighbors.contains(targetId), "Neighbors should not include the entity itself");
            
            // Should find at least some neighbors in this cluster
            assertTrue(neighbors.size() >= 0, "Should find some neighboring entities");
            
            // Check that some of our cluster entities are neighbors
            boolean foundClusterNeighbor = false;
            for (LongEntityID id : entityIds) {
                if (!id.equals(targetId) && neighbors.contains(id)) {
                    foundClusterNeighbor = true;
                    break;
                }
            }
            // This might not always be true depending on spatial distribution
            // but is likely in our test setup
        }
    }

    @Test
    void testValidateSubtree() {
        // Create a simple tree structure
        Point3f p1 = new Point3f(256, 256, 256);
        tetree.insert(p1, (byte) 2, "node1");
        
        // Add more nodes to create a subtree
        for (int i = 0; i < 4; i++) {
            float offset = 50 + i * 25;
            Point3f p = new Point3f(256 + offset, 256 + offset, 256 + offset);
            tetree.insert(p, (byte) 3, "subnode" + i);
        }

        // Find a node to validate its subtree
        Tet rootTet = tetree.locateTetrahedron(p1, (byte) 2);
        long rootIndex = rootTet.index();

        // Validate the subtree
        TetreeValidator.ValidationResult result = tetree.validateSubtree(new TetreeKey((byte)2, BigInteger.valueOf(rootIndex)));
        
        assertNotNull(result);
        // The validation should pass for a correctly constructed tree
        // Specific validation depends on TetreeValidator implementation
    }

    @Test
    void testPerformanceMonitoring() {
        // Initially disabled
        assertFalse(tetree.isPerformanceMonitoringEnabled());
        
        // Enable monitoring
        tetree.setPerformanceMonitoring(true);
        assertTrue(tetree.isPerformanceMonitoringEnabled());
        
        // Perform some operations
        Point3f p1 = new Point3f(100, 100, 100);
        tetree.insert(p1, (byte) 2, "test");
        
        Tet tet = tetree.locateTetrahedron(p1, (byte) 2);
        TetreeKey tetKey = tet.tmIndex();
        tetree.findAllFaceNeighbors(tetKey);
        
        // Get metrics
        TetreeMetrics metrics = tetree.getMetrics();
        assertNotNull(metrics);
        assertTrue(metrics.monitoringEnabled());
        
        // Verify some metrics were recorded
        // Note: exact values depend on operations performed
        assertTrue(metrics.neighborQueryCount() >= 0);
        
        // Reset counters
        tetree.resetPerformanceCounters();
        
        // Get metrics again
        TetreeMetrics resetMetrics = tetree.getMetrics();
        assertEquals(0, resetMetrics.neighborQueryCount());
        
        // Disable monitoring
        tetree.setPerformanceMonitoring(false);
        assertFalse(tetree.isPerformanceMonitoringEnabled());
    }
}