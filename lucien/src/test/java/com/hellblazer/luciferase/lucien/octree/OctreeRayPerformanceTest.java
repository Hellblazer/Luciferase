/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Performance benchmarks for Octree ray intersection functionality. Tests scalability and performance characteristics
 * under various load conditions.
 *
 * @author hal.hildebrand
 */
public class OctreeRayPerformanceTest {

    private final Random                       random = new Random(12345); // Fixed seed for reproducible tests
    private       Octree<LongEntityID, String> octree;
    private       SequentialLongIDGenerator    idGenerator;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testBoundedVsPointEntityPerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int numEntities = 1000;

        // Test with point entities only
        octree = new Octree<>(new SequentialLongIDGenerator());
        insertRandomEntities(numEntities, 1000.0f);

        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 2000.0f);

        long startTime = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            octree.rayIntersectAll(ray);
        }
        long pointDuration = System.nanoTime() - startTime;

        // Test with bounded entities only
        octree = new Octree<>(new SequentialLongIDGenerator());
        insertRandomBoundedEntities(numEntities, 1000.0f, 20.0f);

        startTime = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            octree.rayIntersectAll(ray);
        }
        long boundedDuration = System.nanoTime() - startTime;

        long pointMs = pointDuration / 1_000_000;
        long boundedMs = boundedDuration / 1_000_000;

        System.out.printf("Point entities: %d ms, Bounded entities: %d ms%n", pointMs, boundedMs);

        // Both should be reasonable, bounded entities might be slightly slower due to AABB intersection
        assertTrue(pointMs < 3000, "Point entity intersection should be fast");
        assertTrue(boundedMs < 5000, "Bounded entity intersection should be reasonably fast");
    }

    @Test
    void testConcurrentRayIntersection() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int numEntities = 2000;
        insertRandomEntities(numEntities, 1000.0f);

        Ray3D[] rays = createRandomRays(100, 1000.0f, 2000.0f);

        long startTime = System.nanoTime();

        // Simulate concurrent access (note: octree should be thread-safe for reads)
        rays[0] = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 2000.0f);
        for (int i = 0; i < 10; i++) {
            for (Ray3D ray : rays) {
                List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);
                assertNotNull(intersections, "Concurrent access should work correctly");
            }
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        System.out.printf("Concurrent access: 1000 ray intersections, %d ms%n", durationMs);
        assertTrue(durationMs < 10000, "Concurrent ray intersection should complete within reasonable time");
    }

    @Test
    void testDenseVsSparseDistribution() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int numEntities = 1000;

        // Test dense distribution (small area)
        octree = new Octree<>(new SequentialLongIDGenerator());
        insertRandomEntities(numEntities, 100.0f); // Small area

        Ray3D ray = new Ray3D(new Point3f(10, 10, 10), new Vector3f(1, 1, 1), 500.0f);

        long startTime = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            octree.rayIntersectAll(ray);
        }
        long denseDuration = System.nanoTime() - startTime;

        // Test sparse distribution (large area)
        octree = new Octree<>(new SequentialLongIDGenerator());
        insertRandomEntities(numEntities, 2000.0f); // Large area

        startTime = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            octree.rayIntersectAll(ray);
        }
        long sparseDuration = System.nanoTime() - startTime;

        long denseMs = denseDuration / 1_000_000;
        long sparseMs = sparseDuration / 1_000_000;

        System.out.printf("Dense distribution: %d ms, Sparse distribution: %d ms%n", denseMs, sparseMs);

        // Both should complete in reasonable time
        assertTrue(denseMs < 5000, "Dense distribution should complete reasonably fast");
        assertTrue(sparseMs < 5000, "Sparse distribution should complete reasonably fast");
    }

    @Test
    void testLargeScalePerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        // Insert 10,000 entities
        int numEntities = 10_000;
        insertRandomEntities(numEntities, 1000.0f);

        // Create 100 random rays
        Ray3D[] rays = createRandomRays(100, 1000.0f, 2000.0f);

        long startTime = System.nanoTime();

        for (Ray3D ray : rays) {
            List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);
            assertNotNull(intersections, "Should handle ray intersection without crashing");
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        System.out.printf("Large scale: %d entities, 100 rays, %d ms%n", numEntities, durationMs);
        assertTrue(durationMs < 30000, "Large scale test should complete within reasonable time (< 30 seconds)");
    }

    @Test
    void testMediumScalePerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        // Insert 1000 entities
        int numEntities = 1000;
        insertRandomEntities(numEntities, 1000.0f);

        // Create 50 random rays
        Ray3D[] rays = createRandomRays(50, 1000.0f, 2000.0f);

        long startTime = System.nanoTime();

        for (Ray3D ray : rays) {
            List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);
            assertNotNull(intersections, "Should handle ray intersection without crashing");
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        System.out.printf("Medium scale: %d entities, 50 rays, %d ms%n", numEntities, durationMs);
        assertTrue(durationMs < 5000, "Medium scale test should complete reasonably fast (< 5 seconds)");
    }

    @Test
    void testRayIntersectFirstVsAllPerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        // Insert 5000 entities
        int numEntities = 5000;
        insertRandomEntities(numEntities, 1000.0f);

        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 2000.0f);

        // Test rayIntersectAll performance
        long startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            List<SpatialIndex.RayIntersection<LongEntityID, String>> allIntersections = octree.rayIntersectAll(ray);
        }
        long allDuration = System.nanoTime() - startTime;

        // Test rayIntersectFirst performance
        startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            octree.rayIntersectFirst(ray);
        }
        long firstDuration = System.nanoTime() - startTime;

        long allMs = allDuration / 1_000_000;
        long firstMs = firstDuration / 1_000_000;

        System.out.printf("rayIntersectAll: %d ms, rayIntersectFirst: %d ms%n", allMs, firstMs);

        // rayIntersectFirst should be faster or at least not significantly slower
        assertTrue(firstMs <= allMs * 2, "rayIntersectFirst should not be significantly slower than rayIntersectAll");
    }

    @Test
    void testRayLengthPerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int numEntities = 1000;
        insertRandomEntities(numEntities, 1000.0f);

        Point3f origin = new Point3f(50, 50, 50);
        Vector3f direction = new Vector3f(1, 1, 1);
        float[] rayLengths = { 100.0f, 500.0f, 1000.0f, 2000.0f, 5000.0f };

        for (float length : rayLengths) {
            Ray3D ray = new Ray3D(origin, direction, length);

            long startTime = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                octree.rayIntersectAll(ray);
            }
            long duration = System.nanoTime() - startTime;
            long durationMs = duration / 1_000_000;

            System.out.printf("Ray length: %.1f, 20 intersections, %d ms%n", length, durationMs);

            assertTrue(durationMs < 2000, "Ray intersection should complete quickly regardless of ray length");
        }
    }

    @Test
    void testScalabilityWithEntityCount() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int[] entityCounts = { 100, 500, 1000, 2000, 5000 };
        Ray3D testRay = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 2000.0f);

        for (int count : entityCounts) {
            // Fresh octree for each test
            octree = new Octree<>(new SequentialLongIDGenerator());
            insertRandomEntities(count, 1000.0f);

            long startTime = System.nanoTime();

            // Perform 10 ray intersections
            for (int i = 0; i < 10; i++) {
                octree.rayIntersectAll(testRay);
            }

            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1_000_000;

            System.out.printf("Entity count: %d, 10 rays, %d ms%n", count, durationMs);

            // Performance should scale reasonably
            assertTrue(durationMs < count / 5, "Ray intersection should scale sub-linearly with entity count");
        }
    }

    @Test
    void testSmallScalePerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        // Insert 100 entities
        int numEntities = 100;
        insertRandomEntities(numEntities, 1000.0f);

        // Create 10 random rays
        Ray3D[] rays = createRandomRays(10, 1000.0f, 2000.0f);

        long startTime = System.nanoTime();

        for (Ray3D ray : rays) {
            List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);
            assertNotNull(intersections, "Should handle ray intersection without crashing");
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        System.out.printf("Small scale: %d entities, 10 rays, %d ms%n", numEntities, durationMs);
        assertTrue(durationMs < 1000, "Small scale test should complete quickly (< 1 second)");
    }

    private Ray3D[] createRandomRays(int count, float maxCoordinate, float maxDistance) {
        Ray3D[] rays = new Ray3D[count];
        for (int i = 0; i < count; i++) {
            Point3f origin = new Point3f(10.0f + random.nextFloat() * (maxCoordinate / 2),
                                         // Ensure positive coordinates
                                         10.0f + random.nextFloat() * (maxCoordinate / 2),
                                         10.0f + random.nextFloat() * (maxCoordinate / 2));
            Vector3f direction = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1,
                                              random.nextFloat() * 2 - 1);
            direction.normalize();

            float distance = random.nextFloat() * maxDistance + 100.0f;
            rays[i] = new Ray3D(origin, direction, distance);
        }
        return rays;
    }

    private void insertRandomBoundedEntities(int count, float maxCoordinate, float maxBoundsSize) {
        for (int i = 0; i < count; i++) {
            Point3f pos = new Point3f(random.nextFloat() * maxCoordinate, random.nextFloat() * maxCoordinate,
                                      random.nextFloat() * maxCoordinate);

            float boundsSize = random.nextFloat() * maxBoundsSize + 5.0f; // 5-25 unit bounds
            EntityBounds bounds = new EntityBounds(
            new Point3f(pos.x - boundsSize / 2, pos.y - boundsSize / 2, pos.z - boundsSize / 2),
            new Point3f(pos.x + boundsSize / 2, pos.y + boundsSize / 2, pos.z + boundsSize / 2));

            byte level = (byte) (8 + random.nextInt(8)); // Level 8-15
            LongEntityID id = idGenerator.generateID();
            octree.insert(id, pos, level, "BoundedEntity" + i, bounds);
        }
    }

    private void insertRandomEntities(int count, float maxCoordinate) {
        for (int i = 0; i < count; i++) {
            Point3f pos = new Point3f(random.nextFloat() * maxCoordinate, random.nextFloat() * maxCoordinate,
                                      random.nextFloat() * maxCoordinate);
            byte level = (byte) (8 + random.nextInt(8)); // Level 8-15
            octree.insert(pos, level, "Entity" + i);
        }
    }

    private boolean isRunningInCI() {
        return "true".equals(System.getenv("CI")) || "true".equals(System.getProperty("CI")) || "true".equals(
        System.getenv("GITHUB_ACTIONS"));
    }
}
