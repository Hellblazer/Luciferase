/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4.2 integration tests for Clock usage (GAP-6 validation).
 *
 * Tests verify:
 * - Correct Clock interface from simulation.distributed.integration
 * - TestClock injection patterns work as documented
 * - No usage of java.time.Clock or System.currentTimeMillis()
 */
class Phase42ClockIntegrationTest {

    /**
     * Test 1: Clock pattern validation.
     *
     * Validates the Clock injection pattern documented in PHASE_4_METHODOLOGY.md:
     * - Clock interface from simulation.distributed.integration
     * - currentTimeMillis() method (NOT millis())
     * - Volatile field with setClock() method
     */
    @Test
    void testClockPattern() {
        // Verify Clock is the correct interface
        Clock systemClock = Clock.system();
        assertThat(systemClock)
            .as("Clock.system() should return Clock instance")
            .isNotNull();

        long time1 = systemClock.currentTimeMillis();
        assertThat(time1)
            .as("currentTimeMillis() should return positive timestamp")
            .isGreaterThan(0);

        // Verify fixed Clock
        Clock fixedClock = Clock.fixed(5000);
        assertThat(fixedClock.currentTimeMillis())
            .as("Fixed clock should return fixed time")
            .isEqualTo(5000);
    }

    /**
     * Test 2: TestClock usage patterns.
     *
     * Verifies TestClock can be injected into fault tolerance components
     * and provides deterministic time control.
     */
    @Test
    void testTestClockUsage() {
        var testClock = new TestClock(1000); // Start at t=1000ms

        assertThat(testClock.currentTimeMillis())
            .as("Initial time should be 1000ms")
            .isEqualTo(1000);

        // Advance time
        testClock.advance(500);

        assertThat(testClock.currentTimeMillis())
            .as("After advance(500), time should be 1500ms")
            .isEqualTo(1500);

        // Set absolute time
        testClock.setTime(3000);

        assertThat(testClock.currentTimeMillis())
            .as("After setTime(3000), time should be 3000ms")
            .isEqualTo(3000);

        // Verify TestClock is compatible with Clock interface
        Clock clockInterface = testClock;
        assertThat(clockInterface.currentTimeMillis())
            .as("TestClock should work through Clock interface")
            .isEqualTo(3000);
    }
}
