/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.benchmark;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.prism.Prism;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Comprehensive performance comparison of Octree vs Tetree vs Prism spatial indices.
 * This benchmark provides complete performance metrics for all three implementations
 * to support unified performance documentation and analysis.
 * 
 * Includes basic spatial index operations for all three implementations.
 * 
 * @author hal.hildebrand
 */
public class OctreeVsTetreeVsPrismBenchmark {
    
    private static final String PERFORMANCE_OUTPUT_DIR = System.getProperty("performance.output.dir", "target/performance-output");

    private static final int   WARMUP_ITERATIONS    = 100;
    private static final int   BENCHMARK_ITERATIONS = 1000;
    private static final int   MAX_ENTITY_COUNT     = Integer.parseInt(
        System.getProperty("performance.max.entities", "10000"));
    private static final int[] ALL_ENTITY_COUNTS    = { 100, 1000, 10000 };
    private static final int[] ENTITY_COUNTS        = java.util.Arrays.stream(ALL_ENTITY_COUNTS)
        .filter(size -> size <= MAX_ENTITY_COUNT)
        .toArray();
    private static final int   K_NEIGHBORS          = 10;
    private static final float SEARCH_RADIUS        = 50.0f;
    private static final byte  TEST_LEVEL           = 10;
    private static final float WORLD_SIZE           = 100.0f;

    private static class TestEntity {
        final Point3f position;
        final String content;
        final LongEntityID id;

        TestEntity(Point3f position, String content, LongEntityID id) {
            this.position = position;
            this.content = content;
            this.id = id;
        }
    }

    @Test
    public void compareAllSpatialIndices() throws IOException {
        System.out.println("=== OCTREE vs TETREE vs PRISM PERFORMANCE COMPARISON ===");
        System.out.println("Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
        System.out.println();

        List<String> csvLines = new ArrayList<>();
        csvLines.add("operation,throughput,latency");

        for (int entityCount : ENTITY_COUNTS) {
            System.out.println("=== Testing with " + entityCount + " entities ===");
            csvLines.addAll(runThreeWayComparison(entityCount));
        }
        
        // Write CSV to performance output directory
        writeCsvResults(csvLines);
    }

    @BeforeEach
    void checkEnvironment() {
        // Skip if running in any CI environment
        assumeFalse(CIEnvironmentCheck.isRunningInCI(), CIEnvironmentCheck.getSkipMessage());
    }

    private List<String> runThreeWayComparison(int entityCount) {
        var entities = generateTestEntities(entityCount);
        List<String> csvLines = new ArrayList<>();
        
        // Warm up all three implementations
        warmupAllImplementations(entities);

        // Test insertion performance
        csvLines.addAll(testInsertionPerformance(entities, entityCount));
        
        // Test query performance  
        csvLines.addAll(testQueryPerformance(entities, entityCount));
        
        // Test memory usage
        csvLines.addAll(testMemoryUsage(entities, entityCount));
        
        // Test update and removal performance
        csvLines.addAll(testUpdateAndRemovalPerformance(entities, entityCount));
        
        return csvLines;
    }

    private void warmupAllImplementations(List<TestEntity> entities) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            // Warm up Octree
            var octree = createOctree();
            entities.forEach(e -> octree.insert(e.id, e.position, TEST_LEVEL, e.content));
            
            // Warm up Tetree  
            var tetree = createTetree();
            entities.forEach(e -> tetree.insert(e.id, e.position, TEST_LEVEL, e.content));
            
            // Warm up Prism
            var prism = createPrism();
            entities.forEach(e -> prism.insert(e.position, (byte) TEST_LEVEL, e.content));
        }
    }

    private List<String> testInsertionPerformance(List<TestEntity> entities, int entityCount) {
        System.out.println("\n--- INSERTION Performance ---");
        
        // Benchmark Octree insertion
        long octreeTime = benchmarkOctreeInsertion(entities);
        
        // Benchmark Tetree insertion
        long tetreeTime = benchmarkTetreeInsertion(entities);
        
        // Benchmark Prism insertion
        long prismTime = benchmarkPrismInsertion(entities);

        // Convert to milliseconds and print
        double octreeMs = octreeTime / 1_000_000.0;
        double tetreeMs = tetreeTime / 1_000_000.0;
        double prismMs = prismTime / 1_000_000.0;

        System.out.printf("Octree: %.3f ms%n", octreeMs);
        System.out.printf("Tetree: %.3f ms%n", tetreeMs);
        System.out.printf("Prism: %.3f ms%n", prismMs);
        
        // Output in format expected by TestResultExtractor
        double octreeUsPerOp = (octreeMs * 1000.0) / entities.size();
        double tetreeUsPerOp = (tetreeMs * 1000.0) / entities.size();
        double prismUsPerOp = (prismMs * 1000.0) / entities.size();
        System.out.printf("Insertion     | %.1f μs/op | %.1f μs/op | %.1f μs/op%n", 
                         octreeUsPerOp, tetreeUsPerOp, prismUsPerOp);
        
        List<String> csvLines = new ArrayList<>();
        csvLines.add(String.format("insert-prism-%d,%.1f μs/op,", entityCount, prismUsPerOp));
        return csvLines;
    }

    private List<String> testQueryPerformance(List<TestEntity> entities, int entityCount) {
        System.out.println("\n--- QUERY Performance ---");
        
        // Create populated indices
        var octree = createOctree();
        var tetree = createTetree();
        var prism = createPrism();
        
        entities.forEach(e -> {
            octree.insert(e.id, e.position, TEST_LEVEL, e.content);
            tetree.insert(e.id, e.position, TEST_LEVEL, e.content);
            prism.insert(e.position, (byte) TEST_LEVEL, e.content);
        });

        var queryPosition = entities.get(entities.size() / 2).position;

        // Test k-NN performance
        long octreeKNN = benchmarkKNN(octree, queryPosition);
        long tetreeKNN = benchmarkKNN(tetree, queryPosition);  
        long prismKNN = benchmarkKNN(prism, queryPosition);

        System.out.printf("k-NN Octree: %.0f μs%n", octreeKNN / 1000.0);
        System.out.printf("k-NN Tetree: %.0f μs%n", tetreeKNN / 1000.0);
        System.out.printf("k-NN Prism: %.0f μs%n", prismKNN / 1000.0);
        
        // Output in format expected by TestResultExtractor
        System.out.printf("k-NN Query    | %.1f μs/op | %.1f μs/op | %.1f μs/op%n", 
                         octreeKNN / 1000.0, tetreeKNN / 1000.0, prismKNN / 1000.0);

        // Test range query performance
        long octreeRange = benchmarkRangeQuery(octree, queryPosition);
        long tetreeRange = benchmarkRangeQuery(tetree, queryPosition);
        long prismRange = benchmarkRangeQuery(prism, queryPosition);

        System.out.printf("Range Octree: %.0f μs%n", octreeRange / 1000.0);
        System.out.printf("Range Tetree: %.0f μs%n", tetreeRange / 1000.0);
        System.out.printf("Range Prism: %.0f μs%n", prismRange / 1000.0);
        
        // Output in format expected by TestResultExtractor
        System.out.printf("Range Query   | %.1f μs/op | %.1f μs/op | %.1f μs/op%n", 
                         octreeRange / 1000.0, tetreeRange / 1000.0, prismRange / 1000.0);
        
        List<String> csvLines = new ArrayList<>();
        csvLines.add(String.format("knn-prism-%d,,%.3f ms", entityCount, prismKNN / 1000000.0));
        csvLines.add(String.format("range-prism-%d,,%.3f ms", entityCount, prismRange / 1000000.0));
        return csvLines;
    }

    private List<String> testMemoryUsage(List<TestEntity> entities, int entityCount) {
        System.out.println("\n--- MEMORY Usage ---");
        
        Runtime.getRuntime().gc();
        long baseMemOctree = getUsedMemory();

        // Measure Octree memory
        var octree = createOctree();
        entities.forEach(e -> octree.insert(e.id, e.position, TEST_LEVEL, e.content));
        Runtime.getRuntime().gc();
        long octreeMem = getUsedMemory() - baseMemOctree;

        // Measure Tetree memory
        System.gc();
        long baseMemTetree = getUsedMemory();
        
        var tetree = createTetree();
        entities.forEach(e -> tetree.insert(e.id, e.position, TEST_LEVEL, e.content));
        Runtime.getRuntime().gc();
        long tetreeMem = getUsedMemory() - baseMemTetree;

        // Measure Prism memory
        System.gc();
        long baseMemPrism = getUsedMemory();
        
        var prism = createPrism();
        entities.forEach(e -> prism.insert(e.position, (byte) TEST_LEVEL, e.content));
        Runtime.getRuntime().gc();
        long prismMem = getUsedMemory() - baseMemPrism;

        System.out.printf("Octree Memory: %.3f MB%n", octreeMem / (1024.0 * 1024.0));
        System.out.printf("Tetree Memory: %.3f MB%n", tetreeMem / (1024.0 * 1024.0));
        System.out.printf("Prism Memory: %.3f MB%n", prismMem / (1024.0 * 1024.0));
        
        // Output in format expected by TestResultExtractor
        double octreeBytesPerEntity = (double) octreeMem / entities.size();
        double tetreeBytesPerEntity = (double) tetreeMem / entities.size();
        double prismBytesPerEntity = (double) prismMem / entities.size();
        System.out.printf("Memory per entity | %.1f bytes | %.1f bytes | %.1f bytes%n", 
                         octreeBytesPerEntity, tetreeBytesPerEntity, prismBytesPerEntity);
        
        List<String> csvLines = new ArrayList<>();
        csvLines.add(String.format("memory-prism-%d,%.3f MB,", entityCount, prismMem / (1024.0 * 1024.0)));
        return csvLines;
    }

    private List<String> testUpdateAndRemovalPerformance(List<TestEntity> entities, int entityCount) {
        System.out.println("\n--- UPDATE & REMOVAL Performance ---");
        
        // Test update performance
        var octreeUpdate = createOctree();
        var tetreeUpdate = createTetree();
        var prismUpdate = createPrism();
        
        entities.forEach(e -> {
            octreeUpdate.insert(e.id, e.position, TEST_LEVEL, e.content);
            tetreeUpdate.insert(e.id, e.position, TEST_LEVEL, e.content);
            prismUpdate.insert(e.position, (byte) TEST_LEVEL, e.content);
        });

        var entityToUpdate = entities.get(0);
        var newPosition = new Point3f(entityToUpdate.position.x + 1, 
                                     entityToUpdate.position.y + 1, 
                                     entityToUpdate.position.z + 1);

        long octreeUpdateTime = benchmarkUpdate(octreeUpdate, entityToUpdate.id, newPosition, "updated");
        long tetreeUpdateTime = benchmarkUpdate(tetreeUpdate, entityToUpdate.id, newPosition, "updated");
        long prismUpdateTime = benchmarkUpdate(prismUpdate, entityToUpdate.id, newPosition, "updated");

        System.out.printf("UPDATE Octree: %.3f ms%n", octreeUpdateTime / 1_000_000.0);
        System.out.printf("UPDATE Tetree: %.3f ms%n", tetreeUpdateTime / 1_000_000.0);
        System.out.printf("UPDATE Prism: %.3f ms%n", prismUpdateTime / 1_000_000.0);

        // Test removal performance
        long octreeRemovalTime = benchmarkRemoval(octreeUpdate, entityToUpdate.id);
        long tetreeRemovalTime = benchmarkRemoval(tetreeUpdate, entityToUpdate.id);
        long prismRemovalTime = benchmarkRemoval(prismUpdate, entityToUpdate.id);

        System.out.printf("REMOVAL Octree: %.3f ms%n", octreeRemovalTime / 1_000_000.0);
        System.out.printf("REMOVAL Tetree: %.3f ms%n", tetreeRemovalTime / 1_000_000.0);
        System.out.printf("REMOVAL Prism: %.3f ms%n", prismRemovalTime / 1_000_000.0);
        
        List<String> csvLines = new ArrayList<>();
        csvLines.add(String.format("update-prism-%d,%.1f μs/op,", entityCount, prismUpdateTime / 1000.0));
        csvLines.add(String.format("remove-prism-%d,%.1f μs/op,", entityCount, prismRemovalTime / 1000.0));
        return csvLines;
    }

    // Benchmark helper methods
    private long benchmarkOctreeInsertion(List<TestEntity> entities) {
        long totalTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var octree = createOctree();
            long start = System.nanoTime();
            entities.forEach(e -> octree.insert(e.id, e.position, TEST_LEVEL, e.content));
            totalTime += System.nanoTime() - start;
        }
        return totalTime / BENCHMARK_ITERATIONS;
    }

    private long benchmarkTetreeInsertion(List<TestEntity> entities) {
        long totalTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var tetree = createTetree();
            long start = System.nanoTime();
            entities.forEach(e -> tetree.insert(e.id, e.position, TEST_LEVEL, e.content));
            totalTime += System.nanoTime() - start;
        }
        return totalTime / BENCHMARK_ITERATIONS;
    }

    private long benchmarkPrismInsertion(List<TestEntity> entities) {
        long totalTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var prism = createPrism();
            long start = System.nanoTime();
            entities.forEach(e -> prism.insert(e.position, (byte) TEST_LEVEL, e.content));
            totalTime += System.nanoTime() - start;
        }
        return totalTime / BENCHMARK_ITERATIONS;
    }

    private <T> long benchmarkKNN(T spatialIndex, Point3f queryPosition) {
        long totalTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            
            if (spatialIndex instanceof Octree) {
                ((Octree<LongEntityID, String>) spatialIndex).kNearestNeighbors(queryPosition, K_NEIGHBORS, Float.MAX_VALUE);
            } else if (spatialIndex instanceof Tetree) {
                ((Tetree<LongEntityID, String>) spatialIndex).kNearestNeighbors(queryPosition, K_NEIGHBORS, Float.MAX_VALUE);
            } else if (spatialIndex instanceof Prism) {
                ((Prism<LongEntityID, String>) spatialIndex).kNearestNeighbors(queryPosition, K_NEIGHBORS, Float.MAX_VALUE);
            }
            
            totalTime += System.nanoTime() - start;
        }
        return totalTime / BENCHMARK_ITERATIONS;
    }

    private <T> long benchmarkRangeQuery(T spatialIndex, Point3f queryPosition) {
        long totalTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            
            if (spatialIndex instanceof Octree) {
                ((Octree<LongEntityID, String>) spatialIndex).kNearestNeighbors(queryPosition, Integer.MAX_VALUE, SEARCH_RADIUS);
            } else if (spatialIndex instanceof Tetree) {
                ((Tetree<LongEntityID, String>) spatialIndex).kNearestNeighbors(queryPosition, Integer.MAX_VALUE, SEARCH_RADIUS);
            } else if (spatialIndex instanceof Prism) {
                ((Prism<LongEntityID, String>) spatialIndex).kNearestNeighbors(queryPosition, Integer.MAX_VALUE, SEARCH_RADIUS);
            }
            
            totalTime += System.nanoTime() - start;
        }
        return totalTime / BENCHMARK_ITERATIONS;
    }

    private <T> long benchmarkUpdate(T spatialIndex, LongEntityID entityId, Point3f newPosition, String newContent) {
        long start = System.nanoTime();
        
        if (spatialIndex instanceof Octree) {
            ((Octree<LongEntityID, String>) spatialIndex).updateEntity(entityId, newPosition, TEST_LEVEL);
        } else if (spatialIndex instanceof Tetree) {
            ((Tetree<LongEntityID, String>) spatialIndex).updateEntity(entityId, newPosition, TEST_LEVEL);
        } else if (spatialIndex instanceof Prism) {
            // Prism may not have update - remove and re-insert
            ((Prism<LongEntityID, String>) spatialIndex).removeEntity(entityId);
            ((Prism<LongEntityID, String>) spatialIndex).insert(newPosition, (byte) TEST_LEVEL, newContent);
        }
        
        return System.nanoTime() - start;
    }

    private <T> long benchmarkRemoval(T spatialIndex, LongEntityID entityId) {
        long start = System.nanoTime();
        
        if (spatialIndex instanceof Octree) {
            ((Octree<LongEntityID, String>) spatialIndex).removeEntity(entityId);
        } else if (spatialIndex instanceof Tetree) {
            ((Tetree<LongEntityID, String>) spatialIndex).removeEntity(entityId);
        } else if (spatialIndex instanceof Prism) {
            ((Prism<LongEntityID, String>) spatialIndex).removeEntity(entityId);
        }
        
        return System.nanoTime() - start;
    }

    // Factory methods
    private Octree<LongEntityID, String> createOctree() {
        return new Octree<>(new SequentialLongIDGenerator());
    }

    private Tetree<LongEntityID, String> createTetree() {
        return new Tetree<>(new SequentialLongIDGenerator());
    }

    private Prism<LongEntityID, String> createPrism() {
        return new Prism<>(new SequentialLongIDGenerator(), WORLD_SIZE, 21);
    }

    // Utility methods
    private List<TestEntity> generateTestEntities(int count) {
        var entities = new ArrayList<TestEntity>(count);
        var idGenerator = new SequentialLongIDGenerator();
        
        for (int i = 0; i < count; i++) {
            var position = generateValidPosition();
            entities.add(new TestEntity(position, "content-" + i, idGenerator.generateID()));
        }
        
        return entities;
    }

    /**
     * Generate position valid for all spatial indices including Prism (x + y < worldSize constraint).
     */
    private Point3f generateValidPosition() {
        var random = ThreadLocalRandom.current();
        // For Prism compatibility: ensure x + y < worldSize
        float x = random.nextFloat() * (WORLD_SIZE * 0.7f);  // 0 to 70% of worldSize
        float y = random.nextFloat() * (WORLD_SIZE * 0.7f - x); // Ensure x + y < 70% of worldSize
        float z = random.nextFloat() * WORLD_SIZE;
        return new Point3f(x, y, z);
    }


    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    private void writeCsvResults(List<String> csvLines) throws IOException {
        Path outputDir = Paths.get(PERFORMANCE_OUTPUT_DIR);
        Files.createDirectories(outputDir);
        
        Path csvFile = outputDir.resolve("benchmark-results.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csvFile)) {
            for (String line : csvLines) {
                writer.write(line);
                writer.newLine();
            }
        }
        
        System.out.println("\nPerformance results written to: " + csvFile.toAbsolutePath());
    }
}