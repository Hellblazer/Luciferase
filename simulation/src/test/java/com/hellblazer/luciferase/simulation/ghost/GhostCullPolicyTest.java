package com.hellblazer.luciferase.simulation.ghost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GhostCullPolicy - staleness detection and ghost removal (Phase 7B.3).
 * <p>
 * GhostCullPolicy provides:
 * - Configurable staleness threshold (default 500ms)
 * - Detection of stale ghosts based on last update time
 * - Simple, stateless staleness checking
 * <p>
 * Success criteria:
 * - Default staleness threshold is 500ms
 * - Custom thresholds supported
 * - Correct staleness detection based on time delta
 * - Thread-safe (stateless)
 * <p>
 * Test coverage:
 * - Default threshold (500ms)
 * - Custom threshold configuration
 * - Staleness detection accuracy
 * - Non-stale ghost handling
 *
 * @author hal.hildebrand
 */
class GhostCullPolicyTest {

    @Test
    void testDefaultStalenessThreshold() {
        var policy = new GhostCullPolicy();

        assertEquals(GhostCullPolicy.DEFAULT_STALENESS_MS, policy.getStalenessMs(),
                    "Default staleness should be 500ms");
    }

    @Test
    void testCustomStalenessThreshold() {
        var policy = new GhostCullPolicy(1000L);

        assertEquals(1000L, policy.getStalenessMs(),
                    "Custom staleness threshold should be respected");
    }

    @Test
    void testGhostIsStaleAfterThreshold() {
        var policy = new GhostCullPolicy(500L);

        long lastUpdate = 1000L;
        long currentTime = 1600L;  // 600ms later (> 500ms threshold)

        assertTrue(policy.isStale(lastUpdate, currentTime),
                  "Ghost should be stale after 600ms (threshold 500ms)");
    }

    @Test
    void testGhostIsNotStaleWithinThreshold() {
        var policy = new GhostCullPolicy(500L);

        long lastUpdate = 1000L;
        long currentTime = 1400L;  // 400ms later (< 500ms threshold)

        assertFalse(policy.isStale(lastUpdate, currentTime),
                   "Ghost should NOT be stale after 400ms (threshold 500ms)");
    }

    @Test
    void testGhostIsNotStaleAtExactThreshold() {
        var policy = new GhostCullPolicy(500L);

        long lastUpdate = 1000L;
        long currentTime = 1500L;  // Exactly 500ms later

        assertFalse(policy.isStale(lastUpdate, currentTime),
                   "Ghost should NOT be stale at exact threshold (not strictly greater)");
    }

    @Test
    void testGhostIsStaleJustOverThreshold() {
        var policy = new GhostCullPolicy(500L);

        long lastUpdate = 1000L;
        long currentTime = 1501L;  // 501ms later (just over threshold)

        assertTrue(policy.isStale(lastUpdate, currentTime),
                  "Ghost should be stale at 501ms (threshold 500ms)");
    }

    @Test
    void testVeryLargeStalenessThreshold() {
        var policy = new GhostCullPolicy(10_000L);  // 10 seconds

        long lastUpdate = 1000L;
        long currentTime = 5000L;  // 4 seconds later

        assertFalse(policy.isStale(lastUpdate, currentTime),
                   "Ghost should NOT be stale with large threshold (10s)");
    }

    @Test
    void testZeroTimeDelta() {
        var policy = new GhostCullPolicy(500L);

        long lastUpdate = 1000L;
        long currentTime = 1000L;  // Same time

        assertFalse(policy.isStale(lastUpdate, currentTime),
                   "Ghost should NOT be stale with zero time delta");
    }

    @Test
    void testNegativeTimeDelta() {
        var policy = new GhostCullPolicy(500L);

        long lastUpdate = 2000L;
        long currentTime = 1500L;  // Current time is BEFORE last update (clock skew)

        // Should handle gracefully - not stale if time goes backward
        assertFalse(policy.isStale(lastUpdate, currentTime),
                   "Ghost should NOT be stale with negative time delta (clock skew)");
    }
}
