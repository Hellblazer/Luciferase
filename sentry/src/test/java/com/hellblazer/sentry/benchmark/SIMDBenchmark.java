package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import java.util.Random;

/**
 * Benchmark to compare SIMD vs Scalar geometric predicates performance
 */
public class SIMDBenchmark {
    
    public static void main(String[] args) {
        System.out.println("SIMD Geometric Predicates Benchmark");
        System.out.println("===================================\n");
        
        // Check SIMD availability
        System.out.println("SIMD Support Status: " + SIMDSupport.getStatus());
        System.out.println();
        
        // Run benchmarks
        benchmarkOrientation();
        benchmarkInSphere();
        benchmarkBatchOperations();
    }
    
    private static void benchmarkOrientation() {
        System.out.println("=== Orientation Predicate Benchmark ===");
        
        Random random = new Random(42);
        int iterations = 1_000_000;
        
        // Generate test data
        double[] coords = new double[12];
        for (int i = 0; i < coords.length; i++) {
            coords[i] = random.nextDouble() * 100;
        }
        
        // Test scalar implementation
        GeometricPredicates scalar = new ScalarGeometricPredicates();
        long startScalar = System.nanoTime();
        double sumScalar = 0;
        for (int i = 0; i < iterations; i++) {
            sumScalar += scalar.orientation(
                coords[0], coords[1], coords[2],
                coords[3], coords[4], coords[5],
                coords[6], coords[7], coords[8],
                coords[9], coords[10], coords[11]
            );
        }
        long timeScalar = System.nanoTime() - startScalar;
        
        // Test SIMD implementation if available
        if (SIMDSupport.isAvailable()) {
            try {
                GeometricPredicates simd = GeometricPredicatesFactory.create();
                long startSIMD = System.nanoTime();
                double sumSIMD = 0;
                for (int i = 0; i < iterations; i++) {
                    sumSIMD += simd.orientation(
                        coords[0], coords[1], coords[2],
                        coords[3], coords[4], coords[5],
                        coords[6], coords[7], coords[8],
                        coords[9], coords[10], coords[11]
                    );
                }
                long timeSIMD = System.nanoTime() - startSIMD;
                
                System.out.printf("Scalar: %.2f ns/op (sum=%.2f)\n", 
                    (double)timeScalar / iterations, sumScalar);
                System.out.printf("SIMD:   %.2f ns/op (sum=%.2f)\n", 
                    (double)timeSIMD / iterations, sumSIMD);
                System.out.printf("Speedup: %.2fx\n\n", 
                    (double)timeScalar / timeSIMD);
            } catch (Exception e) {
                System.out.println("SIMD not available: " + e.getMessage());
            }
        } else {
            System.out.printf("Scalar: %.2f ns/op (sum=%.2f)\n", 
                (double)timeScalar / iterations, sumScalar);
            System.out.println("SIMD not available for comparison\n");
        }
    }
    
    private static void benchmarkInSphere() {
        System.out.println("=== InSphere Predicate Benchmark ===");
        
        Random random = new Random(42);
        int iterations = 1_000_000;
        
        // Generate test data
        double[] coords = new double[15];
        for (int i = 0; i < coords.length; i++) {
            coords[i] = random.nextDouble() * 100;
        }
        
        // Test scalar implementation
        GeometricPredicates scalar = new ScalarGeometricPredicates();
        long startScalar = System.nanoTime();
        double sumScalar = 0;
        for (int i = 0; i < iterations; i++) {
            sumScalar += scalar.inSphere(
                coords[0], coords[1], coords[2],
                coords[3], coords[4], coords[5],
                coords[6], coords[7], coords[8],
                coords[9], coords[10], coords[11],
                coords[12], coords[13], coords[14]
            );
        }
        long timeScalar = System.nanoTime() - startScalar;
        
        // Test SIMD implementation if available
        if (SIMDSupport.isAvailable()) {
            try {
                GeometricPredicates simd = GeometricPredicatesFactory.create();
                long startSIMD = System.nanoTime();
                double sumSIMD = 0;
                for (int i = 0; i < iterations; i++) {
                    sumSIMD += simd.inSphere(
                        coords[0], coords[1], coords[2],
                        coords[3], coords[4], coords[5],
                        coords[6], coords[7], coords[8],
                        coords[9], coords[10], coords[11],
                        coords[12], coords[13], coords[14]
                    );
                }
                long timeSIMD = System.nanoTime() - startSIMD;
                
                System.out.printf("Scalar: %.2f ns/op (sum=%.2f)\n", 
                    (double)timeScalar / iterations, sumScalar);
                System.out.printf("SIMD:   %.2f ns/op (sum=%.2f)\n", 
                    (double)timeSIMD / iterations, sumSIMD);
                System.out.printf("Speedup: %.2fx\n\n", 
                    (double)timeScalar / timeSIMD);
            } catch (Exception e) {
                System.out.println("SIMD not available: " + e.getMessage());
            }
        } else {
            System.out.printf("Scalar: %.2f ns/op (sum=%.2f)\n", 
                (double)timeScalar / iterations, sumScalar);
            System.out.println("SIMD not available for comparison\n");
        }
    }
    
    private static void benchmarkBatchOperations() {
        System.out.println("=== Batch Operations Benchmark ===");
        
        Random random = new Random(42);
        int batchSize = 1000;
        int iterations = 1000;
        
        // Generate test data
        double[] qx = new double[batchSize];
        double[] qy = new double[batchSize];
        double[] qz = new double[batchSize];
        for (int i = 0; i < batchSize; i++) {
            qx[i] = random.nextDouble() * 100;
            qy[i] = random.nextDouble() * 100;
            qz[i] = random.nextDouble() * 100;
        }
        
        double ax = random.nextDouble() * 100;
        double ay = random.nextDouble() * 100;
        double az = random.nextDouble() * 100;
        double bx = random.nextDouble() * 100;
        double by = random.nextDouble() * 100;
        double bz = random.nextDouble() * 100;
        double cx = random.nextDouble() * 100;
        double cy = random.nextDouble() * 100;
        double cz = random.nextDouble() * 100;
        
        // Test scalar batch
        GeometricPredicates scalar = new ScalarGeometricPredicates();
        long startScalar = System.nanoTime();
        double sumScalar = 0;
        for (int i = 0; i < iterations; i++) {
            double[] results = scalar.batchOrientation(qx, qy, qz, ax, ay, az, bx, by, bz, cx, cy, cz);
            for (double r : results) sumScalar += r;
        }
        long timeScalar = System.nanoTime() - startScalar;
        
        // Test SIMD batch if available
        if (SIMDSupport.isAvailable()) {
            try {
                GeometricPredicates simd = GeometricPredicatesFactory.create();
                long startSIMD = System.nanoTime();
                double sumSIMD = 0;
                for (int i = 0; i < iterations; i++) {
                    double[] results = simd.batchOrientation(qx, qy, qz, ax, ay, az, bx, by, bz, cx, cy, cz);
                    for (double r : results) sumSIMD += r;
                }
                long timeSIMD = System.nanoTime() - startSIMD;
                
                System.out.printf("Batch Orientation (%d points):\n", batchSize);
                System.out.printf("Scalar: %.2f ms/batch (sum=%.2f)\n", 
                    (double)timeScalar / iterations / 1_000_000, sumScalar);
                System.out.printf("SIMD:   %.2f ms/batch (sum=%.2f)\n", 
                    (double)timeSIMD / iterations / 1_000_000, sumSIMD);
                System.out.printf("Speedup: %.2fx\n\n", 
                    (double)timeScalar / timeSIMD);
            } catch (Exception e) {
                System.out.println("SIMD not available: " + e.getMessage());
            }
        } else {
            System.out.printf("Batch Orientation (%d points):\n", batchSize);
            System.out.printf("Scalar: %.2f ms/batch (sum=%.2f)\n", 
                (double)timeScalar / iterations / 1_000_000, sumScalar);
            System.out.println("SIMD not available for comparison\n");
        }
    }
}