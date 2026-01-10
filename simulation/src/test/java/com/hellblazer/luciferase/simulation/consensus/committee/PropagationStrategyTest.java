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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PropagationStrategy.
 * <p>
 * Timing strategy for P2P vote propagation:
 * - Voting timeout from CommitteeConfig
 * - Exponential backoff for retries
 * <p>
 * Phase 7G Day 2: Voting Protocol & Ballot Box
 *
 * @author hal.hildebrand
 */
class PropagationStrategyTest {

    private PropagationStrategy strategy;
    private CommitteeConfig config;

    @BeforeEach
    void setUp() {
        config = CommitteeConfig.defaultConfig();
        strategy = new PropagationStrategy(config);
    }

    /**
     * Default timeout should be 5000ms from config.
     */
    @Test
    void testDefaultTimeout() {
        assertEquals(5000, strategy.getVotingTimeoutMs(), "Default voting timeout should be 5000ms");
    }

    /**
     * Custom timeout from config should be respected.
     */
    @Test
    void testCustomTimeout() {
        var customConfig = CommitteeConfig.newBuilder().votingTimeoutSeconds(10).build();
        var customStrategy = new PropagationStrategy(customConfig);

        assertEquals(10000, customStrategy.getVotingTimeoutMs(), "Custom timeout should be 10000ms");
    }

    /**
     * Exponential backoff should work: 100ms, 200ms, 400ms, 800ms, ...
     */
    @Test
    void testResendBackoff() {
        // First retry: 100ms
        assertTrue(strategy.shouldResendVote(1), "Should resend on attempt 1");
        assertEquals(100, strategy.getResendDelayMs(1), "First retry delay should be 100ms");

        // Second retry: 200ms
        assertTrue(strategy.shouldResendVote(2), "Should resend on attempt 2");
        assertEquals(200, strategy.getResendDelayMs(2), "Second retry delay should be 200ms");

        // Third retry: 400ms
        assertTrue(strategy.shouldResendVote(3), "Should resend on attempt 3");
        assertEquals(400, strategy.getResendDelayMs(3), "Third retry delay should be 400ms");

        // Fourth retry: 800ms
        assertTrue(strategy.shouldResendVote(4), "Should resend on attempt 4");
        assertEquals(800, strategy.getResendDelayMs(4), "Fourth retry delay should be 800ms");
    }

    /**
     * Should stop retrying after max attempts (default: 5).
     */
    @Test
    void testMaxResendAttempts() {
        assertTrue(strategy.shouldResendVote(5), "Should resend on attempt 5 (last)");
        assertFalse(strategy.shouldResendVote(6), "Should NOT resend on attempt 6 (exceeds max)");
        assertFalse(strategy.shouldResendVote(10), "Should NOT resend on attempt 10 (exceeds max)");
    }

    /**
     * Test edge cases.
     */
    @Test
    void testEdgeCases() {
        // Attempt 0 should not resend
        assertFalse(strategy.shouldResendVote(0), "Should NOT resend on attempt 0 (initial send)");

        // Negative attempts should not resend
        assertFalse(strategy.shouldResendVote(-1), "Should NOT resend on negative attempt");
    }
}
