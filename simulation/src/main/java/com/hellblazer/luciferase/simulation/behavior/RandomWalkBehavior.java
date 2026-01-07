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
import com.hellblazer.luciferase.simulation.config.WorldBounds;

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

    /**
     * Probability of direction change per tick.
     * At 60fps, 0.02 means ~1.2 changes per second on average.
     */
    private static final float DEFAULT_DIRECTION_CHANGE_PROBABILITY = 0.02f;

    /**
     * Margin from world edge where bouncing occurs.
     * Entities within this distance from edge will reverse direction.
     */
    private static final float BOUNCE_MARGIN = 5.0f;

    private final Random random;
    private final float maxSpeed;
    private final float aoiRadius;
    private final float directionChangeProbability;
    private final WorldBounds worldBounds;

    /**
     * Create random walk behavior with default parameters.
     */
    public RandomWalkBehavior() {
        this(new Random(), DEFAULT_MAX_SPEED, DEFAULT_AOI_RADIUS,
             DEFAULT_DIRECTION_CHANGE_PROBABILITY, WorldBounds.DEFAULT);
    }

    /**
     * Create random walk behavior with seed for reproducibility.
     *
     * @param seed Random seed
     */
    public RandomWalkBehavior(long seed) {
        this(new Random(seed), DEFAULT_MAX_SPEED, DEFAULT_AOI_RADIUS,
             DEFAULT_DIRECTION_CHANGE_PROBABILITY, WorldBounds.DEFAULT);
    }

    /**
     * Create random walk behavior with full customization.
     *
     * @param random                     Random number generator
     * @param maxSpeed                   Maximum movement speed (must be > 0)
     * @param aoiRadius                  Area of interest radius (must be > 0)
     * @param directionChangeProbability Probability of direction change per tick (0-1)
     * @param worldBounds                World boundary configuration
     * @throws IllegalArgumentException if parameters are invalid
     */
    public RandomWalkBehavior(Random random, float maxSpeed, float aoiRadius,
                              float directionChangeProbability, WorldBounds worldBounds) {
        // Parameter validation
        if (random == null) {
            throw new IllegalArgumentException("Random cannot be null");
        }
        if (maxSpeed <= 0) {
            throw new IllegalArgumentException("Max speed must be positive, got: " + maxSpeed);
        }
        if (aoiRadius <= 0) {
            throw new IllegalArgumentException("AOI radius must be positive, got: " + aoiRadius);
        }
        if (directionChangeProbability < 0 || directionChangeProbability > 1) {
            throw new IllegalArgumentException(
                "Direction change probability must be 0-1, got: " + directionChangeProbability);
        }
        if (worldBounds == null) {
            throw new IllegalArgumentException("World bounds cannot be null");
        }

        this.random = random;
        this.maxSpeed = maxSpeed;
        this.aoiRadius = aoiRadius;
        this.directionChangeProbability = directionChangeProbability;
        this.worldBounds = worldBounds;
    }

    @Override
    public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                    EnhancedBubble bubble, float deltaTime) {
        var newVelocity = new Vector3f(velocity);

        // Occasionally change direction
        if (random.nextFloat() < directionChangeProbability) {
            newVelocity.set(
                (random.nextFloat() - 0.5f) * 2 * maxSpeed,
                (random.nextFloat() - 0.5f) * 2 * maxSpeed,
                (random.nextFloat() - 0.5f) * 2 * maxSpeed
            );
        }

        // Bounce off world bounds
        if (position.x < worldBounds.min() + BOUNCE_MARGIN) newVelocity.x = Math.abs(newVelocity.x);
        if (position.x > worldBounds.max() - BOUNCE_MARGIN) newVelocity.x = -Math.abs(newVelocity.x);
        if (position.y < worldBounds.min() + BOUNCE_MARGIN) newVelocity.y = Math.abs(newVelocity.y);
        if (position.y > worldBounds.max() - BOUNCE_MARGIN) newVelocity.y = -Math.abs(newVelocity.y);
        if (position.z < worldBounds.min() + BOUNCE_MARGIN) newVelocity.z = Math.abs(newVelocity.z);
        if (position.z > worldBounds.max() - BOUNCE_MARGIN) newVelocity.z = -Math.abs(newVelocity.z);

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

    /**
     * Get the world bounds configuration.
     */
    public WorldBounds getWorldBounds() {
        return worldBounds;
    }
}
