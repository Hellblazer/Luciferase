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
package com.hellblazer.luciferase.lucien.balancing;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BalanceConfiguration.
 *
 * @author hal.hildebrand
 */
public class BalanceConfigurationTest {

    @Test
    public void testDefaultConfiguration() {
        var config = BalanceConfiguration.defaultConfig();

        assertNotNull(config, "Default config should not be null");
        assertEquals(BalanceConfiguration.DEFAULT_MAX_ROUNDS, config.maxRounds());
        assertEquals(BalanceConfiguration.DEFAULT_TIMEOUT_PER_ROUND, config.timeoutPerRound());
        assertEquals(BalanceConfiguration.DEFAULT_BATCH_SIZE, config.batchSize());
        assertEquals(BalanceConfiguration.DEFAULT_REFINEMENT_THRESHOLD, config.refinementThreshold());
    }

    @Test
    public void testCustomConfiguration() {
        var config = new BalanceConfiguration(
            5,
            Duration.ofSeconds(10),
            200,
            0.15
        );

        assertEquals(5, config.maxRounds());
        assertEquals(Duration.ofSeconds(10), config.timeoutPerRound());
        assertEquals(200, config.batchSize());
        assertEquals(0.15, config.refinementThreshold(), 0.001);
    }

    @Test
    public void testInvalidMaxRoundsThrows() {
        assertThrows(IllegalArgumentException.class,
                    () -> new BalanceConfiguration(0, Duration.ofSeconds(5), 100, 0.2),
                    "Should reject zero max rounds");

        assertThrows(IllegalArgumentException.class,
                    () -> new BalanceConfiguration(-1, Duration.ofSeconds(5), 100, 0.2),
                    "Should reject negative max rounds");
    }

    @Test
    public void testInvalidTimeoutThrows() {
        assertThrows(IllegalArgumentException.class,
                    () -> new BalanceConfiguration(10, Duration.ZERO, 100, 0.2),
                    "Should reject zero timeout");

        assertThrows(IllegalArgumentException.class,
                    () -> new BalanceConfiguration(10, Duration.ofSeconds(-1), 100, 0.2),
                    "Should reject negative timeout");

        assertThrows(NullPointerException.class,
                    () -> new BalanceConfiguration(10, null, 100, 0.2),
                    "Should reject null timeout");
    }

    @Test
    public void testInvalidBatchSizeThrows() {
        assertThrows(IllegalArgumentException.class,
                    () -> new BalanceConfiguration(10, Duration.ofSeconds(5), 0, 0.2),
                    "Should reject zero batch size");

        assertThrows(IllegalArgumentException.class,
                    () -> new BalanceConfiguration(10, Duration.ofSeconds(5), -1, 0.2),
                    "Should reject negative batch size");
    }

    @Test
    public void testInvalidRefinementThresholdThrows() {
        assertThrows(IllegalArgumentException.class,
                    () -> new BalanceConfiguration(10, Duration.ofSeconds(5), 100, -0.1),
                    "Should reject negative threshold");

        assertThrows(IllegalArgumentException.class,
                    () -> new BalanceConfiguration(10, Duration.ofSeconds(5), 100, 1.1),
                    "Should reject threshold > 1.0");
    }

    @Test
    public void testWithMaxRounds() {
        var config = BalanceConfiguration.defaultConfig();
        var modified = config.withMaxRounds(20);

        assertEquals(20, modified.maxRounds());
        assertEquals(config.timeoutPerRound(), modified.timeoutPerRound());
        assertEquals(config.batchSize(), modified.batchSize());
        assertEquals(config.refinementThreshold(), modified.refinementThreshold());

        // Original should be unchanged
        assertEquals(BalanceConfiguration.DEFAULT_MAX_ROUNDS, config.maxRounds());
    }

    @Test
    public void testWithTimeoutPerRound() {
        var config = BalanceConfiguration.defaultConfig();
        var newTimeout = Duration.ofSeconds(15);
        var modified = config.withTimeoutPerRound(newTimeout);

        assertEquals(newTimeout, modified.timeoutPerRound());
        assertEquals(config.maxRounds(), modified.maxRounds());
        assertEquals(config.batchSize(), modified.batchSize());
        assertEquals(config.refinementThreshold(), modified.refinementThreshold());
    }

    @Test
    public void testWithBatchSize() {
        var config = BalanceConfiguration.defaultConfig();
        var modified = config.withBatchSize(500);

        assertEquals(500, modified.batchSize());
        assertEquals(config.maxRounds(), modified.maxRounds());
        assertEquals(config.timeoutPerRound(), modified.timeoutPerRound());
        assertEquals(config.refinementThreshold(), modified.refinementThreshold());
    }

    @Test
    public void testWithRefinementThreshold() {
        var config = BalanceConfiguration.defaultConfig();
        var modified = config.withRefinementThreshold(0.3);

        assertEquals(0.3, modified.refinementThreshold(), 0.001);
        assertEquals(config.maxRounds(), modified.maxRounds());
        assertEquals(config.timeoutPerRound(), modified.timeoutPerRound());
        assertEquals(config.batchSize(), modified.batchSize());
    }

    @Test
    public void testEqualsAndHashCode() {
        var config1 = new BalanceConfiguration(10, Duration.ofSeconds(5), 100, 0.2);
        var config2 = new BalanceConfiguration(10, Duration.ofSeconds(5), 100, 0.2);
        var config3 = new BalanceConfiguration(20, Duration.ofSeconds(5), 100, 0.2);

        assertEquals(config1, config2, "Equal configs should be equal");
        assertEquals(config1.hashCode(), config2.hashCode(), "Equal configs should have same hash code");
        assertNotEquals(config1, config3, "Different configs should not be equal");
    }

    @Test
    public void testToString() {
        var config = BalanceConfiguration.defaultConfig();
        var str = config.toString();

        assertNotNull(str);
        assertTrue(str.contains("BalanceConfiguration"));
        assertTrue(str.contains("maxRounds"));
        assertTrue(str.contains("timeoutPerRound"));
        assertTrue(str.contains("batchSize"));
        assertTrue(str.contains("refinementThreshold"));
    }
}
