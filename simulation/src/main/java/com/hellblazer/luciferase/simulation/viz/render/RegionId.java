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
 * Identifies a spatial region by its Morton code at a given octree level.
 * <p>
 * The Morton code encodes the 3D position as a space-filling curve index,
 * providing efficient spatial proximity queries. Regions at the same level
 * form a regular grid subdivision of the world.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param mortonCode Morton-encoded 3D coordinates
 * @param level      Octree subdivision level (depth)
 * @author hal.hildebrand
 */
public record RegionId(long mortonCode, int level) implements Comparable<RegionId> {

    /**
     * Compare regions by level first (shallower levels sort first),
     * then by Morton code for spatial ordering within the same level.
     */
    @Override
    public int compareTo(RegionId other) {
        var cmp = Integer.compare(this.level, other.level);
        return cmp != 0 ? cmp : Long.compare(this.mortonCode, other.mortonCode);
    }
}
