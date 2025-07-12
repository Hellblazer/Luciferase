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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Working tests demonstrating forest functionality
 */
public class ForestWorkingTest {
    
    private Forest<MortonKey, LongEntityID, String> forest;
    private SequentialLongIDGenerator idGenerator;
    
    @BeforeEach
    void setUp() {
        forest = new Forest<>();
        idGenerator = new SequentialLongIDGenerator();
    }
    
    @Test
    void testBasicForestWithEntityManager() {
        // Create and add trees
        var tree1 = new Octree<LongEntityID, String>(idGenerator);
        var tree2 = new Octree<LongEntityID, String>(idGenerator);
        
        forest.addTree(tree1);
        forest.addTree(tree2);
        
        assertEquals(2, forest.getTreeCount());
        
        // Use entity manager for operations
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Insert multiple entities
        for (int i = 0; i < 10; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(i * 10, 50, 50);
            var treeId = entityManager.insert(id, "Entity " + i, pos, null);
            assertNotNull(treeId);
        }
        
        // Verify entities were distributed
        var distribution = entityManager.getEntityDistribution();
        assertEquals(2, distribution.size());
        
        // Test entity lookup
        var id5 = new LongEntityID(5);
        assertTrue(entityManager.containsEntity(id5));
        
        var location = entityManager.getEntityLocation(id5);
        assertNotNull(location);
        assertEquals("Entity 5", entityManager.getEntityContent(id5));
    }
    
    @Test
    void testSpatialQueriesAcrossForest() {
        // Setup forest
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Create a cluster of entities
        var center = new Point3f(50, 50, 50);
        for (int i = 0; i < 20; i++) {
            var id = new LongEntityID(i);
            // Create entities in a sphere around center
            var angle = (i * 2 * Math.PI) / 20;
            var radius = 20.0f;
            var pos = new Point3f(
                center.x + (float)(radius * Math.cos(angle)),
                center.y,
                center.z + (float)(radius * Math.sin(angle))
            );
            entityManager.insert(id, "Entity " + i, pos, null);
        }
        
        // Test K-NN query
        var queries = new ForestSpatialQueries<>(forest);
        var knn = queries.findKNearestNeighbors(center, 5, Float.MAX_VALUE);
        
        assertEquals(5, knn.size());
        
        // Test range query
        var withinRange = queries.findEntitiesWithinDistance(center, 25.0f);
        assertTrue(withinRange.size() > 0);
        assertTrue(withinRange.size() <= 20);
    }
    
    @Test
    void testTreeMetadataAndBounds() {
        var tree = new Octree<LongEntityID, String>(idGenerator);
        
        // Create metadata with properties
        var metadata = TreeMetadata.builder()
            .name("TestTree")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .property("region", "North")
            .property("maxDepth", 10)
            .build();
        
        var treeId = forest.addTree(tree, metadata);
        var treeNode = forest.getTree(treeId);
        
        assertNotNull(treeNode);
        
        // Verify metadata
        var storedMetadata = treeNode.getMetadata("metadata");
        assertTrue(storedMetadata instanceof TreeMetadata);
        var treeMeta = (TreeMetadata) storedMetadata;
        assertEquals("TestTree", treeMeta.getName());
        assertEquals(TreeMetadata.TreeType.OCTREE, treeMeta.getTreeType());
        assertEquals("North", treeMeta.getProperty("region"));
        assertEquals(10, treeMeta.getProperty("maxDepth"));
    }
    
    @Test
    void testEntityMigration() {
        // Create trees with specific regions
        var tree1 = new Octree<LongEntityID, String>(idGenerator);
        var tree2 = new Octree<LongEntityID, String>(idGenerator);
        
        forest.addTree(tree1);
        forest.addTree(tree2);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Insert entity
        var id = new LongEntityID(1);
        var initialPos = new Point3f(10, 10, 10);
        var treeId1 = entityManager.insert(id, "Migrating Entity", initialPos, null);
        
        assertNotNull(treeId1);
        
        // Update position multiple times
        for (int i = 0; i < 5; i++) {
            var newPos = new Point3f(20 + i * 10, 20, 20);
            assertTrue(entityManager.updatePosition(id, newPos));
            
            var location = entityManager.getEntityLocation(id);
            assertNotNull(location);
            assertEquals(newPos, location.getPosition());
        }
        
        // Entity should still be findable
        assertTrue(entityManager.containsEntity(id));
        assertEquals("Migrating Entity", entityManager.getEntityContent(id));
    }
    
    @Test
    void testForestStatistics() {
        // Add multiple trees
        for (int i = 0; i < 3; i++) {
            var tree = new Octree<LongEntityID, String>(idGenerator);
            forest.addTree(tree);
        }
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Add entities
        int totalEntities = 30;
        for (int i = 0; i < totalEntities; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(
                (float)(Math.random() * 100),
                (float)(Math.random() * 100),
                (float)(Math.random() * 100)
            );
            entityManager.insert(id, "Entity " + i, pos, null);
        }
        
        // Check distribution
        var distribution = entityManager.getEntityDistribution();
        
        int sum = 0;
        for (var count : distribution.values()) {
            sum += count;
        }
        assertEquals(totalEntities, sum);
        
        // All trees should have some entities (with round-robin)
        for (var count : distribution.values()) {
            assertTrue(count > 0);
        }
    }
}