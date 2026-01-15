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
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
 * <p>
 * Thread Safety: Uses double-buffering for velocity cache to avoid race conditions
 * when multiple entities compute velocities concurrently.
 *
 * @author hal.hildebrand
 */
public class FlockingBehavior implements EntityBehavior {

    // Default parameters
    private static final float DEFAULT_AOI_RADIUS = 30.0f;
    private static final float DEFAULT_MAX_SPEED = 15.0f;
    private static final float DEFAULT_MAX_FORCE = 0.5f;

    /**
     * Separation radius as fraction of AOI.
     * Entities within this distance trigger separation force.
     */
    private static final float DEFAULT_SEPARATION_RADIUS_FACTOR = 0.4f;

    /**
     * Boundary avoidance margin as fraction of AOI.
     * Entities within this distance from world edge start turning.
     */
    private static final float BOUNDARY_MARGIN_FACTOR = 0.67f;

    // Force weights
    private final float separationWeight;
    private final float alignmentWeight;
    private final float cohesionWeight;

    private final float aoiRadius;
    private final float maxSpeed;
    private final float maxForce;
    private final float separationRadius;
    private final WorldBounds worldBounds;
    private final float boundaryMargin;
    private final Random random;

    // Double-buffered velocity cache for thread-safe alignment calculations
    // previousVelocities: read from during computation (last tick's values)
    // currentVelocities: write to during computation (this tick's values)
    private volatile Map<String, Vector3f> previousVelocities = new ConcurrentHashMap<>();
    private volatile Map<String, Vector3f> currentVelocities = new ConcurrentHashMap<>();

    /**
     * Create flocking behavior with default parameters.
     */
    public FlockingBehavior() {
        this(DEFAULT_AOI_RADIUS, DEFAULT_MAX_SPEED, DEFAULT_MAX_FORCE,
             1.5f, 1.0f, 1.0f, WorldBounds.DEFAULT, new Random());
    }

    /**
     * Create flocking behavior with custom weights.
     *
     * @param separationWeight Weight for separation force (typical: 1.0-2.0)
     * @param alignmentWeight  Weight for alignment force (typical: 0.5-1.5)
     * @param cohesionWeight   Weight for cohesion force (typical: 0.5-1.5)
     */
    public FlockingBehavior(float separationWeight, float alignmentWeight, float cohesionWeight) {
        this(DEFAULT_AOI_RADIUS, DEFAULT_MAX_SPEED, DEFAULT_MAX_FORCE,
             separationWeight, alignmentWeight, cohesionWeight, WorldBounds.DEFAULT, new Random());
    }

    /**
     * Create flocking behavior with full customization.
     *
     * @param aoiRadius        Area of interest radius for neighbor detection (must be > 0)
     * @param maxSpeed         Maximum movement speed (must be > 0)
     * @param maxForce         Maximum steering force per tick (must be > 0)
     * @param separationWeight Weight for separation force
     * @param alignmentWeight  Weight for alignment force
     * @param cohesionWeight   Weight for cohesion force
     * @param worldBounds      World boundary configuration
     * @param random           Random number generator
     * @throws IllegalArgumentException if parameters are invalid
     */
    public FlockingBehavior(float aoiRadius, float maxSpeed, float maxForce,
                            float separationWeight, float alignmentWeight, float cohesionWeight,
                            WorldBounds worldBounds, Random random) {
        // Parameter validation
        if (aoiRadius <= 0) {
            throw new IllegalArgumentException("AOI radius must be positive, got: " + aoiRadius);
        }
        if (maxSpeed <= 0) {
            throw new IllegalArgumentException("Max speed must be positive, got: " + maxSpeed);
        }
        if (maxForce <= 0) {
            throw new IllegalArgumentException("Max force must be positive, got: " + maxForce);
        }
        if (worldBounds == null) {
            throw new IllegalArgumentException("World bounds cannot be null");
        }
        if (random == null) {
            throw new IllegalArgumentException("Random cannot be null");
        }

        this.aoiRadius = aoiRadius;
        this.maxSpeed = maxSpeed;
        this.maxForce = maxForce;
        this.separationWeight = separationWeight;
        this.alignmentWeight = alignmentWeight;
        this.cohesionWeight = cohesionWeight;
        this.separationRadius = aoiRadius * DEFAULT_SEPARATION_RADIUS_FACTOR;
        this.worldBounds = worldBounds;
        this.boundaryMargin = aoiRadius * BOUNDARY_MARGIN_FACTOR;
        this.random = random;
    }

    /**
     * Swap velocity buffers at the start of each tick.
     * <p>
     * Must be called by SimulationBubble before processing any entities.
     * This ensures all entities read from the previous tick's velocities
     * while writing to the current tick's buffer.
     */
    public void swapVelocityBuffers() {
        previousVelocities = currentVelocities;
        currentVelocities = new ConcurrentHashMap<>();
    }

    /**
     * Clean up velocity entries for entities that no longer exist.
     * <p>
     * Should be called periodically to prevent memory leaks.
     *
     * @param activeEntityIds Set of currently active entity IDs
     */
    public void cleanupRemovedEntities(Set<String> activeEntityIds) {
        previousVelocities.keySet().retainAll(activeEntityIds);
        currentVelocities.keySet().retainAll(activeEntityIds);
    }

    @Override
    public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                    EnhancedBubble bubble, float deltaTime) {
        // Store current velocity for next tick's alignment calculations
        currentVelocities.put(entityId, new Vector3f(velocity));

        // Query neighbors within AOI
        var neighbors = bubble.queryRange(position, aoiRadius);

        // If no neighbors (only self), add wander behavior
        if (neighbors.size() <= 1) {
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
        float minSpeed = maxSpeed * 0.3f;
        if (speed < minSpeed) {
            if (speed > 0.001f) {
                newVelocity.scale(minSpeed / speed);
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
                limitForce(steer);
            }
        }

        return steer;
    }

    /**
     * Alignment: Steer towards average heading of neighbors.
     * <p>
     * Reads from previousVelocities to avoid race conditions.
     */
    private Vector3f computeAlignment(String entityId,
                                      java.util.List<EnhancedBubble.EntityRecord> neighbors) {
        var avgVelocity = new Vector3f();
        int count = 0;

        for (var neighbor : neighbors) {
            if (neighbor.id().equals(entityId)) continue;

            // Read from previous tick's velocities (thread-safe)
            var neighborVel = previousVelocities.get(neighbor.id());
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
                limitForce(avgVelocity);
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
                limitForce(desired);
            }

            return desired;
        }

        return new Vector3f();
    }

    /**
     * Apply boundary avoidance forces near world edges.
     * <p>
     * Uses boundary margin derived from AOI radius.
     */
    private void applyBoundaryAvoidance(Point3f position, Vector3f velocity) {
        float turnForce = maxForce * 2;

        if (position.x < worldBounds.min() + boundaryMargin) velocity.x += turnForce;
        if (position.x > worldBounds.max() - boundaryMargin) velocity.x -= turnForce;
        if (position.y < worldBounds.min() + boundaryMargin) velocity.y += turnForce;
        if (position.y > worldBounds.max() - boundaryMargin) velocity.y -= turnForce;
        if (position.z < worldBounds.min() + boundaryMargin) velocity.z += turnForce;
        if (position.z > worldBounds.max() - boundaryMargin) velocity.z -= turnForce;
    }

    /**
     * Limit a force vector to maxForce magnitude.
     */
    private void limitForce(Vector3f force) {
        if (force.length() > maxForce) {
            force.normalize();
            force.scale(maxForce);
        }
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
        float minSpeed = maxSpeed * 0.5f;
        if (speed < minSpeed && speed > 0.001f) {
            newVelocity.scale(minSpeed / speed);
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
     * Get the world bounds configuration.
     */
    public WorldBounds getWorldBounds() {
        return worldBounds;
    }

    /**
     * Clear velocity caches (call when resetting simulation).
     */
    public void clearCache() {
        previousVelocities.clear();
        currentVelocities.clear();
    }
}
