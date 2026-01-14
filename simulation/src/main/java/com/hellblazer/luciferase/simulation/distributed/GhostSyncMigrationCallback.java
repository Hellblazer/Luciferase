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

import java.util.UUID;

/**
 * Ghost synchronization callback for entity migration lifecycle.
 * <p>
 * Coordinates ghost layer with migration lifecycle to prevent ghost-real entity
 * conflicts and ensure consistent ghost state across bubbles. Implements the
 * integration between CrossBubbleMigrationManager and GhostSyncCoordinator.
 * <p>
 * Problem Being Solved:
 * <pre>
 * When entity migrates from Bubble A to Bubble B:
 *   1. Pre-migration: Entity may exist as ghost in Bubble B
 *   2. During migration: Need to remove ghost before adding real entity
 *   3. Post-migration: Need to sync ghost to other bubbles (C, D, etc.)
 * </pre>
 * <p>
 * Solution:
 * <ul>
 *   <li>onMigrationPrepare(): Remove ghost from target bubble (prevent conflict)</li>
 *   <li>onMigrationCommit(): Trigger ghost sync to neighbor bubbles</li>
 *   <li>onMigrationRollback(): Restore ghost in target if needed</li>
 * </ul>
 * <p>
 * Thread Safety: All ghost map operations use ConcurrentHashMap in GhostSyncCoordinator,
 * safe for concurrent access from migration and tick threads.
 * <p>
 * Related:
 * <ul>
 *   <li>M2 Phase 4: simulation/doc/GHOST_LAYER_CONSOLIDATION_ANALYSIS.md</li>
 *   <li>Used by: {@link TwoBubbleSimulation}</li>
 *   <li>Coordinates: {@link CrossBubbleMigrationManager} + {@link GhostSyncCoordinator}</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class GhostSyncMigrationCallback implements MigrationLifecycleCallbacks {

    private static final Logger log = LoggerFactory.getLogger(GhostSyncMigrationCallback.class);

    private final GhostSyncCoordinator ghostSyncCoordinator;
    private final VonBubble bubble1;
    private final VonBubble bubble2;

    /**
     * Create a ghost sync migration callback.
     *
     * @param ghostSyncCoordinator Ghost sync coordinator
     * @param bubble1              First bubble
     * @param bubble2              Second bubble
     */
    public GhostSyncMigrationCallback(GhostSyncCoordinator ghostSyncCoordinator,
                                       VonBubble bubble1, VonBubble bubble2) {
        this.ghostSyncCoordinator = ghostSyncCoordinator;
        this.bubble1 = bubble1;
        this.bubble2 = bubble2;
    }

    @Override
    public void onMigrationPrepare(String entityId, UUID sourceBubble, UUID targetBubble) throws Exception {
        // Remove ghost from target bubble if it exists to prevent conflict with real entity
        // The ghost will be recreated by the next ghost sync if the entity is near the boundary
        var targetGhosts = getGhostsForBubble(targetBubble);
        if (targetGhosts != null) {
            var removed = targetGhosts.remove(entityId);
            if (removed != null) {
                log.debug("Removed ghost {} from target bubble {} before migration", entityId, targetBubble);
            }
        }
    }

    @Override
    public void onMigrationCommit(String entityId, UUID sourceBubble, UUID targetBubble) {
        // Ghost sync is already handled by the periodic ghost sync in TwoBubbleSimulation.tick()
        // No explicit sync needed here - the next sync interval will pick up the migrated entity
        log.trace("Entity {} migrated from {} to {} - ghost sync will occur on next interval",
                  entityId, sourceBubble, targetBubble);
    }

    @Override
    public void onMigrationRollback(String entityId, UUID sourceBubble, UUID targetBubble) {
        // Ghost will be restored by the next ghost sync if the entity is near the boundary
        // No explicit action needed - the ghost sync strategy handles boundary detection
        log.debug("Migration rollback for entity {} - ghost will be restored by next sync if needed", entityId);
    }

    /**
     * Get the ghost map for a specific bubble.
     *
     * @param bubbleUuid Bubble UUID
     * @return Ghost map for the bubble, or null if not found
     */
    private java.util.Map<String, GhostSyncCoordinator.GhostEntry> getGhostsForBubble(UUID bubbleUuid) {
        if (bubbleUuid.equals(bubble1.id())) {
            return ghostSyncCoordinator.getGhostsInBubble1();
        } else if (bubbleUuid.equals(bubble2.id())) {
            return ghostSyncCoordinator.getGhostsInBubble2();
        }
        return null;
    }
}
