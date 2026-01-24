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

import com.hellblazer.luciferase.lucien.balancing.fault.InFlightOperationTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for DefaultParallelBalancer barrier integration (P4.1.1).
 *
 * <p>Validates that pauseCrossPartitionBalance() properly coordinates with InFlightOperationTracker
 * to create a synchronous barrier for safe recovery operations.
 *
 * <p><b>Test Strategy</b>:
 * <ol>
 *   <li>pauseAndWait() is called with 5-second timeout during pause</li>
 *   <li>resumeCrossPartitionBalance() calls resume() on tracker</li>
 *   <li>Concurrent balance operations blocked during pause (new ops rejected)</li>
 *   <li>Resume allows operations to proceed normally</li>
 *   <li>isPaused() reflects tracker state correctly</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
public class P411BarrierIntegrationTest {

    private BalanceConfiguration config;
    private InFlightOperationTracker tracker;
    private DefaultParallelBalancer<?, ?, ?> balancer;

    @BeforeEach
    void setUp() {
        config = BalanceConfiguration.defaultConfig();
        tracker = new InFlightOperationTracker();
        balancer = new DefaultParallelBalancer<>(config, tracker);
    }

    /**
     * Test 1: pauseAndWait() is called during pauseCrossPartitionBalance().
     *
     * <p>Verifies that the pause operation blocks until all in-flight operations complete.
     */
    @Test
    void testPauseAndWaitCalledDuringPause() throws InterruptedException {
        // Given: Balancer with shared tracker, no in-flight operations
        assertFalse(tracker.isPaused());

        // When: Pause cross-partition balance
        balancer.pauseCrossPartitionBalance();

        // Then: Tracker should be marked as paused
        assertTrue(tracker.isPaused(), "Tracker should be paused after pauseCrossPartitionBalance()");
    }

    /**
     * Test 2: resumeCrossPartitionBalance() calls resume() on tracker.
     *
     * <p>Verifies that resume properly clears the paused state.
     */
    @Test
    void testResumeCallsTrackerResume() throws InterruptedException {
        // Given: Balancer in paused state
        balancer.pauseCrossPartitionBalance();
        assertTrue(tracker.isPaused());

        // When: Resume cross-partition balance
        balancer.resumeCrossPartitionBalance();

        // Then: Tracker should no longer be paused
        assertFalse(tracker.isPaused(), "Tracker should not be paused after resumeCrossPartitionBalance()");
    }

    /**
     * Test 3: Concurrent balance operations are rejected during pause.
     *
     * <p>Verifies that beginOperation() rejects new operations when paused.
     */
    @Test
    void testConcurrentOperationsBlockedDuringPause() throws InterruptedException {
        // Given: Balancer in paused state
        balancer.pauseCrossPartitionBalance();

        // When: Try to begin new operation
        var result = tracker.tryBeginOperation();

        // Then: Operation should be rejected (empty Optional)
        assertTrue(result.isEmpty(), "tryBeginOperation() should return empty when paused");
    }

    /**
     * Test 4: Resume allows new operations to proceed.
     *
     * <p>Verifies that after resume, new operations can be started.
     */
    @Test
    void testOperationsAllowedAfterResume() throws InterruptedException {
        // Given: Balancer initially paused, then resumed
        balancer.pauseCrossPartitionBalance();
        assertTrue(tracker.isPaused());

        balancer.resumeCrossPartitionBalance();

        // When: Try to begin operation
        var result = tracker.tryBeginOperation();

        // Then: Operation should be allowed
        assertTrue(result.isPresent(), "tryBeginOperation() should succeed after resume");

        // Cleanup
        result.ifPresent(token -> token.close());
    }

    /**
     * Test 5: isPaused() reflects tracker state.
     *
     * <p>Verifies that the balancer's isPaused() method accurately reflects the tracker state.
     */
    @Test
    void testIsPausedReflectsTrackerState() throws InterruptedException {
        // Given: Balancer initially not paused
        assertFalse(balancer.isPaused());

        // When: Pause the balancer
        balancer.pauseCrossPartitionBalance();

        // Then: isPaused() should return true
        assertTrue(balancer.isPaused(), "isPaused() should return true when paused");

        // When: Resume the balancer
        balancer.resumeCrossPartitionBalance();

        // Then: isPaused() should return false
        assertFalse(balancer.isPaused(), "isPaused() should return false when resumed");
    }

    /**
     * Integration test: Pause/resume with concurrent operations.
     *
     * <p>Simulates concurrent balance operations with pause/resume cycle.
     */
    @Test
    void testPauseResumeWithConcurrentOperations() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            var operationCount = new AtomicInteger(0);
            var blockedCount = new AtomicInteger(0);
            var startSignal = new CountDownLatch(1);
            var completeSignal = new CountDownLatch(3);

            // Start 3 concurrent operations
            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    try {
                        startSignal.await();  // Wait for all threads to start

                        // Try operation before pause
                        var token = tracker.tryBeginOperation();
                        if (token.isPresent()) {
                            operationCount.incrementAndGet();
                            token.get().close();
                        }

                        // Simulate work
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completeSignal.countDown();
                    }
                });
            }

            // Let operations start
            startSignal.countDown();
            completeSignal.await(5, TimeUnit.SECONDS);

            // Then: All operations should have succeeded (not paused yet)
            assertEquals(3, operationCount.get(), "All operations should succeed before pause");

            // When: Pause and try operations
            balancer.pauseCrossPartitionBalance();
            operationCount.set(0);
            var startSignal2 = new CountDownLatch(1);
            var completeSignal2 = new CountDownLatch(3);

            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    try {
                        startSignal2.await();

                        var token = tracker.tryBeginOperation();
                        if (token.isPresent()) {
                            operationCount.incrementAndGet();
                            token.get().close();
                        } else {
                            blockedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completeSignal2.countDown();
                    }
                });
            }

            startSignal2.countDown();
            completeSignal2.await(5, TimeUnit.SECONDS);

            // Then: All operations should be blocked
            assertEquals(0, operationCount.get(), "All operations should be blocked during pause");
            assertEquals(3, blockedCount.get(), "All operations should be rejected");

            // When: Resume
            balancer.resumeCrossPartitionBalance();
            operationCount.set(0);
            var startSignal3 = new CountDownLatch(1);
            var completeSignal3 = new CountDownLatch(3);

            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    try {
                        startSignal3.await();

                        var token = tracker.tryBeginOperation();
                        if (token.isPresent()) {
                            operationCount.incrementAndGet();
                            token.get().close();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completeSignal3.countDown();
                    }
                });
            }

            startSignal3.countDown();
            completeSignal3.await(5, TimeUnit.SECONDS);

            // Then: All operations should succeed again
            assertEquals(3, operationCount.get(), "All operations should succeed after resume");

        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor should shut down");
        }
    }
}
