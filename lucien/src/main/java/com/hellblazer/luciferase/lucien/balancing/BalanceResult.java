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

import java.util.Objects;

/**
 * Result of a parallel balancing operation.
 *
 * <p>Contains success status, final metrics snapshot, and diagnostic information
 * about the balancing process. Immutable after construction.
 *
 * @param successful whether the balance operation succeeded
 * @param finalMetrics a snapshot of the final metrics
 * @param refinementsApplied the total number of refinements applied
 * @param message a diagnostic message describing the result
 * @author hal.hildebrand
 */
public record BalanceResult(
    boolean successful,
    BalanceMetrics.Snapshot finalMetrics,
    int refinementsApplied,
    String message
) {

    /**
     * Create a new balance result.
     *
     * @param successful whether the balance operation succeeded
     * @param finalMetrics a snapshot of the final metrics
     * @param refinementsApplied the total number of refinements applied
     * @param message a diagnostic message describing the result
     * @throws IllegalArgumentException if refinementsApplied is negative
     * @throws NullPointerException if finalMetrics or message is null
     */
    public BalanceResult {
        Objects.requireNonNull(finalMetrics, "finalMetrics cannot be null");
        Objects.requireNonNull(message, "message cannot be null");

        if (refinementsApplied < 0) {
            throw new IllegalArgumentException("refinementsApplied cannot be negative: " + refinementsApplied);
        }
    }

    /**
     * Create a successful balance result.
     *
     * @param finalMetrics the final metrics snapshot
     * @param refinementsApplied the number of refinements applied
     * @return a successful result
     */
    public static BalanceResult success(BalanceMetrics.Snapshot finalMetrics, int refinementsApplied) {
        return new BalanceResult(
            true,
            finalMetrics,
            refinementsApplied,
            String.format("Balance completed successfully in %d rounds with %d refinements",
                         finalMetrics.roundCount(), refinementsApplied)
        );
    }

    /**
     * Create a failure balance result.
     *
     * @param finalMetrics the final metrics snapshot
     * @param reason the failure reason
     * @return a failure result
     */
    public static BalanceResult failure(BalanceMetrics.Snapshot finalMetrics, String reason) {
        return new BalanceResult(
            false,
            finalMetrics,
            finalMetrics.refinementsApplied(),
            reason
        );
    }

    /**
     * Create a timeout balance result.
     *
     * @param finalMetrics the final metrics snapshot
     * @param maxRounds the maximum rounds that were configured
     * @return a timeout result
     */
    public static BalanceResult timeout(BalanceMetrics.Snapshot finalMetrics, int maxRounds) {
        return new BalanceResult(
            false,
            finalMetrics,
            finalMetrics.refinementsApplied(),
            String.format("Balance timed out after %d rounds (max: %d)",
                         finalMetrics.roundCount(), maxRounds)
        );
    }

    /**
     * Check if the balance converged (successful with refinements applied).
     *
     * @return true if converged
     */
    public boolean converged() {
        return successful && refinementsApplied > 0;
    }

    /**
     * Check if the balance had no work to do (successful with no refinements).
     *
     * @return true if no work was needed
     */
    public boolean noWorkNeeded() {
        return successful && refinementsApplied == 0;
    }

    @Override
    public String toString() {
        return String.format("BalanceResult[successful=%s, rounds=%d, refinements=%d, message='%s']",
                           successful, finalMetrics.roundCount(), refinementsApplied, message);
    }
}
