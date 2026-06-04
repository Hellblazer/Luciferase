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

package com.hellblazer.luciferase.simulation.metrics;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-0frcy.114: getSnapshot() is a best-effort (not strictly consistent) snapshot — it
 * performs three weakly-consistent reads over two ConcurrentHashMaps. The contract: it must never
 * throw under concurrent mutation, and must always return a structurally valid snapshot
 * (non-negative aggregates). The Javadoc now documents this as best-effort rather than consistent.
 */
class ObservabilityMetricsSnapshotConsistencyTest {

    @Test
    void getSnapshotIsSafeUnderConcurrentMutation() throws InterruptedException {
        var metrics = new ObservabilityMetrics();
        var start = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();

        var writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 20_000 && error.get() == null; i++) {
                    var id = UUID.randomUUID();
                    metrics.recordAnimatorFrame(id, 5_000_000L, 16_000_000L);
                    metrics.recordNeighborCount(id, i % 7);
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        });

        var reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 20_000 && error.get() == null; i++) {
                    var snap = metrics.getSnapshot();
                    assertNotNull(snap);
                    assertTrue(snap.activeBubbleCount() >= 0, "activeBubbleCount must be non-negative");
                    assertTrue(snap.totalVonNeighbors() >= 0, "totalVonNeighbors must be non-negative");
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
            fail("getSnapshot must be safe (no exception) under concurrent mutation: " + error.get(), error.get());
        }
    }
}
