/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus.committee;

/**
 * Configuration for committee-based consensus voting.
 * <p>
 * Phase 7G Day 1: Committee Selector & Data Structures
 * <p>
 * The committee size and quorum are NOT configured here: the committee is drawn from
 * {@code context.bftSubset()} and the quorum is derived from
 * {@code context.toleranceLevel() + 1} (KerlDHT pattern). The former
 * {@code committeeSizeMin}, {@code committeeSizeMax} and {@code requiredQuorumRatio}
 * fields were dead — never read by production code — and were removed
 * (Luciferase-0frcy.91) to stop advertising configurability that does not exist.
 *
 * @param votingTimeoutSeconds  How long to wait for quorum (default: 5 seconds)
 * @author hal.hildebrand
 */
public record CommitteeConfig(
    int votingTimeoutSeconds
) {

    /**
     * Create default configuration.
     * - Voting timeout: 5 seconds
     * - Committee size / quorum derived from context (not configurable here)
     */
    public static CommitteeConfig defaultConfig() {
        return new CommitteeConfig(5);
    }

    /**
     * Create a builder for custom configuration.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder for CommitteeConfig.
     */
    public static class Builder {
        private int votingTimeoutSeconds = 5;

        public Builder votingTimeoutSeconds(int timeout) {
            this.votingTimeoutSeconds = timeout;
            return this;
        }

        public CommitteeConfig build() {
            return new CommitteeConfig(votingTimeoutSeconds);
        }
    }
}
