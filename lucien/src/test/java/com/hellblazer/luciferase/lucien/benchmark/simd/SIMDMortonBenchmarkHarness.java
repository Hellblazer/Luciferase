/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of Luciferase.
 *
 * Luciferase is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Luciferase is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Luciferase. If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.benchmark.simd;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.internal.VectorAPISupport;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.vecmath.Point3f;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * SIMD-enabled JMH benchmark harness for Morton encoding (Epic 1, Bead 1.0).
 * 
 * This harness provides infrastructure for comparing SIMD vs scalar Morton encoding.
 * The actual SIMD implementation will be added in Bead 1.1.
 * 
 * Usage:
 * - Without SIMD: mvn test -Dtest=SIMDMortonBenchmarkHarness
 * - With SIMD: mvn test -Dtest=SIMDMortonBenchmarkHarness -Psimd-preview
 * 
 * @author Hal Hildebrand
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class SIMDMortonBenchmarkHarness {

    @Param({"10", "15", "20"})
    private byte level;

    @Param({"false", "true"})
    private boolean useSIMD;

    private Point3f[] testPoints;
    private int[] testX;
    private int[] testY;
    private int[] testZ;
    private long[] testEncoded;
    
    private static final int SAMPLE_SIZE = 10000;

    @Setup
    public void setup() {
        // Configure SIMD based on parameter
        if (useSIMD) {
            if (!VectorAPISupport.setEnabled(true)) {
                System.err.println("WARNING: SIMD requested but not available!");
                System.err.println("Status: " + VectorAPISupport.getStatus());
                System.err.println("Run with -Psimd-preview to enable Vector API");
            }
        } else {
            VectorAPISupport.setEnabled(false);
        }
        
        // Log configuration
        System.out.println("=== Benchmark Configuration ===");
        System.out.println("SIMD Requested: " + useSIMD);
        System.out.println("SIMD Available: " + VectorAPISupport.isAvailable());
        System.out.println("CPU Capability: " + VectorAPISupport.getCPUCapability());
        System.out.println("Level: " + level);
        
        // Generate test data
        var random = new Random(42); // Fixed seed for reproducibility
        testPoints = new Point3f[SAMPLE_SIZE];
        testX = new int[SAMPLE_SIZE];
        testY = new int[SAMPLE_SIZE];
        testZ = new int[SAMPLE_SIZE];
        testEncoded = new long[SAMPLE_SIZE];
        
        int maxCoord = Constants.MAX_COORD;
        
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            testX[i] = random.nextInt(maxCoord);
            testY[i] = random.nextInt(maxCoord);
            testZ[i] = random.nextInt(maxCoord);
            testPoints[i] = new Point3f(testX[i], testY[i], testZ[i]);
            testEncoded[i] = MortonCurve.encode(testX[i], testY[i], testZ[i]);
        }
    }

    /**
     * Benchmark Morton encoding (scalar baseline).
     * This establishes the baseline performance before SIMD optimization.
     */
    @Benchmark
    public void benchmarkMortonEncode(Blackhole blackhole) {
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long encoded = MortonCurve.encode(testX[i], testY[i], testZ[i]);
            blackhole.consume(encoded);
        }
    }

    /**
     * Benchmark Morton encoding through Constants.calculateMortonIndex().
     * Includes quantization logic in addition to encoding.
     */
    @Benchmark
    public void benchmarkCalculateMortonIndex(Blackhole blackhole) {
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long index = Constants.calculateMortonIndex(testPoints[i], level);
            blackhole.consume(index);
        }
    }

    /**
     * Benchmark Morton decoding.
     * Useful for understanding round-trip performance.
     */
    @Benchmark
    public void benchmarkMortonDecode(Blackhole blackhole) {
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int[] decoded = MortonCurve.decode(testEncoded[i]);
            blackhole.consume(decoded);
        }
    }

    /**
     * Benchmark full round-trip: encode then decode.
     * Measures combined overhead of both operations.
     */
    @Benchmark
    public void benchmarkMortonRoundTrip(Blackhole blackhole) {
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long encoded = MortonCurve.encode(testX[i], testY[i], testZ[i]);
            int[] decoded = MortonCurve.decode(encoded);
            blackhole.consume(decoded[0] + decoded[1] + decoded[2]);
        }
    }

    /**
     * Benchmark batch encoding (future SIMD optimization target).
     * Currently uses scalar implementation, will be optimized in Bead 1.1.
     */
    @Benchmark
    public void benchmarkBatchEncode(Blackhole blackhole) {
        // Batch size aligned to vector width
        int batchSize = VectorAPISupport.getCPUCapability().getLanes() * 2;
        
        for (int i = 0; i < SAMPLE_SIZE; i += batchSize) {
            int limit = Math.min(i + batchSize, SAMPLE_SIZE);
            for (int j = i; j < limit; j++) {
                long encoded = MortonCurve.encode(testX[j], testY[j], testZ[j]);
                blackhole.consume(encoded);
            }
        }
    }

    /**
     * Main method for running benchmarks directly.
     */
    public static void main(String[] args) throws Exception {
        // Print environment information
        System.out.println("=== SIMD Morton Encoding Benchmark ===");
        System.out.println("Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("Vector API Status: " + VectorAPISupport.getStatus());
        System.out.println("CPU Capability: " + VectorAPISupport.getCPUCapability());
        System.out.println();
        
        // Run JMH
        org.openjdk.jmh.Main.main(args);
    }
}
