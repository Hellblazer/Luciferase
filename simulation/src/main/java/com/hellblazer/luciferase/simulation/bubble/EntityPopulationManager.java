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

import com.hellblazer.luciferase.simulation.config.WorldBounds;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Manages entity population and distribution within the simulation world.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Generate random entity positions within world bounds</li>
 *   <li>Maintain entity count configuration</li>
 *   <li>Provide consistent random distribution (deterministic seed)</li>
 * </ul>
 * <p>
 * This component is extracted from MultiBubbleSimulation as part of the
 * orchestrator pattern refactoring (Sprint B B1).
 *
 * @author hal.hildebrand
 */
public class EntityPopulationManager {

    private final EntityDistribution distribution;
    private final int entityCount;
    private final WorldBounds worldBounds;

    /**
     * Create entity population manager.
     *
     * @param distribution Entity distribution system
     * @param entityCount  Number of entities to populate
     * @param worldBounds  World coordinate bounds
     */
    public EntityPopulationManager(
        EntityDistribution distribution,
        int entityCount,
        WorldBounds worldBounds
    ) {
        this.distribution = distribution;
        this.entityCount = entityCount;
        this.worldBounds = worldBounds;
    }

    /**
     * Get entity distribution system.
     *
     * @return EntityDistribution instance
     */
    public EntityDistribution getDistribution() {
        return distribution;
    }

    /**
     * Get configured entity count.
     *
     * @return Number of entities
     */
    public int getEntityCount() {
        return entityCount;
    }

    /**
     * Get world bounds.
     *
     * @return WorldBounds instance
     */
    public WorldBounds getWorldBounds() {
        return worldBounds;
    }

    /**
     * Generate random entity positions within world bounds.
     *
     * @param count Number of entities to create
     * @return List of EntitySpec with random positions
     */
    public List<EntityDistribution.EntitySpec> populateEntities(int count) {
        var entities = new ArrayList<EntityDistribution.EntitySpec>(count);
        var random = new Random(42); // Deterministic seed for reproducibility

        var size = worldBounds.size();
        var min = worldBounds.min();

        for (int i = 0; i < count; i++) {
            // Generate random position in world bounds
            var x = min + random.nextFloat() * size;
            var y = min + random.nextFloat() * size;
            var z = min + random.nextFloat() * size;

            var position = new Point3f(x, y, z);
            var entityId = "entity-" + i;

            // Velocity will be initialized later
            entities.add(new EntityDistribution.EntitySpec(entityId, position, null));
        }

        return entities;
    }
}
