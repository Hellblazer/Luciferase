package com.hellblazer.luciferase.simulation.entity;

import com.hellblazer.luciferase.simulation.entity.*;

import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Entity ID generator for String-based IDs.
 * Generates sequential IDs like "entity-0", "entity-1", etc.
 *
 * @author hal.hildebrand
 */
public class StringEntityIDGenerator implements EntityIDGenerator<StringEntityID> {
    private final AtomicLong counter = new AtomicLong(0);
    private final String prefix;

    public StringEntityIDGenerator() {
        this("entity");
    }

    public StringEntityIDGenerator(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public StringEntityID generateID() {
        return new StringEntityID(prefix + "-" + counter.getAndIncrement());
    }

    @Override
    public void reset() {
        counter.set(0);
    }
}
