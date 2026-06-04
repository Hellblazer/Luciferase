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

        // Then: Default timeout is 5 seconds. Committee size and quorum are derived
        // from the context, not from config (Luciferase-0frcy.91 removed the dead
        // committeeSizeMin/committeeSizeMax/requiredQuorumRatio fields).
        assertEquals(5, config.votingTimeoutSeconds(), "Default timeout should be 5 seconds");
    }

    @Test
    void testConfigurability() {
        // Given: Custom timeout
        var customTimeout = 10;

        // When: Creating custom config
        var config = new CommitteeConfig(customTimeout);

        // Then: Value is overridden
        assertEquals(customTimeout, config.votingTimeoutSeconds());
    }

    @Test
    void testBuilderPattern() {
        // Given: Builder-based configuration
        var config = CommitteeConfig.newBuilder()
                                     .votingTimeoutSeconds(3)
                                     .build();

        // Then: Builder works correctly
        assertEquals(3, config.votingTimeoutSeconds());
    }

    /**
     * Regression guard for Luciferase-0frcy.91: the dead committeeSizeMin /
     * committeeSizeMax / requiredQuorumRatio fields must stay removed. This test
     * fails to COMPILE if any of them is reintroduced as a record component (the
     * canonical constructor arity is pinned to 1).
     */
    @Test
    void testOnlyVotingTimeoutComponentRemains() {
        assertEquals(1, CommitteeConfig.class.getRecordComponents().length,
                     "CommitteeConfig must expose exactly one record component (votingTimeoutSeconds)");
        assertEquals("votingTimeoutSeconds",
                     CommitteeConfig.class.getRecordComponents()[0].getName());
    }
}
