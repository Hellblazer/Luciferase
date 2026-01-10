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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Main orchestrator for committee-based consensus on entity migrations.
 * <p>
 * Coordinates:
 * 1. ViewCommitteeSelector - Selects BFT committee from current view
 * 2. CommitteeVotingProtocol - Manages voting FSM and quorum detection
 * 3. FirefliesViewMonitor - Tracks current view ID for race condition prevention
 * <p>
 * CRITICAL: View ID Verification
 * <p>
 * Prevents double-commit race condition identified by substantive-critic:
 * <pre>
 * Timeline (WITHOUT view ID check):
 * t1: Committee approves E: A→B (viewId=V1)
 * t2: View changes to V2
 * t3: New committee approves E: C→D (viewId=V2)
 * t4: E ends up in both B and D ← CORRUPTION!
 * </pre>
 * <p>
 * Solution: Check proposal.viewId() == getCurrentViewId() before execution.
 * If view changed, abort proposal and return false (triggers retry in new view).
 * <p>
 * WORKFLOW:
 * 1. requestConsensus(proposal) called with current view ID
 * 2. Verify proposal.viewId matches current view (abort if stale)
 * 3. Select committee using ViewCommitteeSelector
 * 4. Submit to CommitteeVotingProtocol for voting
 * 5. Wait for vote result via CompletableFuture
 * 6. Before executing, verify viewId still matches (abort if changed)
 * 7. Return approval decision
 * <p>
 * VIEW CHANGE HANDLING:
 * - onViewChange(newViewId) rolls back all pending proposals from old views
 * - Fireflies guarantees atomic view changes (Virtual Synchrony)
 * - Pending proposals complete exceptionally with IllegalStateException
 * <p>
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for tracking proposals in-flight.
 * <p>
 * Phase 7G Day 3: ViewCommitteeConsensus & OptimisticMigrator Integration
 *
 * @author hal.hildebrand
 */
public class ViewCommitteeConsensus {

    private static final Logger log = LoggerFactory.getLogger(ViewCommitteeConsensus.class);

    private FirefliesViewMonitor viewMonitor;
    private ViewCommitteeSelector committeeSelector;
    private CommitteeVotingProtocol votingProtocol;

    /**
     * Track proposals in-flight for view change rollback.
     * Key: proposalId, Value: proposal metadata
     */
    private final ConcurrentHashMap<UUID, ProposalTracking> pendingProposals = new ConcurrentHashMap<>();

    /**
     * Set dependencies via dependency injection.
     *
     * @param monitor ViewMonitor for tracking current view
     */
    public void setViewMonitor(FirefliesViewMonitor monitor) {
        this.viewMonitor = Objects.requireNonNull(monitor, "viewMonitor must not be null");
    }

    /**
     * Set committee selector.
     *
     * @param selector ViewCommitteeSelector for BFT committee selection
     */
    public void setCommitteeSelector(ViewCommitteeSelector selector) {
        this.committeeSelector = Objects.requireNonNull(selector, "committeeSelector must not be null");
    }

    /**
     * Set voting protocol.
     *
     * @param protocol CommitteeVotingProtocol for vote aggregation
     */
    public void setVotingProtocol(CommitteeVotingProtocol protocol) {
        this.votingProtocol = Objects.requireNonNull(protocol, "votingProtocol must not be null");
    }

    /**
     * Request consensus for a migration proposal.
     * <p>
     * CRITICAL: View ID verification prevents double-commit race condition.
     * <p>
     * Workflow:
     * 1. Check proposal.viewId == getCurrentViewId() (abort if stale)
     * 2. Select committee for current view
     * 3. Submit to voting protocol
     * 4. Return CompletableFuture that resolves to approval decision
     * 5. Before execution, verify viewId still matches (abort if changed)
     *
     * @param proposal migration proposal with viewId
     * @return CompletableFuture<Boolean> - true if approved, false if rejected or view changed
     */
    public CompletableFuture<Boolean> requestConsensus(MigrationProposal proposal) {
        Objects.requireNonNull(proposal, "proposal must not be null");

        var currentViewId = getCurrentViewId();

        // CRITICAL: View ID verification - abort if proposal from old view
        if (!proposal.viewId().equals(currentViewId)) {
            log.debug("Proposal {} has stale viewId (proposal={}, current={}), aborting",
                     proposal.proposalId(), proposal.viewId(), currentViewId);
            return CompletableFuture.completedFuture(false);
        }

        // Select committee for current view
        var committee = committeeSelector.selectCommittee(currentViewId);
        var committeeIds = committee.stream()
                                    .map(member -> member.getId())
                                    .collect(Collectors.toSet());

        log.debug("Requesting consensus for proposal {}: entity={}, target={}, view={}, committee={}",
                 proposal.proposalId(),
                 proposal.entityId(),
                 proposal.targetNodeId(),
                 proposal.viewId(),
                 committeeIds.size());

        // Track proposal for view change rollback
        var tracking = new ProposalTracking(proposal, committeeIds);
        pendingProposals.put(proposal.proposalId(), tracking);

        // Submit to voting protocol
        var votingFuture = votingProtocol.requestConsensus(proposal, committeeIds);

        // Wrap voting future with view ID verification
        return votingFuture.thenApply(approved -> {
            // CRITICAL: Double-check viewId before allowing execution
            if (!proposal.viewId().equals(getCurrentViewId())) {
                log.warn("View changed during voting for proposal {}, aborting execution", proposal.proposalId());
                pendingProposals.remove(proposal.proposalId());
                return false;  // Abort - view changed
            }

            // Success - remove from pending
            pendingProposals.remove(proposal.proposalId());
            log.debug("Consensus result for proposal {}: approved={}", proposal.proposalId(), approved);
            return approved;
        }).exceptionally(ex -> {
            // Voting failed (timeout or view change)
            log.debug("Consensus failed for proposal {}: {}", proposal.proposalId(), ex.getMessage());
            pendingProposals.remove(proposal.proposalId());
            return false;
        });
    }

    /**
     * Called when view changes.
     * <p>
     * Rolls back all pending proposals from old views (Virtual Synchrony guarantee).
     * Completes their futures exceptionally with IllegalStateException.
     *
     * @param newViewId new view ID after change
     */
    public void onViewChange(Digest newViewId) {
        log.info("View change detected: newView={}, pending={}", newViewId, pendingProposals.size());

        // Fireflies guarantees atomic view change delivery (Virtual Synchrony)
        // Roll back ALL pending proposals from old views
        votingProtocol.rollbackOnViewChange(newViewId);

        // Clean up pending proposals
        pendingProposals.forEach((proposalId, tracking) -> {
            if (!tracking.proposal.viewId().equals(newViewId)) {
                log.debug("Rolling back proposal {} from old view {}", proposalId, tracking.proposal.viewId());
                pendingProposals.remove(proposalId);
            }
        });
    }

    /**
     * Check if migration can be executed (view ID matches).
     * <p>
     * Used by tests to verify double-commit prevention.
     *
     * @param proposal proposal to check
     * @param entityId entity being migrated
     * @return true if proposal viewId matches current view
     */
    public boolean canExecuteMigration(MigrationProposal proposal, UUID entityId) {
        return proposal.viewId().equals(getCurrentViewId());
    }

    /**
     * Check if there are pending proposals.
     * <p>
     * Used by tests to verify rollback behavior.
     *
     * @return true if any proposals are pending
     */
    public boolean hasPendingProposals() {
        return !pendingProposals.isEmpty();
    }

    /**
     * Get current view ID from monitor.
     * <p>
     * Wraps FirefliesViewMonitor.getCurrentViewId() for cleaner code.
     *
     * @return current view ID (Fireflies diadem)
     */
    private Digest getCurrentViewId() {
        return viewMonitor.getCurrentViewId();
    }

    /**
     * Internal tracking for pending proposals.
     * <p>
     * Used for view change rollback and diagnostics.
     */
    private static class ProposalTracking {
        final MigrationProposal proposal;
        final Set<Digest> committee;

        ProposalTracking(MigrationProposal proposal, Set<Digest> committee) {
            this.proposal = proposal;
            this.committee = committee;
        }
    }
}
