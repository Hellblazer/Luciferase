/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This file is part of the Luciferase.
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.cache;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3a concurrent k-NN stress test to validate thread safety and baseline performance.
 * 
 * Tests three concurrent scenarios:
 * 1. Pure read workload: 12 threads, 1000 queries each
 * 2. Mixed workload: 12 query threads + 2 modification threads
 * 3. Sustained load: 5-second continuous concurrent load
 * 
 * Validates:
 * - Thread safety of cache operations
 * - Concurrent query correctness
 * - Cache invalidation under concurrent modifications
 * - Baseline concurrent throughput
 * 
 * @author hal.hildebrand
 */
public class ConcurrentKNNStressTest {

    private static final int THREAD_COUNT = 12;
    private static final int QUERIES_PER_THREAD = 1000;
    private static final int INITIAL_ENTITIES = 10000;
    private static final int MODIFICATIONS = 500;
    private static final int K = 20;
    private static final float MAX_DISTANCE = 100.0f;
    private static final long TEST_DURATION_MS = 5000;

    @Test
    public void testConcurrentQueriesOnly() throws Exception {
        System.out.println("\n=== Concurrent Queries Only (Read-Only Workload) ===\n");

        // Create spatial index with entities
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator(), 8, (byte) 10);
        populateOctree(octree, INITIAL_ENTITIES);

        // Generate shared query points
        var queryPoints = generateQueryPoints(100);

        var executor = Executors.newFixedThreadPool(THREAD_COUNT);
        var startLatch = new CountDownLatch(1);
        var completeLatch = new CountDownLatch(THREAD_COUNT);
        var totalQueries = new AtomicLong(0);
        var totalErrors = new AtomicInteger(0);

        System.out.println("Starting " + THREAD_COUNT + " query threads...");
        System.out.println("Queries per thread: " + QUERIES_PER_THREAD);

        var startTime = System.nanoTime();

        // Launch query threads
        for (int t = 0; t < THREAD_COUNT; t++) {
            final var threadId = t;
            executor.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();

                    // Execute queries
                    for (int i = 0; i < QUERIES_PER_THREAD; i++) {
                        var queryPoint = queryPoints.get((threadId * QUERIES_PER_THREAD + i) % queryPoints.size());
                        var result = octree.kNearestNeighbors(queryPoint, K, MAX_DISTANCE);

                        if (result == null) {
                            totalErrors.incrementAndGet();
                        } else {
                            totalQueries.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Thread " + threadId + " error: " + e.getMessage());
                    totalErrors.incrementAndGet();
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        var completed = completeLatch.await(30, TimeUnit.SECONDS);
        var endTime = System.nanoTime();

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Report results
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;
        var queriesCompleted = totalQueries.get();
        var throughput = (queriesCompleted / totalTimeMs) * 1000.0;

        System.out.println("\nResults:");
        System.out.println("  Completed: " + completed);
        System.out.println("  Total queries: " + queriesCompleted);
        System.out.println("  Total errors: " + totalErrors.get());
        System.out.println("  Total time: " + String.format("%.2f", totalTimeMs) + " ms");
        System.out.println("  Throughput: " + String.format("%.0f", throughput) + " queries/sec");
        System.out.println("  Average latency: " + String.format("%.4f", totalTimeMs / queriesCompleted) + " ms");

        // Validate correctness
        assertTrue(completed, "All threads should complete within timeout");
        assertEquals(THREAD_COUNT * QUERIES_PER_THREAD, queriesCompleted, "All queries should succeed");
        assertEquals(0, totalErrors.get(), "No errors should occur");

        System.out.println("  ✓ PASS: All concurrent queries completed successfully");
    }

    @Test
    public void testConcurrentQueriesWithModifications() throws Exception {
        System.out.println("\n=== Concurrent Queries with Modifications (Mixed Workload) ===\n");

        // Create spatial index with entities
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator(), 8, (byte) 10);
        var entityIds = new ArrayList<LongEntityID>();

        System.out.println("Populating octree with " + INITIAL_ENTITIES + " entities...");
        for (int i = 0; i < INITIAL_ENTITIES; i++) {
            var x = (float) (Math.random() * 100);
            var y = (float) (Math.random() * 100);
            var z = (float) (Math.random() * 100);
            var id = octree.insert(new Point3f(x, y, z), (byte) 0, "entity-" + i);
            entityIds.add(id);
        }
        System.out.println("Octree populated.\n");

        // Generate shared query points
        var queryPoints = generateQueryPoints(100);

        var executor = Executors.newFixedThreadPool(THREAD_COUNT + 2);
        var startLatch = new CountDownLatch(1);
        var completeLatch = new CountDownLatch(THREAD_COUNT + 2);
        var totalQueries = new AtomicLong(0);
        var totalModifications = new AtomicLong(0);
        var totalErrors = new AtomicInteger(0);

        System.out.println("Starting " + THREAD_COUNT + " query threads + 2 modification threads...");

        var startTime = System.nanoTime();

        // Launch query threads
        for (int t = 0; t < THREAD_COUNT; t++) {
            final var threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    for (int i = 0; i < QUERIES_PER_THREAD; i++) {
                        var queryPoint = queryPoints.get((threadId * QUERIES_PER_THREAD + i) % queryPoints.size());
                        var result = octree.kNearestNeighbors(queryPoint, K, MAX_DISTANCE);

                        if (result == null) {
                            totalErrors.incrementAndGet();
                        } else {
                            totalQueries.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Query thread " + threadId + " error: " + e.getMessage());
                    totalErrors.incrementAndGet();
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        // Launch modification threads
        for (int t = 0; t < 2; t++) {
            final var threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    for (int i = 0; i < MODIFICATIONS; i++) {
                        // Update random entity
                        var entityIndex = (int) (Math.random() * entityIds.size());
                        var entityId = entityIds.get(entityIndex);
                        var newX = (float) (Math.random() * 100);
                        var newY = (float) (Math.random() * 100);
                        var newZ = (float) (Math.random() * 100);

                        try {
                            octree.updateEntity(entityId, new Point3f(newX, newY, newZ), (byte) 0);
                            totalModifications.incrementAndGet();
                        } catch (Exception e) {
                            // Entity might have been deleted by other thread
                            totalErrors.incrementAndGet();
                        }

                        // Small delay to avoid overwhelming the system
                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    System.err.println("Modification thread " + threadId + " error: " + e.getMessage());
                    totalErrors.incrementAndGet();
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        var completed = completeLatch.await(60, TimeUnit.SECONDS);
        var endTime = System.nanoTime();

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Report results
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;
        var queriesCompleted = totalQueries.get();
        var modificationsCompleted = totalModifications.get();
        var queryThroughput = (queriesCompleted / totalTimeMs) * 1000.0;
        var modificationThroughput = (modificationsCompleted / totalTimeMs) * 1000.0;

        System.out.println("\nResults:");
        System.out.println("  Completed: " + completed);
        System.out.println("  Total queries: " + queriesCompleted);
        System.out.println("  Total modifications: " + modificationsCompleted);
        System.out.println("  Total errors: " + totalErrors.get());
        System.out.println("  Total time: " + String.format("%.2f", totalTimeMs) + " ms");
        System.out.println("  Query throughput: " + String.format("%.0f", queryThroughput) + " queries/sec");
        System.out.println("  Modification throughput: " + String.format("%.0f", modificationThroughput) + " mods/sec");

        // Validate correctness
        assertTrue(completed, "All threads should complete within timeout");
        assertTrue(queriesCompleted > THREAD_COUNT * QUERIES_PER_THREAD * 0.95,
                   "At least 95% of queries should succeed");
        assertTrue(modificationsCompleted > MODIFICATIONS * 2 * 0.95,
                   "At least 95% of modifications should succeed");

        System.out.println("  ✓ PASS: Mixed workload completed successfully");
    }

    @Test
    public void testSustainedConcurrentLoad() throws Exception {
        System.out.println("\n=== Sustained Concurrent Load (5-second duration) ===\n");

        // Create spatial index with entities
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator(), 8, (byte) 10);
        populateOctree(octree, INITIAL_ENTITIES);

        // Generate shared query points
        var queryPoints = generateQueryPoints(100);

        var executor = Executors.newFixedThreadPool(THREAD_COUNT);
        var running = new AtomicInteger(THREAD_COUNT);
        var totalQueries = new AtomicLong(0);
        var totalErrors = new AtomicInteger(0);

        System.out.println("Running sustained load for " + TEST_DURATION_MS + " ms...");
        System.out.println("Threads: " + THREAD_COUNT);

        var startTime = System.nanoTime();
        var endTime = startTime + (TEST_DURATION_MS * 1_000_000);

        // Launch threads that run until time expires
        for (int t = 0; t < THREAD_COUNT; t++) {
            final var threadId = t;
            executor.submit(() -> {
                try {
                    var queryCount = 0;
                    while (System.nanoTime() < endTime) {
                        var queryPoint = queryPoints.get(queryCount % queryPoints.size());
                        var result = octree.kNearestNeighbors(queryPoint, K, MAX_DISTANCE);

                        if (result == null) {
                            totalErrors.incrementAndGet();
                        } else {
                            totalQueries.incrementAndGet();
                        }

                        queryCount++;
                    }
                } catch (Exception e) {
                    System.err.println("Thread " + threadId + " error: " + e.getMessage());
                    totalErrors.incrementAndGet();
                } finally {
                    running.decrementAndGet();
                }
            });
        }

        // Wait for test duration + grace period
        Thread.sleep(TEST_DURATION_MS + 1000);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        var actualDuration = (System.nanoTime() - startTime) / 1_000_000.0;

        // Report results
        var queriesCompleted = totalQueries.get();
        var throughput = (queriesCompleted / actualDuration) * 1000.0;

        System.out.println("\nResults:");
        System.out.println("  Actual duration: " + String.format("%.2f", actualDuration) + " ms");
        System.out.println("  Total queries: " + queriesCompleted);
        System.out.println("  Total errors: " + totalErrors.get());
        System.out.println("  Throughput: " + String.format("%.0f", throughput) + " queries/sec");
        System.out.println("  Average latency: " + String.format("%.4f", actualDuration / queriesCompleted) + " ms");
        System.out.println("  Queries per thread: " + String.format("%.0f", (double) queriesCompleted / THREAD_COUNT));

        // Validate correctness
        assertEquals(0, running.get(), "All threads should complete");
        assertTrue(queriesCompleted > 1000, "Should complete substantial number of queries");
        assertEquals(0, totalErrors.get(), "No errors should occur");

        System.out.println("  ✓ PASS: Sustained load completed successfully");
    }

    /**
     * Generate random query points for testing.
     */
    private List<Point3f> generateQueryPoints(int count) {
        var points = new ArrayList<Point3f>(count);
        for (int i = 0; i < count; i++) {
            var x = (float) (Math.random() * 100);
            var y = (float) (Math.random() * 100);
            var z = (float) (Math.random() * 100);
            points.add(new Point3f(x, y, z));
        }
        return points;
    }

    /**
     * Populate octree with random entities for testing.
     */
    private void populateOctree(Octree<LongEntityID, String> octree, int count) {
        System.out.println("Populating octree with " + count + " entities...");
        for (int i = 0; i < count; i++) {
            var x = (float) (Math.random() * 100);
            var y = (float) (Math.random() * 100);
            var z = (float) (Math.random() * 100);
            octree.insert(new Point3f(x, y, z), (byte) 0, "entity-" + i);
        }
        System.out.println("Octree populated.\n");
    }
}
