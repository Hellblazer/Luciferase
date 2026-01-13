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
import com.hellblazer.luciferase.simulation.consensus.committee.MigrationProposal;
import com.hellblazer.luciferase.simulation.consensus.committee.ViewCommitteeConsensus;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Coordinates migration proposal lifecycle with ViewCommitteeConsensus.
 * <p>
 * Manages:
 * - Proposal creation with current view ID
 * - Approval/rejection callbacks
 * - View change rollback for pending proposals
 * - Timeout handling
 * <p>
 * WORKFLOW:
 * 1. createMigrationProposal(entityId, sourceId, targetId, callback)
 * 2. Create MigrationProposal with current view ID
 * 3. Submit to ViewCommitteeConsensus.requestConsensus()
 * 4. On approval/rejection: invoke callback
 * 5. On view change: roll back all pending proposals
 * <p>
 * VIEW CHANGE HANDLING:
 * When view changes, all pending proposals from old view are rolled back
 * (completed exceptionally) to prevent stale migrations.
 * <p>
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for tracking pending proposals.
 * <p>
 * Phase 8A Day 1: Consensus-Migration Integration
 *
 * @author hal.hildebrand
 */
public class ConsensusMigrationIntegration {

    private static final Logger log = LoggerFactory.getLogger(ConsensusMigrationIntegration.class);

    private final ViewCommitteeConsensus consensus;
    private volatile Digest currentViewId;

    /**
     * Track pending proposals for view change rollback.
     * Key: proposalId, Value: callback for rollback notification
     */
    private final ConcurrentHashMap<UUID, Consumer<Boolean>> pendingProposals = new ConcurrentHashMap<>();
    private volatile Clock clock = Clock.system();

    /**
     * Create ConsensusMigrationIntegration.
     *
     * @param consensus      ViewCommitteeConsensus for voting
     * @param currentViewId  Current view ID
     */
    public ConsensusMigrationIntegration(ViewCommitteeConsensus consensus, Digest currentViewId) {
        this.consensus = Objects.requireNonNull(consensus, "consensus must not be null");
        this.currentViewId = Objects.requireNonNull(currentViewId, "currentViewId must not be null");
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Create migration proposal and submit to consensus.
     * <p>
     * Proposal tagged with current view ID to prevent cross-view double-commit.
     *
     * @param entityId  Entity being migrated
     * @param sourceId  Source node ID
     * @param targetId  Target node ID
     * @param callback  Callback with approval result (true=approved, false=rejected)
     */
    public void createMigrationProposal(UUID entityId, Digest sourceId, Digest targetId, Consumer<Boolean> callback) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");

        // Create proposal with current view ID
        var proposalId = UUID.randomUUID();
        var proposal = new MigrationProposal(
            proposalId,
            entityId,
            sourceId,
            targetId,
            currentViewId,
            clock.currentTimeMillis()
        );

        // Track pending proposal with callback for view change rollback
        if (callback != null) {
            pendingProposals.put(proposalId, callback);
        }

        log.debug("Creating migration proposal {}: entity={}, source={}, target={}, view={}",
                 proposalId, entityId, sourceId, targetId, currentViewId);

        // Submit to consensus
        consensus.requestConsensus(proposal)
            .thenAccept(approved -> {
                // Consensus decision received
                pendingProposals.remove(proposalId);

                if (approved) {
                    log.debug("Proposal {} approved by consensus", proposalId);
                } else {
                    log.debug("Proposal {} rejected by consensus", proposalId);
                }

                if (callback != null) {
                    callback.accept(approved);
                }
            })
            .exceptionally(ex -> {
                // Consensus failed (timeout, view change, etc.)
                pendingProposals.remove(proposalId);
                log.warn("Proposal {} failed: {}", proposalId, ex.getMessage());

                if (callback != null) {
                    callback.accept(false); // Treat failures as rejection
                }
                return null;
            });
    }

    /**
     * Convenience method without callback.
     *
     * @param entityId Entity being migrated
     * @param sourceId Source node ID
     * @param targetId Target node ID
     */
    public void createMigrationProposal(UUID entityId, Digest sourceId, Digest targetId) {
        createMigrationProposal(entityId, sourceId, targetId, null);
    }

    /**
     * Handle view change by rolling back all pending proposals.
     * <p>
     * Proposals from old view are aborted to prevent stale migrations.
     * Current view ID updated for future proposals.
     *
     * @param newViewId New view ID after change
     */
    public void onViewChange(Digest newViewId) {
        log.info("View change detected: old={}, new={}, pending={}",
                currentViewId, newViewId, pendingProposals.size());

        // Update current view ID
        currentViewId = newViewId;

        // Roll back all pending proposals (they're from old view)
        // Invoke callbacks with rejection before clearing
        var rolledBack = pendingProposals.size();
        pendingProposals.values().forEach(callback -> {
            try {
                callback.accept(false); // View change = rejection
            } catch (Exception e) {
                log.warn("Error invoking rollback callback: {}", e.getMessage());
            }
        });
        pendingProposals.clear();

        log.info("Rolled back {} pending proposals from old view", rolledBack);
    }

    /**
     * Check if there are pending proposals.
     * <p>
     * Used for diagnostics and testing.
     *
     * @return true if any proposals pending
     */
    public boolean hasPendingProposals() {
        return !pendingProposals.isEmpty();
    }

    /**
     * Get current view ID.
     * <p>
     * Used for testing view change handling.
     *
     * @return current view ID
     */
    public Digest getCurrentViewId() {
        return currentViewId;
    }
}
