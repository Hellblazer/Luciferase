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
package com.hellblazer.luciferase.lucien.visitor;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for tree traversal with visitor pattern.
 *
 * @author hal.hildebrand
 */
public class TreeTraversalTest {

    private Octree<LongEntityID, String> octree;

    @BeforeEach
    public void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator(), 10,     // max entities per node
                              (byte) 10  // max depth
        );
    }

    @Test
    public void testCancellation() {
        // Insert many entities spread out to create multiple nodes
        Random rand = new Random(42);
        for (int i = 0; i < 200; i++) {
            // Spread entities across wide area to force multiple nodes
            Point3f pos = new Point3f((i % 10) * 1000,  // Create spatial separation
                                      ((i / 10) % 10) * 1000, ((i / 100) % 10) * 1000);
            octree.insert(pos, (byte) 5, "Entity_" + i);  // Lower level to create more nodes
        }

        // Create cancelling visitor
        final int[] nodesVisited = { 0 };
        final int maxNodesToVisit = 3;
        TreeVisitor<LongEntityID, String> cancellingVisitor = new AbstractTreeVisitor<>() {
            @Override
            public boolean visitNode(com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode<LongEntityID> node,
                                     int level, long parentIndex) {
                nodesVisited[0]++;
                // Cancel after maxNodesToVisit nodes
                return nodesVisited[0] < maxNodesToVisit;
            }
        };

        octree.traverse(cancellingVisitor, TraversalStrategy.DEPTH_FIRST);

        // Should have stopped at maxNodesToVisit
        assertTrue(nodesVisited[0] >= 1, "Should visit at least one node");
        assertTrue(nodesVisited[0] <= maxNodesToVisit, "Should not visit more than " + maxNodesToVisit + " nodes");
    }

    @Test
    public void testEntityCollectorVisitor() {
        // Insert entities with different content
        for (int i = 0; i < 30; i++) {
            Point3f pos = new Point3f(i * 10, i * 10, i * 10);
            String content = i % 2 == 0 ? "EVEN_" + i : "ODD_" + i;
            octree.insert(pos, (byte) 10, content);
        }

        // Collect only even entities
        EntityCollectorVisitor<LongEntityID, String> visitor = new EntityCollectorVisitor<>(
        content -> content.startsWith("EVEN"));

        octree.traverse(visitor, TraversalStrategy.BREADTH_FIRST);

        // Verify results
        assertEquals(15, visitor.getCollectedEntities().size(), "Should collect only even entities");
        assertTrue(visitor.getContents().stream().allMatch(c -> c.startsWith("EVEN")),
                   "All collected entities should be even");
    }

    @Test
    public void testMaxDepthLimit() {
        // Insert entities to create deep tree
        insertTestEntities(100);

        // Create visitor with depth limit
        NodeCountVisitor<LongEntityID, String> visitor = new NodeCountVisitor<>();
        visitor.setMaxDepth(3);

        octree.traverse(visitor, TraversalStrategy.DEPTH_FIRST);

        // Verify depth limit
        assertTrue(visitor.getMaxLevelObserved() <= 3, "Should not traverse beyond max depth");
    }

    @Test
    public void testNodeCountVisitor() {
        // Insert entities at various positions
        insertTestEntities(50);

        // Create visitor
        NodeCountVisitor<LongEntityID, String> visitor = new NodeCountVisitor<>();

        // Traverse tree
        octree.traverse(visitor, TraversalStrategy.DEPTH_FIRST);

        // Verify results
        assertTrue(visitor.getTotalNodes() > 0, "Should have visited some nodes");
        assertEquals(50, visitor.getTotalEntities(), "Should count all entities");

        System.out.println(visitor.getStatistics());
    }

    @Test
    public void testTraverseFrom() {
        // Insert entities
        insertTestEntities(20);

        // Get a specific node
        var nodes = octree.nodes().toList();
        assertFalse(nodes.isEmpty(), "Should have some nodes");

        long startNode = nodes.get(0).mortonIndex();

        // Traverse from specific node
        NodeCountVisitor<LongEntityID, String> visitor = new NodeCountVisitor<>();
        octree.traverseFrom(visitor, TraversalStrategy.DEPTH_FIRST, startNode);

        // Should visit at least the start node
        assertTrue(visitor.getTotalNodes() >= 1, "Should visit at least start node");
    }

    @Test
    public void testTraverseRegion() {
        // Insert entities in specific region
        for (int i = 0; i < 20; i++) {
            Point3f pos = new Point3f(100 + i, 100 + i, 100 + i);
            octree.insert(pos, (byte) 10, "Entity_" + i);
        }

        // Define region
        Spatial.Cube region = new Spatial.Cube(90, 90, 90, 30);

        // Traverse only region
        EntityCollectorVisitor<LongEntityID, String> visitor = new EntityCollectorVisitor<>();
        octree.traverseRegion(visitor, region, TraversalStrategy.LEVEL_ORDER);

        // Should find some entities
        assertFalse(visitor.getCollectedEntities().isEmpty(), "Should find entities in region");
    }

    @Test
    public void testVisitorCallbacks() {
        // Insert a few entities
        insertTestEntities(5);

        // Track callback order
        List<String> callOrder = new ArrayList<>();

        TreeVisitor<LongEntityID, String> trackingVisitor = new TreeVisitor<>() {
            @Override
            public void beginTraversal(int totalNodes, int totalEntities) {
                callOrder.add("beginTraversal");
            }

            @Override
            public void endTraversal(int nodesVisited, int entitiesVisited) {
                callOrder.add("endTraversal");
            }

            @Override
            public void leaveNode(com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode<LongEntityID> node,
                                  int level, int childCount) {
                callOrder.add("leaveNode:" + node.mortonIndex());
            }

            @Override
            public void visitEntity(LongEntityID entityId, String content, long nodeIndex, int level) {
                callOrder.add("visitEntity:" + entityId);
            }

            @Override
            public boolean visitNode(com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode<LongEntityID> node,
                                     int level, long parentIndex) {
                callOrder.add("visitNode:" + node.mortonIndex());
                return true;
            }
        };

        octree.traverse(trackingVisitor, TraversalStrategy.DEPTH_FIRST);

        // Verify callback order
        assertEquals("beginTraversal", callOrder.get(0), "Should start with beginTraversal");
        assertEquals("endTraversal", callOrder.get(callOrder.size() - 1), "Should end with endTraversal");

        // Should have some node visits
        assertTrue(callOrder.stream().anyMatch(s -> s.startsWith("visitNode")), "Should have node visits");
        assertTrue(callOrder.stream().anyMatch(s -> s.startsWith("leaveNode")), "Should have leave node calls");
    }

    private void insertTestEntities(int count) {
        Random rand = new Random(42);
        for (int i = 0; i < count; i++) {
            Point3f pos = new Point3f(rand.nextFloat() * 1000, rand.nextFloat() * 1000, rand.nextFloat() * 1000);
            octree.insert(pos, (byte) 10, "Entity_" + i);
        }
    }
}
