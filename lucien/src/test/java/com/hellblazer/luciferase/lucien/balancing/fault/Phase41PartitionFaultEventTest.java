package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Phase 4.1 PartitionFaultEvent sealed interface.
 *
 * Verifies:
 * - Sealed interface with exactly 3 permitted subtypes
 * - Pattern matching works correctly
 * - Record subtypes have correct structure
 */
class Phase41PartitionFaultEventTest {

    @Test
    void testSealedSubtypes() {
        // Create instances of all 3 subtypes
        var partitionId = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();

        var suspected = new PartitionFaultEvent.PartitionSuspected(partitionId, timestamp, "Barrier timeout");
        var failed = new PartitionFaultEvent.PartitionFailed(partitionId, timestamp, "Consecutive timeouts");
        var recovered = new PartitionFaultEvent.PartitionRecovered(partitionId, timestamp);

        // Verify all are instances of PartitionFaultEvent
        assertThat(suspected).isInstanceOf(PartitionFaultEvent.class);
        assertThat(failed).isInstanceOf(PartitionFaultEvent.class);
        assertThat(recovered).isInstanceOf(PartitionFaultEvent.class);

        // Verify record accessors work
        assertThat(suspected.partitionId()).isEqualTo(partitionId);
        assertThat(suspected.timestamp()).isEqualTo(timestamp);
        assertThat(suspected.reason()).isEqualTo("Barrier timeout");

        assertThat(failed.partitionId()).isEqualTo(partitionId);
        assertThat(failed.timestamp()).isEqualTo(timestamp);
        assertThat(failed.reason()).isEqualTo("Consecutive timeouts");

        assertThat(recovered.partitionId()).isEqualTo(partitionId);
        assertThat(recovered.timestamp()).isEqualTo(timestamp);

        // Verify records are distinct types
        assertThat(suspected.getClass()).isNotEqualTo(failed.getClass());
        assertThat(failed.getClass()).isNotEqualTo(recovered.getClass());
    }

    @Test
    void testPatternMatching() {
        // Test pattern matching with switch expressions (Java 17+)
        var partitionId = UUID.randomUUID();
        var timestamp = System.currentTimeMillis();

        // Test PartitionSuspected
        PartitionFaultEvent suspectedEvent = new PartitionFaultEvent.PartitionSuspected(
            partitionId, timestamp, "Test suspect"
        );

        String result1 = switch (suspectedEvent) {
            case PartitionFaultEvent.PartitionSuspected e -> "SUSPECTED: " + e.reason();
            case PartitionFaultEvent.PartitionFailed e -> "FAILED: " + e.reason();
            case PartitionFaultEvent.PartitionRecovered e -> "RECOVERED";
        };
        assertThat(result1).isEqualTo("SUSPECTED: Test suspect");

        // Test PartitionFailed
        PartitionFaultEvent failedEvent = new PartitionFaultEvent.PartitionFailed(
            partitionId, timestamp, "Test failure"
        );

        String result2 = switch (failedEvent) {
            case PartitionFaultEvent.PartitionSuspected e -> "SUSPECTED: " + e.reason();
            case PartitionFaultEvent.PartitionFailed e -> "FAILED: " + e.reason();
            case PartitionFaultEvent.PartitionRecovered e -> "RECOVERED";
        };
        assertThat(result2).isEqualTo("FAILED: Test failure");

        // Test PartitionRecovered
        PartitionFaultEvent recoveredEvent = new PartitionFaultEvent.PartitionRecovered(
            partitionId, timestamp
        );

        String result3 = switch (recoveredEvent) {
            case PartitionFaultEvent.PartitionSuspected e -> "SUSPECTED: " + e.reason();
            case PartitionFaultEvent.PartitionFailed e -> "FAILED: " + e.reason();
            case PartitionFaultEvent.PartitionRecovered e -> "RECOVERED";
        };
        assertThat(result3).isEqualTo("RECOVERED");

        // Verify partitionId extraction via pattern matching
        UUID extractedId = switch (suspectedEvent) {
            case PartitionFaultEvent.PartitionSuspected e -> e.partitionId();
            case PartitionFaultEvent.PartitionFailed e -> e.partitionId();
            case PartitionFaultEvent.PartitionRecovered e -> e.partitionId();
        };
        assertThat(extractedId).isEqualTo(partitionId);
    }
}
