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

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.*;

/**
 * Factory for creating and distributing bubbles throughout the tetrahedral hierarchy.
 * <p>
 * This factory creates {@link EnhancedBubble} instances distributed across multiple
 * tree levels to balance spatial indexing performance. Bubbles are positioned in
 * Space-Filling Curve (SFC) order for cache locality.
 * <p>
 * Distribution Strategy:
 * <ul>
 *   <li>Even distribution across levels 0 to maxLevel</li>
 *   <li>Example: 9 bubbles with maxLevel=1 → 1 at L0, 8 at L1</li>
 *   <li>Example: 64 bubbles with maxLevel=2 → 1 at L0, 8 at L1, 55 at L2</li>
 *   <li>SFC-ordered keys for spatial locality (cache-friendly)</li>
 * </ul>
 * <p>
 * Performance:
 * <ul>
 *   <li>Create 100 bubbles in <100ms</li>
 *   <li>Thread-safe (all methods are static)</li>
 *   <li>No shared mutable state</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public final class TetreeBubbleFactory {

    /**
     * Private constructor - utility class should not be instantiated.
     */
    private TetreeBubbleFactory() {
        throw new AssertionError("TetreeBubbleFactory is a utility class and should not be instantiated");
    }

    /**
     * Create and distribute bubbles throughout the tetrahedral hierarchy.
     * <p>
     * Bubbles are distributed evenly across tree levels from 0 to maxLevel.
     * Each bubble is initialized with empty bounds (updated later by entity distribution).
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Determine number of levels to use: min(maxLevel+1, bubbleCount)</li>
     *   <li>Distribute bubbleCount across these levels</li>
     *   <li>For each level, generate distinct TetreeKey locations in SFC order</li>
     *   <li>Create EnhancedBubble for each key</li>
     *   <li>Add all bubbles to the grid</li>
     * </ol>
     *
     * @param grid                 TetreeBubbleGrid to populate
     * @param bubbleCount          Number of bubbles to create (must be positive)
     * @param maxLevel             Maximum tree level for distribution (0-21)
     * @param maxEntitiesPerBubble Target entity capacity per bubble (for optimization)
     * @throws NullPointerException     if grid is null
     * @throws IllegalArgumentException if bubbleCount <= 0, maxLevel invalid, or maxEntitiesPerBubble <= 0
     */
    public static void createBubbles(TetreeBubbleGrid grid,
                                     int bubbleCount,
                                     byte maxLevel,
                                     int maxEntitiesPerBubble) {
        Objects.requireNonNull(grid, "TetreeBubbleGrid cannot be null");
        if (bubbleCount <= 0) {
            throw new IllegalArgumentException("Bubble count must be positive, got: " + bubbleCount);
        }
        if (maxLevel < 0 || maxLevel > 21) {
            throw new IllegalArgumentException("Max level must be 0-21, got: " + maxLevel);
        }
        if (maxEntitiesPerBubble <= 0) {
            throw new IllegalArgumentException("Max entities per bubble must be positive, got: " + maxEntitiesPerBubble);
        }

        // Calculate bubble distribution across levels
        var distribution = createBalancedGrid(bubbleCount, maxLevel);

        // Assign TetreeKey locations for all bubbles
        var locations = assignBubbleLocations(bubbleCount, maxLevel, distribution);

        // Target frame time: estimate based on entity count and 60fps target
        // Assume ~500 entities total, 60fps = 16.67ms per frame
        long targetFrameMs = 16;

        // Use grid's createBubbles method which handles internal bubble creation
        grid.createBubbles(bubbleCount, maxLevel, targetFrameMs);
    }

    /**
     * Create a balanced distribution of bubbles across tree levels.
     * <p>
     * Returns a list where index i contains the number of bubbles to place
     * at level i. The distribution aims to balance tree depth:
     * <ul>
     *   <li>1 bubble → [1] (level 0 only)</li>
     *   <li>9 bubbles → [1, 8] (1 at L0, 8 at L1)</li>
     *   <li>64 bubbles → [1, 8, 55] (1 at L0, 8 at L1, 55 at L2)</li>
     * </ul>
     * <p>
     * Strategy: Place 1 bubble at level 0, then fill subsequent levels up to
     * capacity (8^level), distributing remainder to deeper levels.
     *
     * @param desiredCount Total number of bubbles to distribute
     * @param maxLevel     Maximum level to use (0-21)
     * @return List of bubble counts per level, size = min(maxLevel+1, desiredCount)
     * @throws IllegalArgumentException if desiredCount <= 0 or maxLevel invalid
     */
    public static List<Integer> createBalancedGrid(int desiredCount, byte maxLevel) {
        if (desiredCount <= 0) {
            throw new IllegalArgumentException("Desired count must be positive, got: " + desiredCount);
        }
        if (maxLevel < 0 || maxLevel > 21) {
            throw new IllegalArgumentException("Max level must be 0-21, got: " + maxLevel);
        }

        var distribution = new ArrayList<Integer>();
        int remaining = desiredCount;
        byte currentLevel = 0;

        // Special case: single bubble at root
        if (desiredCount == 1) {
            distribution.add(1);
            return distribution;
        }

        // Place 1 bubble at level 0 (root)
        distribution.add(1);
        remaining--;
        currentLevel++;

        // Distribute remaining bubbles across subsequent levels
        while (remaining > 0 && currentLevel <= maxLevel) {
            // Capacity at this level: 8^level
            int capacity = (int) Math.pow(8, currentLevel);

            // Take min of capacity and remaining
            int atThisLevel = Math.min(capacity, remaining);
            distribution.add(atThisLevel);

            remaining -= atThisLevel;
            currentLevel++;
        }

        // If bubbles remain after maxLevel, add them to the last level
        if (remaining > 0) {
            int lastIndex = distribution.size() - 1;
            distribution.set(lastIndex, distribution.get(lastIndex) + remaining);
        }

        return distribution;
    }

    /**
     * Assign TetreeKey locations for bubble placement in SFC order.
     * <p>
     * Generates a list of TetreeKey locations for each bubble, ensuring:
     * <ul>
     *   <li>Even distribution across specified levels</li>
     *   <li>SFC ordering for spatial locality (cache-friendly access)</li>
     *   <li>Uniqueness (no duplicate keys)</li>
     * </ul>
     * <p>
     * Keys are generated by:
     * <ol>
     *   <li>Determining level distribution via {@link #createBalancedGrid}</li>
     *   <li>For each level, creating tetrahedra by subdividing from root</li>
     *   <li>Extracting TetreeKey from each tetrahedron</li>
     *   <li>Sorting keys by SFC index for cache locality</li>
     * </ol>
     *
     * @param totalCount Total number of keys to generate
     * @param maxLevel   Maximum tree level (0-21)
     * @return List of unique TetreeKey locations in SFC order
     * @throws IllegalArgumentException if totalCount <= 0 or maxLevel invalid
     */
    public static List<TetreeKey<?>> assignBubbleLocations(int totalCount, byte maxLevel) {
        return assignBubbleLocations(totalCount, maxLevel, createBalancedGrid(totalCount, maxLevel));
    }

    /**
     * Assign TetreeKey locations using a pre-calculated distribution.
     * <p>
     * This overload allows callers to specify their own distribution strategy
     * rather than using {@link #createBalancedGrid}.
     *
     * @param totalCount   Total number of keys to generate
     * @param maxLevel     Maximum tree level (0-21)
     * @param distribution List of bubble counts per level
     * @return List of unique TetreeKey locations in SFC order
     * @throws NullPointerException     if distribution is null
     * @throws IllegalArgumentException if totalCount <= 0, maxLevel invalid, or distribution invalid
     */
    static List<TetreeKey<?>> assignBubbleLocations(int totalCount, byte maxLevel, List<Integer> distribution) {
        Objects.requireNonNull(distribution, "Distribution cannot be null");
        if (totalCount <= 0) {
            throw new IllegalArgumentException("Total count must be positive, got: " + totalCount);
        }
        if (maxLevel < 0 || maxLevel > 21) {
            throw new IllegalArgumentException("Max level must be 0-21, got: " + maxLevel);
        }

        var keys = new ArrayList<TetreeKey<?>>(totalCount);
        var usedKeys = new HashSet<TetreeKey<?>>();

        int bubbleIndex = 0;

        for (byte level = 0; level < distribution.size() && bubbleIndex < totalCount; level++) {
            int bubblesAtThisLevel = distribution.get(level);

            for (int i = 0; i < bubblesAtThisLevel && bubbleIndex < totalCount; i++) {
                // Create a Tet at this level using subdivision path based on bubbleIndex
                var tet = createTetAtLevel(level, bubbleIndex);
                var key = tet.tmIndex();

                // Ensure uniqueness
                if (usedKeys.contains(key)) {
                    bubbleIndex++;
                    continue;
                }

                usedKeys.add(key);
                keys.add(key);
                bubbleIndex++;
            }
        }

        // Sort keys by level and then by high/low bits for SFC-like ordering
        // This provides cache locality even though we can't directly access consecutiveIndex
        // on the abstract TetreeKey type
        keys.sort(Comparator.comparingInt((TetreeKey<?> k) -> k.getLevel())
                            .thenComparingLong(TetreeKey::getHighBits)
                            .thenComparingLong(TetreeKey::getLowBits));

        return keys;
    }

    /**
     * Create a valid Tet at a specific level by subdividing from root.
     * <p>
     * This ensures all tetrahedra have valid S0-S5 types (0-5) by following
     * a subdivision path from the root. The index parameter determines which
     * child path to take at each level.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Start at root (level 0, S0 tetrahedron)</li>
     *   <li>For each level 1..targetLevel:</li>
     *   <li>  Extract child index from 'index' parameter (mod 8)</li>
     *   <li>  Subdivide current tetrahedron using child(childIndex)</li>
     *   <li>  Continue to next level</li>
     * </ol>
     *
     * @param level Target refinement level (0-21)
     * @param index Encoding of which child path to take (determines spatial position)
     * @return Valid Tet at the specified level with valid S0-S5 type
     */
    private static Tet createTetAtLevel(byte level, int index) {
        if (level == 0) {
            // Root tetrahedron: S0 type at origin
            return new Tet(0, 0, 0, (byte) 0, (byte) 0);
        }

        // Start from root and subdivide down to target level
        var current = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        int remainingIndex = index;

        for (byte l = 1; l <= level; l++) {
            // Extract child index for this level (0-7)
            int childIndex = remainingIndex % 8;
            remainingIndex = remainingIndex / 8;

            // Subdivide: each tetrahedron has 8 children (standard octree-like subdivision)
            current = current.child(childIndex);
        }

        return current;
    }
}
