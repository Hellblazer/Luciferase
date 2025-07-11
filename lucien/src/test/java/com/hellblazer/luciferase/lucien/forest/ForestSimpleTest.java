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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify forest functionality compiles and works
 */
public class ForestSimpleTest {
    
    private Forest<MortonKey, LongEntityID, String> forest;
    private SequentialLongIDGenerator idGenerator;
    
    @BeforeEach
    void setUp() {
        forest = new Forest<>();
        idGenerator = new SequentialLongIDGenerator();
    }
    
    @Test
    void testBasicForestOperations() {
        // Create trees
        var tree1 = new Octree<LongEntityID, String>(idGenerator);
        var tree2 = new Octree<LongEntityID, String>(idGenerator);
        
        // Add trees with metadata
        var metadata1 = TreeMetadata.builder()
            .name("Tree1")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
            
        var metadata2 = TreeMetadata.builder()
            .name("Tree2")
            .treeType(TreeMetadata.TreeType.OCTREE)
            .build();
        
        var treeId1 = forest.addTree(tree1, metadata1);
        var treeId2 = forest.addTree(tree2, metadata2);
        
        // Verify trees were added
        assertEquals(2, forest.getTreeCount());
        assertNotNull(forest.getTree(treeId1));
        assertNotNull(forest.getTree(treeId2));
        
        // Add entities directly to trees
        var tree1Node = forest.getTree(treeId1);
        var spatialIndex1 = tree1Node.getSpatialIndex();
        
        var id1 = new LongEntityID(1);
        var pos1 = new Point3f(50, 50, 50);
        spatialIndex1.insert(id1, pos1, (byte)10, "Entity 1");
        
        // Verify entity was added
        assertEquals(1, spatialIndex1.entityCount());
        
        // Test K-NN across forest
        var results = forest.findKNearestNeighbors(pos1, 10);
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(id1, results.get(0));
    }
    
    @Test
    void testForestEntityManager() {
        // Add trees first
        var tree1 = new Octree<LongEntityID, String>(idGenerator);
        var tree2 = new Octree<LongEntityID, String>(idGenerator);
        
        forest.addTree(tree1);
        forest.addTree(tree2);
        
        // Create entity manager
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Insert entities
        var id1 = new LongEntityID(1);
        var pos1 = new Point3f(50, 50, 50);
        var treeId = entityManager.insert(id1, "Entity 1", pos1, null);
        
        assertNotNull(treeId);
        assertTrue(entityManager.containsEntity(id1));
        
        // Update position
        var newPos = new Point3f(60, 60, 60);
        assertTrue(entityManager.updatePosition(id1, newPos));
        
        var location = entityManager.getEntityLocation(id1);
        assertNotNull(location);
        assertEquals(newPos, location.getPosition());
        
        // Remove entity
        assertTrue(entityManager.remove(id1));
        assertFalse(entityManager.containsEntity(id1));
    }
    
    @Test
    void testForestSpatialQueries() {
        // Setup forest with entities
        var tree = new Octree<LongEntityID, String>(idGenerator);
        forest.addTree(tree);
        
        var entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Add multiple entities
        for (int i = 0; i < 10; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(i * 10, 50, 50);
            entityManager.insert(id, "Entity " + i, pos, null);
        }
        
        // Test spatial queries
        var queries = new ForestSpatialQueries<>(forest);
        
        var center = new Point3f(50, 50, 50);
        var knn = queries.findKNearestNeighbors(center, 5, Float.MAX_VALUE);
        
        assertNotNull(knn);
        assertEquals(5, knn.size());
        
        // Test range query
        var withinRange = queries.findEntitiesWithinDistance(center, 30.0f);
        assertNotNull(withinRange);
        assertTrue(withinRange.size() > 0);
    }
}