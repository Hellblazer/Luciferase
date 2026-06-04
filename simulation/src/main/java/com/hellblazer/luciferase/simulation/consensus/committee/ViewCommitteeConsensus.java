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
     * Per-entity in-flight migration guard (Luciferase-0frcy.94).
     * <p>
     * Maps entityId -&gt; the proposalId currently holding the migration slot for
     * that entity. While an entry exists, any further {@link #requestConsensus}
     * for the same entity is rejected so two concurrent proposals cannot both
     * reach quorum within the same view and double-register the entity at two
     * targets. The view-ID checks only guard cross-view staleness, not
     * within-view duplicate proposals — this map closes that gap.
     */
    private final ConcurrentHashMap<UUID, UUID> inFlightByEntity = new ConcurrentHashMap<>();

    /**
     * No-arg constructor for setter-based dependency injection. Callers MUST invoke
     * {@link #setViewMonitor}, {@link #setCommitteeSelector} and {@link #setVotingProtocol} before
     * {@link #requestConsensus}; {@code requestConsensus} fails fast with {@link IllegalStateException}
     * otherwise (Luciferase-0frcy.20).
     */
    public ViewCommitteeConsensus() {
    }

    /**
     * All-args constructor (preferred). Wires all collaborators up front so the instance is fully
     * initialized at construction time.
     *
     * @param viewMonitor       view monitor for tracking the current view ID
     * @param committeeSelector BFT committee selector
     * @param votingProtocol    voting protocol for vote aggregation
     */
    public ViewCommitteeConsensus(FirefliesViewMonitor viewMonitor, ViewCommitteeSelector committeeSelector,
                                  CommitteeVotingProtocol votingProtocol) {
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");
        this.committeeSelector = Objects.requireNonNull(committeeSelector, "committeeSelector must not be null");
        this.votingProtocol = Objects.requireNonNull(votingProtocol, "votingProtocol must not be null");
    }

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
     * 1. Validate proposal inputs (Byzantine protection)
     * 2. Check proposal.viewId == getCurrentViewId() (abort if stale)
     * 3. Select committee for current view
     * 4. Submit to voting protocol
     * 5. Return CompletableFuture that resolves to approval decision
     * 6. Before execution, verify viewId still matches (abort if changed)
     *
     * @param proposal migration proposal with viewId
     * @return CompletableFuture<Boolean> - true if approved, false if rejected or view changed
     */
    public CompletableFuture<Boolean> requestConsensus(MigrationProposal proposal) {
        Objects.requireNonNull(proposal, "proposal must not be null");

        // Lifecycle guard - all collaborators must be wired before use (Luciferase-0frcy.20)
        checkInitialized();

        // Byzantine input validation - protect against malicious proposals
        if (!validateProposal(proposal)) {
            return CompletableFuture.completedFuture(false);
        }

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

        // Per-entity in-flight guard (Luciferase-0frcy.94): reserve the migration
        // slot for this entity. If another proposal already holds it, reject this
        // one immediately so two concurrent proposals for the same entity cannot
        // both reach quorum and double-register the entity at two targets.
        var entityId = proposal.entityId();
        var existing = inFlightByEntity.putIfAbsent(entityId, proposal.proposalId());
        if (existing != null) {
            log.debug("Entity {} already has in-flight migration proposal {}, rejecting {}",
                     entityId, existing, proposal.proposalId());
            return CompletableFuture.completedFuture(false);
        }

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
                inFlightByEntity.remove(entityId, proposal.proposalId());
                return false;  // Abort - view changed
            }

            // Success - remove from pending
            pendingProposals.remove(proposal.proposalId());
            inFlightByEntity.remove(entityId, proposal.proposalId());
            log.debug("Consensus result for proposal {}: approved={}", proposal.proposalId(), approved);
            return approved;
        }).exceptionally(ex -> {
            // Voting failed (timeout or view change)
            log.debug("Consensus failed for proposal {}: {}", proposal.proposalId(), ex.getMessage());
            pendingProposals.remove(proposal.proposalId());
            inFlightByEntity.remove(entityId, proposal.proposalId());
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
                // Release the per-entity in-flight slot so the entity is not permanently
                // blocked after a view-change rollback (Luciferase-0frcy.94).
                inFlightByEntity.remove(tracking.proposal.entityId(), proposalId);
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
     * Validate migration proposal for Byzantine attacks.
     * <p>
     * Protection against malicious or invalid proposals:
     * 1. Entity ID validation (UUID format, not null)
     * 2. Source/target node validation (not null, exist in view)
     * 3. Self-migration prevention (source != target)
     * 4. Proposal ID validation (not null)
     * <p>
     * References: Luciferase-brtp (Byzantine Input Validation)
     *
     * @param proposal migration proposal to validate
     * @return true if valid, false if invalid (rejects Byzantine attacks)
     */
    /**
     * Verify all injected collaborators are present before processing a request.
     * <p>
     * Setter injection leaves a window where {@code requestConsensus} could be invoked before the
     * dependencies are wired (e.g. a race between service startup and the first migration request).
     * This guard converts what would be an opaque {@link NullPointerException} deep in the call into a
     * fail-fast {@link IllegalStateException} naming the missing dependency.
     */
    private void checkInitialized() {
        if (viewMonitor == null) {
            throw new IllegalStateException(
                "ViewCommitteeConsensus not initialized: viewMonitor is null (call setViewMonitor or use the all-args constructor)");
        }
        if (committeeSelector == null) {
            throw new IllegalStateException(
                "ViewCommitteeConsensus not initialized: committeeSelector is null (call setCommitteeSelector or use the all-args constructor)");
        }
        if (votingProtocol == null) {
            throw new IllegalStateException(
                "ViewCommitteeConsensus not initialized: votingProtocol is null (call setVotingProtocol or use the all-args constructor)");
        }
    }

    private boolean validateProposal(MigrationProposal proposal) {
        // Validate proposal ID (UUID format enforced by type system)
        if (proposal.proposalId() == null) {
            log.warn("Rejected proposal with null proposalId");
            return false;
        }

        // Validate entity ID (UUID format enforced by type system)
        if (proposal.entityId() == null) {
            log.warn("Rejected proposal {} with null entityId", proposal.proposalId());
            return false;
        }

        // Validate source node ID
        if (proposal.sourceNodeId() == null) {
            log.warn("Rejected proposal {} with null sourceNodeId", proposal.proposalId());
            return false;
        }

        // Validate target node ID
        if (proposal.targetNodeId() == null) {
            log.warn("Rejected proposal {} with null targetNodeId", proposal.proposalId());
            return false;
        }

        // Validate view ID - a null viewId cannot be matched against the current view and would NPE
        // downstream on proposal.viewId().equals(...) and the vote path (Luciferase-0frcy.18).
        if (proposal.viewId() == null) {
            log.warn("Rejected proposal {} with null viewId", proposal.proposalId());
            return false;
        }

        // Prevent self-migration (source == target)
        if (proposal.sourceNodeId().equals(proposal.targetNodeId())) {
            log.warn("Rejected proposal {} with source == target (self-migration): {}",
                    proposal.proposalId(), proposal.sourceNodeId());
            return false;
        }

        // Validate target node exists in current Fireflies view
        if (!committeeSelector.isNodeInView(proposal.targetNodeId())) {
            log.warn("Rejected proposal {} with target node not in view: {}",
                    proposal.proposalId(), proposal.targetNodeId());
            return false;
        }

        // Validate source node exists in current Fireflies view (defensive)
        if (!committeeSelector.isNodeInView(proposal.sourceNodeId())) {
            log.warn("Rejected proposal {} with source node not in view: {}",
                    proposal.proposalId(), proposal.sourceNodeId());
            return false;
        }

        return true;
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
