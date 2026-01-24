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

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Scheduler that uses injected time source for deterministic timing.
 *
 * <p>Unlike ScheduledExecutorService which uses wall-clock time,
 * this scheduler delegates timing decisions to an injected LongSupplier.
 * This enables deterministic testing via TestClock.
 *
 * <p><b>Clock Injection</b>: Uses LongSupplier reflection pattern to support
 * both TestClock (from simulation) and java.time.Clock without creating a
 * compile-time dependency on simulation module. This breaks the cyclic
 * dependency between lucien and simulation.
 *
 * <p><b>Usage Pattern</b>:
 * <pre>
 * // Production: uses system time
 * var scheduler = new ClockAwareScheduler(System::currentTimeMillis, 1000, task);
 * scheduler.start();
 *
 * // Testing: manual tick advancement
 * var testClock = new TestClock(0);
 * var scheduler = new ClockAwareScheduler(testClock::currentTimeMillis, 1000, task);
 * scheduler.start();
 *
 * testClock.advance(1000);
 * scheduler.tick(); // Task executes exactly once
 * </pre>
 *
 * <p><b>Thread-Safe</b>: Volatile fields ensure visibility across threads.
 *
 * @author hal.hildebrand
 */
public class ClockAwareScheduler {

    private final LongSupplier timeSource;
    private final long intervalMs;
    private final Runnable task;
    private volatile long lastExecutionTime;
    private volatile boolean running = false;

    /**
     * Create a clock-aware scheduler.
     *
     * @param timeSource the time source for timing decisions (LongSupplier)
     * @param intervalMs execution interval in milliseconds
     * @param task the task to execute periodically
     * @throws NullPointerException if timeSource or task is null
     * @throws IllegalArgumentException if intervalMs is not positive
     */
    public ClockAwareScheduler(LongSupplier timeSource, long intervalMs, Runnable task) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource must not be null");
        this.task = Objects.requireNonNull(task, "task must not be null");

        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be positive, got " + intervalMs);
        }

        this.intervalMs = intervalMs;
        this.lastExecutionTime = timeSource.getAsLong();
    }

    /**
     * Check if interval has elapsed and execute task if needed.
     *
     * <p>Call this method periodically (e.g., from main loop) or after
     * advancing the test clock for deterministic testing.
     *
     * <p><b>Deterministic Testing</b>:
     * <pre>
     * testClock.advance(1000);  // Advance clock
     * scheduler.tick();         // Execute if interval elapsed
     * </pre>
     *
     * @return true if task was executed, false if interval not yet elapsed
     */
    public boolean tick() {
        if (!running) {
            return false;
        }

        long now = timeSource.getAsLong();
        if (now - lastExecutionTime >= intervalMs) {
            lastExecutionTime = now;
            task.run();
            return true;
        }
        return false;
    }

    /**
     * Start scheduling.
     *
     * <p>Initializes lastExecutionTime to current time source value.
     * Subsequent tick() calls will execute task when interval elapses.
     */
    public void start() {
        running = true;
        lastExecutionTime = timeSource.getAsLong();
    }

    /**
     * Stop scheduling.
     *
     * <p>Future tick() calls will return false without executing task.
     */
    public void stop() {
        running = false;
    }

    /**
     * Check if scheduler is running.
     *
     * @return true if start() has been called and stop() has not
     */
    public boolean isRunning() {
        return running;
    }
}
