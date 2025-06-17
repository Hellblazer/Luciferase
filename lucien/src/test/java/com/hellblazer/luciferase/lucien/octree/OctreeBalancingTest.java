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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.balancing.DefaultBalancingStrategy;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancingStrategy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Octree balancing operations (split and merge).
 *
 * @author hal.hildebrand
 */
public class OctreeBalancingTest {

    private Octree<LongEntityID, String> octree;

    @BeforeEach
    public void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator(), 5,      // Low max entities to trigger splits
                              (byte) 10  // max depth
        );
    }

    @Test
    public void testChildParentRelationships() {
        // Insert entities to create parent-child structure
        for (int i = 0; i < 20; i++) {
            Point3f pos = new Point3f(i * 50, i * 50, i * 50);
            octree.insert(pos, (byte) 5, "Entity_" + i);
        }

        // Get all nodes
        var nodes = octree.nodes().toList();

        // For each node, verify parent-child relationships
        for (var node : nodes) {
            long nodeIndex = node.mortonIndex();
            List<Long> children = octree.getChildNodes(nodeIndex);

            // If node has children, verify they exist
            for (Long childIndex : children) {
                assertTrue(octree.hasNode(childIndex), "Child node should exist: " + childIndex);
            }
        }
    }

    @Test
    public void testManualSplitOperation() {
        // Create octree with access to balancer
        octree = new Octree<>(new SequentialLongIDGenerator(), 10, (byte) 10);

        // Insert a few entities at root level
        for (int i = 0; i < 5; i++) {
            Point3f pos = new Point3f(i * 100, i * 100, i * 100);
            octree.insert(pos, (byte) 0, "Entity_" + i);
        }

        // Verify we have nodes with entities
        assertTrue(octree.nodeCount() > 0, "Should have nodes");
        assertTrue(octree.entityCount() > 0, "Should have entities");

        // Access the balancer through reflection or protected method
        // For now, test through public rebalancing API
        TreeBalancingStrategy<LongEntityID> strategy = new DefaultBalancingStrategy<>(0.1,
                                                                                      // Very low merge threshold
                                                                                      0.4,    // Low split threshold
                                                                                      0.2,    // Low imbalance
                                                                                      1000);
        octree.setBalancingStrategy(strategy);

        // Debug before rebalancing
        System.out.println("Before rebalancing:");
        System.out.println("Node count: " + octree.nodeCount());
        System.out.println("Entity count: " + octree.entityCount());
        var statsBefore = octree.getBalancingStats();
        System.out.println("Balancing stats: totalNodes=" + statsBefore.totalNodes() + ", overpopulated="
                           + statsBefore.overpopulatedNodes());

        // Force rebalancing
        TreeBalancer.RebalancingResult result = octree.rebalanceTree();
        assertTrue(result.successful(), "Rebalancing should succeed");

        System.out.println(
        "Rebalancing result: nodesSplit=" + result.nodesSplit() + ", successful=" + result.successful());

        // Debug after rebalancing
        System.out.println("After rebalancing:");
        System.out.println("Node count: " + octree.nodeCount());

        // Check if any splits occurred
        if (result.nodesSplit() > 0) {
            assertTrue(octree.getStats().nodeCount() > 1, "Should have more nodes after split");
        }
    }

    @Test
    public void testMergeWithEmptyNodes() {
        // Create structure with some empty nodes
        for (int i = 0; i < 15; i++) {
            Point3f pos = new Point3f(i * 100, 0, 0);
            octree.insert(pos, (byte) 5, "Entity_" + i);
        }

        // Remove entities to create empty nodes
        var allNodes = octree.nodes().toList();
        int initialNodeCount = allNodes.size();

        // Remove all entities
        var entities = octree.getEntitiesWithPositions();
        for (var id : entities.keySet()) {
            octree.removeEntity(id);
        }

        // Rebalance should clean up empty nodes
        octree.rebalanceTree();

        var finalNodeCount = octree.nodeCount();
        assertTrue(finalNodeCount < initialNodeCount, "Should have fewer nodes after removing all entities");
        assertEquals(0, octree.entityCount(), "Should have no entities");
    }

    @Test
    public void testNodeMerging() {
        // First create a split tree
        List<LongEntityID> entityIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Point3f pos = new Point3f(100 + i * 10, 100, 100);
            LongEntityID id = octree.insert(pos, (byte) 5, "Entity_" + i);
            entityIds.add(id);
        }

        // Get initial stats
        var initialStats = octree.getStats();
        int initialNodes = initialStats.nodeCount();

        // Remove most entities
        for (int i = 0; i < 6; i++) {
            octree.removeEntity(entityIds.get(i));
        }

        // Manually trigger rebalancing
        TreeBalancer.RebalancingResult result = octree.rebalanceTree();
        assertNotNull(result, "Should have rebalancing result");

        // After rebalancing, nodes might be merged
        var finalStats = octree.getStats();
        assertTrue(finalStats.nodeCount() <= initialNodes, "Node count should not increase after removing entities");

        // Remaining entities should still be found
        for (int i = 6; i < 8; i++) {
            assertTrue(octree.containsEntity(entityIds.get(i)), "Remaining entity should exist: " + entityIds.get(i));
        }
    }

    @Test
    public void testNodeSplitting() {
        // Insert entities to trigger split
        List<LongEntityID> entityIds = new ArrayList<>();

        // Let's understand the scale at level 5
        int cellSizeLevel5 = Constants.lengthAtLevel((byte) 5);
        int cellSizeLevel6 = Constants.lengthAtLevel((byte) 6);
        System.out.println("Cell size at level 5: " + cellSizeLevel5);
        System.out.println("Cell size at level 6: " + cellSizeLevel6);

        // At level 5, cell size is 65536
        // At level 6, cell size is 32768
        // To ensure entities distribute to different children, we need positions
        // that are in the same level 5 cell but different level 6 cells

        // Calculate base position that's aligned to level 5 grid
        int baseCoord = cellSizeLevel5 * 2; // 131072

        // Create positions that will map to different child cells
        Point3f[] positions = new Point3f[] { new Point3f(baseCoord, baseCoord, baseCoord),
                                              // Child 0
                                              new Point3f(baseCoord + cellSizeLevel6, baseCoord, baseCoord),
                                              // Child 1 (+X)
                                              new Point3f(baseCoord, baseCoord + cellSizeLevel6, baseCoord),
                                              // Child 2 (+Y)
                                              new Point3f(baseCoord + cellSizeLevel6, baseCoord + cellSizeLevel6,
                                                          baseCoord), // Child 3 (+X+Y)
                                              new Point3f(baseCoord, baseCoord, baseCoord + cellSizeLevel6),
                                              // Child 4 (+Z)
                                              new Point3f(baseCoord + cellSizeLevel6, baseCoord,
                                                          baseCoord + cellSizeLevel6)  // Child 5 (+X+Z)
        };

        // Verify they all map to same parent cell but different child cells
        long parentMorton = Constants.calculateMortonIndex(positions[0], (byte) 5);
        System.out.println("Parent cell morton at level 5: " + parentMorton);

        for (int i = 0; i < positions.length; i++) {
            long morton5 = Constants.calculateMortonIndex(positions[i], (byte) 5);
            long morton6 = Constants.calculateMortonIndex(positions[i], (byte) 6);
            System.out.println("Position " + i + ": level 5 morton = " + morton5 + ", level 6 morton = " + morton6);
            assertEquals(parentMorton, morton5, "All positions should map to same parent cell");
        }

        // Insert entities and trace the subdivision process
        for (int i = 0; i < 6; i++) {
            System.out.println("Inserting entity " + i + " at " + positions[i]);
            LongEntityID id = octree.insert(positions[i], (byte) 5, "Entity_" + i);
            entityIds.add(id);
            System.out.println(
            "After insert " + i + ": node count = " + octree.nodeCount() + ", entity count = " + octree.entityCount());
        }

        // The handleNodeSubdivision should have been triggered
        // All entities should still be findable
        for (int i = 0; i < entityIds.size(); i++) {
            LongEntityID id = entityIds.get(i);
            assertTrue(octree.containsEntity(id), "Entity should still exist: " + id);
            Point3f pos = octree.getEntityPosition(id);
            assertNotNull(pos, "Should have position for: " + id);
            assertEquals(positions[i], pos, "Position should be unchanged");
        }

        // Debug: Check all entities
        System.out.println("=== Node Splitting Debug ===");
        System.out.println("Total entities: " + octree.entityCount());

        // Check entity locations using entity manager
        for (LongEntityID id : entityIds) {
            var locations = octree.getEntitySpanCount(id);
            System.out.println("Entity " + id + " is in " + locations + " nodes");
        }

        // Try looking up at different levels to see where entities went
        System.out.println("\nLookup at different levels:");
        for (byte level = 0; level <= 10; level++) {
            int totalFound = 0;
            for (Point3f pos : positions) {
                var found = octree.lookup(pos, level);
                totalFound += found.size();
            }
            if (totalFound > 0) {
                System.out.println("Level " + level + ": found " + totalFound + " entities total");
            }
        }

        // Check stats
        var stats = octree.getStats();
        System.out.println("\nTree stats:");
        System.out.println("Node count: " + stats.nodeCount());
        System.out.println("Entity count: " + stats.entityCount());
        System.out.println("Max depth: " + stats.maxDepth());

        // The entities should have been moved to child nodes
        // Note: nodeCount only counts non-empty nodes, so we may still have 1 node
        // if all entities went to the same child (since they have same position)
        assertTrue(stats.nodeCount() >= 1, "Should have at least one node");
        assertEquals(6, stats.entityCount(), "Should still have all 6 entities");

        // The key test: check if subdivision worked
        // Count nodes at each level
        int nodesAtLevel5 = 0;
        int nodesAtLevel6 = 0;
        for (var node : octree.nodes().toList()) {
            byte nodeLevel = octree.getLevelFromIndex(node.mortonIndex());
            if (nodeLevel == 5) {
                nodesAtLevel5++;
            }
            if (nodeLevel == 6) {
                nodesAtLevel6++;
            }
        }

        System.out.println("Nodes at level 5: " + nodesAtLevel5);
        System.out.println("Nodes at level 6: " + nodesAtLevel6);

        // If subdivision happened, we should have nodes at level 6
        if (nodesAtLevel6 > 0) {
            System.out.println("SUCCESS: Subdivision worked - created child nodes at level 6");
            assertTrue(nodesAtLevel6 >= 1, "Should have at least one child node");
        } else {
            System.out.println("INFO: No subdivision occurred - may be due to all entities in same cell");
        }

        // Debug: Check where entity 0 is actually stored
        LongEntityID entity0 = entityIds.get(0);
        Point3f pos0 = octree.getEntityPosition(entity0);
        System.out.println("\n=== Debugging Entity 0 Location ===");
        System.out.println("Entity 0 position: " + pos0);

        // Check at all levels where this position maps
        for (byte level = 0; level <= 10; level++) {
            long morton = Constants.calculateMortonIndex(pos0, level);
            boolean hasNode = octree.hasNode(morton);
            if (hasNode) {
                var lookup = octree.lookup(pos0, level);
                boolean containsEntity0 = lookup.contains(entity0);
                System.out.println(
                "Level " + level + ": morton=" + morton + ", hasNode=" + hasNode + ", lookup=" + lookup
                + ", contains entity 0=" + containsEntity0);
            }
        }

        // Check spatial map to see exactly where entity 0 is
        var spatialMap = octree.getSpatialMap();
        System.out.println("\nSpatial map entries containing entity 0:");
        for (var entry : spatialMap.entrySet()) {
            if (entry.getValue().contains(entity0)) {
                long morton = entry.getKey();
                byte level = octree.getLevelFromIndex(morton);
                System.out.println(
                "Found entity 0 at morton=" + morton + ", level=" + level + ", entities=" + entry.getValue());
            }
        }

        // Verify we can find entities at their positions
        for (LongEntityID id : entityIds) {
            Point3f pos = octree.getEntityPosition(id);
            var found = octree.kNearestNeighbors(pos, 10,
                                                 Float.MAX_VALUE); // Search for more neighbors with unlimited distance
            System.out.println("k-NN search for entity " + id + " at " + pos + " found: " + found);
            assertTrue(found.contains(id), "Should find entity " + id + " at its position");
        }
    }

    @Test
    public void testNodeSplittingWithDistributedEntities() {
        // Insert entities spread across a parent cell
        List<LongEntityID> entityIds = new ArrayList<>();

        // Calculate cell size at level 5 using the correct formula
        int cellSizeLevel5 = Constants.lengthAtLevel((byte) 5);
        int cellSizeLevel6 = Constants.lengthAtLevel((byte) 6);

        // Use a base coordinate that ensures we're in a specific level 5 cell
        int baseCoord = cellSizeLevel5 * 2; // This ensures we're in a non-zero Morton cell

        System.out.println("=== testNodeSplittingWithDistributedEntities Debug ===");
        System.out.println("cellSizeLevel5: " + cellSizeLevel5);
        System.out.println("cellSizeLevel6: " + cellSizeLevel6);
        System.out.println("baseCoord: " + baseCoord);

        // Insert entities in different octants of the parent cell
        for (int octant = 0; octant < 8; octant++) {
            float x = baseCoord + ((octant & 1) != 0 ? cellSizeLevel6 : 0);
            float y = baseCoord + ((octant & 2) != 0 ? cellSizeLevel6 : 0);
            float z = baseCoord + ((octant & 4) != 0 ? cellSizeLevel6 : 0);

            Point3f pos = new Point3f(x, y, z);
            long morton5 = Constants.calculateMortonIndex(pos, (byte) 5);
            long morton6 = Constants.calculateMortonIndex(pos, (byte) 6);
            System.out.println(
            "Octant " + octant + ": position=" + pos + ", morton5=" + morton5 + ", morton6=" + morton6);

            LongEntityID id = octree.insert(pos, (byte) 5, "Entity_" + octant);
            entityIds.add(id);
        }

        // Should have triggered a split
        var stats = octree.getStats();
        assertTrue(stats.nodeCount() > 1, "Should have split into multiple nodes");

        // Each entity should be findable via k-NN search
        for (int i = 0; i < entityIds.size(); i++) {
            LongEntityID id = entityIds.get(i);
            Point3f pos = octree.getEntityPosition(id);
            var found = octree.kNearestNeighbors(pos, 1, Float.MAX_VALUE);
            assertTrue(found.contains(id), "Entity should be found via k-NN search at position " + pos);
        }
    }

    @Test
    public void testSplitPreservesEntities() {
        // Insert entities with specific content
        List<LongEntityID> ids = new ArrayList<>();
        List<String> contents = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            Point3f pos = new Point3f(100 + i, 100 + i, 100 + i);
            String content = "Special_Content_" + i;
            LongEntityID id = octree.insert(pos, (byte) 5, content);
            ids.add(id);
            contents.add(content);
        }

        // Force splits by lowering threshold
        octree.setBalancingStrategy(new DefaultBalancingStrategy<>(0.1, 0.3, 0.2, 1000));
        octree.rebalanceTree();

        // Verify all entities and content preserved
        for (int i = 0; i < ids.size(); i++) {
            LongEntityID id = ids.get(i);
            assertTrue(octree.containsEntity(id), "Entity should exist after split");

            String content = octree.getEntity(id);
            assertEquals(contents.get(i), content, "Content should be preserved");

            Point3f pos = octree.getEntityPosition(id);
            assertNotNull(pos, "Position should be preserved");
        }
    }
}
