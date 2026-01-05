/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.simulation.EnhancedBubble;
import com.hellblazer.luciferase.simulation.ExternalBubbleTracker;
import com.hellblazer.luciferase.simulation.GhostBoundarySync;
import com.hellblazer.luciferase.simulation.GhostLayerHealth;
import com.hellblazer.luciferase.simulation.SimulationGhostEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Central orchestrator for Phase 3 ghost synchronization.
 * <p>
 * BubbleGhostManager coordinates all ghost sync components to provide a unified API for distributed animation:
 * <ul>
 *   <li><strong>ServerRegistry</strong> - Tracks which bubbles are on which servers for same-server optimization</li>
 *   <li><strong>GhostChannel</strong> - Batched ghost transmission across servers (100ms buckets)</li>
 *   <li><strong>SameServerOptimizer</strong> - Direct memory access bypass when bubbles share a server</li>
 *   <li><strong>GhostBoundarySync</strong> - TTL (500ms) and memory limit (1000/neighbor) management</li>
 *   <li><strong>GhostLayerHealth</strong> - NC (Neighbor Consistency) metric monitoring for VON health</li>
 *   <li><strong>ExternalBubbleTracker</strong> - Bubble discovery via ghost interactions (no global registry)</li>
 * </ul>
 * <p>
 * <strong>Ghost Lifecycle:</strong>
 * <ol>
 *   <li>Entity approaches boundary → {@link #notifyEntityNearBoundary(EntityID, Point3f, Object, UUID, long)}</li>
 *   <li>Ghost queued in GhostBoundarySync with TTL and memory limits</li>
 *   <li>At bucket boundary → {@link #onBucketComplete(long)} flushes batches</li>
 *   <li>Same-server check: if true, use direct access; else send via GhostChannel</li>
 *   <li>Receiving bubble: {@link #handleGhostBatch(UUID, List)} processes incoming ghosts</li>
 *   <li>Update ExternalBubbleTracker and GhostLayerHealth for VON discovery</li>
 * </ol>
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Same-server optimization: Zero ghost overhead, O(1) direct access</li>
 *   <li>Cross-server: Batched transmission, <100ms latency per bucket</li>
 *   <li>Memory: 1000 ghosts/neighbor max, oldest-first eviction</li>
 *   <li>TTL: 500ms (5 buckets) automatic expiration</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var manager = new BubbleGhostManager&lt;&gt;(
 *     myBubble, serverRegistry, ghostChannel, optimizer,
 *     externalBubbleTracker, ghostLayerHealth
 * );
 *
 * // Register incoming ghost handler
 * ghostChannel.onReceive((fromId, ghosts) -&gt; manager.handleGhostBatch(fromId, ghosts));
 *
 * // Each simulation step:
 * for (var entity : entitiesNearBoundary) {
 *     manager.notifyEntityNearBoundary(entity.id, entity.position, entity.content, neighborId, bucket);
 * }
 * manager.onBucketComplete(bucket);
 *
 * // Monitor health:
 * float nc = manager.getNeighborConsistency();
 * int activeGhosts = manager.getActiveGhostCount();
 * </pre>
 *
 * @param <ID>      Entity ID type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class BubbleGhostManager<ID extends EntityID, Content> {

    private static final Logger log = LoggerFactory.getLogger(BubbleGhostManager.class);

    /**
     * Local bubble being managed
     */
    private final EnhancedBubble bubble;

    /**
     * Server assignment tracking for same-server optimization
     */
    private final ServerRegistry serverRegistry;

    /**
     * Ghost transmission channel (batched)
     */
    private final GhostChannel<ID, Content> ghostChannel;

    /**
     * Same-server optimization detector
     */
    private final SameServerOptimizer optimizer;

    /**
     * Bubble discovery tracker (VON)
     */
    private final ExternalBubbleTracker externalBubbleTracker;

    /**
     * NC metric monitoring (VON health)
     */
    private final GhostLayerHealth ghostLayerHealth;

    /**
     * Internal ghost batching, TTL, and memory limit management
     */
    private final GhostBoundarySync<ID, Content> ghostBoundarySync;

    /**
     * Create ghost manager with all dependencies.
     *
     * @param bubble                 Local bubble to manage
     * @param serverRegistry         Server assignment tracking
     * @param ghostChannel           Ghost transmission channel
     * @param optimizer              Same-server optimization detector
     * @param externalBubbleTracker  Bubble discovery tracker
     * @param ghostLayerHealth       NC metric monitoring
     */
    public BubbleGhostManager(
        EnhancedBubble bubble,
        ServerRegistry serverRegistry,
        GhostChannel<ID, Content> ghostChannel,
        SameServerOptimizer optimizer,
        ExternalBubbleTracker externalBubbleTracker,
        GhostLayerHealth ghostLayerHealth
    ) {
        this.bubble = Objects.requireNonNull(bubble, "bubble must not be null");
        this.serverRegistry = Objects.requireNonNull(serverRegistry, "serverRegistry must not be null");
        this.ghostChannel = Objects.requireNonNull(ghostChannel, "ghostChannel must not be null");
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer must not be null");
        this.externalBubbleTracker = Objects.requireNonNull(externalBubbleTracker,
                                                              "externalBubbleTracker must not be null");
        this.ghostLayerHealth = Objects.requireNonNull(ghostLayerHealth, "ghostLayerHealth must not be null");

        // Create internal GhostBoundarySync with ghostSender callback
        this.ghostBoundarySync = new GhostBoundarySync<>(
            externalBubbleTracker,
            ghostLayerHealth,
            this::sendGhostBatch  // Callback for batch transmission
        );
    }

    /**
     * Notify manager that an entity is near a boundary and should be ghosted to a neighbor.
     * <p>
     * This queues the ghost in GhostBoundarySync with TTL and memory limit management. The ghost will be sent at the
     * next bucket boundary via {@link #onBucketComplete(long)}.
     *
     * @param entityId       Entity ID
     * @param position       Entity position
     * @param content        Entity content
     * @param neighborBubble Target neighbor bubble ID
     * @param bucket         Current simulation bucket
     */
    public void notifyEntityNearBoundary(
        ID entityId,
        Point3f position,
        Content content,
        UUID neighborBubble,
        long bucket
    ) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(position, "position must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(neighborBubble, "neighborBubble must not be null");

        // Check same-server optimization BEFORE creating ghost
        if (optimizer.shouldBypassGhostSync(bubble.id(), neighborBubble)) {
            // Same server - no ghost needed, direct access will be used
            log.debug("Skipping ghost creation for entity {} to neighbor {} (same-server bypass)",
                      entityId.toDebugString(), neighborBubble);
            return;
        }

        // Different server - create ghost and add to GhostBoundarySync
        var ghostEntity = createGhostEntity(entityId, content, position);

        ghostBoundarySync.addGhost(ghostEntity, bubble.id(), neighborBubble, bucket);
    }

    /**
     * Complete a simulation bucket - flush ghost batches and expire stale ghosts.
     * <p>
     * This triggers GhostBoundarySync to:
     * <ol>
     *   <li>Flush all pending batches to GhostChannel (via ghostSender callback)</li>
     *   <li>Expire ghosts older than TTL (5 buckets = 500ms)</li>
     * </ol>
     *
     * @param bucket Bucket number that just completed
     */
    public void onBucketComplete(long bucket) {
        // Expire stale ghosts first
        ghostBoundarySync.expireStaleGhosts(bucket);

        // Flush all pending batches
        ghostBoundarySync.onBucketComplete(bucket);

        // Trigger GhostChannel flush
        ghostChannel.flush(bucket);
    }

    /**
     * Handle incoming ghost batch from another bubble.
     * <p>
     * This processes ghosts received via GhostChannel, updating:
     * <ul>
     *   <li>ExternalBubbleTracker: Mark sending bubble as discovered</li>
     *   <li>GhostLayerHealth: Update NC metric for VON health monitoring</li>
     * </ul>
     *
     * @param fromBubbleId Source bubble that sent this batch
     * @param ghosts       Ghost entities to process
     */
    public void handleGhostBatch(UUID fromBubbleId, List<SimulationGhostEntity<ID, Content>> ghosts) {
        Objects.requireNonNull(fromBubbleId, "fromBubbleId must not be null");
        Objects.requireNonNull(ghosts, "ghosts must not be null");

        if (ghosts.isEmpty()) {
            return;
        }

        // Update bubble discovery (VON)
        externalBubbleTracker.recordGhostInteraction(fromBubbleId);

        // Update NC metric (VON health)
        ghostLayerHealth.recordGhostSource(fromBubbleId);

        log.debug("Received ghost batch from {}: {} ghosts", fromBubbleId, ghosts.size());
    }

    /**
     * VON lifecycle hook: Neighbor added.
     * <p>
     * Called when VON discovers a new boundary neighbor. Registers the neighbor in ServerRegistry if not already
     * known.
     *
     * @param neighborId New neighbor bubble ID
     */
    public void onVONNeighborAdded(UUID neighborId) {
        Objects.requireNonNull(neighborId, "neighborId must not be null");
        externalBubbleTracker.recordGhostInteraction(neighborId);
        log.debug("VON neighbor added: {}", neighborId);
    }

    /**
     * VON lifecycle hook: Neighbor removed.
     * <p>
     * Called when VON detects neighbor is no longer a boundary neighbor. Cleans up ghost state for this neighbor.
     *
     * @param neighborId Removed neighbor bubble ID
     */
    public void onVONNeighborRemoved(UUID neighborId) {
        Objects.requireNonNull(neighborId, "neighborId must not be null");
        ghostBoundarySync.removeNeighbor(neighborId);
        log.debug("VON neighbor removed: {}", neighborId);
    }

    /**
     * Get active ghost count across all neighbors.
     *
     * @return Total number of ghosts currently tracked
     */
    public int getActiveGhostCount() {
        return ghostBoundarySync.getActiveGhostCount();
    }

    /**
     * Get NC (Neighbor Consistency) metric for VON health monitoring.
     * <p>
     * NC measures how consistently ghosts are received from expected neighbors. Values:
     * <ul>
     *   <li>1.0 = Perfect consistency (all expected neighbors sending ghosts)</li>
     *   <li>>0.9 = Healthy</li>
     *   <li><0.9 = Degraded (potential network partition or server failure)</li>
     * </ul>
     *
     * @return NC metric in range [0.0, 1.0]
     */
    public float getNeighborConsistency() {
        return ghostLayerHealth.neighborConsistency();
    }

    // Internal methods

    /**
     * Ghost sender callback for GhostBoundarySync.
     * <p>
     * Called when GhostBoundarySync has a batch ready to send. Checks same-server optimization and routes
     * accordingly:
     * <ul>
     *   <li>Same server: Direct access via SameServerOptimizer (zero overhead)</li>
     *   <li>Different server: Queue to GhostChannel for batched transmission</li>
     * </ul>
     *
     * @param targetBubbleId Target bubble ID
     * @param ghosts         Ghost batch to send
     */
    private void sendGhostBatch(UUID targetBubbleId, List<SimulationGhostEntity<ID, Content>> ghosts) {
        if (ghosts.isEmpty()) {
            return;
        }

        // Defensive check: same-server optimization
        // Ghosts should have been filtered in notifyEntityNearBoundary(), but this provides
        // safety in case future code changes bypass that check
        if (optimizer.shouldBypassGhostSync(bubble.id(), targetBubbleId)) {
            // Same server - direct access, no transmission needed
            log.debug("Bypassing ghost transmission to {} (same-server direct access)", targetBubbleId);
            return;
        }

        // Different server - queue for batched transmission
        for (var ghost : ghosts) {
            ghostChannel.queueGhost(targetBubbleId, ghost);
        }
        log.debug("Queued {} ghosts for transmission to {}", ghosts.size(), targetBubbleId);
    }

    /**
     * Create GhostEntity from entity data.
     * <p>
     * Helper method to construct GhostZoneManager.GhostEntity for GhostBoundarySync.
     *
     * @param entityId Entity ID
     * @param content  Entity content
     * @param position Entity position
     * @return GhostEntity for transmission
     */
    private com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager.GhostEntity<ID, Content> createGhostEntity(
        ID entityId,
        Content content,
        Point3f position
    ) {
        var bounds = new com.hellblazer.luciferase.lucien.entity.EntityBounds(position, 0.5f);
        return new com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager.GhostEntity<>(
            entityId,
            content,
            position,
            bounds,
            "tree-" + bubble.id()  // Source tree ID
        );
    }
}
