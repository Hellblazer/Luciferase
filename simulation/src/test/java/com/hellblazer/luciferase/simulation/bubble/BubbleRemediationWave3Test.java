/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for wave-3 bubble-package remediation beads:
 * Luciferase-0frcy.81 (BubbleEntityStore non-atomic get-remove-insert zombie),
 * .82 (BubbleLifecycle.performJoin wall-clock ms used as sim bucket),
 * .84 (TetreeBubbleFactory dead distribution computation).
 */
class BubbleRemediationWave3Test {

    // ---- Luciferase-0frcy.82: Merge event stamped with logical bucket, not wall-clock ms ----

    @Test
    void performJoinUsesLogicalBucketNotWallClock() {
        var captured = new AtomicReference<BubbleEvent>();
        var lifecycle = new BubbleLifecycle(captured::set);
        lifecycle.setUuidSupplier(UUID::randomUUID);
        // Inject a logical simulation-time source (tick count), not wall-clock.
        lifecycle.setBucketSupplier(() -> 4242L);

        var b1 = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);
        var b2 = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);

        lifecycle.performJoin(b1, b2);

        var event = captured.get();
        assertInstanceOf(BubbleEvent.Merge.class, event);
        var merge = (BubbleEvent.Merge) event;
        assertEquals(4242L, merge.bucket(),
                     "Merge bucket must be the logical sim-time tick, not wall-clock millis");
        // Pre-fix: bucket = clock.currentTimeMillis() => Unix epoch ms (~1.7e12),
        // far above any plausible tick count.
        assertTrue(merge.bucket() < 1_000_000_000L,
                   "bucket must be a tick count, not Unix epoch milliseconds");
    }

    @Test
    void performJoinExplicitBucketOverload() {
        var captured = new AtomicReference<BubbleEvent>();
        var lifecycle = new BubbleLifecycle(captured::set);
        lifecycle.setUuidSupplier(UUID::randomUUID);

        var b1 = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);
        var b2 = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16L);

        lifecycle.performJoin(b1, b2, 99L);

        var merge = (BubbleEvent.Merge) captured.get();
        assertEquals(99L, merge.bucket());
    }

    // ---- Luciferase-0frcy.81: concurrent update/remove never leaves a zombie entity ----

    @Test
    void concurrentUpdateAndRemoveDoesNotZombieEntity() throws Exception {
        var controller = new RealTimeController(UUID.randomUUID(), "test", 100);
        var store = new BubbleEntityStore((byte) 10, controller);

        int iterations = 500;
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                String id = "entity-" + i;
                store.addEntity(id, new Point3f(10f, 10f, 10f), "content");

                var start = new CountDownLatch(1);
                var updater = executor.submit(() -> {
                    awaitQuietly(start);
                    store.updateEntityPosition(id, new Point3f(20f, 20f, 20f));
                });
                var remover = executor.submit(() -> {
                    awaitQuietly(start);
                    store.removeEntity(id);
                });
                start.countDown();
                updater.get(5, TimeUnit.SECONDS);
                remover.get(5, TimeUnit.SECONDS);

                // The zombie defect: when updateEntityPosition's get-remove-insert
                // interleaves with a concurrent removeEntity, the maps get cleared
                // but insert re-adds the internal entity to the spatial index,
                // leaving an orphan that is in spatialIndex but in NO map.
                // getAllEntityRecords() joins via idMapping (so it hides the
                // orphan) but entityCount() reads the spatial index directly.
                // A consistent store keeps these equal.
                int indexCount = store.entityCount();
                int mappedCount = store.getAllEntityRecords().size();
                assertEquals(mappedCount, indexCount,
                             "spatial-index count must equal id-mapped record count (zombie orphan leak) for " + id
                             + " (index=" + indexCount + ", mapped=" + mappedCount + ")");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- Luciferase-0frcy.84: factory delegates to grid without dead duplicate computation ----

    @Test
    void factoryCreateBubblesDelegatesToGrid() {
        // The factory is pure delegation: it must produce exactly what the grid's own
        // createBubbles() produces for the same parameters (Luciferase-0frcy.84). We assert
        // delegation parity against a directly-driven grid rather than a magic count, because
        // the grid's distribution legitimately caps level-0 to a single root bubble (so the
        // realized count can be below the requested count for some level configurations).
        var requestedCount = 9;
        byte maxLevel = (byte) 2;

        // Drive a reference grid directly with the same effective parameters the factory uses
        // internally (the factory fixes targetFrameMs = 16).
        var referenceGrid = new TetreeBubbleGrid((byte) 4);
        referenceGrid.createBubbles(requestedCount, maxLevel, 16L);
        var expectedFromGrid = referenceGrid.getBubbleCount();

        var factoryGrid = new TetreeBubbleGrid((byte) 4);
        TetreeBubbleFactory.createBubbles(factoryGrid, requestedCount, maxLevel, 100);

        assertEquals(expectedFromGrid, factoryGrid.getBubbleCount(),
                     "factory must produce exactly what the grid's own createBubbles() produces "
                     + "(pure delegation, no independent distribution)");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
