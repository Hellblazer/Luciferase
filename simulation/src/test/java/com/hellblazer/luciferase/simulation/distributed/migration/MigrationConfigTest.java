/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.distributed.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for MigrationConfig (Luciferase-65qu).
 * <p>
 * Verifies:
 * - defaults() profile for typical LAN deployments
 * - forHighLatency() profile for WAN or slow storage
 * - forLowLatency() profile for fast local deployments
 * - Validation constraints enforce timeout relationships
 *
 * @author hal.hildebrand
 */
class MigrationConfigTest {

    /**
     * Test: defaults() profile has expected values for LAN deployments.
     */
    @Test
    void testDefaultsProfile() {
        var config = MigrationConfig.defaults();

        assertEquals(100, config.phaseTimeoutMs(), "Default phase timeout");
        assertEquals(300, config.totalTimeoutMs(), "Default total timeout");
        assertEquals(50, config.lockTimeoutMs(), "Default lock timeout");
        assertEquals(5_000_000, config.lockRetryIntervalNs(), "Default lock retry interval");
        assertEquals(10, config.maxLockRetries(), "Default max lock retries");

        // Verify relationships
        assertTrue(config.totalTimeoutMs() >= config.phaseTimeoutMs() * 3,
                  "Total timeout must accommodate prepare+commit+abort");
        assertTrue(config.lockTimeoutMs() < config.phaseTimeoutMs(),
                  "Lock timeout must be less than phase timeout");
    }

    /**
     * Test: forHighLatency() profile has relaxed timeouts for WAN.
     */
    @Test
    void testHighLatencyProfile() {
        var config = MigrationConfig.forHighLatency();

        assertEquals(500, config.phaseTimeoutMs(), "High-latency phase timeout");
        assertEquals(1500, config.totalTimeoutMs(), "High-latency total timeout");
        assertEquals(250, config.lockTimeoutMs(), "High-latency lock timeout");
        assertEquals(10_000_000, config.lockRetryIntervalNs(), "High-latency lock retry interval");
        assertEquals(25, config.maxLockRetries(), "High-latency max lock retries");

        // Verify relationships
        assertTrue(config.totalTimeoutMs() >= config.phaseTimeoutMs() * 3,
                  "Total timeout must accommodate prepare+commit+abort");
        assertTrue(config.lockTimeoutMs() < config.phaseTimeoutMs(),
                  "Lock timeout must be less than phase timeout");

        // Verify it's more relaxed than defaults
        var defaults = MigrationConfig.defaults();
        assertTrue(config.phaseTimeoutMs() > defaults.phaseTimeoutMs(),
                  "High-latency should have longer phase timeout than defaults");
        assertTrue(config.totalTimeoutMs() > defaults.totalTimeoutMs(),
                  "High-latency should have longer total timeout than defaults");
    }

    /**
     * Test: forLowLatency() profile has aggressive timeouts for fast local deployments.
     */
    @Test
    void testLowLatencyProfile() {
        var config = MigrationConfig.forLowLatency();

        assertEquals(50, config.phaseTimeoutMs(), "Low-latency phase timeout");
        assertEquals(150, config.totalTimeoutMs(), "Low-latency total timeout");
        assertEquals(25, config.lockTimeoutMs(), "Low-latency lock timeout");
        assertEquals(2_500_000, config.lockRetryIntervalNs(), "Low-latency lock retry interval");
        assertEquals(10, config.maxLockRetries(), "Low-latency max lock retries");

        // Verify relationships
        assertTrue(config.totalTimeoutMs() >= config.phaseTimeoutMs() * 3,
                  "Total timeout must accommodate prepare+commit+abort");
        assertTrue(config.lockTimeoutMs() < config.phaseTimeoutMs(),
                  "Lock timeout must be less than phase timeout");

        // Verify it's more aggressive than defaults
        var defaults = MigrationConfig.defaults();
        assertTrue(config.phaseTimeoutMs() < defaults.phaseTimeoutMs(),
                  "Low-latency should have shorter phase timeout than defaults");
        assertTrue(config.totalTimeoutMs() < defaults.totalTimeoutMs(),
                  "Low-latency should have shorter total timeout than defaults");
    }

    /**
     * Test: Validation rejects negative phaseTimeoutMs.
     */
    @Test
    void testValidationRejectsNegativePhaseTimeout() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new MigrationConfig(-1, 300, 50, 5_000_000, 10);
        });
        assertTrue(exception.getMessage().contains("phaseTimeoutMs must be > 0"),
                  "Exception should mention phaseTimeoutMs: " + exception.getMessage());
    }

    /**
     * Test: Validation rejects negative totalTimeoutMs.
     */
    @Test
    void testValidationRejectsNegativeTotalTimeout() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new MigrationConfig(100, -1, 50, 5_000_000, 10);
        });
        assertTrue(exception.getMessage().contains("totalTimeoutMs must be > 0"),
                  "Exception should mention totalTimeoutMs: " + exception.getMessage());
    }

    /**
     * Test: Validation rejects negative lockTimeoutMs.
     */
    @Test
    void testValidationRejectsNegativeLockTimeout() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new MigrationConfig(100, 300, -1, 5_000_000, 10);
        });
        assertTrue(exception.getMessage().contains("lockTimeoutMs must be > 0"),
                  "Exception should mention lockTimeoutMs: " + exception.getMessage());
    }

    /**
     * Test: Validation rejects negative lockRetryIntervalNs.
     */
    @Test
    void testValidationRejectsNegativeLockRetryInterval() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new MigrationConfig(100, 300, 50, -1, 10);
        });
        assertTrue(exception.getMessage().contains("lockRetryIntervalNs must be > 0"),
                  "Exception should mention lockRetryIntervalNs: " + exception.getMessage());
    }

    /**
     * Test: Validation rejects negative maxLockRetries.
     */
    @Test
    void testValidationRejectsNegativeMaxLockRetries() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new MigrationConfig(100, 300, 50, 5_000_000, -1);
        });
        assertTrue(exception.getMessage().contains("maxLockRetries must be > 0"),
                  "Exception should mention maxLockRetries: " + exception.getMessage());
    }

    /**
     * Test: Validation rejects totalTimeoutMs < 3 × phaseTimeoutMs.
     */
    @Test
    void testValidationRejectsTotalTimeoutTooSmall() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new MigrationConfig(100, 250, 50, 5_000_000, 10);  // 250 < 3 × 100
        });
        assertTrue(exception.getMessage().contains("totalTimeoutMs"),
                  "Exception should mention totalTimeoutMs: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("phaseTimeoutMs"),
                  "Exception should mention phaseTimeoutMs: " + exception.getMessage());
    }

    /**
     * Test: Validation rejects lockTimeoutMs >= phaseTimeoutMs.
     */
    @Test
    void testValidationRejectsLockTimeoutTooLarge() {
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new MigrationConfig(100, 300, 100, 5_000_000, 10);  // lockTimeout == phaseTimeout
        });
        assertTrue(exception.getMessage().contains("lockTimeoutMs"),
                  "Exception should mention lockTimeoutMs: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("phaseTimeoutMs"),
                  "Exception should mention phaseTimeoutMs: " + exception.getMessage());
    }

    /**
     * Test: Valid custom configuration passes validation.
     */
    @Test
    void testValidCustomConfiguration() {
        assertDoesNotThrow(() -> {
            var config = new MigrationConfig(200, 600, 100, 10_000_000, 20);
            assertEquals(200, config.phaseTimeoutMs());
            assertEquals(600, config.totalTimeoutMs());
            assertEquals(100, config.lockTimeoutMs());
            assertEquals(10_000_000, config.lockRetryIntervalNs());
            assertEquals(20, config.maxLockRetries());
        });
    }

    /**
     * Test: toString() includes all configuration values.
     */
    @Test
    void testToString() {
        var config = MigrationConfig.defaults();
        var str = config.toString();

        assertTrue(str.contains("100ms"), "Should contain phase timeout");
        assertTrue(str.contains("300ms"), "Should contain total timeout");
        assertTrue(str.contains("50ms"), "Should contain lock timeout");
        assertTrue(str.contains("5000000ns"), "Should contain lock retry interval");
        assertTrue(str.contains("×10"), "Should contain max lock retries");
    }

    /**
     * Test: CrossProcessMigration constructor accepts defaults() config.
     */
    @Test
    void testCrossProcessMigrationWithDefaultsConfig() {
        var dedup = new IdempotencyStore(300_000);
        var metrics = new MigrationMetrics();

        assertDoesNotThrow(() -> {
            var migration = new CrossProcessMigration(dedup, metrics, MigrationConfig.defaults());
            migration.stop();
        });
    }

    /**
     * Test: CrossProcessMigration constructor accepts forHighLatency() config.
     */
    @Test
    void testCrossProcessMigrationWithHighLatencyConfig() {
        var dedup = new IdempotencyStore(300_000);
        var metrics = new MigrationMetrics();

        assertDoesNotThrow(() -> {
            var migration = new CrossProcessMigration(dedup, metrics, MigrationConfig.forHighLatency());
            migration.stop();
        });
    }

    /**
     * Test: CrossProcessMigration constructor accepts forLowLatency() config.
     */
    @Test
    void testCrossProcessMigrationWithLowLatencyConfig() {
        var dedup = new IdempotencyStore(300_000);
        var metrics = new MigrationMetrics();

        assertDoesNotThrow(() -> {
            var migration = new CrossProcessMigration(dedup, metrics, MigrationConfig.forLowLatency());
            migration.stop();
        });
    }

    /**
     * Test: CrossProcessMigration constructor with no config parameter uses defaults().
     */
    @Test
    void testCrossProcessMigrationDefaultConstructor() {
        var dedup = new IdempotencyStore(300_000);
        var metrics = new MigrationMetrics();

        assertDoesNotThrow(() -> {
            var migration = new CrossProcessMigration(dedup, metrics);
            migration.stop();
        });
    }
}
