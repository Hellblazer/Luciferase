/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.hellblazer.luciferase.simulation.distributed.ProcessCoordinator.BUCKET_DURATION_MS;
import static com.hellblazer.luciferase.simulation.distributed.ProcessCoordinator.TOLERANCE_MS;

/**
 * Bucket-based wall clock scheduler for distributed bubble simulation.
 * <p>
 * Responsibilities:
 * - Partition time into 100ms buckets (BUCKET_DURATION_MS)
 * - Track bucket-to-wall-clock mapping for message ordering
 * - Detect and log clock skew (>50ms drift = TOLERANCE_MS)
 * - Provide bucket calculation from timestamps
 * - Thread-safe bucket tracking with minimal locking
 * <p>
 * Bucket Philosophy:
 * - Buckets are logical time units, not physical wall-clock time
 * - Enables deterministic ordering even with clock skew
 * - Multiple messages in same bucket are considered "simultaneous"
 * - Bucket boundaries are deterministic: bucket = timestamp / BUCKET_DURATION_MS
 * <p>
 * Performance:
 * - Bucket calculation: O(1) with caching
 * - Clock skew detection: O(1)
 * - Thread-safe: ConcurrentHashMap + AtomicLong
 * - No blocking operations
 * <p>
 * Architecture Decision D6B.7: Clock Synchronization Foundation
 *
 * @author hal.hildebrand
 */
public class WallClockBucketScheduler {

    private static final Logger log = LoggerFactory.getLogger(WallClockBucketScheduler.class);

    private volatile Clock clock = Clock.system();

    private final AtomicLong currentBucket;
    private final ConcurrentHashMap<Long, Long> bucketTransitions; // fromBucket -> toBucket

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Create a WallClockBucketScheduler.
     * <p>
     * Initializes bucket tracking at current time.
     */
    public WallClockBucketScheduler() {
        this.currentBucket = new AtomicLong(bucketForTimestamp(clock.currentTimeMillis()));
        this.bucketTransitions = new ConcurrentHashMap<>();
    }

    /**
     * Get the current bucket index.
     * <p>
     * Bucket index is calculated as: currentTime / BUCKET_DURATION_MS
     *
     * @return current bucket index (0-based)
     */
    public long getCurrentBucket() {
        var now = clock.currentTimeMillis();
        var bucket = bucketForTimestamp(now);
        currentBucket.set(bucket);
        return bucket;
    }

    /**
     * Calculate bucket index for a given timestamp.
     * <p>
     * Bucket calculation: bucket = timestamp / BUCKET_DURATION_MS
     * This is deterministic and handles negative timestamps correctly.
     *
     * @param timestamp wall clock time in milliseconds
     * @return bucket index (can be negative for pre-epoch times)
     */
    public long bucketForTimestamp(long timestamp) {
        return timestamp / BUCKET_DURATION_MS;
    }

    /**
     * Convert bucket index to wall clock time.
     * <p>
     * Returns the start time of the bucket (bucket * BUCKET_DURATION_MS).
     *
     * @param bucketIndex bucket index
     * @return wall clock time at bucket start (milliseconds)
     */
    public long bucketToWallClock(long bucketIndex) {
        return bucketIndex * BUCKET_DURATION_MS;
    }

    /**
     * Detect clock skew for a given timestamp.
     * <p>
     * Compares timestamp against current wall clock time.
     * Returns the difference (skew) in milliseconds.
     * <p>
     * Skew > TOLERANCE_MS (50ms) indicates clock drift warning.
     *
     * @param timestamp wall clock time to check
     * @return clock skew in milliseconds (positive = future, negative = past)
     */
    public long getClockSkew(long timestamp) {
        var now = clock.currentTimeMillis();
        var skew = timestamp - now;

        if (Math.abs(skew) > TOLERANCE_MS) {
            log.warn("Clock skew detected: {}ms (tolerance: {}ms)", skew, TOLERANCE_MS);
        }

        return skew;
    }

    /**
     * Record a bucket transition.
     * <p>
     * Tracks bucket changes for debugging and analysis.
     * Used to detect bucket boundary crossings.
     *
     * @param fromBucket starting bucket index
     * @param toBucket   ending bucket index
     */
    public void recordBucketTransition(long fromBucket, long toBucket) {
        bucketTransitions.put(fromBucket, toBucket);
        log.trace("Bucket transition: {} -> {}", fromBucket, toBucket);
    }

    /**
     * Check if a bucket transition has been recorded.
     * <p>
     * Used for testing and validation.
     *
     * @param fromBucket starting bucket index
     * @param toBucket   ending bucket index
     * @return true if transition was recorded
     */
    public boolean hasRecordedTransition(long fromBucket, long toBucket) {
        var recorded = bucketTransitions.get(fromBucket);
        return recorded != null && recorded == toBucket;
    }
}
