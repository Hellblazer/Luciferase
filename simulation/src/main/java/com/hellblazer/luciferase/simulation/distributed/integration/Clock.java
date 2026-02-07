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

/**
 * Pluggable clock interface for testing with deterministic time control.
 * <p>
 * Implementations:
 * <ul>
 * <li>{@link #system()} - delegates to System.currentTimeMillis()</li>
 * <li>{@link #fixed(long)} - returns a fixed timestamp</li>
 * <li>{@link TestClock} - controllable test clock with skew injection</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public interface Clock {

    /**
     * Returns the current time in milliseconds.
     *
     * @return current time in milliseconds since epoch
     */
    long currentTimeMillis();

    /**
     * Returns the current high-resolution time in nanoseconds.
     * <p>
     * This is used for measuring elapsed time intervals (relative time), not absolute timestamps.
     * The returned value is relative to an arbitrary origin and is NOT comparable across
     * JVM instances or to {@link #currentTimeMillis()}.
     * <p>
     * <b>Note</b>: {@link #fixed(long)} does not support this method and will throw
     * {@link UnsupportedOperationException}. Use {@link TestClock} for tests requiring
     * elapsed time measurements.
     * <p>
     * Default implementation delegates to {@link System#nanoTime()}.
     *
     * @return current high-resolution time in nanoseconds
     * @throws UnsupportedOperationException if called on {@link #fixed(long)} clock
     */
    default long nanoTime() {
        return System.nanoTime();
    }

    /**
     * Returns a clock that delegates to the system clock.
     *
     * @return system clock implementation
     */
    static Clock system() {
        return new Clock() {
            @Override
            public long currentTimeMillis() {
                return System.currentTimeMillis();
            }

            @Override
            public long nanoTime() {
                return System.nanoTime();
            }
        };
    }

    /**
     * Returns a clock that always returns the given fixed time.
     * <p>
     * <b>Important</b>: {@link #nanoTime()} throws {@link UnsupportedOperationException}
     * because fixed clocks cannot support elapsed time measurements. Use {@link TestClock}
     * for tests requiring time advancement.
     *
     * @param fixedTime the fixed timestamp in milliseconds
     * @return fixed clock implementation
     * @throws UnsupportedOperationException if {@link #nanoTime()} is called
     */
    static Clock fixed(long fixedTime) {
        return new Clock() {
            @Override
            public long currentTimeMillis() {
                return fixedTime;
            }

            @Override
            public long nanoTime() {
                throw new UnsupportedOperationException(
                    "Clock.fixed() does not support elapsed time measurements via nanoTime(). " +
                    "Fixed clocks return constant values, making elapsed time always 0. " +
                    "Use TestClock for tests requiring time advancement. " +
                    "Example: var clock = new TestClock(1000L); clock.advance(500); " +
                    "See simulation/doc/H3_DETERMINISM_EPIC.md and ADR_002_CLOCK_FIXED_NANOTIME.md"
                );
            }
        };
    }
}
