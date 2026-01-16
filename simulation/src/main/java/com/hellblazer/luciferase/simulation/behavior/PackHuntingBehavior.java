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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Pack hunting behavior for coordinated predator attacks.
 * <p>
 * Predators exhibit sophisticated pack dynamics:
 * <ul>
 *   <li><b>Pack Formation</b>: Detect and coordinate with nearby predators</li>
 *   <li><b>Role Assignment</b>: Leader (closest to prey) and flankers (intercept positions)</li>
 *   <li><b>Coordinated Attack</b>: Surround prey from multiple angles</li>
 *   <li><b>Pack Cohesion</b>: Maintain formation when not actively hunting</li>
 *   <li><b>Solo Fallback</b>: Independent pursuit when no pack available</li>
 * </ul>
 * <p>
 * Pack Mechanics:
 * <ul>
 *   <li>Pack forms when 2+ predators within pack radius (~40% of AOI)</li>
 *   <li>Leader = predator closest to target prey</li>
 *   <li>Flankers compute intercept positions 90° from leader's approach vector</li>
 *   <li>Pack coordination increases effective hunting range and success rate</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class PackHuntingBehavior implements EntityBehavior {

    // Default parameters
    private static final float DEFAULT_AOI_RADIUS = 45.0f;
    private static final float DEFAULT_MAX_SPEED = 13.0f;      // Cruising speed
    private static final float DEFAULT_PURSUIT_SPEED = 18.0f;  // Sprint during attack
    private static final float DEFAULT_MAX_FORCE = 0.8f;

    /**
     * Pack formation radius (fraction of AOI).
     * Predators within this distance coordinate as a pack.
     */
    private static final float PACK_RADIUS_FACTOR = 0.4f;

    /**
     * Chase activation distance (fraction of AOI).
     * Pack enters pursuit mode when prey is within this range.
     */
    private static final float CHASE_RANGE_FACTOR = 0.95f;

    /**
     * Flanking angle for pack members (degrees).
     * Flankers position themselves perpendicular to leader's approach.
     */
    private static final float FLANKING_ANGLE_DEGREES = 90.0f;

    /**
     * Flanking distance from prey (fraction of chase range).
     * Determines how far flankers spread from the direct pursuit line.
     */
    private static final float FLANKING_DISTANCE_FACTOR = 0.7f;

    /**
     * Separation distance from pack members (fraction of AOI).
     */
    private static final float SEPARATION_RADIUS_FACTOR = 0.25f;

    private final float aoiRadius;
    private final float maxSpeed;
    private final float pursuitSpeed;
    private final float maxForce;
    private final float packRadius;
    private final float chaseRange;
    private final float separationRadius;
    private final float flankingDistance;
    private final WorldBounds worldBounds;
    private final Random random;

    /**
     * Create pack hunting behavior with default parameters.
     */
    public PackHuntingBehavior() {
        this(DEFAULT_AOI_RADIUS, DEFAULT_MAX_SPEED, DEFAULT_PURSUIT_SPEED,
             DEFAULT_MAX_FORCE, WorldBounds.DEFAULT, new Random());
    }

    /**
     * Create pack hunting behavior with custom parameters.
     *
     * @param aoiRadius     Area of interest radius for prey/pack detection
     * @param maxSpeed      Maximum cruising/patrol speed
     * @param pursuitSpeed  Maximum speed during active chase
     * @param maxForce      Maximum steering force
     * @param worldBounds   World boundary configuration
     * @param random        Random number generator
     */
    public PackHuntingBehavior(float aoiRadius, float maxSpeed, float pursuitSpeed,
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
        this.packRadius = aoiRadius * PACK_RADIUS_FACTOR;
        this.chaseRange = aoiRadius * CHASE_RANGE_FACTOR;
        this.separationRadius = aoiRadius * SEPARATION_RADIUS_FACTOR;
        this.flankingDistance = chaseRange * FLANKING_DISTANCE_FACTOR;
        this.worldBounds = worldBounds;
        this.random = random;
    }

    @Override
    public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                    EnhancedBubble bubble, float deltaTime) {
        // Find nearby predators and prey using k-NN query
        var neighbors = bubble.kNearestNeighbors(position, 20);

        // Separate predators and prey
        var packMembers = neighbors.stream()
            .filter(n -> !n.id().equals(entityId))
            .filter(n -> n.content() instanceof EntityType && n.content() == EntityType.PREDATOR)
            .filter(n -> distance(position, n.position()) < packRadius)
            .toList();

        var nearbyPrey = neighbors.stream()
            .filter(n -> n.content() instanceof EntityType && n.content() == EntityType.PREY)
            .toList();

        // Determine pack role and target
        PackRole role = determinePackRole(entityId, position, packMembers, nearbyPrey);

        Vector3f newVelocity;

        if (role.targetPrey() != null && distance(position, role.targetPrey().position()) < chaseRange) {
            // Active hunting mode
            switch (role.role()) {
                case LEADER -> newVelocity = computeLeaderPursuit(position, role.targetPrey().position(), velocity);
                case FLANKER_LEFT, FLANKER_RIGHT -> newVelocity = computeFlankerIntercept(
                    position, role.targetPrey().position(), role.leaderPosition(), role.role(), velocity);
                case SOLO -> newVelocity = computeSoloPursuit(position, role.targetPrey().position(), velocity);
                default -> newVelocity = computeWander(velocity);
            }
        } else {
            // Patrol mode: maintain pack cohesion or wander
            if (!packMembers.isEmpty()) {
                newVelocity = computePackCohesion(position, packMembers, velocity);
            } else {
                newVelocity = computeWander(velocity);
            }
        }

        // Separation from pack members (avoid crowding)
        if (!packMembers.isEmpty()) {
            var separation = computeSeparation(position, packMembers);
            separation.scale(0.5f); // Lower weight than hunt/cohesion
            newVelocity.add(separation);
        }

        // Boundary avoidance
        applyBoundaryAvoidance(position, newVelocity);

        // Speed limits
        boolean isHunting = role.targetPrey() != null;
        float speedLimit = isHunting ? pursuitSpeed : maxSpeed;
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
     * Determine pack role based on position relative to prey and other predators.
     *
     * @return PackRole with assigned role and target information
     */
    private PackRole determinePackRole(String entityId, Point3f position,
                                       List<EnhancedBubble.EntityRecord> packMembers,
                                       List<EnhancedBubble.EntityRecord> nearbyPrey) {
        if (nearbyPrey.isEmpty()) {
            return new PackRole(Role.PATROL, null, null);
        }

        // Find closest prey
        var closestPrey = nearbyPrey.stream()
            .min(Comparator.comparing(p -> distance(position, p.position())))
            .orElse(null);

        if (closestPrey == null) {
            return new PackRole(Role.PATROL, null, null);
        }

        // Solo if no pack
        if (packMembers.isEmpty()) {
            return new PackRole(Role.SOLO, closestPrey, null);
        }

        // Find leader (predator closest to prey)
        var allPredators = new ArrayList<>(packMembers);
        allPredators.add(new EnhancedBubble.EntityRecord(entityId, position, EntityType.PREDATOR, 0L));

        var leader = allPredators.stream()
            .min(Comparator.comparing(p -> distance(p.position(), closestPrey.position())))
            .orElse(null);

        if (leader == null || leader.id().equals(entityId)) {
            return new PackRole(Role.LEADER, closestPrey, null);
        }

        // Flanker role - determine left or right based on position
        var toLeader = new Vector3f(
            leader.position().x - position.x,
            leader.position().y - position.y,
            leader.position().z - position.z
        );

        var toPrey = new Vector3f(
            closestPrey.position().x - leader.position().x,
            closestPrey.position().y - leader.position().y,
            closestPrey.position().z - leader.position().z
        );

        // Cross product to determine left/right
        var cross = new Vector3f();
        cross.cross(toLeader, toPrey);

        Role flankerRole = cross.y > 0 ? Role.FLANKER_LEFT : Role.FLANKER_RIGHT;
        return new PackRole(flankerRole, closestPrey, leader.position());
    }

    /**
     * Leader pursuit: direct chase towards prey.
     */
    private Vector3f computeLeaderPursuit(Point3f position, Point3f preyPosition, Vector3f velocity) {
        var desired = new Vector3f(
            preyPosition.x - position.x,
            preyPosition.y - position.y,
            preyPosition.z - position.z
        );

        if (desired.length() > 0) {
            desired.normalize();
            desired.scale(pursuitSpeed);

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
     * Flanker intercept: position to cut off prey escape.
     */
    private Vector3f computeFlankerIntercept(Point3f position, Point3f preyPosition,
                                             Point3f leaderPosition, Role flankerRole, Vector3f velocity) {
        // Calculate intercept position perpendicular to leader's approach
        var leaderToPrey = new Vector3f(
            preyPosition.x - leaderPosition.x,
            preyPosition.y - leaderPosition.y,
            preyPosition.z - leaderPosition.z
        );

        if (leaderToPrey.length() == 0) {
            return computeSoloPursuit(position, preyPosition, velocity);
        }

        leaderToPrey.normalize();

        // Calculate perpendicular vector (flanking direction)
        float angle = flankerRole == Role.FLANKER_LEFT ?
            (float) Math.toRadians(FLANKING_ANGLE_DEGREES) :
            (float) Math.toRadians(-FLANKING_ANGLE_DEGREES);

        var flankVector = new Vector3f(
            leaderToPrey.x * (float) Math.cos(angle) - leaderToPrey.z * (float) Math.sin(angle),
            leaderToPrey.y,
            leaderToPrey.x * (float) Math.sin(angle) + leaderToPrey.z * (float) Math.cos(angle)
        );

        flankVector.scale(flankingDistance);

        // Target position: prey + flank offset
        var interceptPoint = new Point3f(
            preyPosition.x + flankVector.x,
            preyPosition.y + flankVector.y,
            preyPosition.z + flankVector.z
        );

        // Steer towards intercept point
        var desired = new Vector3f(
            interceptPoint.x - position.x,
            interceptPoint.y - position.y,
            interceptPoint.z - position.z
        );

        if (desired.length() > 0) {
            desired.normalize();
            desired.scale(pursuitSpeed);

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
     * Solo pursuit when no pack available.
     */
    private Vector3f computeSoloPursuit(Point3f position, Point3f preyPosition, Vector3f velocity) {
        return computeLeaderPursuit(position, preyPosition, velocity);
    }

    /**
     * Pack cohesion when not actively hunting.
     */
    private Vector3f computePackCohesion(Point3f position, List<EnhancedBubble.EntityRecord> packMembers,
                                         Vector3f velocity) {
        var centerOfMass = new Vector3f();
        for (var member : packMembers) {
            centerOfMass.x += member.position().x;
            centerOfMass.y += member.position().y;
            centerOfMass.z += member.position().z;
        }
        centerOfMass.scale(1.0f / packMembers.size());

        var desired = new Vector3f(
            centerOfMass.x - position.x,
            centerOfMass.y - position.y,
            centerOfMass.z - position.z
        );

        if (desired.length() > 0) {
            desired.normalize();
            desired.scale(maxSpeed);
            limitForce(desired);

            var newVelocity = new Vector3f(velocity);
            newVelocity.add(desired);
            return newVelocity;
        }

        return new Vector3f(velocity);
    }

    /**
     * Wander behavior when no prey or pack nearby.
     */
    private Vector3f computeWander(Vector3f velocity) {
        var newVelocity = new Vector3f(velocity);

        newVelocity.x += (random.nextFloat() - 0.5f) * maxForce * 1.5f;
        newVelocity.y += (random.nextFloat() - 0.5f) * maxForce * 1.5f;
        newVelocity.z += (random.nextFloat() - 0.5f) * maxForce * 1.5f;

        float speed = newVelocity.length();
        if (speed > maxSpeed) {
            newVelocity.scale(maxSpeed / speed);
        }

        return newVelocity;
    }

    /**
     * Separation from pack members.
     */
    private Vector3f computeSeparation(Point3f position, List<EnhancedBubble.EntityRecord> packMembers) {
        var steer = new Vector3f();
        int count = 0;

        for (var other : packMembers) {
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

    /**
     * Calculate distance between two points.
     */
    private float distance(Point3f a, Point3f b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float dz = b.z - a.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
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
     * Get pursuit speed (used when hunting).
     */
    public float getPursuitSpeed() {
        return pursuitSpeed;
    }

    /**
     * Get pack formation radius.
     */
    public float getPackRadius() {
        return packRadius;
    }

    /**
     * Pack roles for coordinated hunting.
     */
    private enum Role {
        LEADER,        // Closest to prey, direct pursuit
        FLANKER_LEFT,  // Intercepts from left
        FLANKER_RIGHT, // Intercepts from right
        SOLO,          // Independent hunter
        PATROL         // No active hunt
    }

    /**
     * Pack role assignment result.
     *
     * @param role           assigned role
     * @param targetPrey     target prey entity (null if none)
     * @param leaderPosition position of pack leader (null if not flanker)
     */
    private record PackRole(Role role, EnhancedBubble.EntityRecord targetPrey, Point3f leaderPosition) {}
}
