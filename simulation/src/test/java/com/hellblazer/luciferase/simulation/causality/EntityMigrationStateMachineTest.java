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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for EntityMigrationStateMachine (Phase 7C.5)
 *
 * Tests state transitions, view change handling, invariant checking,
 * and metrics tracking for entity ownership during migration.
 *
 * @author hal.hildebrand
 */
class EntityMigrationStateMachineTest {

    private MockFirefliesView<String> view;
    private FirefliesViewMonitor monitor;
    private EntityMigrationStateMachine fsm;
    private Object entityId;

    @BeforeEach
    void setup() {
        view = new MockFirefliesView<>();
        monitor = new FirefliesViewMonitor(view, 3);
        fsm = new EntityMigrationStateMachine(monitor);
        entityId = UUID.randomUUID();
        fsm.initializeOwned(entityId);
    }

    @Test
    void testInitialStateOwned() {
        var state = fsm.getState(entityId);
        assertEquals(EntityMigrationState.OWNED, state, "Should start in OWNED state");
        assertEquals(1, fsm.getEntityCount(), "Should have 1 entity tracked");
        assertEquals(0, fsm.getEntitiesInMigration(), "Should have 0 entities in migration");
    }

    @Test
    void testOwnedToMigratingOut() {
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        assertTrue(result.success, "Transition should succeed");
        assertEquals(EntityMigrationState.OWNED, result.fromState);
        assertEquals(EntityMigrationState.MIGRATING_OUT, result.toState);
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId));
        assertEquals(1, fsm.getEntitiesInMigration(), "Should have 1 entity in migration");
    }

    @Test
    void testMigratingOutToDeparted() {
        // First transition to MIGRATING_OUT
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Need view stable to transition to DEPARTED
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            monitor.onTick(i);
        }

        var result = fsm.transition(entityId, EntityMigrationState.DEPARTED);

        assertTrue(result.success, "Transition should succeed when view stable");
        assertEquals(EntityMigrationState.DEPARTED, fsm.getState(entityId));
        assertEquals(0, fsm.getEntitiesInMigration(), "Should have 0 entities in migration");
    }

    @Test
    void testMigratingOutToRollbackWithoutViewStable() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        var result = fsm.transition(entityId, EntityMigrationState.ROLLBACK_OWNED);

        assertTrue(result.success, "Rollback should always succeed");
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId));
    }

    @Test
    void testDepartedToGhost() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            monitor.onTick(i);
        }
        fsm.transition(entityId, EntityMigrationState.DEPARTED);

        var result = fsm.transition(entityId, EntityMigrationState.GHOST);

        assertTrue(result.success, "Transition DEPARTED -> GHOST should succeed");
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId));
    }

    @Test
    void testGhostToMigratingIn() {
        // Transition through OWNED -> MIGRATING_OUT -> DEPARTED -> GHOST
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            monitor.onTick(i);
        }
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);

        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);

        assertTrue(result.success, "GHOST -> MIGRATING_IN should succeed");
        assertEquals(EntityMigrationState.MIGRATING_IN, fsm.getState(entityId));
        assertEquals(1, fsm.getEntitiesInMigration(), "Should have 1 entity in migration");
    }

    @Test
    void testMigratingInToOwnedWithViewStable() {
        // Setup: transition to GHOST then MIGRATING_IN
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            monitor.onTick(i);
        }
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);

        // Now accept incoming (requires view stable)
        var result = fsm.transition(entityId, EntityMigrationState.OWNED);

        assertTrue(result.success, "MIGRATING_IN -> OWNED should succeed when view stable");
        assertEquals(EntityMigrationState.OWNED, fsm.getState(entityId));
        assertEquals(0, fsm.getEntitiesInMigration(), "Should have 0 entities in migration");
    }

    @Test
    void testMigratingInToGhost() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            monitor.onTick(i);
        }
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);

        var result = fsm.transition(entityId, EntityMigrationState.GHOST);

        assertTrue(result.success, "MIGRATING_IN -> GHOST should succeed");
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId));
    }

    @Test
    void testRollbackToOwned() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.ROLLBACK_OWNED);

        var result = fsm.transition(entityId, EntityMigrationState.OWNED);

        assertTrue(result.success, "ROLLBACK_OWNED -> OWNED should succeed");
        assertEquals(EntityMigrationState.OWNED, fsm.getState(entityId));
    }

    @Test
    void testInvalidTransitionFromOwned() {
        var result = fsm.transition(entityId, EntityMigrationState.DEPARTED);

        assertFalse(result.success, "Direct OWNED -> DEPARTED should fail");
        assertEquals("Invalid transition", result.reason);
        assertEquals(EntityMigrationState.OWNED, fsm.getState(entityId));
    }

    @Test
    void testInvalidTransitionFromGhost() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            monitor.onTick(i);
        }
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);

        var result = fsm.transition(entityId, EntityMigrationState.OWNED);

        assertFalse(result.success, "Direct GHOST -> OWNED should fail");
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId));
    }

    @Test
    void testInvariantExactlyOneOwned() {
        assertTrue(fsm.verifyInvariant(entityId), "Initial OWNED state should satisfy invariant");

        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertTrue(fsm.verifyInvariant(entityId), "MIGRATING_OUT should satisfy invariant");

        // Invariant check: cannot be both OWNED and MIGRATING_IN
        assertTrue(fsm.verifyInvariant(entityId), "Valid state should pass invariant");
    }

    @Test
    void testViewChangeTriggersRollback() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId));

        // View change happens
        fsm.onViewChange();

        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId), "Should rollback to ROLLBACK_OWNED");
        assertEquals(1L, fsm.getTotalRollbacks(), "Should have 1 rollback");
    }

    @Test
    void testViewChangeMigratingInBecomesGhost() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            monitor.onTick(i);
        }
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);

        // View change happens
        fsm.onViewChange();

        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId), "Should become GHOST");
    }

    @Test
    void testViewChangeRequiredForCommit() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Make view unstable by adding member (without waiting for stability)
        view.addMember("bubble1");

        // Try to transition without view being stable
        var result = fsm.transition(entityId, EntityMigrationState.DEPARTED);

        assertFalse(result.success, "Should fail without view stable");
        assertEquals("View not stable", result.reason);
    }

    @Test
    void testViewStableAllowsCommit() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Make view stable
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            monitor.onTick(i);
        }

        var result = fsm.transition(entityId, EntityMigrationState.DEPARTED);

        assertTrue(result.success, "Should succeed when view stable");
    }

    @Test
    void testMetricsTracking() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.ROLLBACK_OWNED);
        fsm.transition(entityId, EntityMigrationState.OWNED);

        assertEquals(3L, fsm.getTotalTransitions(), "Should have 3 transitions");
        assertEquals(0L, fsm.getTotalRollbacks(), "Should have 0 rollbacks from view change");
        assertEquals(0L, fsm.getTotalFailedTransitions(), "Should have 0 failed transitions");
    }

    @Test
    void testFailedTransitionMetrics() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Try invalid transition
        fsm.transition(entityId, EntityMigrationState.GHOST);

        // Try transition without view stable
        var failed = fsm.transition(entityId, EntityMigrationState.DEPARTED);

        assertTrue(fsm.getTotalFailedTransitions() >= 1L, "Should have at least 1 failed transition");
    }

    @Test
    void testConcurrentMigrations() {
        var entity2 = UUID.randomUUID();
        var entity3 = UUID.randomUUID();

        fsm.initializeOwned(entity2);
        fsm.initializeOwned(entity3);

        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entity2, EntityMigrationState.MIGRATING_OUT);
        // entity3 stays OWNED

        assertEquals(3, fsm.getEntityCount(), "Should have 3 entities");
        assertEquals(2, fsm.getEntitiesInMigration(), "Should have 2 in migration");
    }

    @Test
    void testGetEntitiesInState() {
        var entity2 = UUID.randomUUID();
        fsm.initializeOwned(entity2);

        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        // entity2 stays OWNED

        var owned = fsm.getEntitiesInState(EntityMigrationState.OWNED);
        assertEquals(1, owned.size(), "Should have 1 OWNED entity");
        assertTrue(owned.contains(entity2), "entity2 should be OWNED");

        var migrating = fsm.getEntitiesInState(EntityMigrationState.MIGRATING_OUT);
        assertEquals(1, migrating.size(), "Should have 1 MIGRATING_OUT entity");
        assertTrue(migrating.contains(entityId), "entityId should be MIGRATING_OUT");
    }

    @Test
    void testEntityNotFound() {
        var unknownId = UUID.randomUUID();
        var result = fsm.transition(unknownId, EntityMigrationState.MIGRATING_OUT);

        assertFalse(result.success, "Transition should fail for unknown entity");
        assertEquals("Entity not found", result.reason);
    }

    @Test
    void testReset() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertEquals(1, fsm.getEntityCount(), "Should have 1 entity");

        fsm.reset();

        assertEquals(0, fsm.getEntityCount(), "Should have 0 entities after reset");
        assertEquals(0L, fsm.getTotalTransitions(), "Metrics should be reset");
        assertNull(fsm.getState(entityId), "Entity state should be cleared");
    }

    @Test
    void testNullViewMonitor() {
        assertThrows(NullPointerException.class,
                    () -> new EntityMigrationStateMachine(null),
                    "Should throw on null view monitor");
    }

    @Test
    void testComplexMigrationFlow() {
        // Entity 1: OWNED -> MIGRATING_OUT -> DEPARTED -> GHOST
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Make view stable
        view.addMember("bubble1");
        for (int i = 1; i <= 3; i++) {
            monitor.onTick(i);
        }

        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);

        assertEquals(3, fsm.getTotalTransitions(), "Should have 3 successful transitions");
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId));
    }

    @Test
    void testMultipleRollbacks() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.onViewChange();
        assertEquals(1L, fsm.getTotalRollbacks(), "Should have 1 rollback");

        fsm.transition(entityId, EntityMigrationState.OWNED);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.onViewChange();
        assertEquals(2L, fsm.getTotalRollbacks(), "Should have 2 rollbacks");
    }

    @Test
    void testToString() {
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        var str = fsm.toString();
        assertNotNull(str);
        assertTrue(str.contains("EntityMigrationStateMachine"), "Should contain class name");
        assertTrue(str.contains("entities="), "Should show entity count");
    }
}
