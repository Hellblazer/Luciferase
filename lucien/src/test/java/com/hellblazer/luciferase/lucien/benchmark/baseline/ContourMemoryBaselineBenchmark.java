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
package com.hellblazer.luciferase.lucien.benchmark.baseline;

import com.hellblazer.luciferase.esvo.core.ESVONode;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Baseline benchmark for contour normal memory usage (Epic 3).
 * 
 * Measures the memory footprint of contour data in ESVO nodes to establish
 * baseline metrics before optimization. Current implementation uses 4 bytes
 * per node for contour descriptor (24-bit pointer + 8-bit mask).
 * 
 * Epic 3 goal: Optimize contour memory representation for reduced bandwidth
 * and improved cache efficiency.
 * 
 * @author hal.hildebrand
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
public class ContourMemoryBaselineBenchmark {

    @Param({"10000", "100000", "1000000"})
    private int nodeCount;

    private List<ESVONode> nodes;
    private byte[] contourMasks;
    private int[] contourPointers;
    
    private static final int CONTOUR_MASK_BITS = 0xFF;
    private static final int CONTOUR_PTR_SHIFT = 8;

    @Setup
    public void setup() {
        System.out.println("\n=== Contour Memory Baseline Benchmark Setup ===");
        System.out.println("Node count: " + String.format("%,d", nodeCount));
        
        nodes = new ArrayList<>(nodeCount);
        contourMasks = new byte[nodeCount];
        contourPointers = new int[nodeCount];
        
        var random = new Random(42); // Fixed seed for reproducibility
        
        // Generate nodes with contour data
        for (int i = 0; i < nodeCount; i++) {
            // Generate random contour mask (8 bits - which faces have contours)
            byte contourMask = (byte) random.nextInt(256);
            
            // Generate random contour pointer (24 bits - offset to contour data)
            int contourPtr = random.nextInt(1 << 24); // 24-bit pointer
            
            // Pack into contour descriptor: [ptr(24)|mask(8)]
            int contourDescriptor = (contourPtr << CONTOUR_PTR_SHIFT) | (contourMask & CONTOUR_MASK_BITS);
            
            // Create node with contour data
            var node = new ESVONode(0, contourDescriptor);
            nodes.add(node);
            
            // Store for unpacking benchmarks
            contourMasks[i] = contourMask;
            contourPointers[i] = contourPtr;
        }
        
        // Calculate memory usage
        long baselineMemory = (long) nodeCount * 4; // 4 bytes per contour descriptor
        System.out.println("Baseline contour memory: " + String.format("%,d", baselineMemory) + " bytes");
        System.out.println("Per-node overhead: 4 bytes (32-bit descriptor)");
        System.out.println("Setup complete\n");
    }

    /**
     * Benchmark contour descriptor packing.
     * Measures throughput of encoding contour mask + pointer into 32-bit descriptor.
     */
    @Benchmark
    public void benchmarkContourPacking(Blackhole blackhole) {
        for (int i = 0; i < nodeCount; i++) {
            int descriptor = (contourPointers[i] << CONTOUR_PTR_SHIFT) | 
                           (contourMasks[i] & CONTOUR_MASK_BITS);
            blackhole.consume(descriptor);
        }
    }

    /**
     * Benchmark contour mask extraction.
     * Measures throughput of extracting 8-bit contour mask from descriptor.
     */
    @Benchmark
    public void benchmarkContourMaskExtraction(Blackhole blackhole) {
        for (var node : nodes) {
            int mask = node.getContourMask();
            blackhole.consume(mask);
        }
    }

    /**
     * Benchmark contour pointer extraction.
     * Measures throughput of extracting 24-bit contour pointer from descriptor.
     */
    @Benchmark
    public void benchmarkContourPointerExtraction(Blackhole blackhole) {
        for (var node : nodes) {
            int ptr = node.getContourPointer();
            blackhole.consume(ptr);
        }
    }

    /**
     * Benchmark full contour descriptor round-trip.
     * Measures combined packing and unpacking overhead.
     */
    @Benchmark
    public void benchmarkContourRoundTrip(Blackhole blackhole) {
        for (int i = 0; i < nodeCount; i++) {
            // Pack
            int descriptor = (contourPointers[i] << CONTOUR_PTR_SHIFT) | 
                           (contourMasks[i] & CONTOUR_MASK_BITS);
            
            // Unpack mask
            int mask = descriptor & CONTOUR_MASK_BITS;
            
            // Unpack pointer
            int ptr = (descriptor >>> CONTOUR_PTR_SHIFT) & 0xFFFFFF;
            
            blackhole.consume(mask + ptr);
        }
    }

    /**
     * Benchmark contour data access patterns.
     * Simulates typical ray traversal accessing contour data.
     */
    @Benchmark
    public void benchmarkContourAccessPattern(Blackhole blackhole) {
        var random = new Random(42);
        int accessCount = Math.min(10000, nodeCount);
        
        for (int i = 0; i < accessCount; i++) {
            int idx = random.nextInt(nodeCount);
            var node = nodes.get(idx);
            
            // Typical access: check if face has contour, then get pointer
            int mask = node.getContourMask();
            if (mask != 0) {
                int ptr = node.getContourPointer();
                blackhole.consume(ptr);
            }
        }
    }

    /**
     * Measure memory footprint of current contour representation.
     * Reports bytes per node and total memory usage.
     */
    @TearDown(Level.Iteration)
    public void reportMemoryUsage() {
        long descriptorMemory = (long) nodeCount * 4; // 4 bytes per descriptor
        
        System.out.printf("Iteration complete - Memory usage:%n");
        System.out.printf("  Nodes: %,d%n", nodeCount);
        System.out.printf("  Contour descriptors: %,d bytes (4 bytes/node)%n", descriptorMemory);
        System.out.printf("  Contour masks: %,d bytes (1 byte/node)%n", nodeCount);
        System.out.printf("  Contour pointers: %,d bytes (3 bytes logical/node)%n", nodeCount * 3);
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
