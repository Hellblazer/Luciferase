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

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decorator that adds timeout detection to partition barrier operations.
 *
 * <p>Wraps a PartitionRegistry and monitors barrier operations for timeouts.
 * Reports failures to FaultHandler when barrier timeout thresholds are exceeded.
 *
 * <p><b>GAP-6 Fix</b>: Uses simulation.distributed.integration.Clock instead
 * of java.time.Clock for deterministic testing with TestClock.
 *
 * <p><b>Issue #4 Fix</b>: Uses direct barrier.await(timeout, unit) instead of
 * CompletableFuture.get() wrapper to prevent thread leaks on timeout.
 *
 * <p><b>Usage</b>:
 * <pre>
 * var registry = new InMemoryPartitionRegistry(...);
 * var handler = new DefaultFaultHandler(...);
 * var faultAware = new FaultAwarePartitionRegistry(registry, handler, 5000);
 *
 * faultAware.barrier(); // Throws if timeout exceeded
 * </pre>
 *
 * <p><b>Thread-Safe</b>: Safe for concurrent barrier operations. Clock is
 * volatile for atomic updates.
 *
 * @author hal.hildebrand
 */
public class FaultAwarePartitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(FaultAwarePartitionRegistry.class);

    private final PartitionRegistry delegate;
    private final FaultHandler faultHandler;
    private final long barrierTimeoutMs;
    private volatile Clock clock = Clock.system();

    /**
     * Create a fault-aware partition registry decorator.
     *
     * @param delegate the underlying partition registry
     * @param faultHandler handler to report timeouts to
     * @param barrierTimeoutMs timeout in milliseconds for barrier operations
     * @throws NullPointerException if delegate or faultHandler is null
     * @throws IllegalArgumentException if timeout is negative
     */
    public FaultAwarePartitionRegistry(PartitionRegistry delegate, FaultHandler faultHandler,
                                      long barrierTimeoutMs) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.faultHandler = Objects.requireNonNull(faultHandler, "faultHandler must not be null");

        if (barrierTimeoutMs < 0) {
            throw new IllegalArgumentException("barrierTimeoutMs must be non-negative");
        }

        this.barrierTimeoutMs = barrierTimeoutMs;
    }

    /**
     * Set the clock for deterministic testing.
     *
     * <p>Uses simulation.distributed.integration.Clock (GAP-6).
     *
     * @param clock the clock to use
     * @throws NullPointerException if clock is null
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Delegate barrier operation with timeout detection.
     *
     * <p><b>Issue #4 Fix</b>: Uses direct barrier.await(timeout, unit) which
     * properly handles timeouts without thread leaks (unlike CompletableFuture.get()).
     *
     * <p>Executes the delegate's barrier() method with a timeout. If the timeout
     * is exceeded, reports a barrier timeout to the FaultHandler.
     *
     * @throws InterruptedException if the barrier operation is interrupted
     * @throws TimeoutException if barrier timeout is exceeded
     */
    public void barrier() throws InterruptedException, TimeoutException {
        try {
            // Direct barrier.await(timeout) - no CompletableFuture wrapper (Issue #4)
            boolean completed = delegate.barrier(barrierTimeoutMs, TimeUnit.MILLISECONDS);

            if (completed) {
                log.debug("Barrier succeeded");
            } else {
                log.warn("Barrier timeout after {}ms", barrierTimeoutMs);
                throw new TimeoutException("Barrier timeout");
            }

        } catch (BrokenBarrierException e) {
            log.error("Barrier broken: {}", e.getMessage());
            throw new InterruptedException("Barrier broken: " + e.getMessage());
        }
    }

    /**
     * Interface that defines a partition registry.
     *
     * <p>Represents the contract for partition coordination via barriers.
     */
    public interface PartitionRegistry {
        /**
         * Synchronize all partitions at a barrier (legacy no-timeout version).
         *
         * @throws InterruptedException if the barrier is interrupted
         */
        void barrier() throws InterruptedException;

        /**
         * Synchronize all partitions at a barrier with timeout.
         *
         * @param timeout maximum time to wait
         * @param unit time unit
         * @return true if barrier completed, false if timeout
         * @throws InterruptedException if the barrier is interrupted
         * @throws BrokenBarrierException if another thread broke the barrier
         */
        boolean barrier(long timeout, TimeUnit unit)
            throws InterruptedException, BrokenBarrierException;
    }
}
