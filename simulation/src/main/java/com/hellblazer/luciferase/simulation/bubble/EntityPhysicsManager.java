/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.config.WorldBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages entity physics: velocity tracking and position updates.
 * Extracted component from MultiBubbleSimulation following orchestrator pattern.
 * <p>
 * Responsibilities:
 * - Velocity tracking for all entities
 * - Position updates based on velocity and behavior
 * - World bounds clamping
 * - Initial velocity generation
 *
 * @author hal.hildebrand
 */
public class EntityPhysicsManager {

    private final Map<String, Vector3f> velocities;
    private final EntityBehavior behavior;
    private final WorldBounds worldBounds;

    /**
     * Create a new physics manager.
     *
     * @param behavior    Entity movement behavior
     * @param worldBounds World coordinate bounds
     */
    public EntityPhysicsManager(EntityBehavior behavior, WorldBounds worldBounds) {
        this.behavior = behavior;
        this.worldBounds = worldBounds;
        this.velocities = new ConcurrentHashMap<>();
    }

    /**
     * Initialize velocities for all entities based on behavior.
     * Generates random velocities in 3D sphere with deterministic seed.
     *
     * @param entities List of entities to initialize
     */
    public void initializeVelocities(List<EntityDistribution.EntitySpec> entities) {
        var random = new Random(43); // Deterministic seed for reproducibility
        var maxSpeed = behavior.getMaxSpeed();

        for (var entity : entities) {
            // Generate random velocity in 3D sphere
            var theta = random.nextFloat() * 2 * Math.PI; // Azimuthal angle
            var phi = random.nextFloat() * Math.PI; // Polar angle
            var speed = random.nextFloat() * maxSpeed;

            var vx = (float) (speed * Math.sin(phi) * Math.cos(theta));
            var vy = (float) (speed * Math.sin(phi) * Math.sin(theta));
            var vz = (float) (speed * Math.cos(phi));

            velocities.put(entity.id(), new Vector3f(vx, vy, vz));
        }
    }

    /**
     * Update all entities in a bubble: compute new velocities and positions.
     *
     * @param bubble    Bubble containing entities to update
     * @param deltaTime Time step in seconds
     */
    public void updateBubbleEntities(EnhancedBubble bubble, float deltaTime) {
        var entityRecords = bubble.getAllEntityRecords();

        for (var record : entityRecords) {
            var entityId = record.id();
            var currentPos = record.position();
            var currentVel = velocities.get(entityId);

            if (currentVel == null) {
                continue; // Entity has no velocity (shouldn't happen)
            }

            // Compute new velocity based on behavior
            var newVel = behavior.computeVelocity(entityId, currentPos, currentVel, bubble, deltaTime);

            // Update position based on new velocity
            var newPos = new Point3f(currentPos);
            newPos.x += newVel.x * deltaTime;
            newPos.y += newVel.y * deltaTime;
            newPos.z += newVel.z * deltaTime;

            // Clamp to world bounds
            newPos.x = worldBounds.clamp(newPos.x);
            newPos.y = worldBounds.clamp(newPos.y);
            newPos.z = worldBounds.clamp(newPos.z);

            // Update velocity map
            velocities.put(entityId, newVel);

            // Update entity in bubble (this also updates bounds)
            // Note: EnhancedBubble handles position updates internally via its Tetree
            bubble.removeEntity(entityId);
            bubble.addEntity(entityId, newPos, record.content());

            // Note: Spatial index updates are handled by bubble's internal Tetree
            // No need to manually update the top-level spatial index here
        }
    }

    /**
     * Get velocity for an entity.
     *
     * @param entityId Entity identifier
     * @return Velocity vector or null if not found
     */
    public Vector3f getVelocity(String entityId) {
        return velocities.get(entityId);
    }

    /**
     * Set velocity for an entity.
     *
     * @param entityId Entity identifier
     * @param velocity New velocity
     */
    public void setVelocity(String entityId, Vector3f velocity) {
        velocities.put(entityId, velocity);
    }

    /**
     * Remove velocity tracking for an entity.
     *
     * @param entityId Entity identifier
     */
    public void removeEntity(String entityId) {
        velocities.remove(entityId);
    }

    /**
     * Get number of tracked entities.
     *
     * @return Entity count
     */
    public int getEntityCount() {
        return velocities.size();
    }
}
