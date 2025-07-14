/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.ghost;

import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ghost functionality in spatial indices.
 * Tests the integration between AbstractSpatialIndex and the ghost layer components.
 * 
 * @author Hal Hildebrand
 */
public class GhostIntegrationTest {
    
    private static final EntityIDGenerator<UUIDEntityID> ID_GENERATOR = () -> new UUIDEntityID(UUID.randomUUID());
    
    @Test
    void testOctreeGhostConfiguration() {
        var octree = new Octree<UUIDEntityID, String>(ID_GENERATOR);
        
        // Initially ghost type should be NONE
        assertEquals(GhostType.NONE, octree.getGhostType());
        assertNotNull(octree.getGhostLayer());
        
        // Set ghost type to FACES
        octree.setGhostType(GhostType.FACES);
        assertEquals(GhostType.FACES, octree.getGhostType());
        
        // Verify neighbor detector is available (set by Octree constructor)
        assertNotNull(octree.getNeighborDetector());
        
        // Test ghost layer creation (should not throw)
        assertDoesNotThrow(() -> octree.createGhostLayer());
        assertDoesNotThrow(() -> octree.updateGhostLayer());
    }
    
    @Test
    void testTetreeGhostConfiguration() {
        var tetree = new Tetree<UUIDEntityID, String>(ID_GENERATOR);
        
        // Initially ghost type should be NONE
        assertEquals(GhostType.NONE, tetree.getGhostType());
        assertNotNull(tetree.getGhostLayer());
        
        // Set ghost type to EDGES
        tetree.setGhostType(GhostType.EDGES);
        assertEquals(GhostType.EDGES, tetree.getGhostType());
        
        // Verify neighbor detector is available (set by Tetree constructor)
        assertNotNull(tetree.getNeighborDetector());
        
        // Test ghost layer creation (should not throw)
        assertDoesNotThrow(() -> tetree.createGhostLayer());
        assertDoesNotThrow(() -> tetree.updateGhostLayer());
    }
    
    @Test
    void testGhostAwareQueries() {
        var octree = new Octree<UUIDEntityID, String>(ID_GENERATOR);
        octree.setGhostType(GhostType.FACES);
        
        // Insert some test entities
        var id1 = octree.insert(new Point3f(1.0f, 1.0f, 1.0f), (byte) 5, "Entity1");
        var id2 = octree.insert(new Point3f(2.0f, 2.0f, 2.0f), (byte) 5, "Entity2");
        var id3 = octree.insert(new Point3f(3.0f, 3.0f, 3.0f), (byte) 5, "Entity3");
        
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotNull(id3);
        
        // Test ghost-aware query methods
        var spatialKey = octree.calculateSpatialIndex(new Point3f(1.0f, 1.0f, 1.0f), (byte) 5);
        var entitiesWithGhosts = octree.findEntitiesIncludingGhosts(spatialKey);
        assertNotNull(entitiesWithGhosts);
        assertTrue(entitiesWithGhosts.contains(id1));
        
        // Test neighbor search with ghosts
        var neighborsWithGhosts = octree.findNeighborsIncludingGhosts(new Point3f(1.5f, 1.5f, 1.5f), 2.0f);
        assertNotNull(neighborsWithGhosts);
        // Should find at least the local entities
        assertTrue(neighborsWithGhosts.size() >= 2);
    }
    
    @Test
    void testGhostHooksAfterBulkOperations() {
        var octree = new Octree<UUIDEntityID, String>(ID_GENERATOR);
        octree.setGhostType(GhostType.VERTICES);
        
        // Create test data for bulk insertion
        var positions = java.util.List.of(
            new Point3f(1.0f, 1.0f, 1.0f),
            new Point3f(2.0f, 2.0f, 2.0f),
            new Point3f(3.0f, 3.0f, 3.0f),
            new Point3f(4.0f, 4.0f, 4.0f)
        );
        var contents = java.util.List.of("Entity1", "Entity2", "Entity3", "Entity4");
        
        // Bulk insertion should trigger ghost updates automatically
        assertDoesNotThrow(() -> {
            var insertedIds = octree.insertBatch(positions, contents, (byte) 5);
            assertEquals(4, insertedIds.size());
        });
        
        // Verify that entities were inserted
        assertEquals(4, octree.entityCount());
        
        // Tree adaptation operations should also trigger ghost updates
        assertDoesNotThrow(() -> octree.finalizeBulkLoading());
    }
    
    @Test
    void testAllGhostTypes() {
        var tetree = new Tetree<UUIDEntityID, String>(ID_GENERATOR);
        
        // Test all ghost types
        for (var ghostType : GhostType.values()) {
            assertDoesNotThrow(() -> {
                tetree.setGhostType(ghostType);
                assertEquals(ghostType, tetree.getGhostType());
                
                if (ghostType != GhostType.NONE) {
                    tetree.createGhostLayer();
                    tetree.updateGhostLayer();
                }
            }, "Failed for ghost type: " + ghostType);
        }
    }
    
    @Test
    void testGhostLayerInitialization() {
        var octree = new Octree<UUIDEntityID, String>(ID_GENERATOR);
        
        // Ghost layer should be initialized even with NONE type
        assertNotNull(octree.getGhostLayer());
        assertEquals(GhostType.NONE, octree.getGhostLayer().getGhostType());
        
        // Setting a new ghost type should create a new ghost layer
        octree.setGhostType(GhostType.FACES);
        assertNotNull(octree.getGhostLayer());
        assertEquals(GhostType.FACES, octree.getGhostLayer().getGhostType());
    }
}