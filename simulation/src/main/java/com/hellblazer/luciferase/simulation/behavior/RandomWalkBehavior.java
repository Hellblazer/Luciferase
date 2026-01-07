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
import java.util.Random;

/**
 * Simple random walk behavior for entity movement.
 * <p>
 * Entities wander randomly with occasional direction changes.
 * Respects world bounds by reversing direction at edges.
 *
 * @author hal.hildebrand
 */
public class RandomWalkBehavior implements EntityBehavior {

    private static final float DEFAULT_MAX_SPEED = 10.0f;  // units per second
    private static final float DEFAULT_AOI_RADIUS = 20.0f;
    private static final float DIRECTION_CHANGE_PROBABILITY = 0.02f;  // 2% per tick

    private final Random random;
    private final float maxSpeed;
    private final float aoiRadius;
    private final float worldMin;
    private final float worldMax;

    /**
     * Create random walk behavior with default parameters.
     */
    public RandomWalkBehavior() {
        this(new Random(), DEFAULT_MAX_SPEED, DEFAULT_AOI_RADIUS, 0f, 200f);
    }

    /**
     * Create random walk behavior with seed for reproducibility.
     *
     * @param seed Random seed
     */
    public RandomWalkBehavior(long seed) {
        this(new Random(seed), DEFAULT_MAX_SPEED, DEFAULT_AOI_RADIUS, 0f, 200f);
    }

    /**
     * Create random walk behavior with full customization.
     *
     * @param random    Random number generator
     * @param maxSpeed  Maximum movement speed
     * @param aoiRadius Area of interest radius
     * @param worldMin  Minimum world coordinate (same for x, y, z)
     * @param worldMax  Maximum world coordinate (same for x, y, z)
     */
    public RandomWalkBehavior(Random random, float maxSpeed, float aoiRadius,
                              float worldMin, float worldMax) {
        this.random = random;
        this.maxSpeed = maxSpeed;
        this.aoiRadius = aoiRadius;
        this.worldMin = worldMin;
        this.worldMax = worldMax;
    }

    @Override
    public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                    EnhancedBubble bubble, float deltaTime) {
        var newVelocity = new Vector3f(velocity);

        // Occasionally change direction
        if (random.nextFloat() < DIRECTION_CHANGE_PROBABILITY) {
            newVelocity.set(
                (random.nextFloat() - 0.5f) * 2 * maxSpeed,
                (random.nextFloat() - 0.5f) * 2 * maxSpeed,
                (random.nextFloat() - 0.5f) * 2 * maxSpeed
            );
        }

        // Bounce off world bounds
        float margin = 5.0f;
        if (position.x < worldMin + margin) newVelocity.x = Math.abs(newVelocity.x);
        if (position.x > worldMax - margin) newVelocity.x = -Math.abs(newVelocity.x);
        if (position.y < worldMin + margin) newVelocity.y = Math.abs(newVelocity.y);
        if (position.y > worldMax - margin) newVelocity.y = -Math.abs(newVelocity.y);
        if (position.z < worldMin + margin) newVelocity.z = Math.abs(newVelocity.z);
        if (position.z > worldMax - margin) newVelocity.z = -Math.abs(newVelocity.z);

        // Clamp to max speed
        float speed = newVelocity.length();
        if (speed > maxSpeed) {
            newVelocity.scale(maxSpeed / speed);
        }

        return newVelocity;
    }

    @Override
    public float getAoiRadius() {
        return aoiRadius;
    }

    @Override
    public float getMaxSpeed() {
        return maxSpeed;
    }
}
