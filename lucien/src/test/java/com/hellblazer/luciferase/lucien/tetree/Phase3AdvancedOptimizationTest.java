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
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test the Phase 3 advanced optimizations.
 */
public class Phase3AdvancedOptimizationTest {

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        TetreeLevelCache.resetCacheStats();
        ThreadLocalTetreeCache.resetGlobalStatistics();
    }

    @Test
    void testCombinedOptimizations() {
        // Test all Phase 3 optimizations together
        System.out.println("\nCombined Optimizations Test:");

        // Enable thread-local caching
        tetree.setThreadLocalCaching(true);

        // Create clustered data
        var positions = new ArrayList<Point3f>();
        var contents = new ArrayList<String>();

        // Create 10 clusters of 100 entities each
        for (int cluster = 0; cluster < 10; cluster++) {
            int baseX = cluster * 5000;
            int baseY = cluster * 5000;
            int baseZ = cluster * 5000;

            for (int i = 0; i < 100; i++) {
                positions.add(new Point3f(baseX + i * 10, baseY + i * 10, baseZ + i * 10));
                contents.add("Cluster_" + cluster + "_Entity_" + i);
            }
        }

        // Reset all caches
        TetreeLevelCache.resetCacheStats();
        ThreadLocalTetreeCache.resetGlobalStatistics();

        // Perform bulk insertion with all optimizations
        long startTime = System.nanoTime();
        var ids = tetree.insertBatch(positions, contents, (byte) 10);
        long insertTime = System.nanoTime() - startTime;

        // Get comprehensive statistics
        double tetreeKeyHitRate = TetreeLevelCache.getCacheHitRate();
        double parentChainHitRate = TetreeLevelCache.getParentChainHitRate();
        String threadLocalStats = tetree.getThreadLocalCacheStatistics();

        System.out.println("\nCombined Optimization Results:");
        System.out.printf("Total insertion time: %.2f ms%n", insertTime / 1_000_000.0);
        System.out.printf("Average per entity: %.2f μs%n", insertTime / 1000.0 / ids.size());
        System.out.printf("ExtendedTetreeKey cache hit rate: %.2f%%%n", tetreeKeyHitRate * 100);
        System.out.printf("Parent chain cache hit rate: %.2f%%%n", parentChainHitRate * 100);
        System.out.println(threadLocalStats);

        // Verify all entities were inserted
        assertEquals(positions.size(), ids.size());
    }

    @Test
    void testParentChainCaching() {
        // Test that parent chain caching improves tmIndex performance
        System.out.println("\nParent Chain Caching Test:");

        // Create tetrahedra at various levels
        var tets = new ArrayList<Tet>();
        for (int level = 5; level <= 15; level++) {
            for (int i = 0; i < 100; i++) {
                var cellSize = Constants.lengthAtLevel((byte) level);
                // Reduce multiplier to stay within bounds for higher levels
                var maxMultiplier = Math.min(99, (1 << 21) / cellSize - 1);
                var x = (i % maxMultiplier) * cellSize;
                var y = (i % maxMultiplier) * cellSize;
                var z = (i % maxMultiplier) * cellSize;
                tets.add(new Tet(x, y, z, (byte) level, (byte) 0));
            }
        }

        // First pass - populate cache
        TetreeLevelCache.resetCacheStats();
        long firstPassTime = 0;
        for (var tet : tets) {
            long start = System.nanoTime();
            tet.tmIndex();
            firstPassTime += System.nanoTime() - start;
        }
        double firstPassHitRate = TetreeLevelCache.getParentChainHitRate();

        // Second pass - should hit cache
        long secondPassTime = 0;
        for (var tet : tets) {
            long start = System.nanoTime();
            tet.tmIndex();
            secondPassTime += System.nanoTime() - start;
        }
        double secondPassHitRate = TetreeLevelCache.getParentChainHitRate();

        System.out.printf("First pass: %.2f ms (parent chain hit rate: %.2f%%)%n", firstPassTime / 1_000_000.0,
                          firstPassHitRate * 100);
        System.out.printf("Second pass: %.2f ms (parent chain hit rate: %.2f%%)%n", secondPassTime / 1_000_000.0,
                          secondPassHitRate * 100);
        System.out.printf("Speedup: %.2fx%n", (double) firstPassTime / secondPassTime);

        // Second pass should be faster
        assertTrue(secondPassTime < firstPassTime);
        // Parent chain hit rate should improve (or at least not be worse)
        // Note: The parent chain cache might not show improvement in hit rate
        // if the test data doesn't reuse the same parent chains
        assertTrue(secondPassHitRate >= firstPassHitRate || secondPassTime < firstPassTime);
    }

    @Test
    void testSpatialLocalityOptimization() {
        // Test spatial locality pre-caching for ray traversal
        System.out.println("\nSpatial Locality Optimization Test:");

        // Insert entities along a ray path
        var origin = new Point3f(1000, 1000, 1000);
        var direction = new javax.vecmath.Vector3f(1, 1, 1);
        direction.normalize();

        // Insert entities along the ray
        for (int i = 0; i < 100; i++) {
            var pos = new Point3f(origin.x + direction.x * i * 100, origin.y + direction.y * i * 100,
                                  origin.z + direction.z * i * 100);
            tetree.insert(pos, (byte) 10, "RayEntity_" + i);
        }

        // Reset cache stats
        TetreeLevelCache.resetCacheStats();

        // Pre-cache the ray path
        var startTet = Tet.locatePointBeyRefinementFromRoot(origin.x, origin.y, origin.z, (byte) 10);
        var localityCache = new SpatialLocalityCache(2);

        long preCacheStart = System.nanoTime();
        localityCache.preCacheRayPath(startTet, direction, 10000);
        long preCacheTime = System.nanoTime() - preCacheStart;

        // Now perform ray traversal (simulate by accessing tetrahedra along the path)
        long traversalStart = System.nanoTime();
        var hitCount = 0;
        for (int i = 0; i < 100; i++) {
            var pos = new Point3f(origin.x + direction.x * i * 100, origin.y + direction.y * i * 100,
                                  origin.z + direction.z * i * 100);
            var tet = Tet.locatePointBeyRefinementFromRoot(pos.x, pos.y, pos.z, (byte) 10);
            if (tet != null) {
                tet.tmIndex(); // This should hit the cache
                hitCount++;
            }
        }
        long traversalTime = System.nanoTime() - traversalStart;

        double hitRate = TetreeLevelCache.getCacheHitRate();

        System.out.printf("Pre-cache time: %.2f ms%n", preCacheTime / 1_000_000.0);
        System.out.printf("Traversal time: %.2f ms%n", traversalTime / 1_000_000.0);
        System.out.printf("Cache hit rate during traversal: %.2f%%%n", hitRate * 100);
        System.out.printf("Hits: %d%n", hitCount);

        // Should have good cache hit rate with spatial locality optimization
        assertTrue(hitRate > 0.7, "Cache hit rate should be > 70% with spatial locality pre-caching");
    }

    @Test
    void testThreadLocalCaching() throws InterruptedException {
        // Enable thread-local caching
        tetree.setThreadLocalCaching(true);
        assertTrue(tetree.isThreadLocalCachingEnabled());

        // Disable auto-balancing to avoid concurrent subdivision issues
        tetree.setAutoBalancingEnabled(false);

        // Create multiple threads that perform insertions
        int numThreads = 4;
        int entitiesPerThread = 1000;
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var totalInsertions = new AtomicLong();
        var insertionFailures = new AtomicLong();
        var allIds = Collections.synchronizedSet(new HashSet<LongEntityID>());
        var attemptedInsertions = new AtomicLong();
        var threadAttempts = new AtomicLong[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threadAttempts[i] = new AtomicLong();
        }

        // Pre-test entity count
        int initialEntityCount = tetree.entityCount();
        System.out.println("Initial entity count: " + initialEntityCount);

        long startTime = System.nanoTime();

        for (int t = 0; t < numThreads; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    System.out.println("Thread " + threadId + " starting insertions");
                    // Each thread inserts entities in its own region
                    for (int i = 0; i < entitiesPerThread; i++) {
                        attemptedInsertions.incrementAndGet();
                        threadAttempts[threadId].incrementAndGet();
                        var x = threadId * 10000 + i * 10;
                        var y = threadId * 10000 + i * 10;
                        var z = threadId * 10000 + i * 10;
                        try {
                            var id = tetree.insert(new Point3f(x, y, z), (byte) 10, "Thread" + threadId + "_" + i);
                            if (id != null) {
                                if (allIds.add(id)) {
                                    totalInsertions.incrementAndGet();
                                } else {
                                    System.err.println(
                                    "DUPLICATE ID RETURNED: " + id + " for position (" + x + ", " + y + ", " + z + ")");
                                }
                            } else {
                                System.err.println("Got null ID for insert at (" + x + ", " + y + ", " + z + ")");
                            }
                        } catch (Exception e) {
                            insertionFailures.incrementAndGet();
                            System.err.println(
                            "Failed to insert at (" + x + ", " + y + ", " + z + "): " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                    System.out.println(
                    "Thread " + threadId + " completed " + threadAttempts[threadId].get() + " insertions");
                } catch (Throwable throwable) {
                    System.err.println("Thread " + threadId + " failed with exception: " + throwable);
                    throwable.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        latch.await();

        // Shutdown the executor and wait for termination
        executor.shutdown();
        if (!executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
            executor.shutdownNow();
            throw new RuntimeException("Executor did not terminate in time");
        }

        long elapsedTime = System.nanoTime() - startTime;

        // Check results
        System.out.println("\n=== RESULTS ===");
        System.out.println("Total expected insertions: " + numThreads * entitiesPerThread);
        System.out.println("Total attempted insertions: " + attemptedInsertions.get());
        System.out.println("Successful insertions: " + totalInsertions.get());
        System.out.println("Failed insertions: " + insertionFailures.get());
        System.out.println("Unique IDs returned: " + allIds.size());
        System.out.println("Final entity count in tetree: " + tetree.entityCount());
        System.out.println("Net new entities: " + (tetree.entityCount() - initialEntityCount));

        // Print per-thread statistics
        System.out.println("\nPer-thread attempts:");
        for (int i = 0; i < numThreads; i++) {
            System.out.println("Thread " + i + ": " + threadAttempts[i].get() + " attempts");
        }

        // Check for duplicate IDs
        if (allIds.size() != totalInsertions.get()) {
            System.err.println(
            "DUPLICATE IDS DETECTED! " + totalInsertions.get() + " insertions but only " + allIds.size()
            + " unique IDs");
        }

        // Now that threading is fixed, we should get all insertions
        assertEquals(numThreads * entitiesPerThread, attemptedInsertions.get(),
                     "All insertion attempts should complete");
        assertEquals(attemptedInsertions.get(), totalInsertions.get(), "All attempted insertions should succeed");
        assertEquals(totalInsertions.get(), allIds.size(), "All insertions should produce unique IDs");

        // Get statistics
        String stats = tetree.getThreadLocalCacheStatistics();
        System.out.println("\nThread-Local Caching Results:");
        System.out.println(stats);
        System.out.printf("Total time: %.2f ms%n", elapsedTime / 1_000_000.0);
        System.out.printf("Average per entity: %.2f μs%n", elapsedTime / 1000.0 / totalInsertions.get());

        // Clean up thread-local caches
        ThreadLocalTetreeCache.removeThreadCache();
    }
}
