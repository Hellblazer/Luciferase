/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.65: the {@code maxGhosts} admission check in
 * {@code GhostStateManager.updateGhost()} must be atomic with the insert. Previously the size check and
 * the {@code put} were separate ConcurrentHashMap operations, so concurrent inserts of distinct new
 * entities could each pass the guard and drive the map past the declared limit. With many threads each
 * inserting a unique entity, the final ghost count must never exceed {@code maxGhosts}.
 *
 * @author hal.hildebrand
 */
class GhostStateManagerMaxLimitRaceTest {

    private static final int MAX_GHOSTS = 50;

    private GhostStateManager manager;
    private UUID sourceBubbleId;

    @BeforeEach
    void setUp() {
        var rootKey = com.hellblazer.luciferase.lucien.tetree.TetreeKey.create((byte) 10, 0L, 0L);
        var bounds = BubbleBounds.fromTetreeKey(rootKey);
        manager = new GhostStateManager(bounds, MAX_GHOSTS);
        sourceBubbleId = UUID.randomUUID();
    }

    @Test
    void concurrentInsertsNeverExceedMaxGhosts() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            int threads = 16;
            int perThread = 200; // 3200 distinct entities >> MAX_GHOSTS
            var pool = Executors.newFixedThreadPool(threads);
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    final int tid = t;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                var entityId = new StringEntityID("t" + tid + "-e" + i);
                                var event = new EntityUpdateEvent(entityId, new Point3f(1, 1, 1),
                                                                  new Point3f(0, 0, 0), 1000L + i, 1L);
                                manager.updateGhost(sourceBubbleId, event);
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(15, TimeUnit.SECONDS), "all inserter threads must finish");

                assertTrue(manager.getActiveGhostCount() <= MAX_GHOSTS,
                           "ghost count (" + manager.getActiveGhostCount() + ") must never exceed maxGhosts ("
                           + MAX_GHOSTS + ") despite concurrent admission (Luciferase-0frcy.65)");
            } finally {
                pool.shutdownNow();
            }
        });
    }
}
