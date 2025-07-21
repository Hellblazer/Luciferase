package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Benchmark to test early exit optimizations
 */
public class EarlyExitOptimizationBenchmark {
    
    public static void main(String[] args) {
        System.out.println("Sentry Early Exit Optimization Benchmark - Phase 2.2");
        System.out.println("==================================================\n");
        
        // Warm up
        System.out.println("Warming up...");
        runBenchmark(100, true);
        
        // Actual benchmark
        System.out.println("\nRunning benchmark...\n");
        runBenchmark(1000, false);
    }
    
    private static void runBenchmark(int iterations, boolean warmup) {
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
            System.out.println("=== Insertion Performance with Early Exit Optimizations ===");
            
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
            
            System.out.printf("Average insertion time: %.2f µs\n", 
                (double)totalTime / successfulInsertions / 1000);
            System.out.printf("Successful insertions: %d/%d\n", 
                successfulInsertions, iterations);
            
            // Compare with previous results
            System.out.println("\n=== Performance Comparison ===");
            System.out.println("Phase 2.1 (patch optimization): 8.89 µs");
            System.out.printf("Phase 2.2 (early exit optimizations): %.2f µs\n", 
                (double)totalTime / successfulInsertions / 1000);
            
            // Calculate improvement
            double baseline = 8.89;
            double current = (double)totalTime / successfulInsertions / 1000;
            double improvement = ((baseline - current) / baseline) * 100;
            System.out.printf("Improvement: %.1f%%\n", improvement);
        }
    }
}