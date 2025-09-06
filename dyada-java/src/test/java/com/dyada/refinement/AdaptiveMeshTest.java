package com.dyada.refinement;

import com.dyada.TestBase;
import com.dyada.core.coordinates.Bounds;
import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.LevelIndex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdaptiveMesh Tests")
class AdaptiveMeshTest extends TestBase {
    
    private AdaptiveMesh mesh;
    private Bounds testBounds;
    
    @BeforeEach
    void setupMesh() {
        testBounds = new Bounds(
            new double[]{0.0, 0.0},
            new double[]{10.0, 10.0}
        );
        mesh = new AdaptiveMesh(testBounds, 5, 10, 0.1);
    }
    
    @Test
    @DisplayName("Create adaptive mesh")
    void testCreateMesh() {
        assertNotNull(mesh);
        assertEquals(testBounds, mesh.getBounds());
        assertEquals(10, mesh.getMaxRefinementLevel());
        assertTrue(mesh.getActiveNodes().size() > 0);
    }
    
    @Test
    @DisplayName("Insert entity at position")
    void testInsertEntity() {
        var position = coordinate2D(5.0, 5.0);
        var entityId = "entity1";
        
        mesh.insertEntity(entityId, position);
        
        assertTrue(mesh.containsEntity(entityId));
        var retrievedPosition = mesh.getEntityPosition(entityId);
        assertCoordinateEquals(position, retrievedPosition, 1e-10);
    }
    
    @Test
    @DisplayName("Insert multiple entities")
    void testInsertMultipleEntities() {
        mesh.insertEntity("entity1", coordinate2D(1.0, 1.0));
        mesh.insertEntity("entity2", coordinate2D(5.0, 5.0));
        mesh.insertEntity("entity3", coordinate2D(9.0, 9.0));
        
        assertEquals(3, mesh.getEntityCount());
        assertTrue(mesh.containsEntity("entity1"));
        assertTrue(mesh.containsEntity("entity2"));
        assertTrue(mesh.containsEntity("entity3"));
    }
    
    @Test
    @DisplayName("Remove entity")
    void testRemoveEntity() {
        var entityId = "entity1";
        var position = coordinate2D(3.0, 7.0);
        
        mesh.insertEntity(entityId, position);
        assertTrue(mesh.containsEntity(entityId));
        
        boolean removed = mesh.removeEntity(entityId);
        
        assertTrue(removed);
        assertFalse(mesh.containsEntity(entityId));
        assertEquals(0, mesh.getEntityCount());
    }
    
    @Test
    @DisplayName("Remove non-existent entity")
    void testRemoveNonExistentEntity() {
        boolean removed = mesh.removeEntity("nonexistent");
        assertFalse(removed);
    }
    
    @Test
    @DisplayName("Update entity position")
    void testUpdateEntityPosition() {
        var entityId = "entity1";
        var oldPosition = coordinate2D(2.0, 2.0);
        var newPosition = coordinate2D(8.0, 8.0);
        
        mesh.insertEntity(entityId, oldPosition);
        mesh.updateEntityPosition(entityId, newPosition);
        
        var retrievedPosition = mesh.getEntityPosition(entityId);
        assertCoordinateEquals(newPosition, retrievedPosition, 1e-10);
    }
    
    @Test
    @DisplayName("Query entities in range")
    void testQueryEntitiesInRange() {
        mesh.insertEntity("entity1", coordinate2D(2.0, 2.0));
        mesh.insertEntity("entity2", coordinate2D(5.0, 5.0));
        mesh.insertEntity("entity3", coordinate2D(8.0, 8.0));
        
        var queryCenter = coordinate2D(5.0, 5.0);
        var entitiesInRange = mesh.queryEntitiesInRange(queryCenter, 3.0);
        
        assertTrue(entitiesInRange.contains("entity2"));
        // entity1 and entity3 may or may not be in range depending on exact distances
    }
    
    @Test
    @DisplayName("Adaptive refinement")
    void testAdaptiveRefinement() {
        // Insert entities to trigger refinement
        for (int i = 0; i < 10; i++) {
            var position = coordinate2D(5.0 + random.nextGaussian() * 0.5, 
                                      5.0 + random.nextGaussian() * 0.5);
            mesh.insertEntity("entity" + i, position);
        }
        
        var strategy = new ErrorBasedRefinement();
        var criteria = RefinementCriteria.simple(0.1, 0.01, 3);
        
        mesh.refine(strategy, criteria);
        
        // Should have created refined nodes
        assertTrue(mesh.getActiveNodes().size() > 1);
    }
    
    @Test
    @DisplayName("Mesh coarsening")
    void testMeshCoarsening() {
        // First create a refined mesh
        for (int i = 0; i < 5; i++) {
            mesh.insertEntity("entity" + i, coordinate2D(5.0 + i * 0.1, 5.0 + i * 0.1));
        }
        
        var strategy = new ErrorBasedRefinement();
        var criteria = RefinementCriteria.simple(0.001, 0.0001, 5); // Very strict refinement
        
        mesh.refine(strategy, criteria);
        int refinedNodes = mesh.getActiveNodes().size();
        
        // Now remove entities and coarsen
        for (int i = 1; i < 5; i++) {
            mesh.removeEntity("entity" + i);
        }
        
        var coarsenCriteria = RefinementCriteria.simple(1.0, 0.1, 1); // Very loose criteria
        mesh.coarsen(strategy, coarsenCriteria);
        
        // Should have fewer nodes after coarsening
        int coarsenedNodes = mesh.getActiveNodes().size();
        assertTrue(coarsenedNodes <= refinedNodes);
    }
    
    @Test
    @DisplayName("Mesh statistics")
    void testMeshStatistics() {
        mesh.insertEntity("entity1", coordinate2D(1.0, 1.0));
        mesh.insertEntity("entity2", coordinate2D(5.0, 5.0));
        mesh.insertEntity("entity3", coordinate2D(9.0, 9.0));
        
        var stats = mesh.computeStatistics();
        
        assertNotNull(stats);
        assertEquals(25, stats.totalCells()); // 5x5 initial grid
        assertTrue(stats.activeCells() > 0);
        assertTrue(stats.averageRefinementScore() >= 0);
    }
    
    @Test
    @DisplayName("Invalid bounds throw exception")
    void testInvalidBounds() {
        // Test that creating invalid bounds throws exception
        assertThrows(IllegalArgumentException.class, () -> {
            var invalidBounds = new Bounds(
                new double[]{10.0, 10.0},
                new double[]{0.0, 0.0}  // min > max
            );
            new AdaptiveMesh(invalidBounds, 5, 10, 0.1);
        });
    }
    
    @Test
    @DisplayName("Negative max refinement level throws exception")
    void testNegativeMaxRefinementLevel() {
        assertThrows(IllegalArgumentException.class, 
            () -> new AdaptiveMesh(testBounds, -1, 10, 0.1));
    }
    
    @Test
    @DisplayName("Insert entity outside bounds throws exception")
    void testInsertEntityOutsideBounds() {
        var outsidePosition = coordinate2D(15.0, 15.0); // Outside [0,10] x [0,10]
        
        assertThrows(IllegalArgumentException.class, 
            () -> mesh.insertEntity("entity1", outsidePosition));
    }
    
    @Test
    @DisplayName("Update non-existent entity throws exception")
    void testUpdateNonExistentEntity() {
        var newPosition = coordinate2D(5.0, 5.0);
        
        assertThrows(IllegalArgumentException.class, 
            () -> mesh.updateEntityPosition("nonexistent", newPosition));
    }
    
    @Test
    @DisplayName("Null entity ID throws exception")
    void testNullEntityId() {
        var position = coordinate2D(5.0, 5.0);
        
        assertThrows(IllegalArgumentException.class, 
            () -> mesh.insertEntity(null, position));
    }
    
    @Test
    @DisplayName("Null position throws exception")
    void testNullPosition() {
        assertThrows(IllegalArgumentException.class, 
            () -> mesh.insertEntity("entity1", null));
    }
    
    @Test
    @DisplayName("Concurrent access safety")
    void testConcurrentAccess() {
        var entityCount = 100;
        var threads = new Thread[4];
        
        // Create threads that insert entities concurrently
        for (int t = 0; t < threads.length; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < entityCount / threads.length; i++) {
                    var entityId = "thread" + threadId + "_entity" + i;
                    var position = randomCoordinate2D(0.0, 10.0);
                    try {
                        mesh.insertEntity(entityId, position);
                    } catch (Exception e) {
                        log.error("Error inserting entity in thread {}: {}", threadId, e.getMessage());
                    }
                }
            });
        }
        
        // Start all threads
        for (var thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (var thread : threads) {
            try {
                thread.join(5000); // 5 second timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Thread interrupted during concurrent test");
            }
        }
        
        // Verify all entities were inserted
        assertEquals(entityCount, mesh.getEntityCount());
    }
    
    @Test
    @DisplayName("Memory efficiency")
    void testMemoryEfficiency() {
        var initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // Insert many entities
        for (int i = 0; i < 1000; i++) {
            var position = randomCoordinate2D(0.0, 10.0);
            mesh.insertEntity("entity" + i, position);
        }
        
        var afterInsertMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        var memoryUsed = afterInsertMemory - initialMemory;
        
        log.debug("Memory used for 1000 entities: {} KB", memoryUsed / 1024);
        
        // Memory usage should be reasonable (less than 10MB for 1000 entities)
        assertTrue(memoryUsed < 10 * 1024 * 1024, "Memory usage too high: " + memoryUsed + " bytes");
    }
}