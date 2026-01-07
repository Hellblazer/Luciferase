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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classic boids flocking behavior with separation, alignment, and cohesion.
 * <p>
 * Each entity perceives neighbors within its AOI (Area of Interest) and
 * applies three steering forces:
 * <ul>
 *   <li><b>Separation</b>: Steer away from nearby neighbors to avoid crowding</li>
 *   <li><b>Alignment</b>: Steer towards average heading of neighbors</li>
 *   <li><b>Cohesion</b>: Steer towards center of mass of neighbors</li>
 * </ul>
 * <p>
 * Entity AOI is distinct from bubble region - it's the perception radius
 * for behavior calculations (typically 10-50 units).
 *
 * @author hal.hildebrand
 */
public class FlockingBehavior implements EntityBehavior {

    // Default parameters
    private static final float DEFAULT_AOI_RADIUS = 30.0f;
    private static final float DEFAULT_MAX_SPEED = 15.0f;
    private static final float DEFAULT_MAX_FORCE = 0.5f;

    // Separation uses a smaller radius for "too close" detection
    private static final float SEPARATION_RADIUS_FACTOR = 0.4f;

    // Force weights
    private final float separationWeight;
    private final float alignmentWeight;
    private final float cohesionWeight;

    private final float aoiRadius;
    private final float maxSpeed;
    private final float maxForce;
    private final float separationRadius;
    private final float worldMin;
    private final float worldMax;
    private final Random random;

    // Track neighbor velocities for alignment (shared across all entities)
    private final Map<String, Vector3f> velocityCache = new ConcurrentHashMap<>();

    /**
     * Create flocking behavior with default parameters.
     */
    public FlockingBehavior() {
        this(DEFAULT_AOI_RADIUS, DEFAULT_MAX_SPEED, DEFAULT_MAX_FORCE,
             1.5f, 1.0f, 1.0f, 0f, 200f, new Random());
    }

    /**
     * Create flocking behavior with custom weights.
     *
     * @param separationWeight Weight for separation force
     * @param alignmentWeight  Weight for alignment force
     * @param cohesionWeight   Weight for cohesion force
     */
    public FlockingBehavior(float separationWeight, float alignmentWeight, float cohesionWeight) {
        this(DEFAULT_AOI_RADIUS, DEFAULT_MAX_SPEED, DEFAULT_MAX_FORCE,
             separationWeight, alignmentWeight, cohesionWeight, 0f, 200f, new Random());
    }

    /**
     * Create flocking behavior with full customization.
     *
     * @param aoiRadius        Area of interest radius for neighbor detection
     * @param maxSpeed         Maximum movement speed
     * @param maxForce         Maximum steering force per tick
     * @param separationWeight Weight for separation force
     * @param alignmentWeight  Weight for alignment force
     * @param cohesionWeight   Weight for cohesion force
     * @param worldMin         Minimum world coordinate
     * @param worldMax         Maximum world coordinate
     * @param random           Random number generator
     */
    public FlockingBehavior(float aoiRadius, float maxSpeed, float maxForce,
                            float separationWeight, float alignmentWeight, float cohesionWeight,
                            float worldMin, float worldMax, Random random) {
        this.aoiRadius = aoiRadius;
        this.maxSpeed = maxSpeed;
        this.maxForce = maxForce;
        this.separationWeight = separationWeight;
        this.alignmentWeight = alignmentWeight;
        this.cohesionWeight = cohesionWeight;
        this.separationRadius = aoiRadius * SEPARATION_RADIUS_FACTOR;
        this.worldMin = worldMin;
        this.worldMax = worldMax;
        this.random = random;
    }

    @Override
    public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                    EnhancedBubble bubble, float deltaTime) {
        // Cache current velocity for other entities to use in alignment
        velocityCache.put(entityId, new Vector3f(velocity));

        // Query neighbors within AOI
        var neighbors = bubble.queryRange(position, aoiRadius);

        // If no neighbors, add some wander behavior
        if (neighbors.size() <= 1) {  // Only self
            return applyWander(velocity, deltaTime);
        }

        // Compute steering forces
        var separation = computeSeparation(entityId, position, neighbors);
        var alignment = computeAlignment(entityId, neighbors);
        var cohesion = computeCohesion(entityId, position, neighbors);

        // Weight and combine forces
        separation.scale(separationWeight);
        alignment.scale(alignmentWeight);
        cohesion.scale(cohesionWeight);

        // Apply forces to velocity
        var newVelocity = new Vector3f(velocity);
        newVelocity.add(separation);
        newVelocity.add(alignment);
        newVelocity.add(cohesion);

        // Apply world boundary avoidance
        applyBoundaryAvoidance(position, newVelocity);

        // Clamp to max speed
        float speed = newVelocity.length();
        if (speed > maxSpeed) {
            newVelocity.scale(maxSpeed / speed);
        }

        // Ensure minimum speed to keep entities moving
        if (speed < maxSpeed * 0.3f) {
            if (speed > 0.001f) {
                newVelocity.scale((maxSpeed * 0.3f) / speed);
            } else {
                // Random direction if nearly stopped
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
     * Separation: Steer away from neighbors that are too close.
     */
    private Vector3f computeSeparation(String entityId, Point3f position,
                                       java.util.List<EnhancedBubble.EntityRecord> neighbors) {
        var steer = new Vector3f();
        int count = 0;

        for (var neighbor : neighbors) {
            if (neighbor.id().equals(entityId)) continue;

            float dx = position.x - neighbor.position().x;
            float dy = position.y - neighbor.position().y;
            float dz = position.z - neighbor.position().z;
            float distSq = dx * dx + dy * dy + dz * dz;

            if (distSq > 0 && distSq < separationRadius * separationRadius) {
                // Weight by inverse distance (closer = stronger repulsion)
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
            // Implement Reynolds: steer = desired - velocity
            if (steer.length() > 0) {
                steer.normalize();
                steer.scale(maxSpeed);
                // Limit force
                if (steer.length() > maxForce) {
                    steer.normalize();
                    steer.scale(maxForce);
                }
            }
        }

        return steer;
    }

    /**
     * Alignment: Steer towards average heading of neighbors.
     */
    private Vector3f computeAlignment(String entityId,
                                      java.util.List<EnhancedBubble.EntityRecord> neighbors) {
        var avgVelocity = new Vector3f();
        int count = 0;

        for (var neighbor : neighbors) {
            if (neighbor.id().equals(entityId)) continue;

            var neighborVel = velocityCache.get(neighbor.id());
            if (neighborVel != null) {
                avgVelocity.add(neighborVel);
                count++;
            }
        }

        if (count > 0) {
            avgVelocity.scale(1.0f / count);
            // Implement Reynolds: steer = desired - velocity
            if (avgVelocity.length() > 0) {
                avgVelocity.normalize();
                avgVelocity.scale(maxSpeed);
                // We return the steering force, caller will subtract current velocity
                if (avgVelocity.length() > maxForce) {
                    avgVelocity.normalize();
                    avgVelocity.scale(maxForce);
                }
            }
        }

        return avgVelocity;
    }

    /**
     * Cohesion: Steer towards center of mass of neighbors.
     */
    private Vector3f computeCohesion(String entityId, Point3f position,
                                     java.util.List<EnhancedBubble.EntityRecord> neighbors) {
        var centerOfMass = new Vector3f();
        int count = 0;

        for (var neighbor : neighbors) {
            if (neighbor.id().equals(entityId)) continue;

            centerOfMass.x += neighbor.position().x;
            centerOfMass.y += neighbor.position().y;
            centerOfMass.z += neighbor.position().z;
            count++;
        }

        if (count > 0) {
            centerOfMass.scale(1.0f / count);

            // Steer towards center of mass
            var desired = new Vector3f(
                centerOfMass.x - position.x,
                centerOfMass.y - position.y,
                centerOfMass.z - position.z
            );

            if (desired.length() > 0) {
                desired.normalize();
                desired.scale(maxSpeed);
                // Limit force
                if (desired.length() > maxForce) {
                    desired.normalize();
                    desired.scale(maxForce);
                }
            }

            return desired;
        }

        return new Vector3f();
    }

    /**
     * Apply boundary avoidance forces near world edges.
     */
    private void applyBoundaryAvoidance(Point3f position, Vector3f velocity) {
        float margin = 20.0f;
        float turnForce = maxForce * 2;

        if (position.x < worldMin + margin) velocity.x += turnForce;
        if (position.x > worldMax - margin) velocity.x -= turnForce;
        if (position.y < worldMin + margin) velocity.y += turnForce;
        if (position.y > worldMax - margin) velocity.y -= turnForce;
        if (position.z < worldMin + margin) velocity.z += turnForce;
        if (position.z > worldMax - margin) velocity.z -= turnForce;
    }

    /**
     * Add gentle wandering when no neighbors are nearby.
     */
    private Vector3f applyWander(Vector3f velocity, float deltaTime) {
        var newVelocity = new Vector3f(velocity);

        // Add small random perturbation
        newVelocity.x += (random.nextFloat() - 0.5f) * maxForce;
        newVelocity.y += (random.nextFloat() - 0.5f) * maxForce;
        newVelocity.z += (random.nextFloat() - 0.5f) * maxForce;

        // Clamp speed
        float speed = newVelocity.length();
        if (speed > maxSpeed) {
            newVelocity.scale(maxSpeed / speed);
        }
        if (speed < maxSpeed * 0.5f && speed > 0.001f) {
            newVelocity.scale((maxSpeed * 0.5f) / speed);
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
     * Get the separation radius (smaller than AOI).
     */
    public float getSeparationRadius() {
        return separationRadius;
    }

    /**
     * Clear the velocity cache (call when resetting simulation).
     */
    public void clearCache() {
        velocityCache.clear();
    }
}
