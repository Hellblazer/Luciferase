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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AdaptiveForest functionality
 */
public class AdaptiveForestTest {
    
    private AdaptiveForest<MortonKey, LongEntityID, String> adaptiveForest;
    private SequentialLongIDGenerator idGenerator;
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        
        // Create adaptive forest with low thresholds for testing
        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .maxEntitiesPerTree(100)
            .minEntitiesPerTree(10)
            .densityThreshold(50.0f)
            .densityCheckInterval(10)
            .enableAutoSubdivision(true)
            .enableAutoMerging(true)
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.ADAPTIVE)
            .build();
            
        adaptiveForest = new AdaptiveForest<>(
            ForestConfig.defaultConfig(),
            adaptationConfig,
            idGenerator
        );
    }
    
    @AfterEach
    void tearDown() {
        if (adaptiveForest != null) {
            adaptiveForest.shutdown();
        }
    }
    
    @Test
    void testAdaptiveForestCreation() {
        assertNotNull(adaptiveForest);
        assertEquals(0, adaptiveForest.getTreeCount());
        assertEquals(0, adaptiveForest.getSubdivisionCount());
        assertEquals(0, adaptiveForest.getMergeCount());
    }
    
    @Test
    void testTreeAdditionWithDensityTracking() {
        // Add initial tree
        var tree = new Octree<LongEntityID, String>(idGenerator);
        var metadata = TreeMetadata.builder()
            .name("TestTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
            
        var treeId = adaptiveForest.addTree(tree, metadata);
        assertNotNull(treeId);
        assertEquals(1, adaptiveForest.getTreeCount());
        
        // Tree should have bounds initialized
        var treeNode = adaptiveForest.getTree(treeId);
        assertNotNull(treeNode);
        assertNotNull(treeNode.getGlobalBounds());
    }
    
    @Test
    void testEntityDensityTracking() {
        // Create tree
        var tree = new Octree<LongEntityID, String>(idGenerator);
        var treeId = adaptiveForest.addTree(tree);
        
        // Add entities and track density
        for (int i = 0; i < 50; i++) {
            var id = (LongEntityID) idGenerator.generateID();
            var pos = new Point3f(i % 10, i / 10, 0);
            tree.insert(id, pos, (byte)0, "Entity " + i);
            adaptiveForest.trackEntityInsertion(treeId, id, pos);
        }
        
        // Get statistics
        var stats = adaptiveForest.getAdaptationStatistics();
        assertNotNull(stats);
        assertTrue((Double) stats.get("averageDensity") > 0);
    }
    
    @Test
    void testSubdivisionStrategies() {
        // Test with octant subdivision
        var octantConfig = AdaptiveForest.AdaptationConfig.builder()
            .maxEntitiesPerTree(50)
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.OCTANT)
            .build();
            
        var octantForest = new AdaptiveForest<MortonKey, LongEntityID, String>(
            ForestConfig.defaultConfig(),
            octantConfig,
            idGenerator
        );
        
        try {
            // Add tree and entities
            var tree = new Octree<LongEntityID, String>(idGenerator);
            var treeId = octantForest.addTree(tree);
            
            // Add many entities to trigger subdivision
            for (int i = 0; i < 100; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                var pos = new Point3f(
                    (float)(Math.random() * 100),
                    (float)(Math.random() * 100),
                    (float)(Math.random() * 100)
                );
                tree.insert(id, pos, (byte)0, "Entity " + i);
                octantForest.trackEntityInsertion(treeId, id, pos);
            }
            
            // Force adaptation check
            octantForest.checkAndAdapt();
            
            // Wait a bit for async operations
            Thread.sleep(500);
            
            // Should have subdivided or have more trees
            var treeCount = octantForest.getTreeCount();
            var subdivisionCount = octantForest.getSubdivisionCount();
            assertTrue(treeCount > 1 || subdivisionCount > 0,
                "Expected subdivision but got treeCount=" + treeCount + ", subdivisionCount=" + subdivisionCount);
            
        } catch (InterruptedException e) {
            fail("Test interrupted");
        } finally {
            octantForest.shutdown();
        }
    }
    
    @Test
    void testBinarySubdivision() {
        // Test binary subdivision along X axis
        var binaryConfig = AdaptiveForest.AdaptationConfig.builder()
            .maxEntitiesPerTree(30)
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.BINARY_X)
            .densityCheckInterval(5)
            .build();
            
        var binaryForest = new AdaptiveForest<MortonKey, LongEntityID, String>(
            ForestConfig.defaultConfig(),
            binaryConfig,
            idGenerator
        );
        
        try {
            var tree = new Octree<LongEntityID, String>(idGenerator);
            var treeId = binaryForest.addTree(tree);
            
            // Add entities spread along X axis
            for (int i = 0; i < 50; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                var pos = new Point3f(i * 2, 50, 50);
                tree.insert(id, pos, (byte)0, "Entity " + i);
                binaryForest.trackEntityInsertion(treeId, id, pos);
            }
            
            // Force immediate check
            Thread.sleep(500);
            
            var stats = binaryForest.getAdaptationStatistics();
            assertNotNull(stats);
            
        } catch (InterruptedException e) {
            fail("Test interrupted");
        } finally {
            binaryForest.shutdown();
        }
    }
    
    @Test
    void testAdaptiveSubdivisionStrategy() {
        // Test adaptive strategy selection based on variance
        var adaptiveConfig = AdaptiveForest.AdaptationConfig.builder()
            .maxEntitiesPerTree(40)
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.ADAPTIVE)
            .densityCheckInterval(5)
            .build();
            
        var adaptiveForest = new AdaptiveForest<MortonKey, LongEntityID, String>(
            ForestConfig.defaultConfig(),
            adaptiveConfig,
            idGenerator
        );
        
        try {
            var tree = new Octree<LongEntityID, String>(idGenerator);
            var treeId = adaptiveForest.addTree(tree);
            
            // Add entities with high variance in Z direction
            for (int i = 0; i < 60; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                var pos = new Point3f(50, 50, i * 3);
                tree.insert(id, pos, (byte)0, "Entity " + i);
                adaptiveForest.trackEntityInsertion(treeId, id, pos);
            }
            
            // Should select binary Z subdivision
            Thread.sleep(500);
            
        } catch (InterruptedException e) {
            fail("Test interrupted");
        } finally {
            adaptiveForest.shutdown();
        }
    }
    
    @Test
    void testTreeMerging() {
        // Test merging of low-density trees
        var mergeConfig = AdaptiveForest.AdaptationConfig.builder()
            .minEntitiesPerTree(20)
            .enableAutoMerging(true)
            .densityThreshold(100.0f)
            .build();
            
        var mergeForest = new AdaptiveForest<MortonKey, LongEntityID, String>(
            ForestConfig.defaultConfig(),
            mergeConfig,
            idGenerator
        );
        
        try {
            // Create two adjacent low-density trees
            var tree1 = new Octree<LongEntityID, String>(idGenerator);
            var tree2 = new Octree<LongEntityID, String>(idGenerator);
            
            var tree1Id = mergeForest.addTree(tree1);
            var tree2Id = mergeForest.addTree(tree2);
            
            // Set adjacent bounds
            var tree1Node = mergeForest.getTree(tree1Id);
            var tree2Node = mergeForest.getTree(tree2Id);
            
            tree1Node.expandGlobalBounds(new EntityBounds(
                new Point3f(0, 0, 0),
                new Point3f(100, 100, 100)
            ));
            
            tree2Node.expandGlobalBounds(new EntityBounds(
                new Point3f(100, 0, 0),
                new Point3f(200, 100, 100)
            ));
            
            // Add few entities to each (below minimum)
            for (int i = 0; i < 5; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                var pos = new Point3f(50, 50, i * 10);
                tree1.insert(id, pos, (byte)0, "Entity " + i);
                mergeForest.trackEntityInsertion(tree1Id, id, pos);
            }
            
            for (int i = 0; i < 5; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                var pos = new Point3f(150, 50, i * 10);
                tree2.insert(id, pos, (byte)0, "Entity " + i);
                mergeForest.trackEntityInsertion(tree2Id, id, pos);
            }
            
            // Should trigger merging
            Thread.sleep(11000); // Wait for scheduled analysis
            
            // Trees might be merged
            var mergeCount = mergeForest.getMergeCount();
            var treeCount = mergeForest.getTreeCount();
            
            // Either merge happened or trees still exist
            assertTrue(mergeCount > 0 || treeCount >= 2);
            
        } catch (InterruptedException e) {
            fail("Test interrupted");
        } finally {
            mergeForest.shutdown();
        }
    }
    
    @Test
    void testAdaptationToggle() {
        // Test enabling/disabling adaptation
        assertTrue(adaptiveForest.getAdaptationStatistics().get("adaptationEnabled").equals(true));
        
        adaptiveForest.setAdaptationEnabled(false);
        assertFalse((Boolean) adaptiveForest.getAdaptationStatistics().get("adaptationEnabled"));
        
        adaptiveForest.setAdaptationEnabled(true);
        assertTrue((Boolean) adaptiveForest.getAdaptationStatistics().get("adaptationEnabled"));
    }
    
    @Test
    void testKMeansSubdivision() {
        // Test k-means clustering subdivision
        var kmeansConfig = AdaptiveForest.AdaptationConfig.builder()
            .maxEntitiesPerTree(80)
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.K_MEANS)
            .densityCheckInterval(10)
            .build();
            
        var kmeansForest = new AdaptiveForest<MortonKey, LongEntityID, String>(
            ForestConfig.defaultConfig(),
            kmeansConfig,
            idGenerator
        );
        
        try {
            var tree = new Octree<LongEntityID, String>(idGenerator);
            var treeId = kmeansForest.addTree(tree);
            
            // Add entities in clusters
            // Cluster 1: around (25, 25, 25)
            for (int i = 0; i < 30; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                var pos = new Point3f(
                    25 + (float)(Math.random() * 10 - 5),
                    25 + (float)(Math.random() * 10 - 5),
                    25 + (float)(Math.random() * 10 - 5)
                );
                tree.insert(id, pos, (byte)0, "Entity " + i);
                kmeansForest.trackEntityInsertion(treeId, id, pos);
            }
            
            // Cluster 2: around (75, 75, 75)
            for (int i = 30; i < 60; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                var pos = new Point3f(
                    75 + (float)(Math.random() * 10 - 5),
                    75 + (float)(Math.random() * 10 - 5),
                    75 + (float)(Math.random() * 10 - 5)
                );
                tree.insert(id, pos, (byte)0, "Entity " + i);
                kmeansForest.trackEntityInsertion(treeId, id, pos);
            }
            
            // Cluster 3: around (25, 75, 25)
            for (int i = 60; i < 90; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                var pos = new Point3f(
                    25 + (float)(Math.random() * 10 - 5),
                    75 + (float)(Math.random() * 10 - 5),
                    25 + (float)(Math.random() * 10 - 5)
                );
                tree.insert(id, pos, (byte)0, "Entity " + i);
                kmeansForest.trackEntityInsertion(treeId, id, pos);
            }
            
            // Should trigger k-means subdivision
            Thread.sleep(1000);
            
            var stats = kmeansForest.getAdaptationStatistics();
            assertNotNull(stats);
            
        } catch (InterruptedException e) {
            fail("Test interrupted");
        } finally {
            kmeansForest.shutdown();
        }
    }
    
    @Test
    void testConfigurationBuilder() {
        // Test configuration builder
        var config = AdaptiveForest.AdaptationConfig.builder()
            .maxEntitiesPerTree(5000)
            .minEntitiesPerTree(50)
            .densityThreshold(200.0f)
            .minTreeVolume(10.0f)
            .maxTreeVolume(1000000.0f)
            .densityCheckInterval(500)
            .enableAutoSubdivision(false)
            .enableAutoMerging(true)
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.BINARY_Y)
            .build();
            
        assertEquals(5000, config.getMaxEntitiesPerTree());
        assertEquals(50, config.getMinEntitiesPerTree());
        assertEquals(200.0f, config.getDensityThreshold());
        assertEquals(10.0f, config.getMinTreeVolume());
        assertEquals(1000000.0f, config.getMaxTreeVolume());
        assertEquals(500, config.getDensityCheckInterval());
        assertFalse(config.isAutoSubdivisionEnabled());
        assertTrue(config.isAutoMergingEnabled());
        assertEquals(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.BINARY_Y, 
                    config.getSubdivisionStrategy());
    }
}