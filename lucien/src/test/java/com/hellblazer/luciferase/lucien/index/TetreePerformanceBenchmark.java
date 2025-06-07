package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Octree;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.Tet;
import com.hellblazer.luciferase.lucien.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Comprehensive performance benchmark for Tetree vs Octree operations Identifies specific performance bottlenecks and
 * optimization opportunities
 */
public class TetreePerformanceBenchmark {

    private static final int            SMALL_DATASET  = 50;
    private static final int            MEDIUM_DATASET = 200;
    private static final int            LARGE_DATASET  = 500;
    private              Tetree<String> tetree;
    private              Octree<String> octree;

    @Test
    @DisplayName("Memory allocation and GC pressure analysis")
    void analyzeMemoryUsage() {
        System.out.println("=== Memory Usage Analysis ===\n");

        // Force GC before measurement
        System.gc();
        Thread.yield();

        long beforeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Create large dataset
        populateWithTestData(LARGE_DATASET);

        // Force GC after allocation
        System.gc();
        Thread.yield();

        long afterMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long memoryUsed = afterMemory - beforeMemory;

        System.out.printf("Memory used for %d entries: %.2f MB%n", LARGE_DATASET, memoryUsed / (1024.0 * 1024.0));
        System.out.printf("Memory per entry: %.2f KB%n", memoryUsed / (LARGE_DATASET * 1024.0));

        // Analyze query memory allocation
        analyzeQueryMemoryUsage();
    }

    @Test
    @DisplayName("Benchmark spatial query operations across different dataset sizes")
    void benchmarkSpatialQueries() {
        System.out.println("=== Tetree vs Octree Performance Benchmark ===\n");

        // Test with different dataset sizes to identify scalability patterns
        benchmarkDatasetSize("Small Dataset (50 points)", SMALL_DATASET);
        benchmarkDatasetSize("Medium Dataset (200 points)", MEDIUM_DATASET);
        benchmarkDatasetSize("Large Dataset (500 points)", LARGE_DATASET);
    }

    @Test
    @DisplayName("Identify specific performance bottlenecks")
    void identifyBottlenecks() {
        System.out.println("=== Performance Bottleneck Analysis ===\n");

        populateWithTestData(MEDIUM_DATASET);

        var query = new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f);

        // Analyze different phases of query execution
        analyzeQueryPhases(query);

        // Analyze SFC vs Morton curve differences
        analyzeSFCDifferences();

        // Recommendations
        printOptimizationRecommendations();
    }

    @Test
    @DisplayName("Profile SFC operations for performance bottlenecks")
    void profileSFCOperations() {
        System.out.println("=== SFC Operations Performance Profile ===\n");

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int iterations = 10000;

        // Benchmark Tet index computation
        long tetIndexTime = 0;
        for (int i = 0; i < iterations; i++) {
            int x = random.nextInt(10000);
            int y = random.nextInt(10000);
            int z = random.nextInt(10000);
            byte level = (byte) random.nextInt(5, 15);
            byte type = (byte) random.nextInt(6);

            long start = System.nanoTime();
            var tet = new Tet(x, y, z, level, type);
            long index = tet.index();
            tetIndexTime += System.nanoTime() - start;
        }

        // Benchmark Tet reconstruction from index
        long tetReconstructTime = 0;
        for (int i = 0; i < iterations; i++) {
            long index = random.nextLong(0, 1000000);

            long start = System.nanoTime();
            try {
                var tet = Tet.tetrahedron(index);
                tetReconstructTime += System.nanoTime() - start;
            } catch (Exception e) {
                // Skip invalid indices
            }
        }

        // Benchmark SFC level computation
        long levelComputeTime = 0;
        for (int i = 0; i < iterations; i++) {
            long index = random.nextLong(0, 1000000);

            long start = System.nanoTime();
            byte level = Tet.tetLevelFromIndex(index);
            levelComputeTime += System.nanoTime() - start;
        }

        System.out.printf("Tet Index Computation: %.2f ns/op%n", tetIndexTime / (double) iterations);
        System.out.printf("Tet Reconstruction: %.2f ns/op%n", tetReconstructTime / (double) iterations);
        System.out.printf("SFC Level Computation: %.2f ns/op%n", levelComputeTime / (double) iterations);

        // Compare with Morton curve operations
        benchmarkMortonOperations(iterations);
    }

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new TreeMap<>());
        octree = new Octree<>();
    }

    private void analyzeQueryMemoryUsage() {
        System.out.println("\n--- Query Memory Usage ---");

        var query = new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f);

        // Multiple query executions to analyze allocation patterns
        for (int i = 0; i < 5; i++) {
            System.gc();
            long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            // Execute queries
            var tetreeResults = tetree.boundedBy(query).collect(Collectors.toList());
            var octreeResults = octree.boundedBy(query).collect(Collectors.toList());

            long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            System.out.printf("Query %d: Allocated %.2f KB (Tetree: %d, Octree: %d results)%n", i + 1,
                              (after - before) / 1024.0, tetreeResults.size(), octreeResults.size());
        }
    }

    private void analyzeQueryPhases(Spatial.Cube query) {
        System.out.println("--- Query Phase Analysis ---");

        // This analysis would require instrumenting the actual query methods
        // For now, provide high-level timing analysis

        long start = System.nanoTime();
        var tetreeStream = tetree.boundedBy(query);
        long streamCreateTime = System.nanoTime() - start;

        start = System.nanoTime();
        var results = tetreeStream.collect(Collectors.toList());
        long streamProcessTime = System.nanoTime() - start;

        System.out.printf("Tetree Query Phases:%n");
        System.out.printf("  Stream Creation: %.2f μs%n", streamCreateTime / 1000.0);
        System.out.printf("  Stream Processing: %.2f μs%n", streamProcessTime / 1000.0);
        System.out.printf("  Results: %d%n", results.size());
    }

    private void analyzeSFCDifferences() {
        System.out.println("\n--- SFC vs Morton Curve Analysis ---");

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int samples = 1000;

        // Compare SFC index distribution
        long tetIndexSum = 0;
        for (int i = 0; i < samples; i++) {
            int x = random.nextInt(1000);
            int y = random.nextInt(1000);
            int z = random.nextInt(1000);

            var tet = new Tet(x, y, z, (byte) 10, (byte) 0);
            tetIndexSum += tet.index();
        }

        System.out.printf("Average Tetrahedral SFC index: %d%n", tetIndexSum / samples);
        System.out.printf("SFC index distribution affects query range efficiency%n");
    }

    private void benchmarkBoundedByQuery(String queryType, Spatial.Cube query) {
        // Warm up JIT
        for (int i = 0; i < 10; i++) {
            tetree.boundedBy(query).count();
            octree.boundedBy(query).count();
        }

        // Benchmark Tetree
        long tetreeStart = System.nanoTime();
        var tetreeResults = tetree.boundedBy(query).collect(Collectors.toList());
        long tetreeTime = System.nanoTime() - tetreeStart;

        // Benchmark Octree
        long octreeStart = System.nanoTime();
        var octreeResults = octree.boundedBy(query).collect(Collectors.toList());
        long octreeTime = System.nanoTime() - octreeStart;

        System.out.printf("BoundedBy %s: Tetree %d results in %.2f μs, Octree %d results in %.2f μs (%.2fx)%n",
                          queryType, tetreeResults.size(), tetreeTime / 1000.0, octreeResults.size(),
                          octreeTime / 1000.0, (double) tetreeTime / octreeTime);
    }

    private void benchmarkBoundingQuery(String queryType, Spatial.Cube query) {
        // Warm up JIT
        for (int i = 0; i < 10; i++) {
            tetree.bounding(query).count();
            octree.bounding(query).count();
        }

        // Benchmark Tetree
        long tetreeStart = System.nanoTime();
        var tetreeResults = tetree.bounding(query).collect(Collectors.toList());
        long tetreeTime = System.nanoTime() - tetreeStart;

        // Benchmark Octree
        long octreeStart = System.nanoTime();
        var octreeResults = octree.bounding(query).collect(Collectors.toList());
        long octreeTime = System.nanoTime() - octreeStart;

        System.out.printf("Bounding %s: Tetree %d results in %.2f μs, Octree %d results in %.2f μs (%.2fx)%n",
                          queryType, tetreeResults.size(), tetreeTime / 1000.0, octreeResults.size(),
                          octreeTime / 1000.0, (double) tetreeTime / octreeTime);
    }

    private void benchmarkDatasetSize(String testName, int datasetSize) {
        System.out.println("--- " + testName + " ---");

        // Reset data structures
        tetree = new Tetree<>(new TreeMap<>());
        octree = new Octree<>();

        // Populate with test data
        populateWithTestData(datasetSize);

        // Define test query volumes of different sizes
        var smallQuery = new Spatial.Cube(2000.0f, 2000.0f, 2000.0f, 500.0f);
        var mediumQuery = new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f);
        var largeQuery = new Spatial.Cube(0.0f, 0.0f, 0.0f, 5000.0f);

        // Benchmark different query types
        benchmarkBoundedByQuery("Small Query", smallQuery);
        benchmarkBoundedByQuery("Medium Query", mediumQuery);
        benchmarkBoundedByQuery("Large Query", largeQuery);

        benchmarkBoundingQuery("Small Query", smallQuery);
        benchmarkBoundingQuery("Medium Query", mediumQuery);
        benchmarkBoundingQuery("Large Query", largeQuery);

        benchmarkEnclosingQuery("Point Enclosure");

        System.out.println();
    }

    private void benchmarkEnclosingQuery(String queryType) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Point3f queryPoint = new Point3f(random.nextFloat() * 5000, random.nextFloat() * 5000,
                                         random.nextFloat() * 5000);
        byte level = 10;

        // Convert Point3f to Tuple3i for enclosing methods
        var queryTuple = new javax.vecmath.Point3i((int) queryPoint.x, (int) queryPoint.y, (int) queryPoint.z);

        // Warm up JIT
        for (int i = 0; i < 10; i++) {
            tetree.enclosing(queryTuple, level);
            octree.enclosing(queryTuple, level);
        }

        // Benchmark Tetree
        long tetreeStart = System.nanoTime();
        var tetreeResult = tetree.enclosing(queryTuple, level);
        long tetreeTime = System.nanoTime() - tetreeStart;

        // Benchmark Octree
        long octreeStart = System.nanoTime();
        var octreeResult = octree.enclosing(queryTuple, level);
        long octreeTime = System.nanoTime() - octreeStart;

        System.out.printf("Enclosing %s: Tetree %.2f μs, Octree %.2f μs (%.2fx)%n", queryType, tetreeTime / 1000.0,
                          octreeTime / 1000.0, (double) tetreeTime / octreeTime);
    }

    private void benchmarkMortonOperations(int iterations) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // This would require accessing MortonCurve operations from the geometry package
        System.out.println("\n--- Morton Curve Comparison ---");
        System.out.println("Note: Morton curve benchmarks would require accessing geometry.MortonCurve");
    }

    private int getOctreeContentCount() {
        try {
            var field = Octree.class.getDeclaredField("map");
            field.setAccessible(true);
            var map = (TreeMap<?, ?>) field.get(octree);
            return map.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private int getTetreeContentCount() {
        try {
            var field = Tetree.class.getDeclaredField("contents");
            field.setAccessible(true);
            var contents = (TreeMap<?, ?>) field.get(tetree);
            return contents.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private void populateWithTestData(int count) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        long tetreeInsertTime = 0;
        long octreeInsertTime = 0;

        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * 10000;
            float y = random.nextFloat() * 10000;
            float z = random.nextFloat() * 10000;
            byte level = (byte) random.nextInt(5, 15);

            // Benchmark insertion performance
            long start = System.nanoTime();
            tetree.insert(new Point3f(x, y, z), level, "tetree-data-" + i);
            tetreeInsertTime += System.nanoTime() - start;

            start = System.nanoTime();
            octree.insert(new Point3f(x, y, z), level, "octree-data-" + i);
            octreeInsertTime += System.nanoTime() - start;
        }

        System.out.printf("Insertion Performance: Tetree %.2f μs/op, Octree %.2f μs/op%n",
                          tetreeInsertTime / (count * 1000.0), octreeInsertTime / (count * 1000.0));
        System.out.printf("Contents: Tetree %d, Octree %d%n", getTetreeContentCount(), getOctreeContentCount());
    }

    private void printOptimizationRecommendations() {
        System.out.println("\n--- Optimization Recommendations ---");
        System.out.println("1. SFC Range Computation: Optimize range merging and splitting");
        System.out.println("2. Object Allocation: Reduce temporary object creation in queries");
        System.out.println("3. Stream Processing: Consider parallel streams for large datasets");
        System.out.println("4. Index Caching: Cache frequently accessed SFC computations");
        System.out.println("5. Geometric Tests: Optimize tetrahedral containment/intersection tests");
    }
}
