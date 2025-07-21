package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Benchmark to test alternative optimizations (Phase 2.3)
 */
public class AlternativeOptimizationBenchmark {
    
    public static void main(String[] args) {
        System.out.println("Sentry Alternative Optimization Benchmark - Phase 2.3");
        System.out.println("====================================================\n");
        
        // Warm up both implementations
        System.out.println("Warming up...");
        System.setProperty("sentry.useOptimizedFlip", "false");
        runBenchmark(100, true);
        System.setProperty("sentry.useOptimizedFlip", "true");
        runBenchmark(100, true);
        
        // Run baseline (without optimization)
        System.out.println("\nRunning baseline (without optimization)...");
        System.setProperty("sentry.useOptimizedFlip", "false");
        double baselineTime = runBenchmark(1000, false);
        
        // Run with optimization
        System.out.println("\nRunning with alternative optimizations...");
        System.setProperty("sentry.useOptimizedFlip", "true");
        double optimizedTime = runBenchmark(1000, false);
        
        // Compare results
        System.out.println("\n=== Performance Comparison ===");
        System.out.printf("Baseline (Phase 2.2): %.2f µs\n", baselineTime);
        System.out.printf("Alternative optimizations: %.2f µs\n", optimizedTime);
        double improvement = ((baselineTime - optimizedTime) / baselineTime) * 100;
        System.out.printf("Improvement: %.1f%%\n", improvement);
    }
    
    private static double runBenchmark(int iterations, boolean warmup) {
        Random random = new Random(42);
        
        // Create a grid with many tetrahedra
        MutableGrid grid = new MutableGrid();
        
        // Add vertices to create tetrahedra
        for (int i = 0; i < 200; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        if (!warmup) {
            System.out.println("=== Insertion Performance ===");
            
            // Measure flip operations
            long totalTime = 0;
            int successfulInsertions = 0;
            
            for (int i = 0; i < iterations; i++) {
                float x = random.nextFloat() * 100;
                float y = random.nextFloat() * 100;
                float z = random.nextFloat() * 100;
                
                long start = System.nanoTime();
                Vertex v = grid.track(new Point3f(x, y, z), random);
                long end = System.nanoTime();
                
                if (v != null) {
                    totalTime += (end - start);
                    successfulInsertions++;
                }
            }
            
            double avgTime = (double)totalTime / successfulInsertions / 1000;
            System.out.printf("Average insertion time: %.2f µs\n", avgTime);
            System.out.printf("Successful insertions: %d/%d\n", 
                successfulInsertions, iterations);
            
            return avgTime;
        }
        
        return 0;
    }
}