/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import java.util.UUID;

/**
 * Lifecycle callbacks for entity migrations between bubbles.
 * <p>
 * Provides hooks for coordinating auxiliary systems (ghost layer, metrics,
 * visualization) with the migration lifecycle. Callbacks are invoked at
 * key points in the two-phase commit protocol:
 * <ul>
 *   <li>onMigrationPrepare: Before migration starts (PREPARE phase)</li>
 *   <li>onMigrationCommit: After successful migration (COMMIT phase)</li>
 *   <li>onMigrationRollback: If migration fails (ROLLBACK)</li>
 * </ul>
 * <p>
 * Use Case: Ghost Layer Integration
 * <pre>
 * Problem: When entity migrates from Bubble A to Bubble B:
 *   1. Pre-migration: Entity may exist as ghost in Bubble B
 *   2. During migration: Need to remove ghost before adding real entity
 *   3. Post-migration: Need to sync ghost to other bubbles (C, D, etc.)
 *
 * Solution: GhostSyncMigrationCallback implements this interface to:
 *   - onMigrationPrepare(): Remove ghost from target bubble (prevent conflict)
 *   - onMigrationCommit(): Trigger ghost sync to neighbor bubbles
 *   - onMigrationRollback(): Restore ghost in target if needed
 * </pre>
 * <p>
 * Thread Safety: Callbacks are invoked from the migration manager's thread.
 * Implementations must be thread-safe if they access shared state.
 * <p>
 * Related:
 * <ul>
 *   <li>M2 Phase 4: simulation/doc/GHOST_LAYER_CONSOLIDATION_ANALYSIS.md</li>
 *   <li>Used by: {@link CrossBubbleMigrationManager}</li>
 *   <li>Implementation: {@link GhostSyncMigrationCallback}</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public interface MigrationLifecycleCallbacks {

    /**
     * Called before migration starts (PREPARE phase).
     * <p>
     * Use this to prepare auxiliary systems for the migration. For example:
     * <ul>
     *   <li>Remove ghost from target bubble (prevent conflict with real entity)</li>
     *   <li>Pre-allocate resources in target bubble</li>
     *   <li>Record migration intent for auditing</li>
     * </ul>
     * <p>
     * If this method throws an exception, the migration will not proceed.
     *
     * @param entityId     Entity being migrated
     * @param sourceBubble Source bubble UUID
     * @param targetBubble Target bubble UUID
     * @throws Exception if preparation fails (migration will not proceed)
     */
    default void onMigrationPrepare(String entityId, UUID sourceBubble, UUID targetBubble) throws Exception {
        // Default: no-op
    }

    /**
     * Called after successful migration (COMMIT phase).
     * <p>
     * Use this to update auxiliary systems after entity is in target bubble. For example:
     * <ul>
     *   <li>Trigger ghost sync to neighbor bubbles</li>
     *   <li>Update visualization state</li>
     *   <li>Record migration metrics</li>
     * </ul>
     * <p>
     * This method is called after the entity is successfully added to the target
     * bubble and removed from the source bubble. If this method throws an exception,
     * it is logged but the migration is already committed.
     *
     * @param entityId     Entity that was migrated
     * @param sourceBubble Source bubble UUID
     * @param targetBubble Target bubble UUID
     */
    default void onMigrationCommit(String entityId, UUID sourceBubble, UUID targetBubble) {
        // Default: no-op
    }

    /**
     * Called if migration fails (ROLLBACK).
     * <p>
     * Use this to restore auxiliary systems to pre-migration state. For example:
     * <ul>
     *   <li>Restore ghost in target bubble (if removed during prepare)</li>
     *   <li>Clean up pre-allocated resources</li>
     *   <li>Record migration failure</li>
     * </ul>
     * <p>
     * This method is called if the migration fails after onMigrationPrepare
     * succeeded. The entity remains in the source bubble.
     *
     * @param entityId     Entity that failed to migrate
     * @param sourceBubble Source bubble UUID
     * @param targetBubble Target bubble UUID
     */
    default void onMigrationRollback(String entityId, UUID sourceBubble, UUID targetBubble) {
        // Default: no-op
    }
}
