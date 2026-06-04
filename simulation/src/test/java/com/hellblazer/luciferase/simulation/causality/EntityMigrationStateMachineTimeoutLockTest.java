/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.causality;

import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lock-inversion regression for {@link EntityMigrationStateMachine#processTimeouts}
 * (Luciferase-0frcy.17).
 *
 * <p>Pre-fix, processTimeouts() held the StampedLock write lock across transition() calls, which
 * invoke registered listeners synchronously. A blocking listener would pin the lock indefinitely.
 * The fix collects timed-out IDs under the lock, releases it, then drives transitions OUTSIDE the
 * lock. This test registers a listener that blocks during its callback, runs processTimeouts() on
 * one thread, and verifies a concurrent processTimeouts() on a second thread is NOT blocked by the
 * still-running (blocked) listener — i.e. the timeout lock is not held across listener callbacks.
 *
 * @author hal.hildebrand
 */
class EntityMigrationStateMachineTimeoutLockTest {

    private static EntityMigrationStateMachine machineWithTimedOutEntity(MockFirefliesView<UUID> view,
                                                                         UUID entityId) {
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                              .migrationTimeoutMs(1L)
                                                              .enableTimeoutRollback(true)
                                                              .build();
        var fsm = new EntityMigrationStateMachine(new FirefliesViewMonitor(view, 3), config);
        fsm.initializeOwned(entityId);
        view.addMember(UUID.randomUUID());
        // Drive into MIGRATING_OUT so a migration context (with a 1ms timeout) is created.
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        return fsm;
    }

    @Test
    void timeoutLockIsNotHeldWhileListenerCallbackRuns() throws Exception {
        var view = new MockFirefliesView<UUID>();
        var entityA = UUID.randomUUID();
        var fsm = machineWithTimedOutEntity(view, entityA);

        var listenerEntered = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);

        // A listener that blocks during its callback. If processTimeouts() held the write lock
        // across this callback, no other processTimeouts() could make progress until released.
        fsm.addListener(new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                                EntityMigrationState toState,
                                                EntityMigrationStateMachine.TransitionResult result) {
                listenerEntered.countDown();
                try {
                    releaseListener.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
            }
        });

        // Far-future time forces the (1ms) timeout to fire.
        long farFuture = System.currentTimeMillis() + 1_000_000L;

        // Thread 1: drives the timeout -> transition -> blocked listener.
        var blockingRun = CompletableFuture.runAsync(() -> fsm.processTimeouts(farFuture));

        assertTrue(listenerEntered.await(5, TimeUnit.SECONDS),
                   "The blocking listener callback must have been entered");

        // Thread 2: while the listener is still blocked, a concurrent processTimeouts() must
        // complete promptly. With the lock held across the callback (the bug) this would block
        // until releaseListener fires and time out here.
        var concurrentRun = CompletableFuture.supplyAsync(() -> fsm.processTimeouts(farFuture));
        assertDoesNotThrow(() -> concurrentRun.get(2, TimeUnit.SECONDS),
                           "A concurrent processTimeouts() must NOT be blocked by the lock while a "
                           + "listener callback is running — the timeout lock must be released "
                           + "before transitions/listeners run");

        releaseListener.countDown();
        blockingRun.get(5, TimeUnit.SECONDS);
    }
}
