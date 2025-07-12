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
package com.hellblazer.luciferase.lucien.lockfree;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.SpatialKey;

import javax.vecmath.Point3f;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable versioned state for entities in the spatial index, enabling optimistic concurrency control.
 * Each state change creates a new version, allowing lock-free updates through compare-and-swap operations.
 * 
 * <p>The versioning system ensures that entities are always findable during concurrent operations,
 * preventing race conditions and maintaining spatial index consistency.</p>
 * 
 * @param <Key> The spatial key type
 * @param <ID> The entity ID type
 * @param <Content> The entity content type
 * 
 * @author hal.hildebrand
 */
public final class VersionedEntityState<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    /**
     * Movement states for tracking entity lifecycle during spatial operations
     */
    public enum MovementState {
        /** Entity is stable at its current position */
        STABLE,
        /** Entity is being moved to a new position */
        MOVING,
        /** Entity is being removed from the index */
        REMOVING,
        /** Entity is being inserted into the index */
        INSERTING
    }

    private static final AtomicLong VERSION_GENERATOR = new AtomicLong(0);

    private final long version;
    private final ID entityId;
    private final Point3f position;
    private final Content content;
    private final Set<Key> spatialLocations;
    private final MovementState movementState;
    private final long timestamp;

    /**
     * Creates a new versioned entity state.
     */
    public VersionedEntityState(ID entityId, Point3f position, Content content, 
                              Set<Key> spatialLocations, MovementState movementState) {
        this.version = VERSION_GENERATOR.incrementAndGet();
        this.entityId = entityId;
        this.position = new Point3f(position); // Defensive copy
        this.content = content;
        this.spatialLocations = Set.copyOf(spatialLocations); // Immutable copy
        this.movementState = movementState;
        this.timestamp = System.nanoTime();
    }

    /**
     * Creates a new state with updated position.
     */
    public VersionedEntityState<Key, ID, Content> withPosition(Point3f newPosition) {
        return new VersionedEntityState<>(entityId, newPosition, content, spatialLocations, MovementState.STABLE);
    }

    /**
     * Creates a new state with updated spatial locations.
     */
    public VersionedEntityState<Key, ID, Content> withSpatialLocations(Set<Key> newLocations) {
        return new VersionedEntityState<>(entityId, position, content, newLocations, MovementState.STABLE);
    }

    /**
     * Creates a new state with updated movement state.
     */
    public VersionedEntityState<Key, ID, Content> withMovementState(MovementState newState) {
        return new VersionedEntityState<>(entityId, position, content, spatialLocations, newState);
    }

    /**
     * Creates a new state with updated content.
     */
    public VersionedEntityState<Key, ID, Content> withContent(Content newContent) {
        return new VersionedEntityState<>(entityId, position, newContent, spatialLocations, MovementState.STABLE);
    }

    // Getters
    public long getVersion() { return version; }
    public ID getEntityId() { return entityId; }
    public Point3f getPosition() { return new Point3f(position); } // Defensive copy
    public Content getContent() { return content; }
    public Set<Key> getSpatialLocations() { return spatialLocations; }
    public MovementState getMovementState() { return movementState; }
    public long getTimestamp() { return timestamp; }

    /**
     * Checks if this state is newer than the given version.
     */
    public boolean isNewerThan(long otherVersion) {
        return this.version > otherVersion;
    }

    /**
     * Checks if the entity is in a stable state (not being modified).
     */
    public boolean isStable() {
        return movementState == MovementState.STABLE;
    }

    /**
     * Thread-safe container for versioned entity state with atomic updates.
     */
    public static class AtomicVersionedState<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
        
        private final AtomicReference<VersionedEntityState<Key, ID, Content>> stateRef;

        public AtomicVersionedState(VersionedEntityState<Key, ID, Content> initialState) {
            this.stateRef = new AtomicReference<>(initialState);
        }

        /**
         * Gets the current state.
         */
        public VersionedEntityState<Key, ID, Content> get() {
            return stateRef.get();
        }

        /**
         * Atomically updates the state if the current version matches expected.
         * 
         * @param expectedVersion The expected current version
         * @param newState The new state to set
         * @return true if update succeeded, false if version mismatch
         */
        public boolean compareAndSwap(long expectedVersion, VersionedEntityState<Key, ID, Content> newState) {
            var current = stateRef.get();
            return current.getVersion() == expectedVersion && stateRef.compareAndSet(current, newState);
        }

        /**
         * Optimistically updates the state with retry logic.
         * 
         * @param updater Function to create new state from current state
         * @param maxRetries Maximum number of retry attempts
         * @return true if update succeeded within retry limit
         */
        public boolean optimisticUpdate(java.util.function.Function<VersionedEntityState<Key, ID, Content>, 
                                       VersionedEntityState<Key, ID, Content>> updater, int maxRetries) {
            int attempts = 0;
            while (attempts < maxRetries) {
                var current = stateRef.get();
                var newState = updater.apply(current);
                
                if (stateRef.compareAndSet(current, newState)) {
                    return true;
                }
                
                attempts++;
                // Exponential backoff
                if (attempts < maxRetries) {
                    try {
                        Thread.sleep(1L << Math.min(attempts, 10)); // Cap at 1024ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return false;
        }

        /**
         * Unconditionally sets the state (use with caution).
         */
        public void set(VersionedEntityState<Key, ID, Content> newState) {
            stateRef.set(newState);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof VersionedEntityState<?, ?, ?> other)) return false;
        return version == other.version && entityId.equals(other.entityId);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(version) ^ entityId.hashCode();
    }

    @Override
    public String toString() {
        return String.format("VersionedEntityState{version=%d, entityId=%s, position=%s, state=%s}", 
                           version, entityId, position, movementState);
    }
}