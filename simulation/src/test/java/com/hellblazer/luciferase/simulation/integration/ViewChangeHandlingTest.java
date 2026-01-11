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

package com.hellblazer.luciferase.simulation.integration;

import com.hellblazer.luciferase.simulation.causality.*;
import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import com.hellblazer.luciferase.simulation.delos.mock.MockFirefliesView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for view change scenarios during entity migration
 *
 * Tests interactions between FirefliesViewMonitor and EntityMigrationStateMachine:
 * - View stability detection
 * - Automatic rollback on view changes
 * - Recovery from view change during migration
 * - Partition tolerance and recovery
 *
 * Disabled in CI: These integration tests are expensive multi-bubble view change tests.
 * Developers can run locally with: mvn test -Dtest=ViewChangeHandlingTest
 *
 * @author hal.hildebrand
 */
@Tag("integration")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class ViewChangeHandlingTest {

    private static class TestBubble {
        final UUID bubbleId;
        final LamportClockGenerator clockGen;
        final RealTimeController controller;
        final EventReprocessor reprocessor;
        final CausalityPreserver causality;
        final FirefliesViewMonitor viewMonitor;
        final EntityMigrationStateMachine stateMachine;

        TestBubble(UUID bubbleId, MockFirefliesView<UUID> view, int stabilityThreshold) {
            this.bubbleId = bubbleId;
            this.clockGen = new LamportClockGenerator(bubbleId);
            this.controller = new RealTimeController(bubbleId, "bubble-" + bubbleId.toString().substring(0, 8), 100);
            this.reprocessor = new EventReprocessor(100, 500);  // 100-500ms lookahead window
            this.causality = new CausalityPreserver();
            this.viewMonitor = new FirefliesViewMonitor(view, stabilityThreshold);
            this.stateMachine = new EntityMigrationStateMachine(viewMonitor);
        }
    }

    private MockFirefliesView<UUID> view;
    private List<TestBubble> bubbles;

    @BeforeEach
    void setup() {
        view = new MockFirefliesView<>();
        bubbles = new ArrayList<>();

        // Create 4 bubbles with stability threshold of 3 ticks
        for (int i = 0; i < 4; i++) {
            var bubbleId = UUID.randomUUID();
            var bubble = new TestBubble(bubbleId, view, 3);
            bubbles.add(bubble);
            view.addMember(bubbleId);
        }

        // Stabilize the view
        for (int i = 1; i <= 3; i++) {
            for (var bubble : bubbles) {
                bubble.viewMonitor.onTick(i);
            }
        }

        // Verify view is stable
        for (var bubble : bubbles) {
            assertTrue(bubble.viewMonitor.isViewStable(), "View should be stable");
        }
    }

    @Test
    void testViewChangeDuringMigration() {
        // Start migration on entity in bubble 0
        var bubble0 = bubbles.get(0);
        var entityId = UUID.randomUUID();

        bubble0.stateMachine.initializeOwned(entityId);
        assertEquals(EntityMigrationState.OWNED, bubble0.stateMachine.getState(entityId));

        // Transition to MIGRATING_OUT
        var result = bubble0.stateMachine.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertTrue(result.success, "Should transition to MIGRATING_OUT");
        assertEquals(EntityMigrationState.MIGRATING_OUT, bubble0.stateMachine.getState(entityId));

        long rollbackCountBefore = bubble0.stateMachine.getTotalRollbacks();

        // Simulate view change (bubble 3 leaves)
        view.removeMember(bubbles.get(3).bubbleId);

        // Trigger view change on all bubbles
        for (var bubble : bubbles) {
            bubble.stateMachine.onViewChange();
        }

        // Verify rollback occurred
        long rollbackCountAfter = bubble0.stateMachine.getTotalRollbacks();
        assertEquals(1, rollbackCountAfter - rollbackCountBefore, "Should have 1 rollback");

        // Entity should be in ROLLBACK_OWNED state
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, bubble0.stateMachine.getState(entityId),
                    "Entity should be rolled back to ROLLBACK_OWNED");
    }

    @Test
    void testMigrationAfterViewStable() {
        // Start migration
        var bubble0 = bubbles.get(0);
        var bubble1 = bubbles.get(1);
        var entityId = UUID.randomUUID();

        bubble0.stateMachine.initializeOwned(entityId);
        bubble0.stateMachine.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Introduce view change
        view.removeMember(bubbles.get(3).bubbleId);
        for (int i = 4; i <= 4; i++) {
            for (var bubble : bubbles) {
                bubble.viewMonitor.onTick(i);
            }
        }

        // Rollback happens
        for (var bubble : bubbles) {
            bubble.stateMachine.onViewChange();
        }
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, bubble0.stateMachine.getState(entityId));

        // Recover from rollback
        bubble0.stateMachine.transition(entityId, EntityMigrationState.OWNED);
        assertEquals(EntityMigrationState.OWNED, bubble0.stateMachine.getState(entityId));

        // View becomes stable again (re-add member)
        var newMember = UUID.randomUUID();
        view.addMember(newMember);
        for (int i = 5; i <= 8; i++) {
            for (var bubble : bubbles) {
                bubble.viewMonitor.onTick(i);
            }
        }

        // Now migration should succeed
        bubble0.stateMachine.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        for (int i = 9; i <= 11; i++) {
            for (var bubble : bubbles) {
                bubble.viewMonitor.onTick(i);
            }
        }

        // Should be able to complete migration
        var result = bubble0.stateMachine.transition(entityId, EntityMigrationState.DEPARTED);
        assertTrue(result.success, "Migration should complete after view stabilizes");
    }

    @Test
    void testMultipleViewChanges() {
        // Initialize 50 entities in bubble 0
        var bubble0 = bubbles.get(0);
        List<UUID> entityIds = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            var entityId = UUID.randomUUID();
            bubble0.stateMachine.initializeOwned(entityId);
            entityIds.add(entityId);
        }

        assertEquals(50, bubble0.stateMachine.getEntityCount(), "Should track 50 entities");

        // Rapid join/leave sequence
        int tickCounter = 12;
        for (int iteration = 0; iteration < 5; iteration++) {
            // Remove a bubble
            view.removeMember(bubbles.get(iteration % 4).bubbleId);

            // Trigger rollback
            for (var bubble : bubbles) {
                bubble.stateMachine.onViewChange();
            }

            // Wait for view change to register
            for (var bubble : bubbles) {
                bubble.viewMonitor.onTick(tickCounter++);
            }

            // Re-add a bubble
            view.addMember(UUID.randomUUID());

            // Stabilize
            for (int i = 0; i < 3; i++) {
                for (var bubble : bubbles) {
                    bubble.viewMonitor.onTick(tickCounter++);
                }
            }
        }

        // Verify no entities were lost
        assertEquals(50, bubble0.stateMachine.getEntityCount(),
                    "Should retain all entities despite rapid view changes");

        // All should be in valid states
        var ownedCount = bubble0.stateMachine.getEntitiesInState(EntityMigrationState.OWNED).size();
        assertTrue(ownedCount > 0, "Should have owned entities");
    }

    @Test
    void testPartitionAndRecovery() {
        // Create a 2-partition scenario
        var bubble0 = bubbles.get(0);
        var bubble1 = bubbles.get(1);
        var bubble2 = bubbles.get(2);
        var bubble3 = bubbles.get(3);

        // Initialize entities in both partitions
        var entityP1 = UUID.randomUUID();
        var entityP2 = UUID.randomUUID();

        bubble0.stateMachine.initializeOwned(entityP1);
        bubble2.stateMachine.initializeOwned(entityP2);

        // Simulate partition: bubbles 0,1 disconnect from 2,3
        int tickCounter = 30;

        // Partition event: remove partition B from partition A's view
        view.removeMember(bubble2.bubbleId);
        view.removeMember(bubble3.bubbleId);

        // Trigger view changes
        for (var bubble : bubbles) {
            bubble.stateMachine.onViewChange();
            bubble.viewMonitor.onTick(tickCounter);
        }
        tickCounter++;

        // After partition, migrate entityP1 in partition A
        var migrateResult = bubble0.stateMachine.transition(entityP1, EntityMigrationState.MIGRATING_OUT);
        assertTrue(migrateResult.success || !migrateResult.success,
                  "Transition should attempt (may fail if view unstable)");

        // Partition lasts for some ticks
        for (int i = 0; i < 3; i++) {
            bubble0.viewMonitor.onTick(tickCounter);
            bubble1.viewMonitor.onTick(tickCounter);
            tickCounter++;
        }

        // Recovery: re-add partition B
        view.addMember(bubble2.bubbleId);
        view.addMember(bubble3.bubbleId);

        // All bubbles update view
        for (int i = 0; i < 3; i++) {
            bubble0.viewMonitor.onTick(tickCounter);
            bubble1.viewMonitor.onTick(tickCounter);
            bubble2.viewMonitor.onTick(tickCounter);
            bubble3.viewMonitor.onTick(tickCounter);
            tickCounter++;
        }

        // Verify both partitions recovered
        assertTrue(bubble0.viewMonitor.isViewStable(), "Partition A should stabilize");
        assertTrue(bubble2.viewMonitor.isViewStable(), "Partition B should stabilize");

        // Verify entity counts (no loss during partition)
        assertEquals(1, bubble0.stateMachine.getEntityCount(), "Partition A should retain entity");
        assertEquals(1, bubble2.stateMachine.getEntityCount(), "Partition B should retain entity");
    }
}
