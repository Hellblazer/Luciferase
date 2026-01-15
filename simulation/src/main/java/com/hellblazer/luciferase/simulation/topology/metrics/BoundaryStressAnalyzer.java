/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology.metrics;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyzes boundary stress by tracking entity migration rates using sliding window.
 * <p>
 * Maintains migration event history per bubble and calculates migrations/second
 * within a configurable time window (default: 60 seconds). Identifies boundary
 * hotspots where high migration pressure suggests topology adaptation may be needed.
 * <p>
 * <b>Algorithm</b>:
 * <ul>
 *   <li>Record migration timestamps in sliding window per bubble</li>
 *   <li>Calculate migration rate = count / window duration</li>
 *   <li>Expire entries older than window size</li>
 *   <li>Identify hotspots (>10 migrations/second threshold)</li>
 * </ul>
 * <p>
 * <b>Memory Management</b>:
 * <ul>
 *   <li>Bounded window size prevents unbounded growth</li>
 *   <li>Periodic cleanup removes expired entries</li>
 *   <li>~8 bytes per migration event + map overhead</li>
 * </ul>
 * <p>
 * Thread-safe: Uses concurrent data structures for migration tracking.
 *
 * @author hal.hildebrand
 */
public class BoundaryStressAnalyzer {

    private final long                                        windowMillis;
    private final ConcurrentHashMap<UUID, List<Long>>        migrationEvents;
    private final ConcurrentHashMap<UUID, Long>              lastCleanupTime;
    private volatile Clock                                    clock;

    /**
     * Creates a boundary stress analyzer with specified window size.
     *
     * @param windowMillis sliding window duration in milliseconds
     * @throws IllegalArgumentException if windowMillis not positive
     */
    public BoundaryStressAnalyzer(long windowMillis) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("Window size must be positive: " + windowMillis);
        }

        this.windowMillis = windowMillis;
        this.migrationEvents = new ConcurrentHashMap<>();
        this.lastCleanupTime = new ConcurrentHashMap<>();
        this.clock = Clock.system();
    }

    /**
     * Sets the clock for deterministic simulation time.
     * <p>
     * IMPORTANT: Use this for PrimeMover integration. The clock should be
     * injected to ensure simulated time is used instead of wall-clock time.
     *
     * @param clock the clock implementation
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records a migration event for a bubble.
     * <p>
     * Call this method when an entity crosses the boundary from this bubble
     * to a neighbor. Timestamps are used to calculate recent migration rate.
     *
     * @param bubbleId  the bubble identifier
     * @param timestamp migration timestamp (milliseconds)
     */
    public void recordMigration(UUID bubbleId, long timestamp) {
        migrationEvents.computeIfAbsent(bubbleId, k -> new ArrayList<>()).add(timestamp);

        // Periodically clean old entries (every 5 seconds)
        var lastClean = lastCleanupTime.getOrDefault(bubbleId, 0L);
        if (timestamp - lastClean > 5000) {
            cleanOldEntries(bubbleId, timestamp);
            lastCleanupTime.put(bubbleId, timestamp);
        }
    }

    /**
     * Gets the migration rate for a bubble (migrations per second).
     * <p>
     * Calculates rate from all migrations within the sliding window.
     * Returns 0.0 if no migrations recorded.
     *
     * @param bubbleId the bubble identifier
     * @return migrations per second (0.0 if no data)
     */
    public float getMigrationRate(UUID bubbleId) {
        var events = migrationEvents.get(bubbleId);
        if (events == null || events.isEmpty()) {
            return 0.0f;
        }

        synchronized (events) {
            if (events.isEmpty()) {
                return 0.0f;
            }

            // Calculate time span of events in window
            long now = clock.currentTimeMillis();
            long windowStart = now - windowMillis;

            // Filter events within window
            int countInWindow = 0;
            long earliestInWindow = Long.MAX_VALUE;
            long latestInWindow = Long.MIN_VALUE;

            for (long timestamp : events) {
                if (timestamp >= windowStart) {
                    countInWindow++;
                    earliestInWindow = Math.min(earliestInWindow, timestamp);
                    latestInWindow = Math.max(latestInWindow, timestamp);
                }
            }

            if (countInWindow == 0) {
                return 0.0f;
            }

            // Calculate rate over actual time span
            long timeSpanMillis = Math.max(latestInWindow - earliestInWindow, 1000); // Minimum 1 second
            return (countInWindow * 1000.0f) / timeSpanMillis;
        }
    }

    /**
     * Checks if a bubble has high boundary stress.
     * <p>
     * Compares migration rate against threshold. High stress indicates
     * entities are frequently crossing boundaries, suggesting:
     * <ul>
     *   <li>Bubble boundaries don't align with entity clustering</li>
     *   <li>Bubble may need to move to follow entity movement</li>
     *   <li>Adjacent bubbles may need merging</li>
     * </ul>
     *
     * @param bubbleId  the bubble identifier
     * @param threshold migrations/second threshold (e.g., 10.0)
     * @return true if migration rate exceeds threshold
     */
    public boolean hasHighBoundaryStress(UUID bubbleId, float threshold) {
        return getMigrationRate(bubbleId) > threshold;
    }

    /**
     * Cleans old migration entries for a specific bubble.
     * <p>
     * Removes entries older than the sliding window to prevent unbounded growth.
     *
     * @param bubbleId    the bubble identifier
     * @param currentTime current timestamp for window calculation
     */
    private void cleanOldEntries(UUID bubbleId, long currentTime) {
        var events = migrationEvents.get(bubbleId);
        if (events == null) {
            return;
        }

        synchronized (events) {
            long windowStart = currentTime - windowMillis;
            events.removeIf(timestamp -> timestamp < windowStart);
        }
    }

    /**
     * Cleans old migration entries for all bubbles.
     * <p>
     * Public method for test control and periodic maintenance.
     *
     * @param currentTime current timestamp for window calculation
     */
    public void cleanOldEntries(long currentTime) {
        for (var bubbleId : migrationEvents.keySet()) {
            cleanOldEntries(bubbleId, currentTime);
        }
    }

    /**
     * Gets the number of bubbles being tracked.
     *
     * @return bubble count
     */
    public int getTrackedBubbleCount() {
        return migrationEvents.size();
    }

    /**
     * Gets the total number of migration events in memory.
     * <p>
     * Includes all events across all bubbles within the sliding window.
     *
     * @return total event count
     */
    public int getTotalEventCount() {
        int total = 0;
        for (var events : migrationEvents.values()) {
            synchronized (events) {
                total += events.size();
            }
        }
        return total;
    }

    /**
     * Clears all tracked migration data.
     * <p>
     * Used for testing and cleanup.
     */
    public void reset() {
        migrationEvents.clear();
        lastCleanupTime.clear();
    }
}
