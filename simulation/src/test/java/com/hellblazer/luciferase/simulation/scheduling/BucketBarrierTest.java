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
package com.hellblazer.luciferase.simulation.scheduling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BucketBarrier concurrent coordination.
 * <p>
 * Tests verify:
 * - Basic neighbor synchronization
 * - Concurrent recordNeighborReady calls don't deadlock
 * - Timeout behavior unchanged
 * - Latch reset on bucket change
 * - Stress tests with multiple threads
 *
 * @author hal.hildebrand
 */
class BucketBarrierTest {

    private UUID neighbor1;
    private UUID neighbor2;
    private UUID neighbor3;
    private Set<UUID> neighbors;

    @BeforeEach
    void setUp() {
        neighbor1 = UUID.randomUUID();
        neighbor2 = UUID.randomUUID();
        neighbor3 = UUID.randomUUID();
        neighbors = Set.of(neighbor1, neighbor2, neighbor3);
    }

    @Test
    void testAllNeighborsReady() {
        var barrier = new BucketBarrier(neighbors);

        // All neighbors report ready for bucket 1
        barrier.recordNeighborReady(neighbor1, 1L);
        barrier.recordNeighborReady(neighbor2, 1L);
        barrier.recordNeighborReady(neighbor3, 1L);

        var outcome = barrier.waitForNeighbors(1L, 100);

        assertTrue(outcome.allReady(), "All neighbors should be ready");
        assertTrue(outcome.missingNeighbors().isEmpty());
    }

    @Test
    void testTimeout() {
        var barrier = new BucketBarrier(neighbors);

        // Only 2 of 3 neighbors report
        barrier.recordNeighborReady(neighbor1, 1L);
        barrier.recordNeighborReady(neighbor2, 1L);
        // neighbor3 doesn't report

        var outcome = barrier.waitForNeighbors(1L, 50);

        assertFalse(outcome.allReady(), "Not all neighbors ready");
        assertEquals(1, outcome.missingNeighbors().size());
        assertTrue(outcome.missingNeighbors().contains(neighbor3));
    }

    @Test
    void testBucketReset() {
        var barrier = new BucketBarrier(neighbors);

        // All ready for bucket 1
        barrier.recordNeighborReady(neighbor1, 1L);
        barrier.recordNeighborReady(neighbor2, 1L);
        barrier.recordNeighborReady(neighbor3, 1L);

        var outcome1 = barrier.waitForNeighbors(1L, 100);
        assertTrue(outcome1.allReady());

        // For bucket 2, only neighbor1 reports
        barrier.recordNeighborReady(neighbor1, 2L);

        var outcome2 = barrier.waitForNeighbors(2L, 50);
        assertFalse(outcome2.allReady(), "Not all ready for bucket 2");
        assertEquals(2, outcome2.missingNeighbors().size());
    }

    @Test
    void testNeighborAheadOfBucket() {
        var barrier = new BucketBarrier(neighbors);

        // Neighbors report future buckets
        barrier.recordNeighborReady(neighbor1, 5L);
        barrier.recordNeighborReady(neighbor2, 5L);
        barrier.recordNeighborReady(neighbor3, 5L);

        // Waiting for bucket 3 should succeed (neighbors at 5 >= 3)
        var outcome = barrier.waitForNeighbors(3L, 100);
        assertTrue(outcome.allReady(), "Neighbors ahead of target bucket should count as ready");
    }

    @Test
    void testUnexpectedNeighborIgnored() {
        var barrier = new BucketBarrier(neighbors);
        var unexpectedNeighbor = UUID.randomUUID();

        // Record unexpected neighbor
        barrier.recordNeighborReady(unexpectedNeighbor, 1L);

        // Still need all 3 expected neighbors
        barrier.recordNeighborReady(neighbor1, 1L);
        barrier.recordNeighborReady(neighbor2, 1L);

        var outcome = barrier.waitForNeighbors(1L, 50);
        assertFalse(outcome.allReady(), "Should still wait for expected neighbors");
        assertTrue(outcome.missingNeighbors().contains(neighbor3));
    }

    @Test
    void testConcurrentRecordNeighborReadyNoDeadlock() throws Exception {
        var barrier = new BucketBarrier(neighbors);
        int iterations = 1000;
        var executor = Executors.newFixedThreadPool(3);
        var latch = new CountDownLatch(3);
        var deadlock = new AtomicBoolean(false);

        try {
            // Simulate concurrent neighbor updates
            for (var neighbor : neighbors) {
                executor.submit(() -> {
                    try {
                        for (long bucket = 0; bucket < iterations; bucket++) {
                            barrier.recordNeighborReady(neighbor, bucket);
                            // Small delay to interleave
                            if (bucket % 100 == 0) {
                                Thread.yield();
                            }
                        }
                    } catch (Exception e) {
                        deadlock.set(true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait with timeout to detect deadlock
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertTrue(completed, "Concurrent updates should complete without deadlock");
            assertFalse(deadlock.get(), "Should not throw exceptions");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void testConcurrentWaitAndRecordNoDeadlock() throws Exception {
        var barrier = new BucketBarrier(neighbors);
        int buckets = 100;
        var executor = Executors.newFixedThreadPool(4);
        var completedBuckets = new AtomicInteger(0);
        var deadlock = new AtomicBoolean(false);

        try {
            // Waiter thread
            var waiterFuture = executor.submit(() -> {
                try {
                    for (long bucket = 0; bucket < buckets; bucket++) {
                        var outcome = barrier.waitForNeighbors(bucket, 50);
                        if (outcome.allReady()) {
                            completedBuckets.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    deadlock.set(true);
                }
            });

            // Recorder threads (3 neighbors)
            var recorderFutures = new ArrayList<Future<?>>();
            for (var neighbor : neighbors) {
                recorderFutures.add(executor.submit(() -> {
                    try {
                        for (long bucket = 0; bucket < buckets; bucket++) {
                            barrier.recordNeighborReady(neighbor, bucket);
                            // Small random delay
                            if (Math.random() < 0.1) {
                                Thread.sleep(1);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        deadlock.set(true);
                    }
                }));
            }

            // Wait for all to complete
            waiterFuture.get(10, TimeUnit.SECONDS);
            for (var future : recorderFutures) {
                future.get(10, TimeUnit.SECONDS);
            }

            assertFalse(deadlock.get(), "Should not deadlock");
            assertTrue(completedBuckets.get() > 0, "Some buckets should complete");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void testStressMultipleThreads() throws Exception {
        int threadCount = 10;
        int bubblesPerThread = 100;
        var neighbors10 = new HashSet<UUID>();
        for (int i = 0; i < threadCount; i++) {
            neighbors10.add(UUID.randomUUID());
        }
        var neighborList = new ArrayList<>(neighbors10);

        var barrier = new BucketBarrier(neighbors10);
        var executor = Executors.newFixedThreadPool(threadCount);
        var startLatch = new CountDownLatch(1);
        var endLatch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        try {
            // Each thread represents a neighbor
            for (int t = 0; t < threadCount; t++) {
                final var neighbor = neighborList.get(t);
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        for (long bucket = 0; bucket < bubblesPerThread; bucket++) {
                            barrier.recordNeighborReady(neighbor, bucket);
                            // Occasionally wait as well
                            if (bucket % 10 == 0) {
                                barrier.waitForNeighbors(bucket, 10);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            // Start all threads simultaneously
            startLatch.countDown();

            // Wait for completion
            boolean completed = endLatch.await(30, TimeUnit.SECONDS);
            assertTrue(completed, "All threads should complete");
            assertEquals(0, errors.get(), "No errors should occur");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void testGetCurrentBucket() {
        var barrier = new BucketBarrier(neighbors);

        assertEquals(-1, barrier.getCurrentBucket(), "Initial bucket should be -1");

        barrier.waitForNeighbors(5L, 1);
        assertEquals(5, barrier.getCurrentBucket());

        barrier.waitForNeighbors(10L, 1);
        assertEquals(10, barrier.getCurrentBucket());
    }

    @Test
    void testIsNeighborReady() {
        var barrier = new BucketBarrier(neighbors);

        barrier.waitForNeighbors(5L, 1);  // Set current bucket

        assertFalse(barrier.isNeighborReady(neighbor1), "Not ready before report");

        barrier.recordNeighborReady(neighbor1, 5L);
        assertTrue(barrier.isNeighborReady(neighbor1), "Ready after report");

        assertFalse(barrier.isNeighborReady(neighbor2), "Other neighbor not ready");
    }

    @Test
    void testGetNeighborBucket() {
        var barrier = new BucketBarrier(neighbors);

        assertEquals(-1, barrier.getNeighborBucket(neighbor1), "Unknown neighbor returns -1");

        barrier.recordNeighborReady(neighbor1, 42L);
        assertEquals(42, barrier.getNeighborBucket(neighbor1));
    }

    @Test
    void testEmptyNeighborSet() {
        var barrier = new BucketBarrier(Set.of());

        var outcome = barrier.waitForNeighbors(1L, 100);
        assertTrue(outcome.allReady(), "Empty neighbor set should always be ready");
    }
}
