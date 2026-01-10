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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MigrationContext time tracking and Configuration presets (Phase 7D.1 Part 1).
 *
 * Validates:
 * - Wall clock time tracking in MigrationContext
 * - Timeout calculation methods
 * - Configuration presets (default, aggressive, conservative, adaptive)
 * - Builder pattern for custom configurations
 * - Integration with EntityMigrationStateMachine
 *
 * @author hal.hildebrand
 */
class EntityMigrationContextTest {

    // ========================================================================
    // MigrationContext Tests (6 tests)
    // ========================================================================

    @Test
    void testMigrationContextTimeFields() {
        // Given
        var entityId = "entity1";
        long startTimeMs = 1000L;
        long timeoutMs = 9000L;
        var originState = EntityMigrationState.OWNED;

        // When
        var context = new EntityMigrationStateMachine.MigrationContext(
            entityId, 100L, originState, startTimeMs, timeoutMs
        );

        // Then
        assertEquals(entityId, context.entityId, "Entity ID should match");
        assertEquals(100L, context.startTimeTicks, "Start time ticks should match");
        assertEquals(originState, context.originState, "Origin state should match");
        assertEquals(startTimeMs, context.startTimeMs, "Start time MS should match");
        assertEquals(timeoutMs, context.timeoutMs, "Timeout MS should match");
        assertEquals(0, context.retryCount, "Retry count should start at 0");
    }

    @Test
    void testIsTimedOutTrue() {
        // Given
        var context = new EntityMigrationStateMachine.MigrationContext(
            "entity1", 100L, EntityMigrationState.OWNED, 1000L, 9000L
        );

        // When/Then - At exact deadline
        assertTrue(context.isTimedOut(9000L), "Should timeout at exact deadline");

        // When/Then - After deadline
        assertTrue(context.isTimedOut(9001L), "Should timeout after deadline");
        assertTrue(context.isTimedOut(10000L), "Should timeout well after deadline");
    }

    @Test
    void testIsTimedOutFalse() {
        // Given
        var context = new EntityMigrationStateMachine.MigrationContext(
            "entity1", 100L, EntityMigrationState.OWNED, 1000L, 9000L
        );

        // When/Then - Before deadline
        assertFalse(context.isTimedOut(8999L), "Should not timeout before deadline");
        assertFalse(context.isTimedOut(5000L), "Should not timeout well before deadline");
        assertFalse(context.isTimedOut(1000L), "Should not timeout at start time");
    }

    @Test
    void testRemainingTimeMs() {
        // Given
        var context = new EntityMigrationStateMachine.MigrationContext(
            "entity1", 100L, EntityMigrationState.OWNED, 1000L, 9000L
        );

        // When/Then - Various points in time
        assertEquals(8000L, context.remainingTimeMs(1000L), "Remaining should be 8000ms at start");
        assertEquals(7000L, context.remainingTimeMs(2000L), "Remaining should be 7000ms after 1s");
        assertEquals(1000L, context.remainingTimeMs(8000L), "Remaining should be 1000ms near end");
        assertEquals(0L, context.remainingTimeMs(9000L), "Remaining should be 0ms at deadline");
        assertEquals(0L, context.remainingTimeMs(10000L), "Remaining should be 0ms after deadline (not negative)");
    }

    @Test
    void testElapsedTimeMs() {
        // Given
        var context = new EntityMigrationStateMachine.MigrationContext(
            "entity1", 100L, EntityMigrationState.OWNED, 1000L, 9000L
        );

        // When/Then - Various points in time
        assertEquals(0L, context.elapsedTimeMs(1000L), "Elapsed should be 0ms at start");
        assertEquals(1000L, context.elapsedTimeMs(2000L), "Elapsed should be 1000ms after 1s");
        assertEquals(7000L, context.elapsedTimeMs(8000L), "Elapsed should be 7000ms near end");
        assertEquals(8000L, context.elapsedTimeMs(9000L), "Elapsed should be 8000ms at deadline");
        assertEquals(9000L, context.elapsedTimeMs(10000L), "Elapsed should be 9000ms after deadline");
    }

    @Test
    void testRetryCountIncrement() {
        // Given
        var context = new EntityMigrationStateMachine.MigrationContext(
            "entity1", 100L, EntityMigrationState.OWNED, 1000L, 9000L
        );

        // When/Then - Initial state
        assertEquals(0, context.retryCount, "Retry count should start at 0");

        // When - Increment retries
        context.retryCount++;
        assertEquals(1, context.retryCount, "Retry count should be 1 after increment");

        context.retryCount++;
        assertEquals(2, context.retryCount, "Retry count should be 2 after second increment");

        context.retryCount += 3;
        assertEquals(5, context.retryCount, "Retry count should be 5 after adding 3");
    }

    // ========================================================================
    // Configuration Tests (6 tests)
    // ========================================================================

    @Test
    void testDefaultConfiguration() {
        // When
        var config = EntityMigrationStateMachine.Configuration.defaultConfig();

        // Then
        assertTrue(config.requireViewStability, "Default should require view stability");
        assertEquals(100, config.rollbackTimeoutTicks, "Default rollback timeout should be 100 ticks");
        assertEquals(8000L, config.migrationTimeoutMs, "Default migration timeout should be 8000ms");
        assertEquals(3, config.minStabilityTicks, "Default min stability should be 3 ticks");
        assertTrue(config.enableTimeoutRollback, "Default should enable timeout rollback");
        assertEquals(3, config.maxRetries, "Default max retries should be 3");
    }

    @Test
    void testAggressiveConfiguration() {
        // When
        var config = EntityMigrationStateMachine.Configuration.aggressive();

        // Then
        assertTrue(config.requireViewStability, "Aggressive should require view stability");
        assertEquals(50, config.rollbackTimeoutTicks, "Aggressive rollback timeout should be 50 ticks");
        assertEquals(2000L, config.migrationTimeoutMs, "Aggressive migration timeout should be 2000ms");
        assertEquals(2, config.minStabilityTicks, "Aggressive min stability should be 2 ticks");
        assertTrue(config.enableTimeoutRollback, "Aggressive should enable timeout rollback");
        assertEquals(2, config.maxRetries, "Aggressive max retries should be 2");
    }

    @Test
    void testConservativeConfiguration() {
        // When
        var config = EntityMigrationStateMachine.Configuration.conservative();

        // Then
        assertTrue(config.requireViewStability, "Conservative should require view stability");
        assertEquals(200, config.rollbackTimeoutTicks, "Conservative rollback timeout should be 200 ticks");
        assertEquals(15000L, config.migrationTimeoutMs, "Conservative migration timeout should be 15000ms");
        assertEquals(5, config.minStabilityTicks, "Conservative min stability should be 5 ticks");
        assertTrue(config.enableTimeoutRollback, "Conservative should enable timeout rollback");
        assertEquals(5, config.maxRetries, "Conservative max retries should be 5");
    }

    @Test
    void testAdaptiveConfiguration() {
        // When - High latency scenario
        var adaptive1 = EntityMigrationStateMachine.Configuration.adaptive(1000L);

        // Then
        assertEquals(10000L, adaptive1.migrationTimeoutMs,
            "Adaptive timeout should be 10x observed latency (1000ms -> 10000ms)");

        // When - Low latency scenario (below minimum)
        var adaptive2 = EntityMigrationStateMachine.Configuration.adaptive(100L);

        // Then
        assertEquals(2000L, adaptive2.migrationTimeoutMs,
            "Adaptive timeout should not go below 2000ms minimum (100ms -> 2000ms)");

        // When - Edge case: Very high latency
        var adaptive3 = EntityMigrationStateMachine.Configuration.adaptive(5000L);

        // Then
        assertEquals(50000L, adaptive3.migrationTimeoutMs,
            "Adaptive timeout should scale linearly (5000ms -> 50000ms)");
    }

    @Test
    void testConfigurationBuilder() {
        // When
        var config = EntityMigrationStateMachine.Configuration.builder()
            .migrationTimeoutMs(5000L)
            .maxRetries(5)
            .enableTimeoutRollback(false)
            .minStabilityTicks(10)
            .rollbackTimeoutTicks(150)
            .requireViewStability(false)
            .build();

        // Then
        assertEquals(5000L, config.migrationTimeoutMs, "Builder should set migration timeout");
        assertEquals(5, config.maxRetries, "Builder should set max retries");
        assertFalse(config.enableTimeoutRollback, "Builder should set timeout rollback flag");
        assertEquals(10, config.minStabilityTicks, "Builder should set min stability ticks");
        assertEquals(150, config.rollbackTimeoutTicks, "Builder should set rollback timeout ticks");
        assertFalse(config.requireViewStability, "Builder should set view stability requirement");
    }

    @Test
    void testConfigurationBuilderDefaults() {
        // When - Use builder with no customization
        var config = EntityMigrationStateMachine.Configuration.builder().build();

        // Then - Should match default configuration
        assertTrue(config.requireViewStability, "Builder defaults should require view stability");
        assertEquals(100, config.rollbackTimeoutTicks, "Builder defaults should have 100 rollback timeout ticks");
        assertEquals(8000L, config.migrationTimeoutMs, "Builder defaults should have 8000ms migration timeout");
        assertEquals(3, config.minStabilityTicks, "Builder defaults should have 3 min stability ticks");
        assertTrue(config.enableTimeoutRollback, "Builder defaults should enable timeout rollback");
        assertEquals(3, config.maxRetries, "Builder defaults should have 3 max retries");
    }

    // ========================================================================
    // Integration Tests (3+ tests)
    // ========================================================================

    @Test
    void testMigrationContextCreation() {
        // Given
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 3);
        var config = EntityMigrationStateMachine.Configuration.defaultConfig();
        var fsm = new EntityMigrationStateMachine(monitor, config);

        var entityId = "testEntity";
        fsm.initializeOwned(entityId);

        long startTime = System.currentTimeMillis();

        // When - Transition to MIGRATING_OUT
        var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        // Then
        assertTrue(result.success, "Transition should succeed");

        var context = fsm.getMigrationContext(entityId);
        assertNotNull(context, "Migration context should be created");
        assertEquals(entityId, context.entityId, "Context should track correct entity");
        assertEquals(EntityMigrationState.OWNED, context.originState, "Context should track origin state");
        assertTrue(context.startTimeMs >= startTime, "Start time should be recent");
        assertTrue(context.timeoutMs > context.startTimeMs, "Timeout should be after start");
        assertEquals(8000L, context.timeoutMs - context.startTimeMs,
            "Timeout should be startTime + 8000ms (default config)");
    }

    @Test
    void testMigrationContextCleanup() {
        // Given
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 3);
        var config = EntityMigrationStateMachine.Configuration.defaultConfig();
        var fsm = new EntityMigrationStateMachine(monitor, config);

        var entityId = "testEntity";
        fsm.initializeOwned(entityId);

        // When - Transition to MIGRATING_OUT (creates context)
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
        assertNotNull(fsm.getMigrationContext(entityId), "Context should exist during migration");

        // When - Transition to terminal state DEPARTED
        fsm.transition(entityId, EntityMigrationState.DEPARTED);

        // Then - Context should be cleaned up
        assertNull(fsm.getMigrationContext(entityId), "Context should be removed on DEPARTED");

        // Given - Start new migration
        var entityId2 = "testEntity2";
        fsm.initializeOwned(entityId2);
        fsm.transition(entityId2, EntityMigrationState.MIGRATING_OUT);
        assertNotNull(fsm.getMigrationContext(entityId2), "Context should exist during migration");

        // When - Transition to terminal state ROLLBACK_OWNED
        fsm.transition(entityId2, EntityMigrationState.ROLLBACK_OWNED);

        // Then - Context should be cleaned up
        assertNull(fsm.getMigrationContext(entityId2), "Context should be removed on ROLLBACK_OWNED");
    }

    @Test
    void testMigrationContextTimeout() {
        // Given
        var view = new MockFirefliesView<String>();
        var monitor = new FirefliesViewMonitor(view, 3);
        var config = EntityMigrationStateMachine.Configuration.builder()
            .migrationTimeoutMs(1000L)  // 1 second timeout
            .build();
        var fsm = new EntityMigrationStateMachine(monitor, config);

        var entityId = "testEntity";
        fsm.initializeOwned(entityId);

        long startTime = System.currentTimeMillis();

        // When - Transition to MIGRATING_OUT
        fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

        var context = fsm.getMigrationContext(entityId);
        assertNotNull(context, "Context should exist");

        // Then - Verify timeout calculation
        assertEquals(1000L, context.timeoutMs - context.startTimeMs,
            "Timeout should match configured value (1000ms)");

        assertFalse(context.isTimedOut(startTime), "Should not timeout immediately");
        assertFalse(context.isTimedOut(startTime + 500), "Should not timeout at halfway point");
        assertTrue(context.isTimedOut(startTime + 1000), "Should timeout at deadline");
        assertTrue(context.isTimedOut(startTime + 2000), "Should timeout after deadline");
    }

    @Test
    void testFormatTimeInfo() {
        // Given
        var context = new EntityMigrationStateMachine.MigrationContext(
            "entity1", 100L, EntityMigrationState.OWNED, 1000L, 9000L
        );
        context.retryCount = 2;

        // When
        var formatted = context.formatTimeInfo(3000L);

        // Then
        assertTrue(formatted.contains("elapsed=2000ms"), "Should show elapsed time");
        assertTrue(formatted.contains("remaining=6000ms"), "Should show remaining time");
        assertTrue(formatted.contains("retries=2"), "Should show retry count");
    }

    @Test
    void testConfigurationComparison() {
        // Given
        var aggressive = EntityMigrationStateMachine.Configuration.aggressive();
        var conservative = EntityMigrationStateMachine.Configuration.conservative();
        var defaultConfig = EntityMigrationStateMachine.Configuration.defaultConfig();

        // Then - Verify ordering: aggressive < default < conservative
        assertTrue(aggressive.migrationTimeoutMs < defaultConfig.migrationTimeoutMs,
            "Aggressive should have shorter timeout than default");
        assertTrue(defaultConfig.migrationTimeoutMs < conservative.migrationTimeoutMs,
            "Default should have shorter timeout than conservative");

        assertTrue(aggressive.maxRetries < defaultConfig.maxRetries,
            "Aggressive should have fewer retries than default");
        assertTrue(defaultConfig.maxRetries < conservative.maxRetries,
            "Default should have fewer retries than conservative");

        assertTrue(aggressive.minStabilityTicks < defaultConfig.minStabilityTicks,
            "Aggressive should have lower stability requirement than default");
        assertTrue(defaultConfig.minStabilityTicks < conservative.minStabilityTicks,
            "Default should have lower stability requirement than conservative");
    }
}
