package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Benchmark to measure the performance improvement from caching getAdjacentVertex()
 */
public class CachedAdjacentVertexBenchmark {

    public static void main(String[] args) {
        System.out.println("Sentry Optimization Benchmark - Phase 1.2: Cached getAdjacentVertex()");
        System.out.println("====================================================================\n");

        // Warm up
        System.out.println("Warming up...");
        runBenchmarks(1000, true);
        
        // Actual benchmarks
        System.out.println("\nRunning benchmarks...\n");
        runBenchmarks(10000, false);
    }

    private static void runBenchmarks(int iterations, boolean warmup) {
        if (!warmup) {
            benchmarkGetAdjacentVertex(iterations);
            benchmarkFlipWithCaching(iterations);
            benchmarkCacheEffectiveness(iterations);
        }
    }

    private static void benchmarkGetAdjacentVertex(int iterations) {
        System.out.println("=== Direct getAdjacentVertex() Performance ===");
        
        Random random = new Random(42);
        MutableGrid grid = new MutableGrid();
        
        // Create a small grid
        for (int i = 0; i < 50; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        // Collect some oriented faces
        List<OrientedFace> faces = new ArrayList<>();
        for (Tetrahedron tet : grid.tetrahedrons()) {
            for (V vertex : V.values()) {
                faces.add(tet.getFace(vertex));
                if (faces.size() >= 100) break;
            }
            if (faces.size() >= 100) break;
        }
        
        if (faces.isEmpty()) {
            System.out.println("No faces generated\n");
            return;
        }
        
        // Benchmark repeated calls to getAdjacentVertex
        long start = System.nanoTime();
        int nonNullCount = 0;
        
        for (int i = 0; i < iterations; i++) {
            for (OrientedFace face : faces) {
                Vertex v = face.getAdjacentVertex();
                if (v != null) nonNullCount++;
            }
        }
        
        long elapsed = System.nanoTime() - start;
        
        System.out.printf("Total time: %.2f ms\n", elapsed / 1_000_000.0);
        System.out.printf("Average per call: %.2f ns\n", (double)elapsed / (iterations * faces.size()));
        System.out.printf("Non-null results: %d/%d\n\n", nonNullCount, iterations * faces.size());
    }

    private static void benchmarkFlipWithCaching(int iterations) {
        System.out.println("=== Flip Operations with Cached Adjacent Vertex ===");
        
        Random random = new Random(42);
        
        long totalTime = 0;
        int totalFlips = 0;
        
        for (int iter = 0; iter < iterations / 100; iter++) {
            // Create fresh grid for each iteration
            MutableGrid grid = new MutableGrid();
            
            // Add initial vertices
            for (int i = 0; i < 50; i++) {
                float x = random.nextFloat() * 100;
                float y = random.nextFloat() * 100;
                float z = random.nextFloat() * 100;
                grid.track(new Point3f(x, y, z), random);
            }
            
            // Add a vertex to trigger flips
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
            System.out.printf("Total successful insertions: %d\n\n", totalFlips);
        }
    }

    private static void benchmarkCacheEffectiveness(int iterations) {
        System.out.println("=== Cache Effectiveness Test ===");
        
        Random random = new Random(42);
        MutableGrid grid = new MutableGrid();
        
        // Create grid
        for (int i = 0; i < 30; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        // Get a face
        OrientedFace testFace = null;
        for (Tetrahedron tet : grid.tetrahedrons()) {
            testFace = tet.getFace(V.A);
            break;
        }
        
        if (testFace == null) {
            System.out.println("No test face available\n");
            return;
        }
        
        // Test repeated calls (should use cache)
        long start = System.nanoTime();
        Vertex v1 = null;
        for (int i = 0; i < iterations; i++) {
            v1 = testFace.getAdjacentVertex();
        }
        long cachedTime = System.nanoTime() - start;
        
        // To simulate uncached, we'd need to invalidate between calls
        // but since we can't easily do that, we'll just report the cached performance
        
        System.out.printf("Cached getAdjacentVertex time for %d calls: %.2f ms (%.2f ns/call)\n", 
            iterations, cachedTime / 1_000_000.0, (double)cachedTime / iterations);
        System.out.printf("Retrieved vertex: %s\n\n", v1);
    }
}