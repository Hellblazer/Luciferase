/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Luciferase-7wzml.91: {@link CollisionSystem#processAllCollisions} must use injected {@link
 * com.hellblazer.luciferase.common.time.Clock} for elapsed-time measurements rather than {@link System#nanoTime()}
 * directly.
 *
 * @author hal.hildebrand
 */
class CollisionSystemClockTest {

    @Test
    void processAllCollisionsUsesInjectedClockForTotalProcessingTime() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        // Insert two overlapping entities so the collision pair is found
        octree.insert(new LongEntityID(1), new Point3f(0, 0, 0), (byte) 10, "a");
        octree.insert(new LongEntityID(2), new Point3f(0, 0, 0), (byte) 10, "b");

        var system = new CollisionSystem<>(octree);

        // A two-shot anonymous Clock is required: TestClock.nanoTime() returns a stable snapshot
        // (absoluteNanos.get()), so two consecutive calls would return the same value.
        // The stub returns 1000 on the first call (start) and 5000 on the second (end) → elapsed=4000.
        var twoShotClock = new com.hellblazer.luciferase.common.time.Clock() {
            private int callCount = 0;

            @Override
            public long currentTimeMillis() {
                return 0L;
            }

            @Override
            public long nanoTime() {
                callCount++;
                return callCount == 1 ? 1_000L : 5_000L;
            }
        };
        system.setClock(twoShotClock);

        system.processAllCollisions();

        assertEquals(4_000L, system.getLastStats().totalProcessingTime(),
                     "totalProcessingTime must equal endTime - startTime as measured by the injected clock");
    }
}
