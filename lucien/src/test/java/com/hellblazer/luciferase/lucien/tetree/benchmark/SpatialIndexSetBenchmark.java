/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree.benchmark;

import com.hellblazer.luciferase.lucien.SpatialIndexSet;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark comparing original TreeSet O(log n) operations vs optimized SpatialIndexSet O(1) operations.
 * 
 * Demonstrates performance improvements in:
 * - add() operations: O(log n) -> O(1)
 * - remove() operations: O(log n) -> O(1)
 * - contains() operations: O(log n) -> O(1)
 * - Level-based queries: O(n) -> O(1)
 * - Range queries with level filtering
 * 
 * @author hal.hildebrand
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
public class SpatialIndexSetBenchmark {
    
    @State(Scope.Benchmark)
    public static class BenchmarkState {
        // Test data
        List<Long> indices = new ArrayList<>();
        Set<Long> lookupSet = new HashSet<>();
        
        // Collections to benchmark
        NavigableSet<Long> treeSet;
        SpatialIndexSet spatialSet;
        
        // Data sizes
        static final int SMALL_SIZE = 1_000;
        static final int MEDIUM_SIZE = 10_000;
        static final int LARGE_SIZE = 100_000;
        
        @Setup(Level.Invocation)
        public void setup() {
            Random random = new Random(42);
            
            // Generate test indices across all levels
            indices.clear();
            for (int i = 0; i < LARGE_SIZE; i++) {
                indices.add(generateRandomIndex(random));
            }
            
            // Create lookup set for contains() benchmarks
            lookupSet.clear();
            for (int i = 0; i < 1000; i++) {
                lookupSet.add(indices.get(random.nextInt(indices.size())));
            }
            
            // Initialize collections
            treeSet = new TreeSet<>();
            spatialSet = new SpatialIndexSet();
        }
        
        private long generateRandomIndex(Random random) {
            int level = random.nextInt(22); // 0-21
            if (level == 0) return 0;
            
            // Generate index that would be at the specified level
            long minBit = 3 * (level - 1);
            long maxBit = 3 * level - 1;
            long index = 1L << (minBit + random.nextInt((int)(maxBit - minBit + 1)));
            return index + random.nextInt(1000); // Add some variation
        }
    }
    
    // ====== Add operation benchmarks ======
    
    @Benchmark
    public void treeSetAddSmall(BenchmarkState state) {
        state.treeSet.clear();
        for (int i = 0; i < BenchmarkState.SMALL_SIZE; i++) {
            state.treeSet.add(state.indices.get(i));
        }
    }
    
    @Benchmark
    public void spatialSetAddSmall(BenchmarkState state) {
        state.spatialSet.clear();
        for (int i = 0; i < BenchmarkState.SMALL_SIZE; i++) {
            state.spatialSet.add(state.indices.get(i));
        }
    }
    
    @Benchmark
    public void treeSetAddMedium(BenchmarkState state) {
        state.treeSet.clear();
        for (int i = 0; i < BenchmarkState.MEDIUM_SIZE; i++) {
            state.treeSet.add(state.indices.get(i));
        }
    }
    
    @Benchmark
    public void spatialSetAddMedium(BenchmarkState state) {
        state.spatialSet.clear();
        for (int i = 0; i < BenchmarkState.MEDIUM_SIZE; i++) {
            state.spatialSet.add(state.indices.get(i));
        }
    }
    
    @Benchmark
    public void treeSetAddLarge(BenchmarkState state) {
        state.treeSet.clear();
        for (int i = 0; i < BenchmarkState.LARGE_SIZE; i++) {
            state.treeSet.add(state.indices.get(i));
        }
    }
    
    @Benchmark
    public void spatialSetAddLarge(BenchmarkState state) {
        state.spatialSet.clear();
        for (int i = 0; i < BenchmarkState.LARGE_SIZE; i++) {
            state.spatialSet.add(state.indices.get(i));
        }
    }
    
    // ====== Contains operation benchmarks ======
    
    @Benchmark
    @OperationsPerInvocation(1000)
    public int treeSetContains(BenchmarkState state) {
        // Pre-populate
        if (state.treeSet.isEmpty()) {
            state.treeSet.addAll(state.indices.subList(0, BenchmarkState.MEDIUM_SIZE));
        }
        
        int found = 0;
        for (Long index : state.lookupSet) {
            if (state.treeSet.contains(index)) {
                found++;
            }
        }
        return found;
    }
    
    @Benchmark
    @OperationsPerInvocation(1000)
    public int spatialSetContains(BenchmarkState state) {
        // Pre-populate
        if (state.spatialSet.isEmpty()) {
            state.spatialSet.addAll(state.indices.subList(0, BenchmarkState.MEDIUM_SIZE));
        }
        
        int found = 0;
        for (Long index : state.lookupSet) {
            if (state.spatialSet.contains(index)) {
                found++;
            }
        }
        return found;
    }
    
    // ====== Remove operation benchmarks ======
    
    @Benchmark
    public void treeSetRemove(BenchmarkState state) {
        // Pre-populate
        state.treeSet.clear();
        state.treeSet.addAll(state.indices.subList(0, BenchmarkState.SMALL_SIZE));
        
        // Remove half
        for (int i = 0; i < BenchmarkState.SMALL_SIZE / 2; i++) {
            state.treeSet.remove(state.indices.get(i));
        }
    }
    
    @Benchmark
    public void spatialSetRemove(BenchmarkState state) {
        // Pre-populate
        state.spatialSet.clear();
        state.spatialSet.addAll(state.indices.subList(0, BenchmarkState.SMALL_SIZE));
        
        // Remove half
        for (int i = 0; i < BenchmarkState.SMALL_SIZE / 2; i++) {
            state.spatialSet.remove(state.indices.get(i));
        }
    }
    
    // ====== Range query benchmarks ======
    
    @Benchmark
    public int treeSetRangeQuery(BenchmarkState state) {
        // Pre-populate
        if (state.treeSet.isEmpty()) {
            state.treeSet.addAll(state.indices.subList(0, BenchmarkState.MEDIUM_SIZE));
        }
        
        // Range query
        Long min = state.indices.get(100);
        Long max = state.indices.get(200);
        NavigableSet<Long> subset = state.treeSet.subSet(min, true, max, true);
        return subset.size();
    }
    
    @Benchmark
    public int spatialSetRangeQuery(BenchmarkState state) {
        // Pre-populate
        if (state.spatialSet.isEmpty()) {
            state.spatialSet.addAll(state.indices.subList(0, BenchmarkState.MEDIUM_SIZE));
        }
        
        // Range query
        Long min = state.indices.get(100);
        Long max = state.indices.get(200);
        NavigableSet<Long> subset = state.spatialSet.subSet(min, true, max, true);
        return subset.size();
    }
    
    // ====== Level-based query benchmarks (unique to SpatialIndexSet) ======
    
    @Benchmark
    public int treeSetLevelQuery(BenchmarkState state) {
        // Pre-populate
        if (state.treeSet.isEmpty()) {
            state.treeSet.addAll(state.indices.subList(0, BenchmarkState.MEDIUM_SIZE));
        }
        
        // Simulate level query on TreeSet (O(n) operation)
        int count = 0;
        byte targetLevel = 10;
        for (Long index : state.treeSet) {
            if (getLevelFromIndex(index) == targetLevel) {
                count++;
            }
        }
        return count;
    }
    
    @Benchmark
    public int spatialSetLevelQuery(BenchmarkState state) {
        // Pre-populate
        if (state.spatialSet.isEmpty()) {
            state.spatialSet.addAll(state.indices.subList(0, BenchmarkState.MEDIUM_SIZE));
        }
        
        // O(1) level query on SpatialIndexSet
        byte targetLevel = 10;
        return state.spatialSet.getIndicesAtLevel(targetLevel).size();
    }
    
    @Benchmark
    public int treeSetLevelRangeQuery(BenchmarkState state) {
        // Pre-populate
        if (state.treeSet.isEmpty()) {
            state.treeSet.addAll(state.indices.subList(0, BenchmarkState.MEDIUM_SIZE));
        }
        
        // Simulate level range query on TreeSet (O(n) operation)
        int count = 0;
        byte minLevel = 5;
        byte maxLevel = 15;
        for (Long index : state.treeSet) {
            byte level = getLevelFromIndex(index);
            if (level >= minLevel && level <= maxLevel) {
                count++;
            }
        }
        return count;
    }
    
    @Benchmark
    public int spatialSetLevelRangeQuery(BenchmarkState state) {
        // Pre-populate
        if (state.spatialSet.isEmpty()) {
            state.spatialSet.addAll(state.indices.subList(0, BenchmarkState.MEDIUM_SIZE));
        }
        
        // O(levels) query on SpatialIndexSet
        byte minLevel = 5;
        byte maxLevel = 15;
        return state.spatialSet.getIndicesBetweenLevels(minLevel, maxLevel).size();
    }
    
    // ====== Mixed operations benchmark ======
    
    @Benchmark
    public long treeSetMixedOperations(BenchmarkState state) {
        state.treeSet.clear();
        Random random = new Random(42);
        long sum = 0;
        
        // Mix of operations
        for (int i = 0; i < 1000; i++) {
            Long index = state.indices.get(i);
            
            // Add
            state.treeSet.add(index);
            
            // Contains check
            if (state.treeSet.contains(index)) {
                sum++;
            }
            
            // Occasional remove
            if (i % 10 == 0 && i > 0) {
                state.treeSet.remove(state.indices.get(i - 10));
            }
            
            // Occasional range query
            if (i % 100 == 0 && state.treeSet.size() > 10) {
                sum += state.treeSet.first();
                sum += state.treeSet.last();
            }
        }
        
        return sum;
    }
    
    @Benchmark
    public long spatialSetMixedOperations(BenchmarkState state) {
        state.spatialSet.clear();
        Random random = new Random(42);
        long sum = 0;
        
        // Mix of operations
        for (int i = 0; i < 1000; i++) {
            Long index = state.indices.get(i);
            
            // Add
            state.spatialSet.add(index);
            
            // Contains check
            if (state.spatialSet.contains(index)) {
                sum++;
            }
            
            // Occasional remove
            if (i % 10 == 0 && i > 0) {
                state.spatialSet.remove(state.indices.get(i - 10));
            }
            
            // Occasional range query
            if (i % 100 == 0 && state.spatialSet.size() > 10) {
                sum += state.spatialSet.first();
                sum += state.spatialSet.last();
            }
        }
        
        return sum;
    }
    
    // Helper method
    private static byte getLevelFromIndex(long index) {
        if (index == 0) return 0;
        int highBit = 63 - Long.numberOfLeadingZeros(index);
        return (byte) ((highBit / 3) + 1);
    }
    
    // ====== Main method to run benchmark ======
    
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SpatialIndexSetBenchmark.class.getSimpleName())
                .forks(1)
                .build();
        
        new Runner(opt).run();
    }
}