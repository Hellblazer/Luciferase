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

package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.simulation.von.Bubble;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concurrency regression for {@link BubbleMigrator#migrate} (Luciferase-0frcy.38).
 *
 * <p>Pre-fix the duplicate guard was a non-atomic containsKey()/size()/put() sequence: two threads
 * racing on the same bubbleId could both observe containsKey=false and both start a migration, the
 * second put() silently overwriting the first. The fix reserves the in-flight slot atomically via
 * putIfAbsent. This test launches many threads against the same bubbleId simultaneously and asserts
 * exactly one wins the reservation; the rest are rejected with "Already migrating".
 *
 * @author hal.hildebrand
 */
class BubbleMigratorRaceTest {

    @Test
    void concurrentMigrateOnSameBubbleAllowsExactlyOne() throws Exception {
        var tumbler = new SpatialTumbler((byte) 5, 16.0);
        var sourceServerId = UUID.randomUUID();
        var targetServerId = UUID.randomUUID();
        tumbler.registerServer(sourceServerId);
        tumbler.registerServer(targetServerId);

        var migrator = new BubbleMigrator(tumbler, Duration.ofSeconds(5), Duration.ofMillis(0), 50);

        var bubbleId = UUID.randomUUID();
        var bubble = mock(Bubble.class);
        when(bubble.id()).thenReturn(bubbleId);

        // A factory that blocks until released — keeps the winning migration in-flight while the
        // racing threads make their reservation attempt, maximising the race window.
        var releaseFactory = new CountDownLatch(1);
        var factoryEntered = new AtomicInteger(0);
        migrator.setBubbleTransferFactory((server, src) -> {
            factoryEntered.incrementAndGet();
            try {
                releaseFactory.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null; // null target -> executeMigration fails gracefully, but only after we win
        });

        int threads = 16;
        var barrier = new CyclicBarrier(threads);
        var startGate = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        CompletableFuture<BubbleMigrator.MigrationResult>[] futures = new CompletableFuture[threads];
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS); // align all threads
                        futures[idx] = migrator.migrate(bubble, sourceServerId, targetServerId);
                    } catch (Exception e) {
                        futures[idx] = CompletableFuture.completedFuture(
                            new BubbleMigrator.MigrationResult(bubbleId, targetServerId, false,
                                                               "thread-error", 0));
                    } finally {
                        startGate.countDown();
                    }
                });
            }

            // Wait until all threads have called migrate().
            for (int i = 0; i < threads; i++) {
                startGate.await(5, TimeUnit.SECONDS);
            }
            // Give the reservations a moment to settle, then release the blocked factory.
            Thread.sleep(100);

            // Exactly one reservation must have won (entered the in-flight slot / factory path).
            assertThat(migrator.inFlightCount())
                .as("Exactly one migration may be in-flight for a single bubbleId under a race")
                .isEqualTo(1);

            releaseFactory.countDown();

            // Count rejections: every losing thread must get a synchronous "Already migrating".
            int alreadyMigrating = 0;
            int proceeded = 0;
            for (var f : futures) {
                var result = f.get(5, TimeUnit.SECONDS);
                if ("Already migrating".equals(result.message())) {
                    alreadyMigrating++;
                } else {
                    proceeded++;
                }
            }

            assertThat(proceeded)
                .as("Exactly one thread may proceed past the duplicate guard")
                .isEqualTo(1);
            assertThat(alreadyMigrating)
                .as("All other threads must be rejected with 'Already migrating'")
                .isEqualTo(threads - 1);
            assertThat(factoryEntered.get())
                .as("Only the single winning migration may enter the transfer factory")
                .isEqualTo(1);
        } finally {
            releaseFactory.countDown();
            pool.shutdownNow();
        }
    }
}
