/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.config;

/**
 * Centralized configuration for world boundaries.
 * <p>
 * Used by behaviors, simulation loop, and visualization to ensure
 * consistent world size across all components.
 *
 * @param min Minimum world coordinate (same for x, y, z)
 * @param max Maximum world coordinate (same for x, y, z)
 * @author hal.hildebrand
 */
public record WorldBounds(float min, float max) {

    /**
     * Default world bounds: 0 to 200 units.
     */
    public static final WorldBounds DEFAULT = new WorldBounds(0f, 200f);

    /**
     * Validate bounds on construction.
     */
    public WorldBounds {
        if (min >= max) {
            throw new IllegalArgumentException("min (" + min + ") must be less than max (" + max + ")");
        }
    }

    /**
     * Get the size of the world (max - min).
     */
    public float size() {
        return max - min;
    }

    /**
     * Get the center of the world.
     */
    public float center() {
        return (min + max) / 2f;
    }

    /**
     * Check if a coordinate is within bounds.
     */
    public boolean contains(float coord) {
        return coord >= min && coord <= max;
    }

    /**
     * Clamp a coordinate to be within bounds.
     */
    public float clamp(float coord) {
        return Math.max(min, Math.min(max, coord));
    }

    /**
     * Create bounds with a margin inset from edges.
     *
     * @param margin Distance from edges
     * @return New bounds with margin applied
     */
    public WorldBounds withMargin(float margin) {
        return new WorldBounds(min + margin, max - margin);
    }
}
