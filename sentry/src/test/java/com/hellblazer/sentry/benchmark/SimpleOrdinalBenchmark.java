package com.hellblazer.sentry.benchmark;

import com.hellblazer.sentry.*;

/**
 * Simple benchmark to measure ordinalOf performance
 */
public class SimpleOrdinalBenchmark {
    
    public static void main(String[] args) {
        System.out.println("Simple ordinalOf() Performance Test");
        System.out.println("==================================\n");
        
        // Create test vertices
        Vertex a = new Vertex(1, 2, 3);
        Vertex b = new Vertex(4, 5, 6);
        Vertex c = new Vertex(7, 8, 9);
        Vertex d = new Vertex(10, 11, 12);
        
        Tetrahedron t = new Tetrahedron(a, b, c, d);
        
        // Warm up
        for (int i = 0; i < 10000; i++) {
            t.ordinalOf(a);
            t.ordinalOf(b);
            t.ordinalOf(c);
            t.ordinalOf(d);
        }
        
        // Test current implementation
        System.out.println("=== Current ordinalOf(Vertex) Performance ===");
        
        // Test each position
        testOrdinal(t, a, "A", 1000000);
        testOrdinal(t, b, "B", 1000000);
        testOrdinal(t, c, "C", 1000000);
        testOrdinal(t, d, "D", 1000000);
        
        // Test worst case (D requires 3 comparisons before match)
        System.out.println("\n=== Access Pattern Analysis ===");
        long totalA = 0, totalB = 0, totalC = 0, totalD = 0;
        int iterations = 1000000;
        
        // Measure each case separately
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            t.ordinalOf(a);
        }
        totalA = System.nanoTime() - start;
        
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            t.ordinalOf(b);
        }
        totalB = System.nanoTime() - start;
        
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            t.ordinalOf(c);
        }
        totalC = System.nanoTime() - start;
        
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            t.ordinalOf(d);
        }
        totalD = System.nanoTime() - start;
        
        System.out.printf("Comparisons needed:\n");
        System.out.printf("  A: 1 comparison  - %.2f ns/op\n", (double)totalA / iterations);
        System.out.printf("  B: 2 comparisons - %.2f ns/op\n", (double)totalB / iterations);
        System.out.printf("  C: 3 comparisons - %.2f ns/op\n", (double)totalC / iterations);
        System.out.printf("  D: 3 comparisons - %.2f ns/op (return without comparison)\n", (double)totalD / iterations);
        
        // Test null case
        Vertex notInTet = new Vertex(20, 21, 22);
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            t.ordinalOf(notInTet);
        }
        long totalNull = System.nanoTime() - start;
        System.out.printf("  Not found: 4 comparisons - %.2f ns/op\n", (double)totalNull / iterations);
    }
    
    private static void testOrdinal(Tetrahedron t, Vertex v, String name, int iterations) {
        long start = System.nanoTime();
        V result = null;
        for (int i = 0; i < iterations; i++) {
            result = t.ordinalOf(v);
        }
        long end = System.nanoTime();
        
        System.out.printf("ordinalOf(%s): %.2f ns/op (result=%s)\n", 
            name, (double)(end - start) / iterations, result);
    }
}