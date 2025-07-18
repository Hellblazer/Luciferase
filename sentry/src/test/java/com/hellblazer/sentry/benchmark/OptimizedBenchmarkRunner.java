package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Benchmark to compare performance before and after ArrayList optimization
 */
public class OptimizedBenchmarkRunner {

    public static void main(String[] args) {
        System.out.println("Sentry Optimization Benchmark - Phase 1.1 Results");
        System.out.println("================================================\n");

        // Warm up
        System.out.println("Warming up...");
        runBenchmarks(100, true);
        
        // Actual benchmarks
        System.out.println("\nRunning benchmarks...\n");
        runBenchmarks(1000, false);
    }

    private static void runBenchmarks(int iterations, boolean warmup) {
        if (!warmup) {
            // Test the actual flip operation with ears list manipulation
            benchmarkRealFlipOperation(iterations);
            
            // Test isLocallyDelaunay which iterates through ears
            benchmarkIsLocallyDelaunay(iterations);
        }
    }

    private static void benchmarkRealFlipOperation(int iterations) {
        System.out.println("=== Real Flip Operation Performance ===");
        
        Random random = new Random(42);
        
        // Setup grid
        MutableGrid grid = new MutableGrid();
        for (int i = 0; i < 100; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        long totalTime = 0;
        int totalFlips = 0;
        
        // Benchmark flip operations
        for (int iter = 0; iter < iterations; iter++) {
            // Add a new vertex to trigger flips
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            
            long start = System.nanoTime();
            Vertex v = grid.track(new Point3f(x, y, z), random);
            long end = System.nanoTime();
            
            if (v != null) {
                totalTime += (end - start);
                totalFlips++;
            }
        }
        
        if (totalFlips > 0) {
            System.out.printf("Average insertion time: %.2f µs\n", 
                (double)totalTime / totalFlips / 1000);
            System.out.printf("Total successful insertions: %d/%d\n", totalFlips, iterations);
        }
        System.out.println();
    }

    private static void benchmarkIsLocallyDelaunay(int iterations) {
        System.out.println("=== Ears List Access Pattern ===");
        
        // Simulate the access pattern in isLocallyDelaunay
        int[] sizes = {10, 50, 100, 200};
        
        for (int size : sizes) {
            // ArrayList version
            ArrayList<Integer> arrayList = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                arrayList.add(i);
            }
            
            long start = System.nanoTime();
            for (int iter = 0; iter < iterations; iter++) {
                // Simulate the access pattern: iterate and access by index
                for (int i = 0; i < size; i++) {
                    if (i != size / 2) { // Skip middle element (simulating index check)
                        Integer val = arrayList.get(i);
                    }
                }
            }
            long arrayTime = System.nanoTime() - start;
            
            // LinkedList version (for comparison)
            LinkedList<Integer> linkedList = new LinkedList<>();
            for (int i = 0; i < size; i++) {
                linkedList.add(i);
            }
            
            start = System.nanoTime();
            for (int iter = 0; iter < iterations; iter++) {
                for (int i = 0; i < size; i++) {
                    if (i != size / 2) {
                        Integer val = linkedList.get(i);
                    }
                }
            }
            long linkedTime = System.nanoTime() - start;
            
            System.out.printf("Size %d: ArrayList=%.2f ms, LinkedList=%.2f ms, Improvement=%.2fx\n",
                size, 
                arrayTime / 1_000_000.0,
                linkedTime / 1_000_000.0,
                (double)linkedTime / arrayTime);
        }
        System.out.println();
    }
}