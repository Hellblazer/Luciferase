/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for split cooldown timer functionality.
 * <p>
 * Validates that failed splits activate a cooldown period to prevent
 * retry spam during topology checks.
 * <p>
 * P1.2: Cooldown Timer Implementation for Split Retry Prevention
 *
 * @author hal.hildebrand
 */
class SplitCooldownTest {

    private SplitCooldownTracker tracker;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        testClock = new TestClock();
        testClock.setTime(1000L); // Start at t=1000ms
        tracker = new SplitCooldownTracker();
        tracker.setClock(testClock);
    }

    @Test
    void testCooldownActivatesAfterFailure() {
        var bubbleId = UUID.randomUUID();

        // Initially not on cooldown
        assertFalse(tracker.isOnCooldown(bubbleId), "Should not be on cooldown initially");

        // Record failure
        tracker.recordFailure(bubbleId);

        // Should be on cooldown
        assertTrue(tracker.isOnCooldown(bubbleId), "Should be on cooldown after failure");
    }

    @Test
    void testCooldownPreventsImmediateRetry() {
        var bubbleId = UUID.randomUUID();

        // Record failure at t=1000
        tracker.recordFailure(bubbleId);
        assertTrue(tracker.isOnCooldown(bubbleId), "Should be on cooldown after failure");

        // Advance time by 15 seconds (still within 30s cooldown)
        testClock.advance(15_000L);
        assertTrue(tracker.isOnCooldown(bubbleId), "Should still be on cooldown after 15s");
    }

    @Test
    void testCooldownExpiresCorrectly() {
        var bubbleId = UUID.randomUUID();

        // Record failure at t=1000
        tracker.recordFailure(bubbleId);
        assertTrue(tracker.isOnCooldown(bubbleId), "Should be on cooldown after failure");

        // Advance time by 30 seconds (exactly cooldown duration)
        testClock.advance(30_000L);
        assertFalse(tracker.isOnCooldown(bubbleId), "Should not be on cooldown after 30s");

        // Advance time by 31 seconds (past cooldown)
        testClock.setTime(1000L); // Reset
        tracker.recordFailure(bubbleId);
        testClock.advance(31_000L);
        assertFalse(tracker.isOnCooldown(bubbleId), "Should not be on cooldown after 31s");
    }

    @Test
    void testSuccessfulSplitClearsCooldown() {
        var bubbleId = UUID.randomUUID();

        // Record failure
        tracker.recordFailure(bubbleId);
        assertTrue(tracker.isOnCooldown(bubbleId), "Should be on cooldown after failure");

        // Record success (clears cooldown)
        tracker.recordSuccess(bubbleId);
        assertFalse(tracker.isOnCooldown(bubbleId), "Should not be on cooldown after success");
    }

    @Test
    void testIndependentCooldownsPerBubble() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        // Record failure for bubble1 only
        tracker.recordFailure(bubble1);

        // Bubble1 should be on cooldown, bubble2 should not
        assertTrue(tracker.isOnCooldown(bubble1), "Bubble1 should be on cooldown");
        assertFalse(tracker.isOnCooldown(bubble2), "Bubble2 should not be on cooldown");

        // Record failure for bubble2
        tracker.recordFailure(bubble2);
        assertTrue(tracker.isOnCooldown(bubble2), "Bubble2 should now be on cooldown");

        // Clear bubble1
        tracker.recordSuccess(bubble1);
        assertFalse(tracker.isOnCooldown(bubble1), "Bubble1 should be cleared");
        assertTrue(tracker.isOnCooldown(bubble2), "Bubble2 should still be on cooldown");
    }

    @Test
    void testDeterministicClockInjection() {
        var bubbleId = UUID.randomUUID();

        // Create tracker with system clock
        var systemTracker = new SplitCooldownTracker();
        systemTracker.setClock(Clock.system());

        // Record failure
        systemTracker.recordFailure(bubbleId);
        assertTrue(systemTracker.isOnCooldown(bubbleId), "Should work with system clock");

        // Create tracker with test clock
        var testTracker = new SplitCooldownTracker();
        var clock = new TestClock();
        clock.setTime(5000L);
        testTracker.setClock(clock);

        testTracker.recordFailure(bubbleId);
        assertTrue(testTracker.isOnCooldown(bubbleId), "Should work with test clock");

        // Advance test clock
        clock.advance(30_000L);
        assertFalse(testTracker.isOnCooldown(bubbleId), "Should respect test clock time");
    }

    @Test
    void testConfigurableCooldownDuration() {
        var bubbleId = UUID.randomUUID();

        // Create tracker with custom cooldown (10 seconds instead of 30)
        var customTracker = new SplitCooldownTracker(10_000L);
        customTracker.setClock(testClock);

        customTracker.recordFailure(bubbleId);
        assertTrue(customTracker.isOnCooldown(bubbleId), "Should be on cooldown");

        // Advance by 9 seconds (still on cooldown)
        testClock.advance(9_000L);
        assertTrue(customTracker.isOnCooldown(bubbleId), "Should still be on cooldown after 9s");

        // Advance by 10 seconds (cooldown expired)
        testClock.setTime(1000L); // Reset
        customTracker.recordFailure(bubbleId);
        testClock.advance(10_000L);
        assertFalse(customTracker.isOnCooldown(bubbleId), "Should not be on cooldown after 10s");
    }
}
