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
package com.hellblazer.luciferase.lucien.sfc;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Baseline benchmark comparing Octree vs SFCArrayIndex performance.
 *
 * This benchmark tests the Fork and Simplify hypothesis:
 * Does removing tree overhead provide meaningful performance gains?
 *
 * Target: SFCArrayIndex should be ≥2x faster for insertions (stretch: 4.5x)
 *
 * @author hal.hildebrand
 */
public class OctreeVsSFCArrayBenchmark {

    private static final int WARMUP_ITERATIONS = 3;
    private static final int BENCHMARK_ITERATIONS = 5;
    private static final int ENTITY_COUNT = 10000;
    private static final byte LEVEL = (byte) 10;

    private List<Point3f> testPositions;
    private List<String> testContents;
    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42); // Fixed seed for reproducibility
        testPositions = new ArrayList<>(ENTITY_COUNT);
        testContents = new ArrayList<>(ENTITY_COUNT);

        for (var i = 0; i < ENTITY_COUNT; i++) {
            testPositions.add(new Point3f(
                random.nextFloat() * 10000,
                random.nextFloat() * 10000,
                random.nextFloat() * 10000
            ));
            testContents.add("Entity" + i);
        }
    }

    // ===== Insertion Benchmarks =====

    @Test
    void benchmarkInsertionPerformance() {
        System.out.println("\n=== INSERTION BENCHMARK ===");
        System.out.println("Entities: " + ENTITY_COUNT);
        System.out.println();

        // Warmup
        for (var i = 0; i < WARMUP_ITERATIONS; i++) {
            runInsertionOctree();
            runInsertionSFCArray();
        }

        // Benchmark
        var octreeTimes = new long[BENCHMARK_ITERATIONS];
        var sfcTimes = new long[BENCHMARK_ITERATIONS];

        for (var i = 0; i < BENCHMARK_ITERATIONS; i++) {
            octreeTimes[i] = runInsertionOctree();
            sfcTimes[i] = runInsertionSFCArray();
        }

        var avgOctree = average(octreeTimes);
        var avgSFC = average(sfcTimes);
        var speedup = (double) avgOctree / avgSFC;

        System.out.printf("Octree avg:       %,d ns (%.2f ms)%n", avgOctree, avgOctree / 1_000_000.0);
        System.out.printf("SFCArrayIndex avg: %,d ns (%.2f ms)%n", avgSFC, avgSFC / 1_000_000.0);
        System.out.printf("Speedup: %.2fx%n", speedup);
        System.out.println();

        if (speedup >= 2.0) {
            System.out.println("✓ TARGET MET: ≥2x faster insertions");
        } else if (speedup >= 1.0) {
            System.out.println("△ SFCArrayIndex is faster but below 2x target");
        } else {
            System.out.println("✗ Octree is faster - hypothesis may need revision");
        }
    }

    @Test
    void benchmarkBatchInsertionPerformance() {
        System.out.println("\n=== BATCH INSERTION BENCHMARK ===");
        System.out.println("Entities: " + ENTITY_COUNT);
        System.out.println();

        // Warmup
        for (var i = 0; i < WARMUP_ITERATIONS; i++) {
            runBatchInsertionOctree();
            runBatchInsertionSFCArray();
        }

        // Benchmark
        var octreeTimes = new long[BENCHMARK_ITERATIONS];
        var sfcTimes = new long[BENCHMARK_ITERATIONS];

        for (var i = 0; i < BENCHMARK_ITERATIONS; i++) {
            octreeTimes[i] = runBatchInsertionOctree();
            sfcTimes[i] = runBatchInsertionSFCArray();
        }

        var avgOctree = average(octreeTimes);
        var avgSFC = average(sfcTimes);
        var speedup = (double) avgOctree / avgSFC;

        System.out.printf("Octree batch avg:       %,d ns (%.2f ms)%n", avgOctree, avgOctree / 1_000_000.0);
        System.out.printf("SFCArrayIndex batch avg: %,d ns (%.2f ms)%n", avgSFC, avgSFC / 1_000_000.0);
        System.out.printf("Speedup: %.2fx%n", speedup);
    }

    // ===== Range Query Benchmarks =====

    @Test
    void benchmarkRangeQueryPerformance() {
        System.out.println("\n=== RANGE QUERY BENCHMARK ===");

        // Setup: insert entities first
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var sfcIndex = new SFCArrayIndex<LongEntityID, String>(new SequentialLongIDGenerator());

        for (var i = 0; i < ENTITY_COUNT; i++) {
            octree.insert(testPositions.get(i), LEVEL, testContents.get(i));
            sfcIndex.insert(testPositions.get(i), LEVEL, testContents.get(i));
        }

        // Generate query regions
        var queryCount = 100;
        var queries = new ArrayList<Spatial.Cube>(queryCount);
        for (var i = 0; i < queryCount; i++) {
            var x = random.nextFloat() * 8000;
            var y = random.nextFloat() * 8000;
            var z = random.nextFloat() * 8000;
            var size = random.nextFloat() * 1000 + 100;
            queries.add(new Spatial.Cube((int) x, (int) y, (int) z, (int) size));
        }

        // Warmup
        for (var i = 0; i < WARMUP_ITERATIONS; i++) {
            for (var query : queries) {
                octree.entitiesInRegion(query);
                sfcIndex.entitiesInRegion(query);
            }
        }

        // Benchmark
        var octreeTimes = new long[BENCHMARK_ITERATIONS];
        var sfcTimes = new long[BENCHMARK_ITERATIONS];

        for (var iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            var start = System.nanoTime();
            for (var query : queries) {
                octree.entitiesInRegion(query);
            }
            octreeTimes[iter] = System.nanoTime() - start;

            start = System.nanoTime();
            for (var query : queries) {
                sfcIndex.entitiesInRegion(query);
            }
            sfcTimes[iter] = System.nanoTime() - start;
        }

        var avgOctree = average(octreeTimes);
        var avgSFC = average(sfcTimes);
        var ratio = (double) avgSFC / avgOctree;

        System.out.printf("Queries: %d per iteration%n", queryCount);
        System.out.printf("Octree avg:       %,d ns (%.2f ms)%n", avgOctree, avgOctree / 1_000_000.0);
        System.out.printf("SFCArrayIndex avg: %,d ns (%.2f ms)%n", avgSFC, avgSFC / 1_000_000.0);
        System.out.printf("Ratio: %.2fx (SFC/Octree)%n", ratio);
        System.out.println();

        if (ratio <= 1.2) {
            System.out.println("✓ TARGET MET: Query performance within 20% of Octree");
        } else {
            System.out.println("△ Query performance needs improvement");
        }
    }

    // ===== k-NN Benchmarks =====

    @Test
    void benchmarkKNNPerformance() {
        System.out.println("\n=== k-NN BENCHMARK ===");

        // Setup
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var sfcIndex = new SFCArrayIndex<LongEntityID, String>(new SequentialLongIDGenerator());

        for (var i = 0; i < ENTITY_COUNT; i++) {
            octree.insert(testPositions.get(i), LEVEL, testContents.get(i));
            sfcIndex.insert(testPositions.get(i), LEVEL, testContents.get(i));
        }

        // Generate query points
        var queryCount = 100;
        var k = 10;
        var maxDistance = 1000.0f;
        var queryPoints = new ArrayList<Point3f>(queryCount);
        for (var i = 0; i < queryCount; i++) {
            queryPoints.add(new Point3f(
                random.nextFloat() * 10000,
                random.nextFloat() * 10000,
                random.nextFloat() * 10000
            ));
        }

        // Warmup
        for (var i = 0; i < WARMUP_ITERATIONS; i++) {
            for (var query : queryPoints) {
                octree.kNearestNeighbors(query, k, maxDistance);
                sfcIndex.kNearestNeighbors(query, k, maxDistance);
            }
        }

        // Benchmark
        var octreeTimes = new long[BENCHMARK_ITERATIONS];
        var sfcTimes = new long[BENCHMARK_ITERATIONS];

        for (var iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            var start = System.nanoTime();
            for (var query : queryPoints) {
                octree.kNearestNeighbors(query, k, maxDistance);
            }
            octreeTimes[iter] = System.nanoTime() - start;

            start = System.nanoTime();
            for (var query : queryPoints) {
                sfcIndex.kNearestNeighbors(query, k, maxDistance);
            }
            sfcTimes[iter] = System.nanoTime() - start;
        }

        var avgOctree = average(octreeTimes);
        var avgSFC = average(sfcTimes);
        var ratio = (double) avgSFC / avgOctree;

        System.out.printf("k=%d, maxDistance=%.0f, queries=%d per iteration%n", k, maxDistance, queryCount);
        System.out.printf("Octree avg:       %,d ns (%.2f ms)%n", avgOctree, avgOctree / 1_000_000.0);
        System.out.printf("SFCArrayIndex avg: %,d ns (%.2f ms)%n", avgSFC, avgSFC / 1_000_000.0);
        System.out.printf("Ratio: %.2fx (SFC/Octree)%n", ratio);
    }

    // ===== Memory Usage =====

    @Test
    void benchmarkMemoryUsage() {
        System.out.println("\n=== MEMORY USAGE BENCHMARK ===");

        // Force GC and get baseline
        System.gc();
        var runtime = Runtime.getRuntime();
        var beforeOctree = runtime.totalMemory() - runtime.freeMemory();

        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        for (var i = 0; i < ENTITY_COUNT; i++) {
            octree.insert(testPositions.get(i), LEVEL, testContents.get(i));
        }
        var afterOctree = runtime.totalMemory() - runtime.freeMemory();
        var octreeMemory = afterOctree - beforeOctree;

        // Clear and measure SFCArrayIndex
        octree = null;
        System.gc();
        var beforeSFC = runtime.totalMemory() - runtime.freeMemory();

        var sfcIndex = new SFCArrayIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        for (var i = 0; i < ENTITY_COUNT; i++) {
            sfcIndex.insert(testPositions.get(i), LEVEL, testContents.get(i));
        }
        var afterSFC = runtime.totalMemory() - runtime.freeMemory();
        var sfcMemory = afterSFC - beforeSFC;

        var ratio = (double) sfcMemory / octreeMemory;

        System.out.printf("Entities: %,d%n", ENTITY_COUNT);
        System.out.printf("Octree memory:       %,d bytes (%.2f MB)%n", octreeMemory, octreeMemory / 1_000_000.0);
        System.out.printf("SFCArrayIndex memory: %,d bytes (%.2f MB)%n", sfcMemory, sfcMemory / 1_000_000.0);
        System.out.printf("Ratio: %.2fx (SFC/Octree)%n", ratio);
        System.out.println();

        System.out.println("Octree stats: " + octree);
        System.out.println("SFC stats: " + sfcIndex.getStats());
    }

    // ===== cells(Q) Interval Count Analysis =====

    @Test
    void analyzeCellsQIntervalCount() {
        System.out.println("\n=== cells(Q) INTERVAL COUNT ANALYSIS ===");

        var sfcIndex = new SFCArrayIndex<LongEntityID, String>(new SequentialLongIDGenerator());

        var intervalCounts = new int[9]; // 0-8 intervals
        var queryCount = 1000;

        for (var i = 0; i < queryCount; i++) {
            var minX = random.nextFloat() * 9000;
            var minY = random.nextFloat() * 9000;
            var minZ = random.nextFloat() * 9000;
            var width = random.nextFloat() * 500 + 10;
            var height = random.nextFloat() * 500 + 10;
            var depth = random.nextFloat() * 500 + 10;

            var bounds = new VolumeBounds(minX, minY, minZ,
                                          minX + width, minY + height, minZ + depth);
            var intervals = sfcIndex.cellsQ(bounds, LEVEL);
            var count = Math.min(intervals.size(), 8);
            intervalCounts[count]++;
        }

        System.out.printf("Tested %d random 3D query regions%n%n", queryCount);
        System.out.println("Interval count distribution:");
        for (var i = 1; i <= 8; i++) {
            var percentage = (intervalCounts[i] * 100.0) / queryCount;
            System.out.printf("  %d intervals: %d (%.1f%%)%n", i, intervalCounts[i], percentage);
        }

        var underEight = 0;
        for (var i = 1; i <= 8; i++) {
            underEight += intervalCounts[i];
        }
        var percentUnderEight = (underEight * 100.0) / queryCount;
        System.out.printf("%n%.1f%% of queries use ≤8 intervals%n", percentUnderEight);

        if (percentUnderEight >= 95) {
            System.out.println("✓ TARGET MET: ≥95% queries use ≤8 intervals");
        }
    }

    // ===== Helper Methods =====

    private long runInsertionOctree() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var start = System.nanoTime();
        for (var i = 0; i < ENTITY_COUNT; i++) {
            octree.insert(testPositions.get(i), LEVEL, testContents.get(i));
        }
        return System.nanoTime() - start;
    }

    private long runInsertionSFCArray() {
        var sfcIndex = new SFCArrayIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        var start = System.nanoTime();
        for (var i = 0; i < ENTITY_COUNT; i++) {
            sfcIndex.insert(testPositions.get(i), LEVEL, testContents.get(i));
        }
        return System.nanoTime() - start;
    }

    private long runBatchInsertionOctree() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var start = System.nanoTime();
        octree.insertBatch(testPositions, testContents, LEVEL);
        return System.nanoTime() - start;
    }

    private long runBatchInsertionSFCArray() {
        var sfcIndex = new SFCArrayIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        var start = System.nanoTime();
        sfcIndex.insertBatch(testPositions, testContents, LEVEL);
        return System.nanoTime() - start;
    }

    private long average(long[] values) {
        var sum = 0L;
        for (var v : values) {
            sum += v;
        }
        return sum / values.length;
    }
}
