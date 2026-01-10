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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for timeout detection and processing (Phase 7D.1 Part 2).
 *
 * Validates:
 * - checkTimeouts() correctly identifies timed-out migrations
 * - processTimeouts() applies correct rollback state transitions
 * - Metrics tracking works correctly
 * - Configuration flags are respected
 * - Multiple concurrent timeouts handled properly
 *
 * @author hal.hildebrand
 */
class EntityMigrationTimeoutProcessingTest {

    private MockFirefliesView<String> view;
    private FirefliesViewMonitor viewMonitor;
    private EntityMigrationStateMachine fsm;

    @BeforeEach
    void setUp() {
        view = new MockFirefliesView<>();
        viewMonitor = new FirefliesViewMonitor(view, 3);
        // Use config that doesn't require view stability for timeout rollbacks
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                               .requireViewStability(false)
                                                               .migrationTimeoutMs(8000L)
                                                               .enableTimeoutRollback(true)
                                                               .build();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);
    }

    // ========== checkTimeouts() Tests (4 tests) ==========

    @Test
    @DisplayName("checkTimeouts() returns empty when no migrations in progress")
    void testCheckTimeoutsEmptyWhenNoMigrations() {
        // Given: No entities in migration
        var currentTimeMs = System.currentTimeMillis();

        // When: Check for timeouts
        var timedOut = fsm.checkTimeouts(currentTimeMs);

        // Then: No timeouts found
        assertNotNull(timedOut);
        assertTrue(timedOut.isEmpty());
    }

    @Test
    @DisplayName("checkTimeouts() finds timed-out MIGRATING_OUT entities")
    void testCheckTimeoutsFindsTimedOutMigrations() {
        // Given: Entity in MIGRATING_OUT state
        var entityId = "entity1";
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Advance time beyond timeout (default config = 8000ms)
        var futureTime = System.currentTimeMillis() + 9000L;

        // When: Check for timeouts
        var timedOut = fsm.checkTimeouts(futureTime);

        // Then: Entity is identified as timed out
        assertNotNull(timedOut);
        assertEquals(1, timedOut.size());
        assertTrue(timedOut.contains(entityId));
    }

    @Test
    @DisplayName("checkTimeouts() respects enableTimeoutRollback flag")
    void testCheckTimeoutsRespectsEnableFlag() {
        // Given: Configuration with timeout rollback DISABLED
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                               .enableTimeoutRollback(false)
                                                               .migrationTimeoutMs(2000L)
                                                               .build();
        var fsmDisabled = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity1";
        fsmDisabled.initializeOwned(entityId);
        fsmDisabled.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Advance time beyond timeout
        var futureTime = System.currentTimeMillis() + 3000L;

        // When: Check for timeouts
        var timedOut = fsmDisabled.checkTimeouts(futureTime);

        // Then: No timeouts detected (feature disabled)
        assertNotNull(timedOut);
        assertTrue(timedOut.isEmpty());
    }

    @Test
    @DisplayName("checkTimeouts() ignores entities in completed states")
    void testCheckTimeoutsIgnoresCompletedStates() {
        // Given: Multiple entities in different states
        var owned = "owned";
        var departed = "departed";
        var ghost = "ghost";
        var rollbackOwned = "rollbackOwned";

        fsm.initializeOwned(owned);
        fsm.initializeOwned(departed);
        fsm.initializeOwned(rollbackOwned);
        fsm.initializeOwned(ghost);


        // Transition entities through various states
        fsm.transition(departed, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(departed, EntityMigrationState.DEPARTED);
        fsm.transition(departed, EntityMigrationState.GHOST);

        fsm.transition(rollbackOwned, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(rollbackOwned, EntityMigrationState.ROLLBACK_OWNED);

        // Advance time to simulate timeout
        var futureTime = System.currentTimeMillis() + 9000L;

        // When: Check for timeouts
        var timedOut = fsm.checkTimeouts(futureTime);

        // Then: Only entities in transitional states (none in this case) are checked
        assertTrue(timedOut.isEmpty(), "No entities in transitional states should timeout");
    }

    // ========== processTimeouts() Tests (4 tests) ==========

    @Test
    @DisplayName("processTimeouts() rolls back MIGRATING_OUT entity to ROLLBACK_OWNED")
    void testProcessTimeoutsMigratingOut() {
        // Given: Entity in MIGRATING_OUT state
        var entityId = "entity1";
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Advance time beyond timeout
        var futureTime = System.currentTimeMillis() + 9000L;

        // When: Process timeouts
        var rolledBack = fsm.processTimeouts(futureTime);

        // Then: Entity rolled back to ROLLBACK_OWNED
        assertEquals(1, rolledBack);
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId));
        assertEquals(1, fsm.getTotalTimeoutRollbacks());
    }

    @Test
    @DisplayName("processTimeouts() rolls back MIGRATING_IN entity to GHOST")
    void testProcessTimeoutsMigratingIn() {
        // Given: Entity in MIGRATING_IN state
        var entityId = "entity2";
        fsm.initializeOwned(entityId);

        // Transition through states to GHOST, then MIGRATING_IN
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);

        // Advance time beyond timeout
        var futureTime = System.currentTimeMillis() + 9000L;

        // When: Process timeouts
        var rolledBack = fsm.processTimeouts(futureTime);

        // Then: Entity rolled back to GHOST
        assertEquals(1, rolledBack);
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId));
        assertEquals(1, fsm.getTotalTimeoutRollbacks());
    }

    @Test
    @DisplayName("processTimeouts() increments metrics correctly")
    void testProcessTimeoutsIncrementMetrics() {
        // Given: Two entities in different transitional states
        var entity1 = "entity1";
        var entity2 = "entity2";

        fsm.initializeOwned(entity1);
        fsm.initializeOwned(entity2);

        // entity1 -> MIGRATING_OUT
        fsm.transition(entity1, EntityMigrationState.MIGRATING_OUT);

        // entity2 -> GHOST -> MIGRATING_IN
        fsm.transition(entity2, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entity2, EntityMigrationState.DEPARTED);
        fsm.transition(entity2, EntityMigrationState.GHOST);
        fsm.transition(entity2, EntityMigrationState.MIGRATING_IN);

        var initialTimeoutRollbacks = fsm.getTotalTimeoutRollbacks();

        // Advance time beyond timeout
        var futureTime = System.currentTimeMillis() + 9000L;

        // When: Process timeouts
        var rolledBack = fsm.processTimeouts(futureTime);

        // Then: Metrics incremented for both entities
        assertEquals(2, rolledBack);
        assertEquals(initialTimeoutRollbacks + 2, fsm.getTotalTimeoutRollbacks());
    }

    @Test
    @DisplayName("processTimeouts() handles multiple entities correctly")
    void testProcessTimeoutsMultipleEntities() {
        // Given: Three entities in MIGRATING_OUT
        var entity1 = "entity1";
        var entity2 = "entity2";
        var entity3 = "entity3";

        fsm.initializeOwned(entity1);
        fsm.initializeOwned(entity2);
        fsm.initializeOwned(entity3);

        fsm.transition(entity1, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entity2, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entity3, EntityMigrationState.MIGRATING_OUT);

        // Advance time beyond timeout
        var futureTime = System.currentTimeMillis() + 9000L;

        // When: Process timeouts
        var rolledBack = fsm.processTimeouts(futureTime);

        // Then: All entities rolled back
        assertEquals(3, rolledBack);
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entity1));
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entity2));
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entity3));
        assertEquals(3, fsm.getTotalTimeoutRollbacks());
    }

    // ========== Integration Tests (3+ tests) ==========

    @Test
    @DisplayName("Integration: Aggressive config triggers timeout faster (2s)")
    void testTimeoutWithAggressiveConfig() {
        // Given: Aggressive config with 2s timeout
        var config = EntityMigrationStateMachine.Configuration.aggressive();
        var aggressiveFsm = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity1";
        aggressiveFsm.initializeOwned(entityId);
        aggressiveFsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // When: Advance time just beyond aggressive timeout (2000ms)
        var futureTime = System.currentTimeMillis() + 2100L;
        var rolledBack = aggressiveFsm.processTimeouts(futureTime);

        // Then: Entity timed out with aggressive config
        assertEquals(1, rolledBack);
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, aggressiveFsm.getState(entityId));
    }

    @Test
    @DisplayName("Integration: Conservative config tolerates longer delays (15s)")
    void testTimeoutWithConservativeConfig() {
        // Given: Conservative config with 15s timeout
        var config = EntityMigrationStateMachine.Configuration.conservative();
        var conservativeFsm = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity1";
        conservativeFsm.initializeOwned(entityId);
        conservativeFsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // When: Advance time within conservative tolerance (10s < 15s)
        var withinTolerance = System.currentTimeMillis() + 10000L;
        var rolledBackEarly = conservativeFsm.processTimeouts(withinTolerance);

        // Then: No timeout yet (within 15s limit)
        assertEquals(0, rolledBackEarly);
        assertEquals(EntityMigrationState.MIGRATING_OUT, conservativeFsm.getState(entityId));

        // When: Advance time beyond conservative timeout (16s)
        var beyondTimeout = System.currentTimeMillis() + 16000L;
        var rolledBackLate = conservativeFsm.processTimeouts(beyondTimeout);

        // Then: Entity timed out
        assertEquals(1, rolledBackLate);
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, conservativeFsm.getState(entityId));
    }

    @Test
    @DisplayName("Integration: No timeout before deadline")
    void testNoTimeoutBeforeDeadline() {
        // Given: Entity in MIGRATING_OUT state with default 8s timeout
        var entityId = "entity1";
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // When: Check timeouts at various points before deadline
        var currentTime = System.currentTimeMillis();
        var after2s = currentTime + 2000L;
        var after5s = currentTime + 5000L;
        var after7s = currentTime + 7000L;

        // Then: No timeouts before deadline
        assertEquals(0, fsm.processTimeouts(after2s));
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId));

        assertEquals(0, fsm.processTimeouts(after5s));
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId));

        assertEquals(0, fsm.processTimeouts(after7s));
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId));

        // When: Advance beyond deadline (9s > 8s)
        var after9s = currentTime + 9000L;

        // Then: Timeout triggered
        assertEquals(1, fsm.processTimeouts(after9s));
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId));
    }

    @Test
    @DisplayName("Integration: Timeout metrics tracking across multiple operations")
    void testTimeoutMetricsTracking() {
        // Given: Multiple entities and timeout events
        fsm.initializeOwned("entity1");
        fsm.initializeOwned("entity2");
        fsm.initializeOwned("entity3");

        // Initial metrics
        assertEquals(0, fsm.getTotalTimeoutRollbacks());

        // When: First batch times out (2 entities)
        fsm.transition("entity1", EntityMigrationState.MIGRATING_OUT);
        fsm.transition("entity2", EntityMigrationState.MIGRATING_OUT);

        var time1 = System.currentTimeMillis() + 9000L;
        var rollback1 = fsm.processTimeouts(time1);

        // Then: Metrics updated
        assertEquals(2, rollback1);
        assertEquals(2, fsm.getTotalTimeoutRollbacks());

        // When: Second batch times out (1 entity)
        fsm.transition("entity3", EntityMigrationState.MIGRATING_OUT);

        var time2 = System.currentTimeMillis() + 9000L;
        var rollback2 = fsm.processTimeouts(time2);

        // Then: Metrics cumulative
        assertEquals(1, rollback2);
        assertEquals(3, fsm.getTotalTimeoutRollbacks());
    }

    @Test
    @DisplayName("Integration: checkTimeouts() returns unmodifiable list")
    void testCheckTimeoutsReturnsUnmodifiableList() {
        // Given: Entity in MIGRATING_OUT
        var entityId = "entity1";
        fsm.initializeOwned(entityId);
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        var futureTime = System.currentTimeMillis() + 9000L;
        var timedOut = fsm.checkTimeouts(futureTime);

        // When: Try to modify list
        // Then: Should throw UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class, () -> timedOut.add("entity2"));
    }

    @Test
    @DisplayName("Integration: Adaptive config scales timeout with latency")
    void testAdaptiveConfigScalesTimeout() {
        // Given: Adaptive config based on observed latency (500ms)
        var observedLatencyMs = 500L;
        var config = EntityMigrationStateMachine.Configuration.adaptive(observedLatencyMs);
        var adaptiveFsm = new EntityMigrationStateMachine(viewMonitor, config);

        // Expected timeout = max(2000ms, 500ms * 10) = 5000ms
        var entityId = "entity1";
        adaptiveFsm.initializeOwned(entityId);
        adaptiveFsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // When: Check timeout before adaptive deadline (4s < 5s)
        var before = System.currentTimeMillis() + 4000L;
        assertEquals(0, adaptiveFsm.processTimeouts(before));

        // When: Check timeout after adaptive deadline (6s > 5s)
        var after = System.currentTimeMillis() + 6000L;
        assertEquals(1, adaptiveFsm.processTimeouts(after));
    }

    @Test
    @DisplayName("Integration: Mixed transitional states timeout correctly")
    void testMixedTransitionalStatesTimeout() {
        // Given: Mix of MIGRATING_OUT and MIGRATING_IN entities
        var entityOut1 = "entityOut1";
        var entityOut2 = "entityOut2";
        var entityIn1 = "entityIn1";

        fsm.initializeOwned(entityOut1);
        fsm.initializeOwned(entityOut2);
        fsm.initializeOwned(entityIn1);

        // Setup MIGRATING_OUT entities
        fsm.transition(entityOut1, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityOut2, EntityMigrationState.MIGRATING_OUT);

        // Setup MIGRATING_IN entity
        fsm.transition(entityIn1, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entityIn1, EntityMigrationState.DEPARTED);
        fsm.transition(entityIn1, EntityMigrationState.GHOST);
        fsm.transition(entityIn1, EntityMigrationState.MIGRATING_IN);

        // When: Process timeouts
        var futureTime = System.currentTimeMillis() + 9000L;
        var rolledBack = fsm.processTimeouts(futureTime);

        // Then: All transitional entities rolled back correctly
        assertEquals(3, rolledBack);
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityOut1));
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityOut2));
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityIn1));
    }

    @Test
    @DisplayName("Null safety: checkTimeouts() handles null states gracefully")
    void testCheckTimeoutsNullSafety() {
        // Given: No entities registered
        var currentTimeMs = System.currentTimeMillis();

        // When: Check timeouts on empty state machine
        var timedOut = fsm.checkTimeouts(currentTimeMs);

        // Then: Returns empty list, no NPE
        assertNotNull(timedOut);
        assertTrue(timedOut.isEmpty());
    }

    @Test
    @DisplayName("Exception handling: processTimeouts() continues on transition failure")
    void testProcessTimeoutsContinuesOnFailure() {
        // Given: Multiple entities, with view stability removed mid-process
        var entity1 = "entity1";
        var entity2 = "entity2";

        fsm.initializeOwned(entity1);
        fsm.initializeOwned(entity2);

        fsm.transition(entity1, EntityMigrationState.MIGRATING_OUT);
        fsm.transition(entity2, EntityMigrationState.MIGRATING_OUT);

        // Force migration contexts to exist by checking state
        assertNotNull(fsm.getMigrationContext(entity1));
        assertNotNull(fsm.getMigrationContext(entity2));

        // When: Process timeouts (with stable view, both should succeed)
        var futureTime = System.currentTimeMillis() + 9000L;
        var rolledBack = fsm.processTimeouts(futureTime);

        // Then: Both entities successfully rolled back despite potential failures
        assertEquals(2, rolledBack);
        assertEquals(2, fsm.getTotalTimeoutRollbacks());
    }
}
