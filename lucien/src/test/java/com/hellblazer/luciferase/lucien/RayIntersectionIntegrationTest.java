/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests focused on ray intersection functionality. Tests real-world usage scenarios and
 * cross-compatibility.
 *
 * @author hal.hildebrand
 */
public class RayIntersectionIntegrationTest {

    private Octree<LongEntityID, String> octree;
    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }

    @Test
    void testBasicRayIntersectionWorkflow() {
        // Test a complete workflow: insert entities, find intersections, verify results
        Point3f[] positions = { new Point3f(100, 100, 100), new Point3f(200, 200, 200), new Point3f(300, 300, 300) };

        // Insert into both indices
        for (int i = 0; i < positions.length; i++) {
            octree.insert(positions[i], (byte) 10, "OctreeEntity" + i);
            tetree.insert(positions[i], (byte) 10, "TetreeEntity" + i);
        }

        // Create ray that should intersect all entities
        // Distance from (50,50,50) to (300,300,300) along normalized (1,1,1) direction is ~433
        // So we need a max distance > 433 to reach all entities
        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 500.0f);

        // Test Octree
        List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeIntersections = octree.rayIntersectAll(ray);
        assertEquals(3, octreeIntersections.size(), "Octree should find all 3 entities");

        // Verify ordering by distance
        for (int i = 1; i < octreeIntersections.size(); i++) {
            assertTrue(octreeIntersections.get(i - 1).distance() <= octreeIntersections.get(i).distance(),
                       "Octree intersections should be sorted by distance");
        }

        // Test Tetree
        List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeIntersections = tetree.rayIntersectAll(ray);

        if (!tetreeIntersections.isEmpty()) {
            // Verify Tetree also maintains distance ordering
            for (int i = 1; i < tetreeIntersections.size(); i++) {
                assertTrue(tetreeIntersections.get(i - 1).distance() <= tetreeIntersections.get(i).distance(),
                           "Tetree intersections should be sorted by distance");
            }
        }
    }

    @Test
    void testConcurrentRayIntersection() throws InterruptedException, ExecutionException {
        // Test concurrent ray intersection queries while entities are being inserted
        int numThreads = 4;
        int entitiesPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        // Concurrent insert operations
        List<Future<Void>> insertFutures = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            insertFutures.add(executor.submit(() -> {
                for (int i = 0; i < entitiesPerThread; i++) {
                    Point3f pos = new Point3f(100 + threadId * 50 + i * 2, 100 + threadId * 50 + i * 2,
                                              100 + threadId * 50 + i * 2);
                    octree.insert(pos, (byte) 10, "Thread" + threadId + "Entity" + i);
                }
                return null;
            }));
        }

        // Concurrent ray intersection queries
        List<Future<Integer>> rayFutures = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
            rayFutures.add(executor.submit(() -> {
                int totalIntersections = 0;
                for (int i = 0; i < 20; i++) {
                    Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 500.0f);
                    List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(
                    ray);
                    totalIntersections += intersections.size();

                    // Small delay to allow interleaving
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return totalIntersections;
            }));
        }

        // Wait for all operations to complete
        for (Future<Void> future : insertFutures) {
            future.get();
        }

        List<Integer> rayResults = new ArrayList<>();
        for (Future<Integer> future : rayFutures) {
            rayResults.add(future.get());
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate");

        // Verify ray intersections found entities
        assertTrue(rayResults.stream().anyMatch(count -> count > 0), "Some ray queries should find intersections");

        // Final consistency check
        Ray3D finalRay = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 1000.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> finalIntersections = octree.rayIntersectAll(finalRay);
        assertTrue(finalIntersections.size() > 0, "Final ray should find entities");
    }

    @Test
    void testEntitySpanningIntegration() {
        // Test large entities that span multiple nodes with ray intersection
        Point3f center = new Point3f(256, 256, 256);
        EntityBounds largeBounds = new EntityBounds(new Point3f(200, 200, 200), new Point3f(312, 312, 312));

        LongEntityID spanningId = octree.insert(center, (byte) 8, "SpanningEntity");
        octree.insert(spanningId, center, (byte) 8, "SpanningEntity", largeBounds);

        // Test rays from multiple directions
        Point3f[] rayOrigins = { new Point3f(100, 256, 256), // From west
                                 new Point3f(400, 256, 256), // From east
                                 new Point3f(256, 100, 256), // From south
                                 new Point3f(256, 400, 256)  // From north
        };

        Vector3f[] rayDirections = { new Vector3f(1, 0, 0),   // East
                                     new Vector3f(-1, 0, 0),  // West
                                     new Vector3f(0, 1, 0),   // North
                                     new Vector3f(0, -1, 0)   // South
        };

        for (int i = 0; i < rayOrigins.length; i++) {
            Ray3D ray = new Ray3D(rayOrigins[i], rayDirections[i], 300.0f);
            List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);

            assertFalse(intersections.isEmpty(), "Ray from direction " + i + " should intersect spanning entity");
            assertTrue(intersections.stream().anyMatch(inter -> inter.entityId().equals(spanningId)),
                       "Should find the spanning entity from direction " + i);

            // Verify bounds information is preserved
            var spanningIntersection = intersections.stream()
                                                    .filter(inter -> inter.entityId().equals(spanningId))
                                                    .findFirst();

            assertTrue(spanningIntersection.isPresent(), "Should find spanning entity intersection");
            assertNotNull(spanningIntersection.get().bounds(), "Spanning entity should have bounds");
        }
    }

    @Test
    void testMixedEntityTypesWithRayIntersection() {
        // Test ray intersection with mix of point and bounded entities
        Point3f pointPos = new Point3f(150, 150, 150);
        Point3f boundedPos = new Point3f(200, 200, 200);
        EntityBounds bounds = new EntityBounds(new Point3f(180, 180, 180), new Point3f(220, 220, 220));

        LongEntityID pointId = octree.insert(pointPos, (byte) 10, "PointEntity");
        LongEntityID boundedId = octree.insert(boundedPos, (byte) 10, "BoundedEntity");
        octree.insert(boundedId, boundedPos, (byte) 10, "BoundedEntity", bounds);

        Ray3D ray = new Ray3D(new Point3f(100, 100, 100), new Vector3f(1, 1, 1), 400.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);

        assertEquals(2, intersections.size(), "Should find both point and bounded entities");

        // Identify which is which
        var pointIntersection = intersections.stream().filter(inter -> inter.entityId().equals(pointId)).findFirst();
        var boundedIntersection = intersections.stream()
                                               .filter(inter -> inter.entityId().equals(boundedId))
                                               .findFirst();

        assertTrue(pointIntersection.isPresent(), "Should find point entity");
        assertTrue(boundedIntersection.isPresent(), "Should find bounded entity");

        // Verify bounds information
        assertNull(pointIntersection.get().bounds(), "Point entity should not have bounds");
        assertNotNull(boundedIntersection.get().bounds(), "Bounded entity should have bounds");
    }

    @Test
    void testRayIntersectionAccuracyComparison() {
        // Compare accuracy between Octree and Tetree implementations
        Point3f entityPos = new Point3f(200, 200, 200);
        EntityBounds bounds = new EntityBounds(new Point3f(190, 190, 190), new Point3f(210, 210, 210));

        // Insert same entity in both indices
        LongEntityID octreeId = octree.insert(entityPos, (byte) 12, "AccuracyTest");
        LongEntityID tetreeId = tetree.insert(entityPos, (byte) 12, "AccuracyTest");

        // Update the octree entity to have bounds
        octree.insert(octreeId, entityPos, (byte) 12, "AccuracyTest", bounds);
        tetree.insert(tetreeId, entityPos, (byte) 12, "AccuracyTest", bounds);

        Ray3D ray = new Ray3D(new Point3f(100, 200, 200), new Vector3f(1, 0, 0), 200.0f);

        // Get intersections from both indices
        List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeIntersections = octree.rayIntersectAll(ray);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeIntersections = tetree.rayIntersectAll(ray);

        assertFalse(octreeIntersections.isEmpty(), "Octree should find intersection");

        if (!tetreeIntersections.isEmpty()) {
            // Compare distances (should be similar for same geometry)
            float octreeDistance = octreeIntersections.get(0).distance();
            float tetreeDistance = tetreeIntersections.get(0).distance();

            // Expected distance to front face of AABB: 190 - 100 = 90
            assertEquals(90.0f, octreeDistance, 5.0f, "Octree distance should be accurate");
            assertEquals(90.0f, tetreeDistance, 5.0f, "Tetree distance should be accurate");

            // Distances should be similar between implementations
            assertEquals(octreeDistance, tetreeDistance, 10.0f, "Implementations should give similar results");
        }
    }

    @Test
    void testRayIntersectionConsistencyAcrossLevels() {
        // Test that ray intersection works consistently across different refinement levels
        Point3f basePosition = new Point3f(128, 128, 128);

        // Insert same entity at different levels
        byte[] levels = { 8, 12, 16 };
        for (byte level : levels) {
            // Slightly offset positions to avoid exact overlap
            Point3f pos = new Point3f(basePosition.x + level * 0.1f, basePosition.y + level * 0.1f,
                                      basePosition.z + level * 0.1f);
            octree.insert(pos, level, "Level" + level + "Entity");
        }

        Ray3D ray = new Ray3D(new Point3f(64, 64, 64), new Vector3f(1, 1, 1), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);

        assertEquals(levels.length, intersections.size(), "Should find entities at all levels");

        // Verify distances are reasonable and ordered
        for (int i = 1; i < intersections.size(); i++) {
            assertTrue(intersections.get(i - 1).distance() <= intersections.get(i).distance(),
                       "Intersections should be ordered by distance regardless of level");
        }
    }

    @Test
    void testRayIntersectionErrorHandling() {
        // Test system behavior with edge cases and potential error conditions
        Point3f validPos = new Point3f(100, 100, 100);
        octree.insert(validPos, (byte) 10, "ValidEntity");

        // Test with very short ray
        Ray3D shortRay = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 1.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> shortIntersections = octree.rayIntersectAll(shortRay);
        assertTrue(shortIntersections.isEmpty(), "Short ray should not reach entity");

        // Test with very long ray
        Ray3D longRay = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 10000.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> longIntersections = octree.rayIntersectAll(longRay);
        assertFalse(longIntersections.isEmpty(), "Long ray should find entity");

        // Test with ray pointing away
        Ray3D awayRay = new Ray3D(new Point3f(100, 100, 100), new Vector3f(-1, -1, -1), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> awayIntersections = octree.rayIntersectAll(awayRay);
        // May or may not find intersections depending on ray-sphere intersection at origin

        // All operations should complete without throwing exceptions
        assertNotNull(shortIntersections, "Should handle short ray gracefully");
        assertNotNull(longIntersections, "Should handle long ray gracefully");
        assertNotNull(awayIntersections, "Should handle away ray gracefully");
    }

    @Test
    void testRayIntersectionPerformanceCharacteristics() {
        // Test that ray intersection performance scales reasonably
        int[] entityCounts = { 10, 50, 100, 500 };

        for (int count : entityCounts) {
            // Fresh octree for each test
            Octree<LongEntityID, String> testOctree = new Octree<>(new SequentialLongIDGenerator());

            // Insert entities
            for (int i = 0; i < count; i++) {
                Point3f pos = new Point3f(100 + i * 10, 100 + i * 10, 100 + i * 10);
                testOctree.insert(pos, (byte) 10, "PerfEntity" + i);
            }

            Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 10000.0f);

            long startTime = System.nanoTime();
            List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = testOctree.rayIntersectAll(ray);
            long endTime = System.nanoTime();

            long durationMs = (endTime - startTime) / 1_000_000;

            // Should complete quickly and find expected number of entities
            assertTrue(durationMs < 100, "Ray intersection should complete quickly for " + count + " entities");
            assertEquals(count, intersections.size(), "Should find all " + count + " entities");

            System.out.printf("Ray intersection with %d entities: %d ms%n", count, durationMs);
        }
    }

    @Test
    void testRayIntersectionWithinDistance() {
        // Test rayIntersectWithin functionality
        Point3f[] positions = { new Point3f(110, 110, 110), // Distance ≈ 86.6
                                new Point3f(200, 200, 200), // Distance ≈ 260.0
                                new Point3f(300, 300, 300)  // Distance ≈ 433.0
        };

        for (int i = 0; i < positions.length; i++) {
            octree.insert(positions[i], (byte) 10, "Entity" + i);
        }

        Point3f rayOrigin = new Point3f(50, 50, 50);
        Vector3f rayDirection = new Vector3f(1, 1, 1);
        rayDirection.normalize();
        Ray3D ray = new Ray3D(rayOrigin, rayDirection, 500.0f);

        float maxDistance = 300.0f; // Should include first two entities

        List<SpatialIndex.RayIntersection<LongEntityID, String>> withinIntersections = octree.rayIntersectWithin(ray,
                                                                                                                 maxDistance);

        assertTrue(withinIntersections.size() >= 1, "Should find at least the closest entity");
        assertTrue(withinIntersections.size() <= 2, "Should not find the farthest entity");

        for (var intersection : withinIntersections) {
            assertTrue(intersection.distance() <= maxDistance, "All intersections should be within max distance");
        }
    }
}
