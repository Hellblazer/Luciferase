/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.entity;

/**
 * Entity type classification for behavior differentiation.
 * <p>
 * Used to distinguish between different entity roles in simulations:
 * - PREY: Entities that flock together and flee from predators
 * - PREDATOR: Entities that chase and hunt prey
 * <p>
 * The type is stored in the entity's content field and used by
 * behaviors to determine interaction patterns.
 *
 * @author hal.hildebrand
 */
public enum EntityType {
    /**
     * Prey entity - flocks with others, flees from predators.
     */
    PREY,

    /**
     * Predator entity - chases prey, may coordinate with other predators.
     */
    PREDATOR;

    /**
     * Get display color for this entity type (HTML color code).
     *
     * @return Color string for visualization
     */
    public String getColor() {
        return switch (this) {
            case PREY -> "#4A90E2";      // Blue
            case PREDATOR -> "#E74C3C";  // Red
        };
    }

    /**
     * Get relative size multiplier for visualization.
     *
     * @return Size multiplier (1.0 = normal)
     */
    public float getSizeMultiplier() {
        return switch (this) {
            case PREY -> 1.0f;
            case PREDATOR -> 1.5f;  // Predators are larger
        };
    }
}
