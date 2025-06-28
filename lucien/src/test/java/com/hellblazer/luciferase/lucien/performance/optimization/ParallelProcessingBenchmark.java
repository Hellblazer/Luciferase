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
import com.hellblazer.luciferase.lucien.BulkOperationProcessor;
import com.hellblazer.luciferase.lucien.ParallelBulkOperations;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.OctreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/**
 * Benchmarks for parallel processing optimizations including:
 * - Parallel bulk insertion performance
 * - Lock contention analysis
 * - Work-stealing vs fixed thread pool comparison
 * - Scalability across different core counts
 * - Spatial partitioning effectiveness
 * 
 * Run with: RUN_SPATIAL_INDEX_PERF_TESTS=true mvn test
 * 
 * UPDATE: ParallelBulkOperations has been optimized to use true batch operations
 * with coarse-grained locking. Test sizes have been restored to their original
 * values to demonstrate the improved performance and scalability.
 * 
 * @author hal.hildebrand
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
public class ParallelProcessingBenchmark {
    
    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASUREMENT_ROUNDS = 3;
    private static final byte DEFAULT_LEVEL = 10;
    private static final int AVAILABLE_CORES = Runtime.getRuntime().availableProcessors();
    
    static class ParallelBenchmarkResult {
        final String testName;
        final int threadCount;
        final long duration;
        final int entityCount;
        final double throughput;
        final double speedup;
        final double efficiency;
        final long lockContentionTime;
        
        ParallelBenchmarkResult(String testName, int threadCount, long duration, 
                               int entityCount, long baselineDuration, long lockContentionTime) {
            this.testName = testName;
            this.threadCount = threadCount;
            this.duration = duration;
            this.entityCount = entityCount;
            this.throughput = entityCount * 1000.0 / duration;
            this.speedup = baselineDuration > 0 ? (double) baselineDuration / duration : 1.0;
            this.efficiency = speedup / threadCount;
            this.lockContentionTime = lockContentionTime;
        }
        
        @Override
        public String toString() {
            return String.format("%-40s: %2d threads, %,10d entities in %,6d ms " +
                               "(%.2fx speedup, %.1f%% efficiency, %,d ms lock contention)",
                testName, threadCount, entityCount, duration, speedup, 
                efficiency * 100, lockContentionTime);
        }
    }
    
    private final List<ParallelBenchmarkResult> results = new ArrayList<>();
    private final Map<Integer, Long> baselineDurations = new HashMap<>();
    
    @BeforeEach
    void setUp() {
        results.clear();
        baselineDurations.clear();
        System.out.printf("System has %d available cores\n", AVAILABLE_CORES);
    }
    
    @Test
    void benchmarkParallelScaling() {
        System.out.println("\n=== Parallel Scaling Benchmarks ===\n");
        
        // Test with moderate sizes to avoid memory issues
        var testSizes = new int[]{10_000, 50_000, 100_000, 250_000};
        var threadCounts = new int[]{1, 2, 4, 8, Math.min(16, AVAILABLE_CORES), AVAILABLE_CORES};
        
        for (int size : testSizes) {
            System.out.printf("\n--- Testing with %,d entities ---\n", size);
            
            var positions = generateUniformTestData(size);
            var contents = generateContents(size);
            
            // Establish baseline with single thread
            var baseline = benchmarkSingleThreaded(positions, contents);
            baselineDurations.put(size, baseline);
            
            // Test scaling with different thread counts
            for (int threads : threadCounts) {
                if (threads <= AVAILABLE_CORES) {
                    benchmarkParallelInsertion(threads, positions, contents, baseline);
                }
            }
            
            System.out.println();
        }
        
        printScalingSummary();
    }
    
    @Test
    void benchmarkWorkStealingVsFixedPool() {
        System.out.println("\n=== Work-Stealing vs Fixed Thread Pool ===\n");
        
        var testSizes = new int[]{10_000, 50_000, 100_000, 250_000};
        
        for (int size : testSizes) {
            System.out.printf("\n--- Testing with %,d entities ---\n", size);
            
            var positions = generateUniformTestData(size);
            var contents = generateContents(size);
            
            // Get baseline
            var baseline = baselineDurations.computeIfAbsent(size, 
                k -> benchmarkSingleThreaded(positions, contents));
            
            // Test work-stealing pool
            benchmarkWorkStealingPool(positions, contents, baseline);
            
            // Test fixed thread pool
            benchmarkFixedThreadPool(positions, contents, baseline);
            
            System.out.println();
        }
    }
    
    @Test
    void benchmarkSpatialPartitioning() {
        System.out.println("\n=== Spatial Partitioning Effectiveness ===\n");
        
        var testSize = 200_000;
        
        // Test different data distributions
        testSpatialPartitioning("Uniform", generateUniformTestData(testSize));
        testSpatialPartitioning("Clustered", generateClusteredTestData(testSize));
        testSpatialPartitioning("Diagonal", generateDiagonalTestData(testSize));
        testSpatialPartitioning("Grid", generateGridTestData(testSize));
    }
    
    @Test
    void benchmarkLockContention() {
        System.out.println("\n=== Lock Contention Analysis ===\n");
        
        var testSize = 200_000;
        var positions = generateClusteredTestData(testSize); // Worst case for contention
        var contents = generateContents(testSize);
        
        System.out.println("Testing with clustered data (high contention scenario):");
        
        // Test different locking strategies
        benchmarkCoarseLocking(positions, contents);
        benchmarkFineLocking(positions, contents);
        benchmarkRegionBasedLocking(positions, contents);
        benchmarkLockFreeConcurrent(positions, contents);
    }
    
    private long benchmarkSingleThreaded(List<Point3f> positions, List<String> contents) {
        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
        }
        
        // Measurement
        var totalDuration = 0L;
        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
            
            var start = System.nanoTime();
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
            totalDuration += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
        
        var avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        
        var result = new ParallelBenchmarkResult(
            "Single-threaded baseline", 1, avgDuration, positions.size(), avgDuration, 0);
        results.add(result);
        System.out.println(result);
        
        return avgDuration;
    }
    
    private void benchmarkParallelInsertion(int threadCount, List<Point3f> positions, 
                                          List<String> contents, long baseline) {
        var testName = String.format("Parallel insertion", threadCount);
        
        // Configure parallel operations
        var config = new ParallelBulkOperations.ParallelConfig()
            .withThreadCount(threadCount)
            .withBatchSize(1000)
            .withWorkStealing(true);
        
        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            runParallelInsertion(positions, contents, config);
        }
        
        // Measurement
        var totalDuration = 0L;
        var totalLockTime = 0L;
        
        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            var result = 
                runParallelInsertion(positions, contents, config);
            
            totalDuration += result.getTotalTime();
            totalLockTime += result.getTimings().getOrDefault("lockWait", 0L);
        }
        
        var avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        var avgLockTime = totalLockTime / MEASUREMENT_ROUNDS;
        
        var benchResult = new ParallelBenchmarkResult(
            testName, threadCount, avgDuration, positions.size(), baseline, avgLockTime);
        results.add(benchResult);
        System.out.println(benchResult);
    }
    
    private void benchmarkWorkStealingPool(List<Point3f> positions, List<String> contents, long baseline) {
        var testName = "Work-stealing pool";
        
        var config = ParallelBulkOperations.highPerformanceConfig()
            .withThreadCount(AVAILABLE_CORES)
            .withWorkStealing(true);
        
        var totalDuration = 0L;
        
        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            var result = 
                runParallelInsertion(positions, contents, config);
            totalDuration += result.getTotalTime();
        }
        
        var avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        
        var benchResult = new ParallelBenchmarkResult(
            testName, AVAILABLE_CORES, avgDuration, positions.size(), baseline, 0);
        results.add(benchResult);
        System.out.println(benchResult);
    }
    
    private void benchmarkFixedThreadPool(List<Point3f> positions, List<String> contents, long baseline) {
        var testName = "Fixed thread pool";
        
        var config = ParallelBulkOperations.highPerformanceConfig()
            .withThreadCount(AVAILABLE_CORES)
            .withWorkStealing(false);
        
        var totalDuration = 0L;
        
        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            var result = 
                runParallelInsertion(positions, contents, config);
            totalDuration += result.getTotalTime();
        }
        
        var avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        
        var benchResult = new ParallelBenchmarkResult(
            testName, AVAILABLE_CORES, avgDuration, positions.size(), baseline, 0);
        results.add(benchResult);
        System.out.println(benchResult);
    }
    
    private void testSpatialPartitioning(String distribution, List<Point3f> positions) {
        System.out.printf("\n%s distribution:\n", distribution);
        
        var contents = generateContents(positions.size());
        var baseline = benchmarkSingleThreaded(positions, contents);
        
        // Test with spatial partitioning
        var config = ParallelBulkOperations.highPerformanceConfig()
            .withThreadCount(AVAILABLE_CORES);
        
        var result = 
            runParallelInsertion(positions, contents, config);
        
        System.out.printf("  Partitions created: %d\n", 
            result.getStatistics().get("partitionCount"));
        System.out.printf("  Avg entities per partition: %.0f\n", 
            (double) positions.size() / result.getStatistics().get("partitionCount"));
        System.out.printf("  Speedup: %.2fx\n", baseline / (double) result.getTotalTime());
    }
    
    private void benchmarkCoarseLocking(List<Point3f> positions, List<String> contents) {
        System.out.println("\nCoarse-grained locking (single lock):");
        
        var start = System.nanoTime();
        
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var executor = Executors.newFixedThreadPool(AVAILABLE_CORES);
        var futures = new ArrayList<Future<?>>();
        
        var batchSize = positions.size() / AVAILABLE_CORES;
        var globalLock = new Object();
        
        for (int i = 0; i < AVAILABLE_CORES; i++) {
            var startIdx = i * batchSize;
            var endIdx = Math.min((i + 1) * batchSize, positions.size());
            
            futures.add(executor.submit(() -> {
                for (int j = startIdx; j < endIdx; j++) {
                    synchronized (globalLock) {
                        octree.insert(positions.get(j), DEFAULT_LEVEL, contents.get(j));
                    }
                }
            }));
        }
        
        // Wait for completion
        for (var future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        var duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.printf("  Duration: %,d ms\n", duration);
        
        executor.shutdown();
    }
    
    private void benchmarkFineLocking(List<Point3f> positions, List<String> contents) {
        System.out.println("\nFine-grained locking (per-node locks):");
        
        // Use parallel bulk operations with fine-grained locking
        var config = new ParallelBulkOperations.ParallelConfig()
            .withThreadCount(AVAILABLE_CORES)
            .withBatchSize(100);
        
        var result = 
            runParallelInsertion(positions, contents, config);
        
        System.out.printf("  Duration: %,d ms\n", result.getTotalTime());
        System.out.printf("  Lock wait time: %,d ms\n", 
            result.getTimings().getOrDefault("lockWait", 0L));
    }
    
    private void benchmarkRegionBasedLocking(List<Point3f> positions, List<String> contents) {
        System.out.println("\nRegion-based locking (spatial partitioning):");
        
        var config = ParallelBulkOperations.largeDatasetConfig()
            .withThreadCount(AVAILABLE_CORES);
        
        var result = 
            runParallelInsertion(positions, contents, config);
        
        System.out.printf("  Duration: %,d ms\n", result.getTotalTime());
        System.out.printf("  Partitions: %d\n", result.getStatistics().get("partitionCount"));
    }
    
    private void benchmarkLockFreeConcurrent(List<Point3f> positions, List<String> contents) {
        System.out.println("\nLock-free concurrent structures:");
        
        // Simulate lock-free approach using concurrent collections
        var start = System.nanoTime();
        
        var nodeEntities = new ConcurrentHashMap<Long, List<Integer>>();
        
        IntStream.range(0, positions.size()).parallel().forEach(i -> {
            var pos = positions.get(i);
            var nodeIndex = Constants.calculateMortonIndex(pos, DEFAULT_LEVEL);
            nodeEntities.computeIfAbsent(nodeIndex, k -> new CopyOnWriteArrayList<>()).add(i);
        });
        
        var duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.printf("  Duration: %,d ms\n", duration);
        System.out.printf("  Nodes created: %d\n", nodeEntities.size());
    }
    
    private ParallelBulkOperations.ParallelOperationResult<LongEntityID> runParallelInsertion(
            List<Point3f> positions, List<String> contents, 
            ParallelBulkOperations.ParallelConfig config) {
        
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var processor = new BulkOperationProcessor<com.hellblazer.luciferase.lucien.octree.MortonKey, LongEntityID, String>(octree);
        
        var parallelOps = 
            new ParallelBulkOperations<com.hellblazer.luciferase.lucien.octree.MortonKey, LongEntityID, String, OctreeNode<LongEntityID>>(octree, processor, config);
        
        try {
            return parallelOps.insertBatchParallel(positions, contents, DEFAULT_LEVEL);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            parallelOps.shutdown();
        }
    }
    
    // Test data generation methods
    private List<Point3f> generateUniformTestData(int count) {
        var positions = new ArrayList<Point3f>(count);
        var rand = new Random(42);
        
        for (int i = 0; i < count; i++) {
            positions.add(new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            ));
        }
        return positions;
    }
    
    private List<Point3f> generateClusteredTestData(int count) {
        var positions = new ArrayList<Point3f>(count);
        var rand = new Random(42);
        
        var numClusters = 20;
        for (int i = 0; i < count; i++) {
            var cluster = i % numClusters;
            var baseX = (cluster % 5) * 200f;
            var baseY = ((cluster / 5) % 4) * 250f;
            var baseZ = (cluster / 20) * 500f;
            
            positions.add(new Point3f(
                baseX + rand.nextFloat() * 30,
                baseY + rand.nextFloat() * 30,
                baseZ + rand.nextFloat() * 30
            ));
        }
        return positions;
    }
    
    private List<Point3f> generateDiagonalTestData(int count) {
        var positions = new ArrayList<Point3f>(count);
        var rand = new Random(42);
        
        for (int i = 0; i < count; i++) {
            var t = (float) i / count;
            positions.add(new Point3f(
                t * 1000 + rand.nextFloat() * 10,
                t * 1000 + rand.nextFloat() * 10,
                t * 1000 + rand.nextFloat() * 10
            ));
        }
        return positions;
    }
    
    private List<Point3f> generateGridTestData(int count) {
        var positions = new ArrayList<Point3f>(count);
        var gridSize = (int) Math.cbrt(count);
        
        var idx = 0;
        for (int x = 0; x < gridSize && idx < count; x++) {
            for (int y = 0; y < gridSize && idx < count; y++) {
                for (int z = 0; z < gridSize && idx < count; z++) {
                    positions.add(new Point3f(
                        x * 1000.0f / gridSize,
                        y * 1000.0f / gridSize,
                        z * 1000.0f / gridSize
                    ));
                    idx++;
                }
            }
        }
        return positions;
    }
    
    private List<String> generateContents(int count) {
        var contents = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            contents.add("Entity_" + i);
        }
        return contents;
    }
    
    private void printScalingSummary() {
        System.out.println("\n=== Parallel Scaling Summary ===\n");
        
        // Group by entity count
        var bySize = new TreeMap<Integer, List<ParallelBenchmarkResult>>();
        for (ParallelBenchmarkResult result : results) {
            bySize.computeIfAbsent(result.entityCount, k -> new ArrayList<>()).add(result);
        }
        
        // Print scaling curves
        for (var entry : bySize.entrySet()) {
            System.out.printf("\n%,d entities:\n", entry.getKey());
            System.out.println("Threads  Speedup  Efficiency");
            System.out.println("-------  -------  ----------");
            
            for (ParallelBenchmarkResult result : entry.getValue()) {
                if (!result.testName.contains("baseline")) {
                    System.out.printf("%7d  %7.2fx  %9.1f%%\n",
                        result.threadCount, result.speedup, result.efficiency * 100);
                }
            }
        }
    }
}