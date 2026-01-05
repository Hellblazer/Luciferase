/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.EnhancedBubble;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optimizes ghost sync for bubbles on the same server.
 * <p>
 * When bubbles share a server, use direct memory access instead of ghost sync. This critical optimization eliminates:
 * <ul>
 *   <li>Network overhead - no serialization or transmission</li>
 *   <li>Ghost TTL management - direct access is always fresh</li>
 *   <li>Memory overhead - no ghost copies needed</li>
 * </ul>
 * <p>
 * <strong>Detection Strategy:</strong> Delegates to ServerRegistry for server assignment lookup (O(1)).
 * <p>
 * <strong>Direct Access:</strong> Maintains local bubble references for zero-cost queryRange() calls.
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var optimizer = new SameServerOptimizer(serverRegistry);
 * optimizer.registerLocalBubble(myBubble);
 *
 * // Check if ghost sync needed
 * if (optimizer.shouldBypassGhostSync(myBubble.id(), neighborId)) {
 *     // Same server - direct access
 *     var entities = optimizer.queryDirectNeighbor(neighborId, center, radius);
 * } else {
 *     // Different server - use ghost sync
 *     ghostChannel.queueGhost(neighborId, ghostEntity);
 * }
 * </pre>
 *
 * @author hal.hildebrand
 */
public class SameServerOptimizer {

    /**
     * Server registry for bubble-to-server assignment lookups
     */
    private final ServerRegistry serverRegistry;

    /**
     * Local bubble references for direct access
     */
    private final Map<UUID, EnhancedBubble> localBubbles;

    /**
     * Enable/disable optimization (for testing and debugging)
     */
    private boolean enabled = true;

    /**
     * Create optimizer with server registry.
     *
     * @param serverRegistry Registry tracking bubble-to-server assignments
     */
    public SameServerOptimizer(ServerRegistry serverRegistry) {
        this.serverRegistry = Objects.requireNonNull(serverRegistry, "serverRegistry must not be null");
        this.localBubbles = new ConcurrentHashMap<>();
    }

    /**
     * Register a local bubble for direct access.
     *
     * @param bubble Bubble to register
     */
    public void registerLocalBubble(EnhancedBubble bubble) {
        Objects.requireNonNull(bubble, "bubble must not be null");
        localBubbles.put(bubble.id(), bubble);
    }

    /**
     * Unregister a local bubble.
     *
     * @param bubbleId Bubble ID to unregister
     */
    public void unregisterLocalBubble(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "bubbleId must not be null");
        localBubbles.remove(bubbleId);
    }

    /**
     * Check if two bubbles are on the same server.
     *
     * @param bubble1 First bubble ID
     * @param bubble2 Second bubble ID
     * @return true if both bubbles are on the same server
     */
    public boolean isSameServer(UUID bubble1, UUID bubble2) {
        Objects.requireNonNull(bubble1, "bubble1 must not be null");
        Objects.requireNonNull(bubble2, "bubble2 must not be null");
        return serverRegistry.isSameServer(bubble1, bubble2);
    }

    /**
     * Determine if ghost sync should be bypassed.
     * <p>
     * Returns true if: same server AND optimization enabled.
     *
     * @param sourceBubble Source bubble ID
     * @param targetBubble Target bubble ID
     * @return true if ghost sync should be bypassed
     */
    public boolean shouldBypassGhostSync(UUID sourceBubble, UUID targetBubble) {
        Objects.requireNonNull(sourceBubble, "sourceBubble must not be null");
        Objects.requireNonNull(targetBubble, "targetBubble must not be null");

        if (!enabled) {
            return false;
        }
        return isSameServer(sourceBubble, targetBubble);
    }

    /**
     * Get direct reference to a local bubble.
     *
     * @param bubbleId Bubble ID
     * @return EnhancedBubble or null if bubble is not local
     */
    public EnhancedBubble getLocalBubble(UUID bubbleId) {
        Objects.requireNonNull(bubbleId, "bubbleId must not be null");
        return localBubbles.get(bubbleId);
    }

    /**
     * Query entities from a local neighbor directly (bypassing ghost sync).
     * <p>
     * This is the zero-overhead path for same-server bubbles.
     *
     * @param neighborId Neighbor bubble ID
     * @param center     Query center point
     * @param radius     Query radius
     * @return List of entity records in range, or empty list if neighbor is not local
     */
    public List<EnhancedBubble.EntityRecord> queryDirectNeighbor(UUID neighborId, Point3f center, float radius) {
        Objects.requireNonNull(neighborId, "neighborId must not be null");
        Objects.requireNonNull(center, "center must not be null");

        var neighbor = localBubbles.get(neighborId);
        if (neighbor == null) {
            return List.of();  // Not local, can't query directly
        }
        return neighbor.queryRange(center, radius);
    }

    /**
     * Enable/disable same-server optimization.
     *
     * @param enabled true to enable, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Check if optimization is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get count of local bubbles.
     *
     * @return Number of registered local bubbles
     */
    public int getLocalBubbleCount() {
        return localBubbles.size();
    }
}
