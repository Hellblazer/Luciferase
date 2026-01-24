package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Phase 4.1 RecoveryPhase enum.
 *
 * Verifies:
 * - All 7 phases exist (IDLE, DETECTING, REDISTRIBUTING, REBALANCING, VALIDATING, COMPLETE, FAILED)
 * - Valid state machine transitions
 * - Invalid transitions are properly identified
 */
class Phase41RecoveryPhaseTest {

    @Test
    void testEnumValues() {
        // Verify exactly 7 phases as per plan spec
        var values = RecoveryPhase.values();
        assertThat(values).hasSize(7);

        // Verify all required phases exist
        assertThat(values).contains(
            RecoveryPhase.IDLE,
            RecoveryPhase.DETECTING,
            RecoveryPhase.REDISTRIBUTING,
            RecoveryPhase.REBALANCING,
            RecoveryPhase.VALIDATING,
            RecoveryPhase.COMPLETE,
            RecoveryPhase.FAILED
        );

        // Verify we can get enum by name
        assertThat(RecoveryPhase.valueOf("IDLE")).isEqualTo(RecoveryPhase.IDLE);
        assertThat(RecoveryPhase.valueOf("DETECTING")).isEqualTo(RecoveryPhase.DETECTING);
        assertThat(RecoveryPhase.valueOf("REDISTRIBUTING")).isEqualTo(RecoveryPhase.REDISTRIBUTING);
        assertThat(RecoveryPhase.valueOf("REBALANCING")).isEqualTo(RecoveryPhase.REBALANCING);
        assertThat(RecoveryPhase.valueOf("VALIDATING")).isEqualTo(RecoveryPhase.VALIDATING);
        assertThat(RecoveryPhase.valueOf("COMPLETE")).isEqualTo(RecoveryPhase.COMPLETE);
        assertThat(RecoveryPhase.valueOf("FAILED")).isEqualTo(RecoveryPhase.FAILED);
    }

    @Test
    void testStateTransitions() {
        // Test valid state machine transitions as per methodology doc:
        // IDLE -> DETECTING -> REDISTRIBUTING -> REBALANCING -> VALIDATING -> COMPLETE -> IDLE
        // Any phase can transition to FAILED
        // FAILED -> IDLE (retry)

        var idle = RecoveryPhase.IDLE;
        var detecting = RecoveryPhase.DETECTING;
        var redistributing = RecoveryPhase.REDISTRIBUTING;
        var rebalancing = RecoveryPhase.REBALANCING;
        var validating = RecoveryPhase.VALIDATING;
        var complete = RecoveryPhase.COMPLETE;
        var failed = RecoveryPhase.FAILED;

        // Verify terminal states
        assertThat(isTerminalState(idle)).isTrue();
        assertThat(isTerminalState(complete)).isTrue();
        assertThat(isTerminalState(failed)).isTrue();

        // Verify active states
        assertThat(isTerminalState(detecting)).isFalse();
        assertThat(isTerminalState(redistributing)).isFalse();
        assertThat(isTerminalState(rebalancing)).isFalse();
        assertThat(isTerminalState(validating)).isFalse();

        // Verify valid transitions (according to phase sequence)
        assertThat(isValidTransition(idle, detecting)).isTrue();
        assertThat(isValidTransition(detecting, redistributing)).isTrue();
        assertThat(isValidTransition(redistributing, rebalancing)).isTrue();
        assertThat(isValidTransition(rebalancing, validating)).isTrue();
        assertThat(isValidTransition(validating, complete)).isTrue();
        assertThat(isValidTransition(complete, idle)).isTrue();
        assertThat(isValidTransition(failed, idle)).isTrue();

        // Any active phase can transition to FAILED
        assertThat(isValidTransition(detecting, failed)).isTrue();
        assertThat(isValidTransition(redistributing, failed)).isTrue();
        assertThat(isValidTransition(rebalancing, failed)).isTrue();
        assertThat(isValidTransition(validating, failed)).isTrue();
    }

    @Test
    void testInvalidTransitions() {
        // Test transitions that should be invalid

        // Cannot skip phases in normal flow
        assertThat(isValidTransition(RecoveryPhase.IDLE, RecoveryPhase.REDISTRIBUTING)).isFalse();
        assertThat(isValidTransition(RecoveryPhase.DETECTING, RecoveryPhase.REBALANCING)).isFalse();
        assertThat(isValidTransition(RecoveryPhase.REDISTRIBUTING, RecoveryPhase.VALIDATING)).isFalse();

        // Cannot go backwards in normal flow
        assertThat(isValidTransition(RecoveryPhase.REDISTRIBUTING, RecoveryPhase.DETECTING)).isFalse();
        assertThat(isValidTransition(RecoveryPhase.COMPLETE, RecoveryPhase.VALIDATING)).isFalse();

        // Cannot transition from terminal states except to IDLE
        assertThat(isValidTransition(RecoveryPhase.COMPLETE, RecoveryPhase.DETECTING)).isFalse();
        assertThat(isValidTransition(RecoveryPhase.FAILED, RecoveryPhase.DETECTING)).isFalse();

        // Cannot stay in IDLE
        assertThat(isValidTransition(RecoveryPhase.IDLE, RecoveryPhase.IDLE)).isFalse();
    }

    // Helper methods for transition validation
    private boolean isTerminalState(RecoveryPhase phase) {
        return phase == RecoveryPhase.IDLE
            || phase == RecoveryPhase.COMPLETE
            || phase == RecoveryPhase.FAILED;
    }

    private boolean isValidTransition(RecoveryPhase from, RecoveryPhase to) {
        // Define valid transitions according to state machine
        return switch (from) {
            case IDLE -> to == RecoveryPhase.DETECTING;
            case DETECTING -> to == RecoveryPhase.REDISTRIBUTING || to == RecoveryPhase.FAILED;
            case REDISTRIBUTING -> to == RecoveryPhase.REBALANCING || to == RecoveryPhase.FAILED;
            case REBALANCING -> to == RecoveryPhase.VALIDATING || to == RecoveryPhase.FAILED;
            case VALIDATING -> to == RecoveryPhase.COMPLETE || to == RecoveryPhase.FAILED;
            case COMPLETE -> to == RecoveryPhase.IDLE;
            case FAILED -> to == RecoveryPhase.IDLE;
        };
    }
}
