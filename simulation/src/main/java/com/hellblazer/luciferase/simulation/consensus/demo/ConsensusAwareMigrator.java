/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.consensus.demo;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.luciferase.simulation.consensus.committee.OptimisticMigratorIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Consensus-aware entity migration coordinator.
 * <p>
 * Bridges entity migration requests to committee consensus voting. Distinguishes between:
 * - Cross-bubble migrations: Require consensus approval from ViewCommitteeConsensus
 * - Intra-bubble migrations: Use local authority (bypass consensus for performance)
 * <p>
 * WORKFLOW:
 * 1. requestMigration(entityId, targetBubbleId, callback)
 * 2. If targetBubbleId == localBubbleId: approve immediately (local authority)
 * 3. If targetBubbleId != localBubbleId: request consensus approval
 * 4. On approval/rejection: invoke callback with result
 * <p>
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for tracking pending migrations.
 * Callbacks invoked asynchronously from consensus CompletableFuture threads.
 * <p>
 * Phase 8A Day 1: Consensus-Migration Integration
 *
 * @author hal.hildebrand
 */
public class ConsensusAwareMigrator {

    private static final Logger log = LoggerFactory.getLogger(ConsensusAwareMigrator.class);

    private final OptimisticMigratorIntegration consensusIntegration;
    private final Digest localBubbleId;
    private final Digest currentNodeId;

    /**
     * Track pending migrations for diagnostics.
     * Key: entityId, Value: target bubble ID
     */
    private final ConcurrentHashMap<UUID, Digest> pendingMigrations = new ConcurrentHashMap<>();

    /**
     * Create ConsensusAwareMigrator.
     *
     * @param consensusIntegration OptimisticMigratorIntegration for consensus voting
     * @param localBubbleId        ID of this bubble (for cross-bubble detection)
     * @param currentNodeId        ID of this node (source for migrations)
     */
    public ConsensusAwareMigrator(OptimisticMigratorIntegration consensusIntegration, Digest localBubbleId,
                                  Digest currentNodeId) {
        this.consensusIntegration = Objects.requireNonNull(consensusIntegration, "consensusIntegration must not be null");
        this.localBubbleId = Objects.requireNonNull(localBubbleId, "localBubbleId must not be null");
        this.currentNodeId = Objects.requireNonNull(currentNodeId, "currentNodeId must not be null");
    }

    /**
     * Request entity migration with consensus coordination.
     * <p>
     * Cross-bubble migrations require consensus approval.
     * Intra-bubble migrations use local authority (immediate approval).
     *
     * @param entityId       Entity to migrate
     * @param targetBubbleId Target bubble ID
     * @param callback       Callback with approval result (true=approved, false=rejected)
     */
    public void requestMigration(UUID entityId, Digest targetBubbleId, Consumer<Boolean> callback) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(targetBubbleId, "targetBubbleId must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        // Track pending migration
        pendingMigrations.put(entityId, targetBubbleId);

        if (isIntraBubbleMigration(targetBubbleId)) {
            // Intra-bubble migration: local authority, immediate approval
            log.debug("Intra-bubble migration for entity {}: approved locally", entityId);
            pendingMigrations.remove(entityId);
            callback.accept(true);
        } else {
            // Cross-bubble migration: requires consensus
            log.debug("Cross-bubble migration for entity {} to {}: requesting consensus",
                     entityId, targetBubbleId);

            consensusIntegration.requestMigrationApproval(entityId, currentNodeId, targetBubbleId)
                .thenAccept(approved -> {
                    // Consensus decision received
                    pendingMigrations.remove(entityId);

                    if (approved) {
                        log.debug("Consensus approved migration for entity {} to {}", entityId, targetBubbleId);
                    } else {
                        log.debug("Consensus rejected migration for entity {} to {}", entityId, targetBubbleId);
                    }

                    callback.accept(approved);
                })
                .exceptionally(ex -> {
                    // Consensus failed (timeout, view change, etc.)
                    pendingMigrations.remove(entityId);
                    log.warn("Consensus voting failed for entity {} to {}: {}",
                            entityId, targetBubbleId, ex.getMessage());
                    callback.accept(false); // Treat failures as rejection
                    return null;
                });
        }
    }

    /**
     * Check if entity has pending migration.
     * <p>
     * Used for diagnostics and testing.
     *
     * @param entityId Entity to check
     * @return true if migration pending
     */
    public boolean hasPendingMigration(UUID entityId) {
        return pendingMigrations.containsKey(entityId);
    }

    /**
     * Check if migration is intra-bubble (same bubble, no consensus needed).
     *
     * @param targetBubbleId Target bubble ID
     * @return true if target is same as local bubble
     */
    private boolean isIntraBubbleMigration(Digest targetBubbleId) {
        return localBubbleId.equals(targetBubbleId);
    }
}
