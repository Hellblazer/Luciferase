/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.integration;

/**
 * Interface for pluggable time source in distributed systems.
 * <p>
 * Allows injection of custom clocks for deterministic testing while
 * supporting production use with system clock. Located in common module
 * to avoid cyclic dependencies between lucien and simulation.
 * <p>
 * Thread-safe: Implementations should be thread-safe for concurrent access.
 */
public interface Clock {

    /**
     * Get current time in milliseconds.
     *
     * @return current time in milliseconds
     */
    long currentTimeMillis();

    /**
     * Get current time in nanoseconds.
     * <p>
     * Default implementation uses System.nanoTime().
     * Can be overridden for deterministic testing.
     *
     * @return current time in nanoseconds
     */
    default long nanoTime() {
        return System.nanoTime();
    }

    /**
     * Create clock using system time source.
     *
     * @return clock backed by System.currentTimeMillis()
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
     * Create clock with fixed time value.
     *
     * @param fixedTime fixed time in milliseconds
     * @return clock always returning the same time
     */
    static Clock fixed(long fixedTime) {
        return new Clock() {
            @Override
            public long currentTimeMillis() {
                return fixedTime;
            }

            @Override
            public long nanoTime() {
                return fixedTime * 1_000_000;
            }
        };
    }
}
