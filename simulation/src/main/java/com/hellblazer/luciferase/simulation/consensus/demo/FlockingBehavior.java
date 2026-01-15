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

/**
 * Flocking behavior parameters for entities.
 * <p>
 * Implements standard boid flocking algorithm with three rules:
 * 1. Separation: Avoid crowding neighbors (within separationRadius)
 * 2. Alignment: Steer towards average heading of neighbors (within aoiRadius)
 * 3. Cohesion: Steer towards average location of neighbors (within aoiRadius)
 * <p>
 * PARAMETERS:
 * - aoiRadius: Area of Interest radius for detecting neighbors (default 30.0)
 * - maxSpeed: Maximum velocity magnitude (default 15.0 units/tick)
 * - separationRadius: Minimum distance to maintain from neighbors (default 5.0)
 * - mass: Entity mass for inertia calculations (default 1.0)
 * <p>
 * IMMUTABILITY:
 * Behavior parameters are immutable after construction.
 * <p>
 * Phase 8C Day 1: Behavioral Entity Integration
 *
 * @author hal.hildebrand
 */
public class FlockingBehavior {

    private final double aoiRadius;
    private final double maxSpeed;
    private final double separationRadius;
    private final double mass;

    /**
     * Create FlockingBehavior with custom parameters.
     *
     * @param aoiRadius        Area of Interest radius
     * @param maxSpeed         Maximum speed
     * @param separationRadius Separation radius
     * @param mass             Entity mass
     */
    public FlockingBehavior(double aoiRadius, double maxSpeed, double separationRadius, double mass) {
        if (aoiRadius <= 0) {
            throw new IllegalArgumentException("AOI radius must be positive, got " + aoiRadius);
        }
        if (maxSpeed <= 0) {
            throw new IllegalArgumentException("Max speed must be positive, got " + maxSpeed);
        }
        if (separationRadius <= 0) {
            throw new IllegalArgumentException("Separation radius must be positive, got " + separationRadius);
        }
        if (mass <= 0) {
            throw new IllegalArgumentException("Mass must be positive, got " + mass);
        }

        this.aoiRadius = aoiRadius;
        this.maxSpeed = maxSpeed;
        this.separationRadius = separationRadius;
        this.mass = mass;
    }

    /**
     * Get Area of Interest radius.
     * <p>
     * Defines the range for detecting neighboring entities.
     *
     * @return AOI radius
     */
    public double getAoiRadius() {
        return aoiRadius;
    }

    /**
     * Get maximum speed.
     * <p>
     * Velocity magnitude is clamped to this value.
     *
     * @return Maximum speed (units/tick)
     */
    public double getMaxSpeed() {
        return maxSpeed;
    }

    /**
     * Get separation radius.
     * <p>
     * Entities within this distance trigger separation steering.
     *
     * @return Separation radius
     */
    public double getSeparationRadius() {
        return separationRadius;
    }

    /**
     * Get entity mass.
     * <p>
     * Used for inertia calculations in steering.
     *
     * @return Mass
     */
    public double getMass() {
        return mass;
    }

    @Override
    public String toString() {
        return "FlockingBehavior{" +
               "aoiRadius=" + aoiRadius +
               ", maxSpeed=" + maxSpeed +
               ", separationRadius=" + separationRadius +
               ", mass=" + mass +
               '}';
    }
}
