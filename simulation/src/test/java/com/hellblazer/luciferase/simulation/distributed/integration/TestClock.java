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
package com.hellblazer.luciferase.simulation.distributed.integration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Test clock implementation with controllable time for deterministic testing.
 * <p>
 * Provides absolute (fixed) time mode only. All tests now use this mode,
 * so relative mode complexity has been removed.
 * <p>
 * Thread-safe: Uses AtomicLong for time management.
 *
 * @author hal.hildebrand
 */
public class TestClock implements Clock {

    private final AtomicLong absoluteTime;
    private final AtomicLong absoluteNanos;

    /**
     * Creates a new test clock initialized to current system time.
     */
    public TestClock() {
        long now = System.currentTimeMillis();
        this.absoluteTime = new AtomicLong(now);
        this.absoluteNanos = new AtomicLong(now * 1_000_000);
    }

    /**
     * Creates a new test clock with an initial fixed time.
     *
     * @param initialTime the initial timestamp in milliseconds
     */
    public TestClock(long initialTime) {
        this.absoluteTime = new AtomicLong(initialTime);
        this.absoluteNanos = new AtomicLong(initialTime * 1_000_000);
    }

    /**
     * Advances the clock by the specified delta.
     * Maintains consistent millis:nanos ratio (1:1,000,000).
     *
     * @param deltaMs milliseconds to advance (must be non-negative)
     * @throws IllegalArgumentException if deltaMs is negative
     */
    public void advance(long deltaMs) {
        if (deltaMs < 0) {
            throw new IllegalArgumentException("Cannot advance by negative amount: " + deltaMs);
        }

        long deltaNanos = deltaMs * 1_000_000;
        absoluteTime.addAndGet(deltaMs);
        absoluteNanos.addAndGet(deltaNanos);
    }

    /**
     * Sets the clock to an absolute time value.
     * Maintains consistent millis:nanos ratio (1:1,000,000).
     *
     * @param timeMs the absolute time in milliseconds
     */
    public void setTime(long timeMs) {
        absoluteTime.set(timeMs);
        absoluteNanos.set(timeMs * 1_000_000);
    }

    @Override
    public long currentTimeMillis() {
        return absoluteTime.get();
    }

    @Override
    public long nanoTime() {
        return absoluteNanos.get();
    }
}
