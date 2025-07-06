package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Random;

/**
 * Benchmark to test the performance of cache key generation optimizations.
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
public class CacheKeyBenchmark {

    private static final int    ITERATIONS = 10_000_000;
    private static final Random random     = new Random(42);

    // Optimized version with fast path
    private static long generateCacheKeyOptimized(int x, int y, int z, byte level, byte type) {
        // Fast path for small coordinates (common case)
        if ((x | y | z) >= 0 && x < 1024 && y < 1024 && z < 1024) {
            // Pack directly into long for small coordinates
            return ((long) x << 28) | ((long) y << 18) | ((long) z << 8) | ((long) level << 3) | (long) type;
        }

        // Full hash for large coordinates
        var hash = x * 0x9E3779B97F4A7C15L;
        hash ^= y * 0xBF58476D1CE4E5B9L;
        hash ^= z * 0x94D049BB133111EBL;
        hash ^= level * 0x2127599BF4325C37L;
        hash ^= type * 0xFD5167A1D8E52FB7L;

        hash ^= (hash >>> 32);
        hash *= 0xD6E8FEB86659FD93L;
        hash ^= (hash >>> 32);
        hash *= 0xD6E8FEB86659FD93L;
        hash ^= (hash >>> 32);

        return hash;
    }

    // Original version (from TetreeLevelCache)
    private static long generateCacheKeyOriginal(int x, int y, int z, byte level, byte type) {
        var hash = x * 0x9E3779B97F4A7C15L;
        hash ^= y * 0xBF58476D1CE4E5B9L;
        hash ^= z * 0x94D049BB133111EBL;
        hash ^= level * 0x2127599BF4325C37L;
        hash ^= type * 0xFD5167A1D8E52FB7L;

        hash ^= (hash >>> 32);
        hash *= 0xD6E8FEB86659FD93L;
        hash ^= (hash >>> 32);
        hash *= 0xD6E8FEB86659FD93L;
        hash ^= (hash >>> 32);

        return hash;
    }

    @Test
    public void benchmarkCacheKeyGeneration() {
        System.out.println("\n=== Cache Key Generation Benchmark ===\n");

        // Generate test data
        int[] xCoords = new int[1000];
        int[] yCoords = new int[1000];
        int[] zCoords = new int[1000];
        byte[] levels = new byte[1000];
        byte[] types = new byte[1000];

        // Mix of small and large coordinates
        for (int i = 0; i < 1000; i++) {
            if (i < 800) {
                // 80% small coordinates (common case)
                xCoords[i] = random.nextInt(1024);
                yCoords[i] = random.nextInt(1024);
                zCoords[i] = random.nextInt(1024);
            } else {
                // 20% large coordinates
                xCoords[i] = random.nextInt(1 << 20);
                yCoords[i] = random.nextInt(1 << 20);
                zCoords[i] = random.nextInt(1 << 20);
            }
            levels[i] = (byte) random.nextInt(MortonCurve.MAX_REFINEMENT_LEVEL);
            types[i] = (byte) random.nextInt(6);
        }

        // Warm up
        for (int i = 0; i < 100_000; i++) {
            int idx = i % 1000;
            generateCacheKeyOptimized(xCoords[idx], yCoords[idx], zCoords[idx], levels[idx], types[idx]);
        }

        // Benchmark optimized version
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            int idx = i % 1000;
            sum += generateCacheKeyOptimized(xCoords[idx], yCoords[idx], zCoords[idx], levels[idx], types[idx]);
        }
        long optimizedTime = System.nanoTime() - start;

        // Benchmark original version
        start = System.nanoTime();
        long sum2 = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            int idx = i % 1000;
            sum2 += generateCacheKeyOriginal(xCoords[idx], yCoords[idx], zCoords[idx], levels[idx], types[idx]);
        }
        long originalTime = System.nanoTime() - start;

        // Prevent optimization (expected to be different due to different hash algorithms)
        if (sum == sum2) {
            System.out.println("Note: Hash sums happen to be equal");
        }

        System.out.printf("Original version: %.2f ns/op\n", (double) originalTime / ITERATIONS);
        System.out.printf("Optimized version: %.2f ns/op\n", (double) optimizedTime / ITERATIONS);
        System.out.printf("Speedup: %.2fx\n", (double) originalTime / optimizedTime);

        // Test distribution quality
        testDistributionQuality();
    }

    private void testDistributionQuality() {
        System.out.println("\n=== Distribution Quality Test ===\n");

        int buckets = 1024;
        int[] distribution = new int[buckets];
        int samples = 100_000;

        Random rand = new Random(42);
        for (int i = 0; i < samples; i++) {
            int x = rand.nextInt(1 << 16);
            int y = rand.nextInt(1 << 16);
            int z = rand.nextInt(1 << 16);
            byte level = (byte) rand.nextInt(MortonCurve.MAX_REFINEMENT_LEVEL);
            byte type = (byte) rand.nextInt(6);

            long key = generateCacheKeyOptimized(x, y, z, level, type);
            int bucket = (int) (key & (buckets - 1));
            distribution[bucket]++;
        }

        // Calculate statistics
        double expected = (double) samples / buckets;
        double variance = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;

        for (int count : distribution) {
            variance += Math.pow(count - expected, 2);
            min = Math.min(min, count);
            max = Math.max(max, count);
        }
        variance /= buckets;
        double stdDev = Math.sqrt(variance);

        System.out.printf("Expected per bucket: %.1f\n", expected);
        System.out.printf("Min: %d, Max: %d\n", min, max);
        System.out.printf("Standard deviation: %.2f (%.1f%%)\n", stdDev, (stdDev / expected) * 100);
        System.out.printf("Distribution quality: %s\n", (stdDev / expected) < 0.1 ? "EXCELLENT"
                                                                                  : (stdDev / expected) < 0.2 ? "GOOD" :
                                                                                    (stdDev / expected) < 0.3 ? "FAIR"
                                                                                                              : "POOR");
    }
}
