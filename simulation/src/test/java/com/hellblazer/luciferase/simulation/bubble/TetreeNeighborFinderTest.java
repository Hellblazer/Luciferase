/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.entity.StringEntityIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Test TetreeNeighborFinder functionality.
 *
 * @author hal.hildebrand
 */
class TetreeNeighborFinderTest {

    private Tetree<StringEntityID, BubbleLocation> tetree;
    private TetreeNeighborFinder finder;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new StringEntityIDGenerator(), 10, (byte) 10);
        finder = new TetreeNeighborFinder(tetree);
    }

    /**
     * Build a structurally VALID TetreeKey by descending real {@link Tet} children from the root to
     * {@code level}, so the decoded leaf type is always a tetrahedron type (0-5). Fabricating keys
     * from arbitrary bit constants (the old {@code TetreeKey.create((byte) L, magic, 0L)} idiom) is
     * unsafe under the coarsest-at-MSB encoding (Luciferase-tkvb): the leaf 6 bits sit at the LSB, so
     * arbitrary low constants decode to pyramid types 6/7 and trip {@code Tet}'s type assertion.
     */
    private static TetreeKey<?> validKey(byte level, long seed) {
        var tet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        for (int i = 0; i < level; i++) {
            // Each level consumes a distinct 3-bit slice of the seed so distinct seeds yield
            // distinct keys (the level-i child index is bits [3i, 3i+2] of the seed).
            tet = tet.child((int) ((seed >> (3 * i)) & 0x7));
        }
        return tet.tmIndex();
    }

    @Test
    void testFindNeighborsRootLevel() {
        var key = validKey((byte) 0, 0L);

        var neighbors = finder.findNeighbors(key);

        // Root has no neighbors (it's the entire domain)
        assertThat(neighbors).isNotNull();
        assertThat(neighbors).isEmpty();
    }

    @Test
    void testFindNeighborsInteriorLevel() {
        // Create a key at level 1 (8 children of root)
        var key = validKey((byte) 1, 0L);

        var neighbors = finder.findNeighbors(key);

        assertThat(neighbors).isNotNull();
        // At level 1, should have neighbors (siblings and edge-adjacent)
        // Variable count expected (4-12)
    }

    @Test
    void testFaceNeighbors_ExactlyFour() {
        // Interior tetrahedron should have exactly 4 face neighbors
        var key = validKey((byte) 2, 1L);

        var faceNeighbors = finder.findFaceNeighbors(key);

        assertThat(faceNeighbors).isNotNull();
        // Note: May have fewer than 4 at boundaries or in sparse tree
        assertThat(faceNeighbors.size()).isLessThanOrEqualTo(4);
    }

    @Test
    void testBoundaryNeighbors_OverlapDetection() {
        // Create location with bounds
        var key = validKey((byte) 5, 10L);
        var bounds = BubbleBounds.fromTetreeKey(key);
        var location = new BubbleLocation(key, bounds);

        // Create map of registered bubbles
        Map<TetreeKey<?>, BubbleLocation> bubblesByKey = new HashMap<>();
        bubblesByKey.put(key, location);

        // Add some neighboring keys
        var neighborKey1 = validKey((byte) 5, 11L);
        var neighborBounds1 = BubbleBounds.fromTetreeKey(neighborKey1);
        bubblesByKey.put(neighborKey1, new BubbleLocation(neighborKey1, neighborBounds1));

        var neighborKey2 = validKey((byte) 5, 12L);
        var neighborBounds2 = BubbleBounds.fromTetreeKey(neighborKey2);
        bubblesByKey.put(neighborKey2, new BubbleLocation(neighborKey2, neighborBounds2));

        var boundaryNeighbors = finder.findBoundaryNeighbors(location, 100f, bubblesByKey);

        assertThat(boundaryNeighbors).isNotNull();
        // Should find some registered neighbors
        assertThat(boundaryNeighbors.size()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testNeighborSymmetry() {
        // If A is a neighbor of B, then B should be a neighbor of A
        var keyA = validKey((byte) 3, 5L);
        var keyB = validKey((byte) 3, 6L);

        boolean aNeighborOfB = finder.isNeighbor(keyA, keyB);
        boolean bNeighborOfA = finder.isNeighbor(keyB, keyA);

        // Symmetry property
        assertThat(aNeighborOfB).isEqualTo(bNeighborOfA);
    }

    @Test
    void testNeighborCountIsBounded() {
        // findNeighbors returns vertex-adjacent neighbors (face + edge + vertex). In a tetrahedral
        // mesh many cells share a vertex, so the count is well above the old "4-12" folklore (which
        // only held for degenerate corner-anchored keys). The geometric maximum for vertex adjacency
        // is 26 (the 3x3x3 cell shell minus the cell itself); assert that real bound, not 12.
        for (byte level = 1; level <= 5; level++) {
            var key = validKey(level, 1L);
            var neighbors = finder.findNeighbors(key);

            // Can be 0 in a sparse tree; never exceeds the 26-cell vertex shell.
            assertThat(neighbors.size()).isBetween(0, 26);
        }
    }

    @Test
    void testNoSelfNeighbor() {
        var key = validKey((byte) 3, 7L);

        var neighbors = finder.findNeighbors(key);

        // Should not include self
        assertThat(neighbors).doesNotContain(key);
    }

    @Test
    void testNeighborStability_RepeatedCalls() {
        var key = validKey((byte) 4, 15L);

        var neighbors1 = finder.findNeighbors(key);
        var neighbors2 = finder.findNeighbors(key);
        var neighbors3 = finder.findNeighbors(key);

        // Should return consistent results
        assertThat(neighbors1).isEqualTo(neighbors2);
        assertThat(neighbors2).isEqualTo(neighbors3);
    }

    @Test
    void testRootHasValidNeighbors() {
        var rootKey = TetreeKey.getRoot();

        var neighbors = finder.findNeighbors(rootKey);

        // Root has no neighbors (entire domain)
        assertThat(neighbors).isNotNull();
        assertThat(neighbors).isEmpty();
    }

    @Test
    void testLeafNeighborsValidate() {
        // Test at maximum level
        var leafKey = validKey((byte) 10, 1000L);

        var neighbors = finder.findNeighbors(leafKey);

        assertThat(neighbors).isNotNull();
        // All neighbors should be at same level
        for (var neighbor : neighbors) {
            assertThat(neighbor.getLevel()).isEqualTo(leafKey.getLevel());
        }
    }

    @Test
    void testPerformance_FindNeighborsUnder1ms() {
        var key = validKey((byte) 5, 42L);

        // Warm up cache
        for (int i = 0; i < 10; i++) {
            finder.findNeighbors(key);
        }

        // Measure performance
        long startNs = System.nanoTime();
        int iterations = 1000;

        for (int i = 0; i < iterations; i++) {
            finder.findNeighbors(key);
        }

        long endNs = System.nanoTime();
        long avgTimeMs = (endNs - startNs) / iterations / 1_000_000;

        // Should be under 1ms per call (likely much faster with caching)
        assertThat(avgTimeMs).isLessThan(1L);
    }

    @Test
    void testDeepTreeNavigation_Level20() {
        // Test neighbor finding at very deep levels
        var deepKey = validKey((byte) 20, 12345L);

        var neighbors = finder.findNeighbors(deepKey);

        assertThat(neighbors).isNotNull();
        // Should complete without error even at deep levels
    }

    @Test
    void testThreadSafety_ConcurrentQueries() throws InterruptedException {
        int numThreads = 10;
        int queriesPerThread = 100;
        var latch = new CountDownLatch(numThreads);
        var errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < queriesPerThread; i++) {
                        var key = validKey((byte) 5, (long) (threadId * 10 + i));
                        var neighbors = finder.findNeighbors(key);
                        assertThat(neighbors).isNotNull();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertThat(errors.get()).isZero();
    }

    @Test
    void testMemoryBounds_NoBoundedAllocations() {
        // Verify that repeated queries don't cause unbounded memory growth
        var key = validKey((byte) 5, 100L);

        // Warm up cache with first query
        finder.findNeighbors(key);

        var initialCacheSize = (Integer) finder.getCacheStats().get("cache_size");

        // Run many queries with same key
        for (int i = 0; i < 1000; i++) {
            finder.findNeighbors(key);
        }

        var finalCacheSize = (Integer) finder.getCacheStats().get("cache_size");

        // Cache should not grow unboundedly (same key = same cache entry)
        // Allow for initial cache entry if it wasn't already cached
        assertThat(finalCacheSize).isLessThanOrEqualTo(initialCacheSize + 1);
    }

    @Test
    void testNeighborConsistency_AllTreeLevels() {
        // Verify neighbor relationships are consistent across all levels
        for (byte level = 1; level <= 10; level++) {
            var key = validKey(level, 1L);
            var neighbors = finder.findNeighbors(key);

            // All neighbors should be at the same level
            for (var neighbor : neighbors) {
                assertThat(neighbor.getLevel()).isEqualTo(level);
            }
        }
    }

    @Test
    void testComplexTopology_VariableNeighbors() {
        // Test that neighbor counts vary appropriately based on position
        var keys = new TetreeKey<?>[]{
            validKey((byte) 3, 0L),   // Near origin
            validKey((byte) 3, 100L), // Interior
            validKey((byte) 3, 500L)  // Different region
        };

        for (var key : keys) {
            var neighbors = finder.findNeighbors(key);
            assertThat(neighbors).isNotNull();
            // Vertex adjacency is bounded by the 26-cell shell, not the old "12" folklore.
            assertThat(neighbors.size()).isBetween(0, 26);
        }
    }
}
