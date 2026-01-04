package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.Objects;

/**
 * String-based entity identifier for simulation entities.
 * Simple, human-readable entity IDs suitable for testing and debugging.
 *
 * @author hal.hildebrand
 */
public final class StringEntityID implements EntityID {
    private final String id;

    public StringEntityID(String id) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
    }

    @Override
    public int compareTo(EntityID other) {
        if (other instanceof StringEntityID stringOther) {
            return this.id.compareTo(stringOther.id);
        }
        // Compare by class name if different types
        return this.getClass().getName().compareTo(other.getClass().getName());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StringEntityID that)) {
            return false;
        }
        return id.equals(that.id);
    }

    public String getValue() {
        return id;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toDebugString() {
        return "Entity[" + id + "]";
    }

    @Override
    public String toString() {
        return id;
    }
}
