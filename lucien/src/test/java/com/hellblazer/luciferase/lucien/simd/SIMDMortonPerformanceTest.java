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
import com.hellblazer.luciferase.lucien.internal.VectorAPISupport;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * Performance test for SIMD Morton encoding (Epic 1, Bead 1.3).
 * 
 * Measures:
 * - Single encoding performance (scalar vs SIMD)
 * - Batch encoding performance
 * - Speedup ratio
 * 
 * Target: 2-4x speedup on ARM NEON systems
 * 
 * Run with: mvn test -Psimd-preview -Dtest=SIMDMortonPerformanceTest
 * 
 * @author hal.hildebrand
 */
public class SIMDMortonPerformanceTest {
    
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 100_000;
    private static final int BATCH_SIZE = 10_000;
    
    @Test
    public void measureSIMDPerformance() {
        System.out.println("\n=== SIMD Morton Encoding Performance Test ===\n");
        
        // Check SIMD availability
        boolean simdAvailable = SIMDMortonEncoder.isSIMDAvailable();
        System.out.println("SIMD Available: " + simdAvailable);
        if (simdAvailable) {
            System.out.println("CPU: " + VectorAPISupport.getCPUCapability());
            System.out.println("Batch Size: " + SIMDMortonEncoder.getBatchSize() + " lanes\n");
        } else {
            System.out.println("SIMD not available - graceful fallback to scalar\n");
        }
        
        // Generate test data
        Random random = new Random(42);
        int maxCoord = (1 << 21) - 1;
        
        // Single encoding test
        System.out.println("--- Single Encoding Performance ---");
        int x = random.nextInt(maxCoord);
        int y = random.nextInt(maxCoord);
        int z = random.nextInt(maxCoord);
        
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            MortonCurve.encode(x, y, z);
            SIMDMortonEncoder.encode(x, y, z);
        }
        
        // Benchmark scalar
        long scalarStart = System.nanoTime();
        long scalarResult = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            scalarResult = MortonCurve.encode(x, y, z);
        }
        long scalarTime = System.nanoTime() - scalarStart;
        
        // Benchmark SIMD
        long simdStart = System.nanoTime();
        long simdResult = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            simdResult = SIMDMortonEncoder.encode(x, y, z);
        }
        long simdTime = System.nanoTime() - simdStart;
        
        // Verify correctness
        if (scalarResult != simdResult) {
            throw new AssertionError("SIMD result mismatch: scalar=" + scalarResult + ", simd=" + simdResult);
        }
        
        double scalarOpsPerSec = (BENCHMARK_ITERATIONS * 1e9) / scalarTime;
        double simdOpsPerSec = (BENCHMARK_ITERATIONS * 1e9) / simdTime;
        double singleSpeedup = simdOpsPerSec / scalarOpsPerSec;
        
        System.out.printf("Scalar: %.2f M ops/sec (%.2f ns/op)%n", scalarOpsPerSec / 1e6, scalarTime / (double) BENCHMARK_ITERATIONS);
        System.out.printf("SIMD:   %.2f M ops/sec (%.2f ns/op)%n", simdOpsPerSec / 1e6, simdTime / (double) BENCHMARK_ITERATIONS);
        System.out.printf("Speedup: %.2fx%n%n", singleSpeedup);
        
        // Batch encoding test
        System.out.println("--- Batch Encoding Performance ---");
        int[] xCoords = new int[BATCH_SIZE];
        int[] yCoords = new int[BATCH_SIZE];
        int[] zCoords = new int[BATCH_SIZE];
        long[] scalarOutput = new long[BATCH_SIZE];
        long[] simdOutput = new long[BATCH_SIZE];
        
        for (int i = 0; i < BATCH_SIZE; i++) {
            xCoords[i] = random.nextInt(maxCoord);
            yCoords[i] = random.nextInt(maxCoord);
            zCoords[i] = random.nextInt(maxCoord);
        }
        
        // Warmup
        for (int i = 0; i < 10; i++) {
            encodeBatchScalar(xCoords, yCoords, zCoords, scalarOutput, BATCH_SIZE);
            SIMDMortonEncoder.encodeBatch(xCoords, yCoords, zCoords, simdOutput, BATCH_SIZE);
        }
        
        // Benchmark scalar batch
        int batchIterations = 1000;
        long scalarBatchStart = System.nanoTime();
        for (int i = 0; i < batchIterations; i++) {
            encodeBatchScalar(xCoords, yCoords, zCoords, scalarOutput, BATCH_SIZE);
        }
        long scalarBatchTime = System.nanoTime() - scalarBatchStart;
        
        // Benchmark SIMD batch
        long simdBatchStart = System.nanoTime();
        for (int i = 0; i < batchIterations; i++) {
            SIMDMortonEncoder.encodeBatch(xCoords, yCoords, zCoords, simdOutput, BATCH_SIZE);
        }
        long simdBatchTime = System.nanoTime() - simdBatchStart;
        
        // Verify correctness
        encodeBatchScalar(xCoords, yCoords, zCoords, scalarOutput, BATCH_SIZE);
        SIMDMortonEncoder.encodeBatch(xCoords, yCoords, zCoords, simdOutput, BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            if (scalarOutput[i] != simdOutput[i]) {
                throw new AssertionError(String.format("Batch mismatch at index %d: scalar=%d, simd=%d", 
                    i, scalarOutput[i], simdOutput[i]));
            }
        }
        
        long totalScalarOps = (long) batchIterations * BATCH_SIZE;
        long totalSIMDOps = (long) batchIterations * BATCH_SIZE;
        
        double scalarBatchOpsPerSec = (totalScalarOps * 1e9) / scalarBatchTime;
        double simdBatchOpsPerSec = (totalSIMDOps * 1e9) / simdBatchTime;
        double batchSpeedup = simdBatchOpsPerSec / scalarBatchOpsPerSec;
        
        System.out.printf("Scalar Batch: %.2f M ops/sec%n", scalarBatchOpsPerSec / 1e6);
        System.out.printf("SIMD Batch:   %.2f M ops/sec%n", simdBatchOpsPerSec / 1e6);
        System.out.printf("Speedup: %.2fx%n%n", batchSpeedup);
        
        // Summary
        System.out.println("=== Performance Summary ===");
        System.out.printf("Single Encoding Speedup: %.2fx%n", singleSpeedup);
        System.out.printf("Batch Encoding Speedup:  %.2fx%n", batchSpeedup);
        
        if (simdAvailable) {
            System.out.printf("%nTarget: 2-4x speedup%n");
            if (batchSpeedup >= 2.0) {
                System.out.printf("✓ PASSED: Achieved %.2fx speedup (>= 2x target)%n", batchSpeedup);
            } else {
                System.out.printf("⚠ BELOW TARGET: Achieved %.2fx speedup (< 2x target)%n", batchSpeedup);
            }
        } else {
            System.out.println("\nSIMD not enabled - run with -Psimd-preview to enable Vector API");
        }
    }
    
    private void encodeBatchScalar(int[] x, int[] y, int[] z, long[] output, int count) {
        for (int i = 0; i < count; i++) {
            output[i] = MortonCurve.encode(x[i], y[i], z[i]);
        }
    }
}
