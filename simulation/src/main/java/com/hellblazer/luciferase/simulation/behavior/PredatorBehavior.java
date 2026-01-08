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
import com.hellblazer.luciferase.simulation.entity.EntityType;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Random;

/**
 * Predator behavior for hunting prey entities.
 * <p>
 * Predators exhibit:
 * <ul>
 *   <li><b>Chase</b>: Pursuit of nearest prey using k-NN queries</li>
 *   <li><b>Persistence</b>: Continue pursuit even if prey escapes AOI</li>
 *   <li><b>Wander</b>: Random movement when no prey detected</li>
 * </ul>
 * <p>
 * Predators are slower than prey (maxSpeed), making the chase interesting.
 * Prey can escape if they maintain distance and use their speed advantage.
 *
 * @author hal.hildebrand
 */
public class PredatorBehavior implements EntityBehavior {

    // Default parameters
    private static final float DEFAULT_AOI_RADIUS = 40.0f;
    private static final float DEFAULT_MAX_SPEED = 12.0f;      // Slower than prey
    private static final float DEFAULT_PURSUIT_SPEED = 16.0f;  // Burst speed when chasing
    private static final float DEFAULT_MAX_FORCE = 0.7f;

    /**
     * Chase activation distance (fraction of AOI).
     * Predators enter pursuit mode when prey is within this range.
     */
    private static final float CHASE_RANGE_FACTOR = 0.9f;

    /**
     * Separation distance from other predators (fraction of AOI).
     */
    private static final float SEPARATION_RADIUS_FACTOR = 0.3f;

    private final float aoiRadius;
    private final float maxSpeed;
    private final float pursuitSpeed;
    private final float maxForce;
    private final float chaseRange;
    private final float separationRadius;
    private final WorldBounds worldBounds;
    private final Random random;

    /**
     * Create predator behavior with default parameters.
     */
    public PredatorBehavior() {
        this(DEFAULT_AOI_RADIUS, DEFAULT_MAX_SPEED, DEFAULT_PURSUIT_SPEED,
             DEFAULT_MAX_FORCE, WorldBounds.DEFAULT, new Random());
    }

    /**
     * Create predator behavior with custom parameters.
     *
     * @param aoiRadius     Area of interest radius for prey detection
     * @param maxSpeed      Maximum cruising/patrol speed
     * @param pursuitSpeed  Maximum speed during active chase
     * @param maxForce      Maximum steering force
     * @param worldBounds   World boundary configuration
     * @param random        Random number generator
     */
    public PredatorBehavior(float aoiRadius, float maxSpeed, float pursuitSpeed,
                            float maxForce, WorldBounds worldBounds, Random random) {
        if (aoiRadius <= 0) throw new IllegalArgumentException("AOI radius must be positive");
        if (maxSpeed <= 0) throw new IllegalArgumentException("Max speed must be positive");
        if (pursuitSpeed < maxSpeed) throw new IllegalArgumentException("Pursuit speed must be >= max speed");
        if (maxForce <= 0) throw new IllegalArgumentException("Max force must be positive");
        if (worldBounds == null) throw new IllegalArgumentException("World bounds cannot be null");
        if (random == null) throw new IllegalArgumentException("Random cannot be null");

        this.aoiRadius = aoiRadius;
        this.maxSpeed = maxSpeed;
        this.pursuitSpeed = pursuitSpeed;
        this.maxForce = maxForce;
        this.chaseRange = aoiRadius * CHASE_RANGE_FACTOR;
        this.separationRadius = aoiRadius * SEPARATION_RADIUS_FACTOR;
        this.worldBounds = worldBounds;
        this.random = random;
    }

    @Override
    public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                    EnhancedBubble bubble, float deltaTime) {
        // Find nearest prey using k-NN query (efficient for sparse predators)
        var neighbors = bubble.kNearestNeighbors(position, 10);

        // Filter for prey only
        var nearestPrey = neighbors.stream()
            .filter(n -> n.content() instanceof EntityType && n.content() == EntityType.PREY)
            .findFirst();

        Vector3f newVelocity;

        if (nearestPrey.isPresent()) {
            var prey = nearestPrey.get();
            float dx = prey.position().x - position.x;
            float dy = prey.position().y - position.y;
            float dz = prey.position().z - position.z;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist < chaseRange) {
                // Chase mode: pursuit behavior
                newVelocity = computePursuit(position, prey.position(), velocity);
            } else {
                // Patrol mode: wander
                newVelocity = computeWander(velocity);
            }
        } else {
            // No prey detected: wander
            newVelocity = computeWander(velocity);
        }

        // Separation from other predators (avoid crowding)
        var predators = neighbors.stream()
            .filter(n -> !n.id().equals(entityId))
            .filter(n -> n.content() instanceof EntityType && n.content() == EntityType.PREDATOR)
            .toList();

        if (!predators.isEmpty()) {
            var separation = computeSeparation(position, predators);
            separation.scale(0.5f); // Lower weight than chase
            newVelocity.add(separation);
        }

        // Boundary avoidance
        applyBoundaryAvoidance(position, newVelocity);

        // Speed limits
        boolean isChasing = nearestPrey.isPresent();
        float speedLimit = isChasing ? pursuitSpeed : maxSpeed;
        float speed = newVelocity.length();
        if (speed > speedLimit) {
            newVelocity.scale(speedLimit / speed);
        }

        // Minimum speed
        float minSpeed = maxSpeed * 0.4f;
        if (speed < minSpeed) {
            if (speed > 0.001f) {
                newVelocity.scale(minSpeed / speed);
            } else {
                // Random direction if stopped
                newVelocity.set(
                    (random.nextFloat() - 0.5f) * maxSpeed,
                    (random.nextFloat() - 0.5f) * maxSpeed,
                    (random.nextFloat() - 0.5f) * maxSpeed
                );
            }
        }

        return newVelocity;
    }

    /**
     * Pursuit steering towards prey position.
     * <p>
     * Uses simple pursuit: steer directly towards current prey position.
     * Advanced version could predict prey movement.
     */
    private Vector3f computePursuit(Point3f position, Point3f preyPosition, Vector3f velocity) {
        // Desired velocity: towards prey at max pursuit speed
        var desired = new Vector3f(
            preyPosition.x - position.x,
            preyPosition.y - position.y,
            preyPosition.z - position.z
        );

        if (desired.length() > 0) {
            desired.normalize();
            desired.scale(pursuitSpeed);

            // Steering = desired - current
            var steer = new Vector3f(desired);
            steer.sub(velocity);
            limitForce(steer);

            var newVelocity = new Vector3f(velocity);
            newVelocity.add(steer);
            return newVelocity;
        }

        return new Vector3f(velocity);
    }

    /**
     * Wander behavior when no prey is nearby.
     */
    private Vector3f computeWander(Vector3f velocity) {
        var newVelocity = new Vector3f(velocity);

        // Add random perturbation
        newVelocity.x += (random.nextFloat() - 0.5f) * maxForce * 1.5f;
        newVelocity.y += (random.nextFloat() - 0.5f) * maxForce * 1.5f;
        newVelocity.z += (random.nextFloat() - 0.5f) * maxForce * 1.5f;

        // Clamp to patrol speed
        float speed = newVelocity.length();
        if (speed > maxSpeed) {
            newVelocity.scale(maxSpeed / speed);
        }

        return newVelocity;
    }

    /**
     * Separation from other predators.
     */
    private Vector3f computeSeparation(Point3f position, java.util.List<EnhancedBubble.EntityRecord> predators) {
        var steer = new Vector3f();
        int count = 0;

        for (var other : predators) {
            float dx = position.x - other.position().x;
            float dy = position.y - other.position().y;
            float dz = position.z - other.position().z;
            float distSq = dx * dx + dy * dy + dz * dz;

            if (distSq > 0 && distSq < separationRadius * separationRadius) {
                float dist = (float) Math.sqrt(distSq);
                var diff = new Vector3f(dx, dy, dz);
                diff.normalize();
                diff.scale(1.0f / dist);
                steer.add(diff);
                count++;
            }
        }

        if (count > 0) {
            steer.scale(1.0f / count);
            if (steer.length() > 0) {
                steer.normalize();
                steer.scale(maxSpeed);
                limitForce(steer);
            }
        }

        return steer;
    }

    /**
     * Boundary avoidance near world edges.
     */
    private void applyBoundaryAvoidance(Point3f position, Vector3f velocity) {
        float boundaryMargin = aoiRadius * 0.67f;
        float turnForce = maxForce * 2;

        if (position.x < worldBounds.min() + boundaryMargin) velocity.x += turnForce;
        if (position.x > worldBounds.max() - boundaryMargin) velocity.x -= turnForce;
        if (position.y < worldBounds.min() + boundaryMargin) velocity.y += turnForce;
        if (position.y > worldBounds.max() - boundaryMargin) velocity.y -= turnForce;
        if (position.z < worldBounds.min() + boundaryMargin) velocity.z += turnForce;
        if (position.z > worldBounds.max() - boundaryMargin) velocity.z -= turnForce;
    }

    /**
     * Limit force to max magnitude.
     */
    private void limitForce(Vector3f force) {
        if (force.length() > maxForce) {
            force.normalize();
            force.scale(maxForce);
        }
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
     * Get pursuit speed (used when chasing prey).
     */
    public float getPursuitSpeed() {
        return pursuitSpeed;
    }
}
