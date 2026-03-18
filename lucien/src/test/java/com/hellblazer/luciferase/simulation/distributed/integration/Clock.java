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
 * Minimal clock interface for lucien module testing.
 * <p>
 * This is a duck-typed copy that provides the same interface as the canonical
 * Clock in the common module, but avoids a circular lucien -&gt; simulation
 * dependency. The two interfaces are structurally identical and assignment-compatible
 * within the same package name.
 * <p>
 * <b>Canonical definition</b>: {@code com.hellblazer.luciferase.simulation.distributed.integration.Clock}
 * in the {@code common} module. Sync this copy whenever the canonical changes.
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.simulation.distributed.integration.Clock
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
     * Default implementation delegates to {@link System#nanoTime()}.
     *
     * @return current high-resolution time in nanoseconds
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
     * because fixed clocks cannot support elapsed time measurements.
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
                    "Clock.fixed() does not support elapsed time measurements via nanoTime(). "
                    + "Fixed clocks return constant values, making elapsed time always 0. "
                    + "Use TestClock for tests requiring time advancement.");
            }
        };
    }
}
