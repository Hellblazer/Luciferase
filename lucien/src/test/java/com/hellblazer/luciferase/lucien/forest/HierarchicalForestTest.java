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

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HierarchicalForest functionality
 */
public class HierarchicalForestTest {
    
    private HierarchicalForest<MortonKey, LongEntityID, String> hierarchicalForest;
    private SequentialLongIDGenerator idGenerator;
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        
        // Create hierarchical forest with 3 levels
        var hierarchyConfig = HierarchicalForest.HierarchyConfig.builder()
            .maxLevels(3)
            .levelDistances(100.0f, 500.0f, 2000.0f)
            .maxEntitiesPerLevel(100, 500, 2000)
            .autoLevelManagement(true)
            .levelTransitionHysteresis(0.1f)
            .levelStrategy(HierarchicalForest.HierarchyConfig.LevelSelectionStrategy.DISTANCE_BASED)
            .build();
            
        hierarchicalForest = new HierarchicalForest<>(
            ForestConfig.defaultConfig(),
            hierarchyConfig,
            idGenerator
        );
    }
    
    @Test
    void testHierarchicalForestCreation() {
        assertNotNull(hierarchicalForest);
        assertEquals(0, hierarchicalForest.getTreeCount());
        
        var stats = hierarchicalForest.getHierarchyStatistics();
        assertEquals(3, stats.totalLevels);
        assertEquals(3, stats.levelStats.size());
    }
    
    @Test
    void testLevelTreeCreation() {
        // Create trees at different levels
        var bounds = new EntityBounds(
            new Point3f(0, 0, 0),
            new Point3f(2000, 2000, 2000)
        );
        
        var level0Tree = hierarchicalForest.createLevelTree(0, bounds, "CoarseTree");
        var level1Tree = hierarchicalForest.createLevelTree(1, bounds, "MediumTree");
        var level2Tree = hierarchicalForest.createLevelTree(2, bounds, "FineTree");
        
        assertNotNull(level0Tree);
        assertNotNull(level1Tree);
        assertNotNull(level2Tree);
        
        assertEquals(3, hierarchicalForest.getTreeCount());
        
        // Check statistics
        var stats = hierarchicalForest.getHierarchyStatistics();
        assertEquals(1, stats.levelStats.get(0).treeCount);
        assertEquals(1, stats.levelStats.get(1).treeCount);
        assertEquals(1, stats.levelStats.get(2).treeCount);
    }
    
    @Test
    void testEntityInsertionAtLevels() {
        // Create trees
        var bounds = new EntityBounds(
            new Point3f(0, 0, 0),
            new Point3f(2000, 2000, 2000)
        );
        
        hierarchicalForest.createLevelTree(0, bounds, "Level0");
        hierarchicalForest.createLevelTree(1, bounds, "Level1");
        hierarchicalForest.createLevelTree(2, bounds, "Level2");
        
        // Insert entities at different levels
        var id0 = (LongEntityID) idGenerator.generateID();
        var id1 = (LongEntityID) idGenerator.generateID();
        var id2 = (LongEntityID) idGenerator.generateID();
        
        hierarchicalForest.insertAtLevel(id0, "Coarse entity", new Point3f(0, 0, 0), 0, null);
        hierarchicalForest.insertAtLevel(id1, "Medium entity", new Point3f(100, 0, 0), 1, null);
        hierarchicalForest.insertAtLevel(id2, "Fine entity", new Point3f(200, 0, 0), 2, null);
        
        // Check entity levels
        assertEquals(0, hierarchicalForest.getEntityLevel(id0));
        assertEquals(1, hierarchicalForest.getEntityLevel(id1));
        assertEquals(2, hierarchicalForest.getEntityLevel(id2));
        
        // Check statistics
        var stats = hierarchicalForest.getHierarchyStatistics();
        assertEquals(1, stats.levelStats.get(0).entityCount);
        assertEquals(1, stats.levelStats.get(1).entityCount);
        assertEquals(1, stats.levelStats.get(2).entityCount);
    }
    
    @Test
    void testViewerPositionUpdate() {
        // Set initial viewer position
        var viewerPos = new Point3f(0, 0, 0);
        hierarchicalForest.updateViewerPosition(viewerPos);
        
        assertEquals(viewerPos, hierarchicalForest.getViewerPosition());
        
        // Update viewer position
        var newPos = new Point3f(100, 100, 100);
        hierarchicalForest.updateViewerPosition(newPos);
        
        assertEquals(newPos, hierarchicalForest.getViewerPosition());
    }
    
    @Test
    void testHierarchicalQuery() {
        // Create multi-level structure
        var bounds = new EntityBounds(
            new Point3f(0, 0, 0),
            new Point3f(4000, 4000, 4000)
        );
        
        // Create all three levels explicitly to avoid automatic creation
        hierarchicalForest.createLevelTree(0, bounds, "Level0");
        hierarchicalForest.createLevelTree(1, bounds, "Level1");
        hierarchicalForest.createLevelTree(2, bounds, "Level2");
        
        // Add entities at different levels
        var entities = new HashSet<LongEntityID>();
        
        // Coarse level entities (level 0) - keep positions well within bounds
        for (int i = 0; i < 5; i++) {
            var id = (LongEntityID) idGenerator.generateID();
            var pos = new Point3f(i * 400 + 200, 200, 200);
            hierarchicalForest.insertAtLevel(id, "Coarse " + i, pos, 0, null);
            entities.add(id);
        }
        
        // Medium level entities (level 1)
        for (int i = 0; i < 10; i++) {
            var id = (LongEntityID) idGenerator.generateID();
            var pos = new Point3f(i * 200 + 100, 100, 100);
            hierarchicalForest.insertAtLevel(id, "Medium " + i, pos, 1, null);
            entities.add(id);
        }
        
        // More medium level entities
        for (int i = 10; i < 30; i++) {
            var id = (LongEntityID) idGenerator.generateID();
            var pos = new Point3f(i * 50 + 100, 150, 100);
            hierarchicalForest.insertAtLevel(id, "Medium " + i, pos, 1, null);
            entities.add(id);
        }
        
        // Fine level entities (level 2)
        for (int i = 0; i < 30; i++) {
            var id = (LongEntityID) idGenerator.generateID();
            var pos = new Point3f(i * 10 + 100, 200, 100);
            hierarchicalForest.insertAtLevel(id, "Fine " + i, pos, 2, null);
            entities.add(id);
        }
        
        // Test different query modes
        var queryPoint = new Point3f(300, 150, 100);
        var queryRadius = 500.0f;
        
        // Current LOD query
        hierarchicalForest.updateViewerPosition(new Point3f(0, 0, 50));
        var lodResults = hierarchicalForest.hierarchicalQuery(
            queryPoint, queryRadius, HierarchicalForest.QueryMode.CURRENT_LOD
        );
        assertFalse(lodResults.isEmpty());
        
        // All levels query
        var allResults = hierarchicalForest.hierarchicalQuery(
            queryPoint, queryRadius, HierarchicalForest.QueryMode.ALL_LEVELS
        );
        assertFalse(allResults.isEmpty());
        
        // Progressive query
        var progressiveResults = hierarchicalForest.hierarchicalQuery(
            queryPoint, queryRadius, HierarchicalForest.QueryMode.PROGRESSIVE
        );
        assertFalse(progressiveResults.isEmpty());
        
        // Adaptive query
        var adaptiveResults = hierarchicalForest.hierarchicalQuery(
            queryPoint, queryRadius, HierarchicalForest.QueryMode.ADAPTIVE
        );
        assertFalse(adaptiveResults.isEmpty());
    }
    
    @Test
    void testHierarchicalKNN() {
        // Create structure
        var bounds = new EntityBounds(
            new Point3f(0, 0, 0),
            new Point3f(2000, 2000, 2000)
        );
        
        for (int i = 0; i < 3; i++) {
            hierarchicalForest.createLevelTree(i, bounds, "Level" + i);
        }
        
        // Add entities
        for (int level = 0; level < 3; level++) {
            for (int i = 0; i < 10; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                var pos = new Point3f(
                    level * 100 + i * 10,
                    level * 100,
                    0
                );
                hierarchicalForest.insertAtLevel(id, "Entity L" + level + " #" + i, pos, level, null);
            }
        }
        
        // Test hierarchical k-NN
        var queryPoint = new Point3f(50, 50, 0);
        var nearest = hierarchicalForest.hierarchicalKNN(queryPoint, 5);
        
        assertNotNull(nearest);
        assertEquals(5, nearest.size());
    }
    
    @Test
    void testLevelTransitionWithHysteresis() {
        // Create forest with specific hysteresis
        var config = HierarchicalForest.HierarchyConfig.builder()
            .maxLevels(2)
            .levelDistances(100.0f, 1000.0f)
            .maxEntitiesPerLevel(100, 100)
            .levelTransitionHysteresis(0.2f) // 20% hysteresis
            .build();
            
        var forest = new HierarchicalForest<MortonKey, LongEntityID, String>(
            ForestConfig.defaultConfig(),
            config,
            idGenerator
        );
        
        // Create trees
        var bounds = new EntityBounds(
            new Point3f(0, 0, 0),
            new Point3f(2000, 2000, 2000)
        );
        forest.createLevelTree(0, bounds, "Level0");
        forest.createLevelTree(1, bounds, "Level1");
        
        // Add entity at level 0
        var id = (LongEntityID) idGenerator.generateID();
        forest.insertAtLevel(id, "Test entity", new Point3f(40, 0, 0), 0, null);
        
        // Initially at level 0
        assertEquals(0, forest.getEntityLevel(id));
        
        // Move viewer to trigger potential level change
        // Entity is at distance 40, which is within level 0 range (0-50)
        // Even with viewer movement, it should stay at level 0
        forest.updateViewerPosition(new Point3f(0, 0, 0));
        
        // Entity should remain at level 0
        assertEquals(0, forest.getEntityLevel(id));
    }
    
    @Test
    void testConfigurationBuilder() {
        // Test comprehensive configuration
        var config = HierarchicalForest.HierarchyConfig.builder()
            .maxLevels(4)
            .levelDistances(50.0f, 200.0f, 800.0f, 3200.0f)
            .maxEntitiesPerLevel(500, 2000, 8000, 32000)
            .autoLevelManagement(false)
            .levelTransitionHysteresis(0.15f)
            .levelStrategy(HierarchicalForest.HierarchyConfig.LevelSelectionStrategy.HYBRID)
            .build();
            
        assertEquals(4, config.getMaxLevels());
        assertArrayEquals(new float[]{50.0f, 200.0f, 800.0f, 3200.0f}, config.getLevelDistances());
        assertArrayEquals(new int[]{500, 2000, 8000, 32000}, config.getMaxEntitiesPerLevel());
        assertFalse(config.isAutoLevelManagementEnabled());
        assertEquals(0.15f, config.getLevelTransitionHysteresis());
        assertEquals(HierarchicalForest.HierarchyConfig.LevelSelectionStrategy.HYBRID, 
                    config.getLevelStrategy());
    }
    
    @Test
    void testInvalidLevelHandling() {
        var bounds = new EntityBounds(
            new Point3f(0, 0, 0),
            new Point3f(100, 100, 100)
        );
        
        // Test invalid level creation
        assertThrows(IllegalArgumentException.class, () -> {
            hierarchicalForest.createLevelTree(-1, bounds, "Invalid");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            hierarchicalForest.createLevelTree(5, bounds, "Invalid");
        });
        
        // Test entity insertion at invalid level (should clamp)
        hierarchicalForest.createLevelTree(0, bounds, "Level0");
        var id = (LongEntityID) idGenerator.generateID();
        
        // Should clamp to level 2 (max level)
        hierarchicalForest.insertAtLevel(id, "Test", new Point3f(0, 0, 0), 10, null);
        assertEquals(2, hierarchicalForest.getEntityLevel(id));
    }
    
    @Test
    void testStatisticsReporting() {
        // Create complex hierarchy
        var bounds = new EntityBounds(
            new Point3f(0, 0, 0),
            new Point3f(2000, 2000, 2000)
        );
        
        // Create multiple trees per level
        for (int level = 0; level < 3; level++) {
            for (int i = 0; i < level + 1; i++) {
                hierarchicalForest.createLevelTree(level, bounds, "L" + level + "T" + i);
            }
        }
        
        // Add entities
        int entityCount = 0;
        for (int level = 0; level < 3; level++) {
            for (int i = 0; i < (level + 1) * 10; i++) {
                var id = (LongEntityID) idGenerator.generateID();
                hierarchicalForest.insertAtLevel(
                    id, "Entity " + entityCount++,
                    new Point3f(i * 10, level * 100, 0),
                    level, null
                );
            }
        }
        
        // Get statistics
        var stats = hierarchicalForest.getHierarchyStatistics();
        
        assertEquals(3, stats.totalLevels);
        assertEquals(1, stats.levelStats.get(0).treeCount);
        assertEquals(2, stats.levelStats.get(1).treeCount);
        assertEquals(3, stats.levelStats.get(2).treeCount);
        
        assertEquals(10, stats.levelStats.get(0).entityCount);
        assertEquals(20, stats.levelStats.get(1).entityCount);
        assertEquals(30, stats.levelStats.get(2).entityCount);
        
        // Check level distance ranges
        assertEquals(0.0f, stats.levelStats.get(0).minDistance);
        assertEquals(100.0f, stats.levelStats.get(0).maxDistance);
        assertEquals(100.0f, stats.levelStats.get(1).minDistance);
        assertEquals(500.0f, stats.levelStats.get(1).maxDistance);
        assertEquals(500.0f, stats.levelStats.get(2).minDistance);
        assertEquals(2000.0f, stats.levelStats.get(2).maxDistance);
    }
}