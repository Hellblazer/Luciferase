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

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeBits;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import com.hellblazer.luciferase.lucien.tetree.TetreeLevelCache;
import com.hellblazer.luciferase.lucien.benchmark.CIEnvironmentCheck;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark comparing original O(level) parent chain traversal vs optimized O(1) cached version.
 * 
 * Demonstrates performance improvements in:
 * - Parent chain traversal (O(level) -> O(1) for cached entries)
 * - Type computation along parent chain (O(level) -> O(1))
 * - Ancestor queries at different levels
 * 
 * @author hal.hildebrand
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
public class TetreeParentChainBenchmark {
    
    @State(Scope.Benchmark)
    public static class BenchmarkState {
        // Test tetrahedra at different levels
        Tet[] shallowTets = new Tet[100];  // Levels 1-5
        Tet[] mediumTets = new Tet[100];   // Levels 6-12
        Tet[] deepTets = new Tet[100];     // Levels 13-21
        
        @Setup(Level.Trial)
        public void setup() {
            Random random = new Random(42);
            
            // Generate test tetrahedra at different depth levels
            generateTetsAtLevels(shallowTets, 1, 5, random);
            generateTetsAtLevels(mediumTets, 6, 12, random);
            generateTetsAtLevels(deepTets, 13, 21, random);
            
            // Pre-warm the cache with some entries
            warmupCache();
        }
        
        private void generateTetsAtLevels(Tet[] tets, int minLevel, int maxLevel, Random random) {
            for (int i = 0; i < tets.length; i++) {
                int level = minLevel + random.nextInt(maxLevel - minLevel + 1);
                // Generate coordinates that would result in the desired level
                int coord = random.nextInt(1 << (level * 3));
                tets[i] = new Tet(coord, coord, coord, (byte) level, (byte) (random.nextInt(6)));
            }
        }
        
        private void warmupCache() {
            // Warm up cache with some parent chain computations
            for (int i = 0; i < 10; i++) {
                computeParentChainCached(deepTets[i]);
            }
        }
    }
    
    // ====== Original O(level) implementations ======
    
    /**
     * Original parent chain computation - O(level)
     */
    private static long[] computeParentChainOriginal(Tet tet) {
        byte level = tet.l();
        long[] chain = new long[level + 1];
        
        // Start with current index
        chain[0] = tet.index();
        
        // Compute each parent up to root
        Tet current = tet;
        for (int i = 1; i <= level; i++) {
            current = computeParentOriginal(current);
            chain[i] = current.index();
        }
        
        return chain;
    }
    
    /**
     * Original parent computation - requires type tracking
     */
    private static Tet computeParentOriginal(Tet child) {
        if (child.l() == 0) {
            return child; // Root has no parent
        }
        
        byte parentLevel = (byte) (child.l() - 1);
        
        // Original computation using bit operations
        int parentX = child.x() >> 1;
        int parentY = child.y() >> 1;
        int parentZ = child.z() >> 1;
        
        // Compute parent type using connectivity tables
        byte childType = child.type();
        int childIndex = ((child.x() & 1) << 2) | ((child.y() & 1) << 1) | (child.z() & 1);
        byte parentType = computeParentTypeOriginal(childType, childIndex);
        
        return new Tet(parentX, parentY, parentZ, parentLevel, parentType);
    }
    
    /**
     * Original type computation along parent chain - O(level)
     */
    private static byte computeTypeAtLevelOriginal(Tet tet, byte targetLevel) {
        if (targetLevel > tet.l()) {
            throw new IllegalArgumentException("Target level must be <= current level");
        }
        
        if (targetLevel == tet.l()) {
            return tet.type();
        }
        
        // Traverse up the parent chain to find type at target level
        Tet current = tet;
        for (byte level = tet.l(); level > targetLevel; level--) {
            current = computeParentOriginal(current);
        }
        
        return current.type();
    }
    
    private static byte computeParentTypeOriginal(byte childType, int childIndex) {
        // Simplified version - in a real implementation this would use connectivity tables
        // For benchmarking purposes, we'll use a simple calculation
        return (byte) ((childType + childIndex) % 6);
    }
    
    // ====== Optimized O(1) implementations ======
    
    /**
     * Cached parent chain computation - O(1) for cached entries, O(level) otherwise
     */
    private static long[] computeParentChainCached(Tet tet) {
        long index = tet.index();
        byte level = tet.l();
        
        // Check cache first
        long[] cached = TetreeLevelCache.getParentChain(index, level);
        if (cached != null) {
            return cached; // O(1) cache hit
        }
        
        // Cache miss - compute and cache
        long[] chain = computeParentChainOriginal(tet);
        TetreeLevelCache.cacheParentChain(index, level, chain);
        return chain;
    }
    
    /**
     * Cached type computation - O(1) lookup
     */
    private static byte computeTypeAtLevelCached(Tet tet, byte targetLevel) {
        byte cachedType = TetreeLevelCache.getTypeAtLevel(tet.type(), tet.l(), targetLevel);
        if (cachedType != -1) {
            return cachedType; // O(1) cache hit
        }
        
        // Cache miss - compute using original method
        return computeTypeAtLevelOriginal(tet, targetLevel);
    }
    
    // ====== Benchmarks ======
    
    // --- Parent chain computation benchmarks ---
    
    @Benchmark
    public long originalShallowParentChain(BenchmarkState state) {
        long sum = 0;
        for (Tet tet : state.shallowTets) {
            long[] chain = computeParentChainOriginal(tet);
            sum += chain[chain.length - 1]; // Use result to prevent optimization
        }
        return sum;
    }
    
    @Benchmark
    public long cachedShallowParentChain(BenchmarkState state) {
        long sum = 0;
        for (Tet tet : state.shallowTets) {
            long[] chain = computeParentChainCached(tet);
            sum += chain[chain.length - 1];
        }
        return sum;
    }
    
    @Benchmark
    public long originalMediumParentChain(BenchmarkState state) {
        long sum = 0;
        for (Tet tet : state.mediumTets) {
            long[] chain = computeParentChainOriginal(tet);
            sum += chain[chain.length - 1];
        }
        return sum;
    }
    
    @Benchmark
    public long cachedMediumParentChain(BenchmarkState state) {
        long sum = 0;
        for (Tet tet : state.mediumTets) {
            long[] chain = computeParentChainCached(tet);
            sum += chain[chain.length - 1];
        }
        return sum;
    }
    
    @Benchmark
    public long originalDeepParentChain(BenchmarkState state) {
        long sum = 0;
        for (Tet tet : state.deepTets) {
            long[] chain = computeParentChainOriginal(tet);
            sum += chain[chain.length - 1];
        }
        return sum;
    }
    
    @Benchmark
    public long cachedDeepParentChain(BenchmarkState state) {
        long sum = 0;
        for (Tet tet : state.deepTets) {
            long[] chain = computeParentChainCached(tet);
            sum += chain[chain.length - 1];
        }
        return sum;
    }
    
    // --- Type computation benchmarks ---
    
    @Benchmark
    public int originalTypeComputation(BenchmarkState state) {
        int sum = 0;
        for (Tet tet : state.deepTets) {
            // Compute type at various ancestor levels
            sum += computeTypeAtLevelOriginal(tet, (byte) 0);
            sum += computeTypeAtLevelOriginal(tet, (byte) (tet.l() / 2));
            sum += computeTypeAtLevelOriginal(tet, tet.l());
        }
        return sum;
    }
    
    @Benchmark
    public int cachedTypeComputation(BenchmarkState state) {
        int sum = 0;
        for (Tet tet : state.deepTets) {
            // Compute type at various ancestor levels
            sum += computeTypeAtLevelCached(tet, (byte) 0);
            sum += computeTypeAtLevelCached(tet, (byte) (tet.l() / 2));
            sum += computeTypeAtLevelCached(tet, tet.l());
        }
        return sum;
    }
    
    // --- Single parent computation benchmarks ---
    
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Tet originalSingleParent(BenchmarkState state) {
        return computeParentOriginal(state.deepTets[0]);
    }
    
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public long cachedSingleParent(BenchmarkState state) {
        // Using cached parent chain to get immediate parent
        long[] chain = computeParentChainCached(state.deepTets[0]);
        return chain.length > 1 ? chain[1] : chain[0];
    }
    
    // --- Worst case scenario: deep trees ---
    
    @Benchmark
    public long originalWorstCase() {
        // Create a very deep tetrahedron (level 21)
        Tet deepTet = new Tet(1048576, 1048576, 1048576, (byte) 21, (byte) 0);
        long[] chain = computeParentChainOriginal(deepTet);
        return chain[chain.length - 1];
    }
    
    @Benchmark
    public long cachedWorstCase() {
        // Create a very deep tetrahedron (level 21)
        Tet deepTet = new Tet(1048576, 1048576, 1048576, (byte) 21, (byte) 0);
        long[] chain = computeParentChainCached(deepTet);
        return chain[chain.length - 1];
    }
    
    // ====== Main method to run benchmark ======
    
    public static void main(String[] args) throws RunnerException {
        // Skip if running in any CI environment
        if (CIEnvironmentCheck.isRunningInCI()) {
            System.out.println(CIEnvironmentCheck.getSkipMessage());
            return;
        }
        
        Options opt = new OptionsBuilder()
                .include(TetreeParentChainBenchmark.class.getSimpleName())
                .forks(1)
                .build();
        
        new Runner(opt).run();
    }
}