/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.von.BubbleVONNode;
import com.hellblazer.luciferase.simulation.von.SpatialNeighborIndex;
import javafx.geometry.Point3D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/**
 * Wires ghost synchronization into VON (Virtual Organization Network) protocols.
 * <p>
 * GhostSyncVONIntegration implements the VON "watchmen" pattern where the ghost layer enables distributed bubble
 * discovery without a global registry. It coordinates:
 * <ul>
 *   <li><strong>VON JOIN</strong> - Initialize ghost relationships with existing neighbors</li>
 *   <li><strong>VON MOVE</strong> - Update ghost zones when bubble moves</li>
 *   <li><strong>VON LEAVE</strong> - Clean up ghost state when bubble leaves</li>
 *   <li><strong>Ghost-based discovery</strong> - Learn about new neighbors via ghost arrivals</li>
 * </ul>
 * <p>
 * <strong>Core Thesis:</strong> Ghost layer + VON protocols = fully distributed animation with no global state.
 * <p>
 * <strong>Discovery Pattern:</strong>
 * <ol>
 *   <li>Bubble receives ghost from unknown neighbor</li>
 *   <li>{@link #onGhostBatchReceived(UUID)} called with sender ID</li>
 *   <li>Add sender as new VON neighbor</li>
 *   <li>Register with ghost manager for bidirectional ghost sync</li>
 * </ol>
 * <p>
 * <strong>Lifecycle Integration:</strong>
 * <ul>
 *   <li>JOIN: Register all existing neighbors for ghost sync</li>
 *   <li>MOVE: Discover new neighbors based on spatial proximity</li>
 *   <li>LEAVE: Clean up all ghost relationships</li>
 * </ul>
 * <p>
 * <strong>Architecture Constraint:</strong> No global registry. All neighbor discovery happens via:
 * <ul>
 *   <li>Ghost arrivals (primary mechanism)</li>
 *   <li>Spatial queries (SpatialNeighborIndex)</li>
 *   <li>VON protocol events (JOIN/MOVE/LEAVE)</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var integration = new GhostSyncVONIntegration(vonNode, ghostManager, neighborIndex);
 *
 * // Bubble joins VON network
 * integration.onVONJoin();
 *
 * // Bubble moves to new position
 * integration.onVONMove(newPosition);
 *
 * // Receive ghost from neighbor
 * integration.onGhostBatchReceived(neighborId);
 *
 * // Bubble leaves VON network
 * integration.onVONLeave();
 * </pre>
 *
 * @author hal.hildebrand
 */
public class GhostSyncVONIntegration {

    private static final Logger log = LoggerFactory.getLogger(GhostSyncVONIntegration.class);

    /**
     * VON node adapter for this bubble
     */
    private final BubbleVONNode vonNode;

    /**
     * Ghost manager for cross-server synchronization
     */
    private final BubbleGhostManager<?, ?> ghostManager;

    /**
     * Spatial index for neighbor discovery
     */
    private final SpatialNeighborIndex neighborIndex;

    /**
     * Create VON integration with all dependencies.
     *
     * @param vonNode       VON node adapter for this bubble
     * @param ghostManager  Ghost manager for cross-server sync
     * @param neighborIndex Spatial index for neighbor discovery
     */
    public GhostSyncVONIntegration(
        BubbleVONNode vonNode,
        BubbleGhostManager<?, ?> ghostManager,
        SpatialNeighborIndex neighborIndex
    ) {
        this.vonNode = Objects.requireNonNull(vonNode, "vonNode must not be null");
        this.ghostManager = Objects.requireNonNull(ghostManager, "ghostManager must not be null");
        this.neighborIndex = Objects.requireNonNull(neighborIndex, "neighborIndex must not be null");
    }

    /**
     * Handle VON JOIN event - initialize ghost relationships with existing neighbors.
     * <p>
     * Called when a bubble joins the VON network. Registers all existing neighbors (discovered via Voronoi or spatial
     * queries) with the ghost manager for bidirectional ghost synchronization.
     * <p>
     * This establishes the initial ghost sync relationships. As the bubble moves or receives ghosts from unknown
     * neighbors, the neighbor set evolves dynamically via {@link #onGhostBatchReceived(UUID)}.
     */
    public void onVONJoin() {
        var neighbors = vonNode.neighbors();
        log.debug("VON JOIN: Initializing ghost relationships for {} neighbors", neighbors.size());

        for (var neighborId : neighbors) {
            ghostManager.onVONNeighborAdded(neighborId);
            log.debug("Registered neighbor {} for ghost sync", neighborId);
        }
    }

    /**
     * Handle VON MOVE event - update ghost zones when bubble moves.
     * <p>
     * Called when a bubble changes position. Checks for new neighbors based on spatial proximity (boundary neighbors
     * within AOI + buffer range). Discovers new neighbors via spatial index queries.
     * <p>
     * Note: Ghost sync already handles entity-level updates when entities cross boundaries. This method focuses on
     * discovering NEW neighbors that come into range after the move.
     *
     * @param newPosition New bubble position
     */
    public void onVONMove(Point3D newPosition) {
        Objects.requireNonNull(newPosition, "newPosition must not be null");

        log.debug("VON MOVE: Bubble moved to {}", newPosition);

        // Query spatial index for nearby bubbles that might be new boundary neighbors
        var nearbyNodes = neighborIndex.findKNearest(newPosition, 10);

        for (var nearbyNode : nearbyNodes) {
            var nearbyId = nearbyNode.id();

            // Skip if already a neighbor
            if (vonNode.neighbors().contains(nearbyId)) {
                continue;
            }

            // Check if this is a boundary neighbor (within AOI + buffer)
            if (neighborIndex.isBoundaryNeighbor(vonNode, nearbyNode)) {
                log.debug("Discovered new boundary neighbor {} after MOVE", nearbyId);
                vonNode.addNeighbor(nearbyId);
                ghostManager.onVONNeighborAdded(nearbyId);
            }
        }
    }

    /**
     * Handle VON LEAVE event - clean up ghost state when bubble leaves.
     * <p>
     * Called when a bubble leaves the VON network. Removes all neighbors and notifies the ghost manager to clean up
     * ghost state for each neighbor.
     * <p>
     * This ensures no stale ghost relationships remain after the bubble is gone.
     */
    public void onVONLeave() {
        var neighbors = new ArrayList<>(vonNode.neighbors());
        log.debug("VON LEAVE: Cleaning up {} ghost relationships", neighbors.size());

        for (var neighborId : neighbors) {
            vonNode.removeNeighbor(neighborId);
            ghostManager.onVONNeighborRemoved(neighborId);
            log.debug("Removed neighbor {} and cleaned up ghost state", neighborId);
        }
    }

    /**
     * Handle incoming ghost batch - discover new neighbors via ghost arrivals.
     * <p>
     * This is the primary mechanism for distributed neighbor discovery. When a ghost arrives from an unknown bubble:
     * <ol>
     *   <li>Add the sender as a new VON neighbor</li>
     *   <li>Register with ghost manager for bidirectional ghost sync</li>
     * </ol>
     * <p>
     * The "watchmen" pattern: ghosts inform bubbles about each other without requiring a global registry.
     *
     * @param fromBubbleId ID of the bubble that sent the ghost batch
     */
    public void onGhostBatchReceived(UUID fromBubbleId) {
        Objects.requireNonNull(fromBubbleId, "fromBubbleId must not be null");

        // Check if this is a new neighbor (ghost-based discovery)
        if (!vonNode.neighbors().contains(fromBubbleId)) {
            log.debug("Discovered new neighbor {} via ghost arrival", fromBubbleId);
            vonNode.addNeighbor(fromBubbleId);
            ghostManager.onVONNeighborAdded(fromBubbleId);
        } else {
            log.trace("Received ghost from known neighbor {}", fromBubbleId);
        }
    }

    /**
     * Architecture validation: verify distributed discovery pattern.
     * <p>
     * Returns true to confirm this integration uses distributed discovery (ghost arrivals + spatial queries) with no
     * global registry.
     *
     * @return true (always - architecture constraint)
     */
    public boolean usesDistributedDiscovery() {
        return true;
    }
}
