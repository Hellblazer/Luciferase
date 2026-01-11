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
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration and edge case tests for Phase 7D.1 timeout infrastructure (Day 6).
 *
 * Validates:
 * - Configuration presets work end-to-end (aggressive, conservative, adaptive)
 * - Edge cases handled correctly (zero timeout, disabled timeout, very long timeout)
 * - 2PC bridge integration with timeout events via MigrationStateListener
 * - Performance scaling with entity count (O(n) verification)
 * - Memory cleanup after migrations complete
 * - No false positives in timeout detection
 * - Full integration of timeout detection + rollback + metrics
 *
 * These tests complement EntityMigrationContextTest (configuration unit tests)
 * and EntityMigrationTimeoutProcessingTest (timeout processing unit tests)
 * by validating end-to-end behavior and edge cases.
 *
 * @author hal.hildebrand
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class EntityMigrationTimeoutIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EntityMigrationTimeoutIntegrationTest.class);

    private MockFirefliesView<String> view;
    private FirefliesViewMonitor viewMonitor;
    private EntityMigrationStateMachine fsm;

    @BeforeEach
    void setUp() {
        view = new MockFirefliesView<>();
        viewMonitor = new FirefliesViewMonitor(view, 3);
    }

    // ========================================================================
    // Configuration Integration Tests (3 tests)
    // ========================================================================

    @Test
    @DisplayName("Aggressive config timeout (2s) works end-to-end")
    void testAggressiveConfigTimeout() {
        // Given: FSM with aggressive configuration (2s timeout)
        var config = EntityMigrationStateMachine.Configuration.aggressive();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity-aggressive";
        fsm.initializeOwned(entityId);

        // When: Transition to MIGRATING_OUT at time T
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertTrue(result.success, "Transition to MIGRATING_OUT should succeed");

        var context = fsm.getMigrationContext(entityId);
        assertNotNull(context, "Migration context should exist");
        long startTimeMs = context.startTimeMs;

        // Then: Verify NO timeout at T+1999ms (1ms before deadline)
        var timedOut = fsm.checkTimeouts(startTimeMs + 1999L);
        assertTrue(timedOut.isEmpty(), "Should NOT timeout at T+1999ms");
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId),
                    "Should remain in MIGRATING_OUT before timeout");

        // And: Verify timeout at T+2000ms (at deadline)
        timedOut = fsm.checkTimeouts(startTimeMs + 2000L);
        assertEquals(1, timedOut.size(), "Should timeout at T+2000ms");
        assertTrue(timedOut.contains(entityId), "Entity should be in timeout list");

        // And: Process timeout triggers rollback
        int rolledBack = fsm.processTimeouts(startTimeMs + 2000L);
        assertEquals(1, rolledBack, "Should rollback 1 entity");
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId),
                    "Should transition to ROLLBACK_OWNED after timeout");
    }

    @Test
    @DisplayName("Conservative config timeout (15s) works end-to-end")
    void testConservativeConfigTimeout() {
        // Given: FSM with conservative configuration (15s timeout)
        var config = EntityMigrationStateMachine.Configuration.conservative();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity-conservative";
        fsm.initializeOwned(entityId);

        // When: Transition to MIGRATING_OUT
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertTrue(result.success);

        var context = fsm.getMigrationContext(entityId);
        long startTimeMs = context.startTimeMs;

        // Then: No timeout at T+14999ms
        var timedOut = fsm.checkTimeouts(startTimeMs + 14999L);
        assertTrue(timedOut.isEmpty(), "Should NOT timeout before 15s");

        // And: Timeout at T+15000ms
        timedOut = fsm.checkTimeouts(startTimeMs + 15000L);
        assertEquals(1, timedOut.size(), "Should timeout at T+15000ms");

        // And: Verify migration can complete successfully before timeout
        var entityId2 = "entity-completes";
        fsm.initializeOwned(entityId2);
        fsm.transition(entityId2, EntityMigrationState.MIGRATING_OUT);

        // Stabilize view for commit
        view.addMember("bubble1");
        for (int i = 1; i <= 5; i++) {  // Conservative config requires 5 stability ticks
            viewMonitor.onTick(i);
        }

        // Complete migration before timeout
        var context2 = fsm.getMigrationContext(entityId2);
        long startTime2 = context2.startTimeMs;
        var commitResult = fsm.transition(entityId2, EntityMigrationState.DEPARTED);
        assertTrue(commitResult.success, "Should complete migration before timeout");

        // Verify completed entity NOT in timeout list
        timedOut = fsm.checkTimeouts(startTime2 + 5000L);
        assertFalse(timedOut.contains(entityId2), "Completed migration should not timeout");
    }

    @Test
    @DisplayName("Adaptive config timeout scales correctly with latency")
    void testAdaptiveConfigTimeout() {
        // Given: Adaptive configuration with 500ms observed latency
        // Expected timeout = max(2000ms, 500ms * 10) = 5000ms
        var config = EntityMigrationStateMachine.Configuration.adaptive(500L);
        assertEquals(5000L, config.migrationTimeoutMs, "Adaptive timeout should be 10x latency");

        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity-adaptive";
        fsm.initializeOwned(entityId);

        // When: Start migration
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        var context = fsm.getMigrationContext(entityId);
        long startTimeMs = context.startTimeMs;

        // Then: No timeout at T+4999ms
        var timedOut = fsm.checkTimeouts(startTimeMs + 4999L);
        assertTrue(timedOut.isEmpty(), "Should NOT timeout before 5s");

        // And: Timeout at T+5000ms
        timedOut = fsm.checkTimeouts(startTimeMs + 5000L);
        assertEquals(1, timedOut.size(), "Should timeout at T+5000ms");

        // Verify adaptive() enforces 2000ms minimum for very low latencies
        var configLowLatency = EntityMigrationStateMachine.Configuration.adaptive(50L);
        assertEquals(2000L, configLowLatency.migrationTimeoutMs,
                    "Adaptive timeout should enforce 2000ms minimum");
    }

    // ========================================================================
    // Edge Case Tests (4 tests)
    // ========================================================================

    @Test
    @DisplayName("Zero timeout (1ms) causes immediate rollback")
    void testZeroTimeoutImmediateRollback() {
        // Given: Configuration with minimal 1ms timeout (effectively zero)
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                               .migrationTimeoutMs(1L)
                                                               .enableTimeoutRollback(true)
                                                               .requireViewStability(false)
                                                               .build();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity-zero-timeout";
        fsm.initializeOwned(entityId);

        // When: Transition to MIGRATING_OUT
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        var context = fsm.getMigrationContext(entityId);

        // Then: Immediately check timeouts (even at T+0)
        var timedOut = fsm.checkTimeouts(context.startTimeMs + 1L);
        assertEquals(1, timedOut.size(), "Should timeout immediately");

        // And: Process timeout immediately rolls back
        int rolledBack = fsm.processTimeouts(context.startTimeMs + 1L);
        assertEquals(1, rolledBack, "Should rollback immediately");
        assertEquals(EntityMigrationState.ROLLBACK_OWNED, fsm.getState(entityId));
    }

    @Test
    @DisplayName("Disabled timeout (enableTimeoutRollback=false) prevents rollback")
    void testDisabledTimeoutNoRollback() {
        // Given: Configuration with timeout detection DISABLED
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                               .migrationTimeoutMs(1000L)
                                                               .enableTimeoutRollback(false)  // Disabled
                                                               .requireViewStability(false)
                                                               .build();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity-disabled";
        fsm.initializeOwned(entityId);

        // When: Transition to MIGRATING_OUT and let it timeout
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        var context = fsm.getMigrationContext(entityId);
        long timeoutTime = context.startTimeMs + 2000L;  // Well past timeout

        // Then: checkTimeouts() returns empty list (feature disabled)
        var timedOut = fsm.checkTimeouts(timeoutTime);
        assertTrue(timedOut.isEmpty(), "checkTimeouts() should return empty when disabled");

        // And: processTimeouts() does nothing (no rollback)
        int rolledBack = fsm.processTimeouts(timeoutTime);
        assertEquals(0, rolledBack, "Should not rollback when timeout disabled");

        // And: Entity remains in MIGRATING_OUT (no rollback occurred)
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId),
                    "Should remain in MIGRATING_OUT when timeout disabled");
    }

    @Test
    @DisplayName("Very long timeout (60s) has no false positives")
    void testVeryLongTimeoutNoFalsePositives() {
        // Given: Configuration with very long 60s timeout
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                               .migrationTimeoutMs(60000L)
                                                               .enableTimeoutRollback(true)
                                                               .requireViewStability(false)
                                                               .build();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity-long-timeout";
        fsm.initializeOwned(entityId);

        // When: Start migration at time T
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        var context = fsm.getMigrationContext(entityId);
        long startTimeMs = context.startTimeMs;

        // Then: Check at T+30000 (halfway through 60s timeout)
        var timedOut = fsm.checkTimeouts(startTimeMs + 30000L);
        assertTrue(timedOut.isEmpty(), "Should NOT timeout at T+30s (halfway)");
        assertFalse(timedOut.contains(entityId), "Entity should NOT be in timeout list");

        // And: Entity remains in MIGRATING_OUT state
        assertEquals(EntityMigrationState.MIGRATING_OUT, fsm.getState(entityId),
                    "Should remain in MIGRATING_OUT before timeout");

        // And: Multiple checks don't cause false positives
        for (int i = 0; i < 10; i++) {
            long checkTime = startTimeMs + (i * 5000L);  // Check every 5s
            timedOut = fsm.checkTimeouts(checkTime);
            assertTrue(timedOut.isEmpty(), "No false positives at check " + i);
        }

        // Finally: Verify actual timeout at T+60000
        timedOut = fsm.checkTimeouts(startTimeMs + 60000L);
        assertEquals(1, timedOut.size(), "Should timeout at T+60000ms");
    }

    @Test
    @DisplayName("Timeout does not affect completed migrations (GHOST state)")
    void testTimeoutDoesNotAffectCompleted() {
        // Given: FSM with short timeout for testing
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                               .migrationTimeoutMs(2000L)
                                                               .enableTimeoutRollback(true)
                                                               .requireViewStability(false)
                                                               .build();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        var entityId = "entity-completed";
        fsm.initializeOwned(entityId);

        // When: Start migration
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        var context = fsm.getMigrationContext(entityId);
        long startTimeMs = context.startTimeMs;

        // And: Complete migration: MIGRATING_OUT → DEPARTED → GHOST
        fsm.transition(entityId, EntityMigrationState.DEPARTED);
        fsm.transition(entityId, EntityMigrationState.GHOST);

        // Then: Check timeouts AFTER original timeout deadline
        long afterTimeout = startTimeMs + 3000L;  // Well past 2s timeout
        var timedOut = fsm.checkTimeouts(afterTimeout);

        // Verify GHOST entity NOT in timeout list
        assertFalse(timedOut.contains(entityId), "GHOST entity should not be in timeout list");
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId),
                    "GHOST state should be unchanged");

        // And: processTimeouts() doesn't affect GHOST entity
        int rolledBack = fsm.processTimeouts(afterTimeout);
        assertEquals(0, rolledBack, "Should not rollback GHOST entity");
        assertEquals(EntityMigrationState.GHOST, fsm.getState(entityId),
                    "GHOST state should remain unchanged");
    }

    // ========================================================================
    // 2PC Bridge Integration Tests (2 tests)
    // ========================================================================

    @Test
    @DisplayName("Timeout rollback integrates with MigrationCoordinator via listener")
    void testTimeoutWith2PCBridge() {
        // Given: FSM with short timeout and listener for 2PC bridge
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                               .migrationTimeoutMs(1000L)
                                                               .enableTimeoutRollback(true)
                                                               .requireViewStability(false)
                                                               .build();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        // Track state transitions via listener (simulates MigrationCoordinator)
        var transitions = new ArrayList<String>();
        var timeoutRollbacks = new AtomicInteger(0);

        fsm.addListener(new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                               EntityMigrationState toState,
                                               EntityMigrationStateMachine.TransitionResult result) {
                transitions.add(String.format("%s: %s → %s", entityId, fromState, toState));

                // Detect timeout-triggered rollback
                if (fromState == EntityMigrationState.MIGRATING_OUT &&
                    toState == EntityMigrationState.ROLLBACK_OWNED) {
                    timeoutRollbacks.incrementAndGet();
                }
            }

            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
                // Not used in this test
            }
        });

        var entityId = "entity-2pc";
        fsm.initializeOwned(entityId);

        // When: Start migration and let it timeout
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        var context = fsm.getMigrationContext(entityId);
        long timeoutTime = context.startTimeMs + 1500L;

        // Process timeout
        fsm.processTimeouts(timeoutTime);

        // Then: Verify listener received transition notifications
        assertTrue(transitions.size() >= 2, "Should have at least 2 transitions");
        assertTrue(transitions.stream().anyMatch(t -> t.contains("OWNED → MIGRATING_OUT")),
                  "Should track initial transition");
        assertTrue(transitions.stream().anyMatch(t -> t.contains("MIGRATING_OUT → ROLLBACK_OWNED")),
                  "Should track timeout rollback");

        // And: Verify timeout-specific rollback detected
        assertEquals(1, timeoutRollbacks.get(), "Should detect 1 timeout rollback");

        // And: Verify FSM metrics match listener observations
        assertEquals(1L, fsm.getTotalTimeoutRollbacks(), "FSM should track timeout rollback");
    }

    @Test
    @DisplayName("Multiple listeners all receive timeout notifications")
    void testMultipleListenersReceiveTimeoutNotifications() {
        // Given: FSM with multiple listeners
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                               .migrationTimeoutMs(500L)
                                                               .enableTimeoutRollback(true)
                                                               .requireViewStability(false)
                                                               .build();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        var listener1Calls = new AtomicInteger(0);
        var listener2Calls = new AtomicInteger(0);
        var listener3Calls = new AtomicInteger(0);

        fsm.addListener(new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                               EntityMigrationState toState,
                                               EntityMigrationStateMachine.TransitionResult result) {
                listener1Calls.incrementAndGet();
            }
            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {}
        });

        fsm.addListener(new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                               EntityMigrationState toState,
                                               EntityMigrationStateMachine.TransitionResult result) {
                listener2Calls.incrementAndGet();
            }
            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {}
        });

        fsm.addListener(new MigrationStateListener() {
            @Override
            public void onEntityStateTransition(Object entityId, EntityMigrationState fromState,
                                               EntityMigrationState toState,
                                               EntityMigrationStateMachine.TransitionResult result) {
                listener3Calls.incrementAndGet();
            }
            @Override
            public void onViewChangeRollback(int rolledBackCount, int ghostCount) {}
        });

        assertEquals(3, fsm.getListenerCount(), "Should have 3 listeners");

        var entityId = "entity-multi-listener";
        fsm.initializeOwned(entityId);

        // When: Trigger timeout rollback
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        var context = fsm.getMigrationContext(entityId);
        fsm.processTimeouts(context.startTimeMs + 600L);

        // Then: All 3 listeners received both transitions
        assertEquals(2, listener1Calls.get(), "Listener 1 should receive 2 transitions");
        assertEquals(2, listener2Calls.get(), "Listener 2 should receive 2 transitions");
        assertEquals(2, listener3Calls.get(), "Listener 3 should receive 2 transitions");
    }

    // ========================================================================
    // Performance Tests (2 tests)
    // ========================================================================

    @Test
    @DisplayName("Timeout performance scaling: O(n) acceptable with 1000 entities")
    void testTimeoutPerformanceScalingCheck() {
        // Given: FSM with 1000 entities in various states
        var config = EntityMigrationStateMachine.Configuration.builder()
                                                               .migrationTimeoutMs(5000L)
                                                               .enableTimeoutRollback(true)
                                                               .requireViewStability(false)
                                                               .build();
        fsm = new EntityMigrationStateMachine(viewMonitor, config);

        // Create 1000 entities in different states
        int totalEntities = 1000;
        int migrating = 0;
        long startTimeMs = System.currentTimeMillis();

        for (int i = 0; i < totalEntities; i++) {
            var entityId = "entity-" + i;
            fsm.initializeOwned(entityId);

            // 40% in MIGRATING_OUT, 60% in other states
            if (i % 5 < 2) {
                fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
                migrating++;
            }
        }

        log.info("Created {} entities, {} in MIGRATING_OUT state", totalEntities, migrating);

        // When: Measure checkTimeouts() execution time
        long checkStart = System.nanoTime();
        var timedOut = fsm.checkTimeouts(startTimeMs + 6000L);  // All should timeout
        long checkDuration = System.nanoTime() - checkStart;

        // Then: Verify performance (< 10ms for 1000 entities = ~1ms per 100)
        double checkMs = checkDuration / 1_000_000.0;
        log.info("checkTimeouts() took {}ms for {} entities ({} in migration)",
                String.format("%.3f", checkMs), totalEntities, migrating);

        assertTrue(checkMs < 10.0, String.format("checkTimeouts() should complete in <10ms, took %.3fms", checkMs));
        assertEquals(migrating, timedOut.size(), "Should find all MIGRATING_OUT entities");

        // And: Measure processTimeouts() execution time
        long processStart = System.nanoTime();
        int rolledBack = fsm.processTimeouts(startTimeMs + 6000L);
        long processDuration = System.nanoTime() - processStart;

        double processMs = processDuration / 1_000_000.0;
        log.info("processTimeouts() took {}ms to rollback {} entities", String.format("%.3f", processMs), rolledBack);

        assertTrue(processMs < 50.0, String.format("processTimeouts() should complete in <50ms, took %.3fms", processMs));
        assertEquals(migrating, rolledBack, "Should rollback all timed-out entities");
    }

    @Test
    @DisplayName("No memory leak in timeout tracking after many migrations")
    void testNoMemoryLeakInTimeoutTracking() {
        // Given: FSM with default config
        fsm = new EntityMigrationStateMachine(viewMonitor);

        int migrations = 500;
        var entityIds = new ArrayList<String>();

        // When: Create and complete many migrations
        for (int i = 0; i < migrations; i++) {
            var entityId = "entity-" + i;
            entityIds.add(entityId);
            fsm.initializeOwned(entityId);

            // Transition through complete lifecycle: OWNED → MIGRATING_OUT → DEPARTED → GHOST
            fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
            fsm.transition(entityId, EntityMigrationState.DEPARTED);
            fsm.transition(entityId, EntityMigrationState.GHOST);
        }

        // Then: Verify entity count is correct
        assertEquals(migrations, fsm.getEntityCount(), "Should track all entities");

        // And: Verify migration contexts cleaned up for completed migrations
        int contextsRemaining = 0;
        for (var entityId : entityIds) {
            if (fsm.getMigrationContext(entityId) != null) {
                contextsRemaining++;
            }
        }

        // GHOST entities should have contexts cleared (not in migration)
        assertEquals(0, contextsRemaining,
                    "GHOST entities should have migration contexts cleared");

        // And: Verify metrics accurate after cleanup
        assertEquals(0, fsm.getEntitiesInMigration(),
                    "No entities should be in migration after all completed");

        long expectedTransitions = migrations * 3;  // 3 transitions per entity
        assertEquals(expectedTransitions, fsm.getTotalTransitions(),
                    "Should track all transitions");
    }

    // ========================================================================
    // Configuration Presets Test (1 test)
    // ========================================================================

    @Test
    @DisplayName("All configuration presets initialize correctly")
    void testConfigurationPresetsWork() {
        // Test default configuration
        var defaultConfig = EntityMigrationStateMachine.Configuration.defaultConfig();
        assertEquals(8000L, defaultConfig.migrationTimeoutMs, "Default timeout should be 8s");
        assertEquals(3, defaultConfig.minStabilityTicks, "Default min stability should be 3");
        assertEquals(100, defaultConfig.rollbackTimeoutTicks, "Default rollback timeout should be 100");
        assertEquals(3, defaultConfig.maxRetries, "Default max retries should be 3");
        assertTrue(defaultConfig.requireViewStability, "Default should require view stability");
        assertTrue(defaultConfig.enableTimeoutRollback, "Default should enable timeout rollback");

        // Test aggressive configuration
        var aggressive = EntityMigrationStateMachine.Configuration.aggressive();
        assertEquals(2000L, aggressive.migrationTimeoutMs, "Aggressive timeout should be 2s");
        assertEquals(2, aggressive.minStabilityTicks, "Aggressive min stability should be 2");
        assertEquals(50, aggressive.rollbackTimeoutTicks, "Aggressive rollback timeout should be 50");
        assertEquals(2, aggressive.maxRetries, "Aggressive max retries should be 2");
        assertTrue(aggressive.requireViewStability);
        assertTrue(aggressive.enableTimeoutRollback);

        // Test conservative configuration
        var conservative = EntityMigrationStateMachine.Configuration.conservative();
        assertEquals(15000L, conservative.migrationTimeoutMs, "Conservative timeout should be 15s");
        assertEquals(5, conservative.minStabilityTicks, "Conservative min stability should be 5");
        assertEquals(200, conservative.rollbackTimeoutTicks, "Conservative rollback timeout should be 200");
        assertEquals(5, conservative.maxRetries, "Conservative max retries should be 5");
        assertTrue(conservative.requireViewStability);
        assertTrue(conservative.enableTimeoutRollback);

        // Test adaptive configurations with various latencies
        var adaptive100 = EntityMigrationStateMachine.Configuration.adaptive(100L);
        assertEquals(2000L, adaptive100.migrationTimeoutMs, "Adaptive(100ms) should enforce 2s minimum");

        var adaptive500 = EntityMigrationStateMachine.Configuration.adaptive(500L);
        assertEquals(5000L, adaptive500.migrationTimeoutMs, "Adaptive(500ms) should be 10x = 5s");

        var adaptive1000 = EntityMigrationStateMachine.Configuration.adaptive(1000L);
        assertEquals(10000L, adaptive1000.migrationTimeoutMs, "Adaptive(1000ms) should be 10x = 10s");

        // Test builder pattern
        var custom = EntityMigrationStateMachine.Configuration.builder()
                                                               .migrationTimeoutMs(12345L)
                                                               .minStabilityTicks(7)
                                                               .maxRetries(4)
                                                               .requireViewStability(false)
                                                               .enableTimeoutRollback(false)
                                                               .rollbackTimeoutTicks(150)
                                                               .build();

        assertEquals(12345L, custom.migrationTimeoutMs, "Builder should set custom timeout");
        assertEquals(7, custom.minStabilityTicks, "Builder should set custom stability ticks");
        assertEquals(4, custom.maxRetries, "Builder should set custom max retries");
        assertEquals(150, custom.rollbackTimeoutTicks, "Builder should set custom rollback timeout");
        assertFalse(custom.requireViewStability, "Builder should set custom view stability");
        assertFalse(custom.enableTimeoutRollback, "Builder should set custom timeout rollback");
    }
}
