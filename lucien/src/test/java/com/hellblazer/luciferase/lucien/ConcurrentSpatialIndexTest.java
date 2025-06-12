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

import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent tests for spatial index implementations to verify thread safety
 *
 * @author hal.hildebrand
 */
public class ConcurrentSpatialIndexTest {

    private static final int THREAD_COUNT = 20;
    private static final int OPERATIONS_PER_THREAD = 1000;
    private static final int TEST_TIMEOUT_SECONDS = 30;

    @Test
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    public void testConcurrentOctreeInserts() throws InterruptedException {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        performConcurrentInserts(octree, "Octree");
    }

    @Test
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    public void testConcurrentTetreeInserts() throws InterruptedException {
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        performConcurrentInserts(tetree, "Tetree");
    }

    @Test
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    public void testConcurrentOctreeMixedOperations() throws InterruptedException {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        performConcurrentMixedOperations(octree, "Octree");
    }

    @Test
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    public void testConcurrentTetreeMixedOperations() throws InterruptedException {
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        performConcurrentMixedOperations(tetree, "Tetree");
    }

    @Test
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    public void testConcurrentOctreeKNNSearch() throws InterruptedException {
        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator());
        performConcurrentKNNSearch(octree, "Octree");
    }

    @Test
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    public void testConcurrentTetreeKNNSearch() throws InterruptedException {
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        performConcurrentKNNSearch(tetree, "Tetree");
    }

    private void performConcurrentInserts(SpatialIndex<LongEntityID, String> index, String indexType) 
            throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        Set<LongEntityID> insertedIds = ConcurrentHashMap.newKeySet();

        // Create threads
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            Thread thread = new Thread(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        try {
                            float x = threadId * 1000 + j;
                            float y = threadId * 100 + j;
                            float z = threadId * 10 + j;
                            
                            LongEntityID id = new LongEntityID(threadId * OPERATIONS_PER_THREAD + j);
                            index.insert(id, new Point3f(x, y, z), (byte) 10, 
                                       "Entity-" + threadId + "-" + j);
                            
                            insertedIds.add(id);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            e.printStackTrace();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            thread.setName(indexType + "-Insert-" + i);
            threads.add(thread);
            thread.start();
        }

        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for completion
        assertTrue(endLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                  "Threads did not complete in time");

        // Verify results
        assertEquals(0, errorCount.get(), "No errors should occur during concurrent inserts");
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, successCount.get(), 
                    "All inserts should succeed");
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, index.entityCount(),
                    "Entity count should match successful inserts");
        
        // Verify all entities are findable
        for (LongEntityID id : insertedIds) {
            assertTrue(index.containsEntity(id), "Entity " + id + " should be found");
        }
    }

    private void performConcurrentMixedOperations(SpatialIndex<LongEntityID, String> index, String indexType) 
            throws InterruptedException {
        // Pre-populate with some entities
        for (int i = 0; i < 1000; i++) {
            index.insert(new LongEntityID(i), 
                        new Point3f(i * 10, i * 10, i * 10), 
                        (byte) 10, 
                        "Initial-" + i);
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger operationCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Create threads with mixed operations
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            Thread thread = new Thread(() -> {
                try {
                    startLatch.await();
                    Random random = new Random(threadId);
                    
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        try {
                            int operation = random.nextInt(100);
                            
                            if (operation < 40) { // 40% inserts
                                LongEntityID id = new LongEntityID(1000 + threadId * 1000 + j);
                                index.insert(id, 
                                           new Point3f(random.nextFloat() * 1000,
                                                      random.nextFloat() * 1000,
                                                      random.nextFloat() * 1000),
                                           (byte) 10,
                                           "Mixed-" + id);
                            } else if (operation < 60) { // 20% removes
                                LongEntityID id = new LongEntityID(random.nextInt(1000));
                                index.removeEntity(id);
                            } else if (operation < 80) { // 20% updates
                                LongEntityID id = new LongEntityID(random.nextInt(1000));
                                try {
                                    if (index.containsEntity(id)) {
                                        index.updateEntity(id,
                                                         new Point3f(random.nextFloat() * 1000,
                                                                    random.nextFloat() * 1000,
                                                                    random.nextFloat() * 1000),
                                                         (byte) 10);
                                    }
                                } catch (IllegalArgumentException e) {
                                    // Entity was removed by another thread between check and update
                                    // This is expected in concurrent scenarios
                                }
                            } else { // 20% reads
                                // Various read operations
                                if (random.nextBoolean()) {
                                    index.entityCount();
                                } else {
                                    index.nodes().count();
                                }
                            }
                            
                            operationCount.incrementAndGet();
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            e.printStackTrace();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            thread.setName(indexType + "-Mixed-" + i);
            threads.add(thread);
            thread.start();
        }

        startLatch.countDown();
        assertTrue(endLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                  "Threads did not complete in time");

        // Verify no errors
        assertEquals(0, errorCount.get(), "No errors should occur during mixed operations");
        assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, operationCount.get(),
                    "All operations should complete");
        
        // Verify index is still consistent
        assertTrue(index.entityCount() >= 0, "Entity count should be non-negative");
        assertTrue(index.nodeCount() >= 0, "Node count should be non-negative");
    }

    private void performConcurrentKNNSearch(AbstractSpatialIndex<LongEntityID, String, ?> index, String indexType) 
            throws InterruptedException {
        // Pre-populate with entities in a grid
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) {
                for (int z = 0; z < 10; z++) {
                    LongEntityID id = new LongEntityID(x * 100 + y * 10 + z);
                    index.insert(id, 
                                new Point3f(x * 100, y * 100, z * 100), 
                                (byte) 10, 
                                "Grid-" + x + "-" + y + "-" + z);
                }
            }
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Create threads for k-NN searches
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            Thread thread = new Thread(() -> {
                try {
                    startLatch.await();
                    Random random = new Random(threadId);
                    
                    for (int j = 0; j < OPERATIONS_PER_THREAD / 10; j++) { // Fewer k-NN ops as they're expensive
                        try {
                            Point3f queryPoint = new Point3f(
                                random.nextFloat() * 900,
                                random.nextFloat() * 900,
                                random.nextFloat() * 900
                            );
                            
                            List<LongEntityID> neighbors = index.kNearestNeighbors(queryPoint, 10, 500.0f);
                            
                            // Verify results are reasonable
                            assertNotNull(neighbors, "k-NN should not return null");
                            assertTrue(neighbors.size() <= 10, "Should return at most k neighbors");
                            
                            // Verify distances are ordered
                            for (int k = 1; k < neighbors.size(); k++) {
                                Point3f p1 = index.getEntityPosition(neighbors.get(k-1));
                                Point3f p2 = index.getEntityPosition(neighbors.get(k));
                                if (p1 != null && p2 != null) {
                                    float d1 = queryPoint.distance(p1);
                                    float d2 = queryPoint.distance(p2);
                                    assertTrue(d1 <= d2 + 0.001f, // Small epsilon for float comparison
                                             "Neighbors should be ordered by distance");
                                }
                            }
                            
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            e.printStackTrace();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            thread.setName(indexType + "-KNN-" + i);
            threads.add(thread);
            thread.start();
        }

        startLatch.countDown();
        assertTrue(endLatch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                  "Threads did not complete in time");

        assertEquals(0, errorCount.get(), "No errors should occur during k-NN searches");
        assertTrue(successCount.get() > 0, "At least some k-NN searches should succeed");
    }
}