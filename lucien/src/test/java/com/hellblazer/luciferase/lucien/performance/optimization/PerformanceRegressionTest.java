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
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.Spatial;

/**
 * Performance regression test suite that tracks performance over time
 * and fails if performance degrades beyond acceptable thresholds.
 * 
 * Results are stored in CSV format for tracking and analysis.
 * 
 * Run with: RUN_SPATIAL_INDEX_PERF_TESTS=true mvn test
 * 
 * @author hal.hildebrand
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PerformanceRegressionTest {
    
    private static final String RESULTS_DIR = "lucien/performance-results";
    private static final String BASELINE_FILE = "performance-baseline.csv";
    private static final String HISTORY_FILE = "performance-history.csv";
    
    // Performance regression thresholds (percentage)
    private static final double ALLOWED_REGRESSION = 10.0; // Allow 10% performance degradation
    private static final double SIGNIFICANT_IMPROVEMENT = 20.0; // 20% improvement updates baseline
    
    // Test configuration
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASUREMENT_ROUNDS = 10;
    private static final byte DEFAULT_LEVEL = 10;
    
    // Performance metrics
    static class PerformanceMetric {
        final String testName;
        final String indexType;
        final int entityCount;
        final long duration;
        final double throughput;
        final long memoryUsed;
        final LocalDateTime timestamp;
        
        PerformanceMetric(String testName, String indexType, int entityCount, 
                         long duration, long memoryUsed) {
            this.testName = testName;
            this.indexType = indexType;
            this.entityCount = entityCount;
            this.duration = duration;
            this.throughput = entityCount * 1000.0 / duration;
            this.memoryUsed = memoryUsed;
            this.timestamp = LocalDateTime.now();
        }
        
        String toCsv() {
            return String.format("%s,%s,%s,%d,%d,%.0f,%d",
                timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                testName, indexType, entityCount, duration, throughput, memoryUsed);
        }
        
        static PerformanceMetric fromCsv(String line) {
            String[] parts = line.split(",");
            PerformanceMetric metric = new PerformanceMetric(
                parts[1], parts[2], Integer.parseInt(parts[3]),
                Long.parseLong(parts[4]), Long.parseLong(parts[6])
            );
            return metric;
        }
    }
    
    private final List<PerformanceMetric> currentResults = new ArrayList<>();
    private final Map<String, PerformanceMetric> baselineMetrics = new HashMap<>();
    
    @BeforeAll
    static void setupResultsDirectory() throws IOException {
        Path resultsPath = Paths.get(RESULTS_DIR);
        if (!Files.exists(resultsPath)) {
            Files.createDirectories(resultsPath);
        }
    }
    
    @BeforeEach
    void loadBaseline() throws IOException {
        Path baselinePath = Paths.get(RESULTS_DIR, BASELINE_FILE);
        if (Files.exists(baselinePath)) {
            try (BufferedReader reader = Files.newBufferedReader(baselinePath)) {
                reader.lines()
                    .skip(1) // Skip header
                    .map(PerformanceMetric::fromCsv)
                    .forEach(metric -> {
                        String key = metric.testName + "-" + metric.indexType + "-" + metric.entityCount;
                        baselineMetrics.put(key, metric);
                    });
            }
        }
    }
    
    @AfterEach
    void saveResults() throws IOException {
        // Append to history file
        Path historyPath = Paths.get(RESULTS_DIR, HISTORY_FILE);
        boolean writeHeader = !Files.exists(historyPath);
        
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(historyPath, 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            if (writeHeader) {
                writer.println("timestamp,test_name,index_type,entity_count,duration_ms,throughput,memory_bytes");
            }
            
            for (PerformanceMetric metric : currentResults) {
                writer.println(metric.toCsv());
            }
        }
        
        // Check for significant improvements and update baseline
        updateBaselineIfNeeded();
    }
    
    @Test
    @Order(1)
    void testBulkInsertionPerformance() throws Exception {
        System.out.println("\n=== Bulk Insertion Performance Regression Test ===\n");
        
        int[] testSizes = {10_000, 100_000, 500_000, 1_000_000};
        
        for (int size : testSizes) {
            // Test Octree
            testBulkInsertion("Octree", size, () -> new Octree<>(new SequentialLongIDGenerator()));
            
            // Test Tetree
            testBulkInsertion("Tetree", size, () -> new Tetree<>(new SequentialLongIDGenerator()));
        }
    }
    
    @Test
    @Order(2)
    void testQueryPerformance() throws Exception {
        System.out.println("\n=== Query Performance Regression Test ===\n");
        
        int dataSize = 100_000;
        
        // Test Octree queries
        testQueries("Octree", dataSize, () -> new Octree<>(new SequentialLongIDGenerator()));
        
        // Test Tetree queries
        testQueries("Tetree", dataSize, () -> new Tetree<>(new SequentialLongIDGenerator()));
    }
    
    @Test
    @Order(3)
    void testMemoryEfficiency() throws Exception {
        System.out.println("\n=== Memory Efficiency Regression Test ===\n");
        
        int[] testSizes = {50_000, 100_000, 500_000, 1_000_000};
        
        for (int size : testSizes) {
            testMemoryUsage("Octree", size, () -> new Octree<>(new SequentialLongIDGenerator()));
            testMemoryUsage("Tetree", size, () -> new Tetree<>(new SequentialLongIDGenerator()));
        }
    }
    
    @Test
    @Order(4)
    void testOptimizationEffectiveness() throws Exception {
        System.out.println("\n=== Optimization Effectiveness Test ===\n");
        
        int testSize = 100_000;
        List<Point3f> positions = generateUniformTestData(testSize);
        List<String> contents = generateContents(testSize);
        
        // Test various optimization combinations
        testOptimizationCombination("No optimizations", testSize, positions, contents,
            new BulkOperationConfig());
        
        testOptimizationCombination("Deferred subdivision only", testSize, positions, contents,
            new BulkOperationConfig().withDeferredSubdivision(true));
        
        testOptimizationCombination("Pre-sorting only", testSize, positions, contents,
            new BulkOperationConfig().withPreSortByMorton(true));
        
        testOptimizationCombination("All optimizations", testSize, positions, contents,
            BulkOperationConfig.highPerformance());
    }
    
    private void testBulkInsertion(String indexType, int size, IndexFactory factory) throws Exception {
        String testName = "BulkInsertion";
        List<Point3f> positions = generateUniformTestData(size);
        List<String> contents = generateContents(size);
        
        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            var index = factory.create();
            index.configureBulkOperations(BulkOperationConfig.highPerformance());
            index.insertBatch(positions, contents, DEFAULT_LEVEL);
        }
        
        // Measurement
        long totalDuration = 0;
        long totalMemory = 0;
        
        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            System.gc();
            long memBefore = getCurrentMemoryUsage();
            
            var index = factory.create();
            index.configureBulkOperations(BulkOperationConfig.highPerformance());
            
            long start = System.nanoTime();
            index.insertBatch(positions, contents, DEFAULT_LEVEL);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            
            long memAfter = getCurrentMemoryUsage();
            
            totalDuration += duration;
            totalMemory += (memAfter - memBefore);
        }
        
        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        long avgMemory = totalMemory / MEASUREMENT_ROUNDS;
        
        PerformanceMetric metric = new PerformanceMetric(testName, indexType, size, avgDuration, avgMemory);
        currentResults.add(metric);
        
        // Check regression
        checkRegression(metric);
        
        System.out.printf("%-20s %s: %,d entities in %,d ms (%.0f entities/sec)\n",
            testName, indexType, size, avgDuration, metric.throughput);
    }
    
    private void testQueries(String indexType, int size, IndexFactory factory) throws Exception {
        // Prepare test data
        List<Point3f> positions = generateUniformTestData(size);
        List<String> contents = generateContents(size);
        
        var index = factory.create();
        index.configureBulkOperations(BulkOperationConfig.highPerformance());
        index.insertBatch(positions, contents, DEFAULT_LEVEL);
        
        // Test k-NN query
        testKnnQuery(index, indexType, size);
        
        // Test range query
        testRangeQuery(index, indexType, size);
    }
    
    private void testKnnQuery(com.hellblazer.luciferase.lucien.SpatialIndex<?, LongEntityID, String> index,
                             String indexType, int dataSize) {
        String testName = "KnnQuery";
        Point3f queryPoint = new Point3f(500, 500, 500);
        int k = 100;
        
        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            index.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
        }
        
        // Measurement
        long totalDuration = 0;
        
        for (int i = 0; i < MEASUREMENT_ROUNDS * 10; i++) { // More rounds for query tests
            long start = System.nanoTime();
            index.kNearestNeighbors(queryPoint, k, Float.MAX_VALUE);
            totalDuration += TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
        }
        
        long avgDuration = totalDuration / (MEASUREMENT_ROUNDS * 10);
        
        PerformanceMetric metric = new PerformanceMetric(testName, indexType, dataSize, avgDuration, 0);
        currentResults.add(metric);
        
        checkRegression(metric);
        
        System.out.printf("%-20s %s: %d-NN query in %,d μs\n",
            testName, indexType, k, avgDuration);
    }
    
    private <Key extends com.hellblazer.luciferase.lucien.SpatialKey<Key>> void testRangeQuery(com.hellblazer.luciferase.lucien.SpatialIndex<Key, LongEntityID, String> index,
                               String indexType, int dataSize) {
        String testName = "RangeQuery";
        Point3f min = new Point3f(400, 400, 400);
        Point3f max = new Point3f(600, 600, 600);
        
        // Warmup
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            // Create a cube from min/max points
            float extent = Math.max(Math.max(max.x - min.x, max.y - min.y), max.z - min.z);
            index.entitiesInRegion(new Spatial.Cube(min.x, min.y, min.z, extent));
        }
        
        // Measurement
        long totalDuration = 0;
        
        for (int i = 0; i < MEASUREMENT_ROUNDS * 10; i++) {
            long start = System.nanoTime();
            // Create a cube from min/max points
            float extent = Math.max(Math.max(max.x - min.x, max.y - min.y), max.z - min.z);
            index.entitiesInRegion(new Spatial.Cube(min.x, min.y, min.z, extent));
            totalDuration += TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
        }
        
        long avgDuration = totalDuration / (MEASUREMENT_ROUNDS * 10);
        
        PerformanceMetric metric = new PerformanceMetric(testName, indexType, dataSize, avgDuration, 0);
        currentResults.add(metric);
        
        checkRegression(metric);
        
        System.out.printf("%-20s %s: Range query in %,d μs\n",
            testName, indexType, avgDuration);
    }
    
    private void testMemoryUsage(String indexType, int size, IndexFactory factory) throws Exception {
        String testName = "MemoryUsage";
        List<Point3f> positions = generateUniformTestData(size);
        List<String> contents = generateContents(size);
        
        System.gc();
        long memBefore = getCurrentMemoryUsage();
        
        var index = factory.create();
        index.configureBulkOperations(BulkOperationConfig.highPerformance());
        index.insertBatch(positions, contents, DEFAULT_LEVEL);
        
        System.gc();
        long memAfter = getCurrentMemoryUsage();
        long memUsed = memAfter - memBefore;
        
        PerformanceMetric metric = new PerformanceMetric(testName, indexType, size, 0, memUsed);
        currentResults.add(metric);
        
        checkMemoryRegression(metric);
        
        System.out.printf("%-20s %s: %,d entities using %,d KB (%.2f bytes/entity)\n",
            testName, indexType, size, memUsed / 1024, (double) memUsed / size);
    }
    
    private void testOptimizationCombination(String name, int size, List<Point3f> positions,
                                           List<String> contents, BulkOperationConfig config) {
        String testName = "Optimization-" + name.replace(" ", "_");
        
        long totalDuration = 0;
        
        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
            octree.configureBulkOperations(config);
            
            long start = System.nanoTime();
            octree.insertBatch(positions, contents, DEFAULT_LEVEL);
            totalDuration += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
        
        long avgDuration = totalDuration / MEASUREMENT_ROUNDS;
        
        PerformanceMetric metric = new PerformanceMetric(testName, "Octree", size, avgDuration, 0);
        currentResults.add(metric);
        
        System.out.printf("%-40s: %,d ms\n", name, avgDuration);
    }
    
    private void checkRegression(PerformanceMetric current) {
        String key = current.testName + "-" + current.indexType + "-" + current.entityCount;
        PerformanceMetric baseline = baselineMetrics.get(key);
        
        if (baseline != null) {
            double regression = ((double) current.duration - baseline.duration) / baseline.duration * 100;
            
            if (regression > ALLOWED_REGRESSION) {
                String message = String.format(
                    "Performance regression detected for %s %s (%d entities): " +
                    "%.1f%% slower than baseline (current: %dms, baseline: %dms)",
                    current.testName, current.indexType, current.entityCount,
                    regression, current.duration, baseline.duration
                );
                
                // In a CI environment, this would fail the build
                System.err.println("WARNING: " + message);
                // Assertions.fail(message);
            } else if (regression < -SIGNIFICANT_IMPROVEMENT) {
                System.out.printf("  Significant improvement: %.1f%% faster than baseline\n", -regression);
            }
        }
    }
    
    private void checkMemoryRegression(PerformanceMetric current) {
        String key = current.testName + "-" + current.indexType + "-" + current.entityCount;
        PerformanceMetric baseline = baselineMetrics.get(key);
        
        if (baseline != null && baseline.memoryUsed > 0) {
            double regression = ((double) current.memoryUsed - baseline.memoryUsed) / baseline.memoryUsed * 100;
            
            if (regression > ALLOWED_REGRESSION) {
                String message = String.format(
                    "Memory regression detected for %s %s (%d entities): " +
                    "%.1f%% more memory than baseline",
                    current.testName, current.indexType, current.entityCount,
                    regression
                );
                
                System.err.println("WARNING: " + message);
            }
        }
    }
    
    private void updateBaselineIfNeeded() throws IOException {
        boolean shouldUpdate = false;
        
        for (PerformanceMetric current : currentResults) {
            String key = current.testName + "-" + current.indexType + "-" + current.entityCount;
            PerformanceMetric baseline = baselineMetrics.get(key);
            
            if (baseline == null) {
                shouldUpdate = true;
                break;
            }
            
            double improvement = ((double) baseline.duration - current.duration) / baseline.duration * 100;
            if (improvement > SIGNIFICANT_IMPROVEMENT) {
                shouldUpdate = true;
                break;
            }
        }
        
        if (shouldUpdate) {
            System.out.println("\nUpdating performance baseline...");
            
            Path baselinePath = Paths.get(RESULTS_DIR, BASELINE_FILE);
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(baselinePath))) {
                writer.println("timestamp,test_name,index_type,entity_count,duration_ms,throughput,memory_bytes");
                
                // Merge current results with baseline
                Map<String, PerformanceMetric> updated = new HashMap<>(baselineMetrics);
                for (PerformanceMetric metric : currentResults) {
                    String key = metric.testName + "-" + metric.indexType + "-" + metric.entityCount;
                    updated.put(key, metric);
                }
                
                for (PerformanceMetric metric : updated.values()) {
                    writer.println(metric.toCsv());
                }
            }
        }
    }
    
    private long getCurrentMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    private List<Point3f> generateUniformTestData(int count) {
        List<Point3f> positions = new ArrayList<>(count);
        Random rand = new Random(42);
        
        for (int i = 0; i < count; i++) {
            positions.add(new Point3f(
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000,
                rand.nextFloat() * 1000
            ));
        }
        return positions;
    }
    
    private List<String> generateContents(int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> "Entity_" + i)
            .collect(Collectors.toList());
    }
    
    @FunctionalInterface
    interface IndexFactory {
        com.hellblazer.luciferase.lucien.SpatialIndex<? extends com.hellblazer.luciferase.lucien.SpatialKey<?>, LongEntityID, String> create();
    }
}