/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.sentry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Performance benchmarks for different geometric predicate implementations.
 * 
 * @author hal.hildebrand
 */
public class GeometricPredicatesPerformanceTest {
    
    private static final int WARM_UP_ITERATIONS = 10000;
    private static final int BENCHMARK_ITERATIONS = 100000;
    
    // Test data
    private double[][] orientationData;
    private double[][] inSphereData;
    
    @BeforeEach
    public void setUp() {
        // Generate test data
        generateTestData();
    }
    
    @AfterEach
    public void tearDown() {
        System.clearProperty("sentry.predicates.mode");
        GeometricPredicatesFactory.reset();
    }
    
    /**
     * Generate test data for benchmarking
     */
    private void generateTestData() {
        Random random = new Random(0x12345); // Fixed seed for reproducibility
        
        // Generate orientation test data (4 points)
        orientationData = new double[BENCHMARK_ITERATIONS][12];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            for (int j = 0; j < 12; j++) {
                orientationData[i][j] = 100 + random.nextDouble() * 800;
            }
        }
        
        // Generate inSphere test data (5 points)
        inSphereData = new double[BENCHMARK_ITERATIONS][15];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            for (int j = 0; j < 15; j++) {
                inSphereData[i][j] = 100 + random.nextDouble() * 800;
            }
        }
    }
    
    @Test
    @DisplayName("Benchmark all predicate implementations")
    public void benchmarkAllImplementations() {
        System.out.println("\n=== Geometric Predicates Performance Benchmark ===");
        System.out.println("Warm-up iterations: " + WARM_UP_ITERATIONS);
        System.out.println("Benchmark iterations: " + BENCHMARK_ITERATIONS);
        System.out.println();
        
        // Test each implementation
        benchmarkImplementation("SCALAR");
        
        if (SIMDSupport.isAvailable()) {
            benchmarkImplementation("SIMD");
        } else {
            System.out.println("SIMD not available on this platform - skipping");
        }
        
        benchmarkImplementation("EXACT");
        benchmarkImplementation("ADAPTIVE");
        benchmarkImplementation("HYBRID");
        
        // Special test for adaptive with custom epsilon
        System.setProperty("sentry.adaptive.epsilon", "1e-8");
        benchmarkImplementation("ADAPTIVE");
        System.clearProperty("sentry.adaptive.epsilon");
    }
    
    /**
     * Benchmark a specific implementation
     */
    private void benchmarkImplementation(String mode) {
        System.setProperty("sentry.predicates.mode", mode.toLowerCase());
        GeometricPredicates predicates = GeometricPredicatesFactory.create();
        
        System.out.println("--- " + predicates.getImplementationName() + " ---");
        
        // Warm up
        warmUp(predicates);
        
        // Benchmark orientation
        long orientationTime = benchmarkOrientation(predicates);
        double orientationOpsPerSec = (BENCHMARK_ITERATIONS * 1000.0) / orientationTime;
        
        // Benchmark inSphere
        long inSphereTime = benchmarkInSphere(predicates);
        double inSphereOpsPerSec = (BENCHMARK_ITERATIONS * 1000.0) / inSphereTime;
        
        System.out.printf("  Orientation: %d ms (%.0f ops/sec)\n", orientationTime, orientationOpsPerSec);
        System.out.printf("  InSphere:    %d ms (%.0f ops/sec)\n", inSphereTime, inSphereOpsPerSec);
        
        // Print statistics if available
        if (predicates instanceof HybridGeometricPredicates) {
            HybridGeometricPredicates hybrid = (HybridGeometricPredicates) predicates;
            System.out.println("  " + hybrid.getStatistics().replace("\n", "\n  "));
        } else if (predicates instanceof AdaptiveGeometricPredicates) {
            AdaptiveGeometricPredicates adaptive = (AdaptiveGeometricPredicates) predicates;
            System.out.println("  " + adaptive.getStatistics().replace("\n", "\n  "));
        }
        
        System.out.println();
    }
    
    /**
     * Warm up the JVM
     */
    private void warmUp(GeometricPredicates predicates) {
        for (int i = 0; i < WARM_UP_ITERATIONS; i++) {
            int idx = i % orientationData.length;
            double[] d = orientationData[idx];
            predicates.orientation(d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9], d[10], d[11]);
            
            double[] s = inSphereData[idx];
            predicates.inSphere(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], s[10], s[11], s[12], s[13], s[14]);
        }
    }
    
    /**
     * Benchmark orientation predicate
     */
    private long benchmarkOrientation(GeometricPredicates predicates) {
        long start = System.currentTimeMillis();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            double[] d = orientationData[i];
            predicates.orientation(d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9], d[10], d[11]);
        }
        
        return System.currentTimeMillis() - start;
    }
    
    /**
     * Benchmark inSphere predicate
     */
    private long benchmarkInSphere(GeometricPredicates predicates) {
        long start = System.currentTimeMillis();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            double[] s = inSphereData[i];
            predicates.inSphere(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], s[10], s[11], s[12], s[13], s[14]);
        }
        
        return System.currentTimeMillis() - start;
    }
    
    @Test
    @DisplayName("Benchmark degenerate cases")
    public void benchmarkDegenerateCases() {
        System.out.println("\n=== Degenerate Case Performance Benchmark ===");
        
        // Generate degenerate test data (nearly coplanar points)
        Random random = new Random(0x54321);
        double[][] degenerateOrientation = new double[BENCHMARK_ITERATIONS][12];
        double[][] degenerateInSphere = new double[BENCHMARK_ITERATIONS][15];
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            // Create nearly coplanar points for orientation
            double baseX = 500, baseY = 500, baseZ = 500;
            double epsilon = 1e-6;
            
            for (int j = 0; j < 4; j++) {
                degenerateOrientation[i][j*3] = baseX + (j % 2) * 100 + random.nextGaussian() * epsilon;
                degenerateOrientation[i][j*3+1] = baseY + (j / 2) * 100 + random.nextGaussian() * epsilon;
                degenerateOrientation[i][j*3+2] = baseZ + random.nextGaussian() * epsilon;
            }
            
            // Create nearly cospherical points for inSphere
            double radius = 100;
            for (int j = 0; j < 5; j++) {
                double theta = 2 * Math.PI * j / 4;
                double phi = Math.PI * j / 4;
                degenerateInSphere[i][j*3] = baseX + radius * Math.sin(phi) * Math.cos(theta) + random.nextGaussian() * epsilon;
                degenerateInSphere[i][j*3+1] = baseY + radius * Math.sin(phi) * Math.sin(theta) + random.nextGaussian() * epsilon;
                degenerateInSphere[i][j*3+2] = baseZ + radius * Math.cos(phi) + random.nextGaussian() * epsilon;
            }
        }
        
        // Test each implementation with degenerate data
        for (String mode : Arrays.asList("SCALAR", "EXACT", "ADAPTIVE", "HYBRID")) {
            if (mode.equals("SIMD") && !SIMDSupport.isAvailable()) {
                continue;
            }
            
            System.setProperty("sentry.predicates.mode", mode.toLowerCase());
            GeometricPredicates predicates = GeometricPredicatesFactory.create();
            
            System.out.println("\n--- " + predicates.getImplementationName() + " (degenerate cases) ---");
            
            // Reset statistics if available
            if (predicates instanceof HybridGeometricPredicates) {
                ((HybridGeometricPredicates) predicates).resetStatistics();
            } else if (predicates instanceof AdaptiveGeometricPredicates) {
                ((AdaptiveGeometricPredicates) predicates).resetStatistics();
            }
            
            // Benchmark with degenerate data
            long start = System.currentTimeMillis();
            
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                double[] d = degenerateOrientation[i];
                predicates.orientation(d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9], d[10], d[11]);
            }
            
            long orientationTime = System.currentTimeMillis() - start;
            
            start = System.currentTimeMillis();
            
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                double[] s = degenerateInSphere[i];
                predicates.inSphere(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], s[10], s[11], s[12], s[13], s[14]);
            }
            
            long inSphereTime = System.currentTimeMillis() - start;
            
            System.out.printf("  Orientation: %d ms\n", orientationTime);
            System.out.printf("  InSphere:    %d ms\n", inSphereTime);
            
            // Print statistics to see how often exact computation was needed
            if (predicates instanceof HybridGeometricPredicates) {
                HybridGeometricPredicates hybrid = (HybridGeometricPredicates) predicates;
                System.out.println("  " + hybrid.getStatistics().replace("\n", "\n  "));
            }
        }
    }
}