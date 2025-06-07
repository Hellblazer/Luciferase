package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Performance comparison between original Tetree and optimized TetreeOptimized
 * Demonstrates Phase 2 performance improvements
 */
public class OptimizedTetreePerformanceTest {

    private Tetree<String> originalTetree;
    private TetreeOptimized<String> optimizedTetree;
    private Octree<String> octree;
    
    private static final int DATASET_SIZE = 5000;

    @BeforeEach
    void setUp() {
        originalTetree = new Tetree<>(new TreeMap<>());
        optimizedTetree = new TetreeOptimized<>(new TreeMap<>());
        octree = new Octree<>();
        
        // Populate all data structures with identical data
        populateDataStructures();
    }
    
    private void populateDataStructures() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        
        for (int i = 0; i < DATASET_SIZE; i++) {
            float x = random.nextFloat() * 10000;
            float y = random.nextFloat() * 10000;
            float z = random.nextFloat() * 10000;
            byte level = (byte) random.nextInt(5, 15);
            
            String content = "data-" + i;
            
            originalTetree.insert(new Point3f(x, y, z), level, content);
            optimizedTetree.insert(new Point3f(x, y, z), level, content);
            octree.insert(new Point3f(x, y, z), level, content);
        }
    }

    @Test
    @DisplayName("Compare boundedBy query performance: Original vs Optimized vs Octree")
    void compareBoundedByPerformance() {
        System.out.println("=== BoundedBy Query Performance Comparison ===\n");
        
        var testQueries = new Spatial.Cube[] {
            new Spatial.Cube(2000.0f, 2000.0f, 2000.0f, 500.0f),   // Small query
            new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f),  // Medium query
            new Spatial.Cube(0.0f, 0.0f, 0.0f, 5000.0f)            // Large query
        };
        
        String[] queryNames = {"Small Query", "Medium Query", "Large Query"};
        
        for (int i = 0; i < testQueries.length; i++) {
            var query = testQueries[i];
            String queryName = queryNames[i];
            
            System.out.println("--- " + queryName + " ---");
            
            // Warm up
            warmUp(query);
            
            // Benchmark Original Tetree
            long originalStart = System.nanoTime();
            var originalResults = originalTetree.boundedBy(query).collect(Collectors.toList());
            long originalTime = System.nanoTime() - originalStart;
            
            // Benchmark Optimized Tetree
            long optimizedStart = System.nanoTime();
            var optimizedResults = optimizedTetree.boundedBy(query).collect(Collectors.toList());
            long optimizedTime = System.nanoTime() - optimizedStart;
            
            // Benchmark Octree
            long octreeStart = System.nanoTime();
            var octreeResults = octree.boundedBy(query).collect(Collectors.toList());
            long octreeTime = System.nanoTime() - octreeStart;
            
            // Results
            System.out.printf("Original Tetree:  %d results in %.2f μs%n", 
                originalResults.size(), originalTime / 1000.0);
            System.out.printf("Optimized Tetree: %d results in %.2f μs (%.2fx improvement)%n", 
                optimizedResults.size(), optimizedTime / 1000.0, (double) originalTime / optimizedTime);
            System.out.printf("Octree:           %d results in %.2f μs%n", 
                octreeResults.size(), octreeTime / 1000.0);
            System.out.printf("Tetree vs Octree: %.2fx (Original: %.2fx)%n%n", 
                (double) optimizedTime / octreeTime, (double) originalTime / octreeTime);
        }
    }
    
    @Test
    @DisplayName("Compare bounding query performance: Original vs Optimized vs Octree")
    void compareBoundingPerformance() {
        System.out.println("=== Bounding Query Performance Comparison ===\n");
        
        var testQueries = new Spatial.Cube[] {
            new Spatial.Cube(2000.0f, 2000.0f, 2000.0f, 500.0f),   // Small query
            new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f),  // Medium query
            new Spatial.Cube(0.0f, 0.0f, 0.0f, 5000.0f)            // Large query
        };
        
        String[] queryNames = {"Small Query", "Medium Query", "Large Query"};
        
        for (int i = 0; i < testQueries.length; i++) {
            var query = testQueries[i];
            String queryName = queryNames[i];
            
            System.out.println("--- " + queryName + " ---");
            
            // Warm up
            warmUp(query);
            
            // Benchmark Original Tetree
            long originalStart = System.nanoTime();
            var originalResults = originalTetree.bounding(query).collect(Collectors.toList());
            long originalTime = System.nanoTime() - originalStart;
            
            // Benchmark Optimized Tetree
            long optimizedStart = System.nanoTime();
            var optimizedResults = optimizedTetree.bounding(query).collect(Collectors.toList());
            long optimizedTime = System.nanoTime() - optimizedStart;
            
            // Benchmark Octree
            long octreeStart = System.nanoTime();
            var octreeResults = octree.bounding(query).collect(Collectors.toList());
            long octreeTime = System.nanoTime() - octreeStart;
            
            // Results
            System.out.printf("Original Tetree:  %d results in %.2f μs%n", 
                originalResults.size(), originalTime / 1000.0);
            System.out.printf("Optimized Tetree: %d results in %.2f μs (%.2fx improvement)%n", 
                optimizedResults.size(), optimizedTime / 1000.0, (double) originalTime / optimizedTime);
            System.out.printf("Octree:           %d results in %.2f μs%n", 
                octreeResults.size(), octreeTime / 1000.0);
            System.out.printf("Tetree vs Octree: %.2fx (Original: %.2fx)%n%n", 
                (double) optimizedTime / octreeTime, (double) originalTime / octreeTime);
        }
    }
    
    @Test
    @DisplayName("Test caching effectiveness")
    void testCachingEffectiveness() {
        System.out.println("=== Caching Effectiveness Test ===\n");
        
        var query = new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f);
        
        // Clear caches first
        optimizedTetree.clearOptimizationCaches();
        
        // First query (cold cache)
        long coldStart = System.nanoTime();
        var coldResults = optimizedTetree.boundedBy(query).collect(Collectors.toList());
        long coldTime = System.nanoTime() - coldStart;
        
        // Second identical query (warm cache)
        long warmStart = System.nanoTime();
        var warmResults = optimizedTetree.boundedBy(query).collect(Collectors.toList());
        long warmTime = System.nanoTime() - warmStart;
        
        // Third identical query (hot cache)
        long hotStart = System.nanoTime();
        var hotResults = optimizedTetree.boundedBy(query).collect(Collectors.toList());
        long hotTime = System.nanoTime() - hotStart;
        
        System.out.printf("Cold cache:  %d results in %.2f μs%n", coldResults.size(), coldTime / 1000.0);
        System.out.printf("Warm cache:  %d results in %.2f μs (%.2fx improvement)%n", 
            warmResults.size(), warmTime / 1000.0, (double) coldTime / warmTime);
        System.out.printf("Hot cache:   %d results in %.2f μs (%.2fx improvement)%n", 
            hotResults.size(), hotTime / 1000.0, (double) coldTime / hotTime);
        
        System.out.println("\nCache Statistics:");
        System.out.println(optimizedTetree.getOptimizationStats());
    }
    
    @Test
    @DisplayName("Compare different query patterns")
    void compareQueryPatterns() {
        System.out.println("=== Query Pattern Performance Analysis ===\n");
        
        // Test various query patterns
        testPointQueries("Point Queries");
        testSphereQueries("Sphere Queries");
        testLargeVolumeQueries("Large Volume Queries");
    }
    
    private void testPointQueries(String testName) {
        System.out.println("--- " + testName + " ---");
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int numQueries = 100;
        
        long originalTotal = 0;
        long optimizedTotal = 0;
        long octreeTotal = 0;
        
        for (int i = 0; i < numQueries; i++) {
            float x = random.nextFloat() * 10000;
            float y = random.nextFloat() * 10000;
            float z = random.nextFloat() * 10000;
            var pointQuery = new Spatial.Cube(x, y, z, 100.0f); // Small cube around point
            
            // Original Tetree
            long start = System.nanoTime();
            originalTetree.boundedBy(pointQuery).count();
            originalTotal += System.nanoTime() - start;
            
            // Optimized Tetree
            start = System.nanoTime();
            optimizedTetree.boundedBy(pointQuery).count();
            optimizedTotal += System.nanoTime() - start;
            
            // Octree
            start = System.nanoTime();
            octree.boundedBy(pointQuery).count();
            octreeTotal += System.nanoTime() - start;
        }
        
        System.out.printf("Average over %d queries:%n", numQueries);
        System.out.printf("Original Tetree:  %.2f μs%n", originalTotal / (numQueries * 1000.0));
        System.out.printf("Optimized Tetree: %.2f μs (%.2fx improvement)%n", 
            optimizedTotal / (numQueries * 1000.0), (double) originalTotal / optimizedTotal);
        System.out.printf("Octree:           %.2f μs%n%n", octreeTotal / (numQueries * 1000.0));
    }
    
    private void testSphereQueries(String testName) {
        System.out.println("--- " + testName + " ---");
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int numQueries = 50;
        
        long originalTotal = 0;
        long optimizedTotal = 0;
        long octreeTotal = 0;
        
        for (int i = 0; i < numQueries; i++) {
            float centerX = random.nextFloat() * 8000 + 1000;
            float centerY = random.nextFloat() * 8000 + 1000;
            float centerZ = random.nextFloat() * 8000 + 1000;
            float radius = random.nextFloat() * 1000 + 500;
            var sphereQuery = new Spatial.Sphere(centerX, centerY, centerZ, radius);
            
            // Original Tetree
            long start = System.nanoTime();
            originalTetree.bounding(sphereQuery).count();
            originalTotal += System.nanoTime() - start;
            
            // Optimized Tetree
            start = System.nanoTime();
            optimizedTetree.bounding(sphereQuery).count();
            optimizedTotal += System.nanoTime() - start;
            
            // Octree
            start = System.nanoTime();
            octree.bounding(sphereQuery).count();
            octreeTotal += System.nanoTime() - start;
        }
        
        System.out.printf("Average over %d queries:%n", numQueries);
        System.out.printf("Original Tetree:  %.2f μs%n", originalTotal / (numQueries * 1000.0));
        System.out.printf("Optimized Tetree: %.2f μs (%.2fx improvement)%n", 
            optimizedTotal / (numQueries * 1000.0), (double) originalTotal / optimizedTotal);
        System.out.printf("Octree:           %.2f μs%n%n", octreeTotal / (numQueries * 1000.0));
    }
    
    private void testLargeVolumeQueries(String testName) {
        System.out.println("--- " + testName + " ---");
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int numQueries = 20;
        
        long originalTotal = 0;
        long optimizedTotal = 0;
        long octreeTotal = 0;
        
        for (int i = 0; i < numQueries; i++) {
            float x = random.nextFloat() * 2000;
            float y = random.nextFloat() * 2000;
            float z = random.nextFloat() * 2000;
            float extent = random.nextFloat() * 4000 + 2000;
            var largeQuery = new Spatial.Cube(x, y, z, extent);
            
            // Original Tetree
            long start = System.nanoTime();
            originalTetree.bounding(largeQuery).count();
            originalTotal += System.nanoTime() - start;
            
            // Optimized Tetree
            start = System.nanoTime();
            optimizedTetree.bounding(largeQuery).count();
            optimizedTotal += System.nanoTime() - start;
            
            // Octree
            start = System.nanoTime();
            octree.bounding(largeQuery).count();
            octreeTotal += System.nanoTime() - start;
        }
        
        System.out.printf("Average over %d queries:%n", numQueries);
        System.out.printf("Original Tetree:  %.2f μs%n", originalTotal / (numQueries * 1000.0));
        System.out.printf("Optimized Tetree: %.2f μs (%.2fx improvement)%n", 
            optimizedTotal / (numQueries * 1000.0), (double) originalTotal / optimizedTotal);
        System.out.printf("Octree:           %.2f μs%n%n", octreeTotal / (numQueries * 1000.0));
    }
    
    private void warmUp(Spatial.Cube query) {
        // JIT warm-up
        for (int i = 0; i < 5; i++) {
            originalTetree.boundedBy(query).count();
            optimizedTetree.boundedBy(query).count();
            octree.boundedBy(query).count();
        }
    }
}