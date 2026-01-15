/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import javax.vecmath.Vector3f;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity with flocking behavior.
 * <p>
 * Represents a single entity in the simulation with:
 * - Unique ID
 * - Current spatial location (TetreeKey)
 * - Velocity vector (3D)
 * - Flocking behavior parameters
 * <p>
 * THREAD SAFETY:
 * Mutable state (location, velocity) should be updated only by simulation runner.
 * <p>
 * Phase 8C Day 1: Behavioral Entity Integration
 *
 * @author hal.hildebrand
 */
public class FlockingEntity {

    private final UUID id;
    private final FlockingBehavior behavior;
    private TetreeKey<?> location;
    private Vector3f velocity;

    /**
     * Create FlockingEntity.
     *
     * @param id       Entity ID
     * @param location Starting location
     * @param behavior Flocking behavior parameters
     */
    public FlockingEntity(UUID id, TetreeKey<?> location, FlockingBehavior behavior) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.location = Objects.requireNonNull(location, "location must not be null");
        this.behavior = Objects.requireNonNull(behavior, "behavior must not be null");
        this.velocity = new Vector3f(0.0f, 0.0f, 0.0f); // Start stationary
    }

    /**
     * Get entity ID.
     *
     * @return UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Get current location.
     *
     * @return TetreeKey location
     */
    public TetreeKey<?> getLocation() {
        return location;
    }

    /**
     * Set location.
     *
     * @param location New location
     */
    public void setLocation(TetreeKey<?> location) {
        this.location = Objects.requireNonNull(location, "location must not be null");
    }

    /**
     * Get velocity vector.
     *
     * @return Vector3f velocity
     */
    public Vector3f getVelocity() {
        return new Vector3f(velocity); // Defensive copy
    }

    /**
     * Set velocity vector.
     *
     * @param velocity New velocity
     */
    public void setVelocity(Vector3f velocity) {
        this.velocity = new Vector3f(Objects.requireNonNull(velocity, "velocity must not be null"));
    }

    /**
     * Get flocking behavior.
     *
     * @return FlockingBehavior
     */
    public FlockingBehavior getFlockingBehavior() {
        return behavior;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FlockingEntity that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "FlockingEntity{" +
               "id=" + id +
               ", location=" + location +
               ", velocity=" + velocity +
               '}';
    }
}
