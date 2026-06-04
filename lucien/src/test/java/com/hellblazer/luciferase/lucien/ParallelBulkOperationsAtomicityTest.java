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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
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
 * Also covers Luciferase-7wzml.60: AutoCloseable lifecycle, daemon-thread factory for the fixed pool, and
 * configureParallelOperations closing the old pool before the volatile swap.
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

    // ---- Luciferase-7wzml.60: AutoCloseable / daemon-thread / configureParallelOperations ----

    @Test
    void closeShutsPools() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var bulkProcessor = new BulkOperationProcessor<>(octree);

        // work-stealing path (ForkJoinPool) — default config has useWorkStealing=true
        var wsPbo = new ParallelBulkOperations<>(octree, bulkProcessor, ParallelBulkOperations.defaultConfig());
        var wsPool = getWorkStealingPool(wsPbo);
        assertFalse(wsPool.isShutdown(), "pool must be live before close");
        wsPbo.close();
        assertTrue(wsPool.isShutdown(), "ForkJoinPool must be shut down after close() (Luciferase-7wzml.60)");

        // fixed-thread path (ExecutorService)
        var ftConfig = new ParallelBulkOperations.ParallelConfig().withWorkStealing(false);
        var ftPbo = new ParallelBulkOperations<>(octree, bulkProcessor, ftConfig);
        var ftPool = (java.util.concurrent.ExecutorService) getFixedThreadPool(ftPbo);
        assertFalse(ftPool.isShutdown(), "fixed pool must be live before close");
        ftPbo.close();
        assertTrue(ftPool.isShutdown(), "fixed-thread pool must be shut down after close() (Luciferase-7wzml.60)");
    }

    @Test
    void fixedPoolThreadsAreDaemon() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var bulkProcessor = new BulkOperationProcessor<>(octree);
        var config = new ParallelBulkOperations.ParallelConfig().withWorkStealing(false).withThreadCount(2);
        try (var pbo = new ParallelBulkOperations<>(octree, bulkProcessor, config)) {
            var pool = (java.util.concurrent.ExecutorService) getFixedThreadPool(pbo);
            var daemonCapture = new AtomicBoolean(false);
            // Submit a task to force thread creation
            pool.submit(() -> daemonCapture.set(Thread.currentThread().isDaemon())).get(5, TimeUnit.SECONDS);
            assertTrue(daemonCapture.get(),
                       "fixed-pool threads must be daemon so they never pin JVM exit (Luciferase-7wzml.60)");
        }
    }

    @Test
    void configureParallelOperationsClosesOldPool() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        // Capture the instance created during Octree construction
        var oldOps = getParallelOperations(octree);
        assertNotNull(oldOps, "parallelOperations must be initialized at construction");
        var oldPool = getWorkStealingPool(oldOps); // defaultConfig uses work-stealing

        // Reconfigure — must shut down the old pool before the volatile swap
        octree.configureParallelOperations(ParallelBulkOperations.defaultConfig());

        assertTrue(oldPool.isShutdown(),
                   "old ForkJoinPool must be shut down after reconfigure (Luciferase-7wzml.60)");
        var newOps = getParallelOperations(octree);
        assertNotSame(oldOps, newOps, "reconfigure must produce a new instance");
        assertFalse(getWorkStealingPool(newOps).isShutdown(), "new pool must be live after reconfigure");

        octree.close(); // cleanup
    }

    @Test
    void tryWithResourcesWorks() throws Exception {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var bulkProcessor = new BulkOperationProcessor<>(octree);
        ForkJoinPool captured;
        try (var pbo = new ParallelBulkOperations<>(octree, bulkProcessor, ParallelBulkOperations.defaultConfig())) {
            captured = getWorkStealingPool(pbo);
            assertFalse(captured.isShutdown(), "pool must be live inside try block");
        }
        assertTrue(captured.isShutdown(), "pool must be shut down after try-with-resources closes");
    }

    // ---- reflection helpers ----

    private static ForkJoinPool getWorkStealingPool(ParallelBulkOperations<?, ?, ?> pbo) throws Exception {
        Field f = ParallelBulkOperations.class.getDeclaredField("workStealingPool");
        f.setAccessible(true);
        return (ForkJoinPool) f.get(pbo);
    }

    private static Object getFixedThreadPool(ParallelBulkOperations<?, ?, ?> pbo) throws Exception {
        Field f = ParallelBulkOperations.class.getDeclaredField("fixedThreadPool");
        f.setAccessible(true);
        return f.get(pbo);
    }

    @SuppressWarnings("unchecked")
    private static <Key extends SpatialKey<Key>, ID extends com.hellblazer.luciferase.lucien.entity.EntityID, Content>
    ParallelBulkOperations<Key, ID, Content> getParallelOperations(
    AbstractSpatialIndex<Key, ID, Content> idx) throws Exception {
        Field f = AbstractSpatialIndex.class.getDeclaredField("parallelOperations");
        f.setAccessible(true);
        return (ParallelBulkOperations<Key, ID, Content>) f.get(idx);
    }
}
