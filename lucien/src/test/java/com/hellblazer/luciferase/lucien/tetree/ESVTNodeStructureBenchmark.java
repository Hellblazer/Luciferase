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
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Microbenchmark comparing child type lookup strategies for ESVT node design.
 * Validates H0: Child types can be derived from TetreeConnectivity instead of stored.
 *
 * Compares:
 * - Table lookup: TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[type][child]
 * - Bit extraction: (packedTypes >> (child * 3)) & 0x7
 *
 * @author hal.hildebrand
 */
public class ESVTNodeStructureBenchmark {

    private static final int WARMUP_ITERATIONS = 100_000;
    private static final int BENCHMARK_ITERATIONS = 10_000_000;

    @Test
    public void benchmarkChildTypeLookup() {
        var random = new Random(42);

        // Pre-generate random parent types and child indices
        var parentTypes = new byte[BENCHMARK_ITERATIONS];
        var childIndices = new int[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            parentTypes[i] = (byte) random.nextInt(6);
            childIndices[i] = random.nextInt(8);
        }

        // Warmup
        long warmupSum = 0;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            warmupSum += TetreeConnectivity.getChildType(parentTypes[i], childIndices[i]);
        }
        assertEquals(warmupSum > 0, true); // Prevent dead code elimination

        // Benchmark table lookup
        long startLookup = System.nanoTime();
        long sumLookup = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            sumLookup += TetreeConnectivity.getChildType(parentTypes[i], childIndices[i]);
        }
        long lookupTimeNs = System.nanoTime() - startLookup;

        // Prepare packed type descriptors (simulating 12-byte node storage)
        var packedDescriptors = new int[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            int packed = 0;
            for (int c = 0; c < 8; c++) {
                packed |= (TetreeConnectivity.getChildType(parentTypes[i], c) & 0x7) << (c * 3);
            }
            packedDescriptors[i] = packed;
        }

        // Benchmark bit extraction (simulating stored child types)
        long startExtract = System.nanoTime();
        long sumExtract = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            sumExtract += (packedDescriptors[i] >> (childIndices[i] * 3)) & 0x7;
        }
        long extractTimeNs = System.nanoTime() - startExtract;

        // Verify correctness
        assertEquals(sumLookup, sumExtract, "Both methods should produce same results");

        // Report results
        double lookupNsPerOp = (double) lookupTimeNs / BENCHMARK_ITERATIONS;
        double extractNsPerOp = (double) extractTimeNs / BENCHMARK_ITERATIONS;
        double lookupThroughput = 1e9 / lookupNsPerOp; // ops per second
        double extractThroughput = 1e9 / extractNsPerOp;

        System.out.println("=== ESVT Node Structure Benchmark ===");
        System.out.println("Iterations: " + BENCHMARK_ITERATIONS);
        System.out.println();
        System.out.println("Table Lookup (8-byte node - derive child types):");
        System.out.printf("  Time per op: %.2f ns%n", lookupNsPerOp);
        System.out.printf("  Throughput: %.0f M ops/sec%n", lookupThroughput / 1e6);
        System.out.println();
        System.out.println("Bit Extraction (12-byte node - stored child types):");
        System.out.printf("  Time per op: %.2f ns%n", extractNsPerOp);
        System.out.printf("  Throughput: %.0f M ops/sec%n", extractThroughput / 1e6);
        System.out.println();
        System.out.printf("Difference: %.2f ns/op (%.1fx slower for lookup)%n",
                          lookupNsPerOp - extractNsPerOp,
                          lookupNsPerOp / extractNsPerOp);
        System.out.println();

        // Context: impact on ray traversal
        System.out.println("=== Impact Analysis ===");
        double typicalLookups = 20; // per ray
        double extraCostPerRay = (lookupNsPerOp - extractNsPerOp) * typicalLookups;
        System.out.printf("Typical lookups per ray: %.0f%n", typicalLookups);
        System.out.printf("Extra cost per ray: %.0f ns%n", extraCostPerRay);
        System.out.println();

        // Memory savings calculation
        System.out.println("=== Memory Savings ===");
        System.out.println("Node size: 12 bytes -> 8 bytes (33% reduction)");
        System.out.println("For 1M nodes: 12MB -> 8MB (4MB saved)");
        System.out.println("For 10M nodes: 120MB -> 80MB (40MB saved)");
        System.out.println();

        // Final recommendation
        System.out.println("=== RECOMMENDATION ===");
        if (lookupNsPerOp < 10) {
            System.out.println("Table lookup is fast enough (<10ns). USE 8-BYTE NODES.");
        } else if (lookupNsPerOp < 20) {
            System.out.println("Table lookup is acceptable (<20ns). Consider 8-byte nodes.");
        } else {
            System.out.println("Table lookup may be too slow. Consider storing types.");
        }
    }

    @Test
    public void testTableFitsInCacheLine() {
        // Verify the lookup table fits in a single L1 cache line (64 bytes)
        int tableSize = TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE.length *
                        TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[0].length;

        System.out.println("PARENT_TYPE_TO_CHILD_TYPE table:");
        System.out.println("  Dimensions: 6 x 8");
        System.out.println("  Size: " + tableSize + " bytes");
        System.out.println("  L1 cache lines needed: " + (int) Math.ceil(tableSize / 64.0));

        assertTrue(tableSize <= 64, "Table should fit in single cache line");
    }

    @Test
    public void testSimulatedRayTraversal() {
        var random = new Random(42);
        int numRays = 100_000;
        int avgTraversalDepth = 15; // typical for 21-level tree

        // Simulate ray traversal with table lookup
        long startLookup = System.nanoTime();
        long sumLookup = 0;
        for (int ray = 0; ray < numRays; ray++) {
            byte currentType = 0; // Start at root (type 0)
            for (int depth = 0; depth < avgTraversalDepth; depth++) {
                int childIndex = random.nextInt(8);
                currentType = TetreeConnectivity.getChildType(currentType, childIndex);
                sumLookup += currentType;
            }
        }
        long lookupTimeNs = System.nanoTime() - startLookup;

        double nsPerRay = (double) lookupTimeNs / numRays;
        double nsPerLookup = (double) lookupTimeNs / (numRays * avgTraversalDepth);

        System.out.println("=== Simulated Ray Traversal ===");
        System.out.println("Rays: " + numRays);
        System.out.println("Average depth: " + avgTraversalDepth);
        System.out.println("Total lookups: " + (numRays * avgTraversalDepth));
        System.out.printf("Time per ray: %.0f ns%n", nsPerRay);
        System.out.printf("Time per lookup: %.2f ns%n", nsPerLookup);
        System.out.printf("Checksum: %d%n", sumLookup);

        // This is acceptable overhead for ray traversal
        assertTrue(nsPerRay < 1000, "Ray traversal overhead should be < 1us");
    }
}
