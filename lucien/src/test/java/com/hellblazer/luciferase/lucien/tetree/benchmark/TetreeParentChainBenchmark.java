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
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.lucien.benchmark.CIEnvironmentCheck;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for parent chain traversal performance.
 * 
 * Measures performance of:
 * - Parent chain traversal at different levels
 * - Type computation along parent chain
 * - Ancestor queries at different depths
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
            // Warm up JVM with some parent chain computations
            for (int i = 0; i < 10; i++) {
                computeParentChainOriginal(deepTets[i]);
            }
        }
    }
    
    // ====== Original O(level) implementations ======
    
    /**
     * Original parent chain computation - O(level)
     */
    private static TetreeKey[] computeParentChainOriginal(Tet tet) {
        byte level = tet.l();
        TetreeKey[] chain = new TetreeKey[level + 1];
        
        // Start with current tmIndex
        chain[0] = tet.tmIndex();
        
        // Compute each parent up to root
        Tet current = tet;
        for (int i = 1; i <= level; i++) {
            current = computeParentOriginal(current);
            chain[i] = current.tmIndex();
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
    
    // ====== Benchmarks ======
    
    // --- Parent chain computation benchmarks ---
    
    @Benchmark
    public long originalShallowParentChain(BenchmarkState state) {
        long sum = 0;
        for (Tet tet : state.shallowTets) {
            TetreeKey[] chain = computeParentChainOriginal(tet);
            sum += chain[chain.length - 1].getLowBits(); // Use result to prevent optimization
        }
        return sum;
    }
    
    @Benchmark
    public long originalMediumParentChain(BenchmarkState state) {
        long sum = 0;
        for (Tet tet : state.mediumTets) {
            TetreeKey[] chain = computeParentChainOriginal(tet);
            sum += chain[chain.length - 1].getLowBits();
        }
        return sum;
    }
    
    @Benchmark
    public long originalDeepParentChain(BenchmarkState state) {
        long sum = 0;
        for (Tet tet : state.deepTets) {
            TetreeKey[] chain = computeParentChainOriginal(tet);
            sum += chain[chain.length - 1].getLowBits();
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
    
    // --- Single parent computation benchmarks ---
    
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Tet originalSingleParent(BenchmarkState state) {
        return computeParentOriginal(state.deepTets[0]);
    }
    
    // --- Worst case scenario: deep trees ---
    
    @Benchmark
    public long originalWorstCase() {
        // Create a very deep tetrahedron (level 21)
        Tet deepTet = new Tet(1048576, 1048576, 1048576, (byte) 21, (byte) 0);
        TetreeKey[] chain = computeParentChainOriginal(deepTet);
        return chain[chain.length - 1].getLowBits();
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