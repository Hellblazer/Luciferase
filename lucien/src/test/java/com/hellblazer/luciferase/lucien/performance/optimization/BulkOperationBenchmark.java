/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.performance.optimization;

import com.hellblazer.luciferase.lucien.BulkOperationConfig;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive benchmarks for bulk operation optimizations. Compares performance before and after optimization.
 *
 * Tests measure: - Insertion time reduction - Memory allocation efficiency - Throughput improvements - Scaling
 * behavior
 *
 * Run with: RUN_SPATIAL_INDEX_PERF_TESTS=true mvn test
 *
 * @author hal.hildebrand
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
public class BulkOperationBenchmark {

    private static final int                   WARMUP_ROUNDS      = 3;
    private static final int                   MEASUREMENT_ROUNDS = 10;
    private static final byte                  DEFAULT_LEVEL      = 10;
    private final        List<BenchmarkResult> results            = new ArrayList<>();

    @Test
    void benchmarkBulkOperationsOctree() {
        System.out.println("\n=== Octree Bulk Operation Benchmarks ===\n");

        int[] testSizes = { 1000, 10_000, 50_000 };

        for (int size : testSizes) {
            System.out.printf("\n--- Testing with %,d entities ---\n", size);

            for (Distribution dist : Distribution.values()) {
                List<Point3f> positions = generateTestData(size, dist);
                List<String> contents = generateContents(size);

                // Benchmark iterative insertion (baseline)
                benchmarkIterativeInsertion("Octree", dist, positions, contents);

                // Benchmark basic bulk insertion
                benchmarkBasicBulkInsertion("Octree", dist, positions, contents);

                // Benchmark optimized bulk insertion
                benchmarkOptimizedBulkInsertion("Octree", dist, positions, contents);

                // Benchmark stack-based bulk insertion
                benchmarkStackBasedBulkInsertion("Octree", dist, positions, contents);

                System.out.println(); // Spacing between distributions
            }
        }

        printSummary("Octree");
    }

    @Test
    void benchmarkBulkOperationsTetree() {
        System.out.println("\n=== Tetree Bulk Operation Benchmarks ===\n");

        int[] testSizes = { 1000, 10_000 };

        for (int size : testSizes) {
            System.out.printf("\n--- Testing with %,d entities ---\n", size);

            for (Distribution dist : Distribution.values()) {
                List<Point3f> positions = generateTestData(size, dist);
                List<String> contents = generateContents(size);

                // Benchmark iterative insertion (baseline)
                benchmarkIterativeInsertionTetree("Tetree", dist, positions, contents);

                // Benchmark basic bulk insertion
                benchmarkBasicBulkInsertionTetree("Tetree", dist, positions, contents);

                // Benchmark optimized bulk insertion
                benchmarkOptimizedBulkInsertionTetree("Tetree", dist, positions, contents);

                // Benchmark stack-based bulk insertion
                benchmarkStackBasedBulkInsertionTetree("Tetree", dist, positions, contents);

                System.out.println(); // Spacing between distributions
            }
        }

        printSummary("Tetree");
    }

    @BeforeEach
    void setUp() {
        results.clear();
    }

    private void benchmarkBasicBulkInsertion(String indexType, Distribution dist, List<Point3f> positions,
                                             List<String> contents) {
        String testName = String.format("%s Basic Bulk - %s", indexType, dist.displayName);

        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
        }

        // Measurement
        long totalDuration = 0;
        long totalMemory = 0;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());

            long start = System.nanoTime();
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            totalDuration += duration;
            totalMemory += (memAfter - memBefore);
        }

        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        long avgMemory = totalMemory / MEASUREMENT_ROUNDS;

        BenchmarkResult result = new BenchmarkResult(testName, avgDuration, positions.size(), avgMemory);
        results.add(result);
        System.out.println(result);
    }

    private void benchmarkBasicBulkInsertionTetree(String indexType, Distribution dist, List<Point3f> positions,
                                                   List<String> contents) {
        String testName = String.format("%s Basic Bulk - %s", indexType, dist.displayName);

        long totalDuration = 0;
        long totalMemory = 0;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());

            long start = System.nanoTime();
            tetree.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            totalDuration += duration;
            totalMemory += (memAfter - memBefore);
        }

        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        long avgMemory = totalMemory / MEASUREMENT_ROUNDS;

        BenchmarkResult result = new BenchmarkResult(testName, avgDuration, positions.size(), avgMemory);
        results.add(result);
        System.out.println(result);
    }

    private void benchmarkIterativeInsertion(String indexType, Distribution dist, List<Point3f> positions,
                                             List<String> contents) {
        String testName = String.format("%s Iterative - %s", indexType, dist.displayName);

        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
            for (int j = 0; j < positions.size(); j++) {
                octree.insert(positions.get(j), DEFAULT_LEVEL, contents.get(j));
            }
        }

        // Measurement
        long totalDuration = 0;
        long totalMemory = 0;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());

            long start = System.nanoTime();
            for (int j = 0; j < positions.size(); j++) {
                octree.insert(positions.get(j), DEFAULT_LEVEL, contents.get(j));
            }
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            totalDuration += duration;
            totalMemory += (memAfter - memBefore);
        }

        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        long avgMemory = totalMemory / MEASUREMENT_ROUNDS;

        BenchmarkResult result = new BenchmarkResult(testName, avgDuration, positions.size(), avgMemory);
        results.add(result);
        System.out.println(result);
    }

    // Tetree-specific benchmark methods
    private void benchmarkIterativeInsertionTetree(String indexType, Distribution dist, List<Point3f> positions,
                                                   List<String> contents) {
        String testName = String.format("%s Iterative - %s", indexType, dist.displayName);

        // Similar to Octree but using Tetree
        long totalDuration = 0;
        long totalMemory = 0;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());

            long start = System.nanoTime();
            for (int j = 0; j < positions.size(); j++) {
                tetree.insert(positions.get(j), DEFAULT_LEVEL, contents.get(j));
            }
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            totalDuration += duration;
            totalMemory += (memAfter - memBefore);
        }

        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        long avgMemory = totalMemory / MEASUREMENT_ROUNDS;

        BenchmarkResult result = new BenchmarkResult(testName, avgDuration, positions.size(), avgMemory);
        results.add(result);
        System.out.println(result);
    }

    private void benchmarkOptimizedBulkInsertion(String indexType, Distribution dist, List<Point3f> positions,
                                                 List<String> contents) {
        String testName = String.format("%s Optimized Bulk - %s", indexType, dist.displayName);

        BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                        .withDeferredSubdivision(true)
                                                        .withPreSortByMorton(true)
                                                        .withBatchSize(1000);

        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
            octree.configureBulkOperations(config);
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
        }

        // Measurement
        long totalDuration = 0;
        long totalMemory = 0;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
            octree.configureBulkOperations(config);

            long start = System.nanoTime();
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            totalDuration += duration;
            totalMemory += (memAfter - memBefore);
        }

        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        long avgMemory = totalMemory / MEASUREMENT_ROUNDS;

        BenchmarkResult result = new BenchmarkResult(testName, avgDuration, positions.size(), avgMemory);
        results.add(result);
        System.out.println(result);
    }

    private void benchmarkOptimizedBulkInsertionTetree(String indexType, Distribution dist, List<Point3f> positions,
                                                       List<String> contents) {
        String testName = String.format("%s Optimized Bulk - %s", indexType, dist.displayName);

        BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                        .withDeferredSubdivision(true)
                                                        .withPreSortByMorton(true)
                                                        .withBatchSize(1000);

        long totalDuration = 0;
        long totalMemory = 0;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
            tetree.configureBulkOperations(config);

            long start = System.nanoTime();
            tetree.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            totalDuration += duration;
            totalMemory += (memAfter - memBefore);
        }

        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        long avgMemory = totalMemory / MEASUREMENT_ROUNDS;

        BenchmarkResult result = new BenchmarkResult(testName, avgDuration, positions.size(), avgMemory);
        results.add(result);
        System.out.println(result);
    }

    private void benchmarkStackBasedBulkInsertion(String indexType, Distribution dist, List<Point3f> positions,
                                                  List<String> contents) {
        String testName = String.format("%s Stack-Based Bulk - %s", indexType, dist.displayName);

        BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                        .withStackBasedBuilder(true)
                                                        .withStackBuilderThreshold(5000);

        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
            octree.configureBulkOperations(config);
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
        }

        // Measurement
        long totalDuration = 0;
        long totalMemory = 0;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
            octree.configureBulkOperations(config);

            long start = System.nanoTime();
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            totalDuration += duration;
            totalMemory += (memAfter - memBefore);
        }

        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        long avgMemory = totalMemory / MEASUREMENT_ROUNDS;

        BenchmarkResult result = new BenchmarkResult(testName, avgDuration, positions.size(), avgMemory);
        results.add(result);
        System.out.println(result);
    }

    private void benchmarkStackBasedBulkInsertionTetree(String indexType, Distribution dist, List<Point3f> positions,
                                                        List<String> contents) {
        String testName = String.format("%s Stack-Based Bulk - %s", indexType, dist.displayName);

        BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                        .withStackBasedBuilder(true)
                                                        .withStackBuilderThreshold(5000);

        long totalDuration = 0;
        long totalMemory = 0;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
            tetree.configureBulkOperations(config);

            long start = System.nanoTime();
            tetree.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            totalDuration += duration;
            totalMemory += (memAfter - memBefore);
        }

        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        long avgMemory = totalMemory / MEASUREMENT_ROUNDS;

        BenchmarkResult result = new BenchmarkResult(testName, avgDuration, positions.size(), avgMemory);
        results.add(result);
        System.out.println(result);
    }

    private List<String> generateContents(int count) {
        List<String> contents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            contents.add("Entity_" + i);
        }
        return contents;
    }

    // Test data generation methods
    private List<Point3f> generateTestData(int count, Distribution dist) {
        List<Point3f> positions = new ArrayList<>(count);
        Random rand = new Random(42); // Consistent seed for reproducibility

        switch (dist) {
            case UNIFORM:
                for (int i = 0; i < count; i++) {
                    positions.add(
                    new Point3f(rand.nextFloat() * 1000, rand.nextFloat() * 1000, rand.nextFloat() * 1000));
                }
                break;

            case CLUSTERED:
                int numClusters = Math.max(10, count / 1000);
                // At level 10, cell size is 2048. We need spread larger than this
                // to ensure entities distribute across multiple cells
                float spread = 3000.0f; // Larger than cell size at level 10

                // Keep clusters well separated
                float clusterSpacing = 5000.0f;

                for (int i = 0; i < count; i++) {
                    int cluster = i % numClusters;
                    // Limit the spatial extent to avoid memory issues
                    float baseX = (cluster % 5) * clusterSpacing;
                    float baseY = ((cluster / 5) % 4) * clusterSpacing;
                    float baseZ = ((cluster / 20) % 3) * clusterSpacing;

                    positions.add(new Point3f(baseX + rand.nextFloat() * spread, baseY + rand.nextFloat() * spread,
                                              baseZ + rand.nextFloat() * spread));
                }
                break;

            case SURFACE_ALIGNED:
                for (int i = 0; i < count; i++) {
                    float x = rand.nextFloat() * 1000;
                    float z = rand.nextFloat() * 1000;
                    float y = 100 + (float) Math.sin(x / 100) * 50 + (float) Math.cos(z / 100) * 50;
                    positions.add(new Point3f(x, y, z));
                }
                break;

            case DIAGONAL:
                for (int i = 0; i < count; i++) {
                    float t = (float) i / count;
                    positions.add(new Point3f(t * 1000 + rand.nextFloat() * 10, t * 1000 + rand.nextFloat() * 10,
                                              t * 1000 + rand.nextFloat() * 10));
                }
                break;
        }

        return positions;
    }

    private void printSummary(String indexType) {
        System.out.println("\n=== " + indexType + " Performance Summary ===\n");

        // Group results by entity count
        Map<Integer, List<BenchmarkResult>> groupedResults = new TreeMap<>();
        for (BenchmarkResult result : results) {
            groupedResults.computeIfAbsent(result.entityCount, k -> new ArrayList<>()).add(result);
        }

        // Print improvements for each entity count
        for (Map.Entry<Integer, List<BenchmarkResult>> entry : groupedResults.entrySet()) {
            int entityCount = entry.getKey();
            List<BenchmarkResult> sizeResults = entry.getValue();

            System.out.printf("\n--- %,d Entities ---\n", entityCount);

            // Find baseline (iterative) results
            Map<String, BenchmarkResult> baselineByDist = new HashMap<>();
            for (BenchmarkResult result : sizeResults) {
                if (result.testName.contains("Iterative")) {
                    for (Distribution dist : Distribution.values()) {
                        if (result.testName.contains(dist.displayName)) {
                            baselineByDist.put(dist.displayName, result);
                            break;
                        }
                    }
                }
            }

            // Calculate improvements
            for (BenchmarkResult result : sizeResults) {
                if (!result.testName.contains("Iterative")) {
                    for (Distribution dist : Distribution.values()) {
                        if (result.testName.contains(dist.displayName)) {
                            BenchmarkResult baseline = baselineByDist.get(dist.displayName);
                            if (baseline != null) {
                                double improvement = (double) baseline.duration / result.duration;
                                double memReduction = 100.0 * (1 - (double) result.memoryUsed / baseline.memoryUsed);
                                System.out.printf("%-50s: %.2fx faster, %.1f%% memory reduction\n", result.testName,
                                                  improvement, memReduction);
                            }
                            break;
                        }
                    }
                }
            }
        }

        // Clear results for next index type
        results.clear();
    }

    // Test data distributions
    enum Distribution {
        UNIFORM("Uniform Random"), CLUSTERED("Clustered"), SURFACE_ALIGNED("Surface Aligned"), DIAGONAL(
        "Diagonal Line");

        final String displayName;

        Distribution(String displayName) {
            this.displayName = displayName;
        }
    }

    static class BenchmarkResult {
        final String testName;
        final long   duration;
        final int    entityCount;
        final long   memoryUsed;
        final double throughput;

        BenchmarkResult(String testName, long duration, int entityCount, long memoryUsed) {
            this.testName = testName;
            this.duration = duration;
            this.entityCount = entityCount;
            this.memoryUsed = memoryUsed;
            this.throughput = entityCount * 1000.0 / duration; // entities per second
        }

        @Override
        public String toString() {
            return String.format("%-40s: %,10d entities in %,8d ms (%,10.0f entities/sec), Memory: %,d KB", testName,
                                 entityCount, duration, throughput, memoryUsed / 1024);
        }
    }
}
