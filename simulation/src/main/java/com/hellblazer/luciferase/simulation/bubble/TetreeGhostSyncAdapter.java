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

import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.ghost.GhostBoundarySync;
import com.hellblazer.luciferase.simulation.ghost.GhostLayerHealth;
import com.hellblazer.luciferase.simulation.ghost.SimulationGhostEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter connecting GhostBoundarySync to tetrahedral bubble topology.
 * <p>
 * Integration Layer:
 * - Creates GhostBoundarySync instance for each bubble
 * - Configures ExternalBubbleTracker and GhostLayerHealth per bubble
 * - Wires ghostSender callback to in-memory channels (tetrahedral bubbles)
 * - Handles bucket-based ghost synchronization
 * <p>
 * Ghost Sync Flow:
 * 1. detectBoundaryEntities() - find entities near boundaries
 * 2. addGhost() - add ghost to GhostBoundarySync for neighbor
 * 3. onBucketComplete() - batch send ghosts to neighbors
 * 4. receiveGhosts() - receive ghost batch from neighbor
 * <p>
 * Key Differences from GridGhostSyncAdapter:
 * - Uses TetreeNeighborFinder instead of GridConfiguration
 * - Neighbor detection via bounds.overlaps() instead of distance calculation
 * - Supports variable neighbor count (4-12) instead of fixed 8
 * - Works with TetreeBubbleGrid instead of BubbleGrid
 *
 * @author hal.hildebrand
 */
public class TetreeGhostSyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(TetreeGhostSyncAdapter.class);

    /**
     * Area of Interest radius for boundary detection.
     * Entities within this radius of neighbor bounds trigger ghost creation.
     */
    private static final float AOI_RADIUS = 10.0f;

    private final TetreeBubbleGrid bubbleGrid;
    private final TetreeNeighborFinder neighborFinder;

    // Per-bubble ghost sync infrastructure
    private final Map<UUID, GhostBoundarySync<StringEntityID, Object>> ghostSyncByBubble;
    private final Map<UUID, ExternalBubbleTracker> trackerByBubble;
    private final Map<UUID, GhostLayerHealth> healthByBubble;

    // Ghost storage: bubbleId -> (entityId -> SimulationGhostEntity)
    private final Map<UUID, Map<String, SimulationGhostEntity<StringEntityID, Object>>> ghostsByBubble;

    /**
     * Create a ghost sync adapter for tetrahedral bubbles.
     *
     * @param bubbleGrid     Tetrahedral bubble grid topology
     * @param neighborFinder Tetree neighbor finder for topology discovery
     */
    public TetreeGhostSyncAdapter(TetreeBubbleGrid bubbleGrid, TetreeNeighborFinder neighborFinder) {
        this.bubbleGrid = Objects.requireNonNull(bubbleGrid, "BubbleGrid cannot be null");
        this.neighborFinder = Objects.requireNonNull(neighborFinder, "NeighborFinder cannot be null");

        this.ghostSyncByBubble = new ConcurrentHashMap<>();
        this.trackerByBubble = new ConcurrentHashMap<>();
        this.healthByBubble = new ConcurrentHashMap<>();
        this.ghostsByBubble = new ConcurrentHashMap<>();

        // Initialize ghost sync for all bubbles
        initializeGhostSync();

        log.info("TetreeGhostSyncAdapter initialized for {} bubbles", bubbleGrid.getBubbleCount());
    }

    /**
     * Process boundary entities and create ghosts for neighbors.
     * <p>
     * Called during simulation tick to detect entities near boundaries
     * and add ghosts to GhostBoundarySync for neighbor bubbles.
     *
     * @param currentBucket Current bucket number (simulation time)
     */
    public void processBoundaryEntities(long currentBucket) {
        for (var bubble : bubbleGrid.getAllBubbles()) {
            processBubbleBoundaryEntities(bubble, currentBucket);
        }
    }

    /**
     * Complete bucket processing and send ghost batches.
     * <p>
     * Called at end of simulation bucket to trigger ghost transmission.
     *
     * @param bucket Bucket that just completed
     */
    public void onBucketComplete(long bucket) {
        for (var entry : ghostSyncByBubble.entrySet()) {
            var bubbleId = entry.getKey();
            var ghostSync = entry.getValue();

            ghostSync.onBucketComplete(bucket);
        }

        // Clean up expired ghosts from storage (TTL enforcement)
        expireStaleGhosts(bucket);

        log.debug("Ghost sync bucket {} complete", bucket);
    }

    /**
     * Remove expired ghosts from storage.
     * <p>
     * Ghosts expire after GHOST_TTL_BUCKETS (5 buckets = 500ms @ 100ms/bucket).
     *
     * @param currentBucket Current bucket number
     */
    private void expireStaleGhosts(long currentBucket) {
        var expirationBucket = currentBucket - GhostBoundarySync.GHOST_TTL_BUCKETS;

        for (var ghostMap : ghostsByBubble.values()) {
            ghostMap.entrySet().removeIf(entry ->
                entry.getValue().bucket() < expirationBucket
            );
        }
    }

    /**
     * Get total ghost count across all bubbles.
     *
     * @return Sum of ghosts in all bubbles
     */
    public int getTotalGhostCount() {
        return ghostsByBubble.values().stream()
            .mapToInt(Map::size)
            .sum();
    }

    /**
     * Get ghosts for a specific bubble (for visualization/testing).
     *
     * @param bubbleId Bubble UUID
     * @return List of ghost entities in this bubble
     */
    public List<SimulationGhostEntity<StringEntityID, Object>> getGhostsForBubble(UUID bubbleId) {
        var ghosts = ghostsByBubble.get(bubbleId);
        if (ghosts == null) {
            return List.of();
        }
        return new ArrayList<>(ghosts.values());
    }

    /**
     * Get GhostLayerHealth for a bubble (for monitoring).
     *
     * @param bubbleId Bubble UUID
     * @return GhostLayerHealth instance or null
     */
    public GhostLayerHealth getHealth(UUID bubbleId) {
        return healthByBubble.get(bubbleId);
    }

    /**
     * Get ExternalBubbleTracker for a bubble (for monitoring).
     *
     * @param bubbleId Bubble UUID
     * @return ExternalBubbleTracker instance or null
     */
    public ExternalBubbleTracker getTracker(UUID bubbleId) {
        return trackerByBubble.get(bubbleId);
    }

    // ========== Private Methods ==========

    private void initializeGhostSync() {
        for (var bubble : bubbleGrid.getAllBubbles()) {
            var bubbleId = bubble.id();

            // Create infrastructure for this bubble
            var tracker = new ExternalBubbleTracker();
            var health = new GhostLayerHealth();

            // Get actual neighbor count for this bubble
            var neighbors = findBoundaryNeighbors(bubble);
            health.setExpectedNeighbors(neighbors.size());

            // Create ghost sender callback (in-memory channel to neighbor bubble)
            var ghostSender = createGhostSender(bubbleId);

            var ghostSync = new GhostBoundarySync<StringEntityID, Object>(tracker, health, ghostSender);

            // Store instances
            ghostSyncByBubble.put(bubbleId, ghostSync);
            trackerByBubble.put(bubbleId, tracker);
            healthByBubble.put(bubbleId, health);
            ghostsByBubble.put(bubbleId, new ConcurrentHashMap<>());

            log.debug("Initialized ghost sync for bubble {} with {} neighbors", bubbleId, neighbors.size());
        }
    }

    /**
     * Find boundary neighbors for a bubble using bounds-overlap detection.
     * <p>
     * Key Change from GridGhostSyncAdapter:
     * - OLD: distance(myCenter, neighborCenter) < AOI_RADIUS
     * - NEW: myBounds.overlaps(neighborBounds)
     *
     * @param bubble Bubble to find neighbors for
     * @return Set of neighbor bubble UUIDs
     */
    private Set<UUID> findBoundaryNeighbors(EnhancedBubble bubble) {
        var neighbors = new HashSet<UUID>();
        var myBounds = bubble.bounds();

        if (myBounds == null) {
            return neighbors; // No bounds yet (no entities)
        }

        // Get bubble's TetreeKey by searching spatial index
        // Since we need the key, we'll iterate through bubblesByKey to find it
        TetreeKey<?> myKey = null;
        for (var entry : bubbleGrid.getAllBubbles()) {
            if (entry.id().equals(bubble.id())) {
                // Find the key by searching the grid's internal map
                // We need to access TetreeBubbleGrid's bubblesByKey, but it's private
                // So we'll use the neighborFinder to search based on position
                var centroid = myBounds.centroid();
                if (centroid != null) {
                    // Locate the tetrahedron containing the centroid
                    var tet = com.hellblazer.luciferase.lucien.tetree.Tet.locatePointBeyRefinementFromRoot(
                        (float) centroid.getX(),
                        (float) centroid.getY(),
                        (float) centroid.getZ(),
                        myBounds.level()
                    );
                    if (tet != null) {
                        myKey = tet.tmIndex();
                    }
                }
                break;
            }
        }

        if (myKey == null) {
            log.warn("Could not determine TetreeKey for bubble {}", bubble.id());
            return neighbors;
        }

        // Get all topological neighbors via Tetree
        var tetreeNeighbors = neighborFinder.findNeighbors(myKey);

        // Filter by bounds overlap
        for (var neighborKey : tetreeNeighbors) {
            try {
                var neighborBubble = bubbleGrid.getBubble(neighborKey);
                var neighborBounds = neighborBubble.bounds();

                if (neighborBounds != null && myBounds.overlaps(neighborBounds)) {
                    neighbors.add(neighborBubble.id());
                }
            } catch (NoSuchElementException e) {
                // Neighbor key doesn't have a bubble - skip
                log.trace("Neighbor key {} has no bubble", neighborKey);
            }
        }

        return neighbors;
    }

    private void processBubbleBoundaryEntities(EnhancedBubble bubble, long currentBucket) {
        var bubbleId = bubble.id();
        var ghostSync = ghostSyncByBubble.get(bubbleId);

        if (ghostSync == null) {
            return;
        }

        // Find neighbors that need ghosts
        var neighbors = findBoundaryNeighbors(bubble);

        // Check all entities in this bubble
        for (var entityRecord : bubble.getAllEntityRecords()) {
            var position = entityRecord.position();

            // Determine which neighbors need ghosts for this entity
            var neighborsNeedingGhosts = findNeighborsNeedingGhosts(bubble, position, neighbors);

            if (neighborsNeedingGhosts.isEmpty()) {
                continue;  // Entity not near any boundary
            }

            // Create ghost entity
            var entityId = new StringEntityID(entityRecord.id());
            var ghostEntity = new GhostZoneManager.GhostEntity<>(
                entityId,
                entityRecord.content(),
                position,
                null,  // EntityBounds not used in simulation
                bubbleId.toString()
            );

            // Add ghost for each neighbor
            for (var neighborId : neighborsNeedingGhosts) {
                ghostSync.addGhost(ghostEntity, bubbleId, neighborId, currentBucket);
            }
        }
    }

    /**
     * Determine which neighbors need ghosts for a specific entity.
     * <p>
     * An entity near the boundary (within AOI_RADIUS of neighbor bounds)
     * triggers ghost creation in that neighbor.
     *
     * @param bubble               Source bubble
     * @param entityPosition       Entity position
     * @param boundaryNeighbors    Set of neighbor UUIDs with overlapping bounds
     * @return Set of neighbor UUIDs that need ghosts
     */
    private Set<UUID> findNeighborsNeedingGhosts(
        EnhancedBubble bubble,
        Point3f entityPosition,
        Set<UUID> boundaryNeighbors
    ) {
        var result = new HashSet<UUID>();

        for (var neighborId : boundaryNeighbors) {
            // Find the neighbor bubble
            var neighborBubble = findBubbleById(neighborId);
            if (neighborBubble == null) {
                continue;
            }

            var neighborBounds = neighborBubble.bounds();
            if (neighborBounds == null) {
                continue;
            }

            // Check if entity is within AOI_RADIUS of neighbor bounds
            // For simplicity, check if entity is contained or close to neighbor bounds
            if (isNearBounds(entityPosition, neighborBounds, AOI_RADIUS)) {
                result.add(neighborId);
            }
        }

        return result;
    }

    /**
     * Check if a position is near (within radius) of a bounds.
     * <p>
     * Conservative check: if position is contained or within radius of centroid.
     *
     * @param position Position to check
     * @param bounds   Bounds to check against
     * @param radius   Proximity radius
     * @return true if position is near bounds
     */
    private boolean isNearBounds(Point3f position, BubbleBounds bounds, float radius) {
        // First check: is position contained in bounds?
        if (bounds.contains(position)) {
            return true;
        }

        // Second check: distance to centroid
        var centroid = bounds.centroid();
        if (centroid != null) {
            var dx = position.x - centroid.getX();
            var dy = position.y - centroid.getY();
            var dz = position.z - centroid.getZ();
            var distSq = dx * dx + dy * dy + dz * dz;
            return distSq <= radius * radius;
        }

        return false;
    }

    /**
     * Find bubble by UUID.
     *
     * @param bubbleId Bubble UUID
     * @return EnhancedBubble or null if not found
     */
    private EnhancedBubble findBubbleById(UUID bubbleId) {
        for (var bubble : bubbleGrid.getAllBubbles()) {
            if (bubble.id().equals(bubbleId)) {
                return bubble;
            }
        }
        return null;
    }

    private java.util.function.BiConsumer<UUID, List<SimulationGhostEntity<StringEntityID, Object>>> createGhostSender(UUID sourceBubbleId) {
        return (neighborId, ghosts) -> {
            // In-memory channel: send ghosts to neighbor bubble
            var neighborGhosts = ghostsByBubble.get(neighborId);
            if (neighborGhosts == null) {
                log.warn("Neighbor {} not found for ghost sync from {}", neighborId, sourceBubbleId);
                return;
            }

            // Update ghost storage
            for (var ghost : ghosts) {
                var entityId = ghost.entityId().toString();
                neighborGhosts.put(entityId, ghost);
            }

            log.debug("Sent {} ghosts from {} to {}", ghosts.size(), sourceBubbleId, neighborId);
        };
    }
}
