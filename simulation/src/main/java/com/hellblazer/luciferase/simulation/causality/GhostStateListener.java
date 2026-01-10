/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.causality;

import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.ghost.GhostStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GhostStateListener - FSM/Ghost Bridge Integration (Phase 7D.2 Part 1).
 * <p>
 * Implements MigrationStateListener to bridge EntityMigrationStateMachine state transitions
 * to GhostStateManager operations. Coordinates ghost entity lifecycle with migration state machine.
 * <p>
 * <strong>State Transition Mapping:</strong>
 * <ul>
 *   <li>DEPARTED → GHOST: Verify ghost is being tracked (network already created it)</li>
 *   <li>GHOST → MIGRATING_IN: Validate position consistency via GhostConsistencyValidator</li>
 *   <li>MIGRATING_IN → OWNED: Remove ghost tracking (entity now locally owned)</li>
 *   <li>* → ROLLBACK_OWNED: Cleanup ghost on rollback</li>
 *   <li>View change: Reconcile ghost state with FSM (remove ghosts for non-GHOST entities)</li>
 * </ul>
 * <p>
 * <strong>Architecture:</strong>
 * <ul>
 *   <li>Observes EntityMigrationStateMachine via MigrationStateListener</li>
 *   <li>Coordinates with GhostStateManager for ghost lifecycle</li>
 *   <li>Uses GhostConsistencyValidator for position validation during GHOST→MIGRATING_IN</li>
 *   <li>Thread-safe using ConcurrentHashMap for entity→sourceBubble mapping</li>
 * </ul>
 * <p>
 * <strong>Usage Example:</strong>
 * <pre>
 * var ghostStateManager = new GhostStateManager(bounds, 1000);
 * var fsm = new EntityMigrationStateMachine(viewMonitor, config);
 * var ghostStateListener = new GhostStateListener(ghostStateManager, fsm);
 *
 * fsm.addListener(ghostStateListener);
 *
 * // FSM state transitions automatically trigger ghost operations
 * fsm.transition(entityId, EntityMigrationState.GHOST);
 * // → Listener verifies ghost tracking
 *
 * fsm.transition(entityId, EntityMigrationState.MIGRATING_IN);
 * // → Listener validates position consistency
 *
 * fsm.transition(entityId, EntityMigrationState.OWNED);
 * // → Listener removes ghost tracking
 * </pre>
 * <p>
 * <strong>Thread Safety:</strong> All operations are thread-safe using ConcurrentHashMap
 * for entity→sourceBubble mapping.
 *
 * @author hal.hildebrand
 */
public class GhostStateListener implements MigrationStateListener {

    private static final Logger log = LoggerFactory.getLogger(GhostStateListener.class);

    /**
     * Ghost state manager for ghost lifecycle operations.
     */
    private final GhostStateManager ghostStateManager;

    /**
     * Entity migration state machine reference.
     */
    private final EntityMigrationStateMachine fsm;

    /**
     * Position/velocity consistency validator.
     */
    private final GhostConsistencyValidator consistencyValidator;

    /**
     * Entity ID to source bubble mapping (for tracking ghost origins).
     * Thread-safe via ConcurrentHashMap.
     */
    private final ConcurrentHashMap<Object, UUID> entityToSourceBubble;

    /**
     * Reconciliation count metric (for Phase 7D.2 Part 2).
     * Tracks how many times reconcileGhostState() has been called.
     */
    private final AtomicLong reconciliationCount = new AtomicLong(0L);

    /**
     * Create GhostStateListener with ghost state manager and FSM.
     *
     * @param ghostStateManager Ghost state manager for ghost operations
     * @param fsm               Entity migration state machine to observe
     */
    public GhostStateListener(GhostStateManager ghostStateManager, EntityMigrationStateMachine fsm) {
        this.ghostStateManager = Objects.requireNonNull(ghostStateManager, "ghostStateManager must not be null");
        this.fsm = Objects.requireNonNull(fsm, "fsm must not be null");
        this.consistencyValidator = new GhostConsistencyValidator();
        this.consistencyValidator.setGhostStateManager(ghostStateManager);
        this.entityToSourceBubble = new ConcurrentHashMap<>();

        log.debug("GhostStateListener created");
    }

    /**
     * Handle entity state transitions.
     * <p>
     * Transition mapping:
     * <ul>
     *   <li>DEPARTED → GHOST: Verify ghost tracking (network already created)</li>
     *   <li>GHOST → MIGRATING_IN: Validate position consistency</li>
     *   <li>MIGRATING_IN → OWNED: Remove ghost tracking</li>
     *   <li>* → ROLLBACK_OWNED: Cleanup ghost</li>
     * </ul>
     *
     * @param entityId   Entity changing state
     * @param fromState  Previous state
     * @param toState    New state
     * @param result     Transition result
     */
    @Override
    public void onEntityStateTransition(Object entityId,
                                        EntityMigrationState fromState,
                                        EntityMigrationState toState,
                                        EntityMigrationStateMachine.TransitionResult result) {
        // Only process successful transitions
        if (!result.success) {
            return;
        }

        // Convert to StringEntityID for ghost manager operations
        var stringEntityId = (StringEntityID) entityId;

        // Handle state transitions
        if (fromState == EntityMigrationState.DEPARTED && toState == EntityMigrationState.GHOST) {
            handleDepartedToGhost(stringEntityId);
        } else if (fromState == EntityMigrationState.GHOST && toState == EntityMigrationState.MIGRATING_IN) {
            handleGhostToMigratingIn(stringEntityId);
        } else if (fromState == EntityMigrationState.MIGRATING_IN && toState == EntityMigrationState.OWNED) {
            handleMigratingInToOwned(stringEntityId);
        } else if (toState == EntityMigrationState.ROLLBACK_OWNED) {
            handleRollbackOwned(stringEntityId);
        }
    }

    /**
     * Handle view change rollbacks.
     * <p>
     * Reconciles ghost state with FSM after view change:
     * - Removes ghosts for entities no longer in GHOST state
     * - Logs rollback statistics
     *
     * @param rolledBackCount Number of entities rolled back to ROLLBACK_OWNED
     * @param ghostCount      Number of entities converted to GHOST
     */
    @Override
    public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
        log.info("View change rollback: {} rolled back, {} ghosts", rolledBackCount, ghostCount);

        // Reconcile ghost state with FSM
        reconcileGhostState();
    }

    /**
     * Reconcile ghost state with FSM.
     * <p>
     * Removes ghosts for entities that are no longer in GHOST state in the FSM.
     * This handles cases where:
     * - View change converted MIGRATING_IN to GHOST but ghost was already removed
     * - Entity state changed outside normal transition flow
     * - Anomalous states where ghost exists but entity is OWNED
     */
    public void reconcileGhostState() {
        var activeGhosts = ghostStateManager.getActiveGhosts();

        for (var ghost : activeGhosts) {
            var entityId = ghost.entityId();
            var currentState = fsm.getState(entityId);

            // If entity is not in GHOST state, remove ghost tracking
            if (currentState != EntityMigrationState.GHOST) {
                ghostStateManager.removeGhost(entityId);
                entityToSourceBubble.remove(entityId);
                log.debug("Reconciled: removed ghost for {} (current state: {})", entityId, currentState);
            }
        }

        // Increment reconciliation count metric
        reconciliationCount.incrementAndGet();
    }

    /**
     * Register this listener with FirefliesViewMonitor for view change callbacks.
     * <p>
     * When view changes occur, reconcileGhostState() will be automatically triggered
     * to clean up ghosts for entities that are no longer in GHOST state.
     *
     * @param viewMonitor FirefliesViewMonitor to register with
     */
    public void registerWithViewMonitor(FirefliesViewMonitor viewMonitor) {
        Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");

        // Register callback for view changes
        viewMonitor.addViewChangeListener(this::onViewChange);

        log.debug("GhostStateListener registered with FirefliesViewMonitor");
    }

    /**
     * Handle view change notifications from FirefliesViewMonitor.
     * Triggers ghost state reconciliation on view changes.
     *
     * @param change ViewChange with joined and left members
     */
    private void onViewChange(MembershipView.ViewChange<?> change) {
        log.debug("View change detected: {} joined, {} left - triggering reconciliation",
                 change.joined().size(), change.left().size());

        // Reconcile ghost state
        reconcileGhostState();
    }

    /**
     * Get number of times reconcileGhostState() has been called.
     *
     * @return Reconciliation count
     */
    public long getReconciliationCount() {
        return reconciliationCount.get();
    }

    // ========== Internal Transition Handlers ==========

    /**
     * Handle DEPARTED → GHOST transition.
     * <p>
     * Verifies that ghost is being tracked (network should have already created it).
     * If ghost doesn't exist, logs warning but doesn't fail (network may create it later).
     */
    private void handleDepartedToGhost(StringEntityID entityId) {
        var ghost = ghostStateManager.getGhost(entityId);
        if (ghost == null) {
            log.debug("DEPARTED→GHOST: Ghost not yet tracked for {} (network will create)", entityId);
        } else {
            log.debug("DEPARTED→GHOST: Ghost verified for {}", entityId);
            // Track source bubble for this ghost
            entityToSourceBubble.put(entityId, ghost.sourceBubbleId());
        }
    }

    /**
     * Handle GHOST → MIGRATING_IN transition.
     * <p>
     * Validates position consistency using GhostConsistencyValidator.
     * Logs validation results but doesn't block transition (validation is informational).
     */
    private void handleGhostToMigratingIn(StringEntityID entityId) {
        var ghost = ghostStateManager.getGhost(entityId);
        if (ghost == null) {
            log.warn("GHOST→MIGRATING_IN: No ghost found for {}, skipping validation", entityId);
            return;
        }

        // Get expected position and velocity from ghost
        var expectedPosition = ghost.position();
        var expectedVelocity = new Vector3f(0, 0, 0);  // Ghost doesn't expose velocity directly

        // Validate consistency
        var report = consistencyValidator.validateConsistency(entityId, expectedPosition, expectedVelocity);

        if (!report.positionValid()) {
            log.warn("GHOST→MIGRATING_IN: Position inconsistency for {}: {}", entityId, report.message());
        } else {
            log.debug("GHOST→MIGRATING_IN: Validation passed for {}", entityId);
        }
    }

    /**
     * Handle MIGRATING_IN → OWNED transition.
     * <p>
     * Removes ghost tracking since entity is now locally owned.
     */
    private void handleMigratingInToOwned(StringEntityID entityId) {
        ghostStateManager.removeGhost(entityId);
        entityToSourceBubble.remove(entityId);
        log.debug("MIGRATING_IN→OWNED: Removed ghost for {}", entityId);
    }

    /**
     * Handle * → ROLLBACK_OWNED transition.
     * <p>
     * Cleans up ghost tracking on rollback.
     */
    private void handleRollbackOwned(StringEntityID entityId) {
        var ghost = ghostStateManager.getGhost(entityId);
        if (ghost != null) {
            ghostStateManager.removeGhost(entityId);
            entityToSourceBubble.remove(entityId);
            log.debug("*→ROLLBACK_OWNED: Cleaned up ghost for {}", entityId);
        }
    }

    /**
     * Get source bubble for entity (if tracked).
     *
     * @param entityId Entity ID
     * @return Source bubble UUID, or null if not tracked
     */
    public UUID getSourceBubble(Object entityId) {
        return entityToSourceBubble.get(entityId);
    }

    /**
     * Get number of tracked entity→sourceBubble mappings.
     *
     * @return Mapping count
     */
    public int getTrackedMappingCount() {
        return entityToSourceBubble.size();
    }

    @Override
    public String toString() {
        return String.format("GhostStateListener{mappings=%d}", entityToSourceBubble.size());
    }
}
