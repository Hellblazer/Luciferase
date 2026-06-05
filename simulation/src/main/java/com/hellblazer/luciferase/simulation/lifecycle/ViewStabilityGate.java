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
import com.hellblazer.luciferase.common.time.Clock;
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
 * Thread-safe and supports multiple concurrent awaitStability() calls. A single
 * shared {@link ScheduledExecutorService} is created at construction time and
 * reused across all calls; closing the gate via {@link #close()} shuts the pool
 * down. Individual call cleanup is limited to cancelling the call's own
 * {@code ScheduledFuture} — the shared pool is never shut down per-call.
 * <p>
 * <b>Lifecycle (Luciferase-7wzml.212):</b> callers should close the gate when
 * it is no longer needed (e.g. in a try-with-resources block or a component's
 * own {@code close()} method) to release the background thread.
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
public class ViewStabilityGate implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ViewStabilityGate.class);
    private static final long POLL_INTERVAL_MS = 10;  // Poll every 10ms

    private final FirefliesViewMonitor viewMonitor;
    private final long timeoutMs;
    private final ScheduledExecutorService scheduler;
    private volatile Clock clock = Clock.system();

    /**
     * Create a ViewStabilityGate.
     * <p>
     * Creates one shared daemon-thread scheduler that is reused for all
     * {@link #awaitStability()} calls. Release the thread by calling
     * {@link #close()} when the gate is no longer needed.
     *
     * @param viewMonitor the Fireflies view monitor
     * @param timeoutMs timeout in milliseconds (e.g., 5000 for 5 seconds)
     * @throws NullPointerException if viewMonitor is null
     */
    public ViewStabilityGate(FirefliesViewMonitor viewMonitor, long timeoutMs) {
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");
        this.timeoutMs = timeoutMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "view-stability-poller");
            thread.setDaemon(true);
            return thread;
        });

        log.debug("ViewStabilityGate created: timeout={}ms", timeoutMs);
    }

    /**
     * Set the clock for time measurements.
     * <p>
     * For testing with deterministic time control, inject a TestClock.
     * Defaults to Clock.system() for production use.
     *
     * @param clock the clock implementation to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Wait for view stability or timeout.
     * <p>
     * Polls {@code viewMonitor.isViewStable()} every 10ms on the gate's shared
     * scheduler until:
     * <ul>
     *   <li>View becomes stable → completes successfully</li>
     *   <li>Timeout reached → completes exceptionally with TimeoutException</li>
     * </ul>
     * <p>
     * Multiple concurrent calls are safe; each schedules an independent polling
     * task on the shared single-thread executor. Call cleanup cancels only that
     * call's {@code ScheduledFuture} — the shared pool is never shut down here.
     * <p>
     * Throws {@link java.util.concurrent.RejectedExecutionException} (wrapped in
     * the returned future) if the gate has already been {@link #close() closed}.
     *
     * @return CompletableFuture that completes when view is stable or timeout occurs
     */
    public CompletableFuture<Void> awaitStability() {
        var future = new CompletableFuture<Void>();
        var startTime = clock.currentTimeMillis();

        try {
            // Schedule polling task on the shared executor — no new pool created per call.
            var pollingTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    var elapsed = clock.currentTimeMillis() - startTime;
                    if (elapsed >= timeoutMs) {
                        future.completeExceptionally(
                            new java.util.concurrent.TimeoutException(
                                "View stability timeout after " + elapsed + "ms"));
                        return;
                    }

                    if (viewMonitor.isViewStable()) {
                        log.debug("View stability achieved after {}ms", elapsed);
                        future.complete(null);
                    }

                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

            // Cancel this call's polling task once the future settles (normal or exceptional).
            // The shared pool itself is NOT shut down here.
            future.whenComplete((result, throwable) -> pollingTask.cancel(false));

        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Gate has been closed; propagate as a failed future rather than throwing.
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Shuts down the shared scheduler.
     * <p>
     * Already-scheduled polling tasks are allowed to finish their current
     * iteration; no new tasks will be accepted after this call returns. Any
     * in-flight {@link #awaitStability()} futures complete normally or
     * exceptionally via their own logic before the pool terminates.
     * <p>
     * This method is idempotent and safe to call multiple times.
     */
    @Override
    public void close() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
            log.debug("ViewStabilityGate closed");
        }
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
