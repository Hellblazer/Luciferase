package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Phase 4.1 PartitionStatus enum (revised specification).
 *
 * Verifies:
 * - Enum values match specification (HEALTHY, SUSPECTED, FAILED)
 * - Valid state transitions for fault detection
 *
 * Note: This tests the REVISED PartitionStatus spec with 3 states,
 * replacing the earlier 5-state version.
 */
class Phase41PartitionStatusTest {

    @Test
    void testEnumValues() {
        // Verify exactly 3 states as per revised spec
        var values = PartitionStatus.values();
        assertThat(values).hasSize(3);

        // Verify all required states exist
        assertThat(values).contains(
            PartitionStatus.HEALTHY,
            PartitionStatus.SUSPECTED,
            PartitionStatus.FAILED
        );

        // Verify we can get enum by name
        assertThat(PartitionStatus.valueOf("HEALTHY")).isEqualTo(PartitionStatus.HEALTHY);
        assertThat(PartitionStatus.valueOf("SUSPECTED")).isEqualTo(PartitionStatus.SUSPECTED);
        assertThat(PartitionStatus.valueOf("FAILED")).isEqualTo(PartitionStatus.FAILED);
    }

    @Test
    void testTransitions() {
        // Test valid transitions as per methodology doc:
        // HEALTHY -> SUSPECTED (barrier timeout detected)
        // SUSPECTED -> FAILED (failure confirmation threshold exceeded)
        // SUSPECTED -> HEALTHY (false alarm, partition responds)

        var healthy = PartitionStatus.HEALTHY;
        var suspected = PartitionStatus.SUSPECTED;
        var failed = PartitionStatus.FAILED;

        // Verify enum ordering matches severity
        assertThat(healthy.ordinal()).isLessThan(suspected.ordinal());
        assertThat(suspected.ordinal()).isLessThan(failed.ordinal());

        // Verify toString returns the name
        assertThat(healthy.toString()).isEqualTo("HEALTHY");
        assertThat(suspected.toString()).isEqualTo("SUSPECTED");
        assertThat(failed.toString()).isEqualTo("FAILED");

        // Verify all states are distinct
        assertThat(healthy).isNotEqualTo(suspected);
        assertThat(suspected).isNotEqualTo(failed);
        assertThat(failed).isNotEqualTo(healthy);
    }
}
