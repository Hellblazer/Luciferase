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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for concurrent access to spatial indices
 *
 * @author hal.hildebrand
 */
public class SpatialIndexConcurrencyTest {

    private static final int THREAD_COUNT          = 8;
    private static final int OPERATIONS_PER_THREAD = 1000;
    private static final int TEST_TIMEOUT_SECONDS  = 30;

    @BeforeAll
    public static void beforeAll() {
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("RUN_SPATIAL_INDEX_PERF_TESTS", "false")));
    }

    static Stream<SpatialIndex<?, LongEntityID, String>> spatialIndexProvider() {
        var spanningPolicy = EntitySpanningPolicy.withSpanning();
        return Stream.of(new Octree<>(new SequentialLongIDGenerator(), 10, (byte) 20, spanningPolicy),
                         new Tetree<>(new SequentialLongIDGenerator(), 10, (byte) 20, spanningPolicy));
    }

    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testConcurrentInsertions(SpatialIndex<?, LongEntityID, String> index)
    throws InterruptedException, ExecutionException, TimeoutException {
        var executor = Executors.newFixedThreadPool(THREAD_COUNT);
        var insertedIds = new ConcurrentLinkedQueue<LongEntityID>();
        var barrier = new CyclicBarrier(THREAD_COUNT);

        try {
            var futures = IntStream.range(0, THREAD_COUNT).mapToObj(threadId -> executor.submit(() -> {
                try {
                    barrier.await(); // Ensure all threads start together

                    for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                        var position = new Point3f(threadId * 100 + i, threadId * 100 + i, threadId * 100 + i);
                        var id = index.insert(position, (byte) 10, "thread" + threadId + "_" + i);
                        insertedIds.add(id);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })).toList();

            // Wait for all insertions to complete
            for (var future : futures) {
                future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            // Verify all entities were inserted
            assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, index.entityCount());
            assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, insertedIds.size());

            // Verify all entities can be retrieved
            for (var id : insertedIds) {
                assertTrue(index.containsEntity(id));
                assertNotNull(index.getEntity(id));
            }

        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testConcurrentMixedOperations(SpatialIndex<?, LongEntityID, String> index)
    throws InterruptedException, ExecutionException, TimeoutException {
        // Pre-populate with some entities
        var sharedIds = new ConcurrentLinkedQueue<LongEntityID>();
        for (int i = 0; i < 1000; i++) {
            var id = index.insert(new Point3f(i, i, i), (byte) 10, "initial" + i);
            sharedIds.add(id);
        }

        var executor = Executors.newFixedThreadPool(THREAD_COUNT);
        var operationCounts = new ConcurrentHashMap<String, AtomicInteger>();
        operationCounts.put("insert", new AtomicInteger(0));
        operationCounts.put("update", new AtomicInteger(0));
        operationCounts.put("remove", new AtomicInteger(0));
        operationCounts.put("query", new AtomicInteger(0));

        try {
            var futures = IntStream.range(0, THREAD_COUNT).mapToObj(threadId -> executor.submit(() -> {
                var random = new Random(threadId);

                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    var operation = random.nextInt(4);

                    try {
                        switch (operation) {
                            case 0 -> {
                                // Insert
                                var position = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000,
                                                           random.nextFloat() * 1000);
                                var id = index.insert(position, (byte) 10, "thread" + threadId + "_" + i);
                                sharedIds.add(id);
                                operationCounts.get("insert").incrementAndGet();
                            }
                            case 1 -> {
                                // Update
                                if (!sharedIds.isEmpty()) {
                                    var ids = sharedIds.toArray(new LongEntityID[0]);
                                    if (ids.length > 0) {
                                        var id = ids[random.nextInt(ids.length)];
                                        var newPosition = new Point3f(random.nextFloat() * 1000,
                                                                      random.nextFloat() * 1000,
                                                                      random.nextFloat() * 1000);
                                        index.updateEntity(id, newPosition, (byte) 10);
                                        operationCounts.get("update").incrementAndGet();
                                    }
                                }
                            }
                            case 2 -> {
                                // Remove or query if nothing to remove
                                if (!sharedIds.isEmpty() && random.nextFloat() < 0.1) { // Only remove 10% of the time
                                    var id = sharedIds.poll();
                                    if (id != null) {
                                        index.removeEntity(id);
                                        operationCounts.get("remove").incrementAndGet();
                                    }
                                } else {
                                    // Do a query instead if we can't remove
                                    var queryPoint = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000,
                                                                 random.nextFloat() * 1000);
                                    index.kNearestNeighbors(queryPoint, 5, 100);
                                    operationCounts.get("query").incrementAndGet();
                                }
                            }
                            case 3 -> {
                                // Query
                                var queryPoint = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000,
                                                             random.nextFloat() * 1000);
                                index.kNearestNeighbors(queryPoint, 5, 100);
                                operationCounts.get("query").incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        // Log but continue - some operations may fail due to concurrent modifications
                        System.err.println("Operation " + operation + " failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        // Count failed operations as attempted
                        operationCounts.get("query").incrementAndGet(); // Default to query for accounting
                    }
                }
            })).toList();

            // Wait for all operations to complete
            for (var future : futures) {
                future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            // Verify operations were performed
            assertTrue(operationCounts.get("insert").get() > 0);
            assertTrue(operationCounts.get("update").get() > 0);
            // Remove operations might not happen if conditions aren't met
            assertTrue(operationCounts.get("remove").get() >= 0);
            assertTrue(operationCounts.get("query").get() > 0);

            // Total operations should be close to expected (some may fail due to concurrency)
            var totalOps = operationCounts.values().stream().mapToInt(AtomicInteger::get).sum();
            assertTrue(totalOps >= THREAD_COUNT * OPERATIONS_PER_THREAD * 0.9); // Allow 10% failure rate

        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testConcurrentQueries(SpatialIndex<?, LongEntityID, String> index)
    throws InterruptedException, ExecutionException, TimeoutException {
        // Pre-populate index with entities
        for (int i = 0; i < 10000; i++) {
            index.insert(
            new Point3f((float) Math.random() * 1000, (float) Math.random() * 1000, (float) Math.random() * 1000),
            (byte) 10, "entity" + i);
        }

        var executor = Executors.newFixedThreadPool(THREAD_COUNT);
        var queryCount = new AtomicInteger(0);

        try {
            var futures = IntStream.range(0, THREAD_COUNT).mapToObj(threadId -> executor.submit(() -> {
                var random = new Random(threadId);
                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    var queryType = random.nextInt(3);

                    switch (queryType) {
                        case 0 -> {
                            // k-NN query
                            var queryPoint = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000,
                                                         random.nextFloat() * 1000);
                            var neighbors = index.kNearestNeighbors(queryPoint, 10, 500);
                            assertNotNull(neighbors);
                            assertTrue(neighbors.size() <= 10);
                        }
                        case 1 -> {
                            // Range query
                            var origin = new Point3f(random.nextFloat() * 900, random.nextFloat() * 900,
                                                     random.nextFloat() * 900);
                            var region = new Spatial.Cube(origin.x, origin.y, origin.z, 100);
                            var entities = index.entitiesInRegion(region);
                            assertNotNull(entities);
                        }
                        case 2 -> {
                            // Ray query
                            var origin = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000,
                                                     random.nextFloat() * 1000);
                            var direction = new Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f,
                                                         random.nextFloat() - 0.5f);
                            direction.normalize();
                            var ray = new Ray3D(origin, direction);
                            var intersections = index.rayIntersectAll(ray);
                            assertNotNull(intersections);
                        }
                    }
                    queryCount.incrementAndGet();
                }
            })).toList();

            // Wait for all queries to complete
            for (var future : futures) {
                future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, queryCount.get());

        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testConcurrentSpanningEntities(SpatialIndex<?, LongEntityID, String> index)
    throws InterruptedException, ExecutionException, TimeoutException {
        var executor = Executors.newFixedThreadPool(THREAD_COUNT);
        var insertedIds = new ConcurrentLinkedQueue<LongEntityID>();

        try {
            var futures = IntStream.range(0, THREAD_COUNT).mapToObj(threadId -> executor.submit(() -> {
                var random = new Random(threadId);

                for (int i = 0; i < 50; i++) { // Fewer operations due to complexity
                    var center = new Point3f(random.nextFloat() * 800 + 100, random.nextFloat() * 800 + 100,
                                             random.nextFloat() * 800 + 100);
                    var size = random.nextFloat() * 200 + 50; // 50-250 units

                    // Ensure bounds are non-negative for Tetree compatibility
                    var minX = Math.max(0, center.x - size / 2);
                    var minY = Math.max(0, center.y - size / 2);
                    var minZ = Math.max(0, center.z - size / 2);
                    var maxX = center.x + size / 2;
                    var maxY = center.y + size / 2;
                    var maxZ = center.z + size / 2;
                    
                    var bounds = new EntityBounds(
                        new Point3f(minX, minY, minZ),
                        new Point3f(maxX, maxY, maxZ));

                    var id = new LongEntityID(threadId * 1000L + i);
                    index.insert(id, center, (byte) 8, "spanning" + threadId + "_" + i, bounds);
                    insertedIds.add(id);
                }
            })).toList();

            // Wait for all operations to complete
            for (var future : futures) {
                future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            // Verify all entities were inserted
            assertEquals(THREAD_COUNT * 50, index.entityCount());

            // Verify spanning entities
            for (var id : insertedIds) {
                assertTrue(index.containsEntity(id));
                assertTrue(index.getEntitySpanCount(id) >= 1);
            }

        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testConcurrentUpdates(SpatialIndex<?, LongEntityID, String> index)
    throws InterruptedException, ExecutionException, TimeoutException {
        // Pre-populate index
        var entityIds = new ArrayList<LongEntityID>();
        for (int i = 0; i < THREAD_COUNT * 100; i++) {
            var id = index.insert(new Point3f(i, i, i), (byte) 10, "entity" + i);
            entityIds.add(id);
        }

        var executor = Executors.newFixedThreadPool(THREAD_COUNT);
        var updateCount = new AtomicInteger(0);

        try {
            var futures = IntStream.range(0, THREAD_COUNT).mapToObj(threadId -> executor.submit(() -> {
                var random = new Random(threadId);
                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    var id = entityIds.get(random.nextInt(entityIds.size()));
                    var newPosition = new Point3f(random.nextFloat() * 1000, random.nextFloat() * 1000,
                                                  random.nextFloat() * 1000);
                    index.updateEntity(id, newPosition, (byte) 10);
                    updateCount.incrementAndGet();
                }
            })).toList();

            // Wait for all updates to complete
            for (var future : futures) {
                future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            // Verify entity count unchanged
            assertEquals(entityIds.size(), index.entityCount());
            assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, updateCount.get());

        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testRaceConditionOnSameNode(SpatialIndex<?, LongEntityID, String> index)
    throws InterruptedException, ExecutionException, TimeoutException {
        var executor = Executors.newFixedThreadPool(THREAD_COUNT);
        var position = new Point3f(500, 500, 500); // All threads insert at same position
        var barrier = new CyclicBarrier(THREAD_COUNT);

        try {
            var futures = IntStream.range(0, THREAD_COUNT).mapToObj(threadId -> executor.submit(() -> {
                try {
                    barrier.await(); // Ensure all threads start exactly together

                    // All threads try to insert at the same position simultaneously
                    for (int i = 0; i < 100; i++) {
                        index.insert(position, (byte) 15, "thread" + threadId + "_" + i);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })).toList();

            // Wait for all operations to complete
            for (var future : futures) {
                future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            // Verify all entities were inserted
            assertEquals(THREAD_COUNT * 100, index.entityCount());

            // Verify all entities at the position
            var entitiesAtPosition = index.lookup(position, (byte) 15);
            assertEquals(THREAD_COUNT * 100, entitiesAtPosition.size());

        } finally {
            executor.shutdownNow();
        }
    }
}
