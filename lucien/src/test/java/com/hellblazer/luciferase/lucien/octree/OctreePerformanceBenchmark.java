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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Performance benchmarking tests comparing: 1. Octree (multiple entities per node) - using direct API 2.
 * OctreeWithEntitiesSpatialIndexAdapter (compatibility layer)
 *
 * @author hal.hildebrand
 */
@org.junit.jupiter.api.Disabled("This test uses OctreeWithEntitiesSpatialIndexAdapter which is being removed")
public class OctreePerformanceBenchmark {

    private static final int  WARMUP_ITERATIONS    = 100;
    private static final int  BENCHMARK_ITERATIONS = 1000;
    private static final int  ENTITY_COUNT         = 10000;
    private static final byte TEST_LEVEL           = 15;

    private Octree<LongEntityID, TestPayload> entityOctree;

    private List<Point3f>     testPositions;
    private List<TestPayload> testPayloads;
    private Random            random;

    @Test
    void benchmarkInsertionPerformance() {
        System.out.println("\n=== INSERTION PERFORMANCE BENCHMARK ===");

        // Warmup
        warmupInsertion();

        // Direct entity octree insertion as baseline
        long directTime = benchmarkDirectInsertion();
        System.out.printf("Octree (direct): %d ms for %d insertions (%.2f μs/op)%n", directTime, ENTITY_COUNT,
                          (directTime * 1000.0) / ENTITY_COUNT);

        // Benchmark adapter interface
        long adapterTime = 0; // benchmarkAdapterInsertion();
        // System.out.printf("OctreeWithEntitiesSpatialIndexAdapter: %d ms for %d insertions (%.2f μs/op)%n",
        //                   adapterTime, ENTITY_COUNT, (adapterTime * 1000.0) / ENTITY_COUNT);

        // Performance ratios
        // System.out.printf("\nPerformance Ratios:%n");
        // System.out.printf("Adapter vs Direct: %.2fx %s%n",
        //                   (double) adapterTime / directTime,
        //                   adapterTime <= directTime * 1.5 ? "✓ (within 50% overhead target)" : "✗");
    }

    @Test
    void benchmarkLookupPerformance() {
        System.out.println("\n=== LOOKUP PERFORMANCE BENCHMARK ===");

        // Populate octrees
        populateAllOctrees();

        // Select random positions for lookup
        List<Point3f> lookupPositions = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            lookupPositions.add(testPositions.get(random.nextInt(testPositions.size())));
        }

        // Warmup
        warmupLookup(lookupPositions);

        // Benchmark direct entity lookups
        long directTime = benchmarkDirectLookup(lookupPositions);
        System.out.printf("Octree (direct): %d ms for %d lookups (%.2f μs/op)%n", directTime, lookupPositions.size(),
                          (directTime * 1000.0) / lookupPositions.size());

        // Benchmark adapter lookups
        // long adapterTime = benchmarkAdapterLookup(lookupPositions);
        // System.out.printf("OctreeWithEntitiesSpatialIndexAdapter: %d ms for %d lookups (%.2f μs/op)%n",
        //                   adapterTime, lookupPositions.size(),
        //                   (adapterTime * 1000.0) / lookupPositions.size());

        // Performance ratios
        // System.out.printf("\nPerformance Ratios:%n");
        // System.out.printf("Adapter vs Direct: %.2fx%n", (double) adapterTime / directTime);
    }

    @Test
    void benchmarkMemoryUsage() {
        System.out.println("\n=== MEMORY USAGE ANALYSIS ===");

        // Force GC before measurement
        System.gc();
        long beforeMemory = getUsedMemory();

        // Populate entity octree directly
        Octree<LongEntityID, TestPayload> directOctree = new Octree<>(new SequentialLongIDGenerator());
        for (int i = 0; i < ENTITY_COUNT; i++) {
            directOctree.insert(testPositions.get(i), TEST_LEVEL, testPayloads.get(i));
        }
        System.gc();
        long directMemory = getUsedMemory() - beforeMemory;

        // Clear and measure adapter octree
        directOctree = null;
        System.gc();
        beforeMemory = getUsedMemory();

        // OctreeWithEntitiesSpatialIndexAdapter<LongEntityID, TestPayload> adapterOctree =
        //     new OctreeWithEntitiesSpatialIndexAdapter<>(new SequentialLongIDGenerator());
        // for (int i = 0; i < ENTITY_COUNT; i++) {
        //     adapterOctree.insert(testPositions.get(i), TEST_LEVEL, testPayloads.get(i));
        // }
        // System.gc();
        // long adapterMemory = getUsedMemory() - beforeMemory;

        System.out.printf("Octree (direct): %.2f MB%n", directMemory / (1024.0 * 1024.0));
        // System.out.printf("OctreeWithEntitiesSpatialIndexAdapter: %.2f MB%n", adapterMemory / (1024.0 * 1024.0));
        // System.out.printf("Adapter overhead: %.2fx%n", (double) adapterMemory / directMemory);

        // Show stats from adapter
        // var stats = adapterOctree.getStats();
        // System.out.printf("\nOctreeWithEntitiesSpatialIndexAdapter Stats:%n");
        // System.out.printf("  Total nodes: %d%n", stats.totalNodes());
        // System.out.printf("  Total entities: %d%n", stats.totalEntities());
    }

    @Test
    void benchmarkSpatialQueryPerformance() {
        System.out.println("\n=== SPATIAL QUERY PERFORMANCE BENCHMARK ===");

        // Populate octrees
        populateAllOctrees();

        // Generate test regions
        List<Spatial.Cube> testRegions = generateTestRegions(100);

        // Warmup
        warmupSpatialQuery(testRegions);

        // Benchmark direct entity queries
        long directTime = benchmarkDirectSpatialQuery(testRegions);
        System.out.printf("Octree (direct): %d ms for %d queries (%.2f ms/op)%n", directTime, testRegions.size(),
                          (double) directTime / testRegions.size());

        // Benchmark adapter queries
        // long adapterTime = benchmarkAdapterSpatialQuery(testRegions);
        // System.out.printf("OctreeWithEntitiesSpatialIndexAdapter: %d ms for %d queries (%.2f ms/op)%n",
        //                   adapterTime, testRegions.size(),
        //                   (double) adapterTime / testRegions.size());

        // Performance ratios
        // System.out.printf("\nPerformance Ratios:%n");
        // System.out.printf("Adapter vs Direct: %.2fx %s%n",
        //                   (double) adapterTime / directTime,
        //                   adapterTime <= directTime * 1.5 ? "✓ (within 50% overhead target)" : "✗");
    }

    @BeforeEach
    void setUp() {
        entityOctree = new Octree<>(new SequentialLongIDGenerator());
        // adapterOctree = new OctreeWithEntitiesSpatialIndexAdapter<>(new SequentialLongIDGenerator());

        random = new Random(42); // Fixed seed for reproducibility
        generateTestData();
    }

    private long benchmarkDirectInsertion() {
        long start = System.currentTimeMillis();
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            Octree<LongEntityID, TestPayload> octree = new Octree<>(new SequentialLongIDGenerator());
            for (int i = 0; i < ENTITY_COUNT / BENCHMARK_ITERATIONS; i++) {
                int idx = (iter * (ENTITY_COUNT / BENCHMARK_ITERATIONS) + i) % ENTITY_COUNT;
                octree.insert(testPositions.get(idx), TEST_LEVEL, testPayloads.get(idx));
            }
        }
        return System.currentTimeMillis() - start;
    }

    private long benchmarkDirectLookup(List<Point3f> lookupPositions) {
        long start = System.currentTimeMillis();
        int found = 0;
        for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
            for (Point3f pos : lookupPositions) {
                List<LongEntityID> ids = entityOctree.lookup(pos, TEST_LEVEL);
                if (!ids.isEmpty()) {
                    found++;
                }
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("  Found: %d/%d lookups%n", found, lookupPositions.size() * BENCHMARK_ITERATIONS);
        return elapsed;
    }

    private long benchmarkDirectSpatialQuery(List<Spatial.Cube> regions) {
        long start = System.currentTimeMillis();
        int totalFound = 0;
        for (Spatial.Cube region : regions) {
            List<LongEntityID> results = entityOctree.entitiesInRegion(region);
            totalFound += results.size();
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("  Total entities found: %d%n", totalFound);
        return elapsed;
    }

    private void generateTestData() {
        testPositions = new ArrayList<>(ENTITY_COUNT);
        testPayloads = new ArrayList<>(ENTITY_COUNT);

        // Generate clustered data to test real-world scenarios
        int clusters = 10;
        int entitiesPerCluster = ENTITY_COUNT / clusters;

        for (int c = 0; c < clusters; c++) {
            // Random cluster center
            float centerX = random.nextFloat() * 10000;
            float centerY = random.nextFloat() * 10000;
            float centerZ = random.nextFloat() * 10000;

            for (int i = 0; i < entitiesPerCluster; i++) {
                // Generate points around cluster center
                float x = centerX + (random.nextFloat() - 0.5f) * 1000;
                float y = centerY + (random.nextFloat() - 0.5f) * 1000;
                float z = centerZ + (random.nextFloat() - 0.5f) * 1000;

                testPositions.add(new Point3f(x, y, z));
                testPayloads.add(new TestPayload("Entity_" + (c * entitiesPerCluster + i), i));
            }
        }
    }

    private List<Spatial.Cube> generateTestRegions(int count) {
        List<Spatial.Cube> regions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * 9000;
            float y = random.nextFloat() * 9000;
            float z = random.nextFloat() * 9000;
            float size = 500 + random.nextFloat() * 1500; // Variable size regions
            regions.add(new Spatial.Cube(x, y, z, size));
        }
        return regions;
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private void populateAllOctrees() {
        entityOctree = new Octree<>(new SequentialLongIDGenerator());
        // adapterOctree = new OctreeWithEntitiesSpatialIndexAdapter<>(new SequentialLongIDGenerator());

        for (int i = 0; i < ENTITY_COUNT; i++) {
            entityOctree.insert(testPositions.get(i), TEST_LEVEL, testPayloads.get(i));
            // adapterOctree.insert(testPositions.get(i), TEST_LEVEL, testPayloads.get(i));
        }
    }

    // private long benchmarkAdapterInsertion() {
    //     long start = System.currentTimeMillis();
    //     for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
    //         OctreeWithEntitiesSpatialIndexAdapter<LongEntityID, TestPayload> adapter = 
    //             new OctreeWithEntitiesSpatialIndexAdapter<>(new SequentialLongIDGenerator());
    //         for (int i = 0; i < ENTITY_COUNT / BENCHMARK_ITERATIONS; i++) {
    //             int idx = (iter * (ENTITY_COUNT / BENCHMARK_ITERATIONS) + i) % ENTITY_COUNT;
    //             adapter.insert(testPositions.get(idx), TEST_LEVEL, testPayloads.get(idx));
    //         }
    //     }
    //     return System.currentTimeMillis() - start;
    // }

    private void warmupInsertion() {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Octree<LongEntityID, TestPayload> warmup = new Octree<>(new SequentialLongIDGenerator());
            for (int j = 0; j < 100; j++) {
                warmup.insert(testPositions.get(j), TEST_LEVEL, testPayloads.get(j));
            }
        }
    }

    // private long benchmarkAdapterLookup(List<Point3f> lookupPositions) {
    //     long start = System.currentTimeMillis();
    //     int found = 0;
    //     for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
    //         for (Point3f pos : lookupPositions) {
    //             TestPayload result = adapterOctree.lookup(pos, TEST_LEVEL);
    //             if (result != null) found++;
    //         }
    //     }
    //     long elapsed = System.currentTimeMillis() - start;
    //     System.out.printf("  Found: %d/%d lookups%n", found, lookupPositions.size() * BENCHMARK_ITERATIONS);
    //     return elapsed;
    // }

    private void warmupLookup(List<Point3f> lookupPositions) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (Point3f pos : lookupPositions.subList(0, Math.min(10, lookupPositions.size()))) {
                entityOctree.lookup(pos, TEST_LEVEL);
                // adapterOctree.lookup(pos, TEST_LEVEL);
            }
        }
    }

    // private long benchmarkAdapterSpatialQuery(List<Spatial.Cube> regions) {
    //     long start = System.currentTimeMillis();
    //     int totalFound = 0;
    //     for (Spatial.Cube region : regions) {
    //         var results = adapterOctree.boundedBy(region);
    //         totalFound += results.toList().size();
    //     }
    //     long elapsed = System.currentTimeMillis() - start;
    //     System.out.printf("  Total entities found: %d%n", totalFound);
    //     return elapsed;
    // }

    private void warmupSpatialQuery(List<Spatial.Cube> regions) {
        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            entityOctree.entitiesInRegion(regions.get(0));
            // adapterOctree.boundedBy(regions.get(0));
        }
    }

    private static class TestPayload {
        final String name;
        final int    value;

        TestPayload(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }
}
