/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.migration;

import java.util.UUID;

/**
 * OptimisticMigrator - Speculative entity migration with deferred updates (Phase 7E Day 3)
 *
 * Handles optimistic migration of entities across bubble boundaries with deferred physics updates
 * and automatic rollback on view changes.
 *
 * WORKFLOW:
 * Source bubble:
 * - OWNED entity crosses boundary
 * - MigrationOracle triggers initiateOptimisticMigration()
 * - OWNED → MIGRATING_OUT (physics frozen)
 * - EntityDepartureEvent sent immediately to target
 *
 * Target bubble:
 * - GHOST entity receives EntityDepartureEvent
 * - GHOST → MIGRATING_IN (physics deferred)
 * - Updates queued until view stable (3 ticks)
 * - MIGRATING_IN → OWNED (deferred queue flushed)
 * - ViewSynchronyAck sent to source
 *
 * Rollback (on view change):
 * - Source: MIGRATING_OUT → ROLLBACK_OWNED (resume physics)
 * - Target: MIGRATING_IN → GHOST (abandon migration)
 * - EntityRollbackEvent sent both directions
 *
 * DEFERRED QUEUE:
 * - Max 100 events per entity (prevents memory leak)
 * - Queued during MIGRATING_IN state
 * - Flushed when transitioned to OWNED
 * - Overflow: log warning, drop oldest events
 *
 * PERFORMANCE:
 * - initiateOptimisticMigration: O(1)
 * - queueDeferredUpdate: O(1) amortized
 * - flushDeferredUpdates: O(n) where n = queued events
 * - Target: < 20ms for 100 simultaneous migrations
 *
 * THREAD SAFETY:
 * Uses concurrent collections for multi-threaded entity updates.
 *
 * @author hal.hildebrand
 */
public interface OptimisticMigrator {

    /**
     * Initiate optimistic migration of entity to target bubble.
     * Sends EntityDepartureEvent immediately without waiting for acknowledgement.
     * Source FSM transition: OWNED → MIGRATING_OUT
     * Physics frozen on source during migration.
     *
     * @param entityId Entity being migrated
     * @param targetBubbleId Target bubble UUID
     * @throws NullPointerException if either parameter is null
     */
    void initiateOptimisticMigration(UUID entityId, UUID targetBubbleId);

    /**
     * Queue physics update for entity in MIGRATING_IN state.
     * Updates are deferred until view stability confirmed (3 ticks).
     * After MIGRATING_IN → OWNED transition, queued updates are flushed.
     *
     * @param entityId Entity receiving update
     * @param position Updated world position (x, y, z)
     * @param velocity Updated velocity vector (vx, vy, vz)
     * @throws NullPointerException if entityId is null or position/velocity missing
     * @throws IllegalStateException if queue exceeds 100 events (log warning, drop oldest)
     */
    void queueDeferredUpdate(UUID entityId, float[] position, float[] velocity);

    /**
     * Flush deferred updates when entity transitions MIGRATING_IN → OWNED.
     * Called after view stability confirmed to apply all queued physics updates
     * and transition entity to full ownership on target bubble.
     *
     * @param entityId Entity being promoted to OWNED
     * @throws IllegalStateException if no deferred updates queue exists
     */
    void flushDeferredUpdates(UUID entityId);

    /**
     * Rollback migration on view change.
     * Called when group membership changes, forcing entity back to source.
     * Source FSM transition: MIGRATING_OUT → ROLLBACK_OWNED (resume physics)
     * Target FSM transition: MIGRATING_IN → GHOST (abandon migration)
     * Sends EntityRollbackEvent to both source and target.
     *
     * @param entityId Entity being rolled back
     * @param reason Rollback reason for logging: "view_change", "timeout", or "manual"
     * @throws NullPointerException if entityId or reason is null
     */
    void rollbackMigration(UUID entityId, String reason);

    /**
     * Get count of entities with pending deferred updates.
     * Used for monitoring and diagnostics.
     *
     * @return Number of entities with non-empty deferred queues
     */
    int getPendingDeferredCount();

    /**
     * Get size of deferred queue for specific entity.
     * Used for monitoring queue overflow conditions.
     *
     * @param entityId Entity to query
     * @return Queue size (0 if no pending updates)
     */
    int getDeferredQueueSize(UUID entityId);

    /**
     * Clear all deferred updates (emergency cleanup).
     * Used during shutdown or view change rollback.
     */
    void clearAllDeferred();
}
