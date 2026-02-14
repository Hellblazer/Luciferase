/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

/**
 * A region visible to a client with LOD and distance metadata.
 * <p>
 * Implements Comparable for priority ordering (closest regions first).
 *
 * @param regionId Region identifier (Morton code + level)
 * @param lodLevel Level of detail (0 = highest, maxLod = lowest)
 * @param distance Distance from camera eye to region center
 * @author hal.hildebrand
 */
public record VisibleRegion(
    RegionId regionId,
    int lodLevel,
    float distance
) implements Comparable<VisibleRegion> {

    @Override
    public int compareTo(VisibleRegion other) {
        return Float.compare(this.distance, other.distance);
    }
}
