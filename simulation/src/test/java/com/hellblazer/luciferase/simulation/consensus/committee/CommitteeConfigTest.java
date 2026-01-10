/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus.committee;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CommitteeConfig configuration holder.
 *
 * Tests cover:
 * - Default configuration values
 * - Configurability (builder or constructor)
 *
 * @author hal.hildebrand
 */
class CommitteeConfigTest {

    @Test
    void testDefaultConfig() {
        // Given/When: Default configuration
        var config = CommitteeConfig.defaultConfig();

        // Then: Defaults are sensible (7-9 nodes, 5 sec timeout)
        assertTrue(config.committeeSizeMin() >= 7, "Min committee size should be >= 7");
        assertTrue(config.committeeSizeMax() >= config.committeeSizeMin(), "Max >= Min");
        assertEquals(5, config.votingTimeoutSeconds(), "Default timeout should be 5 seconds");
        assertEquals(0.0, config.requiredQuorumRatio(), 0.001, "Quorum ratio not used (context.toleranceLevel())");
    }

    @Test
    void testConfigurability() {
        // Given: Custom configuration values
        var customMin = 5;
        var customMax = 11;
        var customTimeout = 10;

        // When: Creating custom config
        var config = new CommitteeConfig(customMin, customMax, customTimeout, 0.0);

        // Then: Values are overridden
        assertEquals(customMin, config.committeeSizeMin());
        assertEquals(customMax, config.committeeSizeMax());
        assertEquals(customTimeout, config.votingTimeoutSeconds());
    }

    @Test
    void testBuilderPattern() {
        // Given: Builder-based configuration
        var config = CommitteeConfig.newBuilder()
                                     .committeeSizeMin(3)
                                     .committeeSizeMax(7)
                                     .votingTimeoutSeconds(3)
                                     .build();

        // Then: Builder works correctly
        assertEquals(3, config.committeeSizeMin());
        assertEquals(7, config.committeeSizeMax());
        assertEquals(3, config.votingTimeoutSeconds());
    }
}
