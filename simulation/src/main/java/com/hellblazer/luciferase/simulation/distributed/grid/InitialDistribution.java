/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.List;

/**
 * Spatial distribution algorithm for initial entity placement.
 * <p>
 * Assigns entities to bubbles based on their XY position in the grid.
 * The grid is 2D (XY plane), so Z coordinate does not affect assignment.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>For each entity, compute grid coordinate from (x,y) position</li>
 *   <li>Add entity to the bubble at that coordinate</li>
 *   <li>Skip entities outside grid bounds</li>
 * </ol>
 * <p>
 * This is a deterministic spatial assignment (not load balanced).
 * Entities cluster in the same spatial region will be in the same bubble.
 *
 * @author hal.hildebrand
 */
public class InitialDistribution {

    private static final Logger log = LoggerFactory.getLogger(InitialDistribution.class);

    /**
     * Distribute entities to bubbles based on spatial position.
     *
     * @param entities List of entities to distribute
     * @param grid     Bubble grid to populate
     * @param config   Grid configuration
     */
    public static void distribute(
        List<EntitySpec> entities,
        BubbleGrid<EnhancedBubble> grid,
        GridConfiguration config
    ) {
        int distributed = 0;
        int outOfBounds = 0;

        for (var entity : entities) {
            var coord = config.coordinateFor(entity.position());

            if (coord == null) {
                // Entity is outside grid bounds
                outOfBounds++;
                log.debug("Entity {} at {} is outside grid bounds, skipping",
                          entity.id(), entity.position());
                continue;
            }

            var bubble = grid.getBubble(coord);
            if (bubble == null) {
                log.warn("No bubble found at coordinate {}, skipping entity {}",
                         coord, entity.id());
                continue;
            }

            bubble.addEntity(entity.id(), entity.position(), entity.content());
            distributed++;
        }

        log.info("Distributed {} entities ({} out of bounds, {} total)",
                 distributed, outOfBounds, entities.size());
    }

    /**
     * Entity specification for initial distribution.
     *
     * @param id       Entity identifier
     * @param position Initial position
     * @param content  Entity content (may be null)
     */
    public record EntitySpec(String id, Point3f position, Object content) {
        public EntitySpec {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Entity ID must not be blank");
            }
            if (position == null) {
                throw new IllegalArgumentException("Position must not be null");
            }
        }
    }
}
