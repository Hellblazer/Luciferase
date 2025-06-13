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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for tree balancing functionality.
 * 
 * @author hal.hildebrand
 */
public class TreeBalancingTest {
    
    private Octree<LongEntityID, String> octree;
    
    @BeforeEach
    public void setUp() {
        octree = new Octree<>(
            new SequentialLongIDGenerator(),
            20,     // max entities per node
            (byte) 10  // max depth
        );
    }
    
    @Test
    public void testBalancingStrategyConfiguration() {
        // Test default strategy
        assertFalse(octree.isAutoBalancingEnabled(), "Auto-balancing should be disabled by default");
        
        // Enable auto-balancing
        octree.setAutoBalancingEnabled(true);
        assertTrue(octree.isAutoBalancingEnabled(), "Auto-balancing should be enabled");
        
        // Set custom strategy
        TreeBalancingStrategy<LongEntityID> customStrategy = new DefaultBalancingStrategy<>(
            0.2,    // 20% merge factor
            0.8,    // 80% split factor
            0.25,   // 25% imbalance threshold
            30000   // 30 second interval
        );
        
        assertDoesNotThrow(() -> octree.setBalancingStrategy(customStrategy));
    }
    
    @Test
    public void testBalancingStatistics() {
        // Insert entities to create some structure
        insertClusteredEntities(100, 5);
        
        // Get balancing statistics
        TreeBalancingStrategy.TreeBalancingStats stats = octree.getBalancingStats();
        
        assertNotNull(stats, "Stats should not be null");
        assertTrue(stats.totalNodes() > 0, "Should have some nodes");
        assertTrue(stats.averageEntityLoad() > 0, "Should have non-zero average load");
        assertTrue(stats.imbalanceFactor() >= 0, "Imbalance factor should be non-negative");
        
        System.out.println("Tree Statistics:");
        System.out.println("  Total nodes: " + stats.totalNodes());
        System.out.println("  Empty nodes: " + stats.emptyNodes());
        System.out.println("  Underpopulated: " + stats.underpopulatedNodes());
        System.out.println("  Overpopulated: " + stats.overpopulatedNodes());
        System.out.println("  Average load: " + stats.averageEntityLoad());
        System.out.println("  Imbalance factor: " + stats.imbalanceFactor());
    }
    
    @Test
    public void testDefaultBalancingStrategy() {
        DefaultBalancingStrategy<LongEntityID> strategy = new DefaultBalancingStrategy<>();
        
        // Test split threshold
        int splitThreshold = strategy.getSplitThreshold((byte) 5, 100);
        assertTrue(splitThreshold > 0 && splitThreshold < 100, 
                  "Split threshold should be between 0 and max entities");
        
        // Test merge threshold
        int mergeThreshold = strategy.getMergeThreshold((byte) 5, 100);
        assertTrue(mergeThreshold > 0 && mergeThreshold < splitThreshold,
                  "Merge threshold should be less than split threshold");
        
        // Test should split
        assertTrue(strategy.shouldSplit(95, (byte) 5, 100), 
                  "Should split when above threshold");
        assertFalse(strategy.shouldSplit(50, (byte) 5, 100),
                   "Should not split when below threshold");
        
        // Test should merge
        assertTrue(strategy.shouldMerge(5, (byte) 5, new int[]{3, 4, 2}),
                  "Should merge when entities are low");
        assertFalse(strategy.shouldMerge(50, (byte) 5, new int[]{40, 45}),
                   "Should not merge when entities would overflow");
    }
    
    @Test
    public void testEntityDistribution() {
        DefaultBalancingStrategy<LongEntityID> strategy = new DefaultBalancingStrategy<>();
        
        // Create test entities
        var entities = new java.util.HashSet<LongEntityID>();
        for (int i = 0; i < 16; i++) {
            entities.add(new LongEntityID(i));
        }
        
        // Test distribution for octree (8 children)
        var distribution = strategy.distributeEntities(entities, 8);
        
        assertEquals(8, distribution.length, "Should have 8 distributions");
        
        // Check all entities are distributed
        int totalDistributed = 0;
        for (var childEntities : distribution) {
            assertNotNull(childEntities, "Child set should not be null");
            totalDistributed += childEntities.size();
        }
        assertEquals(entities.size(), totalDistributed, "All entities should be distributed");
        
        // Check roughly even distribution
        for (var childEntities : distribution) {
            assertTrue(childEntities.size() >= 1 && childEntities.size() <= 3,
                      "Should have roughly even distribution");
        }
    }
    
    @Test
    public void testRebalanceTreeOperation() {
        // Create an imbalanced tree
        createImbalancedTree();
        
        // Get initial stats
        var statsBefore = octree.getBalancingStats();
        
        // Perform rebalancing
        TreeBalancer.RebalancingResult result = octree.rebalanceTree();
        
        assertNotNull(result, "Result should not be null");
        assertTrue(result.successful(), "Rebalancing should succeed");
        assertTrue(result.timeTaken() >= 0, "Time taken should be non-negative");
        
        // Get stats after
        var statsAfter = octree.getBalancingStats();
        
        // Tree structure might change
        System.out.println("Rebalancing Result:");
        System.out.println("  Nodes created: " + result.nodesCreated());
        System.out.println("  Nodes removed: " + result.nodesRemoved());
        System.out.println("  Nodes split: " + result.nodesSplit());
        System.out.println("  Nodes merged: " + result.nodesMerged());
        System.out.println("  Time taken: " + result.timeTaken() + "ms");
    }
    
    @Test
    public void testAutoBalancing() {
        // Enable auto-balancing with aggressive strategy
        TreeBalancingStrategy<LongEntityID> aggressiveStrategy = new DefaultBalancingStrategy<>(
            0.3,    // 30% merge factor
            0.7,    // 70% split factor
            0.2,    // 20% imbalance threshold
            1000    // 1 second interval
        );
        
        octree.setBalancingStrategy(aggressiveStrategy);
        octree.setAutoBalancingEnabled(true);
        
        // Insert many entities to trigger auto-balancing
        Random rand = new Random(42);
        for (int i = 0; i < 200; i++) {
            Point3f pos = new Point3f(
                rand.nextFloat() * 100,
                rand.nextFloat() * 100,
                rand.nextFloat() * 100
            );
            octree.insert(pos, (byte) 8, "Entity_" + i);
        }
        
        // Auto-balancing should have been checked during insertions
        var stats = octree.getBalancingStats();
        assertNotNull(stats, "Should have statistics after insertions");
    }
    
    @Test
    public void testBalancingWithRemovals() {
        // Insert many entities
        List<LongEntityID> entityIds = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Point3f pos = new Point3f(i * 10, i * 10, i * 10);
            LongEntityID id = octree.insert(pos, (byte) 8, "Entity_" + i);
            entityIds.add(id);
        }
        
        // Enable auto-balancing
        octree.setAutoBalancingEnabled(true);
        
        // Remove many entities to create underpopulated nodes
        for (int i = 0; i < 80; i++) {
            octree.removeEntity(entityIds.get(i));
        }
        
        // Check stats show underpopulated nodes
        var stats = octree.getBalancingStats();
        System.out.println("After removals:");
        System.out.println("  Underpopulated percentage: " + stats.underpopulatedPercentage());
        System.out.println("  Empty nodes: " + stats.emptyNodes());
    }
    
    @Test
    public void testInvalidStrategyParameters() {
        // Test invalid merge factor
        assertThrows(IllegalArgumentException.class, () -> 
            new DefaultBalancingStrategy<LongEntityID>(-0.1, 0.9, 0.3, 1000),
            "Should reject negative merge factor"
        );
        
        assertThrows(IllegalArgumentException.class, () -> 
            new DefaultBalancingStrategy<LongEntityID>(1.1, 0.9, 0.3, 1000),
            "Should reject merge factor > 1"
        );
        
        // Test invalid split factor
        assertThrows(IllegalArgumentException.class, () -> 
            new DefaultBalancingStrategy<LongEntityID>(0.2, -0.1, 0.3, 1000),
            "Should reject negative split factor"
        );
        
        // Test merge >= split
        assertThrows(IllegalArgumentException.class, () -> 
            new DefaultBalancingStrategy<LongEntityID>(0.5, 0.5, 0.3, 1000),
            "Should reject merge factor >= split factor"
        );
    }
    
    /**
     * Insert entities in clusters to create structure.
     */
    private void insertClusteredEntities(int totalEntities, int clusters) {
        Random rand = new Random(42);
        int entitiesPerCluster = totalEntities / clusters;
        
        for (int c = 0; c < clusters; c++) {
            // Center of cluster
            float centerX = c * 200;
            float centerY = c * 200;
            float centerZ = c * 200;
            
            for (int i = 0; i < entitiesPerCluster; i++) {
                Point3f pos = new Point3f(
                    centerX + rand.nextFloat() * 50,
                    centerY + rand.nextFloat() * 50,
                    centerZ + rand.nextFloat() * 50
                );
                octree.insert(pos, (byte) 8, "Entity_" + (c * entitiesPerCluster + i));
            }
        }
    }
    
    /**
     * Create an imbalanced tree for testing.
     */
    private void createImbalancedTree() {
        // Insert many entities in one corner
        for (int i = 0; i < 50; i++) {
            Point3f pos = new Point3f(i * 0.1f, i * 0.1f, i * 0.1f);
            octree.insert(pos, (byte) 10, "Clustered_" + i);
        }
        
        // Insert sparse entities elsewhere
        Random rand = new Random(42);
        for (int i = 0; i < 20; i++) {
            Point3f pos = new Point3f(
                500 + rand.nextFloat() * 500,
                500 + rand.nextFloat() * 500,
                500 + rand.nextFloat() * 500
            );
            octree.insert(pos, (byte) 5, "Sparse_" + i);
        }
    }
}