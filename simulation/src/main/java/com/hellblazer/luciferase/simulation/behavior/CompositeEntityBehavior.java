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

    // entityId -> behavior cache to avoid an O(N) record scan on every
    // computeVelocity() call (which made the per-tick dispatch cost O(N^2) —
    // see Luciferase-0frcy.1). The bubble returns a fresh record snapshot per
    // call, so the cache is rebuilt whenever the snapshot size changes; within
    // a single tick the entity set is stable, collapsing per-tick dispatch to
    // O(N). A null cached value means "present, but resolves to defaultBehavior";
    // an absent key means the entity was not in the last snapshot.
    private final Map<String, EntityBehavior> behaviorByEntityId = new HashMap<>();
    private int cachedSnapshotSize = -1;

    /**
     * Visible-for-testing: number of full record-snapshot scans performed.
     * A correct O(N) tick performs exactly one scan regardless of entity count.
     */
    long snapshotScanCount = 0;

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
        EntityBehavior targetBehavior = resolveBehavior(entityId, bubble);
        return targetBehavior.computeVelocity(entityId, position, velocity, bubble, deltaTime);
    }

    /**
     * Resolve the type-specific behavior for an entity, using a per-tick cache
     * to avoid re-scanning the full entity-record snapshot on every call.
     * <p>
     * The bubble returns a fresh record list per call; we rebuild the cache
     * only when the snapshot size changes. Within a single tick the entity set
     * is stable, so the first call rebuilds (one O(N) scan) and all subsequent
     * calls are O(1) — collapsing the whole-tick dispatch cost from O(N^2) to
     * O(N).
     */
    private EntityBehavior resolveBehavior(String entityId, EnhancedBubble bubble) {
        var records = bubble.getAllEntityRecords();
        if (records.size() != cachedSnapshotSize || !behaviorByEntityId.containsKey(entityId)) {
            rebuildCache(records);
        }
        return behaviorByEntityId.getOrDefault(entityId, defaultBehavior);
    }

    private void rebuildCache(java.util.List<EnhancedBubble.EntityRecord> records) {
        snapshotScanCount++;
        behaviorByEntityId.clear();
        for (var record : records) {
            EntityBehavior target = defaultBehavior;
            if (record.content() instanceof EntityType entityType) {
                target = behaviors.getOrDefault(entityType, defaultBehavior);
            }
            behaviorByEntityId.put(record.id(), target);
        }
        cachedSnapshotSize = records.size();
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
