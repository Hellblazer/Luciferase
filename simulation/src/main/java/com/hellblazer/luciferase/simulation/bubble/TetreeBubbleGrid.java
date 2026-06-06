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

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
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
     * The single level at which all bubbles reside when this grid was built as a spatial partition
     * (RDR-015 AC2). {@code -1} means "not a single-level partition" — the grid is empty or was built
     * via the legacy mixed-level {@link #createBubbles(int, byte, long)}. Routing/distribution query
     * directly at this level when it is {@code > 0}, instead of scanning levels.
     */
    private volatile byte partitionLevel = -1;

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

        // Clear existing bubbles. Legacy mixed-level distribution is NOT a single-level partition.
        bubblesByKey.clear();
        partitionLevel = -1;

        // Determine number of levels to use
        int numLevels = Math.min(maxLevel + 1, count);

        // Calculate bubbles per level
        int bubblesPerLevel = count / numLevels;
        int remainder = count % numLevels;

        // Track used keys to avoid duplicates
        var usedKeys = new HashSet<TetreeKey<?>>();

        int createdCount = 0;

        for (byte level = 0; level < numLevels && createdCount < count; level++) {
            // Distribute remainder across first few levels
            int bubblesAtThisLevel = bubblesPerLevel + (level < remainder ? 1 : 0);

            // Level 0 can only have 1 bubble maximum (hierarchical root)
            if (level == 0) {
                bubblesAtThisLevel = Math.min(bubblesAtThisLevel, 1);
            }

            // Use a per-level index to avoid collisions
            int levelIndex = 0;

            while (levelIndex < bubblesAtThisLevel && createdCount < count) {
                // Generate a valid Tet at this level using levelIndex
                Tet tet = createTetAtLevel(level, levelIndex);

                // Get TetreeKey from the Tet (guaranteed valid)
                var key = tet.tmIndex();

                // Skip if we've already used this key (shouldn't happen with per-level index)
                if (usedKeys.contains(key)) {
                    levelIndex++;
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

                // Record the fixed registration cell on the bubble.
                bubble.setSpatialKey(key);

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

                levelIndex++;
                createdCount++;
            }
        }
    }

    /**
     * Build the bubble grid as a SINGLE-LEVEL adjacent spatial partition tiling the
     * {@link WorldBounds} domain (RDR-015 AC2, Option B).
     * <p>
     * Unlike the legacy {@link #createBubbles(int, byte, long)} — which distributes bubbles across
     * <em>mixed</em> tree levels (an L0 root catch-all plus nested children) and is therefore not a
     * spatial partition — this method emits a set of <em>same-level</em>, face-adjacent tetrahedra
     * that tile the world domain, so an entity crossing a bubble face lands in a real neighbor bubble
     * and the migration path is well-defined.
     * <p>
     * Coordinates are placed directly into the Tetree absolute integer-coordinate space without
     * rescaling (RDR-003 / RDR-015 F5): a world coordinate {@code w} is the Tetree coordinate {@code w}.
     * <p>
     * <b>Level selection.</b> The partition level {@code L} is the finest level whose cell-edge is no
     * coarser than the requested granularity: {@code lengthAtLevel(L) <= WorldBounds.size() / cbrt(N)}.
     * {@code L} is clamped to {@code [1, MAX_REFINEMENT_LEVEL]} — never the L0 root.
     * <p>
     * <b>Seeding + tiling.</b> Seeds from the level-{@code L} tet containing the world centre, then
     * BFS over <b>same-level face neighbors</b>, including a neighbor iff its centroid lies inside the
     * world bounds. Adjacency is confirmed by <b>involution reciprocity</b>
     * ({@code faceNeighbor(faceNeighbor(t,f).face()).tet() == t}), never a shared-vertex count: the
     * Bey-SFC face neighbor is non-conforming (shares 0–3 vertices — see CLAUDE.md). The in-bounds
     * level-{@code L} tet set is finite, so BFS terminates.
     *
     * @param targetBubbleCount granularity hint {@code N} (drives level selection; the realized count
     *                          is the number of in-bounds level-{@code L} tets, which may exceed {@code N})
     * @param worldBounds       the entity world domain to tile
     * @param targetFrameMs     per-bubble frame-time budget
     * @return the chosen partition level {@code L}
     * @throws IllegalArgumentException if {@code targetBubbleCount <= 0} or {@code targetFrameMs <= 0}
     * @throws NullPointerException     if {@code worldBounds} is null
     */
    public byte createBubbles(int targetBubbleCount, WorldBounds worldBounds, long targetFrameMs) {
        if (targetBubbleCount <= 0) {
            throw new IllegalArgumentException("Target bubble count must be positive, got: " + targetBubbleCount);
        }
        Objects.requireNonNull(worldBounds, "WorldBounds cannot be null");
        if (targetFrameMs <= 0) {
            throw new IllegalArgumentException("Target frame time must be positive, got: " + targetFrameMs);
        }

        var level = choosePartitionLevel(worldBounds, targetBubbleCount);

        // Seed from the level-L tet containing the world centre.
        var centre = worldBounds.center();
        var seed = Tet.locatePointBeyRefinementFromRoot(centre, centre, centre, level);

        // BFS over same-level reciprocal face neighbors whose centroid lies inside the world domain.
        var partition = new LinkedHashMap<TetreeKey<?>, Tet>();
        var queue = new ArrayDeque<Tet>();
        partition.put(seed.tmIndex(), seed);
        queue.add(seed);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (int face = 0; face < 4; face++) {
                var fn = current.faceNeighbor(face);
                if (fn == null) {
                    continue; // domain boundary
                }
                // Involution reciprocity: the dual face must point back to current.
                var back = fn.tet().faceNeighbor(fn.face());
                if (back == null || !current.equals(back.tet())) {
                    continue;
                }
                var neighbor = fn.tet();
                if (!centroidInBounds(neighbor, worldBounds)) {
                    continue;
                }
                var key = neighbor.tmIndex();
                if (partition.containsKey(key)) {
                    continue;
                }
                partition.put(key, neighbor);
                queue.add(neighbor);
            }
        }

        // Materialize the partition.
        bubblesByKey.clear();
        neighborFinder.clearCache();
        for (var entry : partition.entrySet()) {
            var bubble = new EnhancedBubble(UUID.randomUUID(), level, targetFrameMs);
            addBubble(bubble, entry.getKey());
        }

        partitionLevel = level;
        return level;
    }

    /**
     * The single level at which all bubbles reside when this grid is a spatial partition (RDR-015 AC2),
     * or {@code -1} if the grid is empty or was built via the legacy mixed-level
     * {@link #createBubbles(int, byte, long)}. Routing and distribution query the Tetree directly at
     * this level instead of scanning a level range.
     *
     * @return the partition level {@code L > 0}, or {@code -1} if not a single-level partition
     */
    public byte getPartitionLevel() {
        return partitionLevel;
    }

    /**
     * Choose the finest partition level whose cell-edge is no coarser than
     * {@code WorldBounds.size() / cbrt(targetBubbleCount)} (RDR-015 AC2). Clamped to
     * {@code [1, MAX_REFINEMENT_LEVEL]} so the partition is never the L0 root.
     */
    private static byte choosePartitionLevel(WorldBounds worldBounds, int targetBubbleCount) {
        double cellTarget = worldBounds.size() / Math.cbrt(targetBubbleCount);
        byte maxLevel = Constants.getMaxRefinementLevel();
        // lengthAtLevel(L) = 2^(maxLevel - L); want 2^(maxLevel - L) <= cellTarget.
        for (byte level = 1; level < maxLevel; level++) {
            if (Constants.lengthAtLevel(level) <= cellTarget) {
                return level;
            }
        }
        return maxLevel;
    }

    /** True iff the tetrahedral centroid of {@code tet} lies within the world bounds (raw coordinates). */
    private static boolean centroidInBounds(Tet tet, WorldBounds worldBounds) {
        var c = tet.coordinates();
        double cx = (c[0].x + c[1].x + c[2].x + c[3].x) / 4.0;
        double cy = (c[0].y + c[1].y + c[2].y + c[3].y) / 4.0;
        double cz = (c[0].z + c[1].z + c[2].z + c[3].z) / 4.0;
        return worldBounds.contains((float) cx) && worldBounds.contains((float) cy)
               && worldBounds.contains((float) cz);
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
            // Hierarchical Tetree: Root level can only have ONE tetrahedron (type 0 at origin)
            // For S0-S5 forest approach, use CubeForest instead
            if (index > 0) {
                throw new IllegalArgumentException(
                    "Level 0 can only have 1 bubble (type 0 at origin). " +
                    "For multiple root bubbles, use CubeForest or level 1+. Got index: " + index
                );
            }
            return new Tet(0, 0, 0, (byte) 0, (byte) 0);
        }

        // For level 1+: Start from S0 root and subdivide
        var current = new Tet(0, 0, 0, (byte) 0, (byte) 0);

        // Subdivide down to target level using index as path
        for (byte l = 1; l <= level; l++) {
            // Extract child index for this level from index
            int childIndex = (index >> (3 * (level - l))) & 0x7; // Extract 3 bits for this level
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
     * Get a bubble by its UUID identifier.
     * <p>
     * Searches through all bubbles to find one with matching UUID.
     * This is less efficient than getBubble(TetreeKey) but useful
     * for scenarios where only the UUID is known.
     *
     * @param bubbleId UUID to search for
     * @return EnhancedBubble with matching ID, or null if not found
     * @throws NullPointerException if bubbleId is null
     */
    public EnhancedBubble getBubbleById(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");

        for (var bubble : bubblesByKey.values()) {
            if (bubble.id().equals(bubbleId)) {
                return bubble;
            }
        }
        return null;
    }

    /**
     * Get the actual spatial-index registration key for a bubble by its UUID.
     * <p>
     * This is the key under which the bubble is stored in {@code bubblesByKey} —
     * i.e. the exact key {@link #addBubble(EnhancedBubble, TetreeKey)} would need
     * to re-insert the bubble at the same spatial position. Rollback bookkeeping
     * MUST use this key rather than a bubble's dynamically-tracked bounds key
     * (e.g. {@code BubbleBounds.rootKey()}), which is the level-root key and not
     * the registration key (Luciferase-0frcy.122).
     *
     * @param bubbleId UUID to look up
     * @return the registration {@link TetreeKey}, or {@code null} if the bubble is not in the grid
     * @throws NullPointerException if bubbleId is null
     */
    public TetreeKey<?> getKeyForBubble(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");

        for (var entry : bubblesByKey.entrySet()) {
            if (entry.getValue().id().equals(bubbleId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Get topological neighbors of a bubble by its UUID.
     * <p>
     * Convenience method that first looks up the bubble by UUID,
     * then returns its neighbors. Returns bubble UUIDs for consistency.
     *
     * @param bubbleId UUID of bubble to find neighbors for
     * @return Set of neighboring bubble UUIDs
     * @throws NoSuchElementException if no bubble with given UUID exists
     * @throws NullPointerException   if bubbleId is null
     */
    public Set<UUID> getNeighbors(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");

        // Find the bubble and its key
        TetreeKey<?> key = null;
        for (var entry : bubblesByKey.entrySet()) {
            if (entry.getValue().id().equals(bubbleId)) {
                key = entry.getKey();
                break;
            }
        }

        if (key == null) {
            throw new NoSuchElementException("No bubble found with ID: " + bubbleId);
        }

        // Get neighbors using the key
        var neighborBubbles = getNeighbors(key);

        // Convert to UUIDs
        var neighborIds = new HashSet<UUID>();
        for (var neighbor : neighborBubbles) {
            neighborIds.add(neighbor.id());
        }

        return neighborIds;
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
     * Get all bubbles with their TetreeKeys.
     * <p>
     * Returns an unmodifiable view of the key-to-bubble mapping for iteration.
     * This is useful for queries that need both the bubble and its spatial key.
     *
     * @return Unmodifiable map of TetreeKey to EnhancedBubble
     */
    public Map<TetreeKey<?>, EnhancedBubble> getBubblesWithKeys() {
        return Collections.unmodifiableMap(bubblesByKey);
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
     * Add a dynamically created bubble to the grid.
     * <p>
     * This method registers a bubble that was created outside the normal
     * createBubbles() flow, such as during a split operation. The bubble
     * and its spatial key are added to the internal data structures.
     * <p>
     * The TetreeKey should be computed based on the bubble's entity
     * distribution to ensure proper spatial indexing.
     *
     * @param bubble The EnhancedBubble to add
     * @param key    The TetreeKey for spatial indexing
     * @throws NullPointerException     if bubble or key is null
     * @throws IllegalArgumentException if a bubble already exists at this key
     */
    public void addBubble(EnhancedBubble bubble, TetreeKey<?> key) {
        Objects.requireNonNull(bubble, "Bubble cannot be null");
        Objects.requireNonNull(key, "TetreeKey cannot be null");

        if (bubblesByKey.containsKey(key)) {
            throw new IllegalArgumentException("Bubble already exists at key: " + key);
        }

        // Record the fixed registration cell on the bubble so migration containment can test against it.
        bubble.setSpatialKey(key);

        // Add to key map
        bubblesByKey.put(key, bubble);

        // Add to spatial index
        var tet = key.toTet();
        var coords = tet.coordinates();
        var location = new BubbleLocation(key, BubbleBounds.fromTetreeKey(key));

        spatialIndex.insert(
            new StringEntityID(bubble.id().toString()),
            new javax.vecmath.Point3f(coords[0].x, coords[0].y, coords[0].z),
            tet.l,
            location
        );

        // Clear neighbor cache to force recomputation
        neighborFinder.clearCache();
    }

    /**
     * Remove a bubble from the grid by its UUID.
     * <p>
     * This method removes a bubble that was previously added to the grid,
     * such as during a merge operation. The bubble is removed from all
     * internal data structures and the neighbor cache is cleared.
     * <p>
     * Note: This method does NOT check if the bubble contains entities.
     * Callers must ensure all entities are removed or migrated before
     * calling this method.
     *
     * @param bubbleId The UUID of the bubble to remove
     * @return true if the bubble was removed, false if not found
     * @throws NullPointerException if bubbleId is null
     */
    public boolean removeBubble(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "Bubble ID cannot be null");

        // Find the bubble by ID
        TetreeKey<?> keyToRemove = null;
        for (var entry : bubblesByKey.entrySet()) {
            if (entry.getValue().id().equals(bubbleId)) {
                keyToRemove = entry.getKey();
                break;
            }
        }

        if (keyToRemove == null) {
            return false;
        }

        // Remove from key map
        bubblesByKey.remove(keyToRemove);

        // Note: Tetree spatial index does not support remove() operation.
        // The stale entry will not affect correctness since we check bubblesByKey
        // for actual bubble existence in all query methods.

        // Clear neighbor cache to force recomputation
        neighborFinder.clearCache();

        return true;
    }

    /**
     * Clear all bubbles from the grid.
     */
    public void clear() {
        bubblesByKey.clear();
        neighborFinder.clearCache();
        partitionLevel = -1;
    }
}
