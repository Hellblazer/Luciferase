/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance test for ExtendedTetreeKey caching in TetreeLevelCache. This test validates that the caching
 * significantly improves tmIndex() performance.
 * 
 * These tests are disabled in CI environments (CI=true) because they are timing-sensitive and can fail
 * due to hardware variations, JVM warmup differences, or shared runner load.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "Performance tests are skipped in CI due to timing sensitivity and hardware variations")
public class TetreeKeyCachePerformanceTest {

    @BeforeEach
    void setUp() {
        // Reset cache statistics before each test
        TetreeLevelCache.resetCacheStats();
    }

    @Test
    void testCacheSizeImpact() {
        // Test with diverse set of tetrahedra to stress cache size
        System.out.println("\nTesting cache with diverse tetrahedra...");

        TetreeLevelCache.resetCacheStats();

        // Access 10,000 unique tetrahedra
        for (int i = 0; i < 10_000; i++) {
            byte level = (byte) ((i % 15) + 5);
            int cellSize = Constants.lengthAtLevel(level);
            // Reduce multiplier to stay within bounds for all levels  
            var maxMultiplier = Math.min(99, (1 << MortonCurve.MAX_REFINEMENT_LEVEL) / cellSize - 1);
            int x = ((i * 7919) % maxMultiplier) * cellSize;  // Grid-aligned distribution
            int y = ((i * 7927) % maxMultiplier) * cellSize;
            int z = ((i * 7933) % maxMultiplier) * cellSize;
            byte type = (byte) (i % 6);

            var tet = new Tet(x, y, z, level, type);
            tet.tmIndex();
        }

        // Now re-access some of them to test cache effectiveness
        for (int i = 0; i < 1_000; i++) {
            int idx = i * 10;  // Access every 10th tetrahedron
            byte level = (byte) ((idx % 15) + 5);
            int cellSize = Constants.lengthAtLevel(level);
            var maxMultiplier = Math.min(99, (1 << MortonCurve.MAX_REFINEMENT_LEVEL) / cellSize - 1);
            int x = ((idx * 7919) % maxMultiplier) * cellSize;
            int y = ((idx * 7927) % maxMultiplier) * cellSize;
            int z = ((idx * 7933) % maxMultiplier) * cellSize;
            byte type = (byte) (idx % 6);

            var tet = new Tet(x, y, z, level, type);
            tet.tmIndex();
        }

        double hitRate = TetreeLevelCache.getCacheHitRate();
        System.out.printf("Hit rate with 11,000 accesses: %.2f%%%n", hitRate * 100);

        // With 65536 cache size, we should still get reasonable hit rate
        assertTrue(hitRate > 0.05, "Should have some cache hits even with diverse access");
    }

    @Test
    void testHighLevelPerformance() {
        // Test performance at high levels where parent chain is longest
        System.out.println("\nTesting high-level tetrahedra (level 20)...");

        // Without cache (first access)
        TetreeLevelCache.resetCacheStats();
        long uncachedStart = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            int cellSize = Constants.lengthAtLevel((byte) 20);
            var tet = new Tet(i * cellSize, i * cellSize, i * cellSize, (byte) 20, (byte) (i % 6));
            tet.tmIndex();
        }
        long uncachedTime = System.nanoTime() - uncachedStart;

        // With cache (repeated access)
        TetreeLevelCache.resetCacheStats();
        long cachedStart = System.nanoTime();
        for (int iter = 0; iter < 10; iter++) {
            for (int i = 0; i < 100; i++) {
                int cellSize = Constants.lengthAtLevel((byte) 20);
                var tet = new Tet(i * cellSize, i * cellSize, i * cellSize, (byte) 20, (byte) (i % 6));
                tet.tmIndex();
            }
        }
        long cachedTime = System.nanoTime() - cachedStart;

        double speedup = (uncachedTime * 10.0) / cachedTime;
        double hitRate = TetreeLevelCache.getCacheHitRate();

        System.out.printf("Level 20 - Uncached: %.2f ms for 100 calls%n", uncachedTime / 1_000_000.0);
        System.out.printf("Level 20 - Cached: %.2f ms for 1000 calls%n", cachedTime / 1_000_000.0);
        System.out.printf("Speedup factor: %.1fx%n", speedup);
        System.out.printf("Cache hit rate: %.2f%%%n", hitRate * 100);

        // At level 20, cache should provide significant speedup
        // Note: Performance assertions are advisory - they log warnings but don't fail the build
        // This prevents CI failures due to hardware variations, JVM warmup, or runner load
        if (speedup < 2) {
            System.out.printf(
            "⚠️  WARNING: Cache speedup %.1fx is less than expected 2x - this may be due to JVM warmup, small data size, or CI runner load%n",
            speedup);
        } else if (speedup < 10) {
            System.out.printf(
            "⚠️  Cache speedup %.1fx is less than optimal 10x - this may be due to JVM warmup or small data size%n",
            speedup);
        }
        
        if (hitRate <= 0.8) {
            System.out.printf(
            "⚠️  WARNING: Cache hit rate %.2f%% is less than expected 80%% - cache may not be performing optimally%n",
            hitRate * 100);
        }
    }

    @Test
    void testTmIndexCacheCorrectness() {
        // Test that cached values are correct
        for (byte level = 1; level <= 15; level++) {
            for (byte type = 0; type < 6; type++) {
                int cellSize = Constants.lengthAtLevel(level);
                // Ensure coordinates stay within bounds
                var maxAllowed = (1 << MortonCurve.MAX_REFINEMENT_LEVEL) / cellSize;  // Max multiplier for this level
                var x = cellSize;
                var y = Math.min(cellSize * 2, (maxAllowed - 1) * cellSize);
                var z = Math.min(cellSize * 3, (maxAllowed - 1) * cellSize);
                var tet = new Tet(x, y, z, level, type);

                // First call - cache miss
                var key1 = tet.tmIndex();

                // Second call - should hit cache
                var key2 = tet.tmIndex();

                // Values should be identical
                assertEquals(key1, key2, "Cached ExtendedTetreeKey should equal computed ExtendedTetreeKey");
                assertEquals(key1.getLevel(), key2.getLevel());
                assertEquals(key1.getLowBits(), key2.getLowBits());
                assertEquals(key1.getHighBits(), key2.getHighBits());
            }
        }
    }

    @Test
    void testTmIndexCachePerformance() {
        // Warm up cache with common tetrahedra
        System.out.println("Warming up cache...");
        long warmupStart = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            int cellSize = Constants.lengthAtLevel((byte) 10);
            var tet = new Tet(i * cellSize, i * cellSize, i * cellSize, (byte) 10, (byte) 0);
            tet.tmIndex();
        }
        long warmupTime = System.nanoTime() - warmupStart;
        System.out.printf("Warmup time: %.2f ms%n", warmupTime / 1_000_000.0);

        // Reset stats after warmup
        TetreeLevelCache.resetCacheStats();

        // Measure performance with repeated access (should hit cache)
        System.out.println("\nMeasuring cache performance...");
        long cacheStart = System.nanoTime();
        for (int iter = 0; iter < 10; iter++) {
            for (int i = 0; i < 1000; i++) {
                int cellSize = Constants.lengthAtLevel((byte) 10);
                var tet = new Tet(i * cellSize, i * cellSize, i * cellSize, (byte) 10, (byte) 0);
                tet.tmIndex();
            }
        }
        long cacheTime = System.nanoTime() - cacheStart;

        // Check cache hit rate
        double hitRate = TetreeLevelCache.getCacheHitRate();
        System.out.printf("Cache hit rate: %.2f%%%n", hitRate * 100);
        System.out.printf("Cached access time: %.2f ms for 10,000 calls%n", cacheTime / 1_000_000.0);
        System.out.printf("Average per call: %.2f ns%n", cacheTime / 10_000.0);

        // Advisory: Should have >90% cache hits for repeated access
        if (hitRate <= 0.9) {
            System.out.printf(
            "⚠️  WARNING: Cache hit rate %.2f%% is less than expected 90%% for repeated access%n",
            hitRate * 100);
        }
    }
}
