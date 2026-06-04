/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.causality;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.63: onRemoteEvent() must advance the source vector
 * entry and the local clock atomically, so this bubble's own vector entry never lags the
 * local clock.
 *
 * @author hal.hildebrand
 */
class LamportClockAtomicityTest {

    @Test
    void ownVectorEntryNeverLagsLocalClockUnderConcurrency() throws InterruptedException {
        var bubbleId = UUID.randomUUID();
        var gen = new LamportClockGenerator(bubbleId);

        int threads = 16;
        int eventsPerThread = 500;
        var sources = new UUID[threads];
        for (int i = 0; i < threads; i++) {
            sources[i] = UUID.randomUUID();
        }

        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        // Sample the (localClock, ownVectorEntry) pair after each update and assert the
        // invariant ownVectorEntry >= ... is never violated by checking final consistency
        // plus a live spot-check.
        var violation = new boolean[1];

        for (int t = 0; t < threads; t++) {
            final UUID src = sources[t];
            pool.submit(() -> {
                try {
                    start.await();
                    for (int e = 1; e <= eventsPerThread; e++) {
                        gen.onRemoteEvent(e, src);
                        // Live check: own vector entry must be >= no constraint vs other sources,
                        // but it must track local clock advances. Read own entry and local clock;
                        // own entry should never be strictly less than a *previously observed*
                        // local clock. Conservative check: own entry must be >= 0 and local clock
                        // monotonic. The strong final assertion below catches the torn update.
                        if (gen.getVectorTimestamp(bubbleId) < 0) {
                            violation[0] = true;
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers finished");
        pool.shutdownNow();

        assertFalse(violation[0], "own vector entry observed in inconsistent state");

        // Strong post-condition: after all updates quiesce, this bubble's own vector entry
        // must equal the local clock. The torn (non-atomic) update path leaves the own entry
        // strictly less than the local clock because a concurrent onRemoteEvent advanced the
        // local clock without (atomically) advancing the own vector entry to match.
        long localClock = gen.getLamportClock();
        long ownEntry = gen.getVectorTimestamp(bubbleId);
        assertEquals(localClock, ownEntry,
                     "own vector entry must equal local clock after atomic updates");
    }
}
