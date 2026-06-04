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

import javax.vecmath.Point3f;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.66: getEntitiesCrossingBoundaries() must never expose a
 * transiently-empty result to concurrent callers. The fix computes into a local set and only
 * publishes the side-effect cache after the full set is built.
 *
 * @author hal.hildebrand
 */
class MigrationOracleCrossingConcurrencyTest {

    @Test
    void concurrentCallersNeverSeeEmptyCrossingSet() throws InterruptedException {
        var oracle = new MigrationOracleImpl();

        // Entity parked exactly on a grid boundary (x=0 is a multiple of the cube size, so it is
        // always reported as crossing). Every call to getEntitiesCrossingBoundaries() MUST
        // contain this entity — there is never a legitimate empty result.
        oracle.updateEntityPosition("boundary-entity", new Point3f(0f, 50f, 50f));

        int threads = 12;
        int callsPerThread = 2000;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var emptyObservations = new AtomicInteger(0);
        var missingEntityObservations = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        var crossing = oracle.getEntitiesCrossingBoundaries();
                        if (crossing.isEmpty()) {
                            emptyObservations.incrementAndGet();
                        } else if (!crossing.contains("boundary-entity")) {
                            missingEntityObservations.incrementAndGet();
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

        assertEquals(0, emptyObservations.get(),
                     "no caller may ever observe an empty crossing set while a boundary entity exists");
        assertEquals(0, missingEntityObservations.get(),
                     "the boundary entity must be present in every non-empty result");
    }
}
