package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.BulkOperationConfig;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Baseline performance tests for Tetree before integration enhancements. Run with: RUN_SPATIAL_INDEX_PERF_TESTS=true
 *
 * This test properly follows the established patterns and uses correct insertion levels.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
public class TetreeIntegrationBaselineTest {

    private static final int  ENTITY_COUNT    = 100_000;
    private static final int  QUERY_COUNT     = 1000;
    private static final byte INSERTION_LEVEL = 15; // Proper level for entity insertion

    private Tetree<LongEntityID, String> tetree;
    private List<Point3f>                testPoints;
    private List<Ray3D>                  testRays;
    private Random                       random;
    private SequentialLongIDGenerator    idGenerator;

    @Test
    void baselineBulkInsertPerformance() {
        System.out.println("\n=== BASELINE: Bulk Insert Performance ===");
        System.out.println("Date: June 21, 2025");
        System.out.println("Implementation: Current Tetree (before optimizations)");

        // Test 1: Individual insertions (baseline)
        long startTime = System.nanoTime();

        for (Point3f point : testPoints) {
            LongEntityID id = idGenerator.generateID();
            tetree.insert(id, point, INSERTION_LEVEL, "Entity-" + id.getValue());
        }

        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1_000_000; // Convert to ms

        System.out.printf("Individual insert %d entities: %d ms%n", ENTITY_COUNT, duration);
        System.out.printf("Throughput: %.2f entities/ms (%.0f entities/sec)%n", (double) ENTITY_COUNT / duration,
                          (double) ENTITY_COUNT / duration * 1000);
        System.out.printf("Final tree size: %d nodes%n", tetree.size());

        // Reset for bulk test
        setUp();

        // Test 2: Bulk insertion with optimizations
        System.out.println("\n--- Bulk Insertion with Current Optimizations ---");

        // Prepare bulk data
        List<Point3f> positions = new ArrayList<>(testPoints);
        List<String> contents = new ArrayList<>(ENTITY_COUNT);
        for (int i = 0; i < ENTITY_COUNT; i++) {
            contents.add("Entity-" + i);
        }

        // Configure bulk operations
        BulkOperationConfig config = BulkOperationConfig.highPerformance().withBatchSize(10000).withDeferredSubdivision(
        true).withPreSortByMorton(true);
        tetree.configureBulkOperations(config);

        startTime = System.nanoTime();
        tetree.insertBatch(positions, contents, INSERTION_LEVEL);
        endTime = System.nanoTime();
        duration = (endTime - startTime) / 1_000_000;

        System.out.printf("Bulk insert %d entities: %d ms%n", ENTITY_COUNT, duration);
        System.out.printf("Throughput: %.2f entities/ms (%.0f entities/sec)%n", (double) ENTITY_COUNT / duration,
                          (double) ENTITY_COUNT / duration * 1000);
        System.out.printf("Final tree size: %d nodes%n", tetree.size());
    }

    @Test
    void baselineMemoryUsage() {
        System.out.println("\n=== BASELINE: Memory Usage ===");
        System.out.println("Date: June 21, 2025");

        // Force GC and wait
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
        }

        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Populate tree
        for (Point3f point : testPoints) {
            LongEntityID id = idGenerator.generateID();
            tetree.insert(id, point, INSERTION_LEVEL, "Entity-" + id.getValue());
        }

        // Force GC and wait
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
        }

        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        long memoryUsed = memoryAfter - memoryBefore;
        double bytesPerEntity = (double) memoryUsed / ENTITY_COUNT;

        System.out.printf("Entities inserted: %d%n", ENTITY_COUNT);
        System.out.printf("Total memory used: %.2f MB%n", memoryUsed / (1024.0 * 1024.0));
        System.out.printf("Memory per entity: %.2f bytes%n", bytesPerEntity);
        System.out.printf("Tree node count: %d%n", tetree.size());
        System.out.printf("Average entities per node: %.2f%n", (double) ENTITY_COUNT / tetree.size());
    }

    @Test
    void baselineRayIntersectionPerformance() {
        System.out.println("\n=== BASELINE: Ray Intersection Performance ===");
        System.out.println("Date: June 21, 2025");

        // Configure for better performance
        BulkOperationConfig config = BulkOperationConfig.highPerformance();
        tetree.configureBulkOperations(config);

        // Populate tree using bulk insert
        List<Point3f> positions = new ArrayList<>(testPoints.subList(0, ENTITY_COUNT));
        List<String> contents = new ArrayList<>(ENTITY_COUNT);
        for (int i = 0; i < ENTITY_COUNT; i++) {
            contents.add("Entity-" + i);
        }
        tetree.insertBatch(positions, contents, INSERTION_LEVEL);

        System.out.printf("Tree populated with %d entities in %d nodes%n", ENTITY_COUNT, tetree.size());

        // Warm up
        for (int i = 0; i < 10; i++) {
            tetree.rayIntersectAll(testRays.get(i));
        }

        // Measure ray intersection queries
        long totalTime = 0;
        int totalIntersections = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = 0;

        for (Ray3D ray : testRays) {
            long startTime = System.nanoTime();
            var results = tetree.rayIntersectAll(ray);
            long endTime = System.nanoTime();

            long elapsed = endTime - startTime;
            totalTime += elapsed;
            totalIntersections += results.size();
            minTime = Math.min(minTime, elapsed);
            maxTime = Math.max(maxTime, elapsed);
        }

        double avgTimeMs = (totalTime / 1_000_000.0) / QUERY_COUNT;
        double minTimeMs = minTime / 1_000_000.0;
        double maxTimeMs = maxTime / 1_000_000.0;

        System.out.printf("Ray intersection queries: %d%n", QUERY_COUNT);
        System.out.printf("Average time: %.3f ms%n", avgTimeMs);
        System.out.printf("Min time: %.3f ms%n", minTimeMs);
        System.out.printf("Max time: %.3f ms%n", maxTimeMs);
        System.out.printf("Total intersections found: %d%n", totalIntersections);
        System.out.printf("Average intersections per ray: %.2f%n", (double) totalIntersections / QUERY_COUNT);

        System.out.println("\nNote: Current implementation uses AABB approximation for ray-tetrahedron intersection");
    }

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator);
        random = new Random(42); // Fixed seed for reproducibility

        // Generate test data within reasonable bounds
        testPoints = generateRandomPoints(ENTITY_COUNT);
        testRays = generateRandomRays(QUERY_COUNT);
    }

    private List<Point3f> generateRandomPoints(int count) {
        List<Point3f> points = new ArrayList<>(count);

        // Use reasonable bounds that work well with level 15
        float maxCoord = 1000.0f;

        for (int i = 0; i < count; i++) {
            points.add(
            new Point3f(random.nextFloat() * maxCoord, random.nextFloat() * maxCoord, random.nextFloat() * maxCoord));
        }
        return points;
    }

    private List<Ray3D> generateRandomRays(int count) {
        List<Ray3D> rays = new ArrayList<>(count);
        float maxCoord = 1000.0f;

        for (int i = 0; i < count; i++) {
            // Origin within bounds
            Point3f origin = new Point3f(random.nextFloat() * maxCoord, random.nextFloat() * maxCoord,
                                         random.nextFloat() * maxCoord);

            // Random direction
            Vector3f direction = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1,
                                              random.nextFloat() * 2 - 1);
            direction.normalize();

            rays.add(new Ray3D(origin, direction));
        }
        return rays;
    }
}
