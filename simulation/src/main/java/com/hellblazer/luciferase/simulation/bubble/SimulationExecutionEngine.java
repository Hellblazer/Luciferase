/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulation tick loop execution engine.
 * <p>
 * Manages the simulation execution lifecycle: scheduling, tick counting, start/stop control.
 * Provides deterministic time support via Clock interface for testing.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Schedule periodic tick execution at fixed intervals</li>
 *   <li>Track tick count and bucket (for ghost sync)</li>
 *   <li>Provide start/stop lifecycle control</li>
 *   <li>Support deterministic time for testing</li>
 * </ul>
 * <p>
 * Thread-safe for concurrent start/stop operations.
 *
 * @author hal.hildebrand
 */
public class SimulationExecutionEngine implements AutoCloseable {

    /**
     * Default tick interval: 60fps (16.67ms).
     */
    public static final long DEFAULT_TICK_INTERVAL_MS = 16;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;
    private final AtomicLong tickCount;
    private final AtomicLong currentBucket;
    private volatile Clock clock = Clock.system();
    private ScheduledFuture<?> tickTask;

    /**
     * Create a new execution engine with default scheduler.
     */
    public SimulationExecutionEngine() {
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.running = new AtomicBoolean(false);
        this.tickCount = new AtomicLong(0);
        this.currentBucket = new AtomicLong(0);
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Get the clock instance.
     *
     * @return Current clock
     */
    public Clock getClock() {
        return clock;
    }

    /**
     * Start the simulation tick loop.
     *
     * @param tickCallback Callback to execute on each tick
     * @throws IllegalStateException if already running
     */
    public void start(Runnable tickCallback) {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("Simulation is already running");
        }

        tickTask = scheduler.scheduleAtFixedRate(
            tickCallback,
            0,
            DEFAULT_TICK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stop the simulation tick loop.
     */
    public void stop() {
        if (running.getAndSet(false)) {
            if (tickTask != null) {
                tickTask.cancel(false);
                tickTask = null;
            }
        }
    }

    /**
     * Check if simulation is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get current tick count.
     *
     * @return Number of ticks executed
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Increment tick count and return new value.
     *
     * @return New tick count
     */
    public long incrementTickCount() {
        return tickCount.incrementAndGet();
    }

    /**
     * Get current bucket (for ghost synchronization).
     *
     * @return Current bucket number
     */
    public long getCurrentBucket() {
        return currentBucket.get();
    }

    /**
     * Increment bucket and return new value.
     *
     * @return New bucket value
     */
    public long incrementBucket() {
        return currentBucket.incrementAndGet();
    }

    /**
     * Close execution engine and release resources.
     */
    @Override
    public void close() {
        stop();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
