/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-0frcy.112: ghostTtlBuckets is computed by integer division of ghostTtlMs by
 * bucketIntervalMs and silently truncates when ghostTtlMs is not a multiple (e.g. 150/100 -> 1
 * bucket -> 100ms effective TTL). Construction must reject non-multiples so the configured TTL is
 * exact, and accept exact multiples.
 */
class SimulationConfigTtlMultipleTest {

    @Test
    void nonMultipleTtlIsRejected() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig.Builder()
                .ghostTtlMs(150)
                .bucketIntervalMs(100)
                .build());
        assertTrue(ex.getMessage().contains("multiple of bucketIntervalMs"),
            "message must explain the multiple requirement, got: " + ex.getMessage());
    }

    @Test
    void exactMultipleTtlIsAccepted() {
        var config = new SimulationConfig.Builder()
            .ghostTtlMs(300)
            .bucketIntervalMs(100)
            .build();
        assertEquals(3, config.ghostTtlBuckets(), "300ms / 100ms must yield exactly 3 buckets");
        // Effective TTL (buckets * interval) must equal the configured TTL — no silent shortening.
        assertEquals(config.ghostTtlMs(), (long) config.ghostTtlBuckets() * config.bucketIntervalMs());
    }

    @Test
    void defaultsAreExactMultiple() {
        // Regression guard: the shipped defaults must satisfy the new invariant.
        assertDoesNotThrow(SimulationConfig::defaults);
    }
}
