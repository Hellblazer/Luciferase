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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for split failure metrics tracking.
 * <p>
 * Phase P1.1: Diagnostic Enhancement - Split Failure Metrics
 *
 * @author hal.hildebrand
 */
class SplitMetricsTest {

    private TopologyMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new TopologyMetrics();
    }

    @Test
    void testMetricsIncrementOnSplitAttempt() {
        // Record split attempts
        metrics.recordSplitAttempt();
        metrics.recordSplitAttempt();
        metrics.recordSplitAttempt();

        var splitMetrics = metrics.getSplitMetrics();

        assertEquals(3, splitMetrics.totalAttempts(), "Should have 3 split attempts");
        assertEquals(0, splitMetrics.successfulSplits(), "Should have 0 successful splits");
        assertEquals(0, splitMetrics.failedSplits(), "Should have 0 failed splits");
    }

    @Test
    void testFailureReasonCategorization() {
        // Record failures with different reasons
        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("KEY_COLLISION");

        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("NO_AVAILABLE_KEY");

        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("KEY_COLLISION");

        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("ENTITY_CONSERVATION_FAILED");

        var splitMetrics = metrics.getSplitMetrics();

        assertEquals(4, splitMetrics.totalAttempts(), "Should have 4 split attempts");
        assertEquals(0, splitMetrics.successfulSplits(), "Should have 0 successful splits");
        assertEquals(4, splitMetrics.failedSplits(), "Should have 4 failed splits");

        // Check failure reasons
        var failuresByReason = splitMetrics.failuresByReason();
        assertEquals(2, failuresByReason.get("KEY_COLLISION"), "Should have 2 KEY_COLLISION failures");
        assertEquals(1, failuresByReason.get("NO_AVAILABLE_KEY"), "Should have 1 NO_AVAILABLE_KEY failure");
        assertEquals(1, failuresByReason.get("ENTITY_CONSERVATION_FAILED"), "Should have 1 ENTITY_CONSERVATION_FAILED failure");
    }

    @Test
    void testLevelsExhaustedTracking() {
        // Record failures with different levels exhausted
        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("NO_AVAILABLE_KEY");
        metrics.recordLevelsExhaustedOnFailure(5);

        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("NO_AVAILABLE_KEY");
        metrics.recordLevelsExhaustedOnFailure(10);

        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("NO_AVAILABLE_KEY");
        metrics.recordLevelsExhaustedOnFailure(15);

        var splitMetrics = metrics.getSplitMetrics();

        assertEquals(3, splitMetrics.totalAttempts(), "Should have 3 split attempts");
        assertEquals(3, splitMetrics.failedSplits(), "Should have 3 failed splits");
        assertEquals(10.0, splitMetrics.avgLevelsExhaustedOnFailure(), 0.01, "Average should be 10.0");
    }

    @Test
    void testSuccessfulSplitTracking() {
        // Record successful splits
        metrics.recordSplitAttempt();
        metrics.recordSplitSuccess();

        metrics.recordSplitAttempt();
        metrics.recordSplitSuccess();

        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("KEY_COLLISION");

        var splitMetrics = metrics.getSplitMetrics();

        assertEquals(3, splitMetrics.totalAttempts(), "Should have 3 split attempts");
        assertEquals(2, splitMetrics.successfulSplits(), "Should have 2 successful splits");
        assertEquals(1, splitMetrics.failedSplits(), "Should have 1 failed split");
    }

    @Test
    void testMetricsResetForTestIsolation() {
        // Record some metrics
        metrics.recordSplitAttempt();
        metrics.recordSplitSuccess();
        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("KEY_COLLISION");
        metrics.recordLevelsExhaustedOnFailure(5);

        // Verify metrics are recorded
        var splitMetrics = metrics.getSplitMetrics();
        assertEquals(2, splitMetrics.totalAttempts(), "Should have 2 attempts before reset");

        // Reset
        metrics.reset();

        // Verify metrics are cleared
        var resetMetrics = metrics.getSplitMetrics();
        assertEquals(0, resetMetrics.totalAttempts(), "Should have 0 attempts after reset");
        assertEquals(0, resetMetrics.successfulSplits(), "Should have 0 successful splits after reset");
        assertEquals(0, resetMetrics.failedSplits(), "Should have 0 failed splits after reset");
        assertTrue(resetMetrics.failuresByReason().isEmpty(), "Should have no failure reasons after reset");
        assertEquals(0.0, resetMetrics.avgLevelsExhaustedOnFailure(), 0.01, "Should have 0.0 avg levels after reset");
    }

    @Test
    void testAvgLevelsExhaustedWithNoFailures() {
        // Record successful splits only
        metrics.recordSplitAttempt();
        metrics.recordSplitSuccess();

        var splitMetrics = metrics.getSplitMetrics();

        assertEquals(0.0, splitMetrics.avgLevelsExhaustedOnFailure(), 0.01,
                    "Average should be 0.0 when no failures recorded");
    }

    @Test
    void testFailureReasonMapDefaultsToZero() {
        // Don't record any failures
        metrics.recordSplitAttempt();
        metrics.recordSplitSuccess();

        var splitMetrics = metrics.getSplitMetrics();

        // Verify empty failure reasons map
        assertTrue(splitMetrics.failuresByReason().isEmpty(), "Should have empty failure reasons map");
    }

    @Test
    void testMultipleResetCycles() {
        // First cycle
        metrics.recordSplitAttempt();
        metrics.recordSplitSuccess();
        assertEquals(1, metrics.getSplitMetrics().totalAttempts());

        // Reset and second cycle
        metrics.reset();
        assertEquals(0, metrics.getSplitMetrics().totalAttempts());

        metrics.recordSplitAttempt();
        metrics.recordSplitAttempt();
        metrics.recordSplitFailure("KEY_COLLISION");
        assertEquals(2, metrics.getSplitMetrics().totalAttempts());

        // Reset and third cycle
        metrics.reset();
        assertEquals(0, metrics.getSplitMetrics().totalAttempts());
    }
}
