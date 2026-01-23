package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FaultMetrics} record.
 */
class FaultMetricsTest {

    @Test
    void testDefaultConstruction() {
        var metrics = new FaultMetrics(0, 0, 0, 0, 0, 0);

        assertEquals(0, metrics.detectionLatencyMs());
        assertEquals(0, metrics.recoveryLatencyMs());
        assertEquals(0, metrics.failureCount());
        assertEquals(0, metrics.recoveryAttempts());
        assertEquals(0, metrics.successfulRecoveries());
        assertEquals(0, metrics.failedRecoveries());
    }

    @Test
    void testCustomValues() {
        var metrics = new FaultMetrics(
            150,  // detectionLatencyMs
            5000, // recoveryLatencyMs
            3,    // failureCount
            5,    // recoveryAttempts
            4,    // successfulRecoveries
            1     // failedRecoveries
        );

        assertEquals(150, metrics.detectionLatencyMs());
        assertEquals(5000, metrics.recoveryLatencyMs());
        assertEquals(3, metrics.failureCount());
        assertEquals(5, metrics.recoveryAttempts());
        assertEquals(4, metrics.successfulRecoveries());
        assertEquals(1, metrics.failedRecoveries());
    }

    @Test
    void testAccumulation() {
        // Simulate accumulating metrics
        var initial = FaultMetrics.zero();
        assertEquals(0, initial.failureCount());

        var afterFailure = initial.withIncrementedFailureCount();
        assertEquals(1, afterFailure.failureCount());

        var afterRecoveryAttempt = afterFailure.withIncrementedRecoveryAttempts();
        assertEquals(1, afterRecoveryAttempt.recoveryAttempts());

        var afterSuccess = afterRecoveryAttempt.withIncrementedSuccessfulRecoveries();
        assertEquals(1, afterSuccess.successfulRecoveries());
    }

    @Test
    void testStateTransitions() {
        var metrics = FaultMetrics.zero();

        // Failure detected
        metrics = metrics.withDetectionLatency(100);
        assertEquals(100, metrics.detectionLatencyMs());

        // Recovery started
        metrics = metrics.withIncrementedRecoveryAttempts();
        assertEquals(1, metrics.recoveryAttempts());

        // Recovery completed
        metrics = metrics.withRecoveryLatency(2000).withIncrementedSuccessfulRecoveries();
        assertEquals(2000, metrics.recoveryLatencyMs());
        assertEquals(1, metrics.successfulRecoveries());
    }

    @Test
    void testSnapshotSemantics() {
        // Each operation returns a new instance
        var original = FaultMetrics.zero();
        var modified = original.withIncrementedFailureCount();

        // Original unchanged
        assertEquals(0, original.failureCount());
        // New instance changed
        assertEquals(1, modified.failureCount());
    }

    @Test
    void testRecordEquality() {
        var metrics1 = new FaultMetrics(100, 2000, 1, 1, 1, 0);
        var metrics2 = new FaultMetrics(100, 2000, 1, 1, 1, 0);
        var metrics3 = new FaultMetrics(200, 2000, 1, 1, 1, 0);

        assertEquals(metrics1, metrics2);
        assertNotEquals(metrics1, metrics3);
        assertEquals(metrics1.hashCode(), metrics2.hashCode());
    }

    @Test
    void testSuccessRate() {
        var metrics = new FaultMetrics(0, 0, 0, 10, 8, 2);
        assertEquals(0.8, metrics.successRate(), 0.01);
    }

    @Test
    void testSuccessRateWithNoAttempts() {
        var metrics = FaultMetrics.zero();
        assertEquals(0.0, metrics.successRate(), 0.01);
    }

    @Test
    void testRecordToString() {
        var metrics = new FaultMetrics(100, 2000, 3, 5, 4, 1);
        var str = metrics.toString();

        assertTrue(str.contains("FaultMetrics"));
        assertTrue(str.contains("failureCount"));
    }

    @Test
    void testNegativeValuesAllowed() {
        // Negative values might represent invalid/error states, allow them
        assertDoesNotThrow(() -> new FaultMetrics(-1, -1, -1, -1, -1, -1));
    }
}
