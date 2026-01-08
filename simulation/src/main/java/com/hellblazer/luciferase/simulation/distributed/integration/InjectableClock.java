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
 * <li>{@link TestClock} - controllable test clock with skew injection</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public interface InjectableClock {

    /**
     * Returns the current time in milliseconds.
     *
     * @return current time in milliseconds since epoch
     */
    long currentTimeMillis();

    /**
     * Returns a clock that delegates to the system clock.
     *
     * @return system clock implementation
     */
    static InjectableClock system() {
        return System::currentTimeMillis;
    }
}
