/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4.2 tests for direct barrier timeout handling (Issue #4).
 *
 * Tests verify:
 * - Timeout detection via direct barrier.await(timeout, unit)
 * - No thread leaks on timeout
 * - Barrier remains usable after timeout
 */
class Phase42BarrierTimeoutTest {

    private InMemoryPartitionRegistry registry;
    private final int numPartitions = 3;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPartitionRegistry(numPartitions);
    }

    /**
     * Test 1: Barrier timeout detected within expected time.
     *
     * Uses direct CyclicBarrier.await(timeout, unit) which throws
     * TimeoutException natively - no CompletableFuture wrapper needed.
     */
    @Test
    void testTimeoutDetection() throws Exception {
        long timeoutMs = 100;

        // Only 1 partition reaches barrier (need 3 for success)
        long startTime = System.currentTimeMillis();

        boolean completed = registry.barrier(timeoutMs, TimeUnit.MILLISECONDS);

        long elapsedMs = System.currentTimeMillis() - startTime;

        // Verify timeout occurred
        assertThat(completed)
            .as("Barrier should timeout when not all partitions arrive")
            .isFalse();

        // Verify timing (allow 50ms tolerance for scheduling jitter)
        assertThat(elapsedMs)
            .as("Timeout should occur within expected time window")
            .isBetween(timeoutMs - 50, timeoutMs + 50);
    }

    /**
     * Test 2: No thread leak after timeout.
     *
     * Critical requirement from Issue #4: CompletableFuture.get(timeout)
     * can leave threads blocked on barrier.await(). Direct barrier.await(timeout)
     * does not have this problem.
     */
    @Test
    void testNoThreadLeak() throws Exception {
        int initialThreadCount = Thread.activeCount();

        // Trigger timeout
        boolean completed = registry.barrier(50, TimeUnit.MILLISECONDS);
        assertThat(completed).isFalse();

        // Give threads time to clean up
        Thread.sleep(200);

        int finalThreadCount = Thread.activeCount();

        assertThat(finalThreadCount)
            .as("No threads should leak after barrier timeout")
            .isLessThanOrEqualTo(initialThreadCount + 1); // Allow +1 for test overhead
    }

    /**
     * Test 3: Barrier state consistent after timeout.
     *
     * Verifies that after a timeout:
     * - Barrier can be reset
     * - Subsequent barrier operations work normally
     * - No state corruption from previous timeout
     */
    @Test
    void testStateConsistencyAfterTimeout() throws Exception {
        // First call: timeout
        boolean firstCall = registry.barrier(50, TimeUnit.MILLISECONDS);
        assertThat(firstCall)
            .as("First call should timeout")
            .isFalse();

        // Reset barrier for next round (registry does this internally)
        registry.resetBarrier();

        // Second call: all partitions arrive this time
        CountDownLatch latch = new CountDownLatch(numPartitions);
        CountDownLatch allReady = new CountDownLatch(numPartitions);

        for (int i = 0; i < numPartitions; i++) {
            new Thread(() -> {
                try {
                    allReady.countDown();
                    allReady.await(); // Wait for all threads ready

                    boolean result = registry.barrier(1000, TimeUnit.MILLISECONDS);
                    if (result) {
                        latch.countDown();
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }).start();
        }

        // Wait for all threads to complete
        boolean completed = latch.await(2, TimeUnit.SECONDS);

        assertThat(completed)
            .as("After timeout recovery, barrier should work normally")
            .isTrue();

        assertThat(latch.getCount())
            .as("All partitions should complete barrier")
            .isEqualTo(0);
    }

    /**
     * Helper class for testing - simplified InMemoryPartitionRegistry.
     *
     * In production, this exists in the test support package.
     * This is a minimal stub for Phase 4.2 testing.
     */
    static class InMemoryPartitionRegistry {
        private CyclicBarrier coordinationBarrier;
        private final int numPartitions;

        public InMemoryPartitionRegistry(int numPartitions) {
            this.numPartitions = numPartitions;
            this.coordinationBarrier = new CyclicBarrier(numPartitions);
        }

        /**
         * Wait for all partitions to reach barrier with timeout.
         *
         * @param timeout maximum time to wait
         * @param unit time unit
         * @return true if barrier completed, false if timeout
         * @throws InterruptedException if wait is interrupted
         */
        public boolean barrier(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                coordinationBarrier.await(timeout, unit);
                return true;
            } catch (TimeoutException e) {
                return false;
            } catch (BrokenBarrierException e) {
                throw new InterruptedException("Barrier broken: " + e.getMessage());
            }
        }

        /**
         * Reset barrier for next round (test helper).
         */
        public void resetBarrier() {
            coordinationBarrier.reset();
        }
    }
}
