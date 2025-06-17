/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

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
 * Performance benchmarks for Tetree ray intersection functionality. Tests scalability and performance characteristics
 * under various load conditions. Note: Tetree performance may differ from Octree due to tetrahedral geometry
 * complexity.
 *
 * @author hal.hildebrand
 */
public class TetreeRayPerformanceTest {

    private final Random                       random = new Random(12345); // Fixed seed for reproducible tests
    private       Tetree<LongEntityID, String> tetree;
    private       SequentialLongIDGenerator    idGenerator;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testBoundedVsPointEntityPerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int numEntities = 500;

        // Test with point entities only
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        insertRandomEntities(numEntities, 1000.0f);

        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 2000.0f);

        long startTime = System.nanoTime();
        for (int i = 0; i < 25; i++) {
            tetree.rayIntersectAll(ray);
        }
        long pointDuration = System.nanoTime() - startTime;

        // Test with bounded entities only
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        insertRandomBoundedEntities(numEntities, 1000.0f, 20.0f);

        startTime = System.nanoTime();
        for (int i = 0; i < 25; i++) {
            tetree.rayIntersectAll(ray);
        }
        long boundedDuration = System.nanoTime() - startTime;

        long pointMs = pointDuration / 1_000_000;
        long boundedMs = boundedDuration / 1_000_000;

        System.out.printf("Tetree Point entities: %d ms, Bounded entities: %d ms%n", pointMs, boundedMs);

        // Both should be reasonable
        assertTrue(pointMs < 5000, "Tetree point entity intersection should be reasonably fast");
        assertTrue(boundedMs < 8000, "Tetree bounded entity intersection should be reasonably fast");
    }

    @Test
    void testLargeScalePerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        // Insert 5,000 entities (smaller than Octree due to potential tetrahedral complexity)
        int numEntities = 5000;
        insertRandomEntities(numEntities, 1000.0f);

        // Create 50 random rays
        Ray3D[] rays = createRandomRays(50, 1000.0f, 2000.0f);

        long startTime = System.nanoTime();

        for (Ray3D ray : rays) {
            List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);
            assertNotNull(intersections, "Should handle ray intersection without crashing");
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        System.out.printf("Tetree Large scale: %d entities, 50 rays, %d ms%n", numEntities, durationMs);
        assertTrue(durationMs < 60000, "Large scale test should complete within reasonable time (< 60 seconds)");
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
            List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);
            assertNotNull(intersections, "Should handle ray intersection without crashing");
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        System.out.printf("Tetree Medium scale: %d entities, 50 rays, %d ms%n", numEntities, durationMs);
        assertTrue(durationMs < 10000, "Medium scale test should complete within reasonable time (< 10 seconds)");
    }

    @Test
    void testMultiLevelPerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int entitiesPerLevel = 200;
        byte[] levels = { 8, 10, 12, 14, 16 };

        // Insert entities at different tetree levels
        for (byte level : levels) {
            for (int i = 0; i < entitiesPerLevel; i++) {
                Point3f pos = new Point3f(50.0f + random.nextFloat() * 950.0f, 50.0f + random.nextFloat() * 950.0f,
                                          50.0f + random.nextFloat() * 950.0f);
                tetree.insert(pos, level, "Level" + level + "_Entity" + i);
            }
        }

        Ray3D[] rays = createRandomRays(20, 1000.0f, 2000.0f);

        long startTime = System.nanoTime();
        for (Ray3D ray : rays) {
            tetree.rayIntersectAll(ray);
        }
        long duration = System.nanoTime() - startTime;
        long durationMs = duration / 1_000_000;

        int totalEntities = entitiesPerLevel * levels.length;
        System.out.printf("Tetree Multi-level: %d entities across %d levels, 20 rays, %d ms%n", totalEntities,
                          levels.length, durationMs);

        assertTrue(durationMs < 6000, "Multi-level tetree should perform reasonably well");
    }

    @Test
    void testPositiveCoordinateConstraint() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int numEntities = 500;

        // Test performance with entities concentrated in positive quadrant
        for (int i = 0; i < numEntities; i++) {
            Point3f pos = new Point3f(50.0f + random.nextFloat() * 950.0f, // 50-1000 range (positive)
                                      50.0f + random.nextFloat() * 950.0f, 50.0f + random.nextFloat() * 950.0f);
            byte level = (byte) (8 + random.nextInt(8)); // Level 8-15
            tetree.insert(pos, level, "Entity" + i);
        }

        // Rays starting from positive coordinates
        Ray3D[] rays = new Ray3D[20];
        for (int i = 0; i < rays.length; i++) {
            Point3f origin = new Point3f(10.0f + random.nextFloat() * 100.0f, // 10-110 range
                                         10.0f + random.nextFloat() * 100.0f, 10.0f + random.nextFloat() * 100.0f);
            Vector3f direction = new Vector3f(random.nextFloat(), // Positive direction components
                                              random.nextFloat(), random.nextFloat());
            direction.normalize();
            rays[i] = new Ray3D(origin, direction, 2000.0f);
        }

        long startTime = System.nanoTime();
        for (Ray3D ray : rays) {
            tetree.rayIntersectAll(ray);
        }
        long duration = System.nanoTime() - startTime;
        long durationMs = duration / 1_000_000;

        System.out.printf("Tetree positive coordinates: %d entities, 20 rays, %d ms%n", numEntities, durationMs);
        assertTrue(durationMs < 5000, "Positive coordinate constraint should not significantly impact performance");
    }

    @Test
    void testRayIntersectFirstVsAllPerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        // Insert 2000 entities
        int numEntities = 2000;
        insertRandomEntities(numEntities, 1000.0f);

        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 2000.0f);

        // Test rayIntersectAll performance
        long startTime = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            List<SpatialIndex.RayIntersection<LongEntityID, String>> allIntersections = tetree.rayIntersectAll(ray);
        }
        long allDuration = System.nanoTime() - startTime;

        // Test rayIntersectFirst performance
        startTime = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            tetree.rayIntersectFirst(ray);
        }
        long firstDuration = System.nanoTime() - startTime;

        long allMs = allDuration / 1_000_000;
        long firstMs = firstDuration / 1_000_000;

        System.out.printf("Tetree rayIntersectAll: %d ms, rayIntersectFirst: %d ms%n", allMs, firstMs);

        // rayIntersectFirst should be faster or at least not significantly slower
        assertTrue(firstMs <= allMs * 2, "rayIntersectFirst should not be significantly slower than rayIntersectAll");
    }

    @Test
    void testRayLengthPerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int numEntities = 500;
        insertRandomEntities(numEntities, 1000.0f);

        Point3f origin = new Point3f(50, 50, 50);
        Vector3f direction = new Vector3f(1, 1, 1);
        direction.normalize();
        float[] rayLengths = { 100.0f, 500.0f, 1000.0f, 2000.0f, 3000.0f };

        for (float length : rayLengths) {
            Ray3D ray = new Ray3D(origin, direction, length);

            long startTime = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                tetree.rayIntersectAll(ray);
            }
            long duration = System.nanoTime() - startTime;
            long durationMs = duration / 1_000_000;

            System.out.printf("Tetree Ray length: %.1f, 10 intersections, %d ms%n", length, durationMs);

            assertTrue(durationMs < 3000,
                       "Tetree ray intersection should complete reasonably fast regardless of ray length");
        }
    }

    @Test
    void testScalabilityWithEntityCount() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int[] entityCounts = { 100, 300, 500, 1000, 2000 };
        Ray3D testRay = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 2000.0f);

        for (int count : entityCounts) {
            // Fresh tetree for each test
            tetree = new Tetree<>(new SequentialLongIDGenerator());
            insertRandomEntities(count, 1000.0f);

            long startTime = System.nanoTime();

            // Perform 5 ray intersections (fewer than Octree due to potential complexity)
            for (int i = 0; i < 5; i++) {
                tetree.rayIntersectAll(testRay);
            }

            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1_000_000;

            System.out.printf("Tetree Entity count: %d, 5 rays, %d ms%n", count, durationMs);

            // Performance should scale reasonably
            assertTrue(durationMs < count / 2, "Tetree ray intersection should scale sub-linearly with entity count");
        }
    }

    @Test
    void testSmallScalePerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        // Insert 100 entities (Tetree requires positive coordinates)
        int numEntities = 100;
        insertRandomEntities(numEntities, 1000.0f);

        // Create 10 random rays
        Ray3D[] rays = createRandomRays(10, 1000.0f, 2000.0f);

        long startTime = System.nanoTime();

        for (Ray3D ray : rays) {
            List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);
            assertNotNull(intersections, "Should handle ray intersection without crashing");
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        System.out.printf("Tetree Small scale: %d entities, 10 rays, %d ms%n", numEntities, durationMs);
        assertTrue(durationMs < 2000, "Small scale test should complete reasonably fast (< 2 seconds)");
    }

    @Test
    void testTetrahedralBoundaryPerformance() {
        assumeFalse(isRunningInCI(), "Skipping performance test in CI environment");

        int numEntities = 1000;

        // Insert entities near tetrahedral cell boundaries (power of 2 coordinates)
        for (int i = 0; i < numEntities; i++) {
            // Place entities near boundary values: 64, 128, 256, 512
            float[] boundaries = { 64.0f, 128.0f, 256.0f, 512.0f };
            float boundary = boundaries[random.nextInt(boundaries.length)];

            Point3f pos = new Point3f(boundary + (random.nextFloat() - 0.5f) * 10.0f, // ±5 units around boundary
                                      boundary + (random.nextFloat() - 0.5f) * 10.0f,
                                      boundary + (random.nextFloat() - 0.5f) * 10.0f);

            // Ensure positive coordinates
            pos.x = Math.max(1.0f, pos.x);
            pos.y = Math.max(1.0f, pos.y);
            pos.z = Math.max(1.0f, pos.z);

            byte level = (byte) (10 + random.nextInt(5)); // Level 10-14
            tetree.insert(pos, level, "BoundaryEntity" + i);
        }

        // Rays that cross tetrahedral boundaries
        Ray3D[] rays = createRandomRays(30, 1000.0f, 1500.0f);

        long startTime = System.nanoTime();
        for (Ray3D ray : rays) {
            tetree.rayIntersectAll(ray);
        }
        long duration = System.nanoTime() - startTime;
        long durationMs = duration / 1_000_000;

        System.out.printf("Tetree boundary entities: %d entities, 30 rays, %d ms%n", numEntities, durationMs);
        assertTrue(durationMs < 8000, "Tetrahedral boundary performance should be reasonable");
    }

    private Ray3D[] createRandomRays(int count, float maxCoordinate, float maxDistance) {
        Ray3D[] rays = new Ray3D[count];
        for (int i = 0; i < count; i++) {
            Point3f origin = new Point3f(10.0f + random.nextFloat() * (maxCoordinate / 2), // Positive origin
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
            Point3f pos = new Point3f(50.0f + random.nextFloat() * (maxCoordinate - 100.0f), // Leave room for bounds
                                      50.0f + random.nextFloat() * (maxCoordinate - 100.0f),
                                      50.0f + random.nextFloat() * (maxCoordinate - 100.0f));

            float boundsSize = random.nextFloat() * maxBoundsSize + 5.0f; // 5-25 unit bounds
            EntityBounds bounds = new EntityBounds(
            new Point3f(pos.x - boundsSize / 2, pos.y - boundsSize / 2, pos.z - boundsSize / 2),
            new Point3f(pos.x + boundsSize / 2, pos.y + boundsSize / 2, pos.z + boundsSize / 2));

            // Ensure bounds are positive
            bounds = new EntityBounds(new Point3f(Math.max(1.0f, bounds.getMin().x), Math.max(1.0f, bounds.getMin().y),
                                                  Math.max(1.0f, bounds.getMin().z)),
                                      new Point3f(Math.max(2.0f, bounds.getMax().x), Math.max(2.0f, bounds.getMax().y),
                                                  Math.max(2.0f, bounds.getMax().z)));

            byte level = (byte) (8 + random.nextInt(8)); // Level 8-15
            LongEntityID id = idGenerator.generateID();
            tetree.insert(id, pos, level, "BoundedEntity" + i, bounds);
        }
    }

    private void insertRandomEntities(int count, float maxCoordinate) {
        for (int i = 0; i < count; i++) {
            Point3f pos = new Point3f(10.0f + random.nextFloat() * (maxCoordinate - 10.0f),
                                      // Ensure positive coordinates
                                      10.0f + random.nextFloat() * (maxCoordinate - 10.0f),
                                      10.0f + random.nextFloat() * (maxCoordinate - 10.0f));
            byte level = (byte) (8 + random.nextInt(8)); // Level 8-15
            tetree.insert(pos, level, "Entity" + i);
        }
    }

    private boolean isRunningInCI() {
        return "true".equals(System.getenv("CI")) || "true".equals(System.getProperty("CI")) || "true".equals(
        System.getenv("GITHUB_ACTIONS"));
    }
}
