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
package com.hellblazer.luciferase.lucien.simd;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for SIMD Morton encoding performance.
 * 
 * Compares:
 * - Scalar implementation (MortonCurve.encode)
 * - SIMD implementation (SIMDMortonEncoder.encode)
 * - SIMD batch encoding (SIMDMortonEncoder.encodeBatch)
 * 
 * Target: 2-4x speedup on ARM NEON systems
 * 
 * Run with: mvn test -Psimd-preview -Dtest=MortonEncodingSIMDBenchmark
 * 
 * @author hal.hildebrand
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class MortonEncodingSIMDBenchmark {
    
    @Param({"100", "1000", "10000"})
    private int size;
    
    private int[] xCoords;
    private int[] yCoords;
    private int[] zCoords;
    private long[] output;
    
    @Setup
    public void setup() {
        var random = new Random(42); // Fixed seed for reproducibility
        
        xCoords = new int[size];
        yCoords = new int[size];
        zCoords = new int[size];
        output = new long[size];
        
        // Generate random coordinates within 21-bit range
        int maxCoord = (1 << 21) - 1;
        for (int i = 0; i < size; i++) {
            xCoords[i] = random.nextInt(maxCoord);
            yCoords[i] = random.nextInt(maxCoord);
            zCoords[i] = random.nextInt(maxCoord);
        }
    }
    
    @Benchmark
    public void scalarEncoding() {
        for (int i = 0; i < size; i++) {
            output[i] = MortonCurve.encode(xCoords[i], yCoords[i], zCoords[i]);
        }
    }
    
    @Benchmark
    public void simdEncoding() {
        for (int i = 0; i < size; i++) {
            output[i] = SIMDMortonEncoder.encode(xCoords[i], yCoords[i], zCoords[i]);
        }
    }
    
    @Benchmark
    public void simdBatchEncoding() {
        SIMDMortonEncoder.encodeBatch(xCoords, yCoords, zCoords, output, size);
    }
    
    /**
     * Single encoding benchmark - measures per-operation overhead
     */
    @Benchmark
    public long scalarSingleEncoding() {
        return MortonCurve.encode(12345, 23456, 34567);
    }
    
    @Benchmark
    public long simdSingleEncoding() {
        return SIMDMortonEncoder.encode(12345, 23456, 34567);
    }
    
    /**
     * Validation: Verify SIMD produces same results as scalar
     */
    @TearDown(Level.Iteration)
    public void validate() {
        // Sample validation - check first 10 elements
        int checkCount = Math.min(10, size);
        for (int i = 0; i < checkCount; i++) {
            long scalarResult = MortonCurve.encode(xCoords[i], yCoords[i], zCoords[i]);
            long simdResult = SIMDMortonEncoder.encode(xCoords[i], yCoords[i], zCoords[i]);
            
            if (scalarResult != simdResult) {
                throw new AssertionError(String.format(
                    "SIMD encoding mismatch at index %d: scalar=%d, simd=%d", 
                    i, scalarResult, simdResult
                ));
            }
        }
    }
}
