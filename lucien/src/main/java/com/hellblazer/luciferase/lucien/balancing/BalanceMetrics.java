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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Performance tracking metrics for parallel balancing operations.
 *
 * <p>This class tracks round counts, timing information, and refinement operations
 * during distributed tree balancing. All operations are thread-safe for concurrent
 * updates during balancing.
 *
 * <p>Thread-safe: All methods use atomic operations for concurrent access.
 *
 * @author hal.hildebrand
 */
public final class BalanceMetrics {

    // Round tracking
    private final AtomicInteger roundCount = new AtomicInteger(0);

    // Timing information (nanoseconds for precision)
    private final LongAdder totalTimeNanos = new LongAdder();
    private final AtomicLong minRoundTimeNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxRoundTimeNanos = new AtomicLong(0);

    // Refinement tracking
    private final AtomicInteger refinementsRequested = new AtomicInteger(0);
    private final AtomicInteger refinementsApplied = new AtomicInteger(0);

    /**
     * Create a new balance metrics tracker.
     */
    public BalanceMetrics() {
        // Initialize with zero state
    }

    /**
     * Record the completion of a balancing round.
     *
     * @param roundDuration the duration of the round
     */
    public void recordRound(Duration roundDuration) {
        roundCount.incrementAndGet();

        var nanos = roundDuration.toNanos();
        totalTimeNanos.add(nanos);

        // Update min
        minRoundTimeNanos.updateAndGet(current -> Math.min(current, nanos));

        // Update max
        maxRoundTimeNanos.updateAndGet(current -> Math.max(current, nanos));
    }

    /**
     * Record a refinement request.
     */
    public void recordRefinementRequested() {
        refinementsRequested.incrementAndGet();
    }

    /**
     * Record a refinement application.
     */
    public void recordRefinementApplied() {
        refinementsApplied.incrementAndGet();
    }

    /**
     * Get the total number of rounds completed.
     *
     * @return the round count
     */
    public int roundCount() {
        return roundCount.get();
    }

    /**
     * Get the total time spent across all rounds.
     *
     * @return the total duration
     */
    public Duration totalTime() {
        return Duration.ofNanos(totalTimeNanos.sum());
    }

    /**
     * Get the average time per round.
     *
     * @return the average duration, or Duration.ZERO if no rounds completed
     */
    public Duration averageRoundTime() {
        var rounds = roundCount.get();
        if (rounds == 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(totalTimeNanos.sum() / rounds);
    }

    /**
     * Get the minimum round time observed.
     *
     * @return the minimum duration, or Duration.ZERO if no rounds completed
     */
    public Duration minRoundTime() {
        var min = minRoundTimeNanos.get();
        if (min == Long.MAX_VALUE) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(min);
    }

    /**
     * Get the maximum round time observed.
     *
     * @return the maximum duration
     */
    public Duration maxRoundTime() {
        return Duration.ofNanos(maxRoundTimeNanos.get());
    }

    /**
     * Get the number of refinements requested.
     *
     * @return the refinement request count
     */
    public int refinementsRequested() {
        return refinementsRequested.get();
    }

    /**
     * Get the number of refinements applied.
     *
     * @return the refinement application count
     */
    public int refinementsApplied() {
        return refinementsApplied.get();
    }

    /**
     * Get the refinement application rate.
     *
     * @return the rate (0.0 to 1.0), or 0.0 if no refinements requested
     */
    public double refinementApplicationRate() {
        var requested = refinementsRequested.get();
        if (requested == 0) {
            return 0.0;
        }
        return (double) refinementsApplied.get() / requested;
    }

    /**
     * Reset all metrics to zero state.
     */
    public void reset() {
        roundCount.set(0);
        totalTimeNanos.reset();
        minRoundTimeNanos.set(Long.MAX_VALUE);
        maxRoundTimeNanos.set(0);
        refinementsRequested.set(0);
        refinementsApplied.set(0);
    }

    /**
     * Create a snapshot of current metrics.
     *
     * <p>The snapshot is a point-in-time view and will not reflect subsequent updates.
     *
     * @return an immutable snapshot
     */
    public Snapshot snapshot() {
        return new Snapshot(
            roundCount.get(),
            Duration.ofNanos(totalTimeNanos.sum()),
            minRoundTime(),
            maxRoundTime(),
            averageRoundTime(),
            refinementsRequested.get(),
            refinementsApplied.get()
        );
    }

    @Override
    public String toString() {
        return String.format(
            "BalanceMetrics[rounds=%d, totalTime=%s, avgTime=%s, minTime=%s, maxTime=%s, requested=%d, applied=%d]",
            roundCount(), totalTime(), averageRoundTime(), minRoundTime(), maxRoundTime(),
            refinementsRequested(), refinementsApplied()
        );
    }

    /**
     * Immutable snapshot of balance metrics at a point in time.
     *
     * @param roundCount the number of rounds completed
     * @param totalTime the total time spent
     * @param minRoundTime the minimum round time
     * @param maxRoundTime the maximum round time
     * @param averageRoundTime the average round time
     * @param refinementsRequested the number of refinements requested
     * @param refinementsApplied the number of refinements applied
     */
    public record Snapshot(
        int roundCount,
        Duration totalTime,
        Duration minRoundTime,
        Duration maxRoundTime,
        Duration averageRoundTime,
        int refinementsRequested,
        int refinementsApplied
    ) {
        /**
         * Get the refinement application rate.
         *
         * @return the rate (0.0 to 1.0), or 0.0 if no refinements requested
         */
        public double refinementApplicationRate() {
            if (refinementsRequested == 0) {
                return 0.0;
            }
            return (double) refinementsApplied / refinementsRequested;
        }
    }
}
