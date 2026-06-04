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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-d3pz6: bubbleMap and bubbleCoordinates must be ConcurrentHashMap. A concurrent
 * registerBubble (structural mutation) racing against getTargetBubble/getClosestBubble iteration
 * over a plain HashMap throws ConcurrentModificationException or corrupts the map. With
 * ConcurrentHashMap the test runs clean.
 */
class MigrationOracleImplConcurrencyTest {

    @Test
    void concurrentRegisterAndLookupRunsClean() throws InterruptedException {
        // Large grid so registerBubble keeps adding distinct coordinates (structural mutation).
        var oracle = new MigrationOracleImpl(16, 16, 16);

        // Seed a baseline so lookups always have data (isolates the concurrency concern — a
        // momentarily-empty map is a separate edge case, not the HashMap-corruption defect under test).
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                oracle.registerBubble(new CubeBubbleCoordinate(x, y, 0), UUID.randomUUID());
            }
        }

        var iterations = 4000;
        var startLatch = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();

        var writer = new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations && error.get() == null; i++) {
                    var x = i % 16;
                    var y = (i / 16) % 16;
                    var z = (i / 256) % 16;
                    oracle.registerBubble(new CubeBubbleCoordinate(x, y, z), UUID.randomUUID());
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        }, "register");

        var reader = new Thread(() -> {
            try {
                startLatch.await();
                var pos = new Point3f(120.0f, 130.0f, 140.0f); // outside small registered region -> getClosestBubble path
                for (int i = 0; i < iterations && error.get() == null; i++) {
                    oracle.getTargetBubble(pos);                 // iterates bubbleMap/bubbleCoordinates
                    oracle.checkMigration(pos, UUID.randomUUID());
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        }, "lookup");

        writer.start();
        reader.start();
        startLatch.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(30));
        reader.join(TimeUnit.SECONDS.toMillis(30));

        assertFalse(writer.isAlive(), "writer thread should finish (no infinite loop from corrupted HashMap)");
        assertFalse(reader.isAlive(), "reader thread should finish");
        if (error.get() != null) {
            fail("concurrent register/lookup must not throw (was " + error.get() + ")", error.get());
        }
    }
}
