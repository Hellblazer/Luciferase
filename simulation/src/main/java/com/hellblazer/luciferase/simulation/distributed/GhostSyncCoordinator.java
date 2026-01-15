/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.von.VonBubble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ghost synchronization coordinator for distributed bubble coordination.
 * <p>
 * Layer 2 Integration component that determines local vs distributed ghost sync
 * using pluggable synchronization strategies. Consolidates synchronization logic
 * from GhostLayerSynchronizer, GhostZoneManager, and BubbleGhostManager.
 * <p>
 * Architecture:
 * <ul>
 *   <li>Strategy Pattern: Pluggable sync algorithms (TwoBubbleSyncStrategy, etc.)</li>
 *   <li>Same-Server Optimization: Direct access when bubbles on same server</li>
 *   <li>Thread-Safe: ConcurrentHashMap pattern from B2 decomposition</li>
 *   <li>Boundary Detection: Hysteresis-based filtering from GhostLayerSynchronizer</li>
 * </ul>
 * <p>
 * Related:
 * <ul>
 *   <li>M1 ADR: simulation/doc/TECHNICAL_DECISION_MIGRATION_CONSENSUS_ARCHITECTURE.md</li>
 *   <li>M2 Analysis: simulation/doc/GHOST_LAYER_CONSOLIDATION_ANALYSIS.md</li>
 *   <li>Replaces: {@link GhostLayerSynchronizer} (deprecated)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class GhostSyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(GhostSyncCoordinator.class);

    private final GhostSyncStrategy strategy;

    /**
     * Create a ghost sync coordinator with a specific strategy.
     *
     * @param strategy Synchronization strategy to use
     */
    public GhostSyncCoordinator(GhostSyncStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Synchronize ghost entities across boundaries.
     * <p>
     * Delegates to the configured strategy to determine which entities
     * should be ghosted and how.
     *
     * @param currentTick Current simulation tick
     */
    public void syncGhosts(long currentTick) {
        strategy.syncGhosts(currentTick);
    }

    /**
     * Expire stale ghost entities.
     * <p>
     * Delegates to the configured strategy to remove ghosts that have
     * exceeded their TTL.
     *
     * @param currentTick Current simulation tick
     */
    public void expireGhosts(long currentTick) {
        strategy.expireGhosts(currentTick);
    }

    /**
     * Get ghosts in bubble 1.
     * <p>
     * Delegates to strategy for actual storage access.
     *
     * @return Map of ghost entities in bubble 1
     */
    public Map<String, GhostEntry> getGhostsInBubble1() {
        return strategy.getGhostsInBubble1();
    }

    /**
     * Get ghosts in bubble 2.
     * <p>
     * Delegates to strategy for actual storage access.
     *
     * @return Map of ghost entities in bubble 2
     */
    public Map<String, GhostEntry> getGhostsInBubble2() {
        return strategy.getGhostsInBubble2();
    }

    /**
     * Clear all ghost state.
     * <p>
     * Called during cleanup to prevent memory leaks.
     */
    public void clear() {
        strategy.clear();
    }

    // ========== Strategy Interface ==========

    /**
     * Strategy interface for ghost synchronization algorithms.
     * <p>
     * Implementations provide specific synchronization logic for different
     * bubble/zone configurations:
     * <ul>
     *   <li>TwoBubbleSyncStrategy: Simple 2-bubble with hysteresis</li>
     *   <li>MultiBubbleSyncStrategy: VON discovery with batched transmission</li>
     *   <li>MultiTreeSyncStrategy: Forest-level zone coordination</li>
     * </ul>
     */
    public interface GhostSyncStrategy {

        /**
         * Synchronize ghost entities for current tick.
         *
         * @param currentTick Current simulation tick
         */
        void syncGhosts(long currentTick);

        /**
         * Expire stale ghost entities for current tick.
         *
         * @param currentTick Current simulation tick
         */
        void expireGhosts(long currentTick);

        /**
         * Get ghosts in bubble 1.
         *
         * @return Map of ghost entities
         */
        Map<String, GhostEntry> getGhostsInBubble1();

        /**
         * Get ghosts in bubble 2.
         *
         * @return Map of ghost entities
         */
        Map<String, GhostEntry> getGhostsInBubble2();

        /**
         * Clear all ghost state.
         */
        void clear();
    }

    // ========== Ghost Entry Record ==========

    /**
     * Ghost entry with position, velocity, and expiration.
     * <p>
     * Shared across all strategies to maintain consistent ghost representation.
     *
     * @param id            Entity ID
     * @param position      Ghost position
     * @param velocity      Ghost velocity
     * @param expirationTick Tick when ghost expires
     */
    public record GhostEntry(String id, Point3f position, Vector3f velocity, long expirationTick) {}
}
