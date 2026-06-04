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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.67: OptimisticMigratorImpl metrics must use AtomicLong so
 * increments from concurrent public-method calls are not lost.
 *
 * @author hal.hildebrand
 */
class OptimisticMigratorMetricsConcurrencyTest {

    @Test
    void initiateMigrationMetricCountsAllIncrementsUnderConcurrency() throws InterruptedException {
        var migrator = new OptimisticMigratorImpl();
        int threads = 16;
        int perThread = 5000;
        var target = UUID.randomUUID();

        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // Distinct entity ids so each call performs a real increment.
                        migrator.initiateOptimisticMigration(UUID.randomUUID(), target);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "workers finished");
        pool.shutdownNow();

        // With plain long fields, concurrent read-add-write triples lose increments and this count
        // would be strictly less than the expected total. AtomicLong guarantees exactness.
        assertEquals((long) threads * perThread, migrator.getTotalMigrationsInitiated(),
                     "no increments lost under concurrent access");
    }
}
