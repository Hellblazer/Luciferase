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

import com.hellblazer.luciferase.lucien.Constants;
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
 * Test the performance impact of lazy TetreeKey evaluation.
 */
public class LazyEvaluationPerformanceTest {

    private Tetree<LongEntityID, String> tetree;
    private List<Point3f>                testPositions;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        TetreeLevelCache.resetCacheStats();

        // Generate test data
        testPositions = new ArrayList<>();
        var random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            testPositions.add(
            new Point3f(random.nextFloat() * 10000, random.nextFloat() * 10000, random.nextFloat() * 10000));
        }
    }

    @Test
    void testBatchInsertionWithLazyEvaluation() {
        System.out.println("\n=== BATCH INSERTION WITH LAZY EVALUATION ===\n");

        var contents = new ArrayList<String>();
        for (int i = 0; i < testPositions.size(); i++) {
            contents.add("Entity_" + i);
        }

        // Test 1: Regular batch insertion
        tetree.setLazyEvaluation(false);
        long regularStart = System.nanoTime();
        tetree.insertBatch(testPositions, contents, (byte) 10);
        long regularTime = System.nanoTime() - regularStart;

        // Clear for next test
        tetree = new Tetree<>(new SequentialLongIDGenerator());

        // Test 2: Lazy batch insertion
        tetree.setLazyEvaluation(true);
        long lazyStart = System.nanoTime();
        tetree.insertBatch(testPositions, contents, (byte) 10);
        long lazyTime = System.nanoTime() - lazyStart;

        System.out.printf("Regular batch: %.2f ms%n", regularTime / 1_000_000.0);
        System.out.printf("Lazy batch: %.2f ms%n", lazyTime / 1_000_000.0);
        System.out.printf("Batch speedup: %.2fx%n", (double) regularTime / lazyTime);

        // Without DeferredSortedSet, we can't verify lazy keys remain unresolved
        // The performance benefit still exists as lazy keys defer tmIndex() computation
        // until they're added to the TreeSet
    }

    @Test
    void testInsertionPerformanceComparison() {
        System.out.println("\n=== LAZY EVALUATION PERFORMANCE TEST ===\n");

        // Test 1: Regular insertion (baseline)
        tetree.setLazyEvaluation(false);
        TetreeLevelCache.resetCacheStats();

        long regularStart = System.nanoTime();
        for (var pos : testPositions) {
            tetree.insert(pos, (byte) 10, "Regular");
        }
        long regularTime = System.nanoTime() - regularStart;
        double regularHitRate = TetreeLevelCache.getCacheHitRate();

        // Clear for next test
        tetree = new Tetree<>(new SequentialLongIDGenerator());

        // Test 2: Lazy evaluation insertion
        tetree.setLazyEvaluation(true);
        TetreeLevelCache.resetCacheStats();

        long lazyStart = System.nanoTime();
        for (var pos : testPositions) {
            tetree.insert(pos, (byte) 10, "Lazy");
        }
        long lazyTime = System.nanoTime() - lazyStart;

        // Print results
        System.out.printf("Regular insertion: %.2f ms (%.2f μs/entity)%n", regularTime / 1_000_000.0,
                          regularTime / 1000.0 / testPositions.size());
        System.out.printf("Lazy insertion: %.2f ms (%.2f μs/entity)%n", lazyTime / 1_000_000.0,
                          lazyTime / 1000.0 / testPositions.size());
        System.out.printf("Speedup: %.2fx%n", (double) regularTime / lazyTime);
        System.out.printf("Cache hit rate: %.2f%%%n", regularHitRate * 100);

        // Lazy may be faster or slower depending on data distribution
        // If many entities map to the same nodes, lazy overhead may exceed benefits
        System.out.printf("Performance difference: %.2f%% %s%n",
                          Math.abs((double) (lazyTime - regularTime) / regularTime * 100),
                          lazyTime < regularTime ? "faster" : "slower");

        // Without DeferredSortedSet, lazy keys are resolved immediately when added to TreeSet
        // So we can't test for unresolved keys anymore
    }

    @Test
    void testLazyKeyCreation() {
        // Test that lazy keys work correctly
        var cellSize = Constants.lengthAtLevel((byte) 10);
        var tet = new Tet(cellSize, cellSize, cellSize, (byte) 10, (byte) 0);
        var lazyKey = new LazyTetreeKey(tet);

        // Should not be resolved initially
        assertFalse(lazyKey.isResolved());
        assertEquals((byte) 10, lazyKey.getLevel());

        // Getting low/high bits should trigger resolution
        var lowBits = lazyKey.getLowBits();
        var highBits = lazyKey.getHighBits();
        assertNotEquals(0L, lowBits | highBits); // At least one should be non-zero
        assertTrue(lazyKey.isResolved());

        // Subsequent calls should return same value
        assertEquals(lowBits, lazyKey.getLowBits());
        assertEquals(highBits, lazyKey.getHighBits());
    }

    @Test
    void testLazyKeyEquality() {
        var cellSize2 = Constants.lengthAtLevel((byte) 10);
        var tet1 = new Tet(cellSize2, cellSize2, cellSize2, (byte) 10, (byte) 0);
        var tet2 = new Tet(cellSize2, cellSize2, cellSize2, (byte) 10, (byte) 0);
        var tet3 = new Tet(cellSize2 * 2, cellSize2, cellSize2, (byte) 10, (byte) 0);

        var lazy1 = new LazyTetreeKey(tet1);
        var lazy2 = new LazyTetreeKey(tet2);
        var lazy3 = new LazyTetreeKey(tet3);

        // Equal lazy keys should be equal without resolution
        assertEquals(lazy1, lazy2);
        assertNotEquals(lazy1, lazy3);

        // Should work with regular TetreeKey
        var regular = tet1.tmIndex();
        assertEquals(lazy1, regular);
        assertTrue(lazy1.isResolved()); // Comparison forced resolution
    }

    @Test
    void testQueryPerformance() {
        // Test query performance with lazy evaluation
        tetree.setLazyEvaluation(true);

        // Insert some entities
        for (int i = 0; i < 100; i++) {
            tetree.insert(testPositions.get(i), (byte) 10, "Entity_" + i);
        }

        // Without DeferredSortedSet, all lazy keys are resolved when added to TreeSet
        // But the lazy evaluation still provides performance benefit during initial insertion
        // by deferring the expensive tmIndex() computation

        // Perform a k-NN query
        var queryPoint = new Point3f(5000, 5000, 5000);
        long queryStart = System.nanoTime();
        var neighbors = tetree.kNearestNeighbors(queryPoint, 10, Float.MAX_VALUE);
        long queryTime = System.nanoTime() - queryStart;

        System.out.printf("\nQuery found %d neighbors in %.2f ms%n", neighbors.size(), queryTime / 1_000_000.0);

        // Verify we got some results
        assertTrue(neighbors.size() > 0, "Should find some neighbors");
    }

    private int countLazyKeys() {
        return (int) tetree.getSpatialIndex().keySet().stream().filter(k -> k instanceof LazyTetreeKey).map(
        k -> (LazyTetreeKey) k).filter(k -> !k.isResolved()).count();
    }
}
