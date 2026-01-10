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
 * Timing strategy for P2P vote propagation.
 * <p>
 * Determines:
 * - Voting timeout (from CommitteeConfig)
 * - Resend strategy (exponential backoff for reliability)
 * <p>
 * Default behavior:
 * - Timeout: 5000ms (from config)
 * - Backoff: 100ms, 200ms, 400ms, 800ms, 1600ms (max 5 retries)
 * <p>
 * Phase 7G Day 2: Voting Protocol & Ballot Box
 *
 * @author hal.hildebrand
 */
public class PropagationStrategy {

    private static final int BASE_DELAY_MS = 100;
    private static final int MAX_RETRY_ATTEMPTS = 5;

    private final CommitteeConfig config;

    public PropagationStrategy(CommitteeConfig config) {
        this.config = config;
    }

    /**
     * Get voting timeout in milliseconds.
     * <p>
     * Default: 5000ms (5 seconds from CommitteeConfig)
     *
     * @return timeout in milliseconds
     */
    public long getVotingTimeoutMs() {
        return config.votingTimeoutSeconds() * 1000L;
    }

    /**
     * Should resend vote on this attempt?
     * <p>
     * Exponential backoff: attempt 1,2,3,4,5 → resend, attempt 6+ → no resend
     *
     * @param attempt retry attempt number (1-based)
     * @return true if should resend
     */
    public boolean shouldResendVote(int attempt) {
        return attempt > 0 && attempt <= MAX_RETRY_ATTEMPTS;
    }

    /**
     * Get delay before resending vote (exponential backoff).
     * <p>
     * - Attempt 1: 100ms
     * - Attempt 2: 200ms
     * - Attempt 3: 400ms
     * - Attempt 4: 800ms
     * - Attempt 5: 1600ms
     *
     * @param attempt retry attempt number (1-based)
     * @return delay in milliseconds
     */
    public long getResendDelayMs(int attempt) {
        if (attempt <= 0 || attempt > MAX_RETRY_ATTEMPTS) {
            return 0;
        }
        // Exponential backoff: 100 * 2^(attempt-1)
        return (long) (BASE_DELAY_MS * Math.pow(2, attempt - 1));
    }
}
