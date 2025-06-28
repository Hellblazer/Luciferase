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
import java.util.NoSuchElementException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TetreeIterator. Validates different traversal orders and level restrictions.
 *
 * @author hal.hildebrand
 */
public class TetreeIteratorTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    public void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }

    @Test
    public void testBreadthFirstOrder() {
        // Build a small tree with known structure
        buildTestTree();

        // Traverse breadth-first
        TetreeIterator<LongEntityID, String> iter = TetreeIterator.breadthFirst(tetree);

        int nodeCount = 0;

        while (iter.hasNext()) {
            iter.next();
            nodeCount++;
        }

        assertEquals(tetree.getSortedSpatialIndices().size(), nodeCount, "Should visit all nodes");
    }

    @Test
    public void testDepthFirstPreOrder() {
        // Build a small tree with known structure
        buildTestTree();

        // Traverse depth-first pre-order
        TetreeIterator<LongEntityID, String> iter = TetreeIterator.depthFirstPre(tetree);

        var indices = new ArrayList<>();

        while (iter.hasNext()) {
            iter.next();
            var index = iter.getCurrentIndex();
            indices.add(index);
        }

        // Verify we visited nodes
        assertFalse(indices.isEmpty(), "Should have visited nodes");
        assertEquals(tetree.getSortedSpatialIndices().size(), indices.size(), "Should visit all nodes");
    }

    @Test
    public void testEmptyTreeIteration() {
        // Test all traversal orders on empty tree
        for (TetreeIterator.TraversalOrder order : TetreeIterator.TraversalOrder.values()) {
            TetreeIterator<LongEntityID, String> iter = new TetreeIterator<>(tetree, order);
            assertFalse(iter.hasNext(), "Empty tree should have no elements for " + order);
        }
    }

    @Test
    public void testIteratorInvalidation() {
        // Build a small tree
        buildTestTree();

        // Create iterator
        TetreeIterator<LongEntityID, String> iter = TetreeIterator.sfcOrder(tetree);

        // Verify initial state
        assertTrue(iter.hasNext());

        // Consume all elements
        while (iter.hasNext()) {
            iter.next();
        }

        // Verify exhausted iterator
        assertFalse(iter.hasNext());
        assertThrows(NoSuchElementException.class, () -> iter.next());
    }

    @Test
    public void testSFCOrder() {
        // Build a simple tree to test SFC order traversal
        // Use lower levels to avoid BigInteger memory issues
        tetree.insert(new Point3f(100, 100, 100), (byte) 2, "entity1");
        tetree.insert(new Point3f(200, 200, 200), (byte) 2, "entity2");

        // Traverse in SFC order
        TetreeIterator<LongEntityID, String> iter = TetreeIterator.sfcOrder(tetree);

        // Just verify basic functionality - that we can iterate without errors
        int nodeCount = 0;
        int maxIterations = 5; // Prevent infinite loops

        while (iter.hasNext() && nodeCount < maxIterations) {
            TetreeNodeImpl<LongEntityID> node = iter.next();
            assertNotNull(node, "Node should not be null");
            assertNotNull(iter.getCurrentIndex(), "Current index should not be null");
            nodeCount++;
        }

        // We should have visited at least one node
        assertTrue(nodeCount > 0, "Should have visited at least one node");

        // The iterator should be exhausted after visiting all nodes
        if (nodeCount < maxIterations) {
            assertFalse(iter.hasNext(), "Iterator should be exhausted");
        }
    }

    @Test
    public void testSingleNodeIteration() {
        // Add single entity
        LongEntityID id1 = tetree.insert(new Point3f(50, 50, 50), (byte) 3, "root");

        // Test all traversal orders
        for (TetreeIterator.TraversalOrder order : TetreeIterator.TraversalOrder.values()) {
            TetreeIterator<LongEntityID, String> iter = new TetreeIterator<>(tetree, order);

            assertTrue(iter.hasNext(), "Should have one element for " + order);
            TetreeNodeImpl<LongEntityID> node = iter.next();
            assertNotNull(node);
            assertTrue(node.getEntityIds().contains(id1));

            assertFalse(iter.hasNext(), "Should have no more elements");
        }
    }

    @Test
    public void testUnsupportedSkipSubtree() {
        buildTestTree();

        // Try to skip subtree in breadth-first traversal
        TetreeIterator<LongEntityID, String> iter = TetreeIterator.breadthFirst(tetree);

        assertTrue(iter.hasNext());
        iter.next();

        assertThrows(UnsupportedOperationException.class, () -> iter.skipSubtree(),
                     "skipSubtree should not be supported for breadth-first traversal");
    }

    // Helper method to build a multi-level tree
    private void buildMultiLevelTree() {
        // Add entities that will force multiple subdivision levels
        Random rand = new Random(42);

        for (int i = 0; i < 20; i++) {
            float x = rand.nextFloat() * 100;
            float y = rand.nextFloat() * 100;
            float z = rand.nextFloat() * 100;

            tetree.insert(new Point3f(x, y, z), (byte) 12, "entity" + i);
        }
    }

    // Helper method to build a sparse tree
    private void buildSparseTree() {
        // Add entities in specific corners to create a sparse tree
        tetree.insert(new Point3f(10, 10, 10), (byte) 12, "low");
        tetree.insert(new Point3f(90, 90, 90), (byte) 12, "high");
        tetree.insert(new Point3f(10, 90, 10), (byte) 12, "mixed1");
        tetree.insert(new Point3f(90, 10, 90), (byte) 12, "mixed2");
    }

    // Helper method to build a simple test tree
    private void buildTestTree() {
        // Add entities at different positions to create a small tree
        // Use lower levels (3-5) to avoid large BigInteger values in tmIndex
        tetree.insert(new Point3f(50, 50, 50), (byte) 3, "center");
        tetree.insert(new Point3f(25, 25, 25), (byte) 3, "corner1");
        tetree.insert(new Point3f(75, 75, 75), (byte) 3, "corner2");
        tetree.insert(new Point3f(25, 75, 25), (byte) 3, "corner3");
    }
}
