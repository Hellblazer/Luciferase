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
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.entity.StringEntityIDGenerator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Organizes bubbles in a Tetree structure (replaces BubbleGrid).
 * <p>
 * This class manages a collection of {@link EnhancedBubble} instances distributed
 * throughout a 3D tetrahedral hierarchy. It provides:
 * <ul>
 *   <li><b>Bubble Creation</b> - Distribute bubbles across tree levels</li>
 *   <li><b>Neighbor Discovery</b> - Find topological and boundary neighbors</li>
 *   <li><b>Bounds Management</b> - Update adaptive bounds as entities move</li>
 *   <li><b>Thread-Safe Access</b> - Concurrent bubble operations</li>
 * </ul>
 * <p>
 * Architecture:
 * <ul>
 *   <li>Replaces 2D grid-based BubbleGrid with 3D tetrahedral organization</li>
 *   <li>Leverages EnhancedBubble's internal Tetree for entity management</li>
 *   <li>Maintains spatial index for BubbleLocation lookups</li>
 *   <li>Supports variable neighbor counts (4-12, not fixed 8)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class TetreeBubbleGrid {

    private final Map<TetreeKey<?>, EnhancedBubble> bubblesByKey;
    private final Tetree<StringEntityID, BubbleLocation> spatialIndex;
    private final TetreeNeighborFinder neighborFinder;
    private final byte maxLevel;

    /**
     * Create a new TetreeBubbleGrid.
     *
     * @param maxLevel Maximum refinement level for bubble distribution
     */
    public TetreeBubbleGrid(byte maxLevel) {
        if (maxLevel < 0 || maxLevel > 21) {
            throw new IllegalArgumentException("Max level must be 0-21, got: " + maxLevel);
        }

        this.maxLevel = maxLevel;
        this.bubblesByKey = new ConcurrentHashMap<>();
        this.spatialIndex = new Tetree<>(new StringEntityIDGenerator(), 100, maxLevel);
        this.neighborFinder = new TetreeNeighborFinder(spatialIndex);
    }

    /**
     * Create bubbles distributed throughout the tree at various levels.
     * <p>
     * Distribution Strategy:
     * <ul>
     *   <li>Distribute evenly across levels 0 to maxLevel</li>
     *   <li>Example: 9 bubbles with maxLevel=1 → 1 at L0, 8 at L1</li>
     *   <li>Each bubble gets a unique TetreeKey at its assigned level</li>
     * </ul>
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Determine number of levels to use (min(maxLevel+1, count))</li>
     *   <li>Distribute bubbles across levels using round-robin</li>
     *   <li>For each bubble, pick a distinct tetrahedron at that level</li>
     *   <li>Create EnhancedBubble with TetreeKey and BubbleLocation</li>
     * </ol>
     *
     * @param count      Number of bubbles to create
     * @param maxLevel   Maximum tree level for distribution
     * @param targetFrameMs Target frame time budget for each bubble
     * @throws IllegalArgumentException if count <= 0 or maxLevel invalid
     */
    public void createBubbles(int count, byte maxLevel, long targetFrameMs) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive, got: " + count);
        }
        if (maxLevel < 0 || maxLevel > 21) {
            throw new IllegalArgumentException("Max level must be 0-21, got: " + maxLevel);
        }
        if (targetFrameMs <= 0) {
            throw new IllegalArgumentException("Target frame time must be positive, got: " + targetFrameMs);
        }

        // Clear existing bubbles
        bubblesByKey.clear();

        // Determine number of levels to use
        int numLevels = Math.min(maxLevel + 1, count);

        // Calculate bubbles per level
        int bubblesPerLevel = count / numLevels;
        int remainder = count % numLevels;

        // Track used keys to avoid duplicates
        var usedKeys = new HashSet<TetreeKey<?>>();

        int bubbleIndex = 0;
        int createdCount = 0;

        for (byte level = 0; level < numLevels && createdCount < count; level++) {
            // Distribute remainder across first few levels
            int bubblesAtThisLevel = bubblesPerLevel + (level < remainder ? 1 : 0);

            for (int i = 0; i < bubblesAtThisLevel && createdCount < count; i++) {
                // Generate a valid Tet at this level by creating child subdivisions
                // Start from root and subdivide to desired level
                Tet tet = createTetAtLevel(level, bubbleIndex);

                // Get TetreeKey from the Tet (guaranteed valid)
                var key = tet.tmIndex();

                // Skip if we've already used this key (duplicate)
                if (usedKeys.contains(key)) {
                    bubbleIndex++;
                    continue;
                }

                usedKeys.add(key);

                // Create EnhancedBubble
                var bubble = new EnhancedBubble(
                    UUID.randomUUID(),
                    level,
                    targetFrameMs
                );

                // Create BubbleLocation
                var bounds = BubbleBounds.fromTetreeKey(key);
                var location = new BubbleLocation(key, bounds);

                // Register in maps
                bubblesByKey.put(key, bubble);

                // Add to spatial index
                var coords = tet.coordinates();
                spatialIndex.insert(
                    new StringEntityID(bubble.id().toString()),
                    new javax.vecmath.Point3f(coords[0].x, coords[0].y, coords[0].z),
                    level,
                    location
                );

                bubbleIndex++;
                createdCount++;
            }
        }
    }

    /**
     * Create a valid Tet at a specific level by subdividing from root.
     * This ensures all tetrahedra have valid types (0-5).
     *
     * @param level Target level
     * @param index Index at that level (which child path to take)
     * @return Valid Tet at the specified level
     */
    private Tet createTetAtLevel(byte level, int index) {
        if (level == 0) {
            // Root tetrahedron
            return new Tet(0, 0, 0, (byte) 0, (byte) 0);
        }

        // Start from root and subdivide
        var current = new Tet(0, 0, 0, (byte) 0, (byte) 0);

        // Encode index into path (8 children per level)
        int remainingIndex = index;

        // Subdivide down to target level
        for (byte l = 1; l <= level; l++) {
            // Extract child index for this level from remaining index
            int childIndex = remainingIndex % 8;
            remainingIndex = remainingIndex / 8;

            current = current.child(childIndex);
        }

        return current;
    }

    /**
     * Get the bubble at a specific TetreeKey.
     *
     * @param key TetreeKey to look up
     * @return EnhancedBubble at that key
     * @throws NoSuchElementException if no bubble exists at that key
     * @throws NullPointerException   if key is null
     */
    public EnhancedBubble getBubble(TetreeKey<?> key) {
        Objects.requireNonNull(key, "TetreeKey cannot be null");

        var bubble = bubblesByKey.get(key);
        if (bubble == null) {
            throw new NoSuchElementException("No bubble found at key: " + key);
        }
        return bubble;
    }

    /**
     * Get all topological neighbors of a bubble.
     * <p>
     * Returns EnhancedBubble objects for all neighboring TetreeKeys.
     * Neighbor count varies (4-12) based on tree level and position.
     *
     * @param key TetreeKey to find neighbors for
     * @return Set of neighboring EnhancedBubbles
     * @throws NoSuchElementException if no bubble exists at key
     * @throws NullPointerException   if key is null
     */
    public Set<EnhancedBubble> getNeighbors(TetreeKey<?> key) {
        Objects.requireNonNull(key, "TetreeKey cannot be null");

        // Verify key exists
        if (!bubblesByKey.containsKey(key)) {
            throw new NoSuchElementException("No bubble found at key: " + key);
        }

        // Find neighbor keys
        var neighborKeys = neighborFinder.findNeighbors(key);

        // Convert to EnhancedBubbles (only include registered bubbles)
        var neighbors = new HashSet<EnhancedBubble>();
        for (var neighborKey : neighborKeys) {
            var neighbor = bubblesByKey.get(neighborKey);
            if (neighbor != null) {
                neighbors.add(neighbor);
            }
        }

        return neighbors;
    }

    /**
     * Get boundary neighbors for ghost synchronization.
     * <p>
     * Returns bubbles whose bounds overlap with the given location's bounds.
     * This is used to discover which bubbles need to receive ghost entities.
     *
     * @param loc BubbleLocation to find boundary neighbors for
     * @return Set of EnhancedBubbles with overlapping bounds
     * @throws NullPointerException if loc is null
     */
    public Set<EnhancedBubble> getBoundaryNeighbors(BubbleLocation loc) {
        Objects.requireNonNull(loc, "BubbleLocation cannot be null");

        // Find boundary neighbor keys (using a large radius for now)
        var neighborKeys = neighborFinder.findBoundaryNeighbors(loc, Float.MAX_VALUE, bubblesByKey);

        // Convert to EnhancedBubbles
        var neighbors = new HashSet<EnhancedBubble>();
        for (var neighborKey : neighborKeys) {
            var neighbor = bubblesByKey.get(neighborKey);
            if (neighbor != null) {
                neighbors.add(neighbor);
            }
        }

        return neighbors;
    }

    /**
     * Update a bubble's adaptive bounds.
     * <p>
     * Call this when entities enter/leave a bubble and bounds need recalculation.
     *
     * @param key       TetreeKey of bubble to update
     * @param newBounds New bounds to apply
     * @throws NoSuchElementException if no bubble exists at key
     * @throws NullPointerException   if key or newBounds is null
     */
    public void updateBubbleBounds(TetreeKey<?> key, BubbleBounds newBounds) {
        Objects.requireNonNull(key, "TetreeKey cannot be null");
        Objects.requireNonNull(newBounds, "New bounds cannot be null");

        var bubble = bubblesByKey.get(key);
        if (bubble == null) {
            throw new NoSuchElementException("No bubble found at key: " + key);
        }

        // EnhancedBubble manages its own bounds internally via recalculateBounds()
        // This method is a placeholder for future bounds synchronization
        bubble.recalculateBounds();
    }

    /**
     * Get all bubbles in the grid.
     *
     * @return Collection of all EnhancedBubbles
     */
    public Collection<EnhancedBubble> getAllBubbles() {
        return new ArrayList<>(bubblesByKey.values());
    }

    /**
     * Get the number of bubbles in the grid.
     *
     * @return Bubble count
     */
    public int getBubbleCount() {
        return bubblesByKey.size();
    }

    /**
     * Check if a bubble exists at a given key.
     *
     * @param key TetreeKey to check
     * @return true if bubble exists, false otherwise
     */
    public boolean containsBubble(TetreeKey<?> key) {
        return bubblesByKey.containsKey(key);
    }

    /**
     * Get the TetreeNeighborFinder for direct access.
     *
     * @return TetreeNeighborFinder instance
     */
    public TetreeNeighborFinder getNeighborFinder() {
        return neighborFinder;
    }

    /**
     * Get the spatial index for direct access.
     *
     * @return Tetree spatial index
     */
    public Tetree<StringEntityID, BubbleLocation> getSpatialIndex() {
        return spatialIndex;
    }

    /**
     * Clear all bubbles from the grid.
     */
    public void clear() {
        bubblesByKey.clear();
        neighborFinder.clearCache();
    }
}
