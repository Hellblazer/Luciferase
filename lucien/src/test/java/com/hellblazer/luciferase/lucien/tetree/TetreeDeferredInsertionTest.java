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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.DeferredInsertionManager;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test deferred insertion functionality for Tetree
 *
 * @author hal.hildebrand
 */
public class TetreeDeferredInsertionTest {

    private Tetree<LongEntityID, String> tetree;
    private static final byte TEST_LEVEL = 5;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }

    @Test
    void testBasicDeferredInsertion() {
        // Enable deferred insertion
        tetree.setDeferredInsertionEnabled(true);
        assertTrue(tetree.isDeferredInsertionEnabled());

        // Insert multiple entities
        List<LongEntityID> entityIds = new ArrayList<>();
        int numEntities = 100;

        for (int i = 0; i < numEntities; i++) {
            Point3f position = new Point3f(100 + i, 200 + i, 300 + i);
            LongEntityID id = tetree.deferredInsert(position, TEST_LEVEL, "Entity " + i);
            entityIds.add(id);
        }

        // Check that entities are not yet in the spatial index
        assertEquals(0, tetree.entityCount());
        assertEquals(numEntities, tetree.getPendingInsertionCount());

        // Flush the deferred insertions
        int flushed = tetree.flushDeferredInsertions();
        assertEquals(numEntities, flushed);

        // Verify all entities are now in the index
        assertEquals(numEntities, tetree.entityCount());
        assertEquals(0, tetree.getPendingInsertionCount());

        // Verify each entity can be retrieved
        for (int i = 0; i < numEntities; i++) {
            String content = tetree.getEntity(entityIds.get(i));
            assertEquals("Entity " + i, content);
        }
    }

    @Test
    void testAutoFlushOnBatchSize() {
        // Configure deferred insertion with small batch size
        DeferredInsertionManager.DeferredInsertionConfig config = tetree.getDeferredInsertionConfig();
        config.setMaxBatchSize(10);

        tetree.setDeferredInsertionEnabled(true);

        // Insert more than batch size
        for (int i = 0; i < 25; i++) {
            Point3f position = new Point3f(100 + i, 200 + i, 300 + i);
            tetree.deferredInsert(position, TEST_LEVEL, "Entity " + i);
        }

        // Should have auto-flushed twice (at 10 and 20), with 5 pending
        assertEquals(20, tetree.entityCount());
        assertEquals(5, tetree.getPendingInsertionCount());

        // Flush remaining
        tetree.flushDeferredInsertions();
        assertEquals(25, tetree.entityCount());
    }

    @Test
    void testAutoFlushOnQuery() throws InterruptedException {
        // Configure deferred insertion
        DeferredInsertionManager.DeferredInsertionConfig config = tetree.getDeferredInsertionConfig();
        config.setAutoFlushOnQuery(true);
        config.setMaxBatchSize(1000); // High batch size to prevent auto-flush

        tetree.setDeferredInsertionEnabled(true);

        // Insert some entities
        Point3f position1 = new Point3f(100, 200, 300);
        Point3f position2 = new Point3f(150, 250, 350);
        LongEntityID id1 = tetree.deferredInsert(position1, TEST_LEVEL, "Entity 1");
        LongEntityID id2 = tetree.deferredInsert(position2, TEST_LEVEL, "Entity 2");

        // Should have 2 pending
        assertEquals(2, tetree.getPendingInsertionCount());
        assertEquals(0, tetree.entityCount());

        // Perform a query - should trigger auto-flush
        var neighbors = tetree.kNearestNeighbors(new Point3f(125, 225, 325), 2, 1000f);

        // Should have flushed
        assertEquals(0, tetree.getPendingInsertionCount());
        assertEquals(2, tetree.entityCount());
        assertEquals(2, neighbors.size());
    }

    @Test
    void testDeferredInsertionWithBounds() {
        tetree.setDeferredInsertionEnabled(true);

        // Insert entities with bounds
        Point3f position = new Point3f(100, 200, 300);
        EntityBounds bounds = new EntityBounds(
            new Point3f(90, 190, 290),
            new Point3f(110, 210, 310)
        );

        LongEntityID id = new LongEntityID(1);
        tetree.deferredInsert(id, position, TEST_LEVEL, "Bounded Entity", bounds);

        // Flush
        tetree.flushDeferredInsertions();

        // Verify entity and bounds
        assertEquals("Bounded Entity", tetree.getEntity(id));
        EntityBounds retrievedBounds = tetree.getEntityBounds(id);
        assertNotNull(retrievedBounds);
        assertEquals(90f, retrievedBounds.getMinX(), 0.001f);
        assertEquals(110f, retrievedBounds.getMaxX(), 0.001f);
    }

    @Test
    void testDeferredInsertionStatistics() {
        tetree.setDeferredInsertionEnabled(true);

        // Configure batch size
        tetree.getDeferredInsertionConfig().setMaxBatchSize(5);

        // Insert entities
        for (int i = 0; i < 12; i++) {
            Point3f position = new Point3f(100 + i, 200 + i, 300 + i);
            tetree.deferredInsert(position, TEST_LEVEL, "Entity " + i);
        }

        // Get statistics
        Map<String, Integer> stats = tetree.getDeferredInsertionStats();
        assertEquals(12, stats.get("totalInsertions").intValue());
        assertEquals(2, stats.get("totalFlushes").intValue()); // Auto-flushed at 5 and 10
        assertEquals(10, stats.get("totalBatches").intValue()); // 5 + 5
        assertEquals(2, stats.get("pendingInsertions").intValue());
        assertEquals(5, stats.get("averageBatchSize").intValue());
    }

    @Test
    void testConcurrentDeferredInsertions() throws InterruptedException, ExecutionException {
        tetree.setDeferredInsertionEnabled(true);
        tetree.getDeferredInsertionConfig().setMaxBatchSize(100);

        int numThreads = 4;
        int insertsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<List<LongEntityID>>> futures = new ArrayList<>();

        // Launch concurrent insertions
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                List<LongEntityID> ids = new ArrayList<>();
                for (int i = 0; i < insertsPerThread; i++) {
                    Point3f position = new Point3f(
                        100 + threadId * 100 + i,
                        200 + threadId * 100 + i,
                        300 + threadId * 100 + i
                    );
                    LongEntityID id = tetree.deferredInsert(position, TEST_LEVEL, 
                                                           "Thread " + threadId + " Entity " + i);
                    ids.add(id);
                }
                return ids;
            }));
        }

        // Wait for all threads to complete
        List<LongEntityID> allIds = new ArrayList<>();
        for (Future<List<LongEntityID>> future : futures) {
            allIds.addAll(future.get());
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Should have all pending (batch size not reached)
        assertEquals(numThreads * insertsPerThread, tetree.getPendingInsertionCount());

        // Flush all
        int flushed = tetree.flushDeferredInsertions();
        assertEquals(numThreads * insertsPerThread, flushed);
        assertEquals(numThreads * insertsPerThread, tetree.entityCount());

        // Verify all entities exist
        for (LongEntityID id : allIds) {
            assertNotNull(tetree.getEntity(id));
        }
    }

    @Test
    void testDeferredInsertionDisabling() {
        tetree.setDeferredInsertionEnabled(true);

        // Insert some deferred
        for (int i = 0; i < 5; i++) {
            Point3f position = new Point3f(100 + i, 200 + i, 300 + i);
            tetree.deferredInsert(position, TEST_LEVEL, "Entity " + i);
        }

        assertEquals(5, tetree.getPendingInsertionCount());

        // Disable deferred insertion - should auto-flush
        tetree.setDeferredInsertionEnabled(false);
        
        assertEquals(0, tetree.getPendingInsertionCount());
        assertEquals(5, tetree.entityCount());

        // Further insertions should be immediate
        Point3f position = new Point3f(200, 300, 400);
        tetree.deferredInsert(position, TEST_LEVEL, "Immediate Entity");
        
        assertEquals(6, tetree.entityCount());
        assertEquals(0, tetree.getPendingInsertionCount());
    }

    @Test
    void testSpatialLocalityOptimization() {
        tetree.setDeferredInsertionEnabled(true);
        tetree.getDeferredInsertionConfig().setMaxBatchSize(1000);

        // Insert entities in random order
        List<Point3f> positions = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) {
                for (int z = 0; z < 10; z++) {
                    positions.add(new Point3f(x * 10 + 50, y * 10 + 50, z * 10 + 50));
                }
            }
        }

        // Shuffle to simulate random insertion order
        Collections.shuffle(positions);

        // Insert all entities
        for (int i = 0; i < positions.size(); i++) {
            tetree.deferredInsert(positions.get(i), TEST_LEVEL, "Entity " + i);
        }

        // Measure time to flush (should be optimized by spatial sorting)
        long startTime = System.nanoTime();
        int flushed = tetree.flushDeferredInsertions();
        long endTime = System.nanoTime();

        assertEquals(positions.size(), flushed);
        assertEquals(positions.size(), tetree.entityCount());

        // The flush should be relatively fast due to spatial locality optimization
        long elapsedMs = (endTime - startTime) / 1_000_000;
        System.out.println("Flushed " + positions.size() + " entities in " + elapsedMs + " ms");
        assertTrue(elapsedMs < 1000, "Flush took too long: " + elapsedMs + " ms");
    }

    @Test
    void testDelayedAutoFlush() throws InterruptedException {
        // Configure with delay-based auto-flush
        DeferredInsertionManager.DeferredInsertionConfig config = tetree.getDeferredInsertionConfig();
        config.setMaxBatchSize(1000); // High to prevent size-based flush
        config.setMaxFlushDelayMillis(100); // 100ms delay

        tetree.setDeferredInsertionEnabled(true);

        // Insert an entity
        Point3f position = new Point3f(100, 200, 300);
        tetree.deferredInsert(position, TEST_LEVEL, "Delayed Entity");

        // Should be pending
        assertEquals(1, tetree.getPendingInsertionCount());
        assertEquals(0, tetree.entityCount());

        // Wait for auto-flush
        Thread.sleep(150);

        // Should have auto-flushed
        assertEquals(0, tetree.getPendingInsertionCount());
        assertEquals(1, tetree.entityCount());
    }
}