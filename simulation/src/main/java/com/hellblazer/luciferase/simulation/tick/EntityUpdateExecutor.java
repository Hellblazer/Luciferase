/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tick;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.UUID;

/**
 * Executes entity updates within a bubble.
 * <p>
 * Extracted from MultiBubbleSimulation.updateBubbleEntities() to reduce complexity.
 * Handles:
 * - Velocity computation per entity
 * - Velocity map updates
 * - Position calculation with deltaTime
 * - Grid boundary clamping (X, Y, Z)
 * - Bubble entity position updates
 * - Per-entity exception handling
 * <p>
 * Design Pattern: Strategy pattern for entity update logic.
 *
 * @author hal.hildebrand
 */
public class EntityUpdateExecutor {

    private static final Logger log = LoggerFactory.getLogger(EntityUpdateExecutor.class);

    private final EntityBehavior behavior;
    private final Map<UUID, Vector3f> velocities;
    private final GridConfiguration gridConfig;

    private EntityUpdateExecutor(EntityBehavior behavior,
                                 Map<UUID, Vector3f> velocities,
                                 GridConfiguration gridConfig) {
        this.behavior = behavior;
        this.velocities = velocities;
        this.gridConfig = gridConfig;
    }

    /**
     * Create a new entity update executor.
     *
     * @param behavior   Entity behavior for velocity computation
     * @param velocities Shared velocity map (UUID -> Vector3f)
     * @param gridConfig Grid configuration for boundary clamping
     * @return New executor instance
     */
    public static EntityUpdateExecutor create(EntityBehavior behavior,
                                             Map<UUID, Vector3f> velocities,
                                             GridConfiguration gridConfig) {
        return new EntityUpdateExecutor(behavior, velocities, gridConfig);
    }

    /**
     * Update all entities in the given bubble.
     * <p>
     * For each entity:
     * 1. Compute new velocity using behavior
     * 2. Update velocity map
     * 3. Calculate new position (position + velocity * deltaTime)
     * 4. Clamp to grid bounds
     * 5. Update bubble entity position
     * <p>
     * Exceptions are caught per entity to avoid cascading failures.
     *
     * @param bubble    Bubble containing entities to update
     * @param deltaTime Time step in seconds
     */
    public void updateEntities(EnhancedBubble bubble, float deltaTime) {
        for (var entity : bubble.getAllEntityRecords()) {
            try {
                var entityUUID = UUID.nameUUIDFromBytes(entity.id().getBytes());
                var velocity = velocities.computeIfAbsent(entityUUID, k -> new Vector3f());

                var newVelocity = behavior.computeVelocity(
                    entity.id(),
                    entity.position(),
                    velocity,
                    bubble,
                    deltaTime
                );

                velocities.put(entityUUID, newVelocity);

                var newPosition = new Point3f(entity.position());
                newPosition.x += newVelocity.x * deltaTime;
                newPosition.y += newVelocity.y * deltaTime;
                newPosition.z += newVelocity.z * deltaTime;

                // Clamp to grid bounds
                newPosition.x = Math.max(gridConfig.originX(),
                                        Math.min(gridConfig.originX() + gridConfig.totalWidth(), newPosition.x));
                newPosition.y = Math.max(gridConfig.originY(),
                                        Math.min(gridConfig.originY() + gridConfig.totalHeight(), newPosition.y));
                newPosition.z = Math.max(0f, Math.min(100f, newPosition.z));

                bubble.updateEntityPosition(entity.id(), newPosition);
            } catch (Exception e) {
                log.error("Failed to update entity {}: {}", entity.id(), e.getMessage());
            }
        }
    }
}
