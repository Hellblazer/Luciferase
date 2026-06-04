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

import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Invariant tests for {@link EntityMigrationStateMachine} covering:
 *
 * <ul>
 *   <li>Luciferase-umqlt: transition() must be atomic read-validate-write so that at most one
 *       concurrent thread can win a transition out of a given currentState (single-owner invariant).</li>
 *   <li>Luciferase-0vb40: getStateCounts() must account for ROLLBACK_OWNED entities so the
 *       StateCounts record invariant (total == sum) never throws during/after rollback.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class EntityMigrationStateMachineInvariantTest {

    private MockFirefliesView<UUID> view;
    private EntityMigrationStateMachine fsm;

    @BeforeEach
    void setup() {
        view = new MockFirefliesView<>();
        fsm = new EntityMigrationStateMachine(new FirefliesViewMonitor(view, 3));
        view.addMember(UUID.randomUUID());
    }

    // ---- Luciferase-umqlt: single-owner invariant under concurrency ----

    /**
     * Two threads race on the SAME entity in OWNED state, both attempting OWNED -> MIGRATING_OUT.
     * A CyclicBarrier releases both threads simultaneously to maximize the race window. With an
     * atomic transition(), exactly one thread must win; the loser must observe MIGRATING_OUT as
     * an invalid source for OWNED -> MIGRATING_OUT and fail.
     *
     * Repeated to make a non-atomic regression flake reliably.
     */
    @RepeatedTest(50)
    void exactlyOneTransitionWinsUnderConcurrentRace() throws Exception {
        var entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);

        var barrier = new CyclicBarrier(2);
        var successCount = new AtomicInteger(0);

        Runnable attempt = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
                if (result.success) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        var t1 = new Thread(attempt, "race-1");
        var t2 = new Thread(attempt, "race-2");
        t1.start();
        t2.start();
        t1.join(5_000);
        t2.join(5_000);

        assertEquals(1, successCount.get(),
            "Exactly one thread may win OWNED -> MIGRATING_OUT (single-owner invariant)");
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId),
            "Final state must be the winning transition's target");
    }

    /**
     * Stress: many threads racing on the same entity. Only one may ever succeed transitioning
     * out of OWNED. transition() never corrupts state (final state always valid, never null).
     */
    @Test
    void manyThreadsRaceSingleOwnerHolds() throws Exception {
        int threadCount = 32;
        var entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);

        var barrier = new CyclicBarrier(threadCount);
        var successCount = new AtomicInteger(0);
        var threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
                    if (result.success) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "stress-" + i);
            threads[i].start();
        }
        for (var t : threads) {
            t.join(5_000);
        }

        assertEquals(1, successCount.get(),
            "Only one thread may win OWNED -> MIGRATING_OUT regardless of contention");
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId));
        // Exactly one successful transition recorded.
        assertEquals(1, fsm.getTotalTransitions());
    }

    @Test
    void invalidTransitionLeavesStateUnchanged() {
        var entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);

        // OWNED -> DEPARTED is invalid.
        var result = fsm.transition(entityId, EntityMigrationState.DEPARTED);
        assertFalse(result.success, "Invalid transition must fail");
        assertEquals(EntityMigrationState.OWNED, fsm.getState(entityId),
            "Invalid transition must not mutate the stored state");
    }

    @Test
    void notFoundTransitionDoesNotCreateEntity() {
        var entityId = UUID.randomUUID();
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertFalse(result.success);
        assertNull(fsm.getState(entityId), "Unknown entity must remain absent after a failed transition");
        assertEquals(0, fsm.getEntityCount());
    }

    // ---- Luciferase-0vb40: getStateCounts handles ROLLBACK_OWNED ----

    @Test
    void getStateCountsHandlesRollbackOwnedWithoutThrowing() {
        var entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);
        assertTrue(fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT).success);
        assertTrue(fsm.transition(entityId, EntityMigrationState.ROLLBACK_OWNED).success);
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId));

        // Must not throw IllegalArgumentException (regression: total counted ROLLBACK_OWNED but no bucket did).
        var counts = assertDoesNotThrow(fsm::getStateCounts);
        assertEquals(1, counts.rollbackOwned(), "ROLLBACK_OWNED entity must be counted");
        assertEquals(1, counts.total());
        assertEquals(0, counts.owned());
    }

    @Test
    void getStateCountsAfterViewChangeRollback() {
        // onViewChange routes MIGRATING_OUT -> ROLLBACK_OWNED; getStateCounts must survive it.
        var entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);
        assertTrue(fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT).success);

        fsm.onViewChange();
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId));

        var counts = assertDoesNotThrow(fsm::getStateCounts);
        assertEquals(1, counts.rollbackOwned());
        assertEquals(1, counts.total());
    }

    @Test
    void getStateCountsTotalEqualsSumAcrossAllStates() {
        // Mix of states including ROLLBACK_OWNED; invariant total == sum must hold.
        // Uses only transitions that do not require view stability so the test is deterministic
        // regardless of the mock view's member count.
        var owned = UUID.randomUUID();
        var rollback = UUID.randomUUID();
        var migrating = UUID.randomUUID();
        fsm.initializeOwned(owned);
        fsm.initializeOwned(rollback);
        fsm.initializeOwned(migrating);

        // rollback: OWNED -> MIGRATING_OUT -> ROLLBACK_OWNED (neither requires view stability)
        assertTrue(fsm.transition(rollback, EntityMigrationState.MIGRATING_OUT).success);
        assertTrue(fsm.transition(rollback, EntityMigrationState.ROLLBACK_OWNED).success);

        // migrating: OWNED -> MIGRATING_OUT (in-transition state)
        assertTrue(fsm.transition(migrating, EntityMigrationState.MIGRATING_OUT).success);

        var counts = assertDoesNotThrow(fsm::getStateCounts);
        assertEquals(3, counts.total());
        assertEquals(counts.total(),
            counts.owned() + counts.migratingOut() + counts.departed()
                + counts.migratingIn() + counts.ghost() + counts.rollbackOwned());
        assertEquals(1, counts.owned());
        assertEquals(1, counts.rollbackOwned());
        assertEquals(1, counts.migratingOut());
    }
}
