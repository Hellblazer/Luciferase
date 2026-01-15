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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ghost layer synchronizer for distributed bubble coordination.
 * <p>
 * Manages ghost entities at bubble boundaries:
 * <ul>
 *   <li>Synchronizes entities near boundary as ghosts in neighboring bubble</li>
 *   <li>Expires stale ghosts based on TTL</li>
 *   <li>Tracks velocity for ghost entities</li>
 * </ul>
 * <p>
 * Ghost entities enable cross-boundary flocking behavior by providing neighbor
 * information to entities approaching the boundary.
 *
 * @deprecated Use {@link GhostSyncCoordinator} with {@link TwoBubbleSyncStrategy} instead.
 * This class will be removed in a future release after all usages migrate to the
 * new strategy-based architecture aligned with M1 ADR Layer 2 Integration.
 * <p>
 * Migration example:
 * <pre>
 * // Old approach:
 * var synchronizer = new GhostLayerSynchronizer(
 *     bubble1, bubble2, boundaryX,
 *     ghostBoundaryWidth, ghostTtlTicks,
 *     velocities1, velocities2
 * );
 * synchronizer.syncGhosts(currentTick);
 * synchronizer.expireGhosts(currentTick);
 * var ghosts1 = synchronizer.getGhostsInBubble1();
 * var ghosts2 = synchronizer.getGhostsInBubble2();
 *
 * // New approach:
 * var strategy = new TwoBubbleSyncStrategy(
 *     bubble1, bubble2, boundaryX,
 *     ghostBoundaryWidth, ghostTtlTicks,
 *     velocities1, velocities2
 * );
 * var coordinator = new GhostSyncCoordinator(strategy);
 * coordinator.syncGhosts(currentTick);
 * coordinator.expireGhosts(currentTick);
 * var ghosts1 = coordinator.getGhostsInBubble1();
 * var ghosts2 = coordinator.getGhostsInBubble2();
 * </pre>
 * <p>
 * Benefits of new architecture:
 * <ul>
 *   <li>Strategy pattern enables pluggable sync algorithms (TwoBubble, MultiBubble, MultiTree)</li>
 *   <li>Aligned with M1 ADR Layer 2 Integration architecture</li>
 *   <li>Supports same-server optimization via future strategies</li>
 *   <li>Consistent with B2 decomposition patterns (pure delegation, no business logic)</li>
 * </ul>
 * <p>
 * Related:
 * <ul>
 *   <li>M1 ADR: simulation/doc/TECHNICAL_DECISION_MIGRATION_CONSENSUS_ARCHITECTURE.md</li>
 *   <li>M2 Analysis: simulation/doc/GHOST_LAYER_CONSOLIDATION_ANALYSIS.md</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@Deprecated
public class GhostLayerSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(GhostLayerSynchronizer.class);

    private final VonBubble bubble1;
    private final VonBubble bubble2;
    private final float boundaryX;
    private final float ghostBoundaryWidth;
    private final int ghostTtlTicks;
    private final Map<String, javax.vecmath.Vector3f> velocities1;
    private final Map<String, javax.vecmath.Vector3f> velocities2;

    private final Map<String, GhostEntry> ghostsInBubble1 = new ConcurrentHashMap<>();
    private final Map<String, GhostEntry> ghostsInBubble2 = new ConcurrentHashMap<>();

    /**
     * Ghost entry with position, velocity, and expiration.
     *
     * @param id            Entity ID
     * @param position      Ghost position
     * @param velocity      Ghost velocity
     * @param expirationTick Tick when ghost expires
     */
    public record GhostEntry(String id, Point3f position, javax.vecmath.Vector3f velocity, long expirationTick) {}

    /**
     * Create a ghost layer synchronizer.
     *
     * @param bubble1            First bubble
     * @param bubble2            Second bubble
     * @param boundaryX          X coordinate dividing the bubbles
     * @param ghostBoundaryWidth Distance from boundary to ghost entities
     * @param ghostTtlTicks      Ghost time-to-live in ticks
     * @param velocities1        Velocity map for bubble 1
     * @param velocities2        Velocity map for bubble 2
     */
    public GhostLayerSynchronizer(VonBubble bubble1, VonBubble bubble2, float boundaryX,
                                   float ghostBoundaryWidth, int ghostTtlTicks,
                                   Map<String, javax.vecmath.Vector3f> velocities1,
                                   Map<String, javax.vecmath.Vector3f> velocities2) {
        this.bubble1 = bubble1;
        this.bubble2 = bubble2;
        this.boundaryX = boundaryX;
        this.ghostBoundaryWidth = ghostBoundaryWidth;
        this.ghostTtlTicks = ghostTtlTicks;
        this.velocities1 = velocities1;
        this.velocities2 = velocities2;
    }

    /**
     * Synchronize ghost entities across boundary.
     * <p>
     * Finds entities within ghostBoundaryWidth of the boundary and creates
     * ghost copies in the neighboring bubble.
     *
     * @param currentTick Current simulation tick
     */
    public void syncGhosts(long currentTick) {
        long expirationTick = currentTick + ghostTtlTicks;

        // Find entities near boundary in bubble1, ghost them to bubble2
        for (var entity : bubble1.getAllEntityRecords()) {
            float distFromBoundary = boundaryX - entity.position().x;
            if (distFromBoundary >= 0 && distFromBoundary < ghostBoundaryWidth) {
                var velocity = velocities1.get(entity.id());
                var ghost = new GhostEntry(
                    entity.id(),
                    new Point3f(entity.position()),
                    velocity != null ? new javax.vecmath.Vector3f(velocity) : new javax.vecmath.Vector3f(),
                    expirationTick
                );
                ghostsInBubble2.put(entity.id(), ghost);
            }
        }

        // Find entities near boundary in bubble2, ghost them to bubble1
        for (var entity : bubble2.getAllEntityRecords()) {
            float distFromBoundary = entity.position().x - boundaryX;
            if (distFromBoundary >= 0 && distFromBoundary < ghostBoundaryWidth) {
                var velocity = velocities2.get(entity.id());
                var ghost = new GhostEntry(
                    entity.id(),
                    new Point3f(entity.position()),
                    velocity != null ? new javax.vecmath.Vector3f(velocity) : new javax.vecmath.Vector3f(),
                    expirationTick
                );
                ghostsInBubble1.put(entity.id(), ghost);
            }
        }

        log.trace("Ghost sync: {} ghosts in bubble1, {} in bubble2",
                  ghostsInBubble1.size(), ghostsInBubble2.size());
    }

    /**
     * Expire stale ghost entities.
     * <p>
     * Removes ghosts that have exceeded their TTL.
     *
     * @param currentTick Current simulation tick
     */
    public void expireGhosts(long currentTick) {
        ghostsInBubble1.entrySet().removeIf(e -> e.getValue().expirationTick() <= currentTick);
        ghostsInBubble2.entrySet().removeIf(e -> e.getValue().expirationTick() <= currentTick);
    }

    /**
     * Get ghosts in bubble 1.
     *
     * @return Map of ghost entities
     */
    public Map<String, GhostEntry> getGhostsInBubble1() {
        return ghostsInBubble1;
    }

    /**
     * Get ghosts in bubble 2.
     *
     * @return Map of ghost entities
     */
    public Map<String, GhostEntry> getGhostsInBubble2() {
        return ghostsInBubble2;
    }

    /**
     * Clear all ghost state.
     * Called during cleanup to prevent memory leaks.
     */
    public void clear() {
        ghostsInBubble1.clear();
        ghostsInBubble2.clear();
    }
}
