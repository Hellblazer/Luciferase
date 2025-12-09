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

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.vecmath.Point3f;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Baseline benchmark for Morton encoding operations (Epic 1).
 * 
 * Measures the performance of Morton encoding to establish baseline metrics
 * before SIMD optimizations. Target improvement: 2-4x speedup with SIMD.
 * 
 * @author hal.hildebrand
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class MortonEncodingBaselineBenchmark {

    @Param({"10", "15", "20"})
    private byte level;

    private Point3f[] testPoints;
    private int[] testX;
    private int[] testY;
    private int[] testZ;
    
    private static final int SAMPLE_SIZE = 10000;

    @Setup
    public void setup() {
        var random = new Random(42); // Fixed seed for reproducibility
        testPoints = new Point3f[SAMPLE_SIZE];
        testX = new int[SAMPLE_SIZE];
        testY = new int[SAMPLE_SIZE];
        testZ = new int[SAMPLE_SIZE];
        
        int maxCoord = Constants.MAX_COORD;
        
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            // Generate random coordinates within valid range
            testX[i] = random.nextInt(maxCoord);
            testY[i] = random.nextInt(maxCoord);
            testZ[i] = random.nextInt(maxCoord);
            testPoints[i] = new Point3f(testX[i], testY[i], testZ[i]);
        }
    }

    /**
     * Benchmark direct Morton encoding using MortonCurve.encode()
     * This is the core operation that will be optimized with SIMD in Epic 1.
     */
    @Benchmark
    public void benchmarkMortonEncode(Blackhole blackhole) {
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long encoded = MortonCurve.encode(testX[i], testY[i], testZ[i]);
            blackhole.consume(encoded);
        }
    }

    /**
     * Benchmark Morton encoding through Constants.calculateMortonIndex()
     * This includes quantization logic in addition to encoding.
     */
    @Benchmark
    public void benchmarkCalculateMortonIndex(Blackhole blackhole) {
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long index = Constants.calculateMortonIndex(testPoints[i], level);
            blackhole.consume(index);
        }
    }

    /**
     * Benchmark Morton decoding using MortonCurve.decode()
     * Useful for understanding round-trip performance.
     */
    @Benchmark
    public void benchmarkMortonDecode(Blackhole blackhole) {
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long encoded = MortonCurve.encode(testX[i], testY[i], testZ[i]);
            int[] decoded = MortonCurve.decode(encoded);
            blackhole.consume(decoded);
        }
    }

    /**
     * Benchmark full round-trip: encode then decode
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

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
