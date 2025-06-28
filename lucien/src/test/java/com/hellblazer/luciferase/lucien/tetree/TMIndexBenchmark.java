package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.tetree.TMIndex128Clean.TetId;
import com.hellblazer.luciferase.lucien.tetree.TMIndex128Clean.TMIndex128Bit;

/**
 * Simple benchmark comparison between Tet.tmIndex() and TMIndex128Clean
 */
public class TMIndexBenchmark {
    
    public static void main(String[] args) {
        System.out.println("TM-Index Performance Comparison");
        System.out.println("==============================\n");
        
        // Test at different levels
        for (int level : new int[]{5, 10, 15, 20, 21}) {
            benchmarkLevel(level);
        }
    }
    
    private static void benchmarkLevel(int level) {
        System.out.printf("Level %d:\n", level);
        
        // Generate test coordinates
        int maxCoord = Math.min(1000, (1 << level) - 1);
        int x = 123 % maxCoord;
        int y = 456 % maxCoord;  
        int z = 789 % maxCoord;
        
        // Create test instances
        TetId tetId = TMIndex128Clean.createWithComputedType(x, y, z, level);
        Tet tet = new Tet(x, y, z, (byte)tetId.type, (byte)level);
        
        // Warm up
        for (int i = 0; i < 10000; i++) {
            tet.tmIndex();
            TMIndex128Clean.encode(tetId);
        }
        
        // Benchmark current implementation
        long start = System.nanoTime();
        int iterations = 100000;
        for (int i = 0; i < iterations; i++) {
            var key = tet.tmIndex();
            if (key == null) throw new RuntimeException();
        }
        long currentTime = (System.nanoTime() - start) / iterations;
        
        // Benchmark new implementation  
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            TMIndex128Bit tm = TMIndex128Clean.encode(tetId);
            if (tm == null) throw new RuntimeException();
        }
        long newTime = (System.nanoTime() - start) / iterations;
        
        double speedup = (double) currentTime / newTime;
        
        System.out.printf("  Current (BigInteger): %,d ns/op\n", currentTime);
        System.out.printf("  New (128-bit):        %,d ns/op\n", newTime);
        System.out.printf("  Speedup:              %.1fx\n\n", speedup);
    }
}