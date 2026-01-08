/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.ghost;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for duplicate entity detection and reconciliation.
 * <p>
 * Tracks:
 * <ul>
 *   <li>Total duplicates detected across all ticks</li>
 *   <li>Total duplicates resolved (reconciled)</li>
 *   <li>Cumulative resolution delay in ticks</li>
 *   <li>Maximum cascading duplicates observed (single entity in N bubbles)</li>
 * </ul>
 * <p>
 * Thread-safe: Uses atomic variables for concurrent updates.
 * <p>
 * Usage:
 * <pre>
 * var metrics = new DuplicateEntityMetrics();
 * metrics.recordDetected(3);  // Entity found in 3 bubbles
 * metrics.recordResolved(2);  // Removed from 2 bubbles
 * metrics.recordResolutionDelay(5);  // Took 5 ticks to detect
 * </pre>
 *
 * @author hal.hildebrand
 */
public class DuplicateEntityMetrics {

    private final AtomicLong duplicatesDetected;
    private final AtomicLong duplicatesResolved;
    private final AtomicLong resolutionDelayTicks;
    private final AtomicInteger maxCascadingDuplicates;

    /**
     * Create a new metrics tracker.
     */
    public DuplicateEntityMetrics() {
        this.duplicatesDetected = new AtomicLong(0);
        this.duplicatesResolved = new AtomicLong(0);
        this.resolutionDelayTicks = new AtomicLong(0);
        this.maxCascadingDuplicates = new AtomicInteger(0);
    }

    /**
     * Record duplicate detection.
     * <p>
     * Call this when a duplicate entity is found during scanning.
     *
     * @param locationCount Number of bubbles containing the duplicate (>= 2)
     */
    public void recordDetected(int locationCount) {
        if (locationCount < 2) {
            throw new IllegalArgumentException("Duplicate must be in at least 2 locations");
        }

        duplicatesDetected.incrementAndGet();

        // Update max cascading duplicates if this is higher
        updateMax(maxCascadingDuplicates, locationCount);
    }

    /**
     * Record duplicate resolution.
     * <p>
     * Call this when duplicate has been reconciled (removed from non-source bubbles).
     *
     * @param removedCount Number of duplicate copies removed (>= 1)
     */
    public void recordResolved(int removedCount) {
        if (removedCount < 1) {
            throw new IllegalArgumentException("Must remove at least 1 duplicate copy");
        }

        duplicatesResolved.addAndGet(removedCount);
    }

    /**
     * Record resolution delay.
     * <p>
     * Tracks how many ticks elapsed between duplicate creation and detection.
     *
     * @param delayTicks Number of ticks delay (>= 0)
     */
    public void recordResolutionDelay(long delayTicks) {
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Delay cannot be negative");
        }

        resolutionDelayTicks.addAndGet(delayTicks);
    }

    /**
     * Get total duplicates detected.
     *
     * @return Count of duplicate entities found
     */
    public long getDuplicatesDetected() {
        return duplicatesDetected.get();
    }

    /**
     * Get total duplicates resolved.
     *
     * @return Count of duplicate copies removed
     */
    public long getDuplicatesResolved() {
        return duplicatesResolved.get();
    }

    /**
     * Get cumulative resolution delay.
     *
     * @return Total ticks delay across all duplicates
     */
    public long getResolutionDelayTicks() {
        return resolutionDelayTicks.get();
    }

    /**
     * Get maximum cascading duplicates observed.
     *
     * @return Highest number of bubbles containing a single entity
     */
    public int getMaxCascadingDuplicates() {
        return maxCascadingDuplicates.get();
    }

    /**
     * Get average resolution delay.
     *
     * @return Average ticks to detect duplicate, or 0 if none detected
     */
    public double getAverageResolutionDelay() {
        long detected = duplicatesDetected.get();
        if (detected == 0) {
            return 0.0;
        }
        return (double) resolutionDelayTicks.get() / detected;
    }

    /**
     * Reset all metrics to zero.
     */
    public void reset() {
        duplicatesDetected.set(0);
        duplicatesResolved.set(0);
        resolutionDelayTicks.set(0);
        maxCascadingDuplicates.set(0);
    }

    @Override
    public String toString() {
        return String.format(
            "DuplicateEntityMetrics{detected=%d, resolved=%d, avgDelayTicks=%.2f, maxCascading=%d}",
            duplicatesDetected.get(),
            duplicatesResolved.get(),
            getAverageResolutionDelay(),
            maxCascadingDuplicates.get()
        );
    }

    /**
     * Atomically update maximum value.
     */
    private void updateMax(AtomicInteger atomic, int newValue) {
        atomic.updateAndGet(current -> Math.max(current, newValue));
    }
}
