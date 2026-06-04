/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression test for Luciferase-0frcy.26: MigrationLog.cleanupBefore() must not lose a
 * just-recorded idempotency token to a concurrent recordMigration(). Pre-fix, cleanup removed the
 * entityId from entityTokens non-atomically with a concurrent recordMigration's token add, so a
 * retransmitted duplicate arriving in that window would pass the duplicate check and be processed
 * twice.
 * <p>
 * The fix serializes record/cleanup on a per-entity lock and re-checks emptiness under the lock.
 * This test asserts the idempotency invariant: a token successfully recorded (recordMigration
 * returned true) is ALWAYS subsequently seen as a duplicate — it is never silently dropped by a
 * racing cleanup.
 */
class MigrationLogCleanupRaceTest {

    @Test
    void concurrentCleanupNeverDropsAJustRecordedToken() {
        assertTimeoutPreemptively(Duration.ofSeconds(25), () -> {
            int trials = 30;
            int perTrial = 400;

            for (int trial = 0; trial < trials; trial++) {
                var log = new MigrationLog();
                var entityId = new StringEntityID("entity-race");
                var source = UUID.randomUUID();
                var target = UUID.randomUUID();
                var lostTokens = new AtomicInteger(0);

                var pool = Executors.newFixedThreadPool(2);
                try {
                    var start = new CountDownLatch(1);

                    var recorder = pool.submit(() -> {
                        await(start);
                        for (int i = 0; i < perTrial; i++) {
                            var token = UUID.randomUUID();
                            // Record at increasing buckets so cleanup is meaningful.
                            boolean recorded = log.recordMigration(entityId, token, source, target, i + 1);
                            if (recorded) {
                                // Invariant: a recorded token must remain known as a duplicate.
                                if (!log.isDuplicate(entityId, token)) {
                                    lostTokens.incrementAndGet();
                                }
                            }
                        }
                    });

                    var cleaner = pool.submit(() -> {
                        await(start);
                        for (int i = 0; i < perTrial; i++) {
                            // Aggressively clean everything before the moving frontier.
                            log.cleanupBefore(i + 1);
                        }
                    });

                    start.countDown();
                    recorder.get(20, TimeUnit.SECONDS);
                    cleaner.get(20, TimeUnit.SECONDS);
                } finally {
                    pool.shutdownNow();
                }

                assertEquals(0, lostTokens.get(),
                             "A just-recorded idempotency token was lost to concurrent cleanup (trial " + trial + ")");
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
