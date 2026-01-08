/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.distributed.grid.BubbleCoordinate;
import com.hellblazer.luciferase.simulation.distributed.grid.GridConfiguration;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity distribution algorithm for assigning entities to grid cells.
 * <p>
 * Distribution is deterministic and spatial - each entity is assigned to the
 * grid cell containing its XY position. Z coordinate is ignored since grid
 * cells are infinite columns in the Z direction.
 * <p>
 * This is a simple spatial partitioning algorithm that does NOT consider load
 * balancing. Entities outside grid bounds are filtered out.
 *
 * @author hal.hildebrand
 */
public class InitialDistribution {

    /**
     * Distribute entities to grid cells based on spatial position.
     * <p>
     * Each entity is assigned to the cell containing its XY position.
     * Entities outside grid bounds are filtered out.
     *
     * @param entities List of entity positions
     * @param config   Grid configuration
     * @return Map from BubbleCoordinate to list of entity positions in that cell
     */
    public static Map<BubbleCoordinate, List<Point3f>> distribute(
        List<Point3f> entities,
        GridConfiguration config
    ) {
        var distribution = new HashMap<BubbleCoordinate, List<Point3f>>();

        for (var position : entities) {
            var coord = config.coordinateFor(position);
            if (coord != null) {
                distribution.computeIfAbsent(coord, k -> new ArrayList<>()).add(position);
            }
            // Silently filter out entities outside grid bounds
        }

        return distribution;
    }

    /**
     * Count entities per cell for load balancing analysis.
     * <p>
     * Returns a map from BubbleCoordinate to entity count.
     * Useful for analyzing distribution quality.
     *
     * @param entities List of entity positions
     * @param config   Grid configuration
     * @return Map from BubbleCoordinate to entity count
     */
    public static Map<BubbleCoordinate, Integer> countPerCell(
        List<Point3f> entities,
        GridConfiguration config
    ) {
        var distribution = distribute(entities, config);
        var counts = new HashMap<BubbleCoordinate, Integer>();

        for (var entry : distribution.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().size());
        }

        return counts;
    }
}
