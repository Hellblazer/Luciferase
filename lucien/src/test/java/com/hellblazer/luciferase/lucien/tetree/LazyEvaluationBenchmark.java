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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Performance benchmarks for lazy evaluation mechanisms.
 * Compares lazy vs eager approaches for various operations.
 *
 * @author hal.hildebrand
 */
public class LazyEvaluationBenchmark {
    
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10000;
    private static final byte TEST_LEVEL = 10;  // Use higher level for smaller cells
    
    private Tetree<LongEntityID, String> tetree;
    
    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        
        // Populate with test data in a grid pattern
        // Use grid-aligned positions based on cell size
        int cellSize = 1 << (21 - TEST_LEVEL);  // Cell size at TEST_LEVEL
        int gridSize = 10;
        
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    tetree.insert(
                        new Point3f(x * cellSize, y * cellSize, z * cellSize),
                        TEST_LEVEL,
                        String.format("Entity_%d_%d_%d", x, y, z)
                    );
                }
            }
        }
        
        System.out.println("Setup complete: " + tetree.entityCount() + " entities in " + tetree.nodeCount() + " nodes");
    }
    
    @Test
    void benchmarkLazyVsEagerRangeEnumeration() {
        System.out.println("\n=== Lazy vs Eager Range Enumeration Benchmark ===");
        
        // Define test ranges of different sizes - must be grid aligned
        int cellSize = 1 << (21 - TEST_LEVEL);
        var smallRange = new VolumeBounds(0, 0, 0, 2 * cellSize, 2 * cellSize, 2 * cellSize);
        var mediumRange = new VolumeBounds(0, 0, 0, 5 * cellSize, 5 * cellSize, 5 * cellSize);
        var largeRange = new VolumeBounds(0, 0, 0, 8 * cellSize, 8 * cellSize, 8 * cellSize);
        
        // Benchmark each range size
        benchmarkRangeSize("Small Range (50³)", smallRange);
        benchmarkRangeSize("Medium Range (100³)", mediumRange);
        benchmarkRangeSize("Large Range (150³)", largeRange);
    }
    
    private void benchmarkRangeSize(String name, VolumeBounds bounds) {
        System.out.println("\n" + name + ":");
        
        // For benchmarking, we'll use a simple range based on bounds
        // In real usage, this would come from spatial queries
        var minTet = new Tet((int)bounds.minX(), (int)bounds.minY(), (int)bounds.minZ(), TEST_LEVEL, (byte)0);
        var maxTet = new Tet((int)bounds.maxX(), (int)bounds.maxY(), (int)bounds.maxZ(), TEST_LEVEL, (byte)5);
        var sfcRanges = List.of(new Tet.SFCRange(minTet.tmIndex(), maxTet.tmIndex()));
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            countLazy(sfcRanges);
            countEager(sfcRanges);
        }
        
        // Benchmark lazy enumeration
        long lazyStart = System.nanoTime();
        long lazyCount = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            lazyCount = countLazy(sfcRanges);
        }
        long lazyTime = System.nanoTime() - lazyStart;
        
        // Benchmark eager enumeration
        long eagerStart = System.nanoTime();
        long eagerCount = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            eagerCount = countEager(sfcRanges);
        }
        long eagerTime = System.nanoTime() - eagerStart;
        
        // Report results
        System.out.printf("  Keys enumerated: %d%n", lazyCount);
        System.out.printf("  Lazy time: %.2f ms (%.2f ns/key)%n", 
            lazyTime / 1_000_000.0, 
            (double) lazyTime / BENCHMARK_ITERATIONS / lazyCount);
        System.out.printf("  Eager time: %.2f ms (%.2f ns/key)%n", 
            eagerTime / 1_000_000.0,
            (double) eagerTime / BENCHMARK_ITERATIONS / eagerCount);
        System.out.printf("  Speedup: %.2fx%n", (double) eagerTime / lazyTime);
    }
    
    private long countLazy(List<Tet.SFCRange> ranges) {
        return ranges.stream()
            .flatMap(range -> range.stream())
            .count();
    }
    
    private long countEager(List<Tet.SFCRange> ranges) {
        // Simulate eager collection to list
        List<TetreeKey<? extends TetreeKey>> allKeys = ranges.stream()
            .flatMap(range -> range.stream())
            .collect(Collectors.toList());
        return allKeys.size();
    }
    
    @Test
    void benchmarkEarlyTermination() {
        System.out.println("\n=== Early Termination Benchmark ===");
        
        int cellSize = 1 << (21 - TEST_LEVEL);
        var bounds = new VolumeBounds(0, 0, 0, 5 * cellSize, 5 * cellSize, 5 * cellSize);
        var minTet = new Tet((int)bounds.minX(), (int)bounds.minY(), (int)bounds.minZ(), TEST_LEVEL, (byte)0);
        var maxTet = new Tet((int)bounds.maxX(), (int)bounds.maxY(), (int)bounds.maxZ(), TEST_LEVEL, (byte)5);
        var sfcRanges = List.of(new Tet.SFCRange(minTet.tmIndex(), maxTet.tmIndex()));
        
        // Benchmark finding first 10 elements
        benchmarkFindFirst(sfcRanges, 1, "Find First");
        benchmarkFindFirst(sfcRanges, 10, "Find First 10");
        benchmarkFindFirst(sfcRanges, 100, "Find First 100");
    }
    
    private void benchmarkFindFirst(List<Tet.SFCRange> ranges, int limit, String operation) {
        System.out.println("\n" + operation + ":");
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            findFirstLazy(ranges, limit);
            findFirstEager(ranges, limit);
        }
        
        // Benchmark lazy
        long lazyStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            findFirstLazy(ranges, limit);
        }
        long lazyTime = System.nanoTime() - lazyStart;
        
        // Benchmark eager
        long eagerStart = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            findFirstEager(ranges, limit);
        }
        long eagerTime = System.nanoTime() - eagerStart;
        
        System.out.printf("  Lazy time: %.2f ms%n", lazyTime / 1_000_000.0);
        System.out.printf("  Eager time: %.2f ms%n", eagerTime / 1_000_000.0);
        System.out.printf("  Speedup: %.2fx%n", (double) eagerTime / lazyTime);
    }
    
    private List<TetreeKey<? extends TetreeKey>> findFirstLazy(List<Tet.SFCRange> ranges, int limit) {
        return ranges.stream()
            .flatMap(range -> range.stream())
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    private List<TetreeKey<? extends TetreeKey>> findFirstEager(List<Tet.SFCRange> ranges, int limit) {
        // Collect all first, then limit
        List<TetreeKey<? extends TetreeKey>> allKeys = ranges.stream()
            .flatMap(range -> range.stream())
            .collect(Collectors.toList());
        
        return allKeys.stream()
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    @Test
    void benchmarkMemoryUsage() {
        System.out.println("\n=== Memory Usage Benchmark ===");
        
        int cellSize = 1 << (21 - TEST_LEVEL);
        var bounds = new VolumeBounds(0, 0, 0, 8 * cellSize, 8 * cellSize, 8 * cellSize);
        var minTet = new Tet((int)bounds.minX(), (int)bounds.minY(), (int)bounds.minZ(), TEST_LEVEL, (byte)0);
        var maxTet = new Tet((int)bounds.maxX(), (int)bounds.maxY(), (int)bounds.maxZ(), TEST_LEVEL, (byte)5);
        var sfcRanges = List.of(new Tet.SFCRange(minTet.tmIndex(), maxTet.tmIndex()));
        
        // Force garbage collection
        System.gc();
        Thread.yield();
        
        // Measure lazy memory usage
        long beforeLazy = getUsedMemory();
        var lazyIterator = new LazyRangeIterator(
            Tet.tetrahedron(sfcRanges.get(0).start()),
            Tet.tetrahedron(sfcRanges.get(sfcRanges.size() - 1).end())
        );
        long afterLazy = getUsedMemory();
        
        // Count elements (to ensure iterator is used)
        long count = 0;
        while (lazyIterator.hasNext()) {
            lazyIterator.next();
            count++;
            if (count > 1000) break; // Don't enumerate all
        }
        
        // Force garbage collection
        System.gc();
        Thread.yield();
        
        // Measure eager memory usage
        long beforeEager = getUsedMemory();
        List<TetreeKey<? extends TetreeKey>> eagerList = new ArrayList<>();
        for (var range : sfcRanges) {
            var iter = range.iterator();
            int collected = 0;
            while (iter.hasNext() && collected < 1000) {
                eagerList.add(iter.next());
                collected++;
            }
        }
        long afterEager = getUsedMemory();
        
        System.out.printf("Lazy memory overhead: %d bytes%n", afterLazy - beforeLazy);
        System.out.printf("Eager memory overhead: %d bytes (for %d keys)%n", 
            afterEager - beforeEager, eagerList.size());
        System.out.printf("Memory savings: %.1f%%%n", 
            100.0 * (1 - (double)(afterLazy - beforeLazy) / (afterEager - beforeEager)));
    }
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    
    @Test
    void benchmarkRangeQueryVisitor() {
        System.out.println("\n=== Range Query Visitor Benchmark ===");
        
        int cellSize = 1 << (21 - TEST_LEVEL);
        var bounds = new VolumeBounds(2 * cellSize, 2 * cellSize, 2 * cellSize, 
                                     5 * cellSize, 5 * cellSize, 5 * cellSize);
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            performVisitorQuery(bounds);
            performStreamQuery(bounds);
        }
        
        // Benchmark visitor-based query
        long visitorStart = System.nanoTime();
        int visitorResults = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) {
            visitorResults = performVisitorQuery(bounds);
        }
        long visitorTime = System.nanoTime() - visitorStart;
        
        // Benchmark stream-based query
        long streamStart = System.nanoTime();
        int streamResults = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS / 10; i++) {
            streamResults = performStreamQuery(bounds);
        }
        long streamTime = System.nanoTime() - streamStart;
        
        System.out.printf("Visitor-based: %d results in %.2f ms%n", 
            visitorResults, visitorTime / 1_000_000.0);
        System.out.printf("Stream-based: %d results in %.2f ms%n", 
            streamResults, streamTime / 1_000_000.0);
        System.out.printf("Visitor speedup: %.2fx%n", (double) streamTime / visitorTime);
    }
    
    private int performVisitorQuery(VolumeBounds bounds) {
        var visitor = new RangeQueryVisitor<LongEntityID, String>(bounds, true);
        tetree.nodes().forEach(node -> visitor.visitNode(node, TEST_LEVEL, null));
        return visitor.getResults().size();
    }
    
    private int performStreamQuery(VolumeBounds bounds) {
        // Simulate traditional approach
        return (int) tetree.nodes()
            .filter(node -> {
                // Simple bounds check
                var tet = Tet.tetrahedron(node.sfcIndex());
                return tet.x < bounds.maxX() && tet.y < bounds.maxY() && tet.z < bounds.maxZ();
            })
            .count();
    }
}