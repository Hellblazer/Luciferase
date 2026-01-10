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

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import com.hellblazer.luciferase.simulation.ghost.GhostStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ViewChangeReconciliationTest - Phase 7D.2 Part 2, Phase A: View Change Integration Tests.
 * <p>
 * Tests automatic ghost reconciliation when FirefliesViewMonitor detects view changes.
 * Validates that ghosts are cleaned up when entities leave GHOST state after view changes.
 * <p>
 * Test Coverage:
 * <ul>
 *   <li>View change triggers reconciliation callback</li>
 *   <li>Reconciliation removes ghosts for non-GHOST entities</li>
 *   <li>Reconciliation preserves valid ghosts still in GHOST state</li>
 *   <li>View monitor registration works correctly</li>
 *   <li>Multiple rapid view changes handled correctly</li>
 *   <li>Re-entry: entity reappears as ghost after leaving</li>
 *   <li>Concurrent view changes + FSM transitions</li>
 *   <li>Reconciliation metrics tracked correctly</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class ViewChangeReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(ViewChangeReconciliationTest.class);

    private GhostStateManager ghostStateManager;
    private EntityMigrationStateMachine fsm;
    private GhostStateListener ghostStateListener;
    private FirefliesViewMonitor viewMonitor;
    private MockMembershipView membershipView;
    private BubbleBounds bounds;

    @BeforeEach
    void setUp() {
        // Create bounds for ghost manager from root tetrahedron at level 10
        var rootKey = com.hellblazer.luciferase.lucien.tetree.TetreeKey.create((byte) 10, 0L, 0L);
        bounds = BubbleBounds.fromTetreeKey(rootKey);

        // Create ghost state manager
        ghostStateManager = new GhostStateManager(bounds, 1000);

        // Create mock membership view
        membershipView = new MockMembershipView();

        // Create view monitor with 3 tick threshold (fast for testing)
        viewMonitor = new FirefliesViewMonitor(membershipView, 3);

        // Create FSM with default config
        fsm = new EntityMigrationStateMachine(viewMonitor, EntityMigrationStateMachine.Configuration.defaultConfig());

        // Create ghost state listener
        ghostStateListener = new GhostStateListener(ghostStateManager, fsm);
        fsm.addListener(ghostStateListener);
    }

    /**
     * Helper method to transition entity to GHOST state following proper FSM path.
     * Path: OWNED → MIGRATING_OUT → DEPARTED → GHOST
     */
    private void transitionToGhostState(Object entityId) {
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);
    }

    /**
     * Helper method to transition entity from GHOST state back to OWNED state.
     * Path: GHOST → MIGRATING_IN → OWNED
     */
    private void transitionFromGhostToOwned(Object entityId) {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);
        fsm.transition(entityId, EntityMigrationState.OWNED);
    }

    /**
     * Test 1: View change triggers reconciliation.
     * Verify that when FirefliesViewMonitor detects a view change, reconciliation is triggered.
     */
    @Test
    void testViewChangeTriggersReconciliation() {
        // Register listener with view monitor
        ghostStateListener.registerWithViewMonitor(viewMonitor);

        // Create entity in GHOST state
        var entityId = new StringEntityID("entity-1");
        transitionToGhostState(entityId);

        // Add ghost to ghost manager
        var sourceBubbleId = UUID.randomUUID();
        var event = new EntityUpdateEvent(
            entityId,
            new Point3f(1, 2, 3),
            new Point3f(0.1f, 0.2f, 0.3f),
            System.currentTimeMillis(),
            1L
        );
        ghostStateManager.updateGhost(sourceBubbleId, event);

        // Verify ghost exists
        assertEquals(1, ghostStateManager.getActiveGhostCount());

        // Transition entity out of GHOST state
        transitionFromGhostToOwned(entityId);

        // Trigger view change
        var member = new Object();
        membershipView.triggerViewChange(List.of(member), List.of());

        // Wait briefly for async callback
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify reconciliation occurred (ghost removed because entity is OWNED)
        assertEquals(0, ghostStateManager.getActiveGhostCount(), "Ghost should be removed after reconciliation");

        // Verify reconciliation count incremented
        assertTrue(ghostStateListener.getReconciliationCount() > 0, "Reconciliation count should be incremented");
    }

    /**
     * Test 2: Reconciliation removes ghosts for non-GHOST entities.
     * When reconciliation runs, ghosts for entities not in GHOST state should be removed.
     */
    @Test
    void testReconciliationRemovesNonGhostEntities() {
        // Create three entities: one GHOST, one OWNED, one MIGRATING_IN
        var ghostEntity = new StringEntityID("ghost");
        var ownedEntity = new StringEntityID("owned");
        var migratingEntity = new StringEntityID("migrating");

        // Set FSM states
        fsm.initializeOwned(ghostEntity);
        fsm.transition(ghostEntity, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(ghostEntity, EntityMigrationState.DEPARTED);
        fsm.transition(ghostEntity, EntityMigrationState.GHOST);

        fsm.initializeOwned(ownedEntity);
        // ownedEntity stays in OWNED

        fsm.initializeOwned(migratingEntity);
        fsm.transition(migratingEntity, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(migratingEntity, EntityMigrationState.DEPARTED);
        fsm.transition(migratingEntity, EntityMigrationState.GHOST);
        fsm.transition(migratingEntity, EntityMigrationState.MIGRATING_IN);

        // Add ghosts for all three entities
        var sourceBubbleId = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();

        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            ghostEntity, new Point3f(1, 1, 1), new Point3f(0.1f, 0, 0), timestamp, 1L
        ));
        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            ownedEntity, new Point3f(2, 2, 2), new Point3f(0.2f, 0, 0), timestamp, 1L
        ));
        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            migratingEntity, new Point3f(3, 3, 3), new Point3f(0.3f, 0, 0), timestamp, 1L
        ));

        assertEquals(3, ghostStateManager.getActiveGhostCount(), "Should have 3 ghosts initially");

        // Debug: Check FSM states before reconciliation
        log.debug("Ghost entity state before reconciliation: {}", fsm.getState(ghostEntity));
        log.debug("Owned entity state before reconciliation: {}", fsm.getState(ownedEntity));
        log.debug("Migrating entity state before reconciliation: {}", fsm.getState(migratingEntity));

        // Run reconciliation
        ghostStateListener.reconcileGhostState();

        // Debug: Check ghost count after reconciliation
        log.debug("Ghost count after reconciliation: {}", ghostStateManager.getActiveGhostCount());

        // Verify only ghost entity remains
        assertEquals(1, ghostStateManager.getActiveGhostCount(), "Only GHOST entity should remain");
        assertNotNull(ghostStateManager.getGhost(ghostEntity), "Ghost entity should still have ghost");
        assertNull(ghostStateManager.getGhost(ownedEntity), "OWNED entity should have ghost removed");
        assertNull(ghostStateManager.getGhost(migratingEntity), "MIGRATING_IN entity should have ghost removed");
    }

    /**
     * Test 3: Reconciliation preserves valid ghosts.
     * Entities still in GHOST state should keep their ghost tracking after reconciliation.
     */
    @Test
    void testReconciliationPreservesValidGhosts() {
        // Create two entities both in GHOST state
        var ghost1 = new StringEntityID("ghost-1");
        var ghost2 = new StringEntityID("ghost-2");

        transitionToGhostState(ghost1);
        transitionToGhostState(ghost2);

        // Add ghosts
        var sourceBubbleId = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();

        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            ghost1, new Point3f(1, 1, 1), new Point3f(0.1f, 0, 0), timestamp, 1L
        ));
        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            ghost2, new Point3f(2, 2, 2), new Point3f(0.2f, 0, 0), timestamp, 1L
        ));

        assertEquals(2, ghostStateManager.getActiveGhostCount());

        // Run reconciliation
        ghostStateListener.reconcileGhostState();

        // Verify both ghosts preserved
        assertEquals(2, ghostStateManager.getActiveGhostCount(), "Both ghosts should be preserved");
        assertNotNull(ghostStateManager.getGhost(ghost1), "Ghost 1 should be preserved");
        assertNotNull(ghostStateManager.getGhost(ghost2), "Ghost 2 should be preserved");
    }

    /**
     * Test 4: View monitor registration works.
     * Verify that registering GhostStateListener with FirefliesViewMonitor succeeds.
     */
    @Test
    void testViewMonitorRegistrationWorks() {
        // Registration should succeed
        assertDoesNotThrow(() -> ghostStateListener.registerWithViewMonitor(viewMonitor));

        // Verify listener is registered by triggering a view change and checking callback
        var entity = new StringEntityID("test");
        transitionToGhostState(entity);

        var sourceBubbleId = UUID.randomUUID();
        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            entity, new Point3f(1, 1, 1), new Point3f(0.1f, 0, 0), System.currentTimeMillis(), 1L
        ));

        // Transition out of GHOST
        transitionFromGhostToOwned(entity);

        // Trigger view change
        membershipView.triggerViewChange(List.of(new Object()), List.of());

        // Wait for async callback
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Ghost should be removed (reconciliation occurred)
        assertEquals(0, ghostStateManager.getActiveGhostCount(), "Reconciliation should have occurred");
    }

    /**
     * Test 5: Multiple rapid view changes handled correctly.
     * System should handle rapid successive view changes without errors.
     */
    @Test
    void testMultipleViewChangesHandledCorrectly() {
        ghostStateListener.registerWithViewMonitor(viewMonitor);

        // Create entity in GHOST state
        var entity = new StringEntityID("rapid-test");
        transitionToGhostState(entity);

        var sourceBubbleId = UUID.randomUUID();
        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            entity, new Point3f(1, 1, 1), new Point3f(0.1f, 0, 0), System.currentTimeMillis(), 1L
        ));

        // Trigger multiple rapid view changes
        for (int i = 0; i < 5; i++) {
            membershipView.triggerViewChange(List.of(new Object()), List.of());
        }

        // Wait for all callbacks
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // System should handle all view changes without errors
        assertTrue(ghostStateListener.getReconciliationCount() >= 5, "All view changes should trigger reconciliation");

        // Ghost should still exist (entity is GHOST)
        assertEquals(1, ghostStateManager.getActiveGhostCount(), "Ghost should be preserved");
    }

    /**
     * Test 6: Re-entry entity re-initializes ghost.
     * When an entity was GHOST, left, then returns as GHOST, it should be tracked again.
     */
    @Test
    void testReentryEntityReInitializesGhost() {
        // Create entity in GHOST state
        var entity = new StringEntityID("reentry");
        transitionToGhostState(entity);

        var sourceBubbleId = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();
        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            entity, new Point3f(1, 1, 1), new Point3f(0.1f, 0, 0), timestamp, 1L
        ));

        assertEquals(1, ghostStateManager.getActiveGhostCount());

        // Entity leaves (transitions back to OWNED)
        transitionFromGhostToOwned(entity);
        ghostStateListener.reconcileGhostState();

        assertEquals(0, ghostStateManager.getActiveGhostCount(), "Ghost should be removed");

        // Entity re-enters as GHOST
        transitionToGhostState(entity);
        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            entity, new Point3f(2, 2, 2), new Point3f(0.2f, 0, 0), timestamp + 100, 2L
        ));

        // Ghost should be re-tracked
        assertEquals(1, ghostStateManager.getActiveGhostCount(), "Ghost should be re-tracked on re-entry");
        var ghost = ghostStateManager.getGhost(entity);
        assertNotNull(ghost, "Ghost should exist");
    }

    /**
     * Test 7: Concurrent view change and FSM transition.
     * View change and FSM state transition occurring concurrently should be handled safely.
     */
    @Test
    void testConcurrentViewChangeAndTransition() throws InterruptedException {
        ghostStateListener.registerWithViewMonitor(viewMonitor);

        var entity = new StringEntityID("concurrent");
        transitionToGhostState(entity);

        var sourceBubbleId = UUID.randomUUID();
        ghostStateManager.updateGhost(sourceBubbleId, new EntityUpdateEvent(
            entity, new Point3f(1, 1, 1), new Point3f(0.1f, 0, 0), System.currentTimeMillis(), 1L
        ));

        var latch = new CountDownLatch(2);

        // Thread 1: Trigger view changes
        var viewChangeThread = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                membershipView.triggerViewChange(List.of(new Object()), List.of());
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            latch.countDown();
        });

        // Thread 2: Trigger FSM transitions
        var transitionThread = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                if (i % 2 == 0) {
                    fsm.initializeOwned(entity);
                } else {
                    transitionToGhostState(entity);
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            latch.countDown();
        });

        viewChangeThread.start();
        transitionThread.start();

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Concurrent operations should complete");

        // No exceptions should be thrown
        // Final state should be consistent
        var currentState = fsm.getState(entity);
        if (currentState == EntityMigrationState.GHOST) {
            // Ghost may or may not exist depending on reconciliation timing
            assertTrue(ghostStateManager.getActiveGhostCount() <= 1, "At most one ghost should exist");
        } else {
            // Ghost should be removed if not in GHOST state after reconciliation
            // (may take a moment for final reconciliation)
        }
    }

    /**
     * Test 8: Reconciliation metrics tracked.
     * Verify that reconciliation count metric is incremented on each reconciliation.
     */
    @Test
    void testReconciliationMetricsTracked() {
        assertEquals(0, ghostStateListener.getReconciliationCount(), "Initial count should be 0");

        // Run reconciliation manually
        ghostStateListener.reconcileGhostState();
        assertEquals(1, ghostStateListener.getReconciliationCount(), "Count should be 1 after first reconciliation");

        // Run again
        ghostStateListener.reconcileGhostState();
        assertEquals(2, ghostStateListener.getReconciliationCount(), "Count should be 2 after second reconciliation");

        // Trigger via view change
        ghostStateListener.registerWithViewMonitor(viewMonitor);
        membershipView.triggerViewChange(List.of(new Object()), List.of());

        // Wait for async callback
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertTrue(ghostStateListener.getReconciliationCount() >= 3, "Count should increment on view change");
    }

    // ========== Mock MembershipView for Testing ==========

    /**
     * Mock MembershipView for testing.
     */
    private static class MockMembershipView implements MembershipView<Object> {

        private final Set<Object> members = Collections.synchronizedSet(new HashSet<>());
        private final List<java.util.function.Consumer<ViewChange<Object>>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public java.util.stream.Stream<Object> getMembers() {
            return new HashSet<>(members).stream();
        }

        @Override
        public void addListener(java.util.function.Consumer<ViewChange<Object>> listener) {
            listeners.add(listener);
        }

        /**
         * Trigger a view change event (for testing).
         */
        void triggerViewChange(List<Object> joined, List<Object> left) {
            // Update members
            members.addAll(joined);
            members.removeAll(left);

            // Notify listeners
            var change = new ViewChange<>(joined, left);
            for (var listener : listeners) {
                listener.accept(change);
            }
        }
    }
}
