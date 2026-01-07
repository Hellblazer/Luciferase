/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.behavior;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Defines entity movement behavior within a bubble.
 * <p>
 * Behaviors compute velocity updates for entities based on their
 * current state and environment. The simulation loop applies these
 * velocities to update entity positions.
 * <p>
 * Entity AOI (Area of Interest) is distinct from bubble region:
 * - Entity AOI: Perception radius for behavior (10-100m typical)
 * - Bubble Region: Spatial ownership boundary (500-2000m typical)
 *
 * @author hal.hildebrand
 */
public interface EntityBehavior {

    /**
     * Compute the velocity for an entity based on its current state.
     * <p>
     * The behavior can query the bubble for nearby entities within
     * its AOI to implement flocking, avoidance, or other behaviors.
     *
     * @param entityId  The entity to compute velocity for
     * @param position  Current entity position
     * @param velocity  Current entity velocity (may be modified or replaced)
     * @param bubble    The bubble containing this entity (for neighbor queries)
     * @param deltaTime Time step in seconds
     * @return New velocity vector
     */
    Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                             EnhancedBubble bubble, float deltaTime);

    /**
     * Get the Area of Interest radius for this behavior.
     * <p>
     * Entities within this radius are considered for behavior calculations
     * (e.g., flocking neighbors, obstacles to avoid).
     *
     * @return AOI radius in world units
     */
    float getAoiRadius();

    /**
     * Get the maximum speed this behavior allows.
     *
     * @return Maximum speed in units per second
     */
    float getMaxSpeed();
}
