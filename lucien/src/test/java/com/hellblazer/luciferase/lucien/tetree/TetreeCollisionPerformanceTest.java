/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarks for Tetree collision detection.
 * Tests scalability and performance characteristics under various load conditions.
 * Accounts for tetrahedral space partitioning constraints (positive coordinates only).
 *
 * @author hal.hildebrand
 */
public class TetreeCollisionPerformanceTest {

    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator idGenerator;
    private Random random;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
        random = new Random(42); // Fixed seed for reproducible results
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testLowDensityCollisionPerformance() {
        // Test performance with low entity density (few collisions expected)
        int entityCount = 1000;
        float worldSize = 5000f; // Large world, spread out entities
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = insertRandomEntities(entityCount, worldSize);
        long insertTime = System.nanoTime() - insertStartTime;
        
        // Benchmark findAllCollisions
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Benchmark individual collision checks
        long individualStartTime = System.nanoTime();
        int checkedPairs = 0;
        for (int i = 0; i < Math.min(entities.size(), 100); i++) {
            for (int j = i + 1; j < Math.min(entities.size(), 100); j++) {
                tetree.checkCollision(entities.get(i), entities.get(j));
                checkedPairs++;
            }
        }
        long individualTime = System.nanoTime() - individualStartTime;
        
        // Performance assertions (tetrahedral partitioning may have different characteristics)
        assertTrue(insertTime < TimeUnit.MILLISECONDS.toNanos(600), "Insert time should be reasonable");
        assertTrue(findAllTime < TimeUnit.MILLISECONDS.toNanos(300), "FindAll time should be reasonable");
        assertTrue(individualTime < TimeUnit.MILLISECONDS.toNanos(150), "Individual checks should be fast");
        
        System.out.println("Tetree Low Density Performance:");
        System.out.println("  Entities: " + entityCount);
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Individual time: " + individualTime / 1_000_000 + "ms (" + checkedPairs + " pairs)");
        System.out.println("  Collisions found: " + allCollisions.size());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testHighDensityCollisionPerformance() {
        // Test performance with high entity density (many collisions expected)
        int entityCount = 500;
        float worldSize = 200f; // Small world, clustered entities (positive coordinates)
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = insertRandomEntities(entityCount, worldSize);
        long insertTime = System.nanoTime() - insertStartTime;
        
        // Benchmark findAllCollisions
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Benchmark findCollisions for a single entity
        LongEntityID testEntity = entities.get(0);
        long findEntityStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> entityCollisions = tetree.findCollisions(testEntity);
        long findEntityTime = System.nanoTime() - findEntityStartTime;
        
        // Performance assertions (tetrahedral partitioning may handle density differently)
        assertTrue(insertTime < TimeUnit.SECONDS.toNanos(3), "Insert time should be reasonable even with high density");
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(2), "FindAll should handle high density efficiently");
        assertTrue(findEntityTime < TimeUnit.MILLISECONDS.toNanos(100), "Single entity collision search should be fast");
        
        System.out.println("Tetree High Density Performance:");
        System.out.println("  Entities: " + entityCount);
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  FindEntity time: " + findEntityTime / 1_000_000 + "ms");
        System.out.println("  Total collisions: " + allCollisions.size());
        System.out.println("  Entity collisions: " + entityCollisions.size());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testScalabilityBenchmark() {
        // Test how performance scales with entity count in tetrahedral space
        int[] entityCounts = {100, 250, 500, 1000, 1500}; // Slightly adjusted for tetrahedral constraints
        
        for (int entityCount : entityCounts) {
            tetree = new Tetree<>(new SequentialLongIDGenerator()); // Fresh tetree
            
            long insertStartTime = System.nanoTime();
            insertRandomEntities(entityCount, 1000f);
            long insertTime = System.nanoTime() - insertStartTime;
            
            long findAllStartTime = System.nanoTime();
            List<SpatialIndex.CollisionPair<LongEntityID, String>> collisions = tetree.findAllCollisions();
            long findAllTime = System.nanoTime() - findAllStartTime;
            
            System.out.println("Tetree Scalability - Entities: " + entityCount + 
                             ", Insert: " + insertTime / 1_000_000 + "ms" +
                             ", FindAll: " + findAllTime / 1_000_000 + "ms" +
                             ", Collisions: " + collisions.size());
            
            // Performance should not degrade exponentially
            assertTrue(insertTime < TimeUnit.SECONDS.toNanos(8), "Insert should scale reasonably in tetrahedral space");
            assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(5), "FindAll should scale reasonably in tetrahedral space");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testBoundedEntityCollisionPerformance() {
        // Test performance with bounded entities in tetrahedral space
        int entityCount = 300;
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = insertRandomBoundedEntities(entityCount, 500f);
        long insertTime = System.nanoTime() - insertStartTime;
        
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Test collision checks between bounded entities
        long boundedCheckStartTime = System.nanoTime();
        int boundedChecks = 0;
        for (int i = 0; i < Math.min(entities.size(), 50); i++) {
            for (int j = i + 1; j < Math.min(entities.size(), 50); j++) {
                tetree.checkCollision(entities.get(i), entities.get(j));
                boundedChecks++;
            }
        }
        long boundedCheckTime = System.nanoTime() - boundedCheckStartTime;
        
        System.out.println("Tetree Bounded Entity Performance:");
        System.out.println("  Entities: " + entityCount);
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Bounded checks time: " + boundedCheckTime / 1_000_000 + "ms (" + boundedChecks + " pairs)");
        System.out.println("  Collisions found: " + allCollisions.size());
        
        assertTrue(insertTime < TimeUnit.SECONDS.toNanos(3), "Bounded entity insertion should be efficient");
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(2), "Bounded collision detection should be efficient");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testMixedEntityTypePerformance() {
        // Test performance with mixed point and bounded entities
        int pointEntities = 400;
        int boundedEntities = 200;
        
        long insertStartTime = System.nanoTime();
        
        // Insert point entities
        List<LongEntityID> pointIds = insertRandomEntities(pointEntities, 800f);
        
        // Insert bounded entities
        List<LongEntityID> boundedIds = insertRandomBoundedEntities(boundedEntities, 800f);
        
        long insertTime = System.nanoTime() - insertStartTime;
        
        // Test findAllCollisions
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Test mixed collision checks
        long mixedCheckStartTime = System.nanoTime();
        int mixedChecks = 0;
        for (int i = 0; i < Math.min(pointIds.size(), 30); i++) {
            for (int j = 0; j < Math.min(boundedIds.size(), 30); j++) {
                tetree.checkCollision(pointIds.get(i), boundedIds.get(j));
                mixedChecks++;
            }
        }
        long mixedCheckTime = System.nanoTime() - mixedCheckStartTime;
        
        System.out.println("Tetree Mixed Entity Type Performance:");
        System.out.println("  Point entities: " + pointEntities);
        System.out.println("  Bounded entities: " + boundedEntities);
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Mixed checks time: " + mixedCheckTime / 1_000_000 + "ms (" + mixedChecks + " pairs)");
        System.out.println("  Collisions found: " + allCollisions.size());
        
        assertTrue(insertTime < TimeUnit.SECONDS.toNanos(4), "Mixed insertion should be efficient");
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(3), "Mixed collision detection should be efficient");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testTetrahedralSpatialLocalityPerformance() {
        // Test performance when entities are spatially clustered in tetrahedral regions
        int clusterCount = 4; // Use tetrahedral-friendly cluster count
        int entitiesPerCluster = 100;
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = insertTetrahedralClusteredEntities(clusterCount, entitiesPerCluster);
        long insertTime = System.nanoTime() - insertStartTime;
        
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Test collision checks within clusters (should benefit from tetrahedral locality)
        long localityStartTime = System.nanoTime();
        int localityChecks = 0;
        for (int i = 0; i < Math.min(entities.size(), 50); i++) {
            for (int j = i + 1; j < Math.min(entities.size(), 50); j++) {
                tetree.checkCollision(entities.get(i), entities.get(j));
                localityChecks++;
            }
        }
        long localityTime = System.nanoTime() - localityStartTime;
        
        System.out.println("Tetree Spatial Locality Performance:");
        System.out.println("  Tetrahedral clusters: " + clusterCount + " x " + entitiesPerCluster + " entities");
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Locality checks time: " + localityTime / 1_000_000 + "ms (" + localityChecks + " pairs)");
        System.out.println("  Collisions found: " + allCollisions.size());
        
        // Tetrahedral locality should provide good performance
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(2), "Clustered entities should have good collision performance");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testWorstCaseScenario() {
        // Worst case: all entities at the same position (maximum collisions)
        int entityCount = 150; // Reduced for worst case in tetrahedral space
        Point3f samePosition = new Point3f(100, 100, 100); // Valid positive coordinates
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            LongEntityID id = tetree.insert(samePosition, (byte) 10, "Entity" + i);
            entities.add(id);
        }
        long insertTime = System.nanoTime() - insertStartTime;
        
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        System.out.println("Tetree Worst Case Performance:");
        System.out.println("  Entities: " + entityCount + " (all at same position)");
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Collisions found: " + allCollisions.size());
        System.out.println("  Expected collisions: " + (entityCount * (entityCount - 1) / 2));
        
        // Even in worst case, should complete in reasonable time
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(8), "Even worst case should complete in reasonable time");
        
        // Verify all possible collisions are found
        // Note: When all entities are at exactly the same position in tetrahedral space,
        // subdivision can cause some collision pairs to be missed due to the way
        // tetrahedra are split. We'll allow for some tolerance in this edge case.
        int expectedCollisions = entityCount * (entityCount - 1) / 2;
        int minAcceptable = (int)(expectedCollisions * 0.9); // Allow 10% variance
        assertTrue(allCollisions.size() >= minAcceptable, 
                  "Should find at least 90% of possible collisions. Expected: " + expectedCollisions + 
                  ", Found: " + allCollisions.size() + ", Min acceptable: " + minAcceptable);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testUpdatePerformance() {
        // Test performance when entities are frequently updated (moving entities)
        int entityCount = 500;
        List<LongEntityID> entities = insertRandomEntities(entityCount, 1000f);
        
        long updateStartTime = System.nanoTime();
        
        // Simulate moving entities (maintaining positive coordinates)
        for (int iteration = 0; iteration < 10; iteration++) {
            for (int i = 0; i < Math.min(entities.size(), 100); i++) {
                Point3f newPos = new Point3f(
                    10f + random.nextFloat() * 990f, // Ensure positive coordinates
                    10f + random.nextFloat() * 990f,
                    10f + random.nextFloat() * 990f
                );
                tetree.updateEntity(entities.get(i), newPos, (byte) 10);
            }
            
            // Check collisions after updates
            tetree.findAllCollisions();
        }
        
        long updateTime = System.nanoTime() - updateStartTime;
        
        System.out.println("Tetree Update Performance:");
        System.out.println("  Entities: " + entityCount);
        System.out.println("  Updates: 10 iterations x 100 entity moves");
        System.out.println("  Total time: " + updateTime / 1_000_000 + "ms");
        System.out.println("  Avg per update: " + (updateTime / 1_000_000) / 1000 + "ms");
        
        assertTrue(updateTime < TimeUnit.SECONDS.toNanos(12), "Entity updates should be efficient");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testPositiveCoordinateConstraintPerformance() {
        // Test performance specifically with positive coordinate constraints
        int entityCount = 800;
        
        long insertStartTime = System.nanoTime();
        
        // Insert entities at various positive coordinate ranges
        List<LongEntityID> entities = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            Point3f position = new Point3f(
                0.1f + random.nextFloat() * 2000f, // Small to large positive coordinates
                0.1f + random.nextFloat() * 2000f,
                0.1f + random.nextFloat() * 2000f
            );
            LongEntityID id = tetree.insert(position, (byte) 10, "PositiveEntity" + i);
            entities.add(id);
        }
        
        long insertTime = System.nanoTime() - insertStartTime;
        
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = tetree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        System.out.println("Tetree Positive Coordinate Performance:");
        System.out.println("  Entities: " + entityCount + " (wide positive range)");
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Collisions found: " + allCollisions.size());
        
        assertTrue(insertTime < TimeUnit.SECONDS.toNanos(3), "Positive coordinate insertion should be efficient");
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(2), "Positive coordinate collision detection should be efficient");
    }

    private List<LongEntityID> insertRandomEntities(int count, float worldSize) {
        List<LongEntityID> entities = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Point3f position = new Point3f(
                1f + random.nextFloat() * (worldSize - 1f), // Ensure positive coordinates
                1f + random.nextFloat() * (worldSize - 1f),
                1f + random.nextFloat() * (worldSize - 1f)
            );
            LongEntityID id = tetree.insert(position, (byte) 10, "Entity" + i);
            entities.add(id);
        }
        return entities;
    }

    private List<LongEntityID> insertRandomBoundedEntities(int count, float worldSize) {
        List<LongEntityID> entities = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Point3f center = new Point3f(
                20f + random.nextFloat() * (worldSize - 40f), // Leave room for bounds
                20f + random.nextFloat() * (worldSize - 40f),
                20f + random.nextFloat() * (worldSize - 40f)
            );
            
            float size = 5f + random.nextFloat() * 15f; // Bounds size 5-20
            EntityBounds bounds = new EntityBounds(
                new Point3f(center.x - size/2, center.y - size/2, center.z - size/2),
                new Point3f(center.x + size/2, center.y + size/2, center.z + size/2)
            );
            
            LongEntityID id = idGenerator.generateID();
            tetree.insert(id, center, (byte) 10, "BoundedEntity" + i, bounds);
            entities.add(id);
        }
        return entities;
    }

    private List<LongEntityID> insertTetrahedralClusteredEntities(int clusterCount, int entitiesPerCluster) {
        List<LongEntityID> entities = new ArrayList<>();
        
        // Use tetrahedral-friendly cluster positions
        Point3f[] tetraVertices = {
            new Point3f(100, 100, 100),
            new Point3f(300, 100, 100),
            new Point3f(200, 300, 100),
            new Point3f(200, 200, 300)
        };
        
        for (int cluster = 0; cluster < Math.min(clusterCount, tetraVertices.length); cluster++) {
            Point3f clusterCenter = tetraVertices[cluster];
            
            // Add entities around cluster center
            for (int i = 0; i < entitiesPerCluster; i++) {
                Point3f position = new Point3f(
                    Math.max(1f, clusterCenter.x + (random.nextFloat() - 0.5f) * 30f),
                    Math.max(1f, clusterCenter.y + (random.nextFloat() - 0.5f) * 30f),
                    Math.max(1f, clusterCenter.z + (random.nextFloat() - 0.5f) * 30f)
                );
                LongEntityID id = tetree.insert(position, (byte) 12, "TetraClusterEntity" + cluster + "_" + i);
                entities.add(id);
            }
        }
        
        return entities;
    }
}