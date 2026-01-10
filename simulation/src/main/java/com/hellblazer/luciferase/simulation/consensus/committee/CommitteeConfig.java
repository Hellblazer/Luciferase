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
 *
 * @param committeeSizeMin      Minimum committee size (default: 7, from bftSubset)
 * @param committeeSizeMax      Maximum committee size (default: 9)
 * @param votingTimeoutSeconds  How long to wait for quorum (default: 5 seconds)
 * @param requiredQuorumRatio   NOT USED - quorum inherited from context.toleranceLevel()
 * @author hal.hildebrand
 */
public record CommitteeConfig(
    int committeeSizeMin,
    int committeeSizeMax,
    int votingTimeoutSeconds,
    double requiredQuorumRatio  // Not used - inherited from context.toleranceLevel() + 1
) {

    /**
     * Create default configuration.
     * - Committee size: 7-9 nodes (bftSubset default)
     * - Voting timeout: 5 seconds
     * - Quorum ratio: 0.0 (not used, context.toleranceLevel() + 1 determines quorum)
     */
    public static CommitteeConfig defaultConfig() {
        return new CommitteeConfig(7, 9, 5, 0.0);
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
        private int committeeSizeMin = 7;
        private int committeeSizeMax = 9;
        private int votingTimeoutSeconds = 5;
        private double requiredQuorumRatio = 0.0;  // Not used

        public Builder committeeSizeMin(int min) {
            this.committeeSizeMin = min;
            return this;
        }

        public Builder committeeSizeMax(int max) {
            this.committeeSizeMax = max;
            return this;
        }

        public Builder votingTimeoutSeconds(int timeout) {
            this.votingTimeoutSeconds = timeout;
            return this;
        }

        public Builder requiredQuorumRatio(double ratio) {
            this.requiredQuorumRatio = ratio;
            return this;
        }

        public CommitteeConfig build() {
            return new CommitteeConfig(committeeSizeMin, committeeSizeMax, votingTimeoutSeconds, requiredQuorumRatio);
        }
    }
}
