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
 * Tests for BalanceResult.
 *
 * @author hal.hildebrand
 */
public class BalanceResultTest {

    @Test
    public void testSuccessfulResult() {
        var metrics = createMetricsSnapshot(5, 10);
        var result = BalanceResult.success(metrics, 10);

        assertTrue(result.successful());
        assertEquals(10, result.refinementsApplied());
        assertNotNull(result.message());
        assertTrue(result.converged());
        assertFalse(result.noWorkNeeded());
    }

    @Test
    public void testSuccessfulResultNoWork() {
        var metrics = createMetricsSnapshot(0, 0);
        var result = BalanceResult.success(metrics, 0);

        assertTrue(result.successful());
        assertEquals(0, result.refinementsApplied());
        assertFalse(result.converged());
        assertTrue(result.noWorkNeeded());
    }

    @Test
    public void testFailureResult() {
        var metrics = createMetricsSnapshot(3, 5);
        var result = BalanceResult.failure(metrics, "Test failure reason");

        assertFalse(result.successful());
        assertEquals(5, result.refinementsApplied());
        assertEquals("Test failure reason", result.message());
        assertFalse(result.converged());
        assertFalse(result.noWorkNeeded());
    }

    @Test
    public void testTimeoutResult() {
        var metrics = createMetricsSnapshot(10, 8);
        var result = BalanceResult.timeout(metrics, 10);

        assertFalse(result.successful());
        assertEquals(8, result.refinementsApplied());
        assertTrue(result.message().contains("timed out"));
        assertTrue(result.message().contains("10 rounds"));
    }

    @Test
    public void testInvalidRefinementsAppliedThrows() {
        var metrics = createMetricsSnapshot(1, 0);

        assertThrows(IllegalArgumentException.class,
                    () -> new BalanceResult(true, metrics, -1, "Test"),
                    "Should reject negative refinements applied");
    }

    @Test
    public void testNullMetricsThrows() {
        assertThrows(NullPointerException.class,
                    () -> new BalanceResult(true, null, 0, "Test"),
                    "Should reject null metrics");
    }

    @Test
    public void testNullMessageThrows() {
        var metrics = createMetricsSnapshot(1, 0);

        assertThrows(NullPointerException.class,
                    () -> new BalanceResult(true, metrics, 0, null),
                    "Should reject null message");
    }

    @Test
    public void testConvergedChecks() {
        var metrics = createMetricsSnapshot(5, 10);

        // Successful with refinements = converged
        var result1 = new BalanceResult(true, metrics, 10, "Success");
        assertTrue(result1.converged());

        // Successful without refinements = not converged
        var result2 = new BalanceResult(true, metrics, 0, "No work");
        assertFalse(result2.converged());

        // Failed with refinements = not converged
        var result3 = new BalanceResult(false, metrics, 10, "Failed");
        assertFalse(result3.converged());
    }

    @Test
    public void testNoWorkNeededChecks() {
        var metrics = createMetricsSnapshot(0, 0);

        // Successful with no refinements = no work needed
        var result1 = new BalanceResult(true, metrics, 0, "No work");
        assertTrue(result1.noWorkNeeded());

        // Successful with refinements = work was needed
        var result2 = new BalanceResult(true, metrics, 5, "Success");
        assertFalse(result2.noWorkNeeded());

        // Failed = work was needed (even if no refinements)
        var result3 = new BalanceResult(false, metrics, 0, "Failed");
        assertFalse(result3.noWorkNeeded());
    }

    @Test
    public void testToString() {
        var metrics = createMetricsSnapshot(5, 10);
        var result = BalanceResult.success(metrics, 10);
        var str = result.toString();

        assertNotNull(str);
        assertTrue(str.contains("BalanceResult"));
        assertTrue(str.contains("successful"));
        assertTrue(str.contains("rounds"));
        assertTrue(str.contains("refinements"));
    }

    @Test
    public void testRecordSemantics() {
        // Records should support value equality
        var metrics1 = createMetricsSnapshot(5, 10);
        var metrics2 = createMetricsSnapshot(5, 10);

        var result1 = new BalanceResult(true, metrics1, 10, "Success");
        var result2 = new BalanceResult(true, metrics2, 10, "Success");

        assertEquals(result1, result2);
        assertEquals(result1.hashCode(), result2.hashCode());
    }

    // Helper methods

    private BalanceMetrics.Snapshot createMetricsSnapshot(int rounds, int refinementsApplied) {
        return new BalanceMetrics.Snapshot(
            rounds,
            Duration.ofMillis(rounds * 100),
            Duration.ofMillis(50),
            Duration.ofMillis(200),
            Duration.ofMillis(100),
            refinementsApplied + 2, // More requests than applications
            refinementsApplied
        );
    }
}
