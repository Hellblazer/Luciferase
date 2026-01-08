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
import com.hellblazer.luciferase.simulation.entity.EntityType;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.HashMap;
import java.util.Map;

/**
 * Composite behavior that routes to different behaviors based on entity type.
 * <p>
 * Enables heterogeneous simulations where different entity types exhibit
 * different behaviors (e.g., predators chase, prey flee).
 * <p>
 * The behavior delegates to type-specific sub-behaviors by checking the
 * entity's content field. If an entity has no type or no matching behavior,
 * it uses the default behavior.
 *
 * @author hal.hildebrand
 */
public class CompositeEntityBehavior implements EntityBehavior {

    private final Map<EntityType, EntityBehavior> behaviors = new HashMap<>();
    private final EntityBehavior defaultBehavior;
    private final float maxAoiRadius;
    private final float maxSpeed;

    /**
     * Create a composite behavior with a default.
     *
     * @param defaultBehavior Behavior for entities without a type
     */
    public CompositeEntityBehavior(EntityBehavior defaultBehavior) {
        if (defaultBehavior == null) {
            throw new IllegalArgumentException("Default behavior cannot be null");
        }
        this.defaultBehavior = defaultBehavior;
        this.maxAoiRadius = defaultBehavior.getAoiRadius();
        this.maxSpeed = defaultBehavior.getMaxSpeed();
    }

    /**
     * Register a behavior for a specific entity type.
     *
     * @param type     Entity type
     * @param behavior Behavior for this type
     * @return This composite for chaining
     */
    public CompositeEntityBehavior addBehavior(EntityType type, EntityBehavior behavior) {
        if (type == null) throw new IllegalArgumentException("Type cannot be null");
        if (behavior == null) throw new IllegalArgumentException("Behavior cannot be null");
        behaviors.put(type, behavior);
        return this;
    }

    /**
     * Swap velocity buffers for all sub-behaviors that support it.
     * <p>
     * Call this at the start of each simulation tick.
     */
    public void swapVelocityBuffers() {
        if (defaultBehavior instanceof FlockingBehavior fb) {
            fb.swapVelocityBuffers();
        }
        for (var behavior : behaviors.values()) {
            if (behavior instanceof FlockingBehavior fb) {
                fb.swapVelocityBuffers();
            } else if (behavior instanceof PreyBehavior pb) {
                pb.swapVelocityBuffers();
            }
        }
    }

    @Override
    public Vector3f computeVelocity(String entityId, Point3f position, Vector3f velocity,
                                    EnhancedBubble bubble, float deltaTime) {
        // Get the entity record to access content (type)
        var records = bubble.getAllEntityRecords().stream()
            .filter(r -> r.id().equals(entityId))
            .findFirst();

        if (records.isEmpty()) {
            // Entity not found, use default
            return defaultBehavior.computeVelocity(entityId, position, velocity, bubble, deltaTime);
        }

        var record = records.get();
        EntityBehavior targetBehavior = defaultBehavior;

        // Determine behavior based on entity type
        if (record.content() instanceof EntityType entityType) {
            targetBehavior = behaviors.getOrDefault(entityType, defaultBehavior);
        }

        return targetBehavior.computeVelocity(entityId, position, velocity, bubble, deltaTime);
    }

    @Override
    public float getAoiRadius() {
        // Return the maximum AOI among all behaviors
        float max = maxAoiRadius;
        for (var behavior : behaviors.values()) {
            max = Math.max(max, behavior.getAoiRadius());
        }
        return max;
    }

    @Override
    public float getMaxSpeed() {
        // Return the maximum speed among all behaviors
        float max = maxSpeed;
        for (var behavior : behaviors.values()) {
            max = Math.max(max, behavior.getMaxSpeed());
        }
        return max;
    }

    /**
     * Get the behavior for a specific entity type.
     *
     * @param type Entity type
     * @return Behavior for this type, or default if not registered
     */
    public EntityBehavior getBehavior(EntityType type) {
        return behaviors.getOrDefault(type, defaultBehavior);
    }
}
