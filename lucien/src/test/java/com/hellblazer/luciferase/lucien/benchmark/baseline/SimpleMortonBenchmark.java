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

import javax.vecmath.Point3f;
import java.util.Random;

/**
 * Simple manual benchmark for Morton encoding operations.
 * Establishes baseline metrics without JMH complexity.
 * 
 * @author hal.hildebrand
 */
public class SimpleMortonBenchmark {
    
    private static final int SAMPLE_SIZE = 100_000;
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 10;
    
    public static void main(String[] args) {
        System.out.println("=== Morton Encoding Baseline Benchmark ===");
        System.out.println("Sample size: " + SAMPLE_SIZE);
        System.out.println("Warmup iterations: " + WARMUP_ITERATIONS);
        System.out.println("Measurement iterations: " + MEASUREMENT_ITERATIONS);
        System.out.println();
        
        byte[] levels = {10, 15, 20};
        
        for (byte level : levels) {
            System.out.println("--- Level: " + level + " ---");
            runBenchmarks(level);
            System.out.println();
        }
    }
    
    private static void runBenchmarks(byte level) {
        var random = new Random(42);
        int maxCoord = Constants.MAX_COORD;
        
        int[] testX = new int[SAMPLE_SIZE];
        int[] testY = new int[SAMPLE_SIZE];
        int[] testZ = new int[SAMPLE_SIZE];
        Point3f[] testPoints = new Point3f[SAMPLE_SIZE];
        
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            testX[i] = random.nextInt(maxCoord);
            testY[i] = random.nextInt(maxCoord);
            testZ[i] = random.nextInt(maxCoord);
            testPoints[i] = new Point3f(testX[i], testY[i], testZ[i]);
        }
        
        // Benchmark 1: Morton Encode
        System.out.println("benchmarkMortonEncode:");
        benchmarkMortonEncode(testX, testY, testZ);
        
        // Benchmark 2: Calculate Morton Index
        System.out.println("benchmarkCalculateMortonIndex:");
        benchmarkCalculateMortonIndex(testPoints, level);
        
        // Benchmark 3: Morton Decode
        System.out.println("benchmarkMortonDecode:");
        benchmarkMortonDecode(testX, testY, testZ);
        
        // Benchmark 4: Round Trip
        System.out.println("benchmarkMortonRoundTrip:");
        benchmarkMortonRoundTrip(testX, testY, testZ);
    }
    
    private static void benchmarkMortonEncode(int[] testX, int[] testY, int[] testZ) {
        // Warmup
        for (int iter = 0; iter < WARMUP_ITERATIONS; iter++) {
            long sum = 0;
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                sum += MortonCurve.encode(testX[i], testY[i], testZ[i]);
            }
            blackhole(sum);
        }
        
        // Measurement
        long totalNanos = 0;
        for (int iter = 0; iter < MEASUREMENT_ITERATIONS; iter++) {
            long start = System.nanoTime();
            long sum = 0;
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                sum += MortonCurve.encode(testX[i], testY[i], testZ[i]);
            }
            long end = System.nanoTime();
            totalNanos += (end - start);
            blackhole(sum);
        }
        
        double avgNanos = totalNanos / (double) MEASUREMENT_ITERATIONS;
        double opsPerSecond = (SAMPLE_SIZE * 1_000_000_000.0) / avgNanos;
        System.out.printf("  Average time: %.2f ms%n", avgNanos / 1_000_000.0);
        System.out.printf("  Throughput: %.2f ops/sec%n", opsPerSecond);
    }
    
    private static void benchmarkCalculateMortonIndex(Point3f[] testPoints, byte level) {
        // Warmup
        for (int iter = 0; iter < WARMUP_ITERATIONS; iter++) {
            long sum = 0;
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                sum += Constants.calculateMortonIndex(testPoints[i], level);
            }
            blackhole(sum);
        }
        
        // Measurement
        long totalNanos = 0;
        for (int iter = 0; iter < MEASUREMENT_ITERATIONS; iter++) {
            long start = System.nanoTime();
            long sum = 0;
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                sum += Constants.calculateMortonIndex(testPoints[i], level);
            }
            long end = System.nanoTime();
            totalNanos += (end - start);
            blackhole(sum);
        }
        
        double avgNanos = totalNanos / (double) MEASUREMENT_ITERATIONS;
        double opsPerSecond = (SAMPLE_SIZE * 1_000_000_000.0) / avgNanos;
        System.out.printf("  Average time: %.2f ms%n", avgNanos / 1_000_000.0);
        System.out.printf("  Throughput: %.2f ops/sec%n", opsPerSecond);
    }
    
    private static void benchmarkMortonDecode(int[] testX, int[] testY, int[] testZ) {
        // Pre-encode
        long[] encoded = new long[SAMPLE_SIZE];
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            encoded[i] = MortonCurve.encode(testX[i], testY[i], testZ[i]);
        }
        
        // Warmup
        for (int iter = 0; iter < WARMUP_ITERATIONS; iter++) {
            int sum = 0;
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                int[] decoded = MortonCurve.decode(encoded[i]);
                sum += decoded[0] + decoded[1] + decoded[2];
            }
            blackhole(sum);
        }
        
        // Measurement
        long totalNanos = 0;
        for (int iter = 0; iter < MEASUREMENT_ITERATIONS; iter++) {
            long start = System.nanoTime();
            int sum = 0;
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                int[] decoded = MortonCurve.decode(encoded[i]);
                sum += decoded[0] + decoded[1] + decoded[2];
            }
            long end = System.nanoTime();
            totalNanos += (end - start);
            blackhole(sum);
        }
        
        double avgNanos = totalNanos / (double) MEASUREMENT_ITERATIONS;
        double opsPerSecond = (SAMPLE_SIZE * 1_000_000_000.0) / avgNanos;
        System.out.printf("  Average time: %.2f ms%n", avgNanos / 1_000_000.0);
        System.out.printf("  Throughput: %.2f ops/sec%n", opsPerSecond);
    }
    
    private static void benchmarkMortonRoundTrip(int[] testX, int[] testY, int[] testZ) {
        // Warmup
        for (int iter = 0; iter < WARMUP_ITERATIONS; iter++) {
            int sum = 0;
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                long encoded = MortonCurve.encode(testX[i], testY[i], testZ[i]);
                int[] decoded = MortonCurve.decode(encoded);
                sum += decoded[0] + decoded[1] + decoded[2];
            }
            blackhole(sum);
        }
        
        // Measurement
        long totalNanos = 0;
        for (int iter = 0; iter < MEASUREMENT_ITERATIONS; iter++) {
            long start = System.nanoTime();
            int sum = 0;
            for (int i = 0; i < SAMPLE_SIZE; i++) {
                long encoded = MortonCurve.encode(testX[i], testY[i], testZ[i]);
                int[] decoded = MortonCurve.decode(encoded);
                sum += decoded[0] + decoded[1] + decoded[2];
            }
            long end = System.nanoTime();
            totalNanos += (end - start);
            blackhole(sum);
        }
        
        double avgNanos = totalNanos / (double) MEASUREMENT_ITERATIONS;
        double opsPerSecond = (SAMPLE_SIZE * 1_000_000_000.0) / avgNanos;
        System.out.printf("  Average time: %.2f ms%n", avgNanos / 1_000_000.0);
        System.out.printf("  Throughput: %.2f ops/sec%n", opsPerSecond);
    }
    
    private static volatile long blackholeSink;
    private static void blackhole(long value) {
        blackholeSink = value;
    }
}
