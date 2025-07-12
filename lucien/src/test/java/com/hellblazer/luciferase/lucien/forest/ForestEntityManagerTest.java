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

import static com.hellblazer.luciferase.lucien.forest.ForestTestUtil.*;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ForestEntityManager
 */
public class ForestEntityManagerTest {
    
    private Forest<MortonKey, LongEntityID, String> forest;
    private ForestEntityManager<MortonKey, LongEntityID, String> entityManager;
    private SequentialLongIDGenerator idGenerator;
    
    @BeforeEach
    void setUp() {
        var config = ForestConfig.defaultConfig();
        forest = new Forest<>(config);
        idGenerator = new SequentialLongIDGenerator();
        
        // Add three trees to the forest
        for (int i = 0; i < 3; i++) {
            var tree = new Octree<LongEntityID, String>(idGenerator);
            var bounds = new EntityBounds(
                new Point3f(i * 100, 0, 0),
                new Point3f((i + 1) * 100, 100, 100)
            );
            ForestTestUtil.addTreeWithBounds(forest, tree, bounds, "tree_" + i);
        }
        
        entityManager = new ForestEntityManager<>(forest, idGenerator);
    }
    
    @Test
    void testInsertWithRoundRobinStrategy() {
        // Set round-robin strategy
        entityManager.setAssignmentStrategy(new ForestEntityManager.RoundRobinStrategy<>());
        
        // Insert entities
        for (int i = 0; i < 9; i++) {
            var id = new LongEntityID(i);
            var position = new Point3f(50, 50, 50); // Position doesn't matter for round-robin
            entityManager.insert(id, "Entity " + i, position, null);
        }
        
        // Verify equal distribution across trees
        var distribution = entityManager.getEntityDistribution();
        assertEquals(3, distribution.size());
        for (var entry : distribution.entrySet()) {
            assertEquals(3, entry.getValue());
        }
    }
    
    @Test
    void testInsertWithSpatialBoundsStrategy() {
        // Set spatial bounds strategy
        entityManager.setAssignmentStrategy(new ForestEntityManager.SpatialBoundsStrategy<>());
        
        // Insert entities in different spatial regions
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                var id = new LongEntityID(i * 3 + j);
                var position = new Point3f(i * 100 + 50, 50, 50); // Each tree's region
                entityManager.insert(id, "Entity " + (i * 3 + j), position, null);
            }
        }
        
        // Verify spatial distribution
        var distribution = entityManager.getEntityDistribution();
        assertEquals(3, distribution.size());
        for (var entry : distribution.entrySet()) {
            assertEquals(3, entry.getValue());
        }
    }
    
    @Test
    void testRemoveEntity() {
        var id1 = new LongEntityID(1);
        var id2 = new LongEntityID(2);
        var pos = new Point3f(50, 50, 50);
        
        entityManager.insert(id1, "Entity 1", pos, null);
        entityManager.insert(id2, "Entity 2", pos, null);
        
        assertTrue(entityManager.containsEntity(id1));
        assertTrue(entityManager.containsEntity(id2));
        
        // Remove entity
        assertTrue(entityManager.remove(id1));
        assertFalse(entityManager.containsEntity(id1));
        assertTrue(entityManager.containsEntity(id2));
        
        // Try to remove again
        assertFalse(entityManager.remove(id1));
    }
    
    @Test
    void testUpdatePosition() {
        // Set spatial bounds strategy for position-based migration
        entityManager.setAssignmentStrategy(new ForestEntityManager.SpatialBoundsStrategy<>());
        
        var id = new LongEntityID(1);
        var initialPos = new Point3f(50, 50, 50);
        var newPos = new Point3f(150, 50, 50);
        
        entityManager.insert(id, "Entity", initialPos, null);
        
        var location1 = entityManager.getEntityLocation(id);
        assertNotNull(location1);
        
        // Update position (should migrate to different tree)
        entityManager.updatePosition(id, newPos);
        
        var location2 = entityManager.getEntityLocation(id);
        assertNotNull(location2);
        assertNotEquals(location1.getTreeId(), location2.getTreeId());
        assertEquals(newPos, location2.getPosition());
    }
    
    @Test
    void testGetEntityData() {
        var id = new LongEntityID(1);
        var pos = new Point3f(50, 50, 50);
        var content = "Test Content";
        
        entityManager.insert(id, content, pos, null);
        
        assertEquals(pos, entityManager.getEntityPosition(id));
        assertEquals(content, entityManager.getEntityContent(id));
    }
    
    @Test
    void testGetEntitiesInTree() {
        entityManager.setAssignmentStrategy(new ForestEntityManager.RoundRobinStrategy<>());
        
        // Insert 10 entities
        for (int i = 0; i < 10; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(50, 50, 50);
            entityManager.insert(id, "Entity " + i, pos, null);
        }
        
        // Get entities in first tree
        var trees = forest.getAllTrees();
        var entitiesInFirstTree = entityManager.getEntitiesInTree(trees.get(0).getTreeId());
        
        assertTrue(entitiesInFirstTree.size() >= 3); // At least 3 with round-robin
    }
    
    @Test
    void testClearAllEntities() {
        // Insert entities
        for (int i = 0; i < 10; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(i * 10, 50, 50);
            entityManager.insert(id, "Entity " + i, pos, null);
        }
        
        assertEquals(10, entityManager.getEntityCount());
        
        // Clear all
        entityManager.clear();
        assertEquals(0, entityManager.getEntityCount());
        
        // Verify all trees are empty
        var distribution = entityManager.getEntityDistribution();
        for (var count : distribution.values()) {
            assertEquals(0, count);
        }
    }
    
    @Test
    void testConcurrentInsertions() throws InterruptedException {
        int numThreads = 10;
        int entitiesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger idCounter = new AtomicInteger(0);
        
        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < entitiesPerThread; i++) {
                        var id = new LongEntityID(idCounter.getAndIncrement());
                        var pos = new Point3f(
                            (float)(Math.random() * 300),
                            (float)(Math.random() * 100),
                            (float)(Math.random() * 100)
                        );
                        entityManager.insert(id, "Entity " + id, pos, null);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Verify all entities were inserted
        assertEquals(numThreads * entitiesPerThread, entityManager.getEntityCount());
    }
    
    @Test
    void testConcurrentUpdates() throws InterruptedException {
        // Insert initial entities
        int numEntities = 100;
        for (int i = 0; i < numEntities; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(50, 50, 50);
            entityManager.insert(id, "Entity " + i, pos, null);
        }
        
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = threadId; i < numEntities; i += numThreads) {
                        var id = new LongEntityID(i);
                        var newPos = new Point3f(
                            (float)(Math.random() * 300),
                            (float)(Math.random() * 100),
                            (float)(Math.random() * 100)
                        );
                        entityManager.updatePosition(id, newPos);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Verify all entities still exist
        assertEquals(numEntities, entityManager.getEntityCount());
    }
    
    @Test
    void testEntityMigrationTracking() {
        // Set spatial bounds strategy for position-based migration
        entityManager.setAssignmentStrategy(new ForestEntityManager.SpatialBoundsStrategy<>());
        
        var id = new LongEntityID(1);
        
        // Insert in first tree's region
        entityManager.insert(id, "Entity", new Point3f(50, 50, 50), null);
        var location1 = entityManager.getEntityLocation(id);
        
        // Update to second tree's region
        entityManager.updatePosition(id, new Point3f(150, 50, 50));
        var location2 = entityManager.getEntityLocation(id);
        
        // Update to third tree's region
        entityManager.updatePosition(id, new Point3f(250, 50, 50));
        var location3 = entityManager.getEntityLocation(id);
        
        // Verify migrations
        assertNotEquals(location1.getTreeId(), location2.getTreeId());
        assertNotEquals(location2.getTreeId(), location3.getTreeId());
        assertNotEquals(location1.getTreeId(), location3.getTreeId());
    }
    
    @Test
    void testInvalidOperations() {
        var id = new LongEntityID(1);
        
        // Update non-existent entity
        assertFalse(entityManager.updatePosition(id, new Point3f(0, 0, 0)));
        
        // Remove non-existent entity
        assertFalse(entityManager.remove(id));
        
        // Get data for non-existent entity
        assertNull(entityManager.getEntityPosition(id));
        assertNull(entityManager.getEntityContent(id));
        assertNull(entityManager.getEntityLocation(id));
    }
    
    @Test
    void testEntityIdGeneration() {
        var id1 = entityManager.generateEntityId();
        var id2 = entityManager.generateEntityId();
        
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }
    
    @Test
    void testGetAllEntityIds() {
        var ids = new LongEntityID[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = new LongEntityID(i);
            entityManager.insert(ids[i], "Entity " + i, new Point3f(i * 10, 50, 50), null);
        }
        
        var allIds = entityManager.getAllEntityIds();
        assertEquals(5, allIds.size());
        for (var id : ids) {
            assertTrue(allIds.contains(id));
        }
    }
}