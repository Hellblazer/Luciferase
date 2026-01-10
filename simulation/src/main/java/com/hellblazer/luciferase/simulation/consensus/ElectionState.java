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

/**
 * Three-state model for coordinator election protocol.
 *
 * State transitions:
 * - FOLLOWER → CANDIDATE: Election timeout triggered
 * - CANDIDATE → LEADER: Won majority votes
 * - CANDIDATE → FOLLOWER: Received AppendHeartbeat from valid leader
 * - LEADER → FOLLOWER: Received AppendHeartbeat from higher term
 *
 * @author hal.hildebrand
 */
public enum ElectionState {
    /**
     * Default state. Listens for leader heartbeat. If no heartbeat received
     * within election timeout, transitions to CANDIDATE.
     */
    FOLLOWER("follower"),

    /**
     * Actively seeking votes for leadership. Resets election timeout after
     * voting for self. If receives majority votes, transitions to LEADER.
     * If receives AppendHeartbeat from valid leader, transitions to FOLLOWER.
     */
    CANDIDATE("candidate"),

    /**
     * Won election and is the authoritative leader. Broadcasts heartbeats
     * periodically. If detects higher term, transitions to FOLLOWER.
     */
    LEADER("leader");

    private final String state;

    ElectionState(String state) {
        this.state = state;
    }

    public String getState() {
        return state;
    }

    /**
     * Parse state from string representation.
     *
     * @param state String representation
     * @return ElectionState or null if invalid
     */
    public static ElectionState fromString(String state) {
        if (state == null) {
            return null;
        }
        for (ElectionState s : ElectionState.values()) {
            if (s.state.equalsIgnoreCase(state)) {
                return s;
            }
        }
        return null;
    }
}
