/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Map;

/**
 * Handles entity updates within a bubble: behavior integration, velocity application,
 * and position clamping to world/bubble bounds.
 * <p>
 * Extracted from TwoBubbleSimulation as part of Sprint B B2 decomposition.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Apply EntityBehavior to compute velocities</li>
 *   <li>Update entity positions from velocities</li>
 *   <li>Clamp positions to world bounds (Y, Z) and bubble region (X)</li>
 *   <li>Handle per-entity update errors gracefully</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class BubbleEntityUpdater {

    private static final Logger log = LoggerFactory.getLogger(BubbleEntityUpdater.class);

    private final WorldBounds worldBounds;

    /**
     * Create an entity updater with world bounds.
     *
     * @param worldBounds World boundary configuration for clamping
     */
    public BubbleEntityUpdater(WorldBounds worldBounds) {
        this.worldBounds = worldBounds;
    }

    /**
     * Update all entities in a bubble: apply behavior, update velocities, update positions.
     *
     * @param bubble     Bubble containing entities to update
     * @param behavior   Behavior to compute velocities (e.g., FlockingBehavior)
     * @param velocities Velocity map for this bubble (modified in-place)
     * @param deltaTime  Time step in seconds
     * @param minX       Minimum X coordinate for this bubble's region
     * @param maxX       Maximum X coordinate for this bubble's region
     */
    public void updateBubbleEntities(EnhancedBubble bubble, EntityBehavior behavior,
                                     Map<String, Vector3f> velocities,
                                     float deltaTime, float minX, float maxX) {
        for (var entity : bubble.getAllEntityRecords()) {
            try {
                updateEntity(entity, bubble, behavior, velocities, deltaTime, minX, maxX);
            } catch (Exception e) {
                log.error("Failed to update entity {}: {}", entity.id(), e.getMessage());
            }
        }
    }

    /**
     * Update a single entity: compute velocity, apply movement, clamp to bounds.
     *
     * @param entity     Entity record from bubble
     * @param bubble     Bubble containing the entity
     * @param behavior   Behavior for velocity computation
     * @param velocities Velocity map (modified in-place)
     * @param deltaTime  Time step in seconds
     * @param minX       Minimum X coordinate for bubble region
     * @param maxX       Maximum X coordinate for bubble region
     */
    private void updateEntity(EnhancedBubble.EntityRecord entity, EnhancedBubble bubble,
                              EntityBehavior behavior, Map<String, Vector3f> velocities,
                              float deltaTime, float minX, float maxX) {
        // Get or create velocity
        var velocity = velocities.computeIfAbsent(entity.id(), k -> new Vector3f());

        // Compute new velocity using behavior
        var newVelocity = behavior.computeVelocity(
            entity.id(),
            entity.position(),
            velocity,
            bubble,
            deltaTime
        );

        // Update velocity map
        velocities.put(entity.id(), newVelocity);

        // Compute new position
        var newPosition = new Point3f(entity.position());
        newPosition.x += newVelocity.x * deltaTime;
        newPosition.y += newVelocity.y * deltaTime;
        newPosition.z += newVelocity.z * deltaTime;

        // Clamp to world bounds (y, z) and bubble region (x)
        clampPosition(newPosition, minX, maxX);

        // Update bubble with new position
        bubble.updateEntityPosition(entity.id(), newPosition);
    }

    /**
     * Clamp position to world bounds (Y, Z) and bubble region (X).
     *
     * @param position Position to clamp (modified in-place)
     * @param minX     Minimum X coordinate for bubble region
     * @param maxX     Maximum X coordinate for bubble region
     */
    private void clampPosition(Point3f position, float minX, float maxX) {
        // Clamp X to bubble region
        position.x = Math.max(minX, Math.min(maxX, position.x));

        // Clamp Y, Z to world bounds
        position.y = worldBounds.clamp(position.y);
        position.z = worldBounds.clamp(position.z);
    }
}
