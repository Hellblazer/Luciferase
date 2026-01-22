/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.balancing;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration options for parallel balancing operations.
 *
 * <p>This configuration controls the behavior of distributed tree balancing across
 * multiple partitions, including termination criteria, timeouts, and performance tuning.
 *
 * <p>Thread-safe and immutable after construction.
 *
 * @author hal.hildebrand
 */
public final class BalanceConfiguration {

    /** Default maximum number of balancing rounds (O(log P) expected) */
    public static final int DEFAULT_MAX_ROUNDS = 10;

    /** Default timeout per round */
    public static final Duration DEFAULT_TIMEOUT_PER_ROUND = Duration.ofSeconds(5);

    /** Default batch size for ghost exchanges */
    public static final int DEFAULT_BATCH_SIZE = 100;

    /** Default refinement threshold (20% imbalance tolerance) */
    public static final double DEFAULT_REFINEMENT_THRESHOLD = 0.2;

    private final int maxRounds;
    private final Duration timeoutPerRound;
    private final int batchSize;
    private final double refinementThreshold;

    /**
     * Create a new balance configuration with specified parameters.
     *
     * @param maxRounds the maximum number of balancing rounds
     * @param timeoutPerRound the timeout for each round
     * @param batchSize the batch size for ghost exchanges
     * @param refinementThreshold the refinement threshold (0.0 to 1.0)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public BalanceConfiguration(int maxRounds, Duration timeoutPerRound, int batchSize, double refinementThreshold) {
        // Check for null first before accessing methods
        Objects.requireNonNull(timeoutPerRound, "timeoutPerRound cannot be null");

        if (maxRounds <= 0) {
            throw new IllegalArgumentException("maxRounds must be positive: " + maxRounds);
        }
        if (timeoutPerRound.isNegative() || timeoutPerRound.isZero()) {
            throw new IllegalArgumentException("timeoutPerRound must be positive: " + timeoutPerRound);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        if (refinementThreshold < 0.0 || refinementThreshold > 1.0) {
            throw new IllegalArgumentException("refinementThreshold must be between 0.0 and 1.0: " + refinementThreshold);
        }

        this.maxRounds = maxRounds;
        this.timeoutPerRound = timeoutPerRound;
        this.batchSize = batchSize;
        this.refinementThreshold = refinementThreshold;
    }

    /**
     * Create a configuration with default values.
     *
     * @return a default configuration
     */
    public static BalanceConfiguration defaultConfig() {
        return new BalanceConfiguration(
            DEFAULT_MAX_ROUNDS,
            DEFAULT_TIMEOUT_PER_ROUND,
            DEFAULT_BATCH_SIZE,
            DEFAULT_REFINEMENT_THRESHOLD
        );
    }

    /**
     * Get the maximum number of balancing rounds.
     *
     * @return the maximum rounds
     */
    public int maxRounds() {
        return maxRounds;
    }

    /**
     * Get the timeout per round.
     *
     * @return the timeout duration
     */
    public Duration timeoutPerRound() {
        return timeoutPerRound;
    }

    /**
     * Get the batch size for ghost exchanges.
     *
     * @return the batch size
     */
    public int batchSize() {
        return batchSize;
    }

    /**
     * Get the refinement threshold.
     *
     * @return the refinement threshold (0.0 to 1.0)
     */
    public double refinementThreshold() {
        return refinementThreshold;
    }

    /**
     * Create a new configuration with a different max rounds.
     *
     * @param newMaxRounds the new max rounds value
     * @return a new configuration with the updated value
     */
    public BalanceConfiguration withMaxRounds(int newMaxRounds) {
        return new BalanceConfiguration(newMaxRounds, timeoutPerRound, batchSize, refinementThreshold);
    }

    /**
     * Create a new configuration with a different timeout per round.
     *
     * @param newTimeout the new timeout value
     * @return a new configuration with the updated value
     */
    public BalanceConfiguration withTimeoutPerRound(Duration newTimeout) {
        return new BalanceConfiguration(maxRounds, newTimeout, batchSize, refinementThreshold);
    }

    /**
     * Create a new configuration with a different batch size.
     *
     * @param newBatchSize the new batch size value
     * @return a new configuration with the updated value
     */
    public BalanceConfiguration withBatchSize(int newBatchSize) {
        return new BalanceConfiguration(maxRounds, timeoutPerRound, newBatchSize, refinementThreshold);
    }

    /**
     * Create a new configuration with a different refinement threshold.
     *
     * @param newThreshold the new refinement threshold
     * @return a new configuration with the updated value
     */
    public BalanceConfiguration withRefinementThreshold(double newThreshold) {
        return new BalanceConfiguration(maxRounds, timeoutPerRound, batchSize, newThreshold);
    }

    @Override
    public String toString() {
        return String.format("BalanceConfiguration[maxRounds=%d, timeoutPerRound=%s, batchSize=%d, refinementThreshold=%.2f]",
                           maxRounds, timeoutPerRound, batchSize, refinementThreshold);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        var other = (BalanceConfiguration) obj;
        return maxRounds == other.maxRounds &&
               batchSize == other.batchSize &&
               Double.compare(refinementThreshold, other.refinementThreshold) == 0 &&
               timeoutPerRound.equals(other.timeoutPerRound);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxRounds, timeoutPerRound, batchSize, refinementThreshold);
    }
}
