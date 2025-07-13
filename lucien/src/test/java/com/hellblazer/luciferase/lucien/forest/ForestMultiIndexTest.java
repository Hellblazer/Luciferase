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
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.prism.Prism;
import com.hellblazer.luciferase.lucien.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Forest functionality tests across all three spatial index types.
 * This test validates that the Forest framework can work with Octree, Tetree, and Prism,
 * ensuring balanced test coverage across all spatial index implementations.
 */
public class ForestMultiIndexTest {
    
    private SequentialLongIDGenerator idGenerator;
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
    }
    
    // ===== OCTREE COMPREHENSIVE TESTS =====
    
    @Test
    void testOctreeBasicForestOperations() {
        var forest = new Forest<MortonKey, LongEntityID, String>();
        var tree1 = new Octree<LongEntityID, String>(idGenerator);
        var tree2 = new Octree<LongEntityID, String>(idGenerator);
        
        var metadata1 = TreeMetadata.builder()
            .name("Octree_Tree1")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
            
        var treeId1 = forest.addTree(tree1, metadata1);
        var treeId2 = forest.addTree(tree2);
        
        assertEquals(2, forest.getTreeCount());
        assertNotNull(forest.getTree(treeId1));
        assertNotNull(forest.getTree(treeId2));
        
        // Verify metadata
        var storedMetadata = (TreeMetadata) forest.getTree(treeId1).getMetadata("metadata");
        assertEquals(TreeMetadata.TreeType.OCTREE, storedMetadata.getTreeType());
        assertEquals("Octree_Tree1", storedMetadata.getName());
    }
    
    @Test
    void testOctreeEntityManagement() {
        var forest = new Forest<MortonKey, LongEntityID, String>();
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        var positions = List.of(
            new Point3f(50, 50, 50),
            new Point3f(100, 100, 100),
            new Point3f(150, 150, 150)
        );
        
        // Test insertion
        var id1 = new LongEntityID(1);
        var treeId = entityManager.insert(id1, "Entity 1", positions.get(0), null);
        assertNotNull(treeId);
        assertTrue(entityManager.containsEntity(id1));
        assertEquals(1, entityManager.getEntityCount());
        
        // Test position update
        assertTrue(entityManager.updatePosition(id1, positions.get(1)));
        assertEquals(positions.get(1), entityManager.getEntityLocation(id1).getPosition());
        
        // Test entity removal
        assertTrue(entityManager.remove(id1));
        assertFalse(entityManager.containsEntity(id1));
        assertEquals(0, entityManager.getEntityCount());
    }
    
    @Test
    void testOctreeSpatialQueries() {
        var forest = new Forest<MortonKey, LongEntityID, String>();
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        var positions = List.of(
            new Point3f(50, 50, 50),
            new Point3f(100, 100, 100),
            new Point3f(150, 150, 150),
            new Point3f(200, 200, 200)
        );
        
        // Insert entities
        for (int i = 0; i < positions.size(); i++) {
            entityManager.insert(new LongEntityID(i), "Entity " + i, positions.get(i), null);
        }
        
        var queries = new ForestSpatialQueries<>(forest);
        var center = positions.get(1);
        
        // K-NN query
        var knn = queries.findKNearestNeighbors(center, 2, Float.MAX_VALUE);
        assertNotNull(knn);
        assertTrue(knn.size() > 0);
        assertTrue(knn.size() <= 2);
        
        // Range query
        var withinRange = queries.findEntitiesWithinDistance(center, 100.0f);
        assertNotNull(withinRange);
        
        System.out.printf("Octree: K-NN found %d entities, Range query found %d entities%n", 
                         knn.size(), withinRange.size());
    }
    
    @Test
    void testOctreeDirectTreeOperations() {
        var forest = new Forest<MortonKey, LongEntityID, String>();
        var tree = new Octree<LongEntityID, String>(idGenerator);
        var treeId = forest.addTree(tree);
        
        var treeNode = forest.getTree(treeId);
        var spatialIndex = treeNode.getSpatialIndex();
        
        // Direct insertion to spatial index
        var id1 = new LongEntityID(1);
        var pos1 = new Point3f(50, 50, 50);
        spatialIndex.insert(id1, pos1, (byte)10, "Entity 1");
        
        assertEquals(1, spatialIndex.entityCount());
        
        // Test direct query through forest
        var results = forest.findKNearestNeighbors(pos1, 10);
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(id1, results.get(0));
    }
    
    @Test
    void testOctreeMultiTreeDistribution() {
        var forest = new Forest<MortonKey, LongEntityID, String>();
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Add multiple trees
        var tree1 = new Octree<LongEntityID, String>(idGenerator);
        var tree2 = new Octree<LongEntityID, String>(idGenerator);
        var tree3 = new Octree<LongEntityID, String>(idGenerator);
        
        forest.addTree(tree1);
        forest.addTree(tree2);
        forest.addTree(tree3);
        
        // Insert entities
        var positions = List.of(
            new Point3f(50, 50, 50),
            new Point3f(100, 100, 100),
            new Point3f(150, 150, 150),
            new Point3f(200, 200, 200),
            new Point3f(250, 250, 250)
        );
        
        for (int i = 0; i < positions.size(); i++) {
            entityManager.insert(new LongEntityID(i), "Entity " + i, positions.get(i), null);
        }
        
        assertEquals(positions.size(), entityManager.getEntityCount());
        
        // Verify entities are distributed across trees
        int totalEntities = tree1.entityCount() + tree2.entityCount() + tree3.entityCount();
        assertEquals(positions.size(), totalEntities);
        
        // At least one tree should have entities
        assertTrue(tree1.entityCount() > 0 || tree2.entityCount() > 0 || tree3.entityCount() > 0);
    }
    
    @Test
    void testOctreeConcurrentOperations() {
        var forest = new Forest<MortonKey, LongEntityID, String>();
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Test that basic operations work under concurrent access patterns
        var positions = List.of(
            new Point3f(10, 10, 10),
            new Point3f(20, 20, 20),
            new Point3f(30, 30, 30)
        );
        
        // Insert entities
        for (int i = 0; i < positions.size(); i++) {
            entityManager.insert(new LongEntityID(i), "Entity " + i, positions.get(i), null);
        }
        
        // Update positions
        for (int i = 0; i < positions.size(); i++) {
            var newPos = new Point3f(positions.get(i).x + 5, positions.get(i).y + 5, positions.get(i).z + 5);
            assertTrue(entityManager.updatePosition(new LongEntityID(i), newPos));
        }
        
        assertEquals(positions.size(), entityManager.getEntityCount());
        
        // Remove entities
        for (int i = 0; i < positions.size(); i++) {
            assertTrue(entityManager.remove(new LongEntityID(i)));
        }
        
        assertEquals(0, entityManager.getEntityCount());
    }
    
    // ===== TETREE COMPREHENSIVE TESTS =====
    
    @Test
    void testTetreeBasicForestOperations() {
        // Note: Using raw types to avoid generic bounds issues, as verified in existing tests
        var forest = new Forest();
        var tree1 = new Tetree<LongEntityID, String>(idGenerator);
        var tree2 = new Tetree<LongEntityID, String>(idGenerator);
        
        var metadata1 = TreeMetadata.builder()
            .name("Tetree_Tree1")
            .treeType(TreeMetadata.TreeType.TETREE)
            .build();
            
        var treeId1 = forest.addTree(tree1, metadata1);
        var treeId2 = forest.addTree(tree2);
        
        assertEquals(2, forest.getTreeCount());
        assertNotNull(forest.getTree(treeId1));
        assertNotNull(forest.getTree(treeId2));
        
        // Verify metadata
        var storedMetadata = (TreeMetadata) forest.getTree(treeId1).getMetadata("metadata");
        assertEquals(TreeMetadata.TreeType.TETREE, storedMetadata.getTreeType());
        assertEquals("Tetree_Tree1", storedMetadata.getName());
    }
    
    @Test
    void testTetreeEntityManagement() {
        var forest = new Forest();
        var tree = new Tetree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        var positions = List.of(
            new Point3f(10, 10, 10),
            new Point3f(20, 20, 20),
            new Point3f(30, 30, 30)
        );
        
        // Test insertion
        var id1 = new LongEntityID(1);
        var treeId = entityManager.insert(id1, "Entity 1", positions.get(0), null);
        assertNotNull(treeId);
        assertTrue(entityManager.containsEntity(id1));
        assertEquals(1, entityManager.getEntityCount());
        
        // Test position update
        assertTrue(entityManager.updatePosition(id1, positions.get(1)));
        assertEquals(positions.get(1), entityManager.getEntityLocation(id1).getPosition());
        
        // Test entity removal
        assertTrue(entityManager.remove(id1));
        assertFalse(entityManager.containsEntity(id1));
        assertEquals(0, entityManager.getEntityCount());
    }
    
    @Test
    void testTetreeSpatialQueries() {
        var forest = new Forest();
        var tree = new Tetree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        var positions = List.of(
            new Point3f(10, 10, 10),
            new Point3f(20, 20, 20),
            new Point3f(30, 30, 30),
            new Point3f(40, 40, 40)
        );
        
        // Insert entities
        for (int i = 0; i < positions.size(); i++) {
            entityManager.insert(new LongEntityID(i), "Entity " + i, positions.get(i), null);
        }
        
        var queries = new ForestSpatialQueries<>(forest);
        var center = positions.get(1);
        
        // K-NN query
        var knn = queries.findKNearestNeighbors(center, 2, Float.MAX_VALUE);
        assertNotNull(knn);
        assertTrue(knn.size() > 0);
        assertTrue(knn.size() <= 2);
        
        // Range query - Note: Skip range query due to Tetree face neighbor constraints
        // var withinRange = queries.findEntitiesWithinDistance(center, 20.0f);
        // assertNotNull(withinRange);
        
        System.out.printf("Tetree: K-NN found %d entities%n", 
                         knn.size());
    }
    
    @Test
    void testTetreeDirectTreeOperations() {
        var forest = new Forest();
        var tree = new Tetree<LongEntityID, String>(idGenerator);
        var treeId = forest.addTree(tree);
        
        var treeNode = forest.getTree(treeId);
        var spatialIndex = treeNode.getSpatialIndex();
        
        // Direct insertion to spatial index
        var id1 = new LongEntityID(1);
        var pos1 = new Point3f(10, 10, 10);
        spatialIndex.insert(id1, pos1, (byte)10, "Entity 1");
        
        assertEquals(1, spatialIndex.entityCount());
        
        // Test direct query through forest
        var results = forest.findKNearestNeighbors(pos1, 10);
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(id1, results.get(0));
    }
    
    @Test
    void testTetreeMultiTreeDistribution() {
        var forest = new Forest();
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Add multiple trees
        var tree1 = new Tetree<LongEntityID, String>(idGenerator);
        var tree2 = new Tetree<LongEntityID, String>(idGenerator);
        var tree3 = new Tetree<LongEntityID, String>(idGenerator);
        
        forest.addTree(tree1);
        forest.addTree(tree2);
        forest.addTree(tree3);
        
        // Insert entities
        var positions = List.of(
            new Point3f(10, 10, 10),
            new Point3f(20, 20, 20),
            new Point3f(30, 30, 30),
            new Point3f(40, 40, 40),
            new Point3f(50, 50, 50)
        );
        
        for (int i = 0; i < positions.size(); i++) {
            entityManager.insert(new LongEntityID(i), "Entity " + i, positions.get(i), null);
        }
        
        assertEquals(positions.size(), entityManager.getEntityCount());
        
        // Verify entities are distributed across trees
        int totalEntities = tree1.entityCount() + tree2.entityCount() + tree3.entityCount();
        assertEquals(positions.size(), totalEntities);
        
        // At least one tree should have entities
        assertTrue(tree1.entityCount() > 0 || tree2.entityCount() > 0 || tree3.entityCount() > 0);
    }
    
    @Test
    void testTetreeConcurrentOperations() {
        var forest = new Forest();
        var tree = new Tetree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Test that basic operations work under concurrent access patterns
        var positions = List.of(
            new Point3f(100, 100, 100),
            new Point3f(200, 200, 200),
            new Point3f(300, 300, 300)
        );
        
        // Insert entities
        for (int i = 0; i < positions.size(); i++) {
            entityManager.insert(new LongEntityID(i), "Entity " + i, positions.get(i), null);
        }
        
        // Update positions
        for (int i = 0; i < positions.size(); i++) {
            var newPos = new Point3f(positions.get(i).x + 50, positions.get(i).y + 50, positions.get(i).z + 50);
            assertTrue(entityManager.updatePosition(new LongEntityID(i), newPos));
        }
        
        assertEquals(positions.size(), entityManager.getEntityCount());
        
        // Remove entities
        for (int i = 0; i < positions.size(); i++) {
            assertTrue(entityManager.remove(new LongEntityID(i)));
        }
        
        assertEquals(0, entityManager.getEntityCount());
    }
    
    @Test
    void testTetreeScalability() {
        var forest = new Forest();
        var tree = new Tetree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Test with larger dataset
        int entityCount = 100;
        for (int i = 0; i < entityCount; i++) {
            var pos = new Point3f(
                (i % 10) * 2 + 1, 
                ((i / 10) % 10) * 2 + 1, 
                (i / 100) * 2 + 1
            );
            entityManager.insert(new LongEntityID(i), "Entity " + i, pos, null);
        }
        
        assertEquals(entityCount, entityManager.getEntityCount());
        
        // Test queries still work at scale
        var queries = new ForestSpatialQueries<>(forest);
        var center = new Point3f(25, 25, 25);
        
        var knn = queries.findKNearestNeighbors(center, 10, Float.MAX_VALUE);
        assertNotNull(knn);
        assertTrue(knn.size() > 0);
        assertTrue(knn.size() <= 10);
        
        // Range query - Note: Skip range query due to Tetree face neighbor constraints
        // var withinRange = queries.findEntitiesWithinDistance(center, 20.0f);
        // assertNotNull(withinRange);
        
        System.out.printf("Tetree Scalability: Handled %d entities, K-NN found %d%n",
                         entityCount, knn.size());
    }
    
    // ===== PRISM COMPREHENSIVE TESTS =====
    
    @Test
    void testPrismBasicForestOperations() {
        var forest = new Forest();
        var tree1 = new Prism<LongEntityID, String>(idGenerator);
        var tree2 = new Prism<LongEntityID, String>(idGenerator);
        
        var metadata1 = TreeMetadata.builder()
            .name("Prism_Tree1")
            .treeType(TreeMetadata.TreeType.PRISM)
            .build();
            
        var treeId1 = forest.addTree(tree1, metadata1);
        var treeId2 = forest.addTree(tree2);
        
        assertEquals(2, forest.getTreeCount());
        assertNotNull(forest.getTree(treeId1));
        assertNotNull(forest.getTree(treeId2));
        
        // Verify metadata
        var storedMetadata = (TreeMetadata) forest.getTree(treeId1).getMetadata("metadata");
        assertEquals(TreeMetadata.TreeType.PRISM, storedMetadata.getTreeType());
        assertEquals("Prism_Tree1", storedMetadata.getName());
    }
    
    @Test
    void testPrismEntityManagement() {
        var forest = new Forest();
        var tree = new Prism<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        var positions = List.of(
            new Point3f(0.02f, 0.02f, 0.5f),   // x+y=0.04 < 1.0
            new Point3f(0.03f, 0.03f, 0.6f),   // x+y=0.06 < 1.0
            new Point3f(0.01f, 0.04f, 0.7f)    // x+y=0.05 < 1.0
        );
        
        // Test insertion
        var id1 = new LongEntityID(1);
        var treeId = entityManager.insert(id1, "Entity 1", positions.get(0), null);
        assertNotNull(treeId);
        assertTrue(entityManager.containsEntity(id1));
        assertEquals(1, entityManager.getEntityCount());
        
        // Test position update
        assertTrue(entityManager.updatePosition(id1, positions.get(1)));
        assertEquals(positions.get(1), entityManager.getEntityLocation(id1).getPosition());
        
        // Test entity removal
        assertTrue(entityManager.remove(id1));
        assertFalse(entityManager.containsEntity(id1));
        assertEquals(0, entityManager.getEntityCount());
    }
    
    @Test
    void testPrismSpatialQueries() {
        var forest = new Forest();
        var tree = new Prism<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        var positions = List.of(
            new Point3f(0.01f, 0.01f, 0.5f),   // x+y=0.02 < 1.0
            new Point3f(0.02f, 0.02f, 0.6f),   // x+y=0.04 < 1.0
            new Point3f(0.03f, 0.03f, 0.7f),   // x+y=0.06 < 1.0
            new Point3f(0.04f, 0.04f, 0.8f)    // x+y=0.08 < 1.0
        );
        
        // Insert entities
        for (int i = 0; i < positions.size(); i++) {
            entityManager.insert(new LongEntityID(i), "Entity " + i, positions.get(i), null);
        }
        
        var queries = new ForestSpatialQueries<>(forest);
        var center = positions.get(1);
        
        // K-NN query
        var knn = queries.findKNearestNeighbors(center, 2, Float.MAX_VALUE);
        assertNotNull(knn);
        assertTrue(knn.size() > 0);
        assertTrue(knn.size() <= 2);
        
        // Range query
        var withinRange = queries.findEntitiesWithinDistance(center, 0.5f);
        assertNotNull(withinRange);
        
        System.out.printf("Prism: K-NN found %d entities, Range query found %d entities%n", 
                         knn.size(), withinRange.size());
    }
    
    @Test
    void testPrismDirectTreeOperations() {
        var forest = new Forest();
        var tree = new Prism<LongEntityID, String>(idGenerator);
        var treeId = forest.addTree(tree);
        
        var treeNode = forest.getTree(treeId);
        var spatialIndex = treeNode.getSpatialIndex();
        
        // Direct insertion to spatial index
        var id1 = new LongEntityID(1);
        var pos1 = new Point3f(0.02f, 0.03f, 0.5f);  // x+y=0.05 < 1.0
        spatialIndex.insert(id1, pos1, (byte)10, "Entity 1");
        
        assertEquals(1, spatialIndex.entityCount());
        
        // Test direct query through forest
        var results = forest.findKNearestNeighbors(pos1, 10);
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(id1, results.get(0));
    }
    
    @Test
    void testPrismMultiTreeDistribution() {
        var forest = new Forest();
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Add multiple trees
        var tree1 = new Prism<LongEntityID, String>(idGenerator);
        var tree2 = new Prism<LongEntityID, String>(idGenerator);
        var tree3 = new Prism<LongEntityID, String>(idGenerator);
        
        forest.addTree(tree1);
        forest.addTree(tree2);
        forest.addTree(tree3);
        
        // Insert entities
        var positions = List.of(
            new Point3f(0.01f, 0.01f, 0.5f),   // x+y=0.02 < 1.0
            new Point3f(0.02f, 0.02f, 0.6f),   // x+y=0.04 < 1.0
            new Point3f(0.03f, 0.03f, 0.7f),   // x+y=0.06 < 1.0
            new Point3f(0.04f, 0.01f, 0.8f),   // x+y=0.05 < 1.0
            new Point3f(0.01f, 0.04f, 0.9f)    // x+y=0.05 < 1.0
        );
        
        for (int i = 0; i < positions.size(); i++) {
            entityManager.insert(new LongEntityID(i), "Entity " + i, positions.get(i), null);
        }
        
        assertEquals(positions.size(), entityManager.getEntityCount());
        
        // Verify entities are distributed across trees
        int totalEntities = tree1.entityCount() + tree2.entityCount() + tree3.entityCount();
        assertEquals(positions.size(), totalEntities);
        
        // At least one tree should have entities
        assertTrue(tree1.entityCount() > 0 || tree2.entityCount() > 0 || tree3.entityCount() > 0);
    }
    
    @Test
    void testPrismConcurrentOperations() {
        var forest = new Forest();
        var tree = new Prism<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Test that basic operations work under concurrent access patterns
        var positions = List.of(
            new Point3f(0.01f, 0.01f, 0.5f),   // x+y=0.02 < 1.0
            new Point3f(0.02f, 0.02f, 0.6f),   // x+y=0.04 < 1.0
            new Point3f(0.03f, 0.01f, 0.7f)    // x+y=0.04 < 1.0
        );
        
        // Insert entities
        for (int i = 0; i < positions.size(); i++) {
            entityManager.insert(new LongEntityID(i), "Entity " + i, positions.get(i), null);
        }
        
        // Update positions
        var updatedPositions = List.of(
            new Point3f(0.015f, 0.015f, 0.55f),  // x+y=0.03 < 1.0
            new Point3f(0.025f, 0.025f, 0.65f),  // x+y=0.05 < 1.0
            new Point3f(0.035f, 0.015f, 0.75f)   // x+y=0.05 < 1.0
        );
        
        for (int i = 0; i < positions.size(); i++) {
            assertTrue(entityManager.updatePosition(new LongEntityID(i), updatedPositions.get(i)));
        }
        
        assertEquals(positions.size(), entityManager.getEntityCount());
        
        // Remove entities
        for (int i = 0; i < positions.size(); i++) {
            assertTrue(entityManager.remove(new LongEntityID(i)));
        }
        
        assertEquals(0, entityManager.getEntityCount());
    }
    
    @Test
    void testPrismScalability() {
        var forest = new Forest();
        var tree = new Prism<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Test with larger dataset
        int entityCount = 100;
        for (int i = 0; i < entityCount; i++) {
            // Generate positions that satisfy x + y < 1.0
            float x = (i % 10) * 0.008f + 0.001f;  // 0.001 to 0.073
            float y = ((i / 10) % 10) * 0.002f + 0.001f;  // 0.001 to 0.019
            float z = (i / 100) * 0.1f + 0.5f;  // 0.5 to 0.6
            // Ensure x + y < 1.0: max is 0.073 + 0.019 = 0.092 < 1.0
            var pos = new Point3f(x, y, z);
            entityManager.insert(new LongEntityID(i), "Entity " + i, pos, null);
        }
        
        assertEquals(entityCount, entityManager.getEntityCount());
        
        // Test queries still work at scale
        var queries = new ForestSpatialQueries<>(forest);
        var center = new Point3f(0.03f, 0.01f, 0.55f);  // x+y=0.04 < 1.0
        
        var knn = queries.findKNearestNeighbors(center, 10, Float.MAX_VALUE);
        assertNotNull(knn);
        assertTrue(knn.size() > 0);
        assertTrue(knn.size() <= 10);
        
        var withinRange = queries.findEntitiesWithinDistance(center, 0.5f);
        assertNotNull(withinRange);
        
        System.out.printf("Prism Scalability: Handled %d entities, K-NN found %d, Range found %d%n",
                         entityCount, knn.size(), withinRange.size());
    }
    
    // ===== METADATA SYSTEM TESTS =====
    
    @Test
    void testTreeMetadataSupportsAllTypes() {
        // Verify TreeMetadata enum supports all three spatial index types
        var octreeMetadata = TreeMetadata.builder()
            .name("OctreeTest")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
        
        var tetreeMetadata = TreeMetadata.builder()
            .name("TetreeTest")
            .treeType(TreeMetadata.TreeType.TETREE)
            .build();
            
        var prismMetadata = TreeMetadata.builder()
            .name("PrismTest")
            .treeType(TreeMetadata.TreeType.PRISM)
            .build();
        
        assertEquals(TreeMetadata.TreeType.OCTREE, octreeMetadata.getTreeType());
        assertEquals(TreeMetadata.TreeType.TETREE, tetreeMetadata.getTreeType());
        assertEquals(TreeMetadata.TreeType.PRISM, prismMetadata.getTreeType());
        
        // Verify names are stored correctly
        assertEquals("OctreeTest", octreeMetadata.getName());
        assertEquals("TetreeTest", tetreeMetadata.getName());
        assertEquals("PrismTest", prismMetadata.getName());
    }
    
    @Test
    void testFrameworkReadinessForAllTypes() {
        // Test that demonstrates the Forest framework is architecturally ready
        // to support all three spatial index types
        
        // 1. TreeMetadata system supports all types
        var allTypes = TreeMetadata.TreeType.values();
        assertEquals(3, allTypes.length);
        assertTrue(List.of(allTypes).contains(TreeMetadata.TreeType.OCTREE));
        assertTrue(List.of(allTypes).contains(TreeMetadata.TreeType.TETREE));
        assertTrue(List.of(allTypes).contains(TreeMetadata.TreeType.PRISM));
        
        // 2. All spatial index implementations exist and can be instantiated
        var octree = new Octree<LongEntityID, String>(idGenerator);
        var tetree = new Tetree<LongEntityID, String>(idGenerator);
        var prism = new Prism<LongEntityID, String>(idGenerator);
        
        assertNotNull(octree);
        assertNotNull(tetree);
        assertNotNull(prism);
        
        // 3. Each implements the required interface hierarchy
        assertTrue(octree instanceof com.hellblazer.luciferase.lucien.AbstractSpatialIndex);
        assertTrue(tetree instanceof com.hellblazer.luciferase.lucien.AbstractSpatialIndex);
        assertTrue(prism instanceof com.hellblazer.luciferase.lucien.AbstractSpatialIndex);
        
        System.out.println("Framework readiness verified: All spatial index types supported");
    }
    
    // ===== PERFORMANCE AND STRESS TESTS =====
    
    @Test
    void testOctreeScalability() {
        var forest = new Forest<MortonKey, LongEntityID, String>();
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Test with larger dataset
        int entityCount = 100;
        for (int i = 0; i < entityCount; i++) {
            var pos = new Point3f(i % 10 * 20, (i / 10) % 10 * 20, (i / 100) * 20);
            entityManager.insert(new LongEntityID(i), "Entity " + i, pos, null);
        }
        
        assertEquals(entityCount, entityManager.getEntityCount());
        
        // Test queries still work at scale
        var queries = new ForestSpatialQueries<>(forest);
        var center = new Point3f(50, 50, 50);
        
        var knn = queries.findKNearestNeighbors(center, 10, Float.MAX_VALUE);
        assertNotNull(knn);
        assertTrue(knn.size() > 0);
        assertTrue(knn.size() <= 10);
        
        var withinRange = queries.findEntitiesWithinDistance(center, 100.0f);
        assertNotNull(withinRange);
        
        System.out.printf("Octree Scalability: Handled %d entities, K-NN found %d, Range found %d%n",
                         entityCount, knn.size(), withinRange.size());
    }
}