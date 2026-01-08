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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prey behavior combining flocking with predator avoidance.
 * <p>
 * Prey entities exhibit:
 * <ul>
 *   <li><b>Flocking</b>: Group cohesion with other prey (safety in numbers)</li>
 *   <li><b>Flee</b>: Strong avoidance response to nearby predators</li>
 *   <li><b>Panic</b>: Increased speed when predators are close</li>
 * </ul>
 * <p>
 * The flee response has higher priority than flocking, causing prey to
 * break formation when threatened. This creates emergent herding behaviors.
 *
 * @author hal.hildebrand
 */
public class PreyBehavior implements EntityBehavior {

    // Default parameters
    private static final float DEFAULT_AOI_RADIUS = 35.0f;
    private static final float DEFAULT_MAX_SPEED = 18.0f;      // Faster than predators
    private static final float DEFAULT_PANIC_SPEED = 25.0f;    // When threatened
    private static final float DEFAULT_MAX_FORCE = 0.6f;

    /**
     * Separation radius for prey flocking (fraction of AOI).
     */
    private static final float SEPARATION_RADIUS_FACTOR = 0.35f;

    /**
     * Predator detection radius (fraction of AOI).
     * Prey flee from predators within this range.
     */
    private static final float PREDATOR_DETECTION_FACTOR = 1.2f;

    /**
     * Panic threshold - distance at which prey enter panic mode.
     */
    private static final float PANIC_THRESHOLD_FACTOR = 0.5f;

    // Behavior weights
    private final float separationWeight;
    private final float alignmentWeight;
    private final float cohesionWeight;
    private final float fleeWeight;

    private final float aoiRadius;
    private final float maxSpeed;
    private final float panicSpeed;
    private final float maxForce;
    private final float separationRadius;
    private final float predatorDetectionRadius;
    private final float panicThreshold;
    private final WorldBounds worldBounds;
    private final Random random;

    // Double-buffered velocity cache for alignment
    private volatile Map<String, Vector3f> previousVelocities = new ConcurrentHashMap<>();
    private volatile Map<String, Vector3f> currentVelocities = new ConcurrentHashMap<>();

    /**
     * Create prey behavior with default parameters.
     */
    public PreyBehavior() {
        this(DEFAULT_AOI_RADIUS, DEFAULT_MAX_SPEED, DEFAULT_PANIC_SPEED, DEFAULT_MAX_FORCE,
             1.5f, 1.0f, 1.0f, 3.0f, WorldBounds.DEFAULT, new Random());
    }

    /**
     * Create prey behavior with custom parameters.
     *
     * @param aoiRadius        Area of interest radius
     * @param maxSpeed         Maximum cruising speed
     * @param panicSpeed       Maximum speed when fleeing
     * @param maxForce         Maximum steering force
     * @param separationWeight Weight for separation from other prey
     * @param alignmentWeight  Weight for alignment with other prey
     * @param cohesionWeight   Weight for cohesion with other prey
     * @param fleeWeight       Weight for fleeing from predators (should be highest)
     * @param worldBounds      World boundary configuration
     * @param random           Random number generator
     */
    public PreyBehavior(float aoiRadius, float maxSpeed, float panicSpeed, float maxForce,
                        float separationWeight, float alignmentWeight, float cohesionWeight,
                        float fleeWeight, WorldBounds worldBounds, Random random) {
        if (aoiRadius <= 0) throw new IllegalArgumentException("AOI radius must be positive");
        if (maxSpeed <= 0) throw new IllegalArgumentException("Max speed must be positive");
        if (panicSpeed < maxSpeed) throw new IllegalArgumentException("Panic speed must be >= max speed");
        if (maxForce <= 0) throw new IllegalArgumentException("Max force must be positive");
        if (worldBounds == null) throw new IllegalArgumentException("World bounds cannot be null");
        if (random == null) throw new IllegalArgumentException("Random cannot be null");

        this.aoiRadius = aoiRadius;
        this.maxSpeed = maxSpeed;
        this.panicSpeed = panicSpeed;
        this.maxForce = maxForce;
        this.separationWeight = separationWeight;
        this.alignmentWeight = alignmentWeight;
        this.cohesionWeight = cohesionWeight;
        this.fleeWeight = fleeWeight;
        this.separationRadius = aoiRadius * SEPARATION_RADIUS_FACTOR;
        this.predatorDetectionRadius = aoiRadius * PREDATOR_DETECTION_FACTOR;
        this.panicThreshold = aoiRadius * PANIC_THRESHOLD_FACTOR;
        this.worldBounds = worldBounds;
        this.random = random;
    }

    /**
     * Swap velocity buffers at tick start.
     */
    public void swapVelocityBuffers() {
        previousVelocities = currentVelocities;
        currentVelocities = new ConcurrentHashMap<>();
    }

    @Override
    public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                    EnhancedBubble bubble, float deltaTime) {
        // Store current velocity for alignment
        currentVelocities.put(entityId, new Vector3f(velocity));

        // Query all neighbors within extended predator detection range
        var neighbors = bubble.queryRange(position, predatorDetectionRadius);

        // Separate prey and predators
        var prey = neighbors.stream()
            .filter(n -> !n.id().equals(entityId))
            .filter(n -> n.content() instanceof EntityType && n.content() == EntityType.PREY)
            .toList();

        var predators = neighbors.stream()
            .filter(n -> n.content() instanceof EntityType && n.content() == EntityType.PREDATOR)
            .toList();

        // Compute flee force from predators (highest priority)
        var flee = computeFlee(position, predators);
        var isPanicking = !predators.isEmpty() && flee.length() > 0.01f;

        // Compute flocking forces with other prey
        var separation = computeSeparation(entityId, position, prey);
        var alignment = computeAlignment(entityId, prey);
        var cohesion = computeCohesion(entityId, position, prey);

        // Weight and combine forces
        flee.scale(fleeWeight);
        separation.scale(separationWeight);
        alignment.scale(alignmentWeight);
        cohesion.scale(cohesionWeight);

        // Apply forces
        var newVelocity = new Vector3f(velocity);
        newVelocity.add(flee);
        newVelocity.add(separation);
        newVelocity.add(alignment);
        newVelocity.add(cohesion);

        // Boundary avoidance
        applyBoundaryAvoidance(position, newVelocity);

        // Speed limits (panic mode if predators nearby)
        float speedLimit = isPanicking ? panicSpeed : maxSpeed;
        float speed = newVelocity.length();
        if (speed > speedLimit) {
            newVelocity.scale(speedLimit / speed);
        }

        // Minimum speed to keep moving
        float minSpeed = maxSpeed * 0.3f;
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
     * Flee from nearby predators with urgency based on distance.
     */
    private Vector3f computeFlee(Point3f position, java.util.List<EnhancedBubble.EntityRecord> predators) {
        var fleeForce = new Vector3f();

        for (var predator : predators) {
            float dx = position.x - predator.position().x;
            float dy = position.y - predator.position().y;
            float dz = position.z - predator.position().z;
            float distSq = dx * dx + dy * dy + dz * dz;

            if (distSq > 0 && distSq < predatorDetectionRadius * predatorDetectionRadius) {
                float dist = (float) Math.sqrt(distSq);
                var diff = new Vector3f(dx, dy, dz);
                diff.normalize();

                // Urgency inversely proportional to distance
                // Closer predators produce stronger flee response
                float urgency = 1.0f - (dist / predatorDetectionRadius);
                diff.scale(urgency * urgency); // Square for more dramatic falloff

                fleeForce.add(diff);
            }
        }

        if (fleeForce.length() > 0) {
            fleeForce.normalize();
            fleeForce.scale(maxSpeed);
            limitForce(fleeForce);
        }

        return fleeForce;
    }

    /**
     * Separation from other prey to avoid crowding.
     */
    private Vector3f computeSeparation(String entityId, Point3f position,
                                       java.util.List<EnhancedBubble.EntityRecord> prey) {
        var steer = new Vector3f();
        int count = 0;

        for (var other : prey) {
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
     * Alignment with other prey.
     */
    private Vector3f computeAlignment(String entityId, java.util.List<EnhancedBubble.EntityRecord> prey) {
        var avgVelocity = new Vector3f();
        int count = 0;

        for (var other : prey) {
            var neighborVel = previousVelocities.get(other.id());
            if (neighborVel != null) {
                avgVelocity.add(neighborVel);
                count++;
            }
        }

        if (count > 0) {
            avgVelocity.scale(1.0f / count);
            if (avgVelocity.length() > 0) {
                avgVelocity.normalize();
                avgVelocity.scale(maxSpeed);
                limitForce(avgVelocity);
            }
        }

        return avgVelocity;
    }

    /**
     * Cohesion towards center of prey group.
     */
    private Vector3f computeCohesion(String entityId, Point3f position,
                                     java.util.List<EnhancedBubble.EntityRecord> prey) {
        var centerOfMass = new Vector3f();
        int count = 0;

        for (var other : prey) {
            centerOfMass.x += other.position().x;
            centerOfMass.y += other.position().y;
            centerOfMass.z += other.position().z;
            count++;
        }

        if (count > 0) {
            centerOfMass.scale(1.0f / count);

            var desired = new Vector3f(
                centerOfMass.x - position.x,
                centerOfMass.y - position.y,
                centerOfMass.z - position.z
            );

            if (desired.length() > 0) {
                desired.normalize();
                desired.scale(maxSpeed);
                limitForce(desired);
            }

            return desired;
        }

        return new Vector3f();
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
     * Get panic speed (used when fleeing).
     */
    public float getPanicSpeed() {
        return panicSpeed;
    }

    /**
     * Clear velocity caches.
     */
    public void clearCache() {
        previousVelocities.clear();
        currentVelocities.clear();
    }
}
