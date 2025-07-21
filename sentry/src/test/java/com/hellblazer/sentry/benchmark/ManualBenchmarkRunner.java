package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;
import javax.vecmath.Point3f;
import java.util.*;

/**
 * Manual benchmark runner without JMH to establish baseline metrics
 */
public class ManualBenchmarkRunner {

    public static void main(String[] args) {
        System.out.println("Running Sentry Manual Benchmarks");
        System.out.println("================================\n");

        // Warm up
        System.out.println("Warming up...");
        runBenchmarks(true);
        
        // Actual benchmarks
        System.out.println("\nRunning benchmarks...\n");
        runBenchmarks(false);
    }

    private static void runBenchmarks(boolean warmup) {
        int iterations = warmup ? 1000 : 10000;
        
        // Benchmark 1: LinkedList vs ArrayList access
        benchmarkListAccess(iterations, warmup);
        
        // Benchmark 2: Flip operation with LinkedList
        benchmarkFlipOperation(iterations, warmup);
        
        // Benchmark 3: getAdjacentVertex calls
        benchmarkGetAdjacentVertex(iterations, warmup);
    }

    private static void benchmarkListAccess(int iterations, boolean warmup) {
        if (warmup) return;
        
        System.out.println("=== LinkedList vs ArrayList Random Access ===");
        
        // Setup
        int listSize = 100;
        List<Integer> linkedList = new LinkedList<>();
        List<Integer> arrayList = new ArrayList<>();
        
        for (int i = 0; i < listSize; i++) {
            linkedList.add(i);
            arrayList.add(i);
        }
        
        // LinkedList benchmark
        long start = System.nanoTime();
        for (int iter = 0; iter < iterations; iter++) {
            int sum = 0;
            for (int i = 0; i < listSize; i++) {
                sum += linkedList.get(i);
            }
        }
        long linkedListTime = System.nanoTime() - start;
        
        // ArrayList benchmark
        start = System.nanoTime();
        for (int iter = 0; iter < iterations; iter++) {
            int sum = 0;
            for (int i = 0; i < listSize; i++) {
                sum += arrayList.get(i);
            }
        }
        long arrayListTime = System.nanoTime() - start;
        
        System.out.printf("LinkedList access time: %.2f ms (%.2f ns/op)\n", 
            linkedListTime / 1_000_000.0, (double)linkedListTime / (iterations * listSize));
        System.out.printf("ArrayList access time: %.2f ms (%.2f ns/op)\n", 
            arrayListTime / 1_000_000.0, (double)arrayListTime / (iterations * listSize));
        System.out.printf("ArrayList is %.2fx faster\n\n", 
            (double)linkedListTime / arrayListTime);
    }

    private static void benchmarkFlipOperation(int iterations, boolean warmup) {
        if (warmup) return;
        
        System.out.println("=== Flip Operation Performance ===");
        
        // Setup grid
        Random random = new Random(42);
        MutableGrid grid = new MutableGrid();
        
        // Add initial vertices
        for (int i = 0; i < 50; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        // Prepare ears list
        List<Tetrahedron> tets = new ArrayList<>(grid.tetrahedrons());
        if (tets.isEmpty()) {
            System.out.println("No tetrahedra generated, skipping flip benchmark\n");
            return;
        }
        
        List<OrientedFace> ears = new LinkedList<>();
        for (Tetrahedron tet : tets) {
            if (ears.size() >= 50) break;
            for (V vertex : V.values()) {
                if (ears.size() >= 50) break;
                ears.add(tet.getFace(vertex));
            }
        }
        
        // Create test vertex
        Vertex testVertex = new Vertex(new Point3f(50, 50, 50));
        
        // Benchmark
        long start = System.nanoTime();
        int validFlips = 0;
        
        for (int i = 0; i < iterations && !ears.isEmpty(); i++) {
            OrientedFace face = ears.get(0);
            // Note: We're not actually performing the flip to avoid modifying the grid
            // Just measuring the overhead of list access and method calls
            if (face != null && face.getAdjacentVertex() != null) {
                validFlips++;
            }
        }
        
        long flipTime = System.nanoTime() - start;
        
        System.out.printf("Flip operation time: %.2f ms (%.2f µs/op)\n", 
            flipTime / 1_000_000.0, (double)flipTime / iterations / 1000);
        System.out.printf("Valid flips: %d/%d\n\n", validFlips, iterations);
    }

    private static void benchmarkGetAdjacentVertex(int iterations, boolean warmup) {
        if (warmup) return;
        
        System.out.println("=== getAdjacentVertex Performance ===");
        
        // Setup
        Random random = new Random(42);
        MutableGrid grid = new MutableGrid();
        
        for (int i = 0; i < 20; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        List<OrientedFace> faces = new ArrayList<>();
        for (Tetrahedron tet : grid.tetrahedrons()) {
            for (V vertex : V.values()) {
                faces.add(tet.getFace(vertex));
                if (faces.size() >= 100) break;
            }
            if (faces.size() >= 100) break;
        }
        
        if (faces.isEmpty()) {
            System.out.println("No faces generated, skipping getAdjacentVertex benchmark\n");
            return;
        }
        
        // Benchmark
        long start = System.nanoTime();
        int nonNullCount = 0;
        
        for (int i = 0; i < iterations; i++) {
            for (OrientedFace face : faces) {
                Vertex v = face.getAdjacentVertex();
                if (v != null) nonNullCount++;
            }
        }
        
        long adjacentTime = System.nanoTime() - start;
        
        System.out.printf("getAdjacentVertex time: %.2f ms (%.2f ns/call)\n", 
            adjacentTime / 1_000_000.0, 
            (double)adjacentTime / (iterations * faces.size()));
        System.out.printf("Non-null results: %d/%d\n\n", 
            nonNullCount, iterations * faces.size());
    }
}