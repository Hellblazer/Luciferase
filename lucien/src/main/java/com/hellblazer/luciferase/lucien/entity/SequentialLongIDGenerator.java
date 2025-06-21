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
package com.hellblazer.luciferase.lucien.entity;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Sequential long-based ID generator. Thread-safe. Mimics C++ vector index generation:
 * TEntityID(m_geometryCollection.size())
 *
 * @author hal.hildebrand
 */
public class SequentialLongIDGenerator implements EntityIDGenerator<LongEntityID> {
    private final AtomicLong counter;

    public SequentialLongIDGenerator() {
        this(0L);
    }

    public SequentialLongIDGenerator(long startValue) {
        this.counter = new AtomicLong(startValue);
    }

    @Override
    public LongEntityID generateID() {
        return new LongEntityID(counter.getAndIncrement());
    }

    /**
     * Get the current counter value without incrementing
     */
    public long getCurrentValue() {
        return counter.get();
    }

    @Override
    public void reset() {
        counter.set(0L);
    }

    /**
     * Set the counter to a specific value
     */
    public void setCounter(long value) {
        counter.set(value);
    }
}
