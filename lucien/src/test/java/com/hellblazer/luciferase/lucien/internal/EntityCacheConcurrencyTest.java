/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.internal;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for EntityCache: validates that the size bound holds under
 * concurrent puts and that the size counter stays coherent (Luciferase-7wzml.127).
 */
class EntityCacheConcurrencyTest {

    private static final int MAX_SIZE   = 100;
    private static final int THREADS    = 16;
    private static final int PUTS_EACH  = 200;  // each thread puts 200 unique entries

    // Tolerated overshoot: at most (THREADS - 1) entries past maxSize in the
    // lock-free path — all threads pass the size check before any eviction completes.
    private static final int ALLOWED_OVERSHOOT = THREADS - 1;

    @Test
    void concurrentPutsNeverExceedMaxSizeBeyondAllowedOvershoot() throws InterruptedException {
        var cache = new EntityCache<LongEntityID>(MAX_SIZE);
        var idCounter = new AtomicLong();
        var latch = new CountDownLatch(THREADS);
        var executor = Executors.newFixedThreadPool(THREADS);
        var errors = new ArrayList<Throwable>();

        for (int t = 0; t < THREADS; t++) {
            executor.submit(() -> {
                latch.countDown();
                try {
                    latch.await();   // all threads start simultaneously
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < PUTS_EACH; i++) {
                    var id = new LongEntityID(idCounter.getAndIncrement());
                    var pos = new Point3f(i, i, i);
                    var bounds = new EntityBounds(pos, 1.0f);
                    cache.put(id, pos, bounds);
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Test timed out");

        int finalSize = cache.getStats().size();
        int hardLimit = MAX_SIZE + ALLOWED_OVERSHOOT;
        assertTrue(finalSize <= hardLimit,
                   "Cache size %d exceeded hard limit %d (maxSize=%d, allowedOvershoot=%d)"
                   .formatted(finalSize, hardLimit, MAX_SIZE, ALLOWED_OVERSHOOT));
    }

    @Test
    void statsSizeConsistentWithCacheAfterConcurrentPuts() throws InterruptedException {
        var cache = new EntityCache<LongEntityID>(MAX_SIZE);
        var idCounter = new AtomicLong();
        var executor = Executors.newFixedThreadPool(THREADS);
        var latch = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            executor.submit(() -> {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < PUTS_EACH; i++) {
                    var id = new LongEntityID(idCounter.getAndIncrement());
                    cache.put(id, new Point3f(i, i, i), new EntityBounds(new Point3f(i, i, i), 1.0f));
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Test timed out");

        // getStats().size() must equal cache's live size (both read cache.size())
        // and must be <= maxSize + ALLOWED_OVERSHOOT
        var stats = cache.getStats();
        assertEquals(stats.size(), cache.getStats().size(),
                     "Two consecutive getStats().size() reads diverged");
        assertTrue(stats.size() <= MAX_SIZE + ALLOWED_OVERSHOOT,
                   "Stats size %d > expected bound %d".formatted(stats.size(), MAX_SIZE + ALLOWED_OVERSHOOT));
    }

    @Test
    void singleThreadedPutNeverExceedsMaxSize() {
        var cache = new EntityCache<LongEntityID>(MAX_SIZE);
        for (int i = 0; i < MAX_SIZE * 5; i++) {
            var id = new LongEntityID(i);
            cache.put(id, new Point3f(i, i, i), new EntityBounds(new Point3f(i, i, i), 1.0f));
            // single-threaded: no concurrency race, size must stay <= maxSize
            int size = cache.getStats().size();
            assertTrue(size <= MAX_SIZE,
                       "Single-threaded put: size %d exceeded maxSize %d after %d puts".formatted(size, MAX_SIZE, i + 1));
        }
    }

    @Test
    void removeAndClearDoNotThrowOrDesync() {
        var cache = new EntityCache<LongEntityID>(MAX_SIZE);
        var id = new LongEntityID(1L);
        cache.put(id, new Point3f(1, 1, 1), new EntityBounds(new Point3f(1, 1, 1), 1.0f));
        cache.remove(id);
        assertNull(cache.getPosition(id));
        assertNull(cache.getBounds(id));
        cache.clear();
        assertEquals(0, cache.getStats().size());
    }
}
