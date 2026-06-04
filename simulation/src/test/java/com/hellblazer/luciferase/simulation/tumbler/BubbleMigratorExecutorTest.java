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

package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.simulation.von.Bubble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for Luciferase-0frcy.116: BubbleMigrator must run its blocking migration task on
 * a dedicated executor, never on ForkJoinPool.commonPool().
 *
 * @author hal.hildebrand
 */
class BubbleMigratorExecutorTest {

    private SpatialTumbler tumbler;
    private BubbleMigrator migrator;

    @BeforeEach
    void setUp() {
        tumbler = new SpatialTumbler((byte) 5, 16.0);
        migrator = new BubbleMigrator(tumbler, Duration.ofSeconds(2), Duration.ofMillis(100), 5);
    }

    @AfterEach
    void tearDown() {
        migrator.shutdown();
    }

    @Test
    void migrationRunsOnDedicatedExecutorNotCommonPool() throws Exception {
        var sourceBubble = mock(Bubble.class);
        when(sourceBubble.id()).thenReturn(UUID.randomUUID());

        var runningThreadName = new AtomicReference<String>();
        // The transfer factory runs INSIDE executeMigration on the migration executor. Capture the
        // executing thread, then return null to fail the migration gracefully (no close()/sleep).
        migrator.setBubbleTransferFactory((targetServerId, src) -> {
            runningThreadName.set(Thread.currentThread().getName());
            return null;
        });

        var result = migrator.migrate(sourceBubble, UUID.randomUUID(), UUID.randomUUID())
                             .get(5, TimeUnit.SECONDS);

        assertFalse(result.success(), "migration fails gracefully (factory returned null)");
        var name = runningThreadName.get();
        assertNotNull(name, "transfer factory executed");
        // Pre-fix the task ran on the common pool, e.g. "ForkJoinPool.commonPool-worker-1".
        assertFalse(name.contains("commonPool"),
                    "migration must NOT run on ForkJoinPool.commonPool (ran on: " + name + ")");
        assertTrue(name.startsWith("bubble-migrator"),
                   "migration must run on the dedicated bubble-migrator pool (ran on: " + name + ")");
    }
}
