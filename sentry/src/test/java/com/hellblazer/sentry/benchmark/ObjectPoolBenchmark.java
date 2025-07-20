package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Benchmark to measure the performance improvement from object pooling
 */
public class ObjectPoolBenchmark {
    
    private static MutableGrid benchmarkGrid;
    
    public static void main(String[] args) {
        System.out.println("Sentry Object Pool Benchmark - Phase 1.3");
        System.out.println("========================================\n");
        
        // Create a grid for benchmarking
        benchmarkGrid = new MutableGrid();
        
        // Warm up
        System.out.println("Warming up...");
        runBenchmark(100, true);
        
        // Actual benchmark
        System.out.println("\nRunning benchmark...\n");
        runBenchmark(1000, false);
        
        // Print pool statistics (using reflection to access the pool)
        System.out.println("\nPool Statistics:");
        System.out.println(getPoolStats());
    }
    
    private static String getPoolStats() {
        try {
            java.lang.reflect.Method getPoolMethod = MutableGrid.class.getDeclaredMethod("getPool");
            getPoolMethod.setAccessible(true);
            TetrahedronPool pool = (TetrahedronPool) getPoolMethod.invoke(benchmarkGrid);
            return pool.getStatistics();
        } catch (Exception e) {
            return "Unable to get pool statistics: " + e.getMessage();
        }
    }
    
    private static String getPoolStatsForGrid(MutableGrid grid) {
        try {
            java.lang.reflect.Method getPoolMethod = MutableGrid.class.getDeclaredMethod("getPool");
            getPoolMethod.setAccessible(true);
            TetrahedronPool pool = (TetrahedronPool) getPoolMethod.invoke(grid);
            return pool.getStatistics();
        } catch (Exception e) {
            return "Unable to get pool statistics: " + e.getMessage();
        }
    }
    
    private static void runBenchmark(int iterations, boolean warmup) {
        Random random = new Random(42);
        
        // Test 1: Measure insertion performance with object pooling
        if (!warmup) {
            System.out.println("=== Insertion Performance with Object Pooling ===");
        }
        
        MutableGrid grid = new MutableGrid();
        
        // Initial grid setup
        for (int i = 0; i < 100; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        // Measure insertions
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
        
        if (!warmup && successfulInsertions > 0) {
            System.out.printf("Average insertion time: %.2f µs\n", 
                (double)totalTime / successfulInsertions / 1000);
            System.out.printf("Successful insertions: %d/%d\n", 
                successfulInsertions, iterations);
            
            // Print pool statistics from the grid used in this benchmark
            System.out.println("\nPool Statistics for this run:");
            System.out.println(getPoolStatsForGrid(grid));
        }
        
        // Test 2: Memory allocation pressure test
        if (!warmup) {
            System.out.println("\n=== Memory Allocation Pressure Test ===");
            testMemoryPressure();
        }
    }
    
    private static void testMemoryPressure() {
        // Force garbage collection before test
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // Create many grids to stress test object allocation
        List<MutableGrid> grids = new ArrayList<>();
        Random random = new Random(42);
        
        for (int g = 0; g < 10; g++) {
            MutableGrid grid = new MutableGrid();
            
            // Add vertices to trigger many flip operations
            for (int i = 0; i < 100; i++) {
                float x = random.nextFloat() * 100;
                float y = random.nextFloat() * 100;
                float z = random.nextFloat() * 100;
                grid.track(new Point3f(x, y, z), random);
            }
            
            grids.add(grid);
        }
        
        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        System.out.printf("Memory used: %.2f MB\n", (memAfter - memBefore) / (1024.0 * 1024.0));
        System.out.printf("Grids created: %d\n", grids.size());
        System.out.printf("Approximate vertices per grid: 100\n");
    }
}