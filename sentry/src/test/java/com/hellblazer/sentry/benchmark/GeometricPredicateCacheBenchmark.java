package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Benchmark to test geometric predicate caching performance
 */
public class GeometricPredicateCacheBenchmark {
    
    public static void main(String[] args) {
        System.out.println("Sentry Geometric Predicate Cache Benchmark - Phase 2.2");
        System.out.println("====================================================\n");
        
        // Clear cache before starting
        GeometricPredicateCache.getInstance().clear();
        
        // Warm up
        System.out.println("Warming up...");
        runBenchmark(100, true);
        
        // Clear cache and run actual benchmark
        GeometricPredicateCache.getInstance().clear();
        System.out.println("\nRunning benchmark...\n");
        runBenchmark(1000, false);
        
        // Print cache statistics
        System.out.println("\nCache Statistics:");
        System.out.println(GeometricPredicateCache.getInstance().getStatistics());
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
            System.out.println("=== Insertion Performance with Predicate Caching ===");
            
            // Measure flip operations which use geometric predicates
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
            System.out.printf("Phase 2.2 (predicate caching): %.2f µs\n", 
                (double)totalTime / successfulInsertions / 1000);
        }
    }
}