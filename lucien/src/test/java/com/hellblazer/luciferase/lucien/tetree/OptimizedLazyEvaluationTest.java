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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the optimized lazy evaluation approach - automatic for bulk operations.
 */
public class OptimizedLazyEvaluationTest {

    private List<Point3f> testPositions;
    private List<String> testContents;

    @BeforeEach
    void setUp() {
        // Generate test data
        testPositions = new ArrayList<>();
        testContents = new ArrayList<>();
        var random = new Random(42);
        
        // Create clustered data for better spatial locality
        for (int cluster = 0; cluster < 10; cluster++) {
            float baseX = cluster * 10000;
            float baseY = cluster * 10000;
            float baseZ = cluster * 10000;
            
            for (int i = 0; i < 100; i++) {
                testPositions.add(new Point3f(
                    baseX + random.nextFloat() * 1000,
                    baseY + random.nextFloat() * 1000,
                    baseZ + random.nextFloat() * 1000
                ));
                testContents.add("Cluster" + cluster + "_Entity" + i);
            }
        }
    }

    @Test
    void testAutoLazyForBulk() {
        System.out.println("\n=== AUTO-LAZY FOR BULK OPERATIONS ===\n");
        
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        // Verify default configuration
        assertFalse(tetree.isLazyEvaluationEnabled());
        assertTrue(tetree.isAutoLazyForBulkEnabled());
        
        // Test bulk insertion with auto-lazy
        TetreeLevelCache.resetCacheStats();
        long startTime = System.nanoTime();
        var ids = tetree.insertBatch(testPositions, testContents, (byte) 10);
        long bulkTime = System.nanoTime() - startTime;
        
        assertEquals(testPositions.size(), ids.size());
        
        // Should still have lazy evaluation disabled for single inserts
        assertFalse(tetree.isLazyEvaluationEnabled());
        
        System.out.printf("Bulk insert with auto-lazy: %.2f ms (%.2f μs/entity)%n",
            bulkTime / 1_000_000.0, bulkTime / 1000.0 / testPositions.size());
        System.out.printf("Cache hit rate: %.2f%%%n", TetreeLevelCache.getCacheHitRate() * 100);
    }

    @Test
    void testPerformanceComparison() {
        System.out.println("\n=== PERFORMANCE COMPARISON ===\n");
        
        // Test 1: Traditional approach (no lazy)
        var traditionalTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        traditionalTetree.setAutoLazyForBulk(false);
        
        TetreeLevelCache.resetCacheStats();
        long traditionalStart = System.nanoTime();
        traditionalTetree.insertBatch(testPositions, testContents, (byte) 10);
        long traditionalTime = System.nanoTime() - traditionalStart;
        double traditionalHitRate = TetreeLevelCache.getCacheHitRate();
        
        // Test 2: Auto-lazy for bulk
        var autoLazyTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        // Default is true, but set explicitly
        autoLazyTetree.setAutoLazyForBulk(true);
        
        TetreeLevelCache.resetCacheStats();
        long autoLazyStart = System.nanoTime();
        autoLazyTetree.insertBatch(testPositions, testContents, (byte) 10);
        long autoLazyTime = System.nanoTime() - autoLazyStart;
        double autoLazyHitRate = TetreeLevelCache.getCacheHitRate();
        
        // Test 3: Always lazy (for comparison)
        var alwaysLazyTetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        alwaysLazyTetree.setLazyEvaluation(true);
        
        TetreeLevelCache.resetCacheStats();
        long alwaysLazyStart = System.nanoTime();
        alwaysLazyTetree.insertBatch(testPositions, testContents, (byte) 10);
        long alwaysLazyTime = System.nanoTime() - alwaysLazyStart;
        
        // Results
        System.out.println("Bulk insertion performance (1000 entities):");
        System.out.printf("1. Traditional (no lazy): %.2f ms (hit rate: %.2f%%)%n", 
            traditionalTime / 1_000_000.0, traditionalHitRate * 100);
        System.out.printf("2. Auto-lazy for bulk: %.2f ms (hit rate: %.2f%%)%n", 
            autoLazyTime / 1_000_000.0, autoLazyHitRate * 100);
        System.out.printf("3. Always lazy: %.2f ms%n", 
            alwaysLazyTime / 1_000_000.0);
        
        System.out.printf("\nSpeedup (auto-lazy vs traditional): %.2fx%n", 
            (double) traditionalTime / autoLazyTime);
        
        // Auto-lazy should be faster than traditional
        assertTrue(autoLazyTime < traditionalTime, 
            "Auto-lazy bulk should be faster than traditional");
    }

    @Test
    void testMixedOperations() {
        System.out.println("\n=== MIXED OPERATIONS TEST ===\n");
        
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        assertTrue(tetree.isAutoLazyForBulkEnabled());
        
        // Single insertions should not use lazy evaluation
        var singlePos = new Point3f(100, 200, 300);
        tetree.insert(singlePos, (byte) 10, "Single");
        
        // Verify no lazy keys in the index
        var lazyCount = countLazyKeys(tetree);
        assertEquals(0, lazyCount, "Single inserts should not create lazy keys");
        
        // Bulk insertion should use lazy evaluation
        var bulkPositions = testPositions.subList(0, 100);
        var bulkContents = testContents.subList(0, 100);
        tetree.insertBatch(bulkPositions, bulkContents, (byte) 10);
        
        // Should have some lazy keys after bulk insert
        lazyCount = countLazyKeys(tetree);
        System.out.printf("Lazy keys after bulk insert: %d%n", lazyCount);
        
        // Query operation should resolve lazy keys as needed
        var queryPoint = new Point3f(5000, 5000, 5000);
        var neighbors = tetree.kNearestNeighbors(queryPoint, 10, Float.MAX_VALUE);
        
        System.out.printf("Found %d neighbors%n", neighbors.size());
        
        // Final state
        lazyCount = countLazyKeys(tetree);
        System.out.printf("Lazy keys after query: %d%n", lazyCount);
    }

    @Test
    void testLargeScaleBulkPerformance() {
        System.out.println("\n=== LARGE SCALE BULK PERFORMANCE ===\n");
        
        // Generate larger dataset
        var largePositions = new ArrayList<Point3f>();
        var largeContents = new ArrayList<String>();
        var random = new Random(42);
        
        for (int i = 0; i < 10000; i++) {
            largePositions.add(new Point3f(
                random.nextFloat() * 100000,
                random.nextFloat() * 100000,
                random.nextFloat() * 100000
            ));
            largeContents.add("Entity_" + i);
        }
        
        // Test with auto-lazy
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        
        TetreeLevelCache.resetCacheStats();
        long startTime = System.nanoTime();
        var ids = tetree.insertBatch(largePositions, largeContents, (byte) 12);
        long insertTime = System.nanoTime() - startTime;
        
        assertEquals(largePositions.size(), ids.size());
        
        System.out.printf("Inserted %d entities in %.2f ms%n", 
            ids.size(), insertTime / 1_000_000.0);
        System.out.printf("Average: %.2f μs/entity%n", 
            insertTime / 1000.0 / ids.size());
        System.out.printf("Cache hit rate: %.2f%%%n", 
            TetreeLevelCache.getCacheHitRate() * 100);
        
        // Measure resolution time
        long resolveStart = System.nanoTime();
        int resolved = tetree.resolveLazyKeys();
        long resolveTime = System.nanoTime() - resolveStart;
        
        System.out.printf("Resolved %d lazy keys in %.2f ms%n", 
            resolved, resolveTime / 1_000_000.0);
    }

    private int countLazyKeys(Tetree<LongEntityID, String> tetree) {
        return (int) tetree.getSpatialIndex().keySet().stream()
            .filter(k -> k instanceof LazyTetreeKey)
            .map(k -> (LazyTetreeKey) k)
            .filter(k -> !k.isResolved())
            .count();
    }
}