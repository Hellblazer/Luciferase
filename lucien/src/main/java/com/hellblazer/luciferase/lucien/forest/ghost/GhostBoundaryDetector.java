/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.forest.ghost;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Element-level ghost boundary detection for spatial indices.
 *
 * <p>Identifies the local <em>partition-boundary</em> elements (those with a face neighbor owned by a different
 * rank) that seed the distributed ghost layer. This class superseded the earlier element- and tree-level ghost
 * managers, which have been removed; the dead tree-level ghost-zone coordination it briefly carried was deleted
 * in Luciferase-1q7u (it had no callers).
 *
 * <p><strong>Ghost Algorithms</strong>:
 * <ul>
 *   <li>MINIMAL: Direct (face) neighbors only (default — Luciferase-9m31)</li>
 *   <li>DEEP_COVERAGE: Direct + second-level neighbors (depth-2 BFS; renamed from CONSERVATIVE)</li>
 *   <li>AGGRESSIVE: 3-level deep neighbor search</li>
 *   <li>ADAPTIVE: Depth-2 coverage with usage statistics</li>
 *   <li>CUSTOM: Pluggable strategy pattern</li>
 * </ul>
 *
 * <p><strong>Thread Safety</strong>: Uses ConcurrentHashMap for optimistic concurrency.
 *
 * @param <Key> the type of spatial key
 * @param <ID> the type of entity identifier
 * @param <Content> the type of content stored in entities
 *
 * @author Hal Hildebrand
 */
public class GhostBoundaryDetector<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(GhostBoundaryDetector.class);

    // ========================================
    // Element-Level Detection
    // ========================================

    private final SpatialIndex<Key, ID, Content> spatialIndex;
    private final NeighborDetector<Key> neighborDetector;
    private final GhostLayer<Key, ID, Content> ghostLayer;
    private final GhostAlgorithm ghostAlgorithm;

    // Track boundary elements for efficient ghost detection
    private final Set<Key> boundaryElements;

    // Track which elements have been processed for ghosts
    private final Set<Key> processedElements;

    // Owner information for distributed support
    private final Map<Key, Integer> elementOwners;

    // Rank of the local partition (Luciferase-8ggq). Defaults to 0; injected via setCurrentRank() from
    // GhostCoordinator.setupDistributedGhosts once the rank is known. A neighbor is "remote" (needs a ghost)
    // when its owner differs from this rank — so single-process use (rank 0) preserves the original != 0 guard.
    private volatile int currentRank = 0;

    // ========================================
    // Constructors
    // ========================================

    /**
     * Create a ghost boundary detector for element-level (partition-boundary) detection.
     *
     * @param spatialIndex the spatial index
     * @param neighborDetector the neighbor detector
     * @param ghostType the type of ghosts to create
     * @param ghostAlgorithm the ghost creation algorithm
     */
    public GhostBoundaryDetector(SpatialIndex<Key, ID, Content> spatialIndex,
                                 NeighborDetector<Key> neighborDetector,
                                 GhostType ghostType,
                                 GhostAlgorithm ghostAlgorithm) {
        this.spatialIndex = spatialIndex;
        this.neighborDetector = neighborDetector;
        this.ghostLayer = spatialIndex != null ? new GhostLayer<>(ghostType) : null;
        this.ghostAlgorithm = ghostAlgorithm;
        this.boundaryElements = ConcurrentHashMap.newKeySet();
        this.processedElements = ConcurrentHashMap.newKeySet();
        this.elementOwners = new ConcurrentHashMap<>();

        log.info("Created GhostBoundaryDetector: element-level={}, algorithm={}",
                spatialIndex != null, ghostAlgorithm);
    }

    // ========================================
    // Element-Level API
    // ========================================

    /**
     * Create ghost elements for the entire spatial index (element-level).
     */
    public void createGhostLayer() {
        if (spatialIndex == null) {
            log.warn("Cannot create ghost layer - spatial index not set");
            return;
        }

        log.info("Creating ghost layer with type: {}", ghostLayer.getGhostType());

        // Clear previous ghost data
        ghostLayer.clear();
        boundaryElements.clear();
        processedElements.clear();

        // Identify boundary elements
        identifyBoundaryElements();

        // Create ghosts for boundary elements
        for (var boundaryKey : boundaryElements) {
            createGhostsForElement(boundaryKey);
        }

        log.info("Created ghost layer with {} boundary elements and {} total ghosts",
                boundaryElements.size(), ghostLayer.getNumGhostElements());
    }

    /**
     * Update ghosts when an element is modified.
     *
     * @param key the spatial key of the modified element
     */
    public void updateElementGhosts(Key key) {
        if (spatialIndex == null) return;

        // Check if this element affects any ghosts
        if (isBoundaryElement(key) || affectsGhosts(key)) {
            // Remove old ghosts
            removeGhostsForElement(key);

            // Recreate ghosts
            createGhostsForElement(key);

            // Update ghosts in neighboring elements
            updateNeighborGhosts(key);
        }
    }

    /**
     * Get all boundary elements.
     *
     * @return set of boundary element keys
     */
    public Set<Key> getBoundaryElements() {
        return new HashSet<>(boundaryElements);
    }

    /**
     * Check if an element is at a boundary.
     *
     * @param key the spatial key
     * @return true if element is at boundary
     */
    public boolean isBoundaryElement(Key key) {
        return boundaryElements.contains(key);
    }

    /**
     * Get the ghost layer.
     *
     * @return the ghost layer
     */
    public GhostLayer<Key, ID, Content> getGhostLayer() {
        return ghostLayer;
    }

    /**
     * Set element owner information (for distributed support).
     *
     * @param key the spatial key
     * @param ownerRank the owner process rank
     */
    public void setElementOwner(Key key, int ownerRank) {
        elementOwners.put(key, ownerRank);
    }

    /**
     * Get element owner information.
     *
     * <p>Defaults to rank 0 when no owner was explicitly registered. Note that 0 is "local" only for a rank-0
     * process: a {@code currentRank > 0} process treats an unregistered owner (0) as <em>remote</em>. Callers
     * that classify partition seams ({@link #isPartitionBoundary}, {@link #createGhostsForElement}) therefore
     * first exclude locally-present keys (which this rank owns regardless of the map) before consulting this
     * default. Owner-map unification across the default-0 convention is Luciferase-9m31's concern.
     *
     * @param key the spatial key
     * @return the registered owner rank, or 0 if none was registered
     */
    public int getElementOwner(Key key) {
        return elementOwners.getOrDefault(key, 0);
    }

    /**
     * Number of elements with an explicitly-registered owner (Luciferase-9m31). The owner map is the single
     * source of truth shared with {@link DistributedGhostManager} after owner-map unification.
     *
     * @return the count of registered element owners
     */
    public int getTrackedOwnerCount() {
        return elementOwners.size();
    }

    /**
     * Clear all registered element owners (Luciferase-9m31), without touching boundary/processed state. Used by
     * {@link DistributedGhostManager#shutdown()} now that the manager delegates ownership to this detector.
     */
    public void clearElementOwners() {
        elementOwners.clear();
    }

    /**
     * Set the local partition rank (Luciferase-8ggq). A neighbor element is treated as remote — and thus gets a
     * ghost created — when its owner rank differs from this value. Injected by
     * {@code GhostCoordinator.setupDistributedGhosts} once the rank is known; defaults to 0 for single-process use.
     *
     * @param currentRank the rank of the local partition
     */
    public void setCurrentRank(int currentRank) {
        this.currentRank = currentRank;
    }

    /**
     * Get the local partition rank used by the remote-neighbor / ghost-creation guard.
     *
     * @return the current local partition rank (0 if not injected)
     */
    public int getCurrentRank() {
        return currentRank;
    }

    /**
     * The ghost-creation algorithm this detector uses (Luciferase-9m31). {@link GhostAlgorithm#MINIMAL} is the
     * default for the forest-level constructor (sufficient on 2:1-balanced meshes).
     *
     * @return the ghost algorithm
     */
    public GhostAlgorithm getGhostAlgorithm() {
        return ghostAlgorithm;
    }

    /**
     * Get statistics.
     *
     * @return map of statistics
     */
    public Map<String, Object> getStatistics() {
        var stats = new HashMap<String, Object>();
        if (spatialIndex != null) {
            stats.put("boundaryElements", boundaryElements.size());
            stats.put("processedElements", processedElements.size());
            stats.put("ghostElements", ghostLayer != null ? ghostLayer.getNumGhostElements() : 0);
            stats.put("ghostAlgorithm", ghostAlgorithm);
        }
        return stats;
    }

    /**
     * Clear all ghost data.
     */
    public void clear() {
        if (ghostLayer != null) {
            ghostLayer.clear();
        }
        boundaryElements.clear();
        processedElements.clear();
        elementOwners.clear();

        log.info("Cleared all ghost boundary data");
    }

    // ========================================
    // Private Element-Level Helper Methods
    // ========================================

    /**
     * Identify the local <em>partition-boundary</em> elements: those occupied by this rank that have at least one
     * face neighbor owned by a different rank (Luciferase-3uwx). This is the t8code ghost-v3 seed set —
     * partition seam, not domain edge.
     *
     * <p><b>Semantic change (Luciferase-3uwx, user-approved correctness+perf).</b> The previous implementation
     * flagged <em>domain</em>-boundary elements (via {@code neighborDetector.getBoundaryDirections}, a
     * coords-vs-MAX_COORD test), which never seeded ghosts for domain-interior partition-seam elements — a
     * correctness gap. The identified set is now exactly the partition seam, and is consistent with the
     * downstream {@link #createGhostsForElement} guard (a face neighbor with owner {@code != currentRank}).
     * Single-process use (one rank, no remote owners) therefore yields an empty boundary set, as it should.
     *
     * <p><b>Iteration strategy (Luciferase-3uwx).</b> A flat scan over the local elements with the
     * partition-boundary leaf test. In this single-index-per-rank model the local {@code spatialIndex} already
     * holds <em>only</em> this rank's elements, so the remote-subtree pruning t8code uses to avoid visiting
     * other ranks' elements is structurally already realized (other ranks' elements are simply absent). The
     * remaining t8code optimization — pruning local-<em>interior</em> subtrees to skip the face test on
     * elements all of whose neighbors are local — is deferred (Luciferase follow-up): it requires a sound
     * seam-face check at subtree granularity (a node's neighbor-dilated cube is not a single SFC-contiguous
     * range, so it cannot be bounded by {@code ownerOf} at the range endpoints alone). The reusable
     * primitives for that descent are in place: {@link SpatialKey#firstDescendantAtLevel(byte)} /
     * {@link SpatialKey#lastDescendantAtLevel(byte)} (S1), {@code ShapeWeightPartitioner.cutPoints/ownerOf}
     * (S2), and {@link SpatialIndex#spatialKeysInRange} (S3).
     */
    private void identifyBoundaryElements() {
        if (neighborDetector == null || spatialIndex == null) {
            log.warn("Cannot identify boundary elements - detector or index not set");
            return;
        }

        boundaryElements.clear();

        var spatialKeys = spatialIndex.getSpatialKeys();
        log.debug("Identifying boundary elements from {} total elements", spatialKeys.size());

        for (var key : spatialKeys) {
            if (isPartitionBoundary(key)) {
                boundaryElements.add(key);
            }
        }

        log.debug("Identified {} partition-boundary elements", boundaryElements.size());
    }

    /**
     * An occupied element is a partition-boundary element iff at least one of its face neighbors is
     * <em>absent from the local index</em> and owned by a rank other than {@link #currentRank}
     * (Luciferase-3uwx). A locally-present face neighbor is owned by this rank (it is in our index), so it is
     * skipped before the owner check — this mirrors the {@link #createGhostsForElement} guard
     * ({@code !containsSpatialKey(neighborKey)} then {@code ownerRank != currentRank}) exactly, so the
     * identified boundary set is precisely the set of elements that {@code createGhostsForElement} turns into
     * ghosts. Without the locally-present skip, a {@code currentRank > 0} process would spuriously flag every
     * interior element (a present neighbor with no explicit owner entry defaults to rank 0 ≠ currentRank), and
     * identify/create would disagree (flagged boundary, but no ghost emitted).
     */
    private boolean isPartitionBoundary(Key key) {
        if (neighborDetector == null) {
            return false;
        }
        for (var neighbor : neighborDetector.findFaceNeighbors(key)) {
            if (spatialIndex != null && spatialIndex.containsSpatialKey(neighbor)) {
                continue; // locally present ⇒ owned by this rank ⇒ not a seam (and creates no ghost)
            }
            if (getElementOwner(neighbor) != currentRank) {
                return true;
            }
        }
        return false;
    }

    private void createGhostsForElement(Key key) {
        if (processedElements.contains(key)) return;

        // Find neighbors based on algorithm
        var neighbors = findNeighborsForGhostCreation(key);

        for (var neighborKey : neighbors) {
            if (spatialIndex != null && !spatialIndex.containsSpatialKey(neighborKey)) {
                var ownerRank = getElementOwner(neighborKey);
                if (ownerRank != currentRank) {
                    createGhostElement(neighborKey, ownerRank);
                }
            }
        }

        processedElements.add(key);
    }

    private Set<Key> findNeighborsForGhostCreation(Key key) {
        if (neighborDetector == null || ghostLayer == null) return Collections.emptySet();

        var neighbors = new HashSet<Key>();
        var ghostType = ghostLayer.getGhostType();

        switch (ghostAlgorithm) {
            case MINIMAL -> {
                // Only direct neighbors
                neighbors.addAll(neighborDetector.findNeighbors(key, ghostType));
            }
            case DEEP_COVERAGE -> {
                // Direct + second-level neighbors
                var directNeighbors = neighborDetector.findNeighbors(key, ghostType);
                neighbors.addAll(directNeighbors);

                for (var neighbor : directNeighbors) {
                    neighbors.addAll(neighborDetector.findNeighbors(neighbor, ghostType));
                }
            }
            case AGGRESSIVE -> {
                // 3-level deep search
                var currentLevel = Set.of(key);
                var visited = new HashSet<Key>();

                for (int level = 0; level < 3; level++) {
                    var nextLevel = new HashSet<Key>();
                    for (var currentKey : currentLevel) {
                        if (!visited.contains(currentKey)) {
                            var levelNeighbors = neighborDetector.findNeighbors(currentKey, ghostType);
                            neighbors.addAll(levelNeighbors);
                            nextLevel.addAll(levelNeighbors);
                            visited.add(currentKey);
                        }
                    }
                    currentLevel = nextLevel;
                }
            }
            case ADAPTIVE, CUSTOM -> {
                // Depth-2 deep-coverage fallback
                var directNeighbors = neighborDetector.findNeighbors(key, ghostType);
                neighbors.addAll(directNeighbors);

                for (var neighbor : directNeighbors) {
                    neighbors.addAll(neighborDetector.findNeighbors(neighbor, ghostType));
                }
            }
        }

        return neighbors;
    }

    /**
     * Create a ghost element for a remote-owned neighbor that is absent from the local index.
     *
     * <p>Placeholder hook: the actual ghost entity data arrives asynchronously via gRPC
     * ({@link DistributedGhostManager}). This method is the single point where the local boundary scan
     * decides "this neighbor key, owned by {@code ownerRank}, is a ghost" — {@code protected} so an
     * integration test can observe the firing (RDR-010 pi1.5 Phase C, Luciferase-azwr), which is the
     * only way to assert the cross-shape ghost path end-to-end given the placeholder body.
     *
     * @param neighborKey the spatial key of the remote-owned neighbor
     * @param ownerRank    the owning process rank (always non-zero here)
     */
    protected void createGhostElement(Key neighborKey, int ownerRank) {
        // Placeholder ghost creation - actual data would come via gRPC
        log.trace("Creating placeholder ghost for key {} owned by rank {}", neighborKey, ownerRank);
    }

    private void removeGhostsForElement(Key key) {
        // Implementation depends on ghost layer API
    }

    private void updateNeighborGhosts(Key key) {
        if (neighborDetector == null || ghostLayer == null) return;

        var neighbors = neighborDetector.findNeighbors(key, ghostLayer.getGhostType());

        for (var neighbor : neighbors) {
            if (processedElements.contains(neighbor)) {
                processedElements.remove(neighbor);
                createGhostsForElement(neighbor);
            }
        }
    }

    private boolean affectsGhosts(Key key) {
        if (neighborDetector == null || ghostLayer == null) return false;

        var neighbors = neighborDetector.findNeighbors(key, ghostLayer.getGhostType());

        for (var neighbor : neighbors) {
            if (boundaryElements.contains(neighbor)) {
                return true;
            }
        }

        return false;
    }
}
