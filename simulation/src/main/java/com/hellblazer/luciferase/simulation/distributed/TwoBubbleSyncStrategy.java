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
 * Two-bubble ghost synchronization strategy.
 * <p>
 * Implements simple boundary-based synchronization for two bubbles with:
 * <ul>
 *   <li>Hysteresis-based filtering (entities within ghostBoundaryWidth of boundary)</li>
 *   <li>TTL expiration (ghostTtlTicks)</li>
 *   <li>Thread-safe storage (ConcurrentHashMap)</li>
 *   <li>Velocity tracking for ghost entities</li>
 * </ul>
 * <p>
 * This strategy extracts the core logic from {@link GhostLayerSynchronizer}
 * into a pluggable strategy for use with {@link GhostSyncCoordinator}.
 * <p>
 * Architecture:
 * <pre>
 * Bubble 1 (x < boundaryX)  │  Boundary  │  Bubble 2 (x >= boundaryX)
 *                            │            │
 *   Entities near boundary ──┼──ghost──►  │  (ghostsInBubble2)
 *                            │            │
 *   (ghostsInBubble1)       ◄┼──ghost──── Entities near boundary
 *                            │            │
 * </pre>
 *
 * @author hal.hildebrand
 */
public class TwoBubbleSyncStrategy implements GhostSyncCoordinator.GhostSyncStrategy {

    private static final Logger log = LoggerFactory.getLogger(TwoBubbleSyncStrategy.class);

    private final VonBubble bubble1;
    private final VonBubble bubble2;
    private final float boundaryX;
    private final float ghostBoundaryWidth;
    private final int ghostTtlTicks;
    private final Map<String, Vector3f> velocities1;
    private final Map<String, Vector3f> velocities2;

    private final Map<String, GhostSyncCoordinator.GhostEntry> ghostsInBubble1 = new ConcurrentHashMap<>();
    private final Map<String, GhostSyncCoordinator.GhostEntry> ghostsInBubble2 = new ConcurrentHashMap<>();

    /**
     * Create a two-bubble sync strategy.
     *
     * @param bubble1            First bubble
     * @param bubble2            Second bubble
     * @param boundaryX          X coordinate dividing the bubbles
     * @param ghostBoundaryWidth Distance from boundary to ghost entities
     * @param ghostTtlTicks      Ghost time-to-live in ticks
     * @param velocities1        Velocity map for bubble 1
     * @param velocities2        Velocity map for bubble 2
     */
    public TwoBubbleSyncStrategy(VonBubble bubble1, VonBubble bubble2, float boundaryX,
                                  float ghostBoundaryWidth, int ghostTtlTicks,
                                  Map<String, Vector3f> velocities1,
                                  Map<String, Vector3f> velocities2) {
        this.bubble1 = bubble1;
        this.bubble2 = bubble2;
        this.boundaryX = boundaryX;
        this.ghostBoundaryWidth = ghostBoundaryWidth;
        this.ghostTtlTicks = ghostTtlTicks;
        this.velocities1 = velocities1;
        this.velocities2 = velocities2;
    }

    @Override
    public void syncGhosts(long currentTick) {
        long expirationTick = currentTick + ghostTtlTicks;

        // Find entities near boundary in bubble1, ghost them to bubble2
        for (var entity : bubble1.getAllEntityRecords()) {
            float distFromBoundary = boundaryX - entity.position().x;
            if (distFromBoundary >= 0 && distFromBoundary < ghostBoundaryWidth) {
                var velocity = velocities1.get(entity.id());
                var ghost = new GhostSyncCoordinator.GhostEntry(
                    entity.id(),
                    new Point3f(entity.position()),
                    velocity != null ? new Vector3f(velocity) : new Vector3f(),
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
                var ghost = new GhostSyncCoordinator.GhostEntry(
                    entity.id(),
                    new Point3f(entity.position()),
                    velocity != null ? new Vector3f(velocity) : new Vector3f(),
                    expirationTick
                );
                ghostsInBubble1.put(entity.id(), ghost);
            }
        }

        log.trace("Ghost sync: {} ghosts in bubble1, {} in bubble2",
                  ghostsInBubble1.size(), ghostsInBubble2.size());
    }

    @Override
    public void expireGhosts(long currentTick) {
        ghostsInBubble1.entrySet().removeIf(e -> e.getValue().expirationTick() <= currentTick);
        ghostsInBubble2.entrySet().removeIf(e -> e.getValue().expirationTick() <= currentTick);
    }

    @Override
    public Map<String, GhostSyncCoordinator.GhostEntry> getGhostsInBubble1() {
        return ghostsInBubble1;
    }

    @Override
    public Map<String, GhostSyncCoordinator.GhostEntry> getGhostsInBubble2() {
        return ghostsInBubble2;
    }

    @Override
    public void clear() {
        ghostsInBubble1.clear();
        ghostsInBubble2.clear();
    }
}
