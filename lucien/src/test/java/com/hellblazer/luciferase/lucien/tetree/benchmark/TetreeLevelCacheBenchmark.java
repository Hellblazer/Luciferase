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

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.TetreeLevelCache;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark comparing original O(log n) level extraction vs optimized O(1) cached version.
 * 
 * To run this benchmark, add the following dependencies to lucien/pom.xml:
 * <dependency>
 *     <groupId>org.openjdk.jmh</groupId>
 *     <artifactId>jmh-core</artifactId>
 *     <version>1.37</version>
 *     <scope>test</scope>
 * </dependency>
 * <dependency>
 *     <groupId>org.openjdk.jmh</groupId>
 *     <artifactId>jmh-generator-annprocess</artifactId>
 *     <version>1.37</version>
 *     <scope>test</scope>
 * </dependency>
 * 
 * Run with: mvn test -Dtest=TetreeLevelCacheBenchmark
 * 
 * @author hal.hildebrand
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G"})
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
public class TetreeLevelCacheBenchmark {
    
    // Test data sizes
    private static final int SMALL_SIZE = 100;
    private static final int MEDIUM_SIZE = 10_000;
    private static final int LARGE_SIZE = 1_000_000;
    
    @State(Scope.Benchmark)
    public static class BenchmarkState {
        // Test data arrays
        long[] smallIndices = new long[SMALL_SIZE];
        long[] mediumIndices = new long[MEDIUM_SIZE];
        long[] largeIndices = new long[LARGE_SIZE];
        
        // Random index for single lookups
        long randomIndex;
        
        @Setup(Level.Trial)
        public void setup() {
            Random random = new Random(42); // Fixed seed for reproducibility
            
            // Generate test indices across all levels (0-21)
            generateIndices(smallIndices, random);
            generateIndices(mediumIndices, random);
            generateIndices(largeIndices, random);
            
            // Pick a random index for single lookup benchmarks
            randomIndex = generateRandomIndex(random, 15); // Mid-range level
        }
        
        private void generateIndices(long[] indices, Random random) {
            for (int i = 0; i < indices.length; i++) {
                int level = random.nextInt(22); // 0-21
                indices[i] = generateIndexForLevel(level, random);
            }
        }
        
        private long generateIndexForLevel(int level, Random random) {
            if (level == 0) {
                return 0;
            }
            
            // Generate an index that will be at the specified level
            // Level 1: 1-7, Level 2: 8-63, Level 3: 64-511, etc.
            long minIndex = (1L << (3 * (level - 1))) * 7 / 6; // Approximate
            long maxIndex = (1L << (3 * level)) - 1;
            
            if (maxIndex < minIndex) {
                maxIndex = minIndex + 1000; // Handle overflow
            }
            
            return minIndex + Math.abs(random.nextLong()) % (maxIndex - minIndex + 1);
        }
        
        private long generateRandomIndex(Random random, int level) {
            return generateIndexForLevel(level, random);
        }
    }
    
    // ====== Original O(log n) implementation ======
    
    /**
     * Original level extraction using numberOfLeadingZeros - O(log n)
     */
    private static byte getLevelFromIndexOriginal(long index) {
        if (index == 0) {
            return 0;
        }
        
        // Original implementation using numberOfLeadingZeros
        int highBit = 63 - Long.numberOfLeadingZeros(index);
        byte level = (byte) ((highBit / 3) + 1);
        
        // Clamp to max level
        return level > Constants.getMaxRefinementLevel() ? Constants.getMaxRefinementLevel() : level;
    }
    
    // ====== Benchmarks ======
    
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public byte originalSingleLookup(BenchmarkState state) {
        return getLevelFromIndexOriginal(state.randomIndex);
    }
    
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public byte cachedSingleLookup(BenchmarkState state) {
        return TetreeLevelCache.getLevelFromIndex(state.randomIndex);
    }
    
    @Benchmark
    public long originalSmallBatch(BenchmarkState state) {
        long sum = 0;
        for (long index : state.smallIndices) {
            sum += getLevelFromIndexOriginal(index);
        }
        return sum;
    }
    
    @Benchmark
    public long cachedSmallBatch(BenchmarkState state) {
        long sum = 0;
        for (long index : state.smallIndices) {
            sum += TetreeLevelCache.getLevelFromIndex(index);
        }
        return sum;
    }
    
    @Benchmark
    public long originalMediumBatch(BenchmarkState state) {
        long sum = 0;
        for (long index : state.mediumIndices) {
            sum += getLevelFromIndexOriginal(index);
        }
        return sum;
    }
    
    @Benchmark
    public long cachedMediumBatch(BenchmarkState state) {
        long sum = 0;
        for (long index : state.mediumIndices) {
            sum += TetreeLevelCache.getLevelFromIndex(index);
        }
        return sum;
    }
    
    @Benchmark
    public long originalLargeBatch(BenchmarkState state) {
        long sum = 0;
        for (long index : state.largeIndices) {
            sum += getLevelFromIndexOriginal(index);
        }
        return sum;
    }
    
    @Benchmark
    public long cachedLargeBatch(BenchmarkState state) {
        long sum = 0;
        for (long index : state.largeIndices) {
            sum += TetreeLevelCache.getLevelFromIndex(index);
        }
        return sum;
    }
    
    // ====== Specific level range benchmarks ======
    
    @Benchmark
    @OperationsPerInvocation(100)
    public long originalSmallIndices() {
        long sum = 0;
        // Test small indices (levels 0-3) which should hit the fast path
        for (int i = 0; i < 100; i++) {
            sum += getLevelFromIndexOriginal(i);
        }
        return sum;
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public long cachedSmallIndices() {
        long sum = 0;
        // Test small indices (levels 0-3) which should hit the fast path
        for (int i = 0; i < 100; i++) {
            sum += TetreeLevelCache.getLevelFromIndex(i);
        }
        return sum;
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public long originalLargeIndices() {
        long sum = 0;
        // Test large indices (higher levels) which use De Bruijn optimization
        long base = 1L << 45; // Very large indices
        for (int i = 0; i < 100; i++) {
            sum += getLevelFromIndexOriginal(base + i);
        }
        return sum;
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public long cachedLargeIndices() {
        long sum = 0;
        // Test large indices (higher levels) which use De Bruijn optimization
        long base = 1L << 45; // Very large indices
        for (int i = 0; i < 100; i++) {
            sum += TetreeLevelCache.getLevelFromIndex(base + i);
        }
        return sum;
    }
    
    // ====== Main method to run benchmark ======
    
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(TetreeLevelCacheBenchmark.class.getSimpleName())
                .forks(1)
                .build();
        
        new Runner(opt).run();
    }
}