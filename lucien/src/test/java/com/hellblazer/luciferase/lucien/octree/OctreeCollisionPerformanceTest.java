/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

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
 * Performance benchmarks for Octree collision detection.
 * Tests scalability and performance characteristics under various load conditions.
 *
 * @author hal.hildebrand
 */
public class OctreeCollisionPerformanceTest {

    private Octree<LongEntityID, String> octree;
    private SequentialLongIDGenerator idGenerator;
    private Random random;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
        random = new Random(42); // Fixed seed for reproducible results
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testLowDensityCollisionPerformance() {
        // Test performance with low entity density (few collisions expected)
        int entityCount = 1000;
        float worldSize = 10000f; // Large world, spread out entities
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = insertRandomEntities(entityCount, worldSize);
        long insertTime = System.nanoTime() - insertStartTime;
        
        // Benchmark findAllCollisions
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Benchmark individual collision checks
        long individualStartTime = System.nanoTime();
        int checkedPairs = 0;
        for (int i = 0; i < Math.min(entities.size(), 100); i++) {
            for (int j = i + 1; j < Math.min(entities.size(), 100); j++) {
                octree.checkCollision(entities.get(i), entities.get(j));
                checkedPairs++;
            }
        }
        long individualTime = System.nanoTime() - individualStartTime;
        
        // Performance assertions (rough guidelines)
        assertTrue(insertTime < TimeUnit.MILLISECONDS.toNanos(500), "Insert time should be reasonable");
        assertTrue(findAllTime < TimeUnit.MILLISECONDS.toNanos(200), "FindAll time should be reasonable");
        assertTrue(individualTime < TimeUnit.MILLISECONDS.toNanos(100), "Individual checks should be fast");
        
        System.out.println("Low Density Performance:");
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
        float worldSize = 200f; // Small world, clustered entities
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = insertRandomEntities(entityCount, worldSize);
        long insertTime = System.nanoTime() - insertStartTime;
        
        // Benchmark findAllCollisions
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Benchmark findCollisions for a single entity
        LongEntityID testEntity = entities.get(0);
        long findEntityStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> entityCollisions = octree.findCollisions(testEntity);
        long findEntityTime = System.nanoTime() - findEntityStartTime;
        
        // Performance assertions
        assertTrue(insertTime < TimeUnit.SECONDS.toNanos(2), "Insert time should be reasonable even with high density");
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(1), "FindAll should handle high density efficiently");
        assertTrue(findEntityTime < TimeUnit.MILLISECONDS.toNanos(50), "Single entity collision search should be fast");
        
        System.out.println("High Density Performance:");
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
        // Test how performance scales with entity count
        int[] entityCounts = {100, 250, 500, 1000, 2000};
        
        for (int entityCount : entityCounts) {
            octree = new Octree<>(new SequentialLongIDGenerator()); // Fresh octree
            
            long insertStartTime = System.nanoTime();
            insertRandomEntities(entityCount, 1000f);
            long insertTime = System.nanoTime() - insertStartTime;
            
            long findAllStartTime = System.nanoTime();
            List<SpatialIndex.CollisionPair<LongEntityID, String>> collisions = octree.findAllCollisions();
            long findAllTime = System.nanoTime() - findAllStartTime;
            
            System.out.println("Scalability - Entities: " + entityCount + 
                             ", Insert: " + insertTime / 1_000_000 + "ms" +
                             ", FindAll: " + findAllTime / 1_000_000 + "ms" +
                             ", Collisions: " + collisions.size());
            
            // Performance should not degrade exponentially
            assertTrue(insertTime < TimeUnit.SECONDS.toNanos(5), "Insert should scale reasonably");
            assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(3), "FindAll should scale reasonably");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testBoundedEntityCollisionPerformance() {
        // Test performance with bounded entities
        int entityCount = 300;
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = insertRandomBoundedEntities(entityCount, 500f);
        long insertTime = System.nanoTime() - insertStartTime;
        
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Test collision checks between bounded entities
        long boundedCheckStartTime = System.nanoTime();
        int boundedChecks = 0;
        for (int i = 0; i < Math.min(entities.size(), 50); i++) {
            for (int j = i + 1; j < Math.min(entities.size(), 50); j++) {
                octree.checkCollision(entities.get(i), entities.get(j));
                boundedChecks++;
            }
        }
        long boundedCheckTime = System.nanoTime() - boundedCheckStartTime;
        
        System.out.println("Bounded Entity Performance:");
        System.out.println("  Entities: " + entityCount);
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Bounded checks time: " + boundedCheckTime / 1_000_000 + "ms (" + boundedChecks + " pairs)");
        System.out.println("  Collisions found: " + allCollisions.size());
        
        assertTrue(insertTime < TimeUnit.SECONDS.toNanos(2), "Bounded entity insertion should be efficient");
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(1), "Bounded collision detection should be efficient");
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
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Test mixed collision checks
        long mixedCheckStartTime = System.nanoTime();
        int mixedChecks = 0;
        for (int i = 0; i < Math.min(pointIds.size(), 30); i++) {
            for (int j = 0; j < Math.min(boundedIds.size(), 30); j++) {
                octree.checkCollision(pointIds.get(i), boundedIds.get(j));
                mixedChecks++;
            }
        }
        long mixedCheckTime = System.nanoTime() - mixedCheckStartTime;
        
        System.out.println("Mixed Entity Type Performance:");
        System.out.println("  Point entities: " + pointEntities);
        System.out.println("  Bounded entities: " + boundedEntities);
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Mixed checks time: " + mixedCheckTime / 1_000_000 + "ms (" + mixedChecks + " pairs)");
        System.out.println("  Collisions found: " + allCollisions.size());
        
        assertTrue(insertTime < TimeUnit.SECONDS.toNanos(3), "Mixed insertion should be efficient");
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(2), "Mixed collision detection should be efficient");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testSpatialLocalityPerformance() {
        // Test performance when entities are spatially clustered
        int clusterCount = 5;
        int entitiesPerCluster = 100;
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = insertClusteredEntities(clusterCount, entitiesPerCluster);
        long insertTime = System.nanoTime() - insertStartTime;
        
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        // Test collision checks within a cluster (should be fast due to spatial locality)
        long localityStartTime = System.nanoTime();
        int localityChecks = 0;
        for (int i = 0; i < Math.min(entities.size(), 50); i++) {
            for (int j = i + 1; j < Math.min(entities.size(), 50); j++) {
                octree.checkCollision(entities.get(i), entities.get(j));
                localityChecks++;
            }
        }
        long localityTime = System.nanoTime() - localityStartTime;
        
        System.out.println("Spatial Locality Performance:");
        System.out.println("  Clusters: " + clusterCount + " x " + entitiesPerCluster + " entities");
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Locality checks time: " + localityTime / 1_000_000 + "ms (" + localityChecks + " pairs)");
        System.out.println("  Collisions found: " + allCollisions.size());
        
        // Spatial locality should provide good performance
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(1), "Clustered entities should have good collision performance");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testWorstCaseScenario() {
        // Worst case: all entities at the same position (maximum collisions)
        int entityCount = 200; // Reduced for worst case
        Point3f samePosition = new Point3f(100, 100, 100);
        
        long insertStartTime = System.nanoTime();
        List<LongEntityID> entities = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            LongEntityID id = octree.insert(samePosition, (byte) 10, "Entity" + i);
            entities.add(id);
        }
        long insertTime = System.nanoTime() - insertStartTime;
        
        long findAllStartTime = System.nanoTime();
        List<SpatialIndex.CollisionPair<LongEntityID, String>> allCollisions = octree.findAllCollisions();
        long findAllTime = System.nanoTime() - findAllStartTime;
        
        System.out.println("Worst Case Performance:");
        System.out.println("  Entities: " + entityCount + " (all at same position)");
        System.out.println("  Insert time: " + insertTime / 1_000_000 + "ms");
        System.out.println("  FindAll time: " + findAllTime / 1_000_000 + "ms");
        System.out.println("  Collisions found: " + allCollisions.size());
        System.out.println("  Expected collisions: " + (entityCount * (entityCount - 1) / 2));
        
        // Even in worst case, should complete in reasonable time
        assertTrue(findAllTime < TimeUnit.SECONDS.toNanos(5), "Even worst case should complete in reasonable time");
        
        // Verify all possible collisions are found
        int expectedCollisions = entityCount * (entityCount - 1) / 2;
        assertEquals(expectedCollisions, allCollisions.size(), "Should find all possible collisions");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testUpdatePerformance() {
        // Test performance when entities are frequently updated (moving entities)
        int entityCount = 500;
        List<LongEntityID> entities = insertRandomEntities(entityCount, 1000f);
        
        long updateStartTime = System.nanoTime();
        
        // Simulate moving entities
        for (int iteration = 0; iteration < 10; iteration++) {
            for (int i = 0; i < Math.min(entities.size(), 100); i++) {
                Point3f newPos = new Point3f(
                    random.nextFloat() * 1000f,
                    random.nextFloat() * 1000f,
                    random.nextFloat() * 1000f
                );
                octree.updateEntity(entities.get(i), newPos, (byte) 10);
            }
            
            // Check collisions after updates
            octree.findAllCollisions();
        }
        
        long updateTime = System.nanoTime() - updateStartTime;
        
        System.out.println("Update Performance:");
        System.out.println("  Entities: " + entityCount);
        System.out.println("  Updates: 10 iterations x 100 entity moves");
        System.out.println("  Total time: " + updateTime / 1_000_000 + "ms");
        System.out.println("  Avg per update: " + (updateTime / 1_000_000) / 1000 + "ms");
        
        assertTrue(updateTime < TimeUnit.SECONDS.toNanos(10), "Entity updates should be efficient");
    }

    private List<LongEntityID> insertRandomEntities(int count, float worldSize) {
        List<LongEntityID> entities = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Point3f position = new Point3f(
                random.nextFloat() * worldSize,
                random.nextFloat() * worldSize,
                random.nextFloat() * worldSize
            );
            LongEntityID id = octree.insert(position, (byte) 10, "Entity" + i);
            entities.add(id);
        }
        return entities;
    }

    private List<LongEntityID> insertRandomBoundedEntities(int count, float worldSize) {
        List<LongEntityID> entities = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Point3f center = new Point3f(
                random.nextFloat() * worldSize,
                random.nextFloat() * worldSize,
                random.nextFloat() * worldSize
            );
            
            float size = 10f + random.nextFloat() * 20f; // Bounds size 10-30
            EntityBounds bounds = new EntityBounds(
                new Point3f(center.x - size/2, center.y - size/2, center.z - size/2),
                new Point3f(center.x + size/2, center.y + size/2, center.z + size/2)
            );
            
            LongEntityID id = idGenerator.generateID();
            octree.insert(id, center, (byte) 10, "BoundedEntity" + i, bounds);
            entities.add(id);
        }
        return entities;
    }

    private List<LongEntityID> insertClusteredEntities(int clusterCount, int entitiesPerCluster) {
        List<LongEntityID> entities = new ArrayList<>();
        
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            // Create cluster center
            Point3f clusterCenter = new Point3f(
                cluster * 200f + 100f,
                cluster * 200f + 100f,
                cluster * 200f + 100f
            );
            
            // Add entities around cluster center
            for (int i = 0; i < entitiesPerCluster; i++) {
                Point3f position = new Point3f(
                    clusterCenter.x + (random.nextFloat() - 0.5f) * 50f,
                    clusterCenter.y + (random.nextFloat() - 0.5f) * 50f,
                    clusterCenter.z + (random.nextFloat() - 0.5f) * 50f
                );
                LongEntityID id = octree.insert(position, (byte) 12, "ClusterEntity" + cluster + "_" + i);
                entities.add(id);
            }
        }
        
        return entities;
    }
}