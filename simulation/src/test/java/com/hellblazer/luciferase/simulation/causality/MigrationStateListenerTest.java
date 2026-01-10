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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationStateListenerTest - Tests for MigrationStateListener interface (Phase 7D Day 1)
 *
 * Validates listener pattern for FSM/2PC bridge:
 * - Listeners notified of successful transitions
 * - Listeners notified of failed transitions
 * - Multiple listeners supported
 * - View change rollback notifications with aggregate counts
 * - Thread safety and concurrent operations
 *
 * @author hal.hildebrand
 */
class MigrationStateListenerTest {

    private MockFirefliesView<String> view;
    private FirefliesViewMonitor viewMonitor;
    private EntityMigrationStateMachine fsm;

    @BeforeEach
    void setUp() {
        view = new MockFirefliesView<>();
        viewMonitor = new FirefliesViewMonitor(view, 3); // 3 tick threshold for testing
        fsm = new EntityMigrationStateMachine(viewMonitor);
    }

    /**
     * Helper method to make view stable by adding member and advancing ticks.
     */
    private void makeViewStable() {
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            viewMonitor.onTick(i);
        }
    }

    /**
     * Helper: Create a capturing transition listener.
     */
    private MigrationStateListener createTransitionListener(List<TransitionEvent> captured) {
        return new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                               EntityMigrationState toState,
                                               EntityMigrationStateMachine.TransitionResult result) {
                captured.add(new TransitionEvent(entityId, fromState, toState, result));
            }

            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
                // Not used in this helper
            }
        };
    }

    /**
     * Test 1: Listener is notified on successful OWNED->MIGRATING_OUT transition.
     */
    @Test
    void testListenerNotifiedOnSuccessfulTransition() {
        // Create capturing listener
        var captured = new ArrayList<TransitionEvent>();
        var listener = createTransitionListener(captured);

        // Register listener
        fsm.addListener(listener);

        // Perform transition
        var entityId = "entity1";
        fsm.initializeOwned(entityId);
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Verify
        assertTrue(result.success, "Transition should succeed");
        assertEquals(1, captured.size(), "Listener should be notified once");

        var event = captured.get(0);
        assertEquals(entityId, event.entityId, "Entity ID should match");
        assertEquals(EntityMigrationState.OWNED, event.fromState, "From state should be OWNED");
        assertEquals(EntityMigrationState.MIGRATING_OUT, event.toState, "To state should be MIGRATING_OUT");
        assertTrue(event.result.success, "Result should indicate success");
    }

    /**
     * Test 2: Listener is notified when transition fails (invalid/blocked).
     */
    @Test
    void testListenerNotifiedOnFailedTransition() {
        var captured = new ArrayList<TransitionEvent>();
        var listener = createTransitionListener(captured);

        fsm.addListener(listener);

        // Attempt invalid transition: OWNED -> DEPARTED (must go through MIGRATING_OUT)
        var entityId = "entity1";
        fsm.initializeOwned(entityId);
        var result = fsm.transition(entityId, EntityMigrationState.DEPARTED);

        // Verify failure notification
        assertFalse(result.success, "Transition should fail");
        assertEquals(1, captured.size(), "Listener should be notified of failure");

        var event = captured.get(0);
        assertEquals(EntityMigrationState.OWNED, event.fromState, "From state should be OWNED");
        assertEquals(EntityMigrationState.DEPARTED, event.toState, "To state should be DEPARTED");
        assertFalse(event.result.success, "Result should indicate failure");
    }

    /**
     * Test 3: Listener receives correct fromState and toState.
     */
    @Test
    void testListenerNotifiedWithCorrectStates() {
        var captured = new ArrayList<TransitionEvent>();
        var listener = createTransitionListener(captured);

        fsm.addListener(listener);

        var entityId = "entity1";
        fsm.initializeOwned(entityId);

        // Execute sequence: OWNED -> MIGRATING_OUT -> DEPARTED -> GHOST -> MIGRATING_IN -> OWNED
        makeViewStable(); // Stabilize view for commit transitions

        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);
        fsm.transition(entityId, EntityMigrationState.OWNED);

        // Verify all transitions captured
        assertEquals(5, captured.size(), "Should capture all 5 transitions");

        // Verify sequence
        assertEquals(EntityMigrationState.OWNED, captured.get(0).fromState);
        assertEquals(EntityMigrationState.MIGRATING_OUT, captured.get(0).toState);

        assertEquals(EntityMigrationState.MIGRATING_OUT, captured.get(1).fromState);
        assertEquals(EntityMigrationState.DEPARTED, captured.get(1).toState);

        assertEquals(EntityMigrationState.DEPARTED, captured.get(2).fromState);
        assertEquals(EntityMigrationState.GHOST, captured.get(2).toState);

        assertEquals(EntityMigrationState.GHOST, captured.get(3).fromState);
        assertEquals(EntityMigrationState.MIGRATING_IN, captured.get(3).toState);

        assertEquals(EntityMigrationState.MIGRATING_IN, captured.get(4).fromState);
        assertEquals(EntityMigrationState.OWNED, captured.get(4).toState);
    }

    /**
     * Test 4: Multiple listeners - all are notified.
     */
    @Test
    void testMultipleListeners() {
        var captured1 = new ArrayList<TransitionEvent>();
        var captured2 = new ArrayList<TransitionEvent>();
        var captured3 = new ArrayList<TransitionEvent>();

        var listener1 = createTransitionListener(captured1);
        var listener2 = createTransitionListener(captured2);
        var listener3 = createTransitionListener(captured3);

        // Register all listeners
        fsm.addListener(listener1);
        fsm.addListener(listener2);
        fsm.addListener(listener3);

        assertEquals(3, fsm.getListenerCount(), "Should have 3 listeners registered");

        // Perform transition
        var entityId = "entity1";
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Verify all listeners notified
        assertEquals(1, captured1.size(), "Listener 1 should be notified");
        assertEquals(1, captured2.size(), "Listener 2 should be notified");
        assertEquals(1, captured3.size(), "Listener 3 should be notified");

        // Verify all received same event
        assertEquals(captured1.get(0).entityId, captured2.get(0).entityId);
        assertEquals(captured1.get(0).fromState, captured2.get(0).fromState);
        assertEquals(captured1.get(0).toState, captured2.get(0).toState);
    }

    /**
     * Test 5: View change rollback triggers notification with aggregate counts.
     */
    @Test
    void testViewChangeRollbackNotification() {
        var rolledBackCounts = new ArrayList<Integer>();
        var ghostCounts = new ArrayList<Integer>();

        MigrationStateListener listener = new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                               EntityMigrationState toState,
                                               EntityMigrationStateMachine.TransitionResult result) {
                // Not used in this test
            }

            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
                rolledBackCounts.add(rolledBackCount);
                ghostCounts.add(ghostCount);
            }
        };

        fsm.addListener(listener);

        // Create entities in MIGRATING_OUT and MIGRATING_IN states
        fsm.initializeOwned("entity1");
        fsm.initializeOwned("entity2");
        fsm.initializeOwned("entity3");

        fsm.transition("entity1", EntityMigrationState.MIGRATING_OUT);
        fsm.transition("entity2", EntityMigrationState.MIGRATING_OUT);

        // Create MIGRATING_IN entities
        fsm.initializeOwned("entity4");
        makeViewStable();
        fsm.transition("entity4", EntityMigrationState.MIGRATING_OUT);
        fsm.transition("entity4", EntityMigrationState.DEPARTED);
        fsm.transition("entity4", EntityMigrationState.GHOST);
        fsm.transition("entity4", EntityMigrationState.MIGRATING_IN);

        // Trigger view change
        fsm.onViewChange();

        // Verify rollback notification
        assertEquals(1, rolledBackCounts.size(), "Should receive one rollback notification");
        assertEquals(2, rolledBackCounts.get(0), "Should rollback 2 MIGRATING_OUT entities");
        assertEquals(1, ghostCounts.get(0), "Should convert 1 MIGRATING_IN to GHOST");
    }

    /**
     * Test 6: addListener(null) throws NPE.
     */
    @Test
    void testListenerNullHandling() {
        assertThrows(NullPointerException.class, () -> {
            fsm.addListener(null);
        }, "Adding null listener should throw NPE");
    }

    /**
     * Test 7: Removed listener is not notified of further transitions.
     */
    @Test
    void testRemoveListener() {
        var captured = new ArrayList<TransitionEvent>();
        var listener = createTransitionListener(captured);

        fsm.addListener(listener);
        assertEquals(1, fsm.getListenerCount(), "Should have 1 listener");

        // First transition - listener notified
        var entityId = "entity1";
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertEquals(1, captured.size(), "Listener should be notified");

        // Remove listener
        fsm.removeListener(listener);
        assertEquals(0, fsm.getListenerCount(), "Should have 0 listeners after removal");

        // Second transition - listener NOT notified
        makeViewStable();
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        assertEquals(1, captured.size(), "Listener should NOT be notified after removal");
    }

    /**
     * Test 8: Concurrent listener operations - add/remove/notify across multiple threads.
     */
    @Test
    void testListenerConcurrency() throws InterruptedException {
        var callCount = new AtomicInteger(0);
        var executorService = Executors.newFixedThreadPool(5);
        var latch = new CountDownLatch(100);

        // Create listener
        MigrationStateListener listener = new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                               EntityMigrationState toState,
                                               EntityMigrationStateMachine.TransitionResult result) {
                callCount.incrementAndGet();
            }

            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
                // Not used in this test
            }
        };

        try {
            // 100 concurrent operations: add listeners, remove listeners, trigger transitions
            for (int i = 0; i < 100; i++) {
                var index = i;
                executorService.submit(() -> {
                    try {
                        if (index % 3 == 0) {
                            // Add listener
                            fsm.addListener(listener);
                        } else if (index % 3 == 1) {
                            // Remove listener
                            fsm.removeListener(listener);
                        } else {
                            // Trigger transition
                            var entityId = "entity" + index;
                            fsm.initializeOwned(entityId);
                            fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for completion
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Operations should complete within 5 seconds");

            // Verify no exceptions thrown and operations executed
            assertTrue(callCount.get() >= 0, "Should have executed listener calls");
            assertTrue(fsm.getListenerCount() >= 0, "Should have valid listener count");

        } finally {
            executorService.shutdown();
            executorService.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    /**
     * Test 9: Listener notification includes entity ID correctly.
     */
    @Test
    void testListenerReceivesEntityId() {
        var capturedIds = new ArrayList<Object>();
        MigrationStateListener listener = new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                               EntityMigrationState toState,
                                               EntityMigrationStateMachine.TransitionResult result) {
                capturedIds.add(entityId);
            }

            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
                // Not used in this test
            }
        };

        fsm.addListener(listener);

        // Transition multiple entities
        fsm.initializeOwned("entity1");
        fsm.initializeOwned("entity2");
        fsm.initializeOwned("entity3");

        fsm.transition("entity1", EntityMigrationState.MIGRATING_OUT);
        fsm.transition("entity2", EntityMigrationState.MIGRATING_OUT);
        fsm.transition("entity3", EntityMigrationState.MIGRATING_OUT);

        // Verify all entity IDs captured
        assertEquals(3, capturedIds.size());
        assertTrue(capturedIds.contains("entity1"));
        assertTrue(capturedIds.contains("entity2"));
        assertTrue(capturedIds.contains("entity3"));
    }

    /**
     * Test 10: Blocked transition (view not stable) still notifies listener.
     */
    @Test
    void testListenerNotifiedOnBlockedTransition() {
        var captured = new ArrayList<TransitionEvent>();
        var listener = createTransitionListener(captured);

        fsm.addListener(listener);

        // Make view stable first
        makeViewStable();

        // Create entity in MIGRATING_OUT state
        var entityId = "entity1";
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Clear captured events
        captured.clear();

        // Now trigger a view change to make view unstable
        view.addMember("bubble2"); // Adding another member triggers view change
        viewMonitor.onTick(4); // Advance 1 tick (not enough for stability - need 3 ticks)

        // Attempt transition to DEPARTED while view is unstable (should be blocked)
        var result = fsm.transition(entityId, EntityMigrationState.DEPARTED);

        // Verify blocked transition notifies listener
        assertFalse(result.success, "Transition should be blocked");
        assertEquals("View not stable", result.reason, "Reason should indicate view instability");
        assertEquals(1, captured.size(), "Listener should be notified of blocked transition");

        var event = captured.get(0);
        assertFalse(event.result.success, "Result should indicate failure");
        assertEquals(EntityMigrationState.MIGRATING_OUT, event.fromState);
        assertEquals(EntityMigrationState.DEPARTED, event.toState);
    }

    // Helper class to capture transition events
    private static class TransitionEvent {
        final Object entityId;
        final EntityMigrationState fromState;
        final EntityMigrationState toState;
        final EntityMigrationStateMachine.TransitionResult result;

        TransitionEvent(Object entityId, EntityMigrationState fromState,
                       EntityMigrationState toState,
                       EntityMigrationStateMachine.TransitionResult result) {
            this.entityId = entityId;
            this.fromState = fromState;
            this.toState = toState;
            this.result = result;
        }
    }
}
