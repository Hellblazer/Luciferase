package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Benchmark to test tmIndex optimization strategies for parent walk ordering.
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
public class TmIndexOptimizationBenchmark {
    
    private static final int ITERATIONS = 100_000;
    private static final Random random = new Random(42);
    private List<Tet> testTets;
    
    @BeforeEach
    public void setUp() {
        // Clear caches to ensure fair comparison
        TetreeLevelCache.clearCaches();
        TetreeLevelCache.resetCacheStats();
        
        // Generate test tetrahedra at various levels
        testTets = new ArrayList<>();
        for (int level = 1; level <= 15; level++) {
            for (int i = 0; i < 100; i++) {
                int maxCoord = 1 << level;
                int x = random.nextInt(maxCoord);
                int y = random.nextInt(maxCoord);
                int z = random.nextInt(maxCoord);
                byte type = (byte) random.nextInt(6);
                testTets.add(new Tet(x, y, z, (byte) level, type));
            }
        }
        
        System.out.println("Generated " + testTets.size() + " test tetrahedra");
    }
    
    @Test
    public void benchmarkTmIndexStrategies() {
        System.out.println("\n=== tmIndex Optimization Benchmark ===\n");
        
        // Warm up all methods to ensure fair comparison
        warmUp();
        
        // Benchmark original implementation
        long originalTime = benchmarkOriginal();
        
        // Clear cache and benchmark first optimization
        TetreeLevelCache.clearCaches();
        TetreeLevelCache.resetCacheStats();
        long optimizedV1Time = benchmarkOptimizedV1();
        
        // Clear cache and benchmark second optimization
        TetreeLevelCache.clearCaches();
        TetreeLevelCache.resetCacheStats();
        long optimizedV2Time = benchmarkOptimizedV2();
        
        // Clear cache and benchmark third optimization (production-ready)
        TetreeLevelCache.clearCaches();
        TetreeLevelCache.resetCacheStats();
        long optimizedV3Time = benchmarkOptimizedV3();
        
        // Report results
        System.out.printf("Original tmIndex: %.2f μs/op\n", (double) originalTime / (ITERATIONS * 1000));
        System.out.printf("Optimized V1: %.2f μs/op (%.2fx speedup)\n", 
            (double) optimizedV1Time / (ITERATIONS * 1000),
            (double) originalTime / optimizedV1Time);
        System.out.printf("Optimized V2: %.2f μs/op (%.2fx speedup)\n", 
            (double) optimizedV2Time / (ITERATIONS * 1000),
            (double) originalTime / optimizedV2Time);
        System.out.printf("Optimized V3: %.2f μs/op (%.2fx speedup)\n", 
            (double) optimizedV3Time / (ITERATIONS * 1000),
            (double) originalTime / optimizedV3Time);
        
        // Test cache efficiency
        testCacheEfficiency();
    }
    
    private void warmUp() {
        for (int i = 0; i < 10_000; i++) {
            Tet tet = testTets.get(i % testTets.size());
            tet.tmIndex();
            TetOptimized.tmIndexOptimized(tet);
            TetOptimized.tmIndexOptimizedV2(tet);
            TetOptimized.tmIndexOptimizedV3(tet);
        }
    }
    
    private long benchmarkOriginal() {
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            Tet tet = testTets.get(i % testTets.size());
            tet.tmIndex();
        }
        return System.nanoTime() - start;
    }
    
    private long benchmarkOptimizedV1() {
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            Tet tet = testTets.get(i % testTets.size());
            TetOptimized.tmIndexOptimized(tet);
        }
        return System.nanoTime() - start;
    }
    
    private long benchmarkOptimizedV2() {
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            Tet tet = testTets.get(i % testTets.size());
            TetOptimized.tmIndexOptimizedV2(tet);
        }
        return System.nanoTime() - start;
    }
    
    private long benchmarkOptimizedV3() {
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            Tet tet = testTets.get(i % testTets.size());
            TetOptimized.tmIndexOptimizedV3(tet);
        }
        return System.nanoTime() - start;
    }
    
    private void testCacheEfficiency() {
        System.out.println("\n=== Cache Efficiency Analysis ===\n");
        
        // Test with cold cache
        TetreeLevelCache.clearCaches();
        TetreeLevelCache.resetCacheStats();
        
        // Run operations with repeated access patterns
        for (int round = 0; round < 3; round++) {
            System.out.printf("Round %d:\n", round + 1);
            
            long start = System.nanoTime();
            for (int i = 0; i < 10_000; i++) {
                Tet tet = testTets.get(i % 100); // Reuse first 100 tets for cache locality
                tet.tmIndex();
            }
            long time = System.nanoTime() - start;
            
            System.out.printf("  Time: %.2f μs/op\n", (double) time / (10_000 * 1000));
            System.out.printf("  Cache hit rate: %.1f%%\n", TetreeLevelCache.getCacheHitRate() * 100);
            System.out.printf("  Parent cache hit rate: %.1f%%\n", TetreeLevelCache.getParentCacheHitRate() * 100);
        }
        
        // Test spatial locality effects
        testSpatialLocality();
    }
    
    private void testSpatialLocality() {
        System.out.println("\n=== Spatial Locality Test ===\n");
        
        // Generate spatially clustered tetrahedra
        List<Tet> clusteredTets = new ArrayList<>();
        for (int cluster = 0; cluster < 10; cluster++) {
            int baseX = cluster * 100;
            int baseY = cluster * 100;
            int baseZ = cluster * 100;
            
            for (int i = 0; i < 100; i++) {
                int x = baseX + random.nextInt(50);
                int y = baseY + random.nextInt(50);
                int z = baseZ + random.nextInt(50);
                byte level = (byte) (10 + random.nextInt(5));
                byte type = (byte) random.nextInt(6);
                clusteredTets.add(new Tet(x, y, z, level, type));
            }
        }
        
        // Test cache performance with spatial clustering
        TetreeLevelCache.clearCaches();
        TetreeLevelCache.resetCacheStats();
        
        long start = System.nanoTime();
        for (Tet tet : clusteredTets) {
            tet.tmIndex();
        }
        long clusteredTime = System.nanoTime() - start;
        
        // Test with random access pattern
        TetreeLevelCache.clearCaches();
        TetreeLevelCache.resetCacheStats();
        
        start = System.nanoTime();
        for (int i = 0; i < clusteredTets.size(); i++) {
            Tet tet = testTets.get(random.nextInt(testTets.size()));
            tet.tmIndex();
        }
        long randomTime = System.nanoTime() - start;
        
        System.out.printf("Clustered access: %.2f μs/op (%.1f%% hit rate)\n", 
            (double) clusteredTime / (clusteredTets.size() * 1000),
            TetreeLevelCache.getCacheHitRate() * 100);
        
        TetreeLevelCache.resetCacheStats();
        System.out.printf("Random access: %.2f μs/op (%.1f%% hit rate)\n", 
            (double) randomTime / (clusteredTets.size() * 1000),
            TetreeLevelCache.getCacheHitRate() * 100);
        
        System.out.printf("Spatial locality benefit: %.2fx\n", (double) randomTime / clusteredTime);
    }
}