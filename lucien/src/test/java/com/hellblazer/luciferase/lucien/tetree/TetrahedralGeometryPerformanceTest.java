package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Ray3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Performance comparison test between standard and enhanced tetrahedral geometry implementations. Run with:
 * RUN_SPATIAL_INDEX_PERF_TESTS=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
public class TetrahedralGeometryPerformanceTest {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int TEST_ITERATIONS   = 10000;
    private static final int RAY_COUNT         = 1000;
    private static final int TET_COUNT         = 100;

    private List<Ray3D>     testRays;
    private List<TetreeKey> testTetIndices;
    private Random          random;

    @Test
    void compareRayIntersectionPerformance() {
        System.out.println("\n=== Tetrahedral Geometry Performance Comparison ===");
        System.out.println("Date: 2025-01-21");
        System.out.println("Test iterations: " + TEST_ITERATIONS);

        // Test 1: Standard implementation
        System.out.println("\n--- Standard TetrahedralGeometry ---");
        testStandardImplementation();

        // Test 2: Enhanced implementation with caching
        System.out.println("\n--- Enhanced TetrahedralGeometry (Cached) ---");
        testEnhancedCachedImplementation();

        // Test 3: Fast boolean test
        System.out.println("\n--- Enhanced Fast Boolean Test ---");
        testEnhancedFastImplementation();

        // Test 4: Batch operations
        System.out.println("\n--- Batch Ray Testing ---");
        testBatchOperations();

        // Test 5: Cache effectiveness
        System.out.println("\n--- Cache Effectiveness ---");
        testCacheEffectiveness();
    }

    @BeforeEach
    void setUp() {
        random = new Random(42);
        testRays = generateTestRays(RAY_COUNT);
        testTetIndices = generateTestTetIndices(TET_COUNT);
    }

    @Test
    void testBoundingSphereOptimization() {
        System.out.println("\n=== Bounding Sphere Early Rejection Test ===");

        // Generate rays that mostly miss
        List<Ray3D> missRays = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Point3f origin = new Point3f(2000 + random.nextFloat() * 1000, 0, 0);
            Vector3f direction = new Vector3f(0, 1, 0); // Perpendicular to tetrahedra
            missRays.add(new Ray3D(origin, direction));
        }

        var tetIndex = testTetIndices.get(0);
        int intersections = 0;
        var boundingSphere = TetrahedralGeometry.getTetrahedronBoundingSphere(tetIndex);

        // Test without early rejection
        long startTime = System.nanoTime();
        for (var ray : missRays) {
            var result = TetrahedralGeometry.rayIntersectsTetrahedron(ray, tetIndex);
            if (result.intersects) {
                intersections++;
            }
        }
        long withoutRejectionTime = System.nanoTime() - startTime;

        // Test with early rejection
        startTime = System.nanoTime();
        int earlyRejections = 0;
        intersections = 0;
        for (Ray3D ray : missRays) {
            if (!TetrahedralGeometry.rayIntersectsSphere(ray, boundingSphere)) {
                earlyRejections++;
                continue;
            }
            var result = TetrahedralGeometry.rayIntersectsTetrahedron(ray, tetIndex);
            if (result.intersects) {
                intersections++;
            }
        }
        long withRejectionTime = System.nanoTime() - startTime;

        System.out.printf("Without early rejection: %.2f ms\n", withoutRejectionTime / 1_000_000.0);
        System.out.printf("With early rejection: %.2f ms\n", withRejectionTime / 1_000_000.0);
        System.out.printf("Speedup: %.2fx\n", (double) withoutRejectionTime / withRejectionTime);
        System.out.printf("Early rejections: %d (%.1f%%)\n", earlyRejections,
                          (earlyRejections * 100.0) / missRays.size());
    }

    private List<Ray3D> generateTestRays(int count) {
        List<Ray3D> rays = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            Point3f origin = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000,
                                         random.nextFloat() * 1000);

            Vector3f direction = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1,
                                              random.nextFloat() * 2 - 1);
            direction.normalize();

            rays.add(new Ray3D(origin, direction));
        }

        return rays;
    }

    private List<TetreeKey> generateTestTetIndices(int count) {
        var indices = new ArrayList<TetreeKey>(count);

        // Generate tetrahedra at various levels
        for (int i = 0; i < count; i++) {
            int x = random.nextInt(1000);
            int y = random.nextInt(1000);
            int z = random.nextInt(1000);
            byte level = (byte) (10 + random.nextInt(5)); // Levels 10-14
            byte type = (byte) random.nextInt(6); // Types 0-5

            Tet tet = new Tet(x, y, z, level, type);
            indices.add((TetreeKey) tet.tmIndex());
        }

        return indices;
    }

    private void testBatchOperations() {
        // Test batch processing of multiple rays against same tetrahedron
        var tetIndex = testTetIndices.get(0);
        var rayBatch = testRays.toArray(new Ray3D[0]);

        // Standard approach - one by one
        long startTime = System.nanoTime();
        int intersectionCount = 0;
        for (Ray3D ray : rayBatch) {
            var result = TetrahedralGeometry.rayIntersectsTetrahedron(ray, tetIndex);
            if (result.intersects) {
                intersectionCount++;
            }
        }
        long standardTime = System.nanoTime() - startTime;

        // Batch approach
        startTime = System.nanoTime();
        var batchResults = TetrahedralGeometry.batchRayIntersectsTetrahedron(rayBatch, tetIndex);
        int batchIntersectionCount = 0;
        for (var result : batchResults) {
            if (result.intersects) {
                batchIntersectionCount++;
            }
        }
        long batchTime = System.nanoTime() - startTime;

        System.out.printf("Standard approach: %.2f ms\n", standardTime / 1_000_000.0);
        System.out.printf("Batch approach: %.2f ms\n", batchTime / 1_000_000.0);
        System.out.printf("Speedup: %.2fx\n", (double) standardTime / batchTime);
        System.out.printf("Intersections: %d (both methods should match)\n", intersectionCount);
    }

    private void testCacheEffectiveness() {
        // Test cache hit rate by using same tetrahedra repeatedly
        TetreeKey[] frequentTets = new TetreeKey[10];
        for (int i = 0; i < 10; i++) {
            frequentTets[i] = testTetIndices.get(i);
        }

        // First pass - cache miss
        long startTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            var ray = testRays.get(i % testRays.size());
            var tetIndex = frequentTets[i % frequentTets.length];
            TetrahedralGeometry.rayIntersectsTetrahedronCached(ray, tetIndex);
        }
        long firstPassTime = System.nanoTime() - startTime;

        // Second pass - cache hit
        startTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            var ray = testRays.get(i % testRays.size());
            var tetIndex = frequentTets[i % frequentTets.length];
            TetrahedralGeometry.rayIntersectsTetrahedronCached(ray, tetIndex);
        }
        long secondPassTime = System.nanoTime() - startTime;

        System.out.printf("First pass (cache cold): %.2f ms\n", firstPassTime / 1_000_000.0);
        System.out.printf("Second pass (cache warm): %.2f ms\n", secondPassTime / 1_000_000.0);
        System.out.printf("Cache speedup: %.2fx\n", (double) firstPassTime / secondPassTime);
    }

    private void testEnhancedCachedImplementation() {
        // Warmup to populate cache
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var ray = testRays.get(i % testRays.size());
            var tetIndex = testTetIndices.get(i % testTetIndices.size());
            TetrahedralGeometry.rayIntersectsTetrahedronCached(ray, tetIndex);
        }

        // Actual test
        long startTime = System.nanoTime();
        int intersectionCount = 0;

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var ray = testRays.get(i % testRays.size());
            var tetIndex = testTetIndices.get(i % testTetIndices.size());
            var result = TetrahedralGeometry.rayIntersectsTetrahedronCached(ray, tetIndex);
            if (result.intersects) {
                intersectionCount++;
            }
        }

        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        double avgTimeNs = (double) totalTime / TEST_ITERATIONS;

        System.out.printf("Total time: %.2f ms\n", totalTime / 1_000_000.0);
        System.out.printf("Average time per test: %.2f ns\n", avgTimeNs);
        System.out.printf("Intersections found: %d (%.1f%%)\n", intersectionCount,
                          (intersectionCount * 100.0) / TEST_ITERATIONS);
    }

    private void testEnhancedFastImplementation() {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var ray = testRays.get(i % testRays.size());
            var tetIndex = testTetIndices.get(i % testTetIndices.size());
            TetrahedralGeometry.rayIntersectsTetrahedronFast(ray, tetIndex);
        }

        // Actual test
        long startTime = System.nanoTime();
        int intersectionCount = 0;

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            Ray3D ray = testRays.get(i % testRays.size());
            var tetIndex = testTetIndices.get(i % testTetIndices.size());
            if (TetrahedralGeometry.rayIntersectsTetrahedronFast(ray, tetIndex)) {
                intersectionCount++;
            }
        }

        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        double avgTimeNs = (double) totalTime / TEST_ITERATIONS;

        System.out.printf("Total time: %.2f ms\n", totalTime / 1_000_000.0);
        System.out.printf("Average time per test: %.2f ns\n", avgTimeNs);
        System.out.printf("Intersections found: %d (%.1f%%)\n", intersectionCount,
                          (intersectionCount * 100.0) / TEST_ITERATIONS);
    }

    private void testStandardImplementation() {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var ray = testRays.get(i % testRays.size());
            var tetIndex = testTetIndices.get(i % testTetIndices.size());
            TetrahedralGeometry.rayIntersectsTetrahedron(ray, tetIndex);
        }

        // Actual test
        long startTime = System.nanoTime();
        int intersectionCount = 0;

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var ray = testRays.get(i % testRays.size());
            var tetIndex = testTetIndices.get(i % testTetIndices.size());
            var result = TetrahedralGeometry.rayIntersectsTetrahedron(ray, tetIndex);
            if (result.intersects) {
                intersectionCount++;
            }
        }

        long endTime = System.nanoTime();
        long totalTime = endTime - startTime;
        double avgTimeNs = (double) totalTime / TEST_ITERATIONS;

        System.out.printf("Total time: %.2f ms\n", totalTime / 1_000_000.0);
        System.out.printf("Average time per test: %.2f ns\n", avgTimeNs);
        System.out.printf("Intersections found: %d (%.1f%%)\n", intersectionCount,
                          (intersectionCount * 100.0) / TEST_ITERATIONS);
    }
}
