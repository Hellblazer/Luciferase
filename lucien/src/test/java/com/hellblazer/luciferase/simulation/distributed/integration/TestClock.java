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
 * Minimal test clock implementation for lucien module testing.
 * <p>
 * This is a duck-typed implementation that provides the same interface as
 * simulation module's TestClock, but avoids circular dependency.
 * <p>
 * Thread-safe: Uses AtomicLong for time management.
 *
 * @author hal.hildebrand
 */
public class TestClock implements Clock {

    private final AtomicLong absoluteTime;

    /**
     * Creates a new test clock with an initial fixed time.
     *
     * @param initialTime the initial timestamp in milliseconds
     */
    public TestClock(long initialTime) {
        this.absoluteTime = new AtomicLong(initialTime);
    }

    /**
     * Advances the clock by the specified delta.
     *
     * @param deltaMs milliseconds to advance (must be non-negative)
     * @throws IllegalArgumentException if deltaMs is negative
     */
    public void advance(long deltaMs) {
        if (deltaMs < 0) {
            throw new IllegalArgumentException("Cannot advance by negative amount: " + deltaMs);
        }
        absoluteTime.addAndGet(deltaMs);
    }

    /**
     * Sets the clock to an absolute time value.
     *
     * @param timeMs the absolute time in milliseconds
     */
    public void setTime(long timeMs) {
        absoluteTime.set(timeMs);
    }

    /**
     * Returns the current time in milliseconds.
     * <p>
     * This method signature matches the LongSupplier duck-typing pattern used
     * by lucien fault tolerance components.
     *
     * @return current time in milliseconds since epoch
     */
    @Override
    public long currentTimeMillis() {
        return absoluteTime.get();
    }
}
