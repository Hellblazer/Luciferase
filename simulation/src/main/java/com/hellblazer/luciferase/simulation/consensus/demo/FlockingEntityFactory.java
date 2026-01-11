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

import java.util.Objects;
import java.util.UUID;

/**
 * Factory for creating flocking entities with consistent behavior parameters.
 * <p>
 * Provides factory methods for creating entities with:
 * - Default flocking parameters (AOI: 30 units, max speed: 15 units/tick)
 * - Custom flocking parameters via FlockingConfig
 * <p>
 * DEFAULT PARAMETERS:
 * - AOI radius: 30.0 units (neighbor detection range)
 * - Max speed: 15.0 units/tick (velocity magnitude limit)
 * - Separation radius: 5.0 units (minimum neighbor distance)
 * - Mass: 1.0 (entity mass for inertia)
 * <p>
 * STATELESS:
 * Factory is stateless, all methods are static.
 * <p>
 * Phase 8C Day 1: Behavioral Entity Integration
 *
 * @author hal.hildebrand
 */
public class FlockingEntityFactory {

    /**
     * Default flocking parameters.
     */
    private static final double DEFAULT_AOI_RADIUS = 30.0;
    private static final double DEFAULT_MAX_SPEED = 15.0;
    private static final double DEFAULT_SEPARATION_RADIUS = 5.0;
    private static final double DEFAULT_MASS = 1.0;

    /**
     * Flocking configuration parameters.
     * <p>
     * Mutable builder-style configuration object.
     */
    public static class FlockingConfig {
        public double aoiRadius = DEFAULT_AOI_RADIUS;
        public double maxSpeed = DEFAULT_MAX_SPEED;
        public double separationRadius = DEFAULT_SEPARATION_RADIUS;
        public double mass = DEFAULT_MASS;

        public FlockingConfig() {
        }

        public FlockingConfig(double aoiRadius, double maxSpeed, double separationRadius, double mass) {
            this.aoiRadius = aoiRadius;
            this.maxSpeed = maxSpeed;
            this.separationRadius = separationRadius;
            this.mass = mass;
        }
    }

    /**
     * Create flocking entity with default parameters.
     *
     * @param id       Entity ID
     * @param location Starting location
     * @return FlockingEntity with default behavior
     */
    public static FlockingEntity createEntity(UUID id, TetreeKey<?> location) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(location, "location must not be null");

        var behavior = new FlockingBehavior(
            DEFAULT_AOI_RADIUS,
            DEFAULT_MAX_SPEED,
            DEFAULT_SEPARATION_RADIUS,
            DEFAULT_MASS
        );

        return new FlockingEntity(id, location, behavior);
    }

    /**
     * Create flocking entity with custom configuration.
     *
     * @param id       Entity ID
     * @param location Starting location
     * @param config   Flocking configuration
     * @return FlockingEntity with custom behavior
     */
    public static FlockingEntity createWithConfig(UUID id, TetreeKey<?> location, FlockingConfig config) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(config, "config must not be null");

        var behavior = new FlockingBehavior(
            config.aoiRadius,
            config.maxSpeed,
            config.separationRadius,
            config.mass
        );

        return new FlockingEntity(id, location, behavior);
    }
}
