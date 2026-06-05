/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.dag.cache;

import com.hellblazer.luciferase.common.time.Clock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable test clock for deterministic time-based tests in the render module.
 * Thread-safe: uses AtomicLong.
 *
 * @author hal.hildebrand
 */
class TestClock implements Clock {

    private final AtomicLong time;

    TestClock(long initialTimeMs) {
        this.time = new AtomicLong(initialTimeMs);
    }

    /** Advance the clock by {@code deltaMs} milliseconds. */
    void advance(long deltaMs) {
        if (deltaMs < 0) {
            throw new IllegalArgumentException("Cannot advance by negative amount: " + deltaMs);
        }
        time.addAndGet(deltaMs);
    }

    /** Set the clock to an absolute millisecond value. */
    void setTime(long timeMs) {
        time.set(timeMs);
    }

    @Override
    public long currentTimeMillis() {
        return time.get();
    }
}
