/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.distributed.migration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-0frcy.110: percentile snapshot must be consistent with histogram contents. The fix
 * tracks a `filled` counter under the histogram lock instead of using the atomic `count` (which is
 * incremented before the histogram write), so getPercentile never reads uninitialized zero slots.
 */
class MigrationMetricsLatencyStatsTest {

    /**
     * With fewer than HISTOGRAM_SIZE samples, all of value 50, every percentile must be 50 — never
     * 0 (which would mean an uninitialized slot was pulled into the sort).
     */
    @Test
    void percentilesNeverIncludeUninitializedSlots() {
        var stats = new MigrationMetrics.LatencyStats();
        for (int i = 0; i < 100; i++) {
            stats.record(50L);
        }
        assertEquals(50L, stats.getP50Latency());
        assertEquals(50L, stats.getP95Latency());
        assertEquals(50L, stats.getP99Latency(), "P99 over 100 identical samples must be the sample, not a zero slot");
    }

    /**
     * Concurrent record() vs getPercentile() must never expose a torn snapshot: with all positive
     * samples, percentiles must remain strictly positive (a 0 would indicate count outran the
     * histogram write and an uninitialized slot was sorted in).
     */
    @Test
    void concurrentRecordAndPercentileIsConsistent() throws InterruptedException {
        var stats = new MigrationMetrics.LatencyStats();
        var start = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();

        var writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 50_000 && error.get() == null; i++) {
                    stats.record(10L + (i % 90)); // always >= 10, never 0
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });

        var reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 50_000 && error.get() == null; i++) {
                    var snap = stats.snapshot();
                    if (snap.p99() < 0 || (snap.count() > 0 && snap.p99() == 0)) {
                        throw new AssertionError("inconsistent percentile snapshot: p99=" + snap.p99()
                                                 + " count=" + snap.count());
                    }
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });

        writer.start();
        reader.start();
        start.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(30));
        reader.join(TimeUnit.SECONDS.toMillis(30));

        if (error.get() != null) {
            fail("concurrent record/percentile inconsistency: " + error.get(), error.get());
        }
    }
}
