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

import com.hellblazer.luciferase.lucien.NodeEstimator;
import com.hellblazer.luciferase.lucien.SpatialNodePool;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.OctreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for memory optimization features including: - Node pre-allocation effectiveness - Memory pool performance
 * - Node estimation accuracy - Memory fragmentation reduction
 *
 * Run with: RUN_SPATIAL_INDEX_PERF_TESTS=true mvn test
 *
 * @author hal.hildebrand
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
public class MemoryOptimizationBenchmark {

    private static final int                         WARMUP_ROUNDS      = 3;
    private static final int                         MEASUREMENT_ROUNDS = 10;
    private static final byte                        DEFAULT_LEVEL      = 10;
    private static final MemoryMXBean                memoryBean         = ManagementFactory.getMemoryMXBean();
    private final        List<MemoryBenchmarkResult> results            = new ArrayList<>();

    @Test
    void benchmarkNodeEstimationAccuracy() {
        System.out.println("\n=== Node Estimation Accuracy ===\n");

        int[] testSizes = { 10_000, 50_000, 100_000 };

        for (int size : testSizes) {
            System.out.println("\n--- " + size + " entities ---");

            // Test different distributions
            testEstimationAccuracy(size, NodeEstimator.SpatialDistribution.UNIFORM, "Uniform");
            testEstimationAccuracy(size, NodeEstimator.SpatialDistribution.CLUSTERED_HIGH, "Clustered");
            testEstimationAccuracy(size, NodeEstimator.SpatialDistribution.SURFACE_THIN, "Surface");
        }
    }

    @Test
    void benchmarkNodePooling() {
        System.out.println("\n=== Node Pooling Benchmarks ===\n");

        int[] poolSizes = { 100, 1000, 10_000 };
        int testSize = 100_000;

        for (int poolSize : poolSizes) {
            System.out.printf("\n--- Testing with pool size %,d ---\n", poolSize);

            List<Point3f> positions = generateUniformTestData(testSize);
            List<String> contents = generateContents(testSize);

            // Benchmark without pooling
            benchmarkWithoutPooling(positions, contents);

            // Benchmark with pooling
            benchmarkWithPooling(poolSize, positions, contents);

            // Benchmark pool efficiency under churn
            benchmarkPoolChurn(poolSize);
        }
    }

    @Test
    void benchmarkNodePreAllocation() {
        System.out.println("\n=== Node Pre-allocation Benchmarks ===\n");

        int[] testSizes = { 10_000, 50_000, 100_000 };
        NodeEstimator.SpatialDistribution[] distributions = { NodeEstimator.SpatialDistribution.UNIFORM,
                                                              NodeEstimator.SpatialDistribution.CLUSTERED_HIGH,
                                                              NodeEstimator.SpatialDistribution.SURFACE_THIN };

        for (int size : testSizes) {
            System.out.printf("\n--- Testing with %,d entities ---\n", size);

            for (NodeEstimator.SpatialDistribution dist : distributions) {
                List<Point3f> positions = generateTestData(size, dist);
                List<String> contents = generateContents(size);

                // Benchmark without pre-allocation
                benchmarkWithoutPreAllocation("Octree", dist, positions, contents);

                // Benchmark with pre-allocation
                benchmarkWithPreAllocation("Octree", dist, positions, contents);

                // Benchmark with adaptive pre-allocation
                benchmarkWithAdaptivePreAllocation("Octree", dist, positions, contents);
            }
        }

        printMemorySummary();
    }

    @BeforeEach
    void setUp() {
        results.clear();
    }

    private void benchmarkPoolChurn(int poolSize) {
        System.out.println("\nPool Churn Test (size=" + poolSize + "):");

        SpatialNodePool<OctreeNode<LongEntityID>> pool = new SpatialNodePool<>(() -> new OctreeNode<>());
        pool.preAllocate(poolSize);

        int operations = 100_000;
        List<OctreeNode<LongEntityID>> acquired = new ArrayList<>();

        var start = System.nanoTime();

        // Simulate high churn scenario
        for (int i = 0; i < operations; i++) {
            if (i % 3 == 0 && !acquired.isEmpty()) {
                // Release some nodes
                int releaseCount = Math.min(10, acquired.size());
                for (int j = 0; j < releaseCount; j++) {
                    pool.release(acquired.remove(acquired.size() - 1));
                }
            } else {
                // Acquire nodes
                OctreeNode<LongEntityID> node = pool.acquire();
                if (node != null) {
                    acquired.add(node);
                }
            }
        }

        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        System.out.printf("  Completed %,d operations in %,d ms (%.0f ops/sec)\n", operations, duration,
                          operations * 1000.0 / duration);
        System.out.printf("  Pool efficiency: %.2f%% hit rate\n", pool.getStats().getHitRate() * 100);
    }

    private void benchmarkWithAdaptivePreAllocation(String indexType, NodeEstimator.SpatialDistribution dist,
                                                    List<Point3f> positions, List<String> contents) {
        var testName = String.format("%s With Adaptive Pre-allocation - %s", indexType, dist.getType());

        var totalDuration = 0L;
        var totalHeap = 0L;
        var totalAllocations = 0L;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            memoryBean.gc();

            var allocBefore = getAllocationCount();
            var heapBefore = memoryBean.getHeapMemoryUsage();

            var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());

            // Use sample of positions for adaptive pre-allocation
            List<Point3f> sample = positions.subList(0, Math.min(1000, positions.size()));
            octree.preAllocateAdaptive(sample, positions.size(), DEFAULT_LEVEL);

            var start = System.nanoTime();
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            MemoryUsage heapAfter = memoryBean.getHeapMemoryUsage();
            long allocAfter = getAllocationCount();

            totalDuration += duration;
            totalHeap += (heapAfter.getUsed() - heapBefore.getUsed());
            totalAllocations += (allocAfter - allocBefore);
        }

        var avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        var avgHeap = totalHeap / MEASUREMENT_ROUNDS;
        var avgAllocations = totalAllocations / MEASUREMENT_ROUNDS;

        var result = new MemoryBenchmarkResult(testName, avgDuration, positions.size(), avgHeap, avgAllocations);
        results.add(result);
        System.out.println(result);
    }

    private void benchmarkWithPooling(int poolSize, List<Point3f> positions, List<String> contents) {
        var testName = String.format("Octree With Node Pooling (size=%d)", poolSize);

        System.gc();
        memoryBean.gc();

        // Create and pre-populate pool
        SpatialNodePool<OctreeNode<LongEntityID>> pool = new SpatialNodePool<>(() -> new OctreeNode<>());
        pool.preAllocate(poolSize);

        long allocBefore = getAllocationCount();
        MemoryUsage heapBefore = memoryBean.getHeapMemoryUsage();

        // Create octree with pool
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        // Note: Direct pool configuration not available in current implementation
        // Would need to extend AbstractSpatialIndex to expose nodePool configuration

        var start = System.nanoTime();
        for (int i = 0; i < positions.size(); i++) {
            octree.insert(positions.get(i), DEFAULT_LEVEL, contents.get(i));
        }
        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        var heapAfter = memoryBean.getHeapMemoryUsage();
        var allocAfter = getAllocationCount();

        var result = new MemoryBenchmarkResult(testName, duration, positions.size(),
                                               heapAfter.getUsed() - heapBefore.getUsed(), allocAfter - allocBefore);
        System.out.println(result);

        // Print pool statistics
        System.out.printf("  Pool efficiency: %.2f%% (hit rate), %d allocations saved\n",
                          pool.getStats().getHitRate() * 100, pool.getStats().getAllocationsSaved());
    }

    private void benchmarkWithPreAllocation(String indexType, NodeEstimator.SpatialDistribution dist,
                                            List<Point3f> positions, List<String> contents) {
        var testName = String.format("%s With Pre-allocation - %s", indexType, dist.getType());

        var totalDuration = 0L;
        var totalHeap = 0L;
        var totalAllocations = 0L;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            memoryBean.gc();

            var allocBefore = getAllocationCount();
            var heapBefore = memoryBean.getHeapMemoryUsage();

            var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());

            // Pre-allocate nodes based on estimation
            octree.preAllocateNodes(positions.size(), dist);

            var start = System.nanoTime();
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            MemoryUsage heapAfter = memoryBean.getHeapMemoryUsage();
            long allocAfter = getAllocationCount();

            totalDuration += duration;
            totalHeap += (heapAfter.getUsed() - heapBefore.getUsed());
            totalAllocations += (allocAfter - allocBefore);
        }

        var avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        var avgHeap = totalHeap / MEASUREMENT_ROUNDS;
        var avgAllocations = totalAllocations / MEASUREMENT_ROUNDS;

        var result = new MemoryBenchmarkResult(testName, avgDuration, positions.size(), avgHeap, avgAllocations);
        results.add(result);
        System.out.println(result);
    }

    private void benchmarkWithoutPooling(List<Point3f> positions, List<String> contents) {
        var testName = "Octree Without Node Pooling";

        System.gc();
        memoryBean.gc();

        long allocBefore = getAllocationCount();
        MemoryUsage heapBefore = memoryBean.getHeapMemoryUsage();

        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());

        var start = System.nanoTime();
        for (int i = 0; i < positions.size(); i++) {
            octree.insert(positions.get(i), DEFAULT_LEVEL, contents.get(i));
        }
        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        var heapAfter = memoryBean.getHeapMemoryUsage();
        var allocAfter = getAllocationCount();

        var result = new MemoryBenchmarkResult(testName, duration, positions.size(),
                                               heapAfter.getUsed() - heapBefore.getUsed(), allocAfter - allocBefore);
        System.out.println(result);
    }

    private void benchmarkWithoutPreAllocation(String indexType, NodeEstimator.SpatialDistribution dist,
                                               List<Point3f> positions, List<String> contents) {
        var testName = String.format("%s Without Pre-allocation - %s", indexType, dist.getType());

        var totalDuration = 0L;
        var totalHeap = 0L;
        var totalAllocations = 0L;

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            memoryBean.gc();

            var allocBefore = getAllocationCount();
            var heapBefore = memoryBean.getHeapMemoryUsage();

            var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());

            var start = System.nanoTime();
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            MemoryUsage heapAfter = memoryBean.getHeapMemoryUsage();
            long allocAfter = getAllocationCount();

            totalDuration += duration;
            totalHeap += (heapAfter.getUsed() - heapBefore.getUsed());
            totalAllocations += (allocAfter - allocBefore);
        }

        var avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        var avgHeap = totalHeap / MEASUREMENT_ROUNDS;
        var avgAllocations = totalAllocations / MEASUREMENT_ROUNDS;

        var result = new MemoryBenchmarkResult(testName, avgDuration, positions.size(), avgHeap, avgAllocations);
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

    // Helper methods
    private List<Point3f> generateTestData(int count, NodeEstimator.SpatialDistribution dist) {
        List<Point3f> positions = new ArrayList<>(count);
        Random rand = new Random(42);

        switch (dist.getType()) {
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
                    float y = 100 + rand.nextFloat() * 10; // Thin surface at y=100
                    positions.add(new Point3f(x, y, z));
                }
                break;
        }

        return positions;
    }

    private List<Point3f> generateUniformTestData(int count) {
        return generateTestData(count, NodeEstimator.SpatialDistribution.UNIFORM);
    }

    private long getAllocationCount() {
        // Simplified allocation tracking - in real benchmarks would use JMH
        return Runtime.getRuntime().totalMemory() / 1024;
    }

    private void printMemorySummary() {
        System.out.println("\n=== Memory Optimization Summary ===\n");

        // Group results by test type
        Map<String, List<MemoryBenchmarkResult>> groupedResults = new HashMap<>();
        for (MemoryBenchmarkResult result : results) {
            String key = result.testName.split(" - ")[0];
            groupedResults.computeIfAbsent(key, k -> new ArrayList<>()).add(result);
        }

        // Calculate improvements
        for (Map.Entry<String, List<MemoryBenchmarkResult>> entry : groupedResults.entrySet()) {
            String testType = entry.getKey();
            List<MemoryBenchmarkResult> typeResults = entry.getValue();

            if (testType.contains("Without")) {
                String optimizedType = testType.replace("Without", "With");
                List<MemoryBenchmarkResult> optimizedResults = groupedResults.get(optimizedType);

                if (optimizedResults != null) {
                    for (int i = 0; i < Math.min(typeResults.size(), optimizedResults.size()); i++) {
                        MemoryBenchmarkResult baseline = typeResults.get(i);
                        MemoryBenchmarkResult optimized = optimizedResults.get(i);

                        double memReduction = 100.0 * (1 - (double) optimized.heapUsed / baseline.heapUsed);
                        double allocReduction = 100.0 * (1 - (double) optimized.allocations / baseline.allocations);

                        System.out.printf("%s → %s:\n", testType.replace("Octree ", ""),
                                          optimizedType.replace("Octree ", ""));
                        System.out.printf("  Memory: %.1f%% reduction\n", memReduction);
                        System.out.printf("  Allocations: %.1f%% reduction\n", allocReduction);
                        System.out.println();
                    }
                }
            }
        }
    }

    private void testEstimationAccuracy(int entityCount, NodeEstimator.SpatialDistribution dist, String distName) {
        // Generate test data
        var positions = generateTestData(entityCount, dist);
        var contents = generateContents(entityCount);

        // Get estimation
        var estimatedNodes = NodeEstimator.estimateNodeCount(entityCount, 32, (byte) 20, dist);

        // Build actual tree and count nodes
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        octree.insertBatch(positions, contents, DEFAULT_LEVEL);

        var actualNodes = octree.nodeCount();
        var accuracy = 100.0 * Math.min(estimatedNodes, actualNodes) / Math.max(estimatedNodes, actualNodes);

        System.out.printf("  %-20s: Estimated: %,6d, Actual: %,6d, Accuracy: %.1f%%\n", distName, estimatedNodes,
                          actualNodes, accuracy);
    }

    static class MemoryBenchmarkResult {
        final String testName;
        final long   duration;
        final int    entityCount;
        final long   heapUsed;
        final long   allocations;
        final double allocationsPerEntity;

        MemoryBenchmarkResult(String testName, long duration, int entityCount, long heapUsed, long allocations) {
            this.testName = testName;
            this.duration = duration;
            this.entityCount = entityCount;
            this.heapUsed = heapUsed;
            this.allocations = allocations;
            this.allocationsPerEntity = (double) allocations / entityCount;
        }

        @Override
        public String toString() {
            return String.format(
            "%-50s: %,10d entities, Heap: %,d KB, Allocations: %,d (%.2f per entity), Time: %,d ms", testName,
            entityCount, heapUsed / 1024, allocations, allocationsPerEntity, duration);
        }
    }
}
