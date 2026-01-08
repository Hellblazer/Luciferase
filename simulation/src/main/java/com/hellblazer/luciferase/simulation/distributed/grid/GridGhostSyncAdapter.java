/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.ExternalBubbleTracker;
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
 * Adapter connecting GhostBoundarySync to grid topology.
 * <p>
 * Integration Layer:
 * - Creates GhostBoundarySync instance for each bubble
 * - Configures ExternalBubbleTracker and GhostLayerHealth per bubble
 * - Wires ghostSender callback to in-memory channels (grid bubbles)
 * - Handles bucket-based ghost synchronization
 * <p>
 * Ghost Sync Flow:
 * 1. detectBoundaryEntities() - find entities near boundaries
 * 2. addGhost() - add ghost to GhostBoundarySync for neighbor
 * 3. onBucketComplete() - batch send ghosts to neighbors
 * 4. receiveGhosts() - receive ghost batch from neighbor
 *
 * @author hal.hildebrand
 */
public class GridGhostSyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(GridGhostSyncAdapter.class);

    private final GridConfiguration gridConfig;
    private final BubbleGrid<EnhancedBubble> bubbleGrid;
    private final GridBoundaryDetector boundaryDetector;

    // Per-bubble ghost sync infrastructure
    private final Map<UUID, GhostBoundarySync<StringEntityID, Object>> ghostSyncByBubble;
    private final Map<UUID, ExternalBubbleTracker> trackerByBubble;
    private final Map<UUID, GhostLayerHealth> healthByBubble;

    // Ghost storage: bubbleId -> (entityId -> SimulationGhostEntity)
    private final Map<UUID, Map<String, SimulationGhostEntity<StringEntityID, Object>>> ghostsByBubble;

    /**
     * Create a ghost sync adapter for the grid.
     *
     * @param gridConfig Grid configuration
     * @param bubbleGrid Bubble grid topology
     */
    public GridGhostSyncAdapter(GridConfiguration gridConfig, BubbleGrid<EnhancedBubble> bubbleGrid) {
        this.gridConfig = gridConfig;
        this.bubbleGrid = bubbleGrid;
        this.boundaryDetector = new GridBoundaryDetector(gridConfig);

        this.ghostSyncByBubble = new ConcurrentHashMap<>();
        this.trackerByBubble = new ConcurrentHashMap<>();
        this.healthByBubble = new ConcurrentHashMap<>();
        this.ghostsByBubble = new ConcurrentHashMap<>();

        // Initialize ghost sync for all bubbles
        initializeGhostSync();

        log.info("GridGhostSyncAdapter initialized for {}x{} grid",
                 gridConfig.rows(), gridConfig.columns());
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
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = bubbleGrid.getBubble(coord);

                processBubbleBoundaryEntities(bubble, coord, currentBucket);
            }
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
        long expirationBucket = currentBucket - GhostBoundarySync.GHOST_TTL_BUCKETS;

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
        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = bubbleGrid.getBubble(coord);

                if (bubble == null) {
                    continue;
                }

                var bubbleId = bubble.id();

                // Create infrastructure for this bubble
                var tracker = new ExternalBubbleTracker();
                var health = new GhostLayerHealth();

                // Set expected neighbors based on grid topology
                var expectedNeighbors = coord.expectedNeighborCount(gridConfig.rows(), gridConfig.columns());
                health.setExpectedNeighbors(expectedNeighbors);

                // Create ghost sender callback (in-memory channel to neighbor bubble)
                var ghostSender = createGhostSender(bubbleId);

                var ghostSync = new GhostBoundarySync<StringEntityID, Object>(tracker, health, ghostSender);

                // Store instances
                ghostSyncByBubble.put(bubbleId, ghostSync);
                trackerByBubble.put(bubbleId, tracker);
                healthByBubble.put(bubbleId, health);
                ghostsByBubble.put(bubbleId, new ConcurrentHashMap<>());

                log.debug("Initialized ghost sync for bubble {} at {}", bubbleId, coord);
            }
        }
    }

    private void processBubbleBoundaryEntities(EnhancedBubble bubble, BubbleCoordinate coord, long currentBucket) {
        var bubbleId = bubble.id();
        var ghostSync = ghostSyncByBubble.get(bubbleId);

        if (ghostSync == null) {
            return;
        }

        // Check all entities in this bubble
        for (var entityRecord : bubble.getAllEntityRecords()) {
            var position = entityRecord.position();

            // Detect which neighbors need ghosts
            var neighborsNeedingGhosts = boundaryDetector.getNeighborsNeedingGhosts(position, coord);

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
            for (var neighborCoord : neighborsNeedingGhosts) {
                var neighborBubble = bubbleGrid.getBubble(neighborCoord);
                if (neighborBubble != null) {
                    ghostSync.addGhost(ghostEntity, bubbleId, neighborBubble.id(), currentBucket);
                }
            }
        }
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
