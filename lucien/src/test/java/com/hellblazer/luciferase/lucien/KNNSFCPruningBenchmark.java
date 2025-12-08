/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dedicated benchmark for k-NN SFC range pruning optimization.
 * Measures performance improvements from Paper 4 (Space-Filling Trees for Motion Planning).
 * 
 * Expected improvement: 4-6× speedup over breadth-first search
 *
 * @author hal.hildebrand
 */
@DisplayName("k-NN SFC Range Pruning Benchmark")
public class KNNSFCPruningBenchmark {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 1000;
    private static final Random random = new Random(42); // Fixed seed for reproducibility

    @Test
    @DisplayName("Benchmark k-NN SFC pruning performance")
    void benchmarkKNNSFCPruning() {
        System.out.println("\n=== k-NN SFC Range Pruning Benchmark ===\n");

        // Test configurations
        int[] treeSizes = { 1000, 10000, 100000 };
        int[] kValues = { 5, 10, 20, 50 };
        float searchRadius = 100.0f;

        for (int treeSize : treeSizes) {
            System.out.printf("Tree size: %,d entities%n", treeSize);
            System.out.println("-".repeat(60));

            // Create and populate index
            var index = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
            var entities = generateEntities(treeSize);

            for (var entity : entities) {
                index.insert(entity.id, entity.position, (byte) 10, entity.content);
            }

            // Generate query points
            var queryPoints = generateQueryPoints(BENCHMARK_ITERATIONS);

            for (int k : kValues) {
                // Warmup
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    index.kNearestNeighbors(queryPoints.get(i % queryPoints.size()), k, searchRadius);
                }

                // Benchmark
                long startTime = System.nanoTime();
                for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                    var results = index.kNearestNeighbors(queryPoints.get(i), k, searchRadius);
                }
                long endTime = System.nanoTime();

                double avgTimeMs = (endTime - startTime) / 1_000_000.0 / BENCHMARK_ITERATIONS;
                double queriesPerSec = 1000.0 / avgTimeMs;

                System.out.printf("  k=%2d: %.3f ms/query (%.0f queries/sec)%n", 
                    k, avgTimeMs, queriesPerSec);
            }

            // Print performance metrics
            var metrics = index.getKNNPerformanceMetrics();
            System.out.println();
            System.out.println("Performance Metrics:");
            System.out.printf("  Cache hits: %,d%n", metrics.cacheHits());
            System.out.printf("  Cache misses: %,d%n", metrics.cacheMisses());
            System.out.printf("  Cache hit rate: %.1f%%%n", metrics.cacheHitRate() * 100);
            System.out.printf("  Expanding search used: %,d%n", metrics.expandingSearchUsed());
            System.out.printf("  SFC pruning used: %,d%n", metrics.sfcPruningUsed());
            System.out.printf("  SFC pruning rate: %.1f%%%n", metrics.sfcPruningRate() * 100);
            System.out.println();
        }

        System.out.println("=".repeat(60));
        System.out.println("Benchmark complete!");
        System.out.println("\nExpected performance (from Paper 4):");
        System.out.println("  - Target: <0.1ms per query (4-6× faster than breadth-first)");
        System.out.println("  - Previous breadth-first: ~1.5-2.0ms per query");
        System.out.println("=".repeat(60));
    }

    private List<TestEntity> generateEntities(int count) {
        var entities = new ArrayList<TestEntity>(count);
        var idGen = new SequentialLongIDGenerator();

        for (int i = 0; i < count; i++) {
            var pos = new Point3f(
                random.nextFloat() * 1000.0f,
                random.nextFloat() * 1000.0f,
                random.nextFloat() * 1000.0f
            );
            entities.add(new TestEntity(idGen.generateID(), pos, "Entity-" + i));
        }

        return entities;
    }

    private List<Point3f> generateQueryPoints(int count) {
        var points = new ArrayList<Point3f>(count);

        for (int i = 0; i < count; i++) {
            points.add(new Point3f(
                random.nextFloat() * 1000.0f,
                random.nextFloat() * 1000.0f,
                random.nextFloat() * 1000.0f
            ));
        }

        return points;
    }

    private record TestEntity(LongEntityID id, Point3f position, String content) {}
}
