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
package com.hellblazer.luciferase.lucien;

/**
 * Base interface for all spatial index keys.
 *
 * This interface defines the contract for keys used in spatial index structures. Each spatial structure (Octree,
 * Tetree, etc.) implements its own key type that encodes the necessary information for unique spatial identification.
 *
 * Keys must be immutable and implement proper equals/hashCode semantics. The Comparable ordering must preserve spatial
 * locality where possible.
 *
 * @param <K> The concrete key type (self-referential for type safety)
 * @author hal.hildebrand
 */
public interface SpatialKey<K extends SpatialKey<K>> extends Comparable<K> {

    /**
     * Get the level of this key in the spatial hierarchy. Level 0 represents the root, with increasing levels
     * representing finer subdivisions of space.
     *
     * This method is required for optimizations like SpatialIndexSet that need efficient level-based operations.
     *
     * @return the hierarchical level (0-based)
     */
    byte getLevel();

    /**
     * Check if this key represents a valid spatial location. Some key encodings may have invalid states that should be
     * rejected.
     *
     * @return true if this key represents a valid spatial location
     */
    default boolean isValid() {
        return true;
    }

    /**
     * Get the parent key of this spatial key in the hierarchy.
     *
     * @return the parent key, or null if this is the root (level 0)
     */
    K parent();

    /**
     * Answer the root cell of the decomposition
     *
     * @return K - the root cell of the decomposition
     */
    K root();

    /**
     * Get a human-readable string representation of this key. This should include all relevant components (level,
     * index, etc.) for debugging purposes.
     *
     * @return string representation of this key
     */
    @Override
    String toString();
}
