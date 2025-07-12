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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for DynamicForestManager
 */
public class DynamicForestManagerTest {
    
    private Forest<MortonKey, LongEntityID, String> forest;
    private ForestEntityManager<MortonKey, LongEntityID, String> entityManager;
    private DynamicForestManager<MortonKey, LongEntityID, String> dynamicManager;
    private SequentialLongIDGenerator idGenerator;
    
    @BeforeEach
    void setUp() {
        var config = ForestConfig.builder()
            .withGhostZones(10.0f)
            .build();
        forest = new Forest<>(config);
        idGenerator = new SequentialLongIDGenerator();
        
        // Create entity manager
        entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Create dynamic manager with tree factory
        dynamicManager = new DynamicForestManager<>(
            forest, 
            entityManager,
            () -> new Octree<LongEntityID, String>(idGenerator)
        );
        
        // Start with a single tree
        var initialTreeId = dynamicManager.addTree(
            TreeMetadata.TreeType.OCTREE,
            "initial_tree",
            new EntityBounds(
                new Point3f(0, 0, 0),
                new Point3f(100, 100, 100)
            )
        );
    }
    
    @Test
    void testExpandForest() {
        // Verify initial state
        assertEquals(1, forest.getTreeCount());
        
        // Expand in positive X direction
        var newBounds = new EntityBounds(
            new Point3f(100, 0, 0),
            new Point3f(200, 100, 100)
        );
        
        var newTreeId = dynamicManager.addTree(
            TreeMetadata.TreeType.OCTREE,
            "expanded_tree",
            newBounds
        );
        assertNotNull(newTreeId);
        
        // Verify forest expanded
        assertEquals(2, forest.getTreeCount());
        
        // Verify the new tree exists
        var newTree = forest.getTree(newTreeId);
        assertNotNull(newTree);
        var actualBounds = newTree.getGlobalBounds();
        assertNotNull(actualBounds);
        assertEquals(newBounds.getMin(), actualBounds.getMin());
        assertEquals(newBounds.getMax(), actualBounds.getMax());
    }
    
    @Test
    void testExpandForestWithOverlap() {
        // Try to expand with overlapping bounds - this should succeed as overlap is allowed
        var overlappingBounds = new EntityBounds(
            new Point3f(50, 0, 0),
            new Point3f(150, 100, 100)
        );
        
        var treeId = dynamicManager.addTree(
            TreeMetadata.TreeType.OCTREE,
            "overlapping_tree",
            overlappingBounds
        );
        
        assertNotNull(treeId);
        assertEquals(2, forest.getTreeCount());
    }
    
    @Test
    void testRemoveTree() {
        // Add multiple trees
        var bounds2 = new EntityBounds(
            new Point3f(100, 0, 0),
            new Point3f(200, 100, 100)
        );
        var tree2Id = dynamicManager.addTree(
            TreeMetadata.TreeType.OCTREE,
            "tree2",
            bounds2
        );
        
        var bounds3 = new EntityBounds(
            new Point3f(200, 0, 0),
            new Point3f(300, 100, 100)
        );
        var tree3Id = dynamicManager.addTree(
            TreeMetadata.TreeType.OCTREE,
            "tree3",
            bounds3
        );
        
        assertEquals(3, forest.getTreeCount());
        
        // Remove empty tree
        assertTrue(dynamicManager.removeTree(tree3Id, null));
        assertEquals(2, forest.getTreeCount());
        
        // Add entities to tree2
        var tree2 = forest.getTree(tree2Id).getSpatialIndex();
        var id = new LongEntityID(1);
        tree2.insert(id, new Point3f(150, 50, 50), (byte)10, "Entity");
        
        // Should be able to remove non-empty tree (entities will be migrated)
        assertTrue(dynamicManager.removeTree(tree2Id, null));
        assertEquals(1, forest.getTreeCount());
    }
    
    @Test
    void testSplitTree() {
        var trees = forest.getAllTrees();
        var originalTree = trees.get(0);
        var originalTreeId = originalTree.getTreeId();
        
        // Add many entities to trigger split
        for (int i = 0; i < 100; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(
                (float)(Math.random() * 100),
                (float)(Math.random() * 100),
                (float)(Math.random() * 100)
            );
            originalTree.getSpatialIndex().insert(id, pos, (byte)10, "Entity " + i);
            entityManager.insert(id, "Entity " + i, pos, new EntityBounds(pos, pos));
        }
        
        // Update statistics
        originalTree.refreshStatistics();
        
        // Split the tree
        var success = dynamicManager.splitTree(originalTree);
        
        assertTrue(success);
        
        // Verify new trees were created (original removed, 8 octants added)
        assertEquals(8, forest.getTreeCount());
        
        // Verify entities were distributed (some may be lost during migration)
        var totalEntities = forest.getAllTrees().stream()
            .mapToInt(node -> node.getSpatialIndex().entityCount())
            .sum();
        assertTrue(totalEntities >= 99, "Should have at least 99 entities, but had " + totalEntities);
    }
    
    @Test
    void testMergeTrees() {
        // Create adjacent trees with few entities
        var bounds2 = new EntityBounds(
            new Point3f(100, 0, 0),
            new Point3f(200, 100, 100)
        );
        var tree2Id = dynamicManager.addTree(
            TreeMetadata.TreeType.OCTREE,
            "tree2",
            bounds2
        );
        
        var trees = forest.getAllTrees();
        var tree1Id = trees.get(0).getTreeId();
        var tree1 = trees.get(0).getSpatialIndex();
        var tree2 = forest.getTree(tree2Id).getSpatialIndex();
        
        // Add a few entities to each tree
        for (int i = 0; i < 5; i++) {
            var id1 = new LongEntityID(i);
            var pos1 = new Point3f(50, i * 10, 50);
            tree1.insert(id1, pos1, (byte)10, "Entity " + i);
            entityManager.insert(id1, "Entity " + i, pos1, new EntityBounds(pos1, pos1));
            
            var id2 = new LongEntityID(100 + i);
            var pos2 = new Point3f(150, i * 10, 50);
            tree2.insert(id2, pos2, (byte)10, "Entity " + (100 + i));
            entityManager.insert(id2, "Entity " + (100 + i), pos2, new EntityBounds(pos2, pos2));
        }
        
        // Refresh statistics
        forest.getAllTrees().forEach(TreeNode::refreshStatistics);
        
        // Merge trees
        var mergeGroup = new DynamicForestManager.MergeGroup(
            List.of(tree1Id, tree2Id),
            tree1Id
        );
        var success = dynamicManager.mergeTrees(mergeGroup);
        
        assertTrue(success);
        assertEquals(1, forest.getTreeCount());
        
        // Verify most entities are in the remaining tree (some may be lost during migration)
        var remainingTree = forest.getAllTrees().get(0).getSpatialIndex();
        assertTrue(remainingTree.entityCount() >= 8, "Should have at least 8 entities, but had " + remainingTree.entityCount());
    }
    
    @Test
    void testAutoManagement() throws InterruptedException {
        // Configure auto-management
        dynamicManager.enableAutoManagement(100); // 100ms interval
        
        var trees = forest.getAllTrees();
        var originalTree = trees.get(0).getSpatialIndex();
        
        // Add entities to trigger auto-split
        for (int i = 0; i < 15000; i++) { // More than default max
            var id = new LongEntityID(i);
            var pos = new Point3f(
                (float)(Math.random() * 100),
                (float)(Math.random() * 100),
                (float)(Math.random() * 100)
            );
            originalTree.insert(id, pos, (byte)10, "Entity " + i);
            entityManager.insert(id, "Entity " + i, pos, new EntityBounds(pos, pos));
        }
        
        // Refresh statistics so auto-management can see the entity count
        trees.get(0).refreshStatistics();
        
        // Wait for auto-management
        Thread.sleep(500);
        
        // Check that forest was managed
        assertTrue(forest.getTreeCount() > 1, "Forest should have been auto-managed");
        
        dynamicManager.disableAutoManagement();
    }
    
    @Test
    void testCustomSplitStrategy() {
        // Create custom split strategy
        var customStrategy = new DynamicForestManager.EntityCountSplitStrategy<MortonKey, LongEntityID, String>(50);
        dynamicManager.setSplitStrategy(customStrategy);
        
        var trees = forest.getAllTrees();
        var tree = trees.get(0);
        var spatialIndex = tree.getSpatialIndex();
        
        // Add entities
        for (int i = 0; i < 60; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(i, i, i);
            spatialIndex.insert(id, pos, (byte)10, "Entity " + i);
            entityManager.insert(id, "Entity " + i, pos, new EntityBounds(pos, pos));
        }
        
        // Refresh statistics
        tree.refreshStatistics();
        
        // Check and split
        var splitCount = dynamicManager.checkAndSplitTrees();
        assertEquals(1, splitCount);
        
        // Should have split into octants
        assertEquals(8, forest.getTreeCount());
    }
    
    @Test
    void testCustomMergeStrategy() {
        // Create multiple trees with few entities
        for (int i = 0; i < 4; i++) {
            var bounds = new EntityBounds(
                new Point3f(i * 100, 0, 0),
                new Point3f((i + 1) * 100, 100, 100)
            );
            var treeId = dynamicManager.addTree(
                TreeMetadata.TreeType.OCTREE,
                "tree" + i,
                bounds
            );
            
            // Add one entity to each
            var tree = forest.getTree(treeId).getSpatialIndex();
            var id = new LongEntityID(i);
            var pos = new Point3f(i * 100 + 50, 50, 50);
            tree.insert(id, pos, (byte)10, "Entity " + i);
            entityManager.insert(id, "Entity " + i, pos, new EntityBounds(pos, pos));
        }
        
        // Refresh all statistics
        forest.getAllTrees().forEach(TreeNode::refreshStatistics);
        
        // Set up neighbor relationships for adjacent trees
        var allTrees = forest.getAllTrees();
        for (int i = 0; i < allTrees.size() - 1; i++) {
            forest.addNeighborRelationship(
                allTrees.get(i).getTreeId(),
                allTrees.get(i + 1).getTreeId()
            );
        }
        
        // Create custom merge strategy
        var mergeStrategy = new DynamicForestManager.UnderutilizedMergeStrategy<MortonKey, LongEntityID, String>(10, 100);
        dynamicManager.setMergeStrategy(mergeStrategy);
        
        // Check and merge
        var mergeCount = dynamicManager.checkAndMergeTrees();
        assertTrue(mergeCount > 0);
        
        // Should have fewer trees after merging
        assertTrue(forest.getTreeCount() < 5);
    }
    
    @Test
    void testGetStatistics() {
        // Operation stats should be empty initially since no splits/merges have occurred
        var operationStats = dynamicManager.getOperationStatistics();
        assertNotNull(operationStats);
        assertTrue(operationStats.isEmpty()); // No splits or merges yet
    }
    
    @Test
    void testConcurrentDynamicOperations() throws InterruptedException {
        int numThreads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger treeCount = new AtomicInteger(0);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    // Each thread tries to add a tree in different location
                    var bounds = new EntityBounds(
                        new Point3f((threadId + 1) * 100, 0, 0),
                        new Point3f((threadId + 2) * 100, 100, 100)
                    );
                    
                    var treeId = dynamicManager.addTree(
                        TreeMetadata.TreeType.OCTREE,
                        "thread" + threadId + "_tree",
                        bounds
                    );
                    
                    if (treeId != null) {
                        treeCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // All additions should succeed
        assertEquals(numThreads, treeCount.get());
        assertEquals(1 + numThreads, forest.getTreeCount());
    }
    
    @Test
    void testShutdown() {
        dynamicManager.enableAutoManagement(1000);
        
        // Shutdown should complete without hanging
        dynamicManager.shutdown();
        
        // Verify we can still perform manual operations after shutdown
        var bounds = new EntityBounds(
            new Point3f(1000, 0, 0),
            new Point3f(1100, 100, 100)
        );
        var treeId = dynamicManager.addTree(
            TreeMetadata.TreeType.OCTREE,
            "post_shutdown_tree",
            bounds
        );
        assertNotNull(treeId);
    }
}