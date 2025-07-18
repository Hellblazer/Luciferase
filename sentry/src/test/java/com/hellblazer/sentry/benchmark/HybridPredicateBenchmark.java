package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Benchmark to test hybrid predicate performance (Phase 4.1)
 */
public class HybridPredicateBenchmark {
    
    public static void main(String[] args) {
        System.out.println("Sentry Hybrid Predicate Benchmark - Phase 4.1");
        System.out.println("=============================================\n");
        
        // Test with different grid sizes
        int[] gridSizes = {100, 500, 1000, 2000, 5000};
        
        // Warm up
        System.out.println("Warming up...");
        benchmarkGridSize(100, 5);
        
        System.out.println("\n=== Benchmark Results ===\n");
        
        for (int gridSize : gridSizes) {
            System.out.println("\n--- Grid size: " + gridSize + " vertices ---");
            benchmarkGridSize(gridSize, 20);
        }
    }
    
    private static void benchmarkGridSize(int vertexCount, int iterations) {
        Random random = new Random(42);
        
        // Benchmark with standard predicates
        System.setProperty("sentry.useHybridPredicates", "false");
        GeometricPredicatesFactory.reset();
        // Force Vertex class to reload predicates
        try {
            java.lang.reflect.Field predicatesField = Vertex.class.getDeclaredField("PREDICATES");
            predicatesField.setAccessible(true);
            predicatesField.set(null, GeometricPredicatesFactory.getInstance());
        } catch (Exception e) {
            System.err.println("Failed to reset Vertex predicates: " + e);
        }
        long standardTime = benchmarkInsertion(vertexCount, iterations, random, false);
        
        // Benchmark with hybrid predicates
        System.setProperty("sentry.useHybridPredicates", "true");
        GeometricPredicatesFactory.reset();
        // Force Vertex class to reload predicates
        try {
            java.lang.reflect.Field predicatesField = Vertex.class.getDeclaredField("PREDICATES");
            predicatesField.setAccessible(true);
            predicatesField.set(null, GeometricPredicatesFactory.getInstance());
        } catch (Exception e) {
            System.err.println("Failed to reset Vertex predicates: " + e);
        }
        long hybridTime = benchmarkInsertion(vertexCount, iterations, random, true);
        
        // Calculate improvement
        double improvement = ((double)(standardTime - hybridTime) / standardTime) * 100;
        System.out.printf("Improvement with hybrid predicates: %.1f%%\n", improvement);
    }
    
    private static long benchmarkInsertion(int vertexCount, int iterations, Random random, boolean isHybrid) {
        long totalTime = 0;
        
        for (int iter = 0; iter < iterations; iter++) {
            // Create and populate grid
            MutableGrid grid = new MutableGrid();
            
            // Generate points
            Point3f[] points = new Point3f[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                points[i] = new Point3f(
                    random.nextFloat() * 100,
                    random.nextFloat() * 100,
                    random.nextFloat() * 100
                );
            }
            
            // Time the insertions
            long startTime = System.nanoTime();
            
            for (Point3f point : points) {
                grid.track(point, random);
            }
            
            long endTime = System.nanoTime();
            totalTime += (endTime - startTime);
        }
        
        long avgTime = totalTime / iterations;
        System.out.printf("%s predicates: %.2f ms average for %d insertions\n",
            isHybrid ? "Hybrid" : "Standard",
            avgTime / 1_000_000.0,
            vertexCount);
        
        // Print hybrid statistics if available
        if (isHybrid) {
            GeometricPredicates predicates = GeometricPredicatesFactory.getInstance();
            if (predicates instanceof HybridGeometricPredicates) {
                System.out.println(((HybridGeometricPredicates) predicates).getStatistics());
            }
        }
        
        return avgTime;
    }
}