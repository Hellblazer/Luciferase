/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Luciferase-aqx6x: updateBatchParallel/removeBatchParallel performed per-entity
 * get/remove/insert under separate locks across parallel threads, so between an entity's remove and reinsert it was
 * absent — concurrent range/kNN/collision readers saw partial batch state. The fix runs each batch under one
 * write-lock critical section, making it atomic versus concurrent readers (which acquire the same read lock).
 *
 * @author hal.hildebrand
 */
class ParallelBulkOperationsAtomicityTest {

    @Test
    void updateBatchIsAtomicVersusConcurrentReaders() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        final int n = 1000;
        final byte level = 10;
        var rnd = new Random(42);

        var ids = new ArrayList<LongEntityID>(n);
        var newPositions = new ArrayList<Point3f>(n);
        for (int i = 0; i < n; i++) {
            var p = new Point3f(1 + rnd.nextFloat() * 800, 1 + rnd.nextFloat() * 800, 1 + rnd.nextFloat() * 800);
            ids.add(octree.insert(p, level, "e" + i));
            newPositions.add(new Point3f(1 + rnd.nextFloat() * 800, 1 + rnd.nextFloat() * 800, 1 + rnd.nextFloat() * 800));
        }
        assertEquals(n, octree.entityCount());

        var bulkProcessor = new BulkOperationProcessor<>(octree);
        var pbo = new ParallelBulkOperations<>(octree, bulkProcessor, ParallelBulkOperations.defaultConfig());

        // A reader samples the entity count throughout the update. update = per-entity remove+reinsert, so the
        // count must stay exactly n at every instant the reader can observe. The pre-fix interleaving let the
        // reader catch an entity removed-but-not-yet-reinserted (count < n).
        var minObserved = new AtomicInteger(Integer.MAX_VALUE);
        var stop = new AtomicBoolean(false);
        var readerReady = new java.util.concurrent.CountDownLatch(1);
        var reader = new Thread(() -> {
            readerReady.countDown();
            while (!stop.get()) {
                int c = octree.entityCount();
                minObserved.updateAndGet(m -> Math.min(m, c));
            }
        });
        reader.start();
        readerReady.await(); // ensure the reader is sampling before the batch starts (avoid a vacuous pass)

        var result = pbo.updateBatchParallel(ids, newPositions, level).get(30, TimeUnit.SECONDS);

        stop.set(true);
        reader.join(5000);

        assertEquals(n, result.size(), "every entity was updated");
        assertEquals(n, octree.entityCount(), "count unchanged after atomic batch update");
        assertEquals(n, minObserved.get(),
                     "concurrent reader must never observe a sub-n (mid-batch) count (Luciferase-aqx6x)");
    }

    @Test
    void removeBatchRemovesExactlyTheRequestedEntities() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        final int n = 600;
        final byte level = 10;
        var rnd = new Random(7);

        var ids = new ArrayList<LongEntityID>(n);
        for (int i = 0; i < n; i++) {
            var p = new Point3f(1 + rnd.nextFloat() * 800, 1 + rnd.nextFloat() * 800, 1 + rnd.nextFloat() * 800);
            ids.add(octree.insert(p, level, "e" + i));
        }
        var toRemove = ids.subList(0, n / 2);

        var bulkProcessor = new BulkOperationProcessor<>(octree);
        var pbo = new ParallelBulkOperations<>(octree, bulkProcessor, ParallelBulkOperations.defaultConfig());

        // Concurrent reader: an atomic batch must let the reader observe ONLY the before-count (n) or the
        // after-count (n - n/2), never any intermediate value.
        var observed = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        var stop = new AtomicBoolean(false);
        var readerReady = new java.util.concurrent.CountDownLatch(1);
        var reader = new Thread(() -> {
            readerReady.countDown();
            while (!stop.get()) {
                observed.add(octree.entityCount());
            }
        });
        reader.start();
        readerReady.await();

        int removed = pbo.removeBatchParallel(new ArrayList<>(toRemove)).get(30, TimeUnit.SECONDS);
        stop.set(true);
        reader.join(5000);

        assertEquals(n / 2, removed, "exactly the requested entities are removed");
        assertEquals(n - n / 2, octree.entityCount(), "remaining count is correct (no missing/duplicate)");
        for (int c : observed) {
            assertTrue(c == n || c == n - n / 2,
                       "reader saw an intermediate count " + c + " — batch remove was not atomic (Luciferase-aqx6x)");
        }
        for (var id : toRemove) {
            assertNull(octree.getEntity(id), "removed entity must be absent");
        }
        for (var id : ids.subList(n / 2, n)) {
            assertNotNull(octree.getEntity(id), "untouched entity must remain");
        }
    }
}
