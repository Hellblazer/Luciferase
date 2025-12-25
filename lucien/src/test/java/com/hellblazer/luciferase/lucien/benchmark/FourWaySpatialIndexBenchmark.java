/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.lucien.benchmark;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.sfc.SFCArrayIndex;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Comprehensive 3-way performance comparison of spatial index implementations:
 * - Octree (Morton curve, cubic subdivision)
 * - Tetree (Tetrahedral SFC, tetrahedral subdivision)
 * - SFCArrayIndex (Morton curve, flat array storage)
 *
 * Note: Prism is excluded due to its triangular domain constraints.
 *
 * Tests insertion, range queries, k-NN, and memory usage with LITMAX/BIGMIN optimizations.
 *
 * @author hal.hildebrand
 */
public class FourWaySpatialIndexBenchmark {

    private static final int WARMUP_ITERATIONS = 3;
    private static final int BENCHMARK_ITERATIONS = 5;
    private static final int[] ENTITY_COUNTS = {1000, 10000, 50000};
    private static final byte TEST_LEVEL = 10;
    private static final int K_NEIGHBORS = 10;
    private static final float WORLD_SIZE = 10000.0f;

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
        assumeFalse(CIEnvironmentCheck.isRunningInCI(), CIEnvironmentCheck.getSkipMessage());
    }

    @Test
    void runComprehensiveBenchmark() {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║     SPATIAL INDEX BENCHMARK (Post-LITMAX/BIGMIN Optimization)               ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║ Platform: %-68s ║%n", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.printf("║ Java Version: %-64s ║%n", System.getProperty("java.version"));
        System.out.printf("║ Processors: %-66d ║%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("║ Max Memory: %-62d MB ║%n", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");

        for (int entityCount : ENTITY_COUNTS) {
            runBenchmarkSuite(entityCount);
        }

        printSummary();
    }

    private void runBenchmarkSuite(int entityCount) {
        System.out.println("\n┌──────────────────────────────────────────────────────────────────────────────┐");
        System.out.printf("│                    BENCHMARK SUITE: %,6d ENTITIES                           │%n", entityCount);
        System.out.println("└──────────────────────────────────────────────────────────────────────────────┘");

        var positions = generatePositions(entityCount);
        var queryPositions = generatePositions(100);

        System.out.println("\nWarming up JIT...");
        warmup(positions);

        benchmarkInsertion(positions, entityCount);
        benchmarkRangeQueries(positions, queryPositions, entityCount);
        benchmarkKNN(positions, queryPositions, entityCount);
        benchmarkMemory(positions, entityCount);
    }

    private void warmup(List<Point3f> positions) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var octree = new Octree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
            var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
            var sfcArray = new SFCArrayIndex<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);

            for (int j = 0; j < Math.min(1000, positions.size()); j++) {
                var pos = positions.get(j);
                octree.insert(pos, TEST_LEVEL, "e" + j);
                tetree.insert(pos, TEST_LEVEL, "e" + j);
                sfcArray.insert(pos, TEST_LEVEL, "e" + j);
            }
        }
    }

    private void benchmarkInsertion(List<Point3f> positions, int entityCount) {
        System.out.println("\n─── INSERTION PERFORMANCE ───");

        var octreeTimes = new long[BENCHMARK_ITERATIONS];
        var tetreeTimes = new long[BENCHMARK_ITERATIONS];
        var sfcTimes = new long[BENCHMARK_ITERATIONS];

        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            var octree = new Octree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
            long start = System.nanoTime();
            for (int i = 0; i < positions.size(); i++) {
                octree.insert(positions.get(i), TEST_LEVEL, "e" + i);
            }
            octreeTimes[iter] = System.nanoTime() - start;

            var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
            start = System.nanoTime();
            for (int i = 0; i < positions.size(); i++) {
                tetree.insert(positions.get(i), TEST_LEVEL, "e" + i);
            }
            tetreeTimes[iter] = System.nanoTime() - start;

            var sfcArray = new SFCArrayIndex<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
            start = System.nanoTime();
            for (int i = 0; i < positions.size(); i++) {
                sfcArray.insert(positions.get(i), TEST_LEVEL, "e" + i);
            }
            sfcTimes[iter] = System.nanoTime() - start;
        }

        printResults("Insertion", entityCount, octreeTimes, tetreeTimes, sfcTimes, "ns/entity");
    }

    private void benchmarkRangeQueries(List<Point3f> positions, List<Point3f> queryPositions, int entityCount) {
        System.out.println("\n─── RANGE QUERY PERFORMANCE (100 queries) ───");

        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
        var sfcArray = new SFCArrayIndex<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);

        for (int i = 0; i < positions.size(); i++) {
            var pos = positions.get(i);
            octree.insert(pos, TEST_LEVEL, "e" + i);
            tetree.insert(pos, TEST_LEVEL, "e" + i);
            sfcArray.insert(pos, TEST_LEVEL, "e" + i);
        }

        float querySize = WORLD_SIZE * 0.1f;

        var octreeTimes = new long[BENCHMARK_ITERATIONS];
        var tetreeTimes = new long[BENCHMARK_ITERATIONS];
        var sfcTimes = new long[BENCHMARK_ITERATIONS];

        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            long start = System.nanoTime();
            for (var queryPos : queryPositions) {
                var region = new Spatial.Cube(queryPos.x, queryPos.y, queryPos.z, querySize);
                octree.entitiesInRegion(region);
            }
            octreeTimes[iter] = System.nanoTime() - start;

            start = System.nanoTime();
            for (var queryPos : queryPositions) {
                var region = new Spatial.Cube(queryPos.x, queryPos.y, queryPos.z, querySize);
                tetree.entitiesInRegion(region);
            }
            tetreeTimes[iter] = System.nanoTime() - start;

            start = System.nanoTime();
            for (var queryPos : queryPositions) {
                var region = new Spatial.Cube(queryPos.x, queryPos.y, queryPos.z, querySize);
                sfcArray.entitiesInRegion(region);
            }
            sfcTimes[iter] = System.nanoTime() - start;
        }

        printResults("Range Query", queryPositions.size(), octreeTimes, tetreeTimes, sfcTimes, "ns/query");
    }

    private void benchmarkKNN(List<Point3f> positions, List<Point3f> queryPositions, int entityCount) {
        System.out.println("\n─── K-NN QUERY PERFORMANCE (k=" + K_NEIGHBORS + ", 100 queries) ───");

        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
        var sfcArray = new SFCArrayIndex<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);

        for (int i = 0; i < positions.size(); i++) {
            var pos = positions.get(i);
            octree.insert(pos, TEST_LEVEL, "e" + i);
            tetree.insert(pos, TEST_LEVEL, "e" + i);
            sfcArray.insert(pos, TEST_LEVEL, "e" + i);
        }

        float maxDistance = WORLD_SIZE;

        var octreeTimes = new long[BENCHMARK_ITERATIONS];
        var tetreeTimes = new long[BENCHMARK_ITERATIONS];
        var sfcTimes = new long[BENCHMARK_ITERATIONS];

        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            long start = System.nanoTime();
            for (var queryPos : queryPositions) {
                octree.kNearestNeighbors(queryPos, K_NEIGHBORS, maxDistance);
            }
            octreeTimes[iter] = System.nanoTime() - start;

            start = System.nanoTime();
            for (var queryPos : queryPositions) {
                tetree.kNearestNeighbors(queryPos, K_NEIGHBORS, maxDistance);
            }
            tetreeTimes[iter] = System.nanoTime() - start;

            start = System.nanoTime();
            for (var queryPos : queryPositions) {
                sfcArray.kNearestNeighbors(queryPos, K_NEIGHBORS, maxDistance);
            }
            sfcTimes[iter] = System.nanoTime() - start;
        }

        printResults("K-NN Query", queryPositions.size(), octreeTimes, tetreeTimes, sfcTimes, "ns/query");
    }

    private void benchmarkMemory(List<Point3f> positions, int entityCount) {
        System.out.println("\n─── MEMORY USAGE ───");

        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        long baseline = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        var octree = new Octree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
        for (int i = 0; i < positions.size(); i++) {
            octree.insert(positions.get(i), TEST_LEVEL, "e" + i);
        }
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        long octreeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() - baseline;
        octree = null;

        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        baseline = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
        for (int i = 0; i < positions.size(); i++) {
            tetree.insert(positions.get(i), TEST_LEVEL, "e" + i);
        }
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        long tetreeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() - baseline;
        tetree = null;

        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        baseline = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        var sfcArray = new SFCArrayIndex<>(new SequentialLongIDGenerator(), 10, TEST_LEVEL);
        for (int i = 0; i < positions.size(); i++) {
            sfcArray.insert(positions.get(i), TEST_LEVEL, "e" + i);
        }
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException e) {}
        long sfcMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() - baseline;

        System.out.printf("  %-15s %,12d bytes  (%,d bytes/entity)%n",
            "Octree:", Math.max(0, octreeMemory), Math.max(0, octreeMemory) / entityCount);
        System.out.printf("  %-15s %,12d bytes  (%,d bytes/entity)%n",
            "Tetree:", Math.max(0, tetreeMemory), Math.max(0, tetreeMemory) / entityCount);
        System.out.printf("  %-15s %,12d bytes  (%,d bytes/entity)%n",
            "SFCArrayIndex:", Math.max(0, sfcMemory), Math.max(0, sfcMemory) / entityCount);
    }

    private List<Point3f> generatePositions(int count) {
        var positions = new ArrayList<Point3f>(count);
        for (int i = 0; i < count; i++) {
            positions.add(new Point3f(
                random.nextFloat() * WORLD_SIZE,
                random.nextFloat() * WORLD_SIZE,
                random.nextFloat() * WORLD_SIZE
            ));
        }
        return positions;
    }

    private void printResults(String operation, int count, long[] octree, long[] tetree, long[] sfc, String unit) {
        long avgOctree = avg(octree);
        long avgTetree = avg(tetree);
        long avgSfc = avg(sfc);
        long fastest = Math.min(Math.min(avgOctree, avgTetree), avgSfc);

        System.out.printf("  %-15s %,12d ns  (%.2f ms)  %.2fx  %,d %s%n",
            "Octree:", avgOctree, avgOctree / 1e6, (double) avgOctree / fastest, avgOctree / count, unit);
        System.out.printf("  %-15s %,12d ns  (%.2f ms)  %.2fx  %,d %s%n",
            "Tetree:", avgTetree, avgTetree / 1e6, (double) avgTetree / fastest, avgTetree / count, unit);
        System.out.printf("  %-15s %,12d ns  (%.2f ms)  %.2fx  %,d %s%n",
            "SFCArrayIndex:", avgSfc, avgSfc / 1e6, (double) avgSfc / fastest, avgSfc / count, unit);
    }

    private void printSummary() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              BENCHMARK SUMMARY                               ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║ Spatial Index Characteristics:                                               ║");
        System.out.println("║   Octree:       Morton SFC, cubic subdivision, tree structure                ║");
        System.out.println("║   Tetree:       Tetrahedral SFC, tetrahedral subdivision, tree structure     ║");
        System.out.println("║   SFCArrayIndex: Morton SFC, flat sorted array, no tree overhead             ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║ LITMAX/BIGMIN Optimization Applied:                                          ║");
        System.out.println("║   Octree:       Yes - direct Morton code intervals                           ║");
        System.out.println("║   Tetree:       Yes - grid-cell Morton → enumerate 6 tets per cell           ║");
        System.out.println("║   SFCArrayIndex: Yes - direct Morton code intervals                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
    }

    private long avg(long[] values) {
        long sum = 0;
        for (var v : values) sum += v;
        return sum / values.length;
    }
}
