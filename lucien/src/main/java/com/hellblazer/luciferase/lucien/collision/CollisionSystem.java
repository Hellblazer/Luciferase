/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialIndex.CollisionPair;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Complete collision detection and response system. Integrates spatial indexing, collision detection, and physics
 * response.
 *
 * @param <ID>      The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * @author hal.hildebrand
 */
public class CollisionSystem<ID extends EntityID, Content> {

    protected final SpatialIndex<?, ID, Content>         spatialIndex;
    protected final CollisionResolver                    resolver;
    protected final Map<ID, PhysicsProperties>           physicsProperties;
    protected final List<CollisionListener<ID, Content>> listeners;
    protected final CollisionFilter<ID, Content>         globalFilter;
    private         CollisionStats                       lastStats = new CollisionStats(0, 0, 0, 0, 0);

    public CollisionSystem(SpatialIndex<?, ID, Content> spatialIndex) {
        this(spatialIndex, new CollisionResolver(), CollisionFilter.all());
    }

    public CollisionSystem(SpatialIndex<?, ID, Content> spatialIndex, CollisionResolver resolver,
                           CollisionFilter<ID, Content> globalFilter) {
        this.spatialIndex = spatialIndex;
        this.resolver = resolver;
        this.physicsProperties = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
        this.globalFilter = globalFilter;
    }

    /**
     * Add a collision listener
     */
    public void addCollisionListener(CollisionListener<ID, Content> listener) {
        listeners.add(listener);
    }

    /**
     * Get the last collision statistics
     */
    public CollisionStats getLastStats() {
        return lastStats;
    }

    /**
     * Get physics properties for an entity (creates default if not exists)
     */
    public PhysicsProperties getPhysicsProperties(ID entityId) {
        return physicsProperties.computeIfAbsent(entityId, k -> new PhysicsProperties());
    }

    /**
     * Process all collisions in the spatial index
     *
     * @return list of processed collision responses
     */
    public List<ProcessedCollision<ID>> processAllCollisions() {
        long startTime = System.nanoTime();

        // Find all collisions
        List<CollisionPair<ID, Content>> collisions = spatialIndex.findAllCollisions();

        int broadPhaseChecks = collisions.size();
        List<ProcessedCollision<ID>> processed = new ArrayList<>();
        int narrowPhaseChecks = 0;
        int resolved = 0;

        // Process each collision
        for (CollisionPair<ID, Content> collision : collisions) {
            narrowPhaseChecks++;

            // Apply global filter
            if (!globalFilter.shouldProcess(collision)) {
                continue;
            }

            // Get physics properties
            PhysicsProperties props1 = getPhysicsProperties(collision.entityId1());
            PhysicsProperties props2 = getPhysicsProperties(collision.entityId2());

            // Skip if both are static
            if (props1.isStatic() && props2.isStatic()) {
                continue;
            }

            // Resolve collision
            CollisionResponse response = resolver.resolveCollision(collision, props1.getVelocity(),
                                                                   props2.getVelocity(), props1.getMass(),
                                                                   props2.getMass());

            // Notify listeners
            boolean shouldApply = notifyListeners(collision, response);

            if (shouldApply && response.hasResponse()) {
                // Apply impulses
                props1.applyImpulse(response.impulse1());
                props2.applyImpulse(response.impulse2());

                // Apply position corrections
                applyPositionCorrection(collision.entityId1(), response.correction1());
                applyPositionCorrection(collision.entityId2(), response.correction2());

                processed.add(new ProcessedCollision<>(collision, response));
                resolved++;
            }
        }

        long endTime = System.nanoTime();
        lastStats = new CollisionStats(broadPhaseChecks, narrowPhaseChecks, collisions.size(), resolved,
                                       endTime - startTime);

        return processed;
    }

    /**
     * Process collisions for a specific entity
     */
    public List<ProcessedCollision<ID>> processEntityCollisions(ID entityId) {
        List<CollisionPair<ID, Content>> collisions = spatialIndex.findCollisions(entityId);
        List<ProcessedCollision<ID>> processed = new ArrayList<>();

        PhysicsProperties entityProps = getPhysicsProperties(entityId);

        for (CollisionPair<ID, Content> collision : collisions) {
            if (!globalFilter.shouldProcess(collision)) {
                continue;
            }

            // Determine which entity is the other one
            boolean isEntity1 = collision.entityId1().equals(entityId);
            ID otherId = isEntity1 ? collision.entityId2() : collision.entityId1();
            PhysicsProperties otherProps = getPhysicsProperties(otherId);

            // Skip if both static
            if (entityProps.isStatic() && otherProps.isStatic()) {
                continue;
            }

            // Resolve collision
            CollisionResponse response;
            if (isEntity1) {
                response = resolver.resolveCollision(collision, entityProps.getVelocity(), otherProps.getVelocity(),
                                                     entityProps.getMass(), otherProps.getMass());
            } else {
                response = resolver.resolveCollision(collision, otherProps.getVelocity(), entityProps.getVelocity(),
                                                     otherProps.getMass(), entityProps.getMass());
            }

            // Notify listeners
            boolean shouldApply = notifyListeners(collision, response);

            if (shouldApply && response.hasResponse()) {
                // Apply responses
                if (isEntity1) {
                    entityProps.applyImpulse(response.impulse1());
                    otherProps.applyImpulse(response.impulse2());
                    applyPositionCorrection(entityId, response.correction1());
                    applyPositionCorrection(otherId, response.correction2());
                } else {
                    otherProps.applyImpulse(response.impulse1());
                    entityProps.applyImpulse(response.impulse2());
                    applyPositionCorrection(otherId, response.correction1());
                    applyPositionCorrection(entityId, response.correction2());
                }

                processed.add(new ProcessedCollision<>(collision, response));
            }
        }

        return processed;
    }

    /**
     * Remove a collision listener
     */
    public void removeCollisionListener(CollisionListener<ID, Content> listener) {
        listeners.remove(listener);
    }

    /**
     * Remove physics properties for an entity
     */
    public void removePhysicsProperties(ID entityId) {
        physicsProperties.remove(entityId);
    }

    /**
     * Set physics properties for an entity
     */
    public void setPhysicsProperties(ID entityId, PhysicsProperties properties) {
        physicsProperties.put(entityId, properties);
    }

    /**
     * Update physics simulation
     *
     * @param deltaTime time step in seconds
     * @param gravity   gravity vector (optional)
     */
    public void updatePhysics(float deltaTime, Vector3f gravity) {
        // Apply gravity and integrate velocities
        for (Map.Entry<ID, PhysicsProperties> entry : physicsProperties.entrySet()) {
            ID entityId = entry.getKey();
            PhysicsProperties props = entry.getValue();

            // Skip static objects
            if (props.isStatic()) {
                continue;
            }

            // Apply gravity if not kinematic
            if (gravity != null && !props.isKinematic()) {
                Vector3f gravityForce = new Vector3f(gravity);
                gravityForce.scale(props.getMass());
                props.applyForce(gravityForce);
            }

            // Integrate position
            Vector3f displacement = props.integrate(deltaTime);

            // Update entity position if moved
            if (displacement.lengthSquared() > 0) {
                Point3f currentPos = spatialIndex.getEntityPosition(entityId);
                if (currentPos != null) {
                    Point3f newPos = new Point3f(currentPos);
                    newPos.add(displacement);

                    // Update in spatial index
                    byte level = determineEntityLevel(entityId);
                    spatialIndex.updateEntity(entityId, newPos, level);
                }
            }

            // Clear forces for next frame
            props.clearForces();
        }
    }

    /**
     * Apply position correction to an entity
     */
    private void applyPositionCorrection(ID entityId, Vector3f correction) {
        if (correction.lengthSquared() > 0) {
            Point3f currentPos = spatialIndex.getEntityPosition(entityId);
            if (currentPos != null) {
                Point3f newPos = new Point3f(currentPos);
                newPos.add(correction);
                byte level = determineEntityLevel(entityId);
                spatialIndex.updateEntity(entityId, newPos, level);
            }
        }
    }

    /**
     * Determine the appropriate level for an entity based on its properties. This method uses a heuristic based on
     * entity size and velocity to determine an appropriate level in the spatial hierarchy.
     *
     * @param entityId the entity to get the level for
     * @return the appropriate level for spatial updates
     */
    private byte determineEntityLevel(ID entityId) {
        // Check if we have physics properties for better level determination
        PhysicsProperties props = physicsProperties.get(entityId);
        EntityBounds bounds = spatialIndex.getEntityBounds(entityId);

        // Use a heuristic based on entity size and velocity
        if (bounds != null) {
            // Calculate the maximum extent of the entity
            float maxExtent = Math.max(
            Math.max(bounds.getMaxX() - bounds.getMinX(), bounds.getMaxY() - bounds.getMinY()),
            bounds.getMaxZ() - bounds.getMinZ());

            // Map entity size to appropriate level
            // Larger entities should be at coarser levels (lower numbers)
            // Smaller entities can be at finer levels (higher numbers)
            if (maxExtent > 1000.0f) {
                return 5;  // Very large entities
            } else if (maxExtent > 100.0f) {
                return 8;  // Large entities
            } else if (maxExtent > 10.0f) {
                return 10;  // Medium entities
            } else if (maxExtent > 1.0f) {
                return 12;   // Small entities
            } else {
                return 15;  // Very small entities
            }
        }

        // If we have velocity information, fast-moving objects might benefit
        // from slightly coarser levels to reduce update frequency
        if (props != null && !props.isStatic()) {
            float speed = props.getVelocity().length();
            if (speed > 100.0f) {
                return 8;  // Fast-moving objects at coarser level
            }
        }

        // Default to level 10 for entities without size information
        return 10;
    }

    /**
     * Notify all listeners about a collision
     */
    private boolean notifyListeners(CollisionPair<ID, Content> collision, CollisionResponse response) {
        boolean shouldApply = true;

        for (CollisionListener<ID, Content> listener : listeners) {
            if (!listener.onCollision(collision, response)) {
                shouldApply = false;
            }
        }

        return shouldApply;
    }

    /**
     * Statistics about collision processing
     */
    public record CollisionStats(int broadPhaseChecks, int narrowPhaseChecks, int collisionsDetected,
                                 int collisionsResolved, long totalProcessingTime) {
        public double averageProcessingTime() {
            return collisionsDetected > 0 ? (double) totalProcessingTime / collisionsDetected : 0;
        }
    }

    /**
     * Record of a processed collision
     */
    public record ProcessedCollision<ID extends EntityID>(CollisionPair<ID, ?> collision, CollisionResponse response) {
    }
}
