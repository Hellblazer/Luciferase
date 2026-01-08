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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hellblazer.luciferase.simulation.distributed.ProcessCoordinator.BUCKET_DURATION_MS;
import static com.hellblazer.luciferase.simulation.distributed.ProcessCoordinator.TOLERANCE_MS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test WallClockBucketScheduler bucket calculations and clock skew detection.
 * <p>
 * Test Coverage:
 * - Bucket calculation accuracy
 * - Clock skew detection (within/exceeding tolerance)
 * - Bucket transitions on time boundaries
 * - Concurrent bucket reads/writes
 * - Edge cases: zero, negative timestamps, overflow
 *
 * @author hal.hildebrand
 */
class WallClockBucketSchedulerTest {

    private WallClockBucketScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WallClockBucketScheduler();
    }

    @Test
    void testBucketCalculationAccuracy() {
        // Test bucket calculation at various timestamps
        var timestamp1 = 0L;
        assertEquals(0L, scheduler.bucketForTimestamp(timestamp1), "Bucket 0 should be at timestamp 0");

        var timestamp2 = BUCKET_DURATION_MS;
        assertEquals(1L, scheduler.bucketForTimestamp(timestamp2), "Bucket 1 should be at timestamp 100ms");

        var timestamp3 = BUCKET_DURATION_MS * 10;
        assertEquals(10L, scheduler.bucketForTimestamp(timestamp3), "Bucket 10 should be at timestamp 1000ms");

        var timestamp4 = BUCKET_DURATION_MS * 100 + 50;
        assertEquals(100L, scheduler.bucketForTimestamp(timestamp4), "Bucket 100 should be at timestamp 10050ms");
    }

    @Test
    void testBucketToWallClock() {
        // Test conversion from bucket index to wall clock time
        assertEquals(0L, scheduler.bucketToWallClock(0), "Bucket 0 = 0ms");
        assertEquals(BUCKET_DURATION_MS, scheduler.bucketToWallClock(1), "Bucket 1 = 100ms");
        assertEquals(BUCKET_DURATION_MS * 10, scheduler.bucketToWallClock(10), "Bucket 10 = 1000ms");
        assertEquals(BUCKET_DURATION_MS * 100, scheduler.bucketToWallClock(100), "Bucket 100 = 10000ms");
    }

    @Test
    void testClockSkewWithinTolerance() {
        var now = System.currentTimeMillis();
        var withinTolerance = now + (TOLERANCE_MS / 2);

        var skew = scheduler.getClockSkew(withinTolerance);
        assertTrue(Math.abs(skew) <= TOLERANCE_MS, "Skew should be within tolerance");
    }

    @Test
    void testClockSkewExceedsTolerance() {
        var now = System.currentTimeMillis();
        var exceedsTolerance = now + TOLERANCE_MS + 100;

        var skew = scheduler.getClockSkew(exceedsTolerance);
        assertTrue(Math.abs(skew) > TOLERANCE_MS, "Skew should exceed tolerance");
    }

    @Test
    void testClockSkewNegative() {
        var now = System.currentTimeMillis();
        var pastTime = now - TOLERANCE_MS - 100;

        var skew = scheduler.getClockSkew(pastTime);
        assertTrue(skew < -TOLERANCE_MS, "Negative skew should be detected");
    }

    @Test
    void testBucketTransitionRecording() {
        var fromBucket = 0L;
        var toBucket = 1L;

        scheduler.recordBucketTransition(fromBucket, toBucket);

        // Verify transition was recorded (implementation will provide query method)
        assertTrue(scheduler.hasRecordedTransition(fromBucket, toBucket),
                "Transition from bucket 0 to 1 should be recorded");
    }

    @Test
    void testBucketTransitionChain() {
        // Record a chain of transitions
        scheduler.recordBucketTransition(0, 1);
        scheduler.recordBucketTransition(1, 2);
        scheduler.recordBucketTransition(2, 3);

        assertTrue(scheduler.hasRecordedTransition(0, 1), "Transition 0→1 recorded");
        assertTrue(scheduler.hasRecordedTransition(1, 2), "Transition 1→2 recorded");
        assertTrue(scheduler.hasRecordedTransition(2, 3), "Transition 2→3 recorded");
    }

    @Test
    void testConcurrentBucketReads() throws InterruptedException {
        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    var bucket = scheduler.getCurrentBucket();
                    assertTrue(bucket >= 0, "Bucket should be non-negative");

                    var timestamp = System.currentTimeMillis();
                    var calculatedBucket = scheduler.bucketForTimestamp(timestamp);
                    assertTrue(calculatedBucket >= 0, "Calculated bucket should be non-negative");
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertEquals(0, errorCount.get(), "No errors should occur during concurrent reads");
    }

    @Test
    void testConcurrentBucketWrites() throws InterruptedException {
        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final var bucketIndex = i;
            new Thread(() -> {
                try {
                    scheduler.recordBucketTransition(bucketIndex, bucketIndex + 1);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertEquals(0, errorCount.get(), "No errors should occur during concurrent writes");

        // Verify all transitions were recorded
        for (int i = 0; i < threadCount; i++) {
            assertTrue(scheduler.hasRecordedTransition(i, i + 1),
                    "Transition " + i + "→" + (i + 1) + " should be recorded");
        }
    }

    @Test
    void testZeroTimestamp() {
        var bucket = scheduler.bucketForTimestamp(0L);
        assertEquals(0L, bucket, "Zero timestamp should map to bucket 0");

        var wallClock = scheduler.bucketToWallClock(0);
        assertEquals(0L, wallClock, "Bucket 0 should map to 0ms wall clock");
    }

    @Test
    void testNegativeTimestamp() {
        // Negative timestamps represent pre-epoch times
        var negativeTimestamp = -BUCKET_DURATION_MS;
        var bucket = scheduler.bucketForTimestamp(negativeTimestamp);
        assertEquals(-1L, bucket, "Negative timestamp should map to negative bucket");
    }

    @Test
    void testLargeTimestamp() {
        // Test with large timestamp (year 2100)
        var largeTimestamp = 4102444800000L; // Jan 1, 2100
        var bucket = scheduler.bucketForTimestamp(largeTimestamp);
        assertTrue(bucket > 0, "Large timestamp should map to positive bucket");

        var wallClock = scheduler.bucketToWallClock(bucket);
        assertEquals(largeTimestamp - (largeTimestamp % BUCKET_DURATION_MS), wallClock,
                "Wall clock should round down to bucket start");
    }

    @Test
    void testBucketBoundary() {
        // Test exact bucket boundary
        var timestamp = BUCKET_DURATION_MS * 5; // Exactly at bucket 5 start
        var bucket = scheduler.bucketForTimestamp(timestamp);
        assertEquals(5L, bucket, "Exact boundary should map to bucket 5");

        var timestampMinus1 = timestamp - 1;
        var bucketMinus1 = scheduler.bucketForTimestamp(timestampMinus1);
        assertEquals(4L, bucketMinus1, "One millisecond before boundary should be bucket 4");
    }

    @Test
    void testGetCurrentBucket() {
        var now = System.currentTimeMillis();
        var currentBucket = scheduler.getCurrentBucket();
        var expectedBucket = now / BUCKET_DURATION_MS;

        // Allow for small timing differences
        assertTrue(Math.abs(currentBucket - expectedBucket) <= 1,
                "Current bucket should be within 1 of expected bucket");
    }

    @Test
    void testBucketMonotonicity() {
        // Verify that increasing timestamps produce non-decreasing buckets
        var timestamp1 = 1000L;
        var bucket1 = scheduler.bucketForTimestamp(timestamp1);

        var timestamp2 = timestamp1 + 50;
        var bucket2 = scheduler.bucketForTimestamp(timestamp2);

        var timestamp3 = timestamp2 + 100;
        var bucket3 = scheduler.bucketForTimestamp(timestamp3);

        assertTrue(bucket2 >= bucket1, "Later timestamp should have >= bucket");
        assertTrue(bucket3 >= bucket2, "Even later timestamp should have >= bucket");
    }
}
