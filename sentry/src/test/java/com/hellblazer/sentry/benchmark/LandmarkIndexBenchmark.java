package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Benchmark to test landmark index performance (Phase 3.3)
 */
public class LandmarkIndexBenchmark {
    
    public static void main(String[] args) {
        System.out.println("Sentry Landmark Index Benchmark - Phase 3.3");
        System.out.println("===========================================\n");
        
        // Test with different grid sizes
        int[] gridSizes = {100, 500, 1000, 2000};
        
        for (int gridSize : gridSizes) {
            System.out.println("\n=== Grid size: " + gridSize + " vertices ===");
            benchmarkGridSize(gridSize);
        }
    }
    
    private static void benchmarkGridSize(int vertexCount) {
        Random random = new Random(42);
        
        // Benchmark without landmark index
        System.setProperty("sentry.useLandmarkIndex", "false");
        long baselineTime = benchmarkLocate(vertexCount, random, false);
        
        // Benchmark with landmark index
        System.setProperty("sentry.useLandmarkIndex", "true");
        long landmarkTime = benchmarkLocate(vertexCount, random, true);
        
        // Calculate improvement
        double improvement = ((double)(baselineTime - landmarkTime) / baselineTime) * 100;
        System.out.printf("Improvement with landmarks: %.1f%%\n", improvement);
    }
    
    private static long benchmarkLocate(int vertexCount, Random random, boolean useLandmarks) {
        // Create and populate grid
        MutableGrid grid = new MutableGrid();
        
        // Insert vertices to build the mesh
        for (int i = 0; i < vertexCount; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        // Generate query points
        int queryCount = 1000;
        Point3f[] queries = new Point3f[queryCount];
        for (int i = 0; i < queryCount; i++) {
            queries[i] = new Point3f(
                random.nextFloat() * 100,
                random.nextFloat() * 100,
                random.nextFloat() * 100
            );
        }
        
        // Warm up
        for (int i = 0; i < 100; i++) {
            grid.locate(queries[i % queries.length], random);
        }
        
        // Benchmark locate operations
        long startTime = System.nanoTime();
        int found = 0;
        
        for (Point3f query : queries) {
            Tetrahedron t = grid.locate(query, random);
            if (t != null) {
                found++;
            }
        }
        
        long totalTime = System.nanoTime() - startTime;
        
        // Print results
        System.out.printf("%s: %d queries, %d found, %.2f ms total, %.2f µs/query\n",
            useLandmarks ? "With landmarks" : "Without landmarks",
            queryCount, found, 
            totalTime / 1_000_000.0,
            totalTime / 1000.0 / queryCount);
        
        if (useLandmarks) {
            System.out.println("Landmark statistics: " + grid.getLandmarkStatistics());
        }
        
        return totalTime;
    }
}