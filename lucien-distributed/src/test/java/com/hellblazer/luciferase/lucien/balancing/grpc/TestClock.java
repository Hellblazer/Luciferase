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
package com.hellblazer.luciferase.lucien.balancing.grpc;

import com.hellblazer.luciferase.common.time.Clock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Controllable clock for deterministic test assertions.
 */
class TestClock implements Clock {

    private final AtomicLong millis;

    TestClock(long initialMillis) {
        this.millis = new AtomicLong(initialMillis);
    }

    public void setTime(long ms) {
        millis.set(ms);
    }

    @Override
    public long currentTimeMillis() {
        return millis.get();
    }

    @Override
    public long nanoTime() {
        return millis.get() * 1_000_000L;
    }
}
