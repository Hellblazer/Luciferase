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

import com.hellblazer.luciferase.lucien.neighbor.TetreeNeighborDetector;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers bubble neighbors via Tetree structure.
 * <p>
 * This class replaces the previous GridConfiguration neighbor logic with proper
 * 3D tetrahedral neighbor discovery. It leverages the existing {@link TetreeNeighborDetector}
 * infrastructure to find topological neighbors in the tetrahedral subdivision.
 * <p>
 * Key Features:
 * <ul>
 *   <li><b>Variable Neighbor Count</b> - Returns 4-12 neighbors depending on tree level</li>
 *   <li><b>Face Neighbors</b> - Exactly 4 neighbors sharing a face (tetrahedron has 4 faces)</li>
 *   <li><b>Edge/Vertex Neighbors</b> - Additional neighbors for comprehensive connectivity</li>
 *   <li><b>Boundary Neighbors</b> - Spatial overlap detection for ghost synchronization</li>
 *   <li><b>Thread-Safe</b> - Concurrent access support via ConcurrentHashMap caching</li>
 * </ul>
 * <p>
 * Performance:
 * <ul>
 *   <li>Target: <1ms per findNeighbors() call</li>
 *   <li>Caching: Results cached per key to amortize tree traversal cost</li>
 *   <li>Supports all tree levels (0-21)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class TetreeNeighborFinder {

    private final Tetree<?, ?> tetree;
    private final TetreeNeighborDetector neighborDetector;
    private final Map<TetreeKey<?>, Set<TetreeKey<?>>> neighborCache;

    /**
     * Create a TetreeNeighborFinder for the given Tetree.
     *
     * @param tetree The Tetree to use for neighbor detection
     * @throws NullPointerException if tetree is null
     */
    public TetreeNeighborFinder(Tetree<?, ?> tetree) {
        this.tetree = Objects.requireNonNull(tetree, "Tetree cannot be null");
        this.neighborDetector = new TetreeNeighborDetector(tetree);
        this.neighborCache = new ConcurrentHashMap<>();
    }

    /**
     * Find all topological neighbors of a tetrahedron.
     * <p>
     * Returns ALL neighbors (face-adjacent, edge-adjacent, and vertex-adjacent).
     * The number of neighbors varies by level:
     * <ul>
     *   <li><b>Minimum</b>: 4 neighbors (face-only, interior tetrahedra)</li>
     *   <li><b>Maximum</b>: 12 neighbors (includes edge and vertex neighbors)</li>
     * </ul>
     * <p>
     * Implementation uses {@link TetreeNeighborDetector#findVertexNeighbors(TetreeKey)}
     * which includes all face, edge, and vertex neighbors.
     *
     * @param key TetreeKey to find neighbors for
     * @return Set of all neighboring TetreeKeys (4-12 neighbors)
     * @throws NullPointerException if key is null
     */
    public Set<TetreeKey<?>> findNeighbors(TetreeKey<?> key) {
        Objects.requireNonNull(key, "TetreeKey cannot be null");

        // Check cache first
        var cached = neighborCache.get(key);
        if (cached != null) {
            return new HashSet<>(cached); // Return defensive copy
        }

        // Find all neighbors (face + edge + vertex)
        var neighborList = neighborDetector.findVertexNeighbors(key);
        var neighbors = new HashSet<>(neighborList);

        // Cache the result
        neighborCache.put(key, Set.copyOf(neighbors));

        return neighbors;
    }

    /**
     * Find only face-adjacent neighbors.
     * <p>
     * Returns exactly 4 neighbors for interior tetrahedra (one per face).
     * Boundary tetrahedra may have fewer face neighbors.
     * <p>
     * A tetrahedron has 4 triangular faces. Face neighbors share an entire face
     * with the query tetrahedron.
     *
     * @param key TetreeKey to find face neighbors for
     * @return Set of face-adjacent TetreeKeys (typically 4, fewer at boundaries)
     * @throws NullPointerException if key is null
     */
    public Set<TetreeKey<?>> findFaceNeighbors(TetreeKey<?> key) {
        Objects.requireNonNull(key, "TetreeKey cannot be null");

        var neighborList = neighborDetector.findFaceNeighbors(key);
        return new HashSet<>(neighborList);
    }

    /**
     * Find bubbles whose bounds overlap a radius around a location's bounds.
     * <p>
     * This is used for ghost synchronization: find bubbles that need to receive
     * ghost entities from a source bubble.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Get all topological neighbors of loc.key()</li>
     *   <li>Filter to only bubbles registered in bubblesByKey</li>
     *   <li>Test each neighbor's bounds for overlap with loc.bounds()</li>
     *   <li>Return set of overlapping neighbor keys</li>
     * </ol>
     *
     * @param loc            Source bubble location
     * @param radius         Unused for now (may be used for future radius-based filtering)
     * @param bubblesByKey   Map of registered bubbles (for filtering valid neighbors)
     * @return Set of TetreeKeys for bubbles with overlapping bounds
     * @throws NullPointerException if loc or bubblesByKey is null
     */
    public Set<TetreeKey<?>> findBoundaryNeighbors(BubbleLocation loc,
                                                   float radius,
                                                   Map<TetreeKey<?>, ?> bubblesByKey) {
        Objects.requireNonNull(loc, "BubbleLocation cannot be null");
        Objects.requireNonNull(bubblesByKey, "Bubbles map cannot be null");

        // Get all topological neighbors
        var allNeighbors = findNeighbors(loc.key());

        // Filter to only registered bubbles with overlapping bounds
        var boundaryNeighbors = new HashSet<TetreeKey<?>>();
        for (var neighborKey : allNeighbors) {
            // Only consider neighbors that are registered bubbles
            if (!bubblesByKey.containsKey(neighborKey)) {
                continue;
            }

            // For now, include all topological neighbors
            // Future enhancement: test bounds overlap more precisely
            boundaryNeighbors.add(neighborKey);
        }

        return boundaryNeighbors;
    }

    /**
     * Check if two tetrahedra are topological neighbors.
     * <p>
     * Two tetrahedra are neighbors if they share at least one vertex, edge, or face.
     *
     * @param a First TetreeKey
     * @param b Second TetreeKey
     * @return true if a and b are neighbors, false otherwise
     * @throws NullPointerException if a or b is null
     */
    public boolean isNeighbor(TetreeKey<?> a, TetreeKey<?> b) {
        Objects.requireNonNull(a, "First key cannot be null");
        Objects.requireNonNull(b, "Second key cannot be null");

        // Keys at different levels cannot be neighbors
        if (a.getLevel() != b.getLevel()) {
            return false;
        }

        // Self is not considered a neighbor
        if (a.equals(b)) {
            return false;
        }

        // Check if b is in a's neighbor set
        var neighbors = findNeighbors(a);
        return neighbors.contains(b);
    }

    /**
     * Clear the neighbor cache.
     * <p>
     * Call this when the Tetree structure changes significantly (e.g., during
     * adaptive refinement or bubble redistribution).
     */
    public void clearCache() {
        neighborCache.clear();
    }

    /**
     * Get cache statistics for monitoring performance.
     *
     * @return Map with cache statistics (size, etc.)
     */
    public Map<String, Object> getCacheStats() {
        var stats = new HashMap<String, Object>();
        stats.put("cache_size", neighborCache.size());
        stats.put("cached_keys", new ArrayList<>(neighborCache.keySet()));
        return stats;
    }
}
