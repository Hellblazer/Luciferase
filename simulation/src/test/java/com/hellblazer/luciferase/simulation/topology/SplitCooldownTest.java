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

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    void testCooldownWithZeroDuration() {
        // Zero duration should be rejected
        assertThrows(IllegalArgumentException.class, () -> new SplitCooldownTracker(0L),
                     "Should reject zero cooldown duration");
    }

    @Test
    void testCooldownWithNegativeDuration() {
        // Negative duration should be rejected
        assertThrows(IllegalArgumentException.class, () -> new SplitCooldownTracker(-1000L),
                     "Should reject negative cooldown duration");
    }

    @Test
    void testCooldownWithMaxDuration() {
        var bubbleId = UUID.randomUUID();

        // Create tracker with very large cooldown
        var longTracker = new SplitCooldownTracker(Long.MAX_VALUE / 2);
        longTracker.setClock(testClock);

        longTracker.recordFailure(bubbleId);
        assertTrue(longTracker.isOnCooldown(bubbleId), "Should be on cooldown");

        // Even after large time advance, should still be on cooldown
        testClock.advance(1_000_000_000L); // ~11 days
        assertTrue(longTracker.isOnCooldown(bubbleId), "Should still be on cooldown after long time");
    }

    @Test
    void testMultipleConcurrentCooldowns() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var bubble3 = UUID.randomUUID();

        // Create staggered cooldowns
        tracker.recordFailure(bubble1);
        testClock.advance(10_000L);
        tracker.recordFailure(bubble2);
        testClock.advance(10_000L);
        tracker.recordFailure(bubble3);

        // All should be on cooldown
        assertTrue(tracker.isOnCooldown(bubble1), "Bubble1 should be on cooldown");
        assertTrue(tracker.isOnCooldown(bubble2), "Bubble2 should be on cooldown");
        assertTrue(tracker.isOnCooldown(bubble3), "Bubble3 should be on cooldown");

        // Advance to expire bubble1 only (30s from its start)
        testClock.advance(10_000L); // Total elapsed: 30s from bubble1, 20s from bubble2, 10s from bubble3
        assertFalse(tracker.isOnCooldown(bubble1), "Bubble1 should have expired");
        assertTrue(tracker.isOnCooldown(bubble2), "Bubble2 should still be on cooldown");
        assertTrue(tracker.isOnCooldown(bubble3), "Bubble3 should still be on cooldown");

        // Advance to expire bubble2
        testClock.advance(10_000L); // Total elapsed: 40s from bubble1, 30s from bubble2, 20s from bubble3
        assertFalse(tracker.isOnCooldown(bubble2), "Bubble2 should have expired");
        assertTrue(tracker.isOnCooldown(bubble3), "Bubble3 should still be on cooldown");
    }

    @Test
    void testCooldownCleanupRemovesExpired() {
        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();
        var bubble3 = UUID.randomUUID();

        // Record failures for all bubbles
        tracker.recordFailure(bubble1);
        tracker.recordFailure(bubble2);
        tracker.recordFailure(bubble3);

        // All should be counted
        assertEquals(3, tracker.getActiveCooldownCount(), "Should have 3 active cooldowns");

        // Advance time to expire all cooldowns
        testClock.advance(30_000L);

        // Cleanup should happen during count
        assertEquals(0, tracker.getActiveCooldownCount(), "Should have 0 active cooldowns after expiry");

        // Verify bubbles are no longer on cooldown
        assertFalse(tracker.isOnCooldown(bubble1), "Bubble1 should not be on cooldown");
        assertFalse(tracker.isOnCooldown(bubble2), "Bubble2 should not be on cooldown");
        assertFalse(tracker.isOnCooldown(bubble3), "Bubble3 should not be on cooldown");
    }

    @Test
    void testNullBubbleIdHandling() {
        // All methods should reject null bubbleId
        assertThrows(NullPointerException.class, () -> tracker.isOnCooldown(null), "isOnCooldown should reject null");
        assertThrows(NullPointerException.class, () -> tracker.recordFailure(null), "recordFailure should reject null");
        assertThrows(NullPointerException.class, () -> tracker.recordSuccess(null), "recordSuccess should reject null");
    }

    @Test
    void testNullClockHandling() {
        // setClock should reject null
        assertThrows(NullPointerException.class, () -> tracker.setClock(null), "setClock should reject null");
    }

    @Test
    void testMultipleFailuresForSameBubble() {
        var bubbleId = UUID.randomUUID();

        // Record first failure at t=1000
        tracker.recordFailure(bubbleId);
        var firstCooldownEnd = testClock.currentTimeMillis() + 30_000L;

        // Advance time by 10 seconds
        testClock.advance(10_000L);

        // Record second failure - should extend cooldown from current time
        tracker.recordFailure(bubbleId);
        var secondCooldownEnd = testClock.currentTimeMillis() + 30_000L;

        assertTrue(secondCooldownEnd > firstCooldownEnd, "Second failure should extend cooldown");

        // Verify cooldown is extended
        testClock.advance(20_000L); // 30s from first failure, but only 20s from second
        assertTrue(tracker.isOnCooldown(bubbleId), "Should still be on cooldown after 20s from second failure");

        // Advance to expire second cooldown
        testClock.advance(10_000L); // Now 30s from second failure
        assertFalse(tracker.isOnCooldown(bubbleId), "Should not be on cooldown after 30s from second failure");
    }

    @Test
    void testRecordSuccessWhenNoCooldown() {
        var bubbleId = UUID.randomUUID();

        // Recording success without prior failure should not throw
        assertDoesNotThrow(() -> tracker.recordSuccess(bubbleId), "Should handle success without cooldown");

        // Bubble should still not be on cooldown
        assertFalse(tracker.isOnCooldown(bubbleId), "Should not be on cooldown");
    }

    @Test
    void testGetActiveCooldownCount() {
        assertEquals(0, tracker.getActiveCooldownCount(), "Should start with 0 cooldowns");

        var bubble1 = UUID.randomUUID();
        var bubble2 = UUID.randomUUID();

        tracker.recordFailure(bubble1);
        assertEquals(1, tracker.getActiveCooldownCount(), "Should have 1 cooldown");

        tracker.recordFailure(bubble2);
        assertEquals(2, tracker.getActiveCooldownCount(), "Should have 2 cooldowns");

        tracker.recordSuccess(bubble1);
        assertEquals(1, tracker.getActiveCooldownCount(), "Should have 1 cooldown after success");

        tracker.recordSuccess(bubble2);
        assertEquals(0, tracker.getActiveCooldownCount(), "Should have 0 cooldowns after all cleared");
    }

    @Test
    void testGetCooldownDurationMs() {
        var defaultTracker = new SplitCooldownTracker();
        assertEquals(30_000L, defaultTracker.getCooldownDurationMs(), "Default duration should be 30 seconds");

        var customTracker = new SplitCooldownTracker(15_000L);
        assertEquals(15_000L, customTracker.getCooldownDurationMs(), "Custom duration should be preserved");
    }

    // ========================================================================
    // Concurrency Tests
    // ========================================================================

    @Test
    void testConcurrentCooldownOperations() throws InterruptedException {
        var bubbleId = UUID.randomUUID();
        var iterations = 1000;
        var successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        var failureCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // Create two threads: one recording failures, one recording successes
        var failureThread = new Thread(() -> {
            for (int i = 0; i < iterations; i++) {
                tracker.recordFailure(bubbleId);
                failureCount.incrementAndGet();
                Thread.yield();
            }
        });

        var successThread = new Thread(() -> {
            for (int i = 0; i < iterations; i++) {
                tracker.recordSuccess(bubbleId);
                successCount.incrementAndGet();
                Thread.yield();
            }
        });

        failureThread.start();
        successThread.start();

        failureThread.join();
        successThread.join();

        // Verify all operations completed
        assertEquals(iterations, failureCount.get(), "All failures should be recorded");
        assertEquals(iterations, successCount.get(), "All successes should be recorded");

        // Final state is non-deterministic due to race, but should be valid
        // (either on cooldown or not, but no crash)
        assertDoesNotThrow(() -> tracker.isOnCooldown(bubbleId), "Should not crash after concurrent operations");
    }

    @Test
    void testCooldownUnderHighContention() throws InterruptedException {
        var numThreads = 10;
        var numBubbles = 100;
        var bubbles = new UUID[numBubbles];
        for (int i = 0; i < numBubbles; i++) {
            bubbles[i] = UUID.randomUUID();
        }

        var threads = new Thread[numThreads];
        var completedOperations = new java.util.concurrent.atomic.AtomicInteger(0);

        // Each thread performs random operations on random bubbles
        for (int t = 0; t < numThreads; t++) {
            threads[t] = new Thread(() -> {
                var random = new java.util.Random();
                for (int i = 0; i < 100; i++) {
                    var bubbleId = bubbles[random.nextInt(numBubbles)];
                    var operation = random.nextInt(3);

                    switch (operation) {
                        case 0 -> tracker.recordFailure(bubbleId);
                        case 1 -> tracker.recordSuccess(bubbleId);
                        case 2 -> tracker.isOnCooldown(bubbleId);
                    }

                    completedOperations.incrementAndGet();
                    Thread.yield();
                }
            });
            threads[t].start();
        }

        // Wait for all threads to complete
        for (var thread : threads) {
            thread.join();
        }

        // Verify all operations completed without errors
        assertEquals(numThreads * 100, completedOperations.get(), "All operations should complete");

        // Verify tracker is in valid state
        int activeCooldowns = tracker.getActiveCooldownCount();
        assertTrue(activeCooldowns >= 0 && activeCooldowns <= numBubbles,
                   "Active cooldown count should be valid: " + activeCooldowns);
    }

    @Test
    void testConcurrentGetActiveCooldownCount() throws InterruptedException {
        var numThreads = 5;
        var threads = new Thread[numThreads];

        // Create initial cooldowns
        for (int i = 0; i < 10; i++) {
            tracker.recordFailure(UUID.randomUUID());
        }

        // Multiple threads calling getActiveCooldownCount concurrently
        for (int t = 0; t < numThreads; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    int count = tracker.getActiveCooldownCount();
                    assertTrue(count >= 0, "Count should never be negative");
                    Thread.yield();
                }
            });
            threads[t].start();
        }

        // Wait for all threads
        for (var thread : threads) {
            thread.join();
        }

        // Should still have valid state
        int finalCount = tracker.getActiveCooldownCount();
        assertTrue(finalCount >= 0, "Final count should be non-negative");
    }
}
