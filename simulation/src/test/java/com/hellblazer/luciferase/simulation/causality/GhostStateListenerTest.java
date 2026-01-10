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
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import com.hellblazer.luciferase.simulation.ghost.GhostStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for GhostStateListener - FSM/Ghost bridge integration (Phase 7D.2 Part 1).
 *
 * Tests verify that GhostStateListener correctly bridges EntityMigrationStateMachine
 * state transitions to GhostStateManager operations.
 *
 * @author hal.hildebrand
 */
class GhostStateListenerTest {

    private GhostStateManager ghostStateManager;
    private EntityMigrationStateMachine fsm;
    private FirefliesViewMonitor viewMonitor;
    private GhostStateListener ghostStateListener;
    private MockFirefliesView<?> mockView;

    @BeforeEach
    void setUp() {
        // Create mock view and view monitor
        mockView = new MockFirefliesView<>();
        viewMonitor = new FirefliesViewMonitor(mockView, 3);

        // Create FSM with view monitor
        fsm = new EntityMigrationStateMachine(viewMonitor, EntityMigrationStateMachine.Configuration.defaultConfig());

        // Create ghost state manager
        var positions = List.of(new Point3f(0, 0, 0), new Point3f(100, 100, 100));
        var bounds = BubbleBounds.fromEntityPositions(positions);
        ghostStateManager = new GhostStateManager(bounds, 1000);

        // Create and register listener
        ghostStateListener = new GhostStateListener(ghostStateManager, fsm);
        fsm.addListener(ghostStateListener);
    }

    @Test
    @DisplayName("DEPARTED→GHOST transition succeeds, listener does not create ghost (network already did)")
    void testDepartedToGhostNoDirectAction() {
        // Given: Entity in DEPARTED state
        var entityId = new StringEntityID("entity1");
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);

        // When: Ghost created by network (simulated)
        var sourceBubble = UUID.randomUUID();
        var event = new EntityUpdateEvent(
            entityId,
            new Point3f(10, 10, 10),
            new Point3f(1, 0, 0),
            System.currentTimeMillis(),
            100L
        );
        ghostStateManager.updateGhost(sourceBubble, event);

        // And: Transition to GHOST state
        var result = fsm.transition(entityId, EntityMigrationState.GHOST);

        // Then: Transition succeeds
        assertTrue(result.success, "Transition should succeed");
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId));

        // And: Ghost exists in manager (created by network, not listener)
        assertNotNull(ghostStateManager.getGhost(entityId), "Ghost should exist");
    }

    @Test
    @DisplayName("GHOST→MIGRATING_IN transition validates position consistency")
    void testGhostToMigratingInValidatesPosition() {
        // Given: Entity in GHOST state with known position
        var entityId = new StringEntityID("entity2");
        var sourceBubble = UUID.randomUUID();
        var initialPosition = new Point3f(20, 20, 20);
        var velocity = new Point3f(2, 0, 0);

        // Initialize entity through DEPARTED→GHOST path
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);

        // Network creates ghost
        var event = new EntityUpdateEvent(entityId, initialPosition, velocity, System.currentTimeMillis(), 100L);
        ghostStateManager.updateGhost(sourceBubble, event);
        fsm.transition(entityId, EntityMigrationState.GHOST);

        // When: Transition to MIGRATING_IN (this triggers validation)
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);

        // Then: Transition succeeds (validation passes for consistent position)
        assertTrue(result.success, "Transition should succeed");
        assertEquals(EntityMigrationState.MIGRATING_IN, fsm.getState(entityId));
    }

    @Test
    @DisplayName("MIGRATING_IN→OWNED transition removes ghost from GhostStateManager")
    void testMigratingInToOwnedRemovesGhost() {
        // Given: Entity in MIGRATING_IN state with ghost tracking
        var entityId = new StringEntityID("entity3");
        var sourceBubble = UUID.randomUUID();

        // Setup: DEPARTED→GHOST→MIGRATING_IN
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);

        var event = new EntityUpdateEvent(
            entityId,
            new Point3f(30, 30, 30),
            new Point3f(1, 1, 0),
            System.currentTimeMillis(),
            100L
        );
        ghostStateManager.updateGhost(sourceBubble, event);
        fsm.transition(entityId, EntityMigrationState.GHOST);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);

        // Verify ghost exists before transition
        assertNotNull(ghostStateManager.getGhost(entityId), "Ghost should exist before OWNED transition");

        // When: Transition to OWNED
        viewMonitor.onTick(100);  // Ensure view is stable
        var result = fsm.transition(entityId, EntityMigrationState.OWNED);

        // Then: Transition succeeds
        assertTrue(result.success, "Transition should succeed");
        assertEquals(EntityMigrationState.OWNED, fsm.getState(entityId));

        // And: Ghost removed from manager
        assertNull(ghostStateManager.getGhost(entityId), "Ghost should be removed after OWNED transition");
    }

    @Test
    @DisplayName("Any→ROLLBACK_OWNED transition cleans up ghost")
    void testRollbackOwnedCleansUpGhost() {
        // Given: Entity in MIGRATING_OUT state
        var entityId = new StringEntityID("entity4");
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Create ghost (simulating remote tracking)
        var sourceBubble = UUID.randomUUID();
        var event = new EntityUpdateEvent(
            entityId,
            new Point3f(40, 40, 40),
            new Point3f(0, 1, 0),
            System.currentTimeMillis(),
            100L
        );
        ghostStateManager.updateGhost(sourceBubble, event);

        // When: Rollback to ROLLBACK_OWNED
        var result = fsm.transition(entityId, EntityMigrationState.ROLLBACK_OWNED);

        // Then: Transition succeeds
        assertTrue(result.success, "Rollback transition should succeed");
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId));

        // And: Ghost cleaned up
        assertNull(ghostStateManager.getGhost(entityId), "Ghost should be removed on rollback");
    }

    @Test
    @DisplayName("View change reconciliation removes ghosts for non-GHOST entities")
    void testViewChangeReconciliation() {
        // Given: Multiple entities in various states
        var ghost1 = new StringEntityID("ghost1");
        var ghost2 = new StringEntityID("ghost2");
        var owned1 = new StringEntityID("owned1");

        // Setup ghost1 and ghost2 in GHOST state
        for (var entityId : new StringEntityID[]{ghost1, ghost2}) {
            fsm.initializeOwned(entityId);
            fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
            fsm.transition(entityId, EntityMigrationState.DEPARTED);

            var event = new EntityUpdateEvent(
                entityId,
                new Point3f(10, 10, 10),
                new Point3f(1, 0, 0),
                System.currentTimeMillis(),
                100L
            );
            ghostStateManager.updateGhost(UUID.randomUUID(), event);
            fsm.transition(entityId, EntityMigrationState.GHOST);
        }

        // Setup owned1 in OWNED state but with ghost tracking (anomaly)
        fsm.initializeOwned(owned1);
        var event = new EntityUpdateEvent(owned1, new Point3f(50, 50, 50), new Point3f(0, 0, 1), System.currentTimeMillis(), 100L);
        ghostStateManager.updateGhost(UUID.randomUUID(), event);

        // Verify initial state
        assertNotNull(ghostStateManager.getGhost(ghost1));
        assertNotNull(ghostStateManager.getGhost(ghost2));
        assertNotNull(ghostStateManager.getGhost(owned1));

        // When: View change occurs (triggers reconciliation)
        fsm.onViewChange();

        // Then: Ghosts for entities not in GHOST state should be removed
        // ghost1 and ghost2 remain in GHOST state, so their ghosts should stay
        assertNotNull(ghostStateManager.getGhost(ghost1), "ghost1 should remain");
        assertNotNull(ghostStateManager.getGhost(ghost2), "ghost2 should remain");

        // owned1 is in OWNED state, so its ghost should be removed during reconciliation
        // Note: This requires calling reconcileGhostState() explicitly or on next tick
        ghostStateListener.reconcileGhostState();
        assertNull(ghostStateManager.getGhost(owned1), "owned1 ghost should be removed");
    }

    @Test
    @DisplayName("Multiple concurrent ghosts tracked correctly")
    void testMultipleGhostTracking() {
        // Given: 10 entities in GHOST state
        var entities = new ArrayList<StringEntityID>();
        for (int i = 0; i < 10; i++) {
            entities.add(new StringEntityID("multi" + i));
        }

        var sourceBubble = UUID.randomUUID();

        // When: All entities transition to GHOST
        for (var entityId : entities) {
            fsm.initializeOwned(entityId);
            fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
            fsm.transition(entityId, EntityMigrationState.DEPARTED);

            var event = new EntityUpdateEvent(
                entityId,
                new Point3f(10, 10, 10),
                new Point3f(1, 0, 0),
                System.currentTimeMillis(),
                100L
            );
            ghostStateManager.updateGhost(sourceBubble, event);
            fsm.transition(entityId, EntityMigrationState.GHOST);
        }

        // Then: All ghosts tracked correctly
        for (var entityId : entities) {
            assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId));
            assertNotNull(ghostStateManager.getGhost(entityId), "Ghost should exist for " + entityId);
        }

        // When: All transition to MIGRATING_IN then OWNED
        viewMonitor.onTick(100);
        for (var entityId : entities) {
            fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);
            fsm.transition(entityId, EntityMigrationState.OWNED);
        }

        // Then: All ghosts removed
        for (var entityId : entities) {
            assertEquals(EntityMigrationState.OWNED, fsm.getState(entityId));
            assertNull(ghostStateManager.getGhost(entityId), "Ghost should be removed for " + entityId);
        }
    }

    @Test
    @DisplayName("StringEntityID conversion works correctly")
    void testEntityIdMapping() {
        // Given: Entity with StringEntityID
        var entityId = new StringEntityID("string-entity");
        var sourceBubble = UUID.randomUUID();

        // When: Entity goes through GHOST lifecycle
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);

        var event = new EntityUpdateEvent(
            entityId,
            new Point3f(10, 10, 10),
            new Point3f(1, 0, 0),
            System.currentTimeMillis(),
            100L
        );
        ghostStateManager.updateGhost(sourceBubble, event);
        fsm.transition(entityId, EntityMigrationState.GHOST);

        // Then: Ghost manager correctly handles StringEntityID
        var ghost = ghostStateManager.getGhost(entityId);
        assertNotNull(ghost, "Ghost should exist");
        assertEquals(entityId, ghost.entityId(), "Entity ID should match");
    }

    @Test
    @DisplayName("Listener properly integrated with FSM")
    void testListenerRegistrationIntegration() {
        // Given: Fresh FSM and listener
        var freshFsm = new EntityMigrationStateMachine(viewMonitor, EntityMigrationStateMachine.Configuration.defaultConfig());
        var positions = List.of(new Point3f(0, 0, 0), new Point3f(100, 100, 100));
        var bounds = BubbleBounds.fromEntityPositions(positions);
        var freshManager = new GhostStateManager(bounds, 1000);
        var freshListener = new GhostStateListener(freshManager, freshFsm);

        // When: Listener registered
        freshFsm.addListener(freshListener);

        // Then: FSM reports listener
        assertEquals(1, freshFsm.getListenerCount(), "Should have 1 listener");
        assertTrue(freshFsm.getListeners().contains(freshListener), "Should contain our listener");

        // When: Listener removed
        freshFsm.removeListener(freshListener);

        // Then: FSM no longer has listener
        assertEquals(0, freshFsm.getListenerCount(), "Should have 0 listeners");
        assertFalse(freshFsm.getListeners().contains(freshListener), "Should not contain removed listener");
    }

    @Test
    @DisplayName("Thread-safe concurrent ghost operations")
    void testThreadSafeGhostOperations() throws Exception {
        // Given: 10 threads operating on 100 entities each
        int threadCount = 10;
        int entitiesPerThread = 100;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        // When: Concurrent transitions
        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < entitiesPerThread; i++) {
                        var entityId = new StringEntityID("thread" + threadId + "-entity" + i);
                        var sourceBubble = UUID.randomUUID();

                        // Rapid state transitions
                        fsm.initializeOwned(entityId);
                        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
                        fsm.transition(entityId, EntityMigrationState.DEPARTED);

                        var event = new EntityUpdateEvent(
                            entityId,
                            new Point3f(10, 10, 10),
                            new Point3f(1, 0, 0),
                            System.currentTimeMillis(),
                            100L
                        );
                        ghostStateManager.updateGhost(sourceBubble, event);
                        fsm.transition(entityId, EntityMigrationState.GHOST);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Then: All operations complete without exceptions
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate");

        // And: Correct number of entities tracked
        assertEquals(threadCount * entitiesPerThread, fsm.getEntityCount(),
                    "Should have all entities tracked");
    }

    @Test
    @DisplayName("Stale ghost detection and removal during tick")
    void testStalenessDetectionIntegration() throws Exception {
        // Given: Entity in GHOST state
        var entityId = new StringEntityID("stale-entity");
        var sourceBubble = UUID.randomUUID();

        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);

        var event = new EntityUpdateEvent(
            entityId,
            new Point3f(10, 10, 10),
            new Point3f(1, 0, 0),
            System.currentTimeMillis(),
            100L
        );
        ghostStateManager.updateGhost(sourceBubble, event);
        fsm.transition(entityId, EntityMigrationState.GHOST);

        // Verify ghost exists
        assertNotNull(ghostStateManager.getGhost(entityId), "Ghost should exist initially");

        // When: Time advances beyond staleness threshold (500ms default)
        Thread.sleep(600);
        ghostStateManager.tick(System.currentTimeMillis());

        // Then: Stale ghost culled
        assertNull(ghostStateManager.getGhost(entityId), "Stale ghost should be culled");
    }
}
