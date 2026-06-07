/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-fwfpm: {@code CollisionEngine.findCollisions(id)} read
 * {@code core.entityManager().getEntityLocations(id)} DIRECTLY (bypassing the read-locked public accessor that
 * Luciferase-1q51y added) and only acquired the read lock AFTER. {@code updateEntity} clears then re-inserts the
 * location set under the write lock, so the unlocked read could observe the transiently EMPTY set mid-move and
 * short-circuit to {@link java.util.Collections#emptyList()} — a false "no collisions" result for an entity that
 * is in fact colliding. The fix moves the {@code getEntityLocations} read inside the read lock (mirroring
 * {@code findCollisionsFineGrained}), so the query only ever observes a settled location set.
 * <p>
 * The probe entity is permanently co-located with a partner at whichever of two positions it currently occupies,
 * so a correctly-locked {@code findCollisions} ALWAYS returns at least one collision. An empty result can therefore
 * only come from the transient-empty unlocked read — exactly the regression under test.
 * <p>
 * This is a probabilistic stress probe, not a deterministic handshake: it relies on the reader hitting the
 * write-lock clear→re-insert window across {@code moves} iterations rather than forcing it with a latch (which
 * would require exposing {@code EntityLifecycleManager} internals). 4000 iterations against two OS-scheduled
 * threads make a missed window negligible in practice; it failed reliably on the pre-fix code. The bug is real,
 * so this MUST run in CI — do NOT add a {@code @DisabledIfEnvironmentVariable(CI)} guard.
 *
 * @author hal.hildebrand
 */
class CollisionEngineFindCollisionsLockTest {

    @Test
    void findCollisionsNeverFalseEmptyDuringConcurrentMove() throws InterruptedException {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());

        var a = new Point3f(100, 100, 100);
        var b = new Point3f(5000, 5000, 5000);

        // Stationary partners co-located at each position the probe visits, so the probe always has a collision.
        octree.insert(new LongEntityID(10), a, (byte) 10, "partner-a");
        octree.insert(new LongEntityID(11), b, (byte) 10, "partner-b");

        var probe = new LongEntityID(1);
        octree.insert(probe, a, (byte) 10, "probe");

        final int moves = 4000;
        var sawFalseEmpty = new AtomicBoolean(false);
        var reads = new AtomicInteger(0);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);

        var mover = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < moves; i++) {
                    octree.updateEntity(probe, (i % 2 == 0) ? b : a, (byte) 10);
                }
            } catch (InterruptedException ignored) {
            } finally {
                done.countDown();
            }
        });

        var reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < moves; i++) {
                    if (octree.findCollisions(probe).isEmpty()) {
                        sawFalseEmpty.set(true);
                    }
                    reads.incrementAndGet();
                }
            } catch (InterruptedException ignored) {
            } finally {
                done.countDown();
            }
        });

        mover.start();
        reader.start();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads must finish");

        assertEquals(moves, reads.get(), "reader completed all reads");
        assertFalse(sawFalseEmpty.get(),
                    "findCollisions must never return a false-empty result for a permanently-colliding entity "
                    + "during a concurrent move (Luciferase-fwfpm)");
    }
}
