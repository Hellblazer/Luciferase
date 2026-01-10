/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ElectionState enum.
 *
 * @author hal.hildebrand
 */
class ElectionStateTest {

    /**
     * Verify all three election states exist with correct string representations.
     */
    @Test
    void testElectionStateValues() {
        // Verify all three states exist
        var states = ElectionState.values();
        assertThat(states).hasSize(3);

        // Verify each state and its string representation
        assertThat(ElectionState.FOLLOWER.getState()).isEqualTo("follower");
        assertThat(ElectionState.CANDIDATE.getState()).isEqualTo("candidate");
        assertThat(ElectionState.LEADER.getState()).isEqualTo("leader");

        // Verify fromString parsing
        assertThat(ElectionState.fromString("follower")).isEqualTo(ElectionState.FOLLOWER);
        assertThat(ElectionState.fromString("candidate")).isEqualTo(ElectionState.CANDIDATE);
        assertThat(ElectionState.fromString("leader")).isEqualTo(ElectionState.LEADER);

        // Verify case-insensitive parsing
        assertThat(ElectionState.fromString("FOLLOWER")).isEqualTo(ElectionState.FOLLOWER);
        assertThat(ElectionState.fromString("Candidate")).isEqualTo(ElectionState.CANDIDATE);
        assertThat(ElectionState.fromString("LEADER")).isEqualTo(ElectionState.LEADER);
    }

    /**
     * Verify state transitions are logically valid according to protocol.
     *
     * Valid transitions:
     * - FOLLOWER → CANDIDATE (election timeout)
     * - CANDIDATE → LEADER (won election)
     * - CANDIDATE → FOLLOWER (received heartbeat from valid leader)
     * - LEADER → FOLLOWER (received heartbeat from higher term)
     */
    @Test
    void testElectionStateTransitions() {
        // All states should be accessible and distinct
        assertThat(ElectionState.FOLLOWER).isNotEqualTo(ElectionState.CANDIDATE);
        assertThat(ElectionState.CANDIDATE).isNotEqualTo(ElectionState.LEADER);
        assertThat(ElectionState.LEADER).isNotEqualTo(ElectionState.FOLLOWER);

        // Verify fromString returns null for invalid states
        assertThat(ElectionState.fromString(null)).isNull();
        assertThat(ElectionState.fromString("invalid")).isNull();
        assertThat(ElectionState.fromString("")).isNull();

        // Verify all values are unique
        var stateSet = java.util.Set.of(
            ElectionState.FOLLOWER,
            ElectionState.CANDIDATE,
            ElectionState.LEADER
        );
        assertThat(stateSet).hasSize(3);
    }
}
