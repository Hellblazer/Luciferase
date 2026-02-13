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
 * Axis-aligned bounding box for a spatial region.
 * <p>
 * Defines the spatial extent of a region in 3D space. Used for entity containment
 * testing and frustum culling.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param minX Minimum X coordinate (inclusive)
 * @param minY Minimum Y coordinate (inclusive)
 * @param minZ Minimum Z coordinate (inclusive)
 * @param maxX Maximum X coordinate (exclusive)
 * @param maxY Maximum Y coordinate (exclusive)
 * @param maxZ Maximum Z coordinate (exclusive)
 * @author hal.hildebrand
 */
public record RegionBounds(float minX, float minY, float minZ,
                           float maxX, float maxY, float maxZ) {

    /**
     * Calculate the center X coordinate.
     */
    public float centerX() {
        return (minX + maxX) * 0.5f;
    }

    /**
     * Calculate the center Y coordinate.
     */
    public float centerY() {
        return (minY + maxY) * 0.5f;
    }

    /**
     * Calculate the center Z coordinate.
     */
    public float centerZ() {
        return (minZ + maxZ) * 0.5f;
    }

    /**
     * Calculate the size (assuming cubic regions).
     * For non-cubic regions, returns X extent.
     */
    public float size() {
        return maxX - minX;
    }

    /**
     * Test if a point is contained within the region bounds.
     * <p>
     * Min bounds are inclusive, max bounds are exclusive (half-open interval).
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return true if the point is within bounds
     */
    public boolean contains(float x, float y, float z) {
        return x >= minX && x < maxX
            && y >= minY && y < maxY
            && z >= minZ && z < maxZ;
    }
}
