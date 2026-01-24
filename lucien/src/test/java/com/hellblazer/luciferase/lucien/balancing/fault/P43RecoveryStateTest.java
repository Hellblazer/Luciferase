package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for RecoveryState record (P4.3.2).
 * Tests the immutable record pattern with metadata support.
 */
class P43RecoveryStateTest {

    @Test
    void testRecordCreation_WithDefaultValues() {
        // Given
        var partitionId = UUID.randomUUID();
        var phase = RecoveryPhase.IDLE;
        var attemptCount = 0;
        var lastAttemptTime = System.currentTimeMillis();
        Map<String, Object> metadata = Map.of("key", "value");

        // When
        var state = new RecoveryState(partitionId, phase, attemptCount, lastAttemptTime, metadata);

        // Then
        assertEquals(partitionId, state.partitionId());
        assertEquals(phase, state.currentPhase());
        assertEquals(attemptCount, state.attemptCount());
        assertEquals(lastAttemptTime, state.lastAttemptTime());
        assertEquals(metadata, state.metadata());
    }

    @Test
    void testStateTransitions_VerifyImmutability() {
        // Given
        var partitionId = UUID.randomUUID();
        var initialTime = 1000L;
        var initialState = new RecoveryState(
            partitionId,
            RecoveryPhase.IDLE,
            0,
            initialTime,
            new HashMap<String, Object>()
        );

        // When - transition to next phase
        var newTime = 2000L;
        var nextState = initialState.withPhase(RecoveryPhase.DETECTING, newTime);

        // Then - original state unchanged (immutability)
        assertEquals(RecoveryPhase.IDLE, initialState.currentPhase());
        assertEquals(initialTime, initialState.lastAttemptTime());

        // And new state has updated values
        assertEquals(RecoveryPhase.DETECTING, nextState.currentPhase());
        assertEquals(newTime, nextState.lastAttemptTime());
        assertEquals(partitionId, nextState.partitionId());
        assertEquals(0, nextState.attemptCount());
    }

    @Test
    void testMetadataStorage_AndRetrieval() {
        // Given
        var partitionId = UUID.randomUUID();
        var metadata = new HashMap<String, Object>();
        metadata.put("error", "Connection timeout");
        metadata.put("retryAfter", 5000);
        metadata.put("coordinator", UUID.randomUUID());

        // When
        var state = new RecoveryState(
            partitionId,
            RecoveryPhase.FAILED,
            3,
            System.currentTimeMillis(),
            metadata
        );

        // Then
        assertEquals("Connection timeout", state.metadata().get("error"));
        assertEquals(5000, state.metadata().get("retryAfter"));
        assertNotNull(state.metadata().get("coordinator"));
    }

    @Test
    void testWithMetadata_CreatesNewStateWithUpdatedMetadata() {
        // Given
        var partitionId = UUID.randomUUID();
        var initialState = new RecoveryState(
            partitionId,
            RecoveryPhase.REDISTRIBUTING,
            1,
            System.currentTimeMillis(),
            Map.<String, Object>of("initial", "value")
        );

        // When - add new metadata
        var newMetadata = new HashMap<String, Object>();
        newMetadata.put("initial", "value");
        newMetadata.put("progress", 0.75);
        var updatedState = initialState.withMetadata(newMetadata);

        // Then - original unchanged
        assertEquals(1, initialState.metadata().size());
        assertEquals("value", initialState.metadata().get("initial"));
        assertNull(initialState.metadata().get("progress"));

        // And new state has both values
        assertEquals(2, updatedState.metadata().size());
        assertEquals("value", updatedState.metadata().get("initial"));
        assertEquals(0.75, updatedState.metadata().get("progress"));
    }

    @Test
    void testWithIncrementedAttempt_IncrementsCount() {
        // Given
        var partitionId = UUID.randomUUID();
        var initialState = new RecoveryState(
            partitionId,
            RecoveryPhase.DETECTING,
            0,
            1000L,
            Map.<String, Object>of()
        );

        // When
        var attempt1 = initialState.withIncrementedAttempt(1500L);
        var attempt2 = attempt1.withIncrementedAttempt(2000L);

        // Then
        assertEquals(0, initialState.attemptCount());
        assertEquals(1, attempt1.attemptCount());
        assertEquals(2, attempt2.attemptCount());

        // And time is updated
        assertEquals(1000L, initialState.lastAttemptTime());
        assertEquals(1500L, attempt1.lastAttemptTime());
        assertEquals(2000L, attempt2.lastAttemptTime());
    }

    @Test
    void testToString_ContainsAllFields() {
        // Given
        var partitionId = UUID.randomUUID();
        var state = new RecoveryState(
            partitionId,
            RecoveryPhase.VALIDATING,
            2,
            12345L,
            Map.<String, Object>of("test", "value")
        );

        // When
        var str = state.toString();

        // Then - record toString includes all components
        assertTrue(str.contains(partitionId.toString()));
        assertTrue(str.contains("VALIDATING"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("12345"));
    }

    @Test
    void testEqualsAndHashCode_RecordSemantics() {
        // Given
        var partitionId = UUID.randomUUID();
        var state1 = new RecoveryState(
            partitionId,
            RecoveryPhase.COMPLETE,
            3,
            5000L,
            Map.<String, Object>of("key", "value")
        );
        var state2 = new RecoveryState(
            partitionId,
            RecoveryPhase.COMPLETE,
            3,
            5000L,
            Map.<String, Object>of("key", "value")
        );
        var state3 = new RecoveryState(
            partitionId,
            RecoveryPhase.FAILED,  // Different phase
            3,
            5000L,
            Map.<String, Object>of("key", "value")
        );

        // Then
        assertEquals(state1, state2);
        assertEquals(state1.hashCode(), state2.hashCode());
        assertNotEquals(state1, state3);
    }

    @Test
    void testValidation_RejectsNullPartitionId() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            new RecoveryState(null, RecoveryPhase.IDLE, 0, 0L, Map.<String, Object>of())
        );
    }

    @Test
    void testValidation_RejectsNullPhase() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            new RecoveryState(UUID.randomUUID(), null, 0, 0L, Map.<String, Object>of())
        );
    }

    @Test
    void testValidation_RejectsNullMetadata() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            new RecoveryState(UUID.randomUUID(), RecoveryPhase.IDLE, 0, 0L, null)
        );
    }

    @Test
    void testValidation_RejectsNegativeAttemptCount() {
        // When/Then
        var exception = assertThrows(IllegalArgumentException.class, () ->
            new RecoveryState(UUID.randomUUID(), RecoveryPhase.IDLE, -1, 0L, Map.<String, Object>of())
        );
        assertTrue(exception.getMessage().contains("attemptCount"));
    }
}
