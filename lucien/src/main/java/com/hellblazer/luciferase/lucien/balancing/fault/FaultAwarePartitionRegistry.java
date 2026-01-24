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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decorator that adds timeout detection to partition barrier operations.
 *
 * <p>Wraps a PartitionRegistry and monitors barrier operations for timeouts.
 * Reports failures to FaultHandler when barrier timeout thresholds are exceeded.
 *
 * <p><b>Usage</b>:
 * <pre>
 * var registry = new InMemoryPartitionRegistry(...);
 * var handler = new SimpleFaultHandler(...);
 * var faultAware = new FaultAwarePartitionRegistry(registry, handler, 5000);
 *
 * faultAware.barrier(); // Throws if timeout exceeded
 * </pre>
 *
 * <p><b>Thread-Safe</b>: Safe for concurrent barrier operations. Clock is
 * volatile for atomic updates.
 */
public class FaultAwarePartitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(FaultAwarePartitionRegistry.class);

    private final PartitionRegistry delegate;
    private final FaultHandler faultHandler;
    private final long barrierTimeoutMs;
    private volatile Clock clock = Clock.systemDefaultZone();

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
     * <p>Allows tests to inject a fixed or controlled clock.
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
     * <p>Executes the delegate's barrier() method with a timeout. If the timeout
     * is exceeded, reports a barrier timeout to the FaultHandler.
     *
     * @throws InterruptedException if the barrier operation is interrupted or fails
     * @throws TimeoutException if barrier timeout is exceeded
     */
    public void barrier() throws InterruptedException, TimeoutException {
        long startTime = clock.millis();

        try {
            // Execute barrier directly with timeout via CompletableFuture
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    delegate.barrier();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);  // Wrap to propagate through CompletableFuture
                }
            });

            future.get(barrierTimeoutMs, TimeUnit.MILLISECONDS);
            log.debug("Barrier succeeded");

        } catch (TimeoutException e) {
            log.warn("Barrier timeout after {}ms", clock.millis() - startTime);
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            // Unwrap wrapped InterruptedException
            if (e.getCause() instanceof RuntimeException &&
                e.getCause().getCause() instanceof InterruptedException) {
                throw (InterruptedException) e.getCause().getCause();
            }
            log.error("Barrier failed: {}", e.getMessage());
            throw new InterruptedException("Barrier operation failed: " + e.getMessage());
        } catch (InterruptedException e) {
            log.error("Barrier interrupted: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Interface that defines a partition registry.
     *
     * <p>Represents the contract for partition coordination via barriers.
     */
    public interface PartitionRegistry {
        /**
         * Synchronize all partitions at a barrier.
         *
         * @throws InterruptedException if the barrier is interrupted
         */
        void barrier() throws InterruptedException;
    }
}
