package com.hellblazer.luciferase.lucien.prism;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;

import javax.vecmath.Point3f;

/**
 * Tests cross-spatial-index compatibility and Forest integration.
 * Verifies that Prism works correctly alongside Octree and Tetree in mixed environments.
 */
public class PrismCrossIndexCompatibilityTest {

    private SequentialLongIDGenerator idGenerator;
    private final float worldSize = 100.0f;
    private final Random random = new Random(42);
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
    }
    
    @Test
    void testForestIntegrationWithAllThreeIndices() {
        // Test that Prism works alongside other spatial indices
        // Note: Forest requires same key type, so we test them independently
        var octree = new Octree<LongEntityID, String>(idGenerator, 100, (byte)21);
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 100, (byte)21);
        var prism = new Prism<LongEntityID, String>(idGenerator, worldSize, 21);
        
        // Insert entities into appropriate trees based on characteristics
        int entitiesPerTree = 100;
        
        // Octree: General purpose entities
        for (int i = 0; i < entitiesPerTree; i++) {
            var pos = generateOctreePosition();
            octree.insert(pos, (byte)10, "OctreeEntity" + i);
        }
        
        // Tetree: Memory-efficient storage
        for (int i = 0; i < entitiesPerTree; i++) {
            var pos = generateTetreePosition();
            tetree.insert(pos, (byte)10, "TetreeEntity" + i);
        }
        
        // Prism: Terrain/horizontal-focused entities
        for (int i = 0; i < entitiesPerTree; i++) {
            var pos = generatePrismPosition();
            prism.insert(pos, (byte)10, "PrismEntity" + i);
        }
        
        // Verify all trees have correct counts
        assertEquals(entitiesPerTree, octree.entityCount());
        assertEquals(entitiesPerTree, tetree.entityCount());
        assertEquals(entitiesPerTree, prism.entityCount());
        
        // Test total entity count across all indices
        int totalEntities = octree.entityCount() + tetree.entityCount() + prism.entityCount();
        assertEquals(entitiesPerTree * 3, totalEntities);
        
        // Test k-NN on each tree type
        var queryPoint = new Point3f(50.0f, 25.0f, 50.0f);
        var octreeNeighbors = octree.kNearestNeighbors(queryPoint, 10, Float.MAX_VALUE);
        var tetreeNeighbors = tetree.kNearestNeighbors(queryPoint, 10, Float.MAX_VALUE);
        var prismNeighbors = prism.kNearestNeighbors(queryPoint, 10, Float.MAX_VALUE);
        
        // All should find some neighbors
        assertTrue(octreeNeighbors.size() > 0, "Octree should find neighbors");
        assertTrue(tetreeNeighbors.size() > 0, "Tetree should find neighbors");
        assertTrue(prismNeighbors.size() > 0, "Prism should find neighbors");
        
        System.out.printf("k-NN results - Octree: %d, Tetree: %d, Prism: %d%n", 
                         octreeNeighbors.size(), tetreeNeighbors.size(), prismNeighbors.size());
    }
    
    @Test
    void testDataMigrationBetweenIndices() {
        // Test moving entities between different spatial index types
        var octree = new Octree<LongEntityID, String>(idGenerator, 100, (byte)21);
        var prism = new Prism<LongEntityID, String>(idGenerator, worldSize, 21);
        
        // Insert entities into octree
        List<Point3f> positions = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<LongEntityID> octreeIds = new ArrayList<>();
        
        for (int i = 0; i < 50; i++) {
            var pos = generateOctreePosition();
            positions.add(pos);
            contents.add("MigrateEntity" + i);
            var id = octree.insert(pos, (byte)10, contents.get(i));
            octreeIds.add(id);
        }
        
        assertEquals(50, octree.entityCount());
        
        // Migrate entities to prism (only those that satisfy triangular constraint)
        int migrated = 0;
        List<LongEntityID> prismIds = new ArrayList<>();
        
        for (int i = 0; i < positions.size(); i++) {
            var pos = positions.get(i);
            
            // Check if position satisfies prism constraint
            if (pos.x + pos.y < worldSize) {
                // Remove from octree
                assertTrue(octree.removeEntity(octreeIds.get(i)));
                
                // Insert into prism
                var newId = prism.insert(pos, (byte)10, contents.get(i));
                prismIds.add(newId);
                migrated++;
            }
        }
        
        System.out.printf("Migrated %d/%d entities from Octree to Prism%n", migrated, positions.size());
        
        // Verify migration
        assertEquals(50 - migrated, octree.entityCount());
        assertEquals(migrated, prism.entityCount());
        
        // Verify data integrity
        for (var id : prismIds) {
            assertTrue(prism.containsEntity(id));
            assertNotNull(prism.getEntity(id));
        }
    }
    
    @Test
    void testCommonQueryInterface() {
        // Test that all three indices support the same query interface
        var octree = new Octree<LongEntityID, String>(idGenerator, 100, (byte)21);
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 100, (byte)21);
        var prism = new Prism<LongEntityID, String>(idGenerator, worldSize, 21);
        
        // Insert test data into each
        var testPos = new Point3f(30.0f, 30.0f, 30.0f);
        var octreeId = octree.insert(testPos, (byte)10, "OctreeTest");
        var tetreeId = tetree.insert(testPos, (byte)10, "TetreeTest");
        var prismId = prism.insert(testPos, (byte)10, "PrismTest");
        
        // Test common operations on all three
        
        // 1. Entity lookup
        assertTrue(octree.containsEntity(octreeId));
        assertTrue(tetree.containsEntity(tetreeId));
        assertTrue(prism.containsEntity(prismId));
        
        // 2. k-NN queries
        var queryPoint = new Point3f(31.0f, 29.0f, 30.0f);
        var octreeKnn = octree.kNearestNeighbors(queryPoint, 1, Float.MAX_VALUE);
        var tetreeKnn = tetree.kNearestNeighbors(queryPoint, 1, Float.MAX_VALUE);
        var prismKnn = prism.kNearestNeighbors(queryPoint, 1, Float.MAX_VALUE);
        
        assertEquals(1, octreeKnn.size());
        assertEquals(1, tetreeKnn.size());
        assertEquals(1, prismKnn.size());
        
        // 3. Range queries
        var searchCube = new com.hellblazer.luciferase.lucien.Spatial.Cube(25.0f, 25.0f, 25.0f, 10.0f);
        var octreeRange = octree.entitiesInRegion(searchCube);
        var tetreeRange = tetree.entitiesInRegion(searchCube);
        var prismRange = prism.entitiesInRegion(searchCube);
        
        assertEquals(1, octreeRange.size());
        assertEquals(1, tetreeRange.size());
        assertEquals(1, prismRange.size());
        
        // 4. Entity removal
        assertTrue(octree.removeEntity(octreeId));
        assertTrue(tetree.removeEntity(tetreeId));
        assertTrue(prism.removeEntity(prismId));
        
        assertEquals(0, octree.entityCount());
        assertEquals(0, tetree.entityCount());
        assertEquals(0, prism.entityCount());
    }
    
    @Test
    void testCoordinateSystemCompatibility() {
        // Test coordinate handling differences between indices
        var octree = new Octree<LongEntityID, String>(idGenerator, 100, (byte)21);
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 100, (byte)21);
        var prism = new Prism<LongEntityID, String>(idGenerator, worldSize, 21);
        
        // Test positions at various extremes
        List<Point3f> testPositions = Arrays.asList(
            new Point3f(0.001f, 0.001f, 0.001f),      // Near origin
            new Point3f(99.0f, 0.0f, 50.0f),          // X-axis extreme
            new Point3f(0.0f, 99.0f, 50.0f),          // Y-axis extreme
            new Point3f(49.0f, 49.0f, 99.0f),         // Near triangular boundary
            new Point3f(25.0f, 25.0f, 0.0f),          // Z minimum
            new Point3f(25.0f, 25.0f, 99.999f)        // Z maximum
        );
        
        for (int i = 0; i < testPositions.size(); i++) {
            var pos = testPositions.get(i);
            final int idx = i; // Make final for lambda
            
            // Octree: Should accept all positive coordinates
            assertDoesNotThrow(() -> octree.insert(pos, (byte)10, "Octree" + idx));
            
            // Tetree: Should accept all positive coordinates  
            assertDoesNotThrow(() -> tetree.insert(pos, (byte)10, "Tetree" + idx));
            
            // Prism: Should only accept if x+y < worldSize
            if (pos.x + pos.y < worldSize) {
                assertDoesNotThrow(() -> prism.insert(pos, (byte)10, "Prism" + idx));
            } else {
                assertThrows(IllegalArgumentException.class, 
                           () -> prism.insert(pos, (byte)10, "Prism" + idx));
            }
        }
    }
    
    @Test
    void testPerformanceCharacteristicsAwareness() {
        // Create a system that routes entities based on performance characteristics
        var octree = new Octree<LongEntityID, String>(idGenerator, 100, (byte)21);
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 100, (byte)21);
        var prism = new Prism<LongEntityID, String>(idGenerator, worldSize, 21);
        
        // Route entities based on use case
        
        // High-frequency query entities -> Octree (best overall performance)
        for (int i = 0; i < 100; i++) {
            var pos = generateOctreePosition();
            octree.insert(pos, (byte)10, "HighFreq" + i);
        }
        
        // Large volume, low query entities -> Tetree (best memory efficiency)
        for (int i = 0; i < 200; i++) {
            var pos = generateTetreePosition();
            tetree.insert(pos, (byte)8, "LowFreq" + i);
        }
        
        // Terrain/layer entities -> Prism (best for horizontal queries)
        for (int i = 0; i < 150; i++) {
            var pos = generatePrismPosition();
            // Use coarser vertical level for terrain
            prism.insert(pos, (byte)6, "Terrain" + i);
        }
        
        // Verify appropriate distribution
        System.out.printf("Entity distribution:%n");
        System.out.printf("  General (Octree): %d entities%n", octree.entityCount());
        System.out.printf("  Memory (Tetree): %d entities%n", tetree.entityCount());
        System.out.printf("  Terrain (Prism): %d entities%n", prism.entityCount());
        
        int totalEntities = octree.entityCount() + tetree.entityCount() + prism.entityCount();
        assertEquals(450, totalEntities);
    }
    
    // Helper methods
    
    private Point3f generateOctreePosition() {
        // Octree accepts any positive coordinates
        return new Point3f(
            random.nextFloat() * 99.0f,
            random.nextFloat() * 99.0f,
            random.nextFloat() * 99.0f
        );
    }
    
    private Point3f generateTetreePosition() {
        // Tetree also accepts any positive coordinates
        return new Point3f(
            random.nextFloat() * 99.0f,
            random.nextFloat() * 99.0f,
            random.nextFloat() * 99.0f
        );
    }
    
    private Point3f generatePrismPosition() {
        // Prism requires x + y < worldSize
        float x = random.nextFloat() * (worldSize * 0.7f);
        float y = random.nextFloat() * (worldSize * 0.7f - x);
        float z = random.nextFloat() * worldSize;
        return new Point3f(x, y, z);
    }
}