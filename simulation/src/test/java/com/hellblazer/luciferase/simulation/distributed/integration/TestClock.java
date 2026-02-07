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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test clock implementation with controllable time for deterministic testing.
 * <p>
 * Supports two modes:
 * <ul>
 * <li>Relative mode: System.currentTimeMillis() + offset</li>
 * <li>Absolute mode: Fixed time value</li>
 * </ul>
 * Thread-safe: Uses AtomicLong for offset/time management.
 *
 * @author hal.hildebrand
 */
public class TestClock implements Clock {

    private final AtomicLong    offset;
    private final AtomicLong    absoluteTime;
    private final AtomicLong    nanoOffset;
    private final AtomicLong    absoluteNanos;
    private final AtomicBoolean absoluteMode;

    /**
     * Creates a new test clock initialized to current system time in relative mode.
     */
    public TestClock() {
        this.offset = new AtomicLong(0);
        this.absoluteTime = new AtomicLong(0);
        this.absoluteNanos = new AtomicLong(0);
        this.nanoOffset = new AtomicLong(0);
        this.absoluteMode = new AtomicBoolean(false);
    }

    /**
     * Creates a new test clock with an initial fixed time (absolute mode).
     *
     * @param initialTime the initial timestamp in milliseconds
     */
    public TestClock(long initialTime) {
        this.offset = new AtomicLong(0);
        this.absoluteTime = new AtomicLong(initialTime);
        this.absoluteNanos = new AtomicLong(initialTime * 1_000_000);
        this.nanoOffset = new AtomicLong(0);
        this.absoluteMode = new AtomicBoolean(true);
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

        if (absoluteMode.get()) {
            absoluteTime.addAndGet(deltaMs);
            absoluteNanos.addAndGet(deltaNanos);
            offset.addAndGet(deltaMs);
        } else {
            offset.addAndGet(deltaMs);
            nanoOffset.addAndGet(deltaNanos);
        }
    }

    /**
     * Sets the absolute clock skew (offset from real time) and switches to relative mode.
     *
     * @param skewMs the skew in milliseconds
     */
    public void setSkew(long skewMs) {
        absoluteMode.set(false);
        offset.set(skewMs);
    }

    /**
     * Sets the clock to an absolute time value and switches to absolute mode.
     * Maintains consistent millis:nanos ratio (1:1,000,000).
     *
     * @param timeMs the absolute time in milliseconds
     */
    public void setTime(long timeMs) {
        absoluteMode.set(true);
        absoluteTime.set(timeMs);
        absoluteNanos.set(timeMs * 1_000_000);
        offset.set(0);
        nanoOffset.set(0);
    }

    /**
     * Returns the current offset from system time (in relative mode) or from initial time (in absolute mode).
     *
     * @return current offset in milliseconds
     */
    public long getOffset() {
        return offset.get();
    }

    /**
     * Returns the current clock skew (alias for getOffset).
     *
     * @return current skew in milliseconds
     */
    public long getSkew() {
        return offset.get();
    }

    /**
     * Returns true if the clock is in absolute mode (fixed time).
     *
     * @return true if absolute mode
     */
    public boolean isAbsoluteMode() {
        return absoluteMode.get();
    }

    /**
     * Resets the clock to relative mode with zero offset.
     * Clears all time tracking fields including nanosecond offsets.
     */
    public void reset() {
        absoluteMode.set(false);
        offset.set(0);
        absoluteTime.set(0);
        nanoOffset.set(0);
        absoluteNanos.set(0);
    }

    @Override
    public long currentTimeMillis() {
        if (absoluteMode.get()) {
            return absoluteTime.get();
        } else {
            return System.currentTimeMillis() + offset.get();
        }
    }

    @Override
    public long nanoTime() {
        if (absoluteMode.get()) {
            return absoluteNanos.get();
        } else {
            return System.nanoTime() + nanoOffset.get();
        }
    }
}
