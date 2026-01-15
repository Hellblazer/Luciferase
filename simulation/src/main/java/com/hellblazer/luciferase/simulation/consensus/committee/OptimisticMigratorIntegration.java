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

package com.hellblazer.luciferase.simulation.consensus.committee;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter bridging OptimisticMigrator to ViewCommitteeConsensus.
 * <p>
 * Called from OptimisticMigratorImpl.requestMigrationApproval() to obtain
 * committee consensus before initiating entity migration.
 * <p>
 * WORKFLOW:
 * 1. requestMigrationApproval(entityId, sourceId, targetNodeId)
 * 2. Create MigrationProposal with current viewId from FirefliesViewMonitor
 * 3. Submit to ViewCommitteeConsensus.requestConsensus()
 * 4. On approval, record migration approval for tracking
 * 5. Return CompletableFuture<Boolean> to caller
 * <p>
 * VIEW ID TAGGING:
 * Proposals are tagged with getCurrentViewId() to prevent double-commit across view boundaries.
 * If view changes during voting, proposal is automatically aborted.
 * <p>
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for tracking migration approvals.
 * <p>
 * Phase 7G Day 3: ViewCommitteeConsensus & OptimisticMigrator Integration
 *
 * @author hal.hildebrand
 */
public class OptimisticMigratorIntegration {

    private static final Logger log = LoggerFactory.getLogger(OptimisticMigratorIntegration.class);

    private final ViewCommitteeConsensus consensus;
    private final FirefliesViewMonitor viewMonitor;
    private volatile Clock clock = Clock.system();

    /**
     * Track approved migrations for diagnostics and validation.
     * Key: entityId, Value: target node ID
     */
    private final ConcurrentHashMap<UUID, Digest> approvedMigrations = new ConcurrentHashMap<>();

    /**
     * Track last proposal for testing.
     */
    private volatile MigrationProposal lastProposal;

    /**
     * Create OptimisticMigratorIntegration.
     *
     * @param consensus ViewCommitteeConsensus orchestrator
     * @param viewMonitor FirefliesViewMonitor for current view ID
     */
    public OptimisticMigratorIntegration(ViewCommitteeConsensus consensus, FirefliesViewMonitor viewMonitor) {
        this.consensus = Objects.requireNonNull(consensus, "consensus must not be null");
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");
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
     * Request migration approval from committee consensus.
     * <p>
     * Creates MigrationProposal tagged with current viewId and submits to consensus.
     * On approval, records migration for tracking.
     *
     * @param entityId Entity to migrate
     * @param sourceId Source node ID
     * @param targetNodeId Target node ID
     * @return CompletableFuture<Boolean> - true if approved, false if rejected or view changed
     */
    public CompletableFuture<Boolean> requestMigrationApproval(UUID entityId, Digest sourceId, Digest targetNodeId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");

        // Create proposal with current view ID (CRITICAL for race prevention)
        var currentViewId = getCurrentViewId();
        var proposal = new MigrationProposal(
            UUID.randomUUID(),
            entityId,
            sourceId,
            targetNodeId,
            currentViewId,
            clock.currentTimeMillis()
        );

        // Store for testing
        lastProposal = proposal;

        log.debug("Requesting migration approval: entity={}, source={}, target={}, view={}",
                 entityId, sourceId, targetNodeId, currentViewId);

        // Submit to consensus
        return consensus.requestConsensus(proposal)
            .thenApply(approved -> {
                if (approved) {
                    recordMigrationApproval(entityId, targetNodeId);
                    log.debug("Migration approved: entity={} → target={}", entityId, targetNodeId);
                } else {
                    log.debug("Migration rejected: entity={} → target={}", entityId, targetNodeId);
                }
                return approved;
            });
    }

    /**
     * Record migration approval for tracking.
     * <p>
     * Used for diagnostics and validation.
     *
     * @param entityId entity being migrated
     * @param targetNodeId target node
     */
    private void recordMigrationApproval(UUID entityId, Digest targetNodeId) {
        approvedMigrations.put(entityId, targetNodeId);
    }

    /**
     * Check if entity has migration approval.
     * <p>
     * Used by tests to verify approval tracking.
     *
     * @param entityId entity to check
     * @return true if migration approved
     */
    public boolean hasMigrationApproval(UUID entityId) {
        return approvedMigrations.containsKey(entityId);
    }

    /**
     * Get last proposal created (for testing).
     *
     * @return last MigrationProposal
     */
    public MigrationProposal getLastProposal() {
        return lastProposal;
    }

    /**
     * Get current view ID from monitor.
     *
     * @return current view ID
     */
    private Digest getCurrentViewId() {
        return viewMonitor.getCurrentViewId();
    }

    /**
     * Clear all approved migrations (for testing or recovery).
     */
    public void clearApprovals() {
        approvedMigrations.clear();
        log.debug("Cleared all migration approvals");
    }
}
