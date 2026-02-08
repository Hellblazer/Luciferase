/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.lifecycle;

import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gate that waits for Fireflies view stability before allowing shutdown to proceed.
 * <p>
 * View stability ensures all messages sent within the current view are delivered
 * before components close, preventing message loss during graceful shutdown.
 * <p>
 * Uses polling strategy to check view stability every 10ms. Completes when:
 * <ul>
 *   <li>View is stable (no membership changes for threshold ticks)</li>
 *   <li>Timeout is reached (graceful degradation)</li>
 * </ul>
 * <p>
 * Thread-safe and supports multiple concurrent awaitStability() calls.
 * <p>
 * <b>Fireflies Virtual Synchrony:</b>
 * <ul>
 *   <li>View: Current set of active cluster members</li>
 *   <li>View Stability: No membership changes for N ticks (default 30 ticks = 300ms at 100Hz)</li>
 *   <li>Guarantee: All messages sent within stable view delivered to live members</li>
 *   <li>Graceful Shutdown: Wait for view stability before closing components</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ViewStabilityGate {
    private static final Logger log = LoggerFactory.getLogger(ViewStabilityGate.class);
    private static final long POLL_INTERVAL_MS = 10;  // Poll every 10ms

    private final FirefliesViewMonitor viewMonitor;
    private final long timeoutMs;

    /**
     * Create a ViewStabilityGate.
     *
     * @param viewMonitor the Fireflies view monitor
     * @param timeoutMs timeout in milliseconds (e.g., 5000 for 5 seconds)
     * @throws NullPointerException if viewMonitor is null
     */
    public ViewStabilityGate(FirefliesViewMonitor viewMonitor, long timeoutMs) {
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");
        this.timeoutMs = timeoutMs;

        log.debug("ViewStabilityGate created: timeout={}ms", timeoutMs);
    }

    /**
     * Wait for view stability or timeout.
     * <p>
     * Polls viewMonitor.isViewStable() every 10ms until:
     * <ul>
     *   <li>View becomes stable → completes successfully</li>
     *   <li>Timeout reached → completes exceptionally with TimeoutException</li>
     * </ul>
     * <p>
     * Multiple concurrent calls are safe and will each create independent polling tasks.
     *
     * @return CompletableFuture that completes when view is stable or timeout occurs
     */
    public CompletableFuture<Void> awaitStability() {
        var future = new CompletableFuture<Void>();
        var startTime = System.currentTimeMillis();

        // Create a single-threaded scheduled executor for polling
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "view-stability-poller");
            thread.setDaemon(true);
            return thread;
        });

        // Schedule polling task
        var pollingTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                // Check for timeout
                var elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= timeoutMs) {
                    future.completeExceptionally(
                        new java.util.concurrent.TimeoutException("View stability timeout after " + elapsed + "ms")
                    );
                    scheduler.shutdown();
                    return;
                }

                // Check if view is stable
                if (viewMonitor.isViewStable()) {
                    log.debug("View stability achieved after {}ms", elapsed);
                    future.complete(null);
                    scheduler.shutdown();
                }

            } catch (Exception e) {
                future.completeExceptionally(e);
                scheduler.shutdown();
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Cleanup scheduler when future completes (either success or failure)
        future.whenComplete((result, throwable) -> {
            if (!scheduler.isShutdown()) {
                scheduler.shutdown();
            }
        });

        return future;
    }

    /**
     * Check if view is currently stable.
     * <p>
     * Delegates to viewMonitor.isViewStable().
     *
     * @return true if view is stable
     */
    public boolean isStable() {
        return viewMonitor.isViewStable();
    }

    /**
     * Get configured timeout in milliseconds.
     *
     * @return timeout in milliseconds
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    @Override
    public String toString() {
        return String.format("ViewStabilityGate{timeout=%dms, stable=%s}", timeoutMs, isStable());
    }
}
