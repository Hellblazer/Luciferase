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

import com.hellblazer.luciferase.lucien.entity.*;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ConfigurableTetree
 *
 * @author hal.hildebrand
 */
class ConfigurableTetreeTest {

    static class TestEntity implements Entity<LongEntityID, String> {
        private final LongEntityID id;
        private final String content;
        private final EntityBounds bounds;

        TestEntity(long id, String content, float x, float y, float z, float radius) {
            this.id = new LongEntityID(id);
            this.content = content;
            this.bounds = new EntityBounds(x, y, z, radius);
        }

        @Override
        public LongEntityID getId() {
            return id;
        }

        @Override
        public String getContent() {
            return content;
        }

        @Override
        public EntityBounds getBounds() {
            return bounds;
        }
    }

    @Test
    void testConfigurableTetreeWithDefaultConfig() {
        TetreeConfiguration config = new TetreeConfiguration();
        ConfigurableTetree<LongEntityID, String> tetree = new ConfigurableTetree<>(
            new LongEntityIDGenerator(), config
        );
        
        assertNotNull(tetree.getConfiguration());
        assertEquals(TetreeConfiguration.DEFAULT_ARRAY_THRESHOLD, config.getArrayThreshold());
        
        // Add entities
        for (int i = 0; i < 10; i++) {
            TestEntity entity = new TestEntity(i, "Entity " + i, 
                                               100 + i * 10, 100 + i * 10, 100 + i * 10, 5);
            tetree.insert(entity);
        }
        
        assertEquals(10, tetree.size());
    }

    @Test
    void testNodeStorageTransition() {
        // Configure low threshold to test transition
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .arrayThreshold(3)
            .arrayInitialCapacity(5)
            .build();
            
        ConfigurableTetree<LongEntityID, String> tetree = new ConfigurableTetree<>(
            new LongEntityIDGenerator(), 10, (byte) 10, config
        );
        
        // Insert entities at same location to test node storage
        Point3f location = new Point3f(100, 100, 100);
        
        // Add entities below threshold
        for (int i = 0; i < 3; i++) {
            TestEntity entity = new TestEntity(i, "Entity " + i, 
                                               location.x, location.y, location.z, 1);
            tetree.insert(entity);
        }
        
        // Get node stats before transition
        long nodeIndex = tetree.locate(location, (byte) 0).index();
        var stats = tetree.getNodeStorageStats(nodeIndex);
        assertNotNull(stats);
        // May start as either type depending on implementation
        
        // Add more entities to potentially trigger array storage
        for (int i = 3; i < 10; i++) {
            TestEntity entity = new TestEntity(i, "Entity " + i, 
                                               location.x, location.y, location.z, 1);
            tetree.insert(entity);
        }
        
        // Check stats after adding many entities
        stats = tetree.getNodeStorageStats(nodeIndex);
        assertNotNull(stats);
        assertEquals(10, stats.entityCount());
    }

    @Test
    void testTreeStorageStats() {
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .arrayThreshold(5)
            .alwaysUseArrayNodes(false)
            .build();
            
        ConfigurableTetree<LongEntityID, String> tetree = new ConfigurableTetree<>(
            new LongEntityIDGenerator(), config
        );
        
        // Add entities at different locations
        for (int i = 0; i < 20; i++) {
            TestEntity entity = new TestEntity(i, "Entity " + i, 
                                               100 + (i % 5) * 50, 
                                               100 + ((i / 5) % 5) * 50, 
                                               100, 5);
            tetree.insert(entity);
        }
        
        // Get tree-wide statistics
        var treeStats = tetree.getTreeStorageStats();
        assertNotNull(treeStats);
        assertTrue(treeStats.totalNodes() > 0);
        assertEquals(20, treeStats.totalEntities());
        assertTrue(treeStats.averageFillRatio() > 0 && treeStats.averageFillRatio() <= 1.0);
        
        System.out.printf("Tree Statistics:%n");
        System.out.printf("  Total nodes: %d%n", treeStats.totalNodes());
        System.out.printf("  Array nodes: %d%n", treeStats.arrayNodes());
        System.out.printf("  Set nodes: %d%n", treeStats.setNodes());
        System.out.printf("  Total entities: %d%n", treeStats.totalEntities());
        System.out.printf("  Total capacity: %d%n", treeStats.totalCapacity());
        System.out.printf("  Average fill ratio: %.2f%n", treeStats.averageFillRatio());
    }

    @Test
    void testAlwaysUseArrayConfig() {
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .alwaysUseArrayNodes(true)
            .arrayInitialCapacity(20)
            .build();
            
        ConfigurableTetree<LongEntityID, String> tetree = new ConfigurableTetree<>(
            new LongEntityIDGenerator(), config
        );
        
        // Add just one entity
        TestEntity entity = new TestEntity(1, "Single Entity", 100, 100, 100, 5);
        tetree.insert(entity);
        
        // Check that it's using array storage even with one entity
        long nodeIndex = tetree.locate(new Point3f(100, 100, 100), (byte) 0).index();
        var stats = tetree.getNodeStorageStats(nodeIndex);
        assertNotNull(stats);
        assertEquals("Array", stats.type());
        assertEquals(1, stats.entityCount());
        assertEquals(20, stats.capacity()); // Initial capacity
    }

    @Test
    void testSpatialOperationsWork() {
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .arrayThreshold(10)
            .enableNodeCompaction(true)
            .compactionThreshold(0.4f)
            .build();
            
        ConfigurableTetree<LongEntityID, String> tetree = new ConfigurableTetree<>(
            new LongEntityIDGenerator(), config
        );
        
        // Test various spatial operations work correctly
        TestEntity entity1 = new TestEntity(1, "Entity 1", 100, 100, 100, 10);
        TestEntity entity2 = new TestEntity(2, "Entity 2", 120, 120, 120, 10);
        TestEntity entity3 = new TestEntity(3, "Entity 3", 200, 200, 200, 10);
        
        tetree.insert(entity1);
        tetree.insert(entity2);
        tetree.insert(entity3);
        
        // Test find
        var found = tetree.find(new Point3f(100, 100, 100));
        assertNotNull(found);
        assertEquals(entity1.getId(), found.getId());
        
        // Test findNearestNeighbors
        var neighbors = tetree.findNearestNeighbors(new Point3f(110, 110, 110), 2);
        assertEquals(2, neighbors.size());
        
        // Test findEntitiesWithinRadius
        var withinRadius = tetree.findEntitiesWithinRadius(new Point3f(110, 110, 110), 30);
        assertEquals(2, withinRadius.size()); // entity1 and entity2
        
        // Test remove
        assertTrue(tetree.remove(entity2.getId()));
        assertEquals(2, tetree.size());
        
        // Verify tree stats after operations
        var treeStats = tetree.getTreeStorageStats();
        assertEquals(2, treeStats.totalEntities());
    }
}