package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Benchmark to test patch() method optimization
 */
public class PatchOptimizationBenchmark {
    
    public static void main(String[] args) {
        System.out.println("Sentry patch() Optimization Benchmark - Phase 2.1");
        System.out.println("===============================================\n");
        
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
            System.out.println("=== Flip Operation Performance ===");
            
            // Measure flip operations which use patch()
            long totalTime = 0;
            int successfulFlips = 0;
            
            for (int i = 0; i < iterations; i++) {
                float x = random.nextFloat() * 100;
                float y = random.nextFloat() * 100;
                float z = random.nextFloat() * 100;
                
                long start = System.nanoTime();
                Vertex v = grid.track(new Point3f(x, y, z), random);
                long end = System.nanoTime();
                
                if (v != null) {
                    totalTime += (end - start);
                    successfulFlips++;
                }
            }
            
            System.out.printf("Average insertion time: %.2f µs\n", 
                (double)totalTime / successfulFlips / 1000);
            System.out.printf("Successful insertions: %d/%d\n", 
                successfulFlips, iterations);
            
            // Compare with previous results
            System.out.println("\n=== Performance Comparison ===");
            System.out.println("Phase 1.3 (object pooling): 9.90 µs");
            System.out.printf("Phase 2.1 (patch optimization): %.2f µs\n", 
                (double)totalTime / successfulFlips / 1000);
        }
    }
}