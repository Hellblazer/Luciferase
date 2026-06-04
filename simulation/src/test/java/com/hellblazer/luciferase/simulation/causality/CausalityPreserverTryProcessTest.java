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

import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link CausalityPreserver#tryProcess} — the atomic
 * check-and-mark that closes the canProcess/markProcessed TOCTOU
 * (Luciferase-0frcy.86).
 *
 * @author hal.hildebrand
 */
class CausalityPreserverTryProcessTest {

    private EntityUpdateEvent createEvent(long lamportClock, String entityId) {
        return new EntityUpdateEvent(new StringEntityID(entityId), new Point3f(0, 0, 0), new Point3f(1, 0, 0), 0L,
                                     lamportClock);
    }

    @Test
    void tryProcessAdvancesAndRejectsOutOfOrder() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();

        assertTrue(preserver.tryProcess(createEvent(1L, "e1"), source), "first event accepted");
        assertTrue(preserver.tryProcess(createEvent(2L, "e2"), source), "monotonic event accepted");
        assertFalse(preserver.tryProcess(createEvent(1L, "e1"), source), "lower clock rejected after advance");
        assertEquals(2L, preserver.getProcessedClock(source), "highest clock retained after rejection");
        assertEquals(1L, preserver.getTotalRejected(), "rejection counted");
    }

    @Test
    void tryProcessCountsIdempotentReplay() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();

        assertTrue(preserver.tryProcess(createEvent(5L, "e"), source));
        assertTrue(preserver.tryProcess(createEvent(5L, "e"), source), "idempotent replay accepted");
        assertEquals(1L, preserver.getTotalIdempotent(), "replay counted as idempotent");
    }

    /**
     * The core race: many threads concurrently submit the SAME clock value for the
     * same source. With the old canProcess/markProcessed split, multiple threads
     * could observe canProcess==true and each proceed. With the atomic tryProcess,
     * for a strictly-increasing-then-duplicate workload the count of "new"
     * acceptances (non-idempotent) must equal the number of distinct clocks, and
     * the highest processed clock must equal the max submitted — never exceeded by
     * any accepted lower clock.
     */
    @Test
    void tryProcessIsAtomicUnderConcurrency() throws InterruptedException {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        int threads = 16;
        int distinctClocks = 200;

        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        // Each thread races to submit the full sequence of clocks 0..distinctClocks-1.
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (long c = 0; c < distinctClocks; c++) {
                        preserver.tryProcess(createEvent(c, "e" + c), source);
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

        // Exactly distinctClocks first-time acceptances regardless of thread interleaving:
        // each clock value can win "new" processing exactly once.
        assertEquals(distinctClocks, preserver.getTotalProcessed(),
                     "each distinct clock accepted as new exactly once");
        assertEquals(distinctClocks - 1, preserver.getProcessedClock(source),
                     "highest processed clock equals max submitted");
    }
}
