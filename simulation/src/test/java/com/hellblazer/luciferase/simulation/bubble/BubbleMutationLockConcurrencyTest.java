/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency regression for the one coherent mutation-lock protocol (Luciferase-n7io1).
 * <p>
 * Two <b>different</b> multi-bubble entity-writer paths hammer the SAME bubble pair concurrently:
 * <ul>
 *   <li>{@link BubbleLifecycle#transferEntities} — a production writer now guarded by
 *       {@link MutationLocks};</li>
 *   <li>an independent writer that follows the same {@link MutationLocks} protocol directly (the shape
 *       used by {@code TetrahedralMigration}/{@code MultiDirectionalMigration}/{@code BubbleMerger}:
 *       lock the pair in UUID order, then add-to-target / remove-from-source).</li>
 * </ul>
 * Because every multi-bubble writer acquires the involved bubbles' locks in a single global UUID order,
 * (1) the run cannot deadlock (it completes within the timeout) and (2) entities are exactly conserved —
 * no entity is lost, duplicated, or created. Before n7io1, {@code transferEntities} skipped the lock, so
 * its read-snapshot / add / remove interleaved with the other writer and could lose or duplicate entities.
 *
 * @author hal.hildebrand
 */
class BubbleMutationLockConcurrencyTest {

    @Test
    void concurrentDistinctWriterPaths_conserveEntitiesAndDoNotDeadlock() throws Exception {
        var lifecycle = new BubbleLifecycle(e -> { });
        var a = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 10);
        var b = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 10);

        int entityCount = 50;
        var originalIds = new HashSet<String>();
        for (int i = 0; i < entityCount; i++) {
            var id = "e" + i;
            originalIds.add(id);
            a.addEntity(id, new Point3f(i, 0f, 0f), "content-" + i);
        }

        int iterations = 300;
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);

        // Path 1: production BubbleLifecycle.transferEntities, ping-ponging the pair.
        Callable<Void> path1 = () -> {
            start.await();
            for (int i = 0; i < iterations; i++) {
                lifecycle.transferEntities(a, b);
                lifecycle.transferEntities(b, a);
            }
            return null;
        };

        // Path 2: an independent protocol-honoring writer (same MutationLocks discipline as the
        // migration/merge paths), moving in the opposite phase.
        Callable<Void> path2 = () -> {
            start.await();
            for (int i = 0; i < iterations; i++) {
                moveAllUnderLock(b, a);
                moveAllUnderLock(a, b);
            }
            return null;
        };

        var f1 = pool.submit(path1);
        var f2 = pool.submit(path2);
        start.countDown();

        // Completion within the timeout proves no lock-ordering deadlock (UUID-ordered acquisition).
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        var inA = new HashSet<>(a.getEntities());
        var inB = new HashSet<>(b.getEntities());
        var union = new HashSet<String>();
        union.addAll(inA);
        union.addAll(inB);

        assertThat(union)
            .as("entities must be exactly conserved across concurrent multi-bubble writers (no loss/creation)")
            .isEqualTo(originalIds);
        assertThat(java.util.Collections.disjoint(inA, inB))
            .as("no entity may reside in both bubbles (no duplication)")
            .isTrue();
    }

    /**
     * Move every entity from {@code src} to {@code dst} atomically under the shared mutation-lock
     * protocol — the same add-to-target / remove-from-source shape the production migration/merge paths
     * use, lifted out so the test exercises a writer path distinct from {@code transferEntities}.
     */
    private static void moveAllUnderLock(EnhancedBubble src, EnhancedBubble dst) {
        try (var ignored = MutationLocks.lock(src, dst)) {
            var records = src.getAllEntityRecords();
            for (var record : records) {
                dst.addEntity(record.id(), record.position(), record.content());
            }
            for (var record : records) {
                src.removeEntity(record.id());
            }
        }
    }
}
