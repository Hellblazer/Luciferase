package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PartitionStatus} enum.
 */
class PartitionStatusTest {

    @Test
    void testEnumValues() {
        // All expected values exist
        var values = PartitionStatus.values();
        assertEquals(5, values.length);
        assertEquals(PartitionStatus.HEALTHY, values[0]);
        assertEquals(PartitionStatus.SUSPECTED, values[1]);
        assertEquals(PartitionStatus.FAILED, values[2]);
        assertEquals(PartitionStatus.RECOVERING, values[3]);
        assertEquals(PartitionStatus.DEGRADED, values[4]);
    }

    @Test
    void testOrdinalConsistency() {
        // Verify ordinal values are stable (important for serialization)
        assertEquals(0, PartitionStatus.HEALTHY.ordinal());
        assertEquals(1, PartitionStatus.SUSPECTED.ordinal());
        assertEquals(2, PartitionStatus.FAILED.ordinal());
        assertEquals(3, PartitionStatus.RECOVERING.ordinal());
        assertEquals(4, PartitionStatus.DEGRADED.ordinal());
    }

    @Test
    void testValueOf() {
        // Can retrieve by name
        assertEquals(PartitionStatus.HEALTHY, PartitionStatus.valueOf("HEALTHY"));
        assertEquals(PartitionStatus.SUSPECTED, PartitionStatus.valueOf("SUSPECTED"));
        assertEquals(PartitionStatus.FAILED, PartitionStatus.valueOf("FAILED"));
        assertEquals(PartitionStatus.RECOVERING, PartitionStatus.valueOf("RECOVERING"));
        assertEquals(PartitionStatus.DEGRADED, PartitionStatus.valueOf("DEGRADED"));
    }

    @Test
    void testSwitchExpression() {
        // Modern switch expressions work correctly
        var result = switch (PartitionStatus.HEALTHY) {
            case HEALTHY -> "ok";
            case SUSPECTED -> "warning";
            case FAILED -> "error";
            case RECOVERING -> "restoring";
            case DEGRADED -> "partial";
        };
        assertEquals("ok", result);
    }

    @Test
    void testTransitionSemantics() {
        // Document expected state transitions
        // HEALTHY -> SUSPECTED -> FAILED -> RECOVERING -> HEALTHY
        // HEALTHY -> SUSPECTED -> HEALTHY (false alarm)
        // RECOVERING -> DEGRADED (partial recovery)

        // This test documents the expected state machine, actual enforcement
        // will be in FaultHandler implementation
        var healthy = PartitionStatus.HEALTHY;
        var suspected = PartitionStatus.SUSPECTED;
        var failed = PartitionStatus.FAILED;
        var recovering = PartitionStatus.RECOVERING;
        var degraded = PartitionStatus.DEGRADED;

        // Verify all states are distinct
        assertNotEquals(healthy, suspected);
        assertNotEquals(suspected, failed);
        assertNotEquals(failed, recovering);
        assertNotEquals(recovering, degraded);
    }
}
