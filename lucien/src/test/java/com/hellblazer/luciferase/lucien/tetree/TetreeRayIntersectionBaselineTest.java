package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.BulkOperationConfig;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.SpatialIndex.RayIntersection;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Baseline performance and correctness tests for ray-tetrahedron intersection. Run with:
 * RUN_SPATIAL_INDEX_PERF_TESTS=true
 *
 * This test establishes baseline metrics before implementing enhanced ray-tetrahedron intersection.
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
public class TetreeRayIntersectionBaselineTest {

    private static final int  ENTITY_COUNT      = 100_000;
    private static final int  QUERY_COUNT       = 1000;
    private static final int  WARMUP_ITERATIONS = 5;
    private static final byte INSERTION_LEVEL   = 15;

    private Tetree<LongEntityID, String> tetree;
    private List<Point3f>                testPoints;
    private List<Ray3D>                  testRays;
    private Random                       random;
    private SequentialLongIDGenerator    idGenerator;

    @Test
    void baselineRayIntersectionPerformance() {
        System.out.println("\n=== BASELINE: Ray-Tetrahedron Intersection Performance ===");
        System.out.println("Date: 2025-01-21");
        System.out.println("Implementation: Current AABB approximation");

        // Populate tree using bulk operations for efficiency
        BulkOperationConfig config = BulkOperationConfig.highPerformance().withBatchSize(10000).withDeferredSubdivision(
        true).withPreSortByMorton(true);
        tetree.configureBulkOperations(config);

        List<Point3f> positions = new ArrayList<>(testPoints.subList(0, ENTITY_COUNT));
        List<String> contents = new ArrayList<>(ENTITY_COUNT);
        for (int i = 0; i < ENTITY_COUNT; i++) {
            contents.add("Entity-" + i);
        }

        long startTime = System.nanoTime();
        tetree.insertBatch(positions, contents, INSERTION_LEVEL);
        long insertTime = (System.nanoTime() - startTime) / 1_000_000;

        System.out.printf("Tree populated with %d entities in %d ms (%d nodes)\n", ENTITY_COUNT, insertTime,
                          tetree.size());

        // Warm up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (int j = 0; j < 10; j++) {
                tetree.rayIntersectAll(testRays.get(j));
            }
        }

        // Test 1: Ray intersection queries (all intersections)
        System.out.println("\n--- Ray Intersection All ---");
        testRayIntersectionAll();

        // Test 2: Ray intersection first
        System.out.println("\n--- Ray Intersection First ---");
        testRayIntersectionFirst();

        // Test 3: Different ray types
        System.out.println("\n--- Ray Type Analysis ---");
        testDifferentRayTypes();

        // Test 4: Direct TetrahedralGeometry performance
        System.out.println("\n--- Direct Geometry Performance ---");
        testDirectGeometryPerformance();
    }

    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator);
        random = new Random(42); // Fixed seed for reproducibility

        // Generate test data
        testPoints = generateRandomPoints(ENTITY_COUNT);
        testRays = generateTestRays(QUERY_COUNT);
    }

    private List<Ray3D> generateAxisAlignedRays(int count) {
        List<Ray3D> rays = new ArrayList<>(count);
        float maxCoord = 1000.0f;
        Vector3f[] directions = { new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1), new Vector3f(-1,
                                                                                                                    0,
                                                                                                                    0),
                                  new Vector3f(0, -1, 0), new Vector3f(0, 0, -1) };

        for (int i = 0; i < count; i++) {
            Point3f origin = new Point3f(random.nextFloat() * maxCoord, random.nextFloat() * maxCoord,
                                         random.nextFloat() * maxCoord);
            rays.add(new Ray3D(origin, directions[i % directions.length]));
        }
        return rays;
    }

    private List<Ray3D> generateDiagonalRays(int count) {
        List<Ray3D> rays = new ArrayList<>(count);
        float maxCoord = 1000.0f;

        for (int i = 0; i < count; i++) {
            Point3f origin = new Point3f(random.nextFloat() * maxCoord, random.nextFloat() * maxCoord,
                                         random.nextFloat() * maxCoord);
            Vector3f direction = new Vector3f(1, 1, 1);
            direction.normalize();
            rays.add(new Ray3D(origin, direction));
        }
        return rays;
    }

    private List<Point3f> generateRandomPoints(int count) {
        List<Point3f> points = new ArrayList<>(count);
        float maxCoord = 1000.0f;

        for (int i = 0; i < count; i++) {
            points.add(
            new Point3f(random.nextFloat() * maxCoord, random.nextFloat() * maxCoord, random.nextFloat() * maxCoord));
        }
        return points;
    }

    private List<Ray3D> generateRaysFromOutside(int count) {
        List<Ray3D> rays = new ArrayList<>(count);
        float maxCoord = 1000.0f;

        for (int i = 0; i < count; i++) {
            // Origin at the edge of the positive space, pointing inward
            Point3f origin = new Point3f(0.1f,  // Just inside positive space
                                         random.nextFloat() * maxCoord, random.nextFloat() * maxCoord);

            // Direction pointing into the volume
            Point3f target = new Point3f(500.0f + random.nextFloat() * 500.0f,  // Target deeper in the volume
                                         random.nextFloat() * maxCoord, random.nextFloat() * maxCoord);

            Vector3f direction = new Vector3f();
            direction.sub(target, origin);
            direction.normalize();

            rays.add(new Ray3D(origin, direction));
        }
        return rays;
    }

    private List<Ray3D> generateTestRays(int count) {
        List<Ray3D> rays = new ArrayList<>(count);
        float maxCoord = 1000.0f;

        for (int i = 0; i < count; i++) {
            Point3f origin = new Point3f(random.nextFloat() * maxCoord, random.nextFloat() * maxCoord,
                                         random.nextFloat() * maxCoord);

            Vector3f direction = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1,
                                              random.nextFloat() * 2 - 1);
            direction.normalize();

            rays.add(new Ray3D(origin, direction));
        }
        return rays;
    }

    private void testDifferentRayTypes() {
        // Test axis-aligned rays
        List<Ray3D> axisAlignedRays = generateAxisAlignedRays(100);
        testRayCategory("Axis-aligned", axisAlignedRays);

        // Test diagonal rays
        List<Ray3D> diagonalRays = generateDiagonalRays(100);
        testRayCategory("Diagonal", diagonalRays);

        // Test rays from outside bounds
        List<Ray3D> outsideRays = generateRaysFromOutside(100);
        testRayCategory("From outside", outsideRays);
    }

    private void testDirectGeometryPerformance() {
        // Create a test tetrahedron
        Tet testTet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        Point3i[] vertices = testTet.coordinates();
        var testKey = testTet.tmIndex();

        // Test direct ray-tetrahedron intersection performance
        long totalTime = 0;
        int intersectionCount = 0;

        for (Ray3D ray : testRays) {
            long startTime = System.nanoTime();
            var result = TetrahedralGeometry.rayIntersectsTetrahedron(ray, testKey);
            boolean intersects = result.intersects;
            long endTime = System.nanoTime();

            totalTime += (endTime - startTime);
            if (intersects) {
                intersectionCount++;
            }
        }

        double avgTimeNs = (double) totalTime / QUERY_COUNT;
        double avgTimeUs = avgTimeNs / 1000.0;

        System.out.printf("Direct geometry calls: %d\n", QUERY_COUNT);
        System.out.printf("Average time per call: %.2f ns (%.3f μs)\n", avgTimeNs, avgTimeUs);
        System.out.printf("Intersections found: %d (%.1f%%)\n", intersectionCount,
                          (intersectionCount * 100.0) / QUERY_COUNT);
    }

    private void testRayCategory(String category, List<Ray3D> rays) {
        long totalTime = 0;
        int totalIntersections = 0;

        for (Ray3D ray : rays) {
            long startTime = System.nanoTime();
            List<RayIntersection<LongEntityID, String>> results = tetree.rayIntersectAll(ray);
            long endTime = System.nanoTime();

            totalTime += (endTime - startTime);
            totalIntersections += results.size();
        }

        double avgTimeMs = (totalTime / 1_000_000.0) / rays.size();
        double avgIntersections = (double) totalIntersections / rays.size();

        System.out.printf("%s rays: %.3f ms avg, %.2f intersections avg\n", category, avgTimeMs, avgIntersections);
    }

    private void testRayIntersectionAll() {
        long totalTime = 0;
        int totalIntersections = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = 0;
        int emptyResults = 0;

        for (Ray3D ray : testRays) {
            long startTime = System.nanoTime();
            List<RayIntersection<LongEntityID, String>> results = tetree.rayIntersectAll(ray);
            long endTime = System.nanoTime();

            long elapsed = endTime - startTime;
            totalTime += elapsed;
            totalIntersections += results.size();
            minTime = Math.min(minTime, elapsed);
            maxTime = Math.max(maxTime, elapsed);

            if (results.isEmpty()) {
                emptyResults++;
            }
        }

        double avgTimeMs = (totalTime / 1_000_000.0) / QUERY_COUNT;
        double minTimeMs = minTime / 1_000_000.0;
        double maxTimeMs = maxTime / 1_000_000.0;

        System.out.printf("Queries: %d\n", QUERY_COUNT);
        System.out.printf("Average time: %.3f ms\n", avgTimeMs);
        System.out.printf("Min time: %.3f ms\n", minTimeMs);
        System.out.printf("Max time: %.3f ms\n", maxTimeMs);
        System.out.printf("Total intersections: %d\n", totalIntersections);
        System.out.printf("Average intersections per ray: %.2f\n", (double) totalIntersections / QUERY_COUNT);
        System.out.printf("Empty results: %d (%.1f%%)\n", emptyResults, (emptyResults * 100.0) / QUERY_COUNT);
    }

    private void testRayIntersectionFirst() {
        long totalTime = 0;
        int foundCount = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = 0;

        for (Ray3D ray : testRays) {
            long startTime = System.nanoTime();
            var result = tetree.rayIntersectFirst(ray);
            long endTime = System.nanoTime();

            long elapsed = endTime - startTime;
            totalTime += elapsed;
            if (result.isPresent()) {
                foundCount++;
            }
            minTime = Math.min(minTime, elapsed);
            maxTime = Math.max(maxTime, elapsed);
        }

        double avgTimeMs = (totalTime / 1_000_000.0) / QUERY_COUNT;
        double minTimeMs = minTime / 1_000_000.0;
        double maxTimeMs = maxTime / 1_000_000.0;

        System.out.printf("Queries: %d\n", QUERY_COUNT);
        System.out.printf("Average time: %.3f ms\n", avgTimeMs);
        System.out.printf("Min time: %.3f ms\n", minTimeMs);
        System.out.printf("Max time: %.3f ms\n", maxTimeMs);
        System.out.printf("Found intersections: %d (%.1f%%)\n", foundCount, (foundCount * 100.0) / QUERY_COUNT);
    }
}
