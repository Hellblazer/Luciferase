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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MigrationCoordinator - FSM/2PC Bridge for Entity Migration (Phase 7D Day 2)
 *
 * Bridges EntityMigrationStateMachine (FSM) and CrossProcessMigration (2PC protocol)
 * by observing FSM state transitions and coordinating with 2PC operations.
 *
 * ARCHITECTURE:
 * - Implements MigrationStateListener to observe FSM transitions
 * - Maps FSM state changes to 2PC operations (PREPARE/COMMIT/ABORT)
 * - Maintains coordination state per entity
 * - Thread-safe with <1ms listener execution time
 *
 * FSM → 2PC MAPPING:
 *
 * Outbound Migration (Source Bubble):
 * - OWNED → MIGRATING_OUT: Send PrepareRequest to target
 * - MIGRATING_OUT → DEPARTED: Send CommitRequest (after PREPARED received)
 * - MIGRATING_OUT → ROLLBACK_OWNED: Send AbortRequest (migration failed)
 *
 * Inbound Migration (Target Bubble):
 * - GHOST → MIGRATING_IN: Accept PrepareRequest, reply PREPARED
 * - MIGRATING_IN → OWNED: Accept entity locally (on CommitRequest)
 * - MIGRATING_IN → GHOST: Reject migration (on view change or timeout)
 *
 * View Change Handling:
 * - MIGRATING_OUT entities automatically rollback to ROLLBACK_OWNED (FSM handles)
 * - MIGRATING_IN entities automatically become GHOST (FSM handles)
 * - MigrationCoordinator aborts all pending 2PC operations with target bubbles
 *
 * THREAD SAFETY:
 * - All state stored in ConcurrentHashMap
 * - Atomic metrics for thread-safe counters
 * - Listener callbacks execute synchronously (<1ms requirement)
 *
 * USAGE:
 * <pre>
 *   var coordinator = new MigrationCoordinator(fsm, crossProcessMigration, localBubbleId);
 *   fsm.addListener(coordinator);
 *
 *   // Set target bubble before initiating migration
 *   coordinator.setTargetBubble(entityId, targetBubbleId);
 *
 *   // FSM transition automatically triggers PrepareRequest
 *   fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
 * </pre>
 *
 * @author hal.hildebrand
 * @see MigrationStateListener
 * @see EntityMigrationStateMachine
 */
public class MigrationCoordinator implements MigrationStateListener {

    private static final Logger log = LoggerFactory.getLogger(MigrationCoordinator.class);

    /**
     * Internal coordination state for an entity migration.
     */
    enum MigrationState {
        IDLE,               // No migration in progress
        PREPARE_SENT,       // OWNED→MIGRATING_OUT, waiting for PrepareReply
        PREPARED,           // Received PREPARED from target
        COMMIT_SENT,        // MIGRATING_OUT→DEPARTED, sent CommitRequest
        COMMITTED,          // Target acknowledged commit
        ABORT_SENT,         // Sent AbortRequest
        ABORTED,            // Received AbortAck

        RECEIVING_PREPARE,  // Received PrepareRequest (GHOST→MIGRATING_IN)
        ACCEPTED,           // Replied PREPARED (waiting for CommitRequest or AbortRequest)
        ACCEPTING_COMMIT,   // Received CommitRequest (MIGRATING_IN→OWNED)
        ACCEPTED_ENTITY     // Entity committed locally
    }

    /**
     * Per-entity coordination state.
     */
    static class CoordinationState {
        volatile MigrationState state;
        volatile UUID targetBubble;
        volatile UUID sourceBubble;
        volatile boolean prepareAccepted;

        CoordinationState() {
            this.state = MigrationState.IDLE;
            this.prepareAccepted = false;
        }
    }

    private final EntityMigrationStateMachine fsm;
    private final Object crossProcessMigration;  // MockCrossProcessMigration or actual implementation
    private final Object localBubbleId;
    private final ConcurrentHashMap<Object, CoordinationState> coordinatedEntities;

    // Metrics
    private final AtomicLong totalPrepares;
    private final AtomicLong totalCommits;
    private final AtomicLong totalAborts;
    private final AtomicLong totalViewChangeAborts;

    /**
     * Create MigrationCoordinator.
     *
     * @param fsm EntityMigrationStateMachine to observe
     * @param crossProcessMigration CrossProcessMigration for 2PC operations
     * @param localBubbleId Local bubble identifier
     */
    public MigrationCoordinator(EntityMigrationStateMachine fsm,
                                Object crossProcessMigration,
                                Object localBubbleId) {
        this.fsm = Objects.requireNonNull(fsm, "fsm must not be null");
        this.crossProcessMigration = Objects.requireNonNull(crossProcessMigration,
            "crossProcessMigration must not be null");
        this.localBubbleId = Objects.requireNonNull(localBubbleId,
            "localBubbleId must not be null");
        this.coordinatedEntities = new ConcurrentHashMap<>();
        this.totalPrepares = new AtomicLong(0);
        this.totalCommits = new AtomicLong(0);
        this.totalAborts = new AtomicLong(0);
        this.totalViewChangeAborts = new AtomicLong(0);

        log.debug("MigrationCoordinator created for bubble {}", localBubbleId);
    }

    /**
     * Set target bubble for entity migration (must be called before transition).
     *
     * @param entityId Entity to migrate
     * @param targetBubble Target bubble ID
     */
    public void setTargetBubble(Object entityId, UUID targetBubble) {
        var state = coordinatedEntities.computeIfAbsent(entityId, k -> new CoordinationState());
        state.targetBubble = targetBubble;
    }

    /**
     * Handle PrepareRequest from remote bubble (incoming migration).
     *
     * @param entityId Entity ID
     * @param sourceBubble Source bubble ID
     */
    public void handlePrepareRequest(Object entityId, UUID sourceBubble) {
        var state = coordinatedEntities.computeIfAbsent(entityId, k -> new CoordinationState());
        state.sourceBubble = sourceBubble;
        state.state = MigrationState.RECEIVING_PREPARE;

        // Check if entity is in GHOST state (can accept migration)
        var currentState = fsm.getState(entityId);
        if (currentState == EntityMigrationState.GHOST) {
            state.prepareAccepted = true;
            state.state = MigrationState.ACCEPTED;
            log.debug("Accepted PrepareRequest for entity {} from {}", entityId, sourceBubble);
        } else {
            state.prepareAccepted = false;
            log.debug("Rejected PrepareRequest for entity {} (state={})", entityId, currentState);
        }
    }

    /**
     * Handle CommitRequest from remote bubble.
     *
     * @param entityId Entity ID
     * @param sourceBubble Source bubble ID
     */
    public void handleCommitRequest(Object entityId, UUID sourceBubble) {
        var state = coordinatedEntities.get(entityId);
        if (state == null) {
            log.warn("CommitRequest for unknown entity {}", entityId);
            return;
        }

        // Only accept commit if we're in ACCEPTED state and entity is MIGRATING_IN
        if (state.state == MigrationState.ACCEPTED &&
            fsm.getState(entityId) == EntityMigrationState.MIGRATING_IN) {
            state.state = MigrationState.ACCEPTING_COMMIT;
            log.debug("Accepting CommitRequest for entity {} from {}", entityId, sourceBubble);
        } else {
            log.debug("Rejecting CommitRequest for entity {} (state={})",
                entityId, fsm.getState(entityId));
        }
    }

    /**
     * Notify coordinator that PrepareReply was received.
     *
     * @param entityId Entity ID
     * @param accepted true if target accepted prepare, false if rejected
     */
    public void onPrepareReply(Object entityId, boolean accepted) {
        var state = coordinatedEntities.get(entityId);
        if (state == null) {
            log.warn("PrepareReply for unknown entity {}", entityId);
            return;
        }

        if (accepted) {
            state.state = MigrationState.PREPARED;
            log.debug("PrepareReply ACCEPTED for entity {}", entityId);
        } else {
            state.state = MigrationState.ABORTED;
            log.debug("PrepareReply REJECTED for entity {}", entityId);
        }
    }

    /**
     * Check if entity prepare was accepted.
     *
     * @param entityId Entity ID
     * @return true if prepare accepted
     */
    public boolean isPrepared(Object entityId) {
        var state = coordinatedEntities.get(entityId);
        return state != null && state.prepareAccepted;
    }

    /**
     * Check if entity is owned locally.
     *
     * @param entityId Entity ID
     * @return true if entity committed locally
     */
    public boolean isOwned(Object entityId) {
        var currentState = fsm.getState(entityId);
        return currentState == EntityMigrationState.OWNED;
    }

    @Override
    public void onEntityStateTransition(Object entityId,
                                       EntityMigrationState fromState,
                                       EntityMigrationState toState,
                                       EntityMigrationStateMachine.TransitionResult result) {
        if (!result.success) {
            log.debug("Transition failed, no 2PC action: {} {} → {}",
                entityId, fromState, toState);
            return;
        }

        // Map FSM transitions to 2PC operations
        if (fromState == EntityMigrationState.OWNED &&
            toState == EntityMigrationState.MIGRATING_OUT) {
            handleOwnedToMigratingOut(entityId);
        } else if (fromState == EntityMigrationState.MIGRATING_OUT &&
                   toState == EntityMigrationState.DEPARTED) {
            handleMigratingOutToDeparted(entityId);
        } else if (fromState == EntityMigrationState.MIGRATING_OUT &&
                   toState == EntityMigrationState.ROLLBACK_OWNED) {
            handleMigratingOutToRollback(entityId);
        } else if (fromState == EntityMigrationState.MIGRATING_IN &&
                   toState == EntityMigrationState.OWNED) {
            handleMigratingInToOwned(entityId);
        }
    }

    @Override
    public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
        log.info("View change: {} entities rolled back, {} became ghost",
            rolledBackCount, ghostCount);

        // FSM uses replaceAll for view changes, which doesn't trigger onEntityStateTransition
        // So we need to send AbortRequests for all rolled-back entities here
        coordinatedEntities.forEach((entityId, state) -> {
            // Check if this entity was in migration and needs abort
            if (state.state == MigrationState.PREPARE_SENT ||
                state.state == MigrationState.PREPARED ||
                state.state == MigrationState.COMMIT_SENT) {

                // Send AbortRequest to target
                if (state.targetBubble != null) {
                    sendAbortRequest(entityId, state.targetBubble);
                    totalAborts.incrementAndGet();
                }

                // Clean up coordination state
                coordinatedEntities.remove(entityId);
            }
        });

        // Track view change aborts for metrics
        totalViewChangeAborts.addAndGet(rolledBackCount);
    }

    /**
     * Handle OWNED → MIGRATING_OUT transition.
     * Send PrepareRequest to target bubble.
     */
    private void handleOwnedToMigratingOut(Object entityId) {
        var state = coordinatedEntities.get(entityId);
        if (state == null || state.targetBubble == null) {
            log.warn("Cannot send PrepareRequest for entity {} - no target bubble set", entityId);
            return;
        }

        // Send PrepareRequest to target
        sendPrepareRequest(entityId, (UUID) localBubbleId, state.targetBubble);
        state.state = MigrationState.PREPARE_SENT;
        totalPrepares.incrementAndGet();

        log.debug("Sent PrepareRequest for entity {} to {}", entityId, state.targetBubble);
    }

    /**
     * Handle MIGRATING_OUT → DEPARTED transition.
     * Send CommitRequest to target bubble.
     */
    private void handleMigratingOutToDeparted(Object entityId) {
        var state = coordinatedEntities.get(entityId);
        if (state == null || state.targetBubble == null) {
            log.warn("Cannot send CommitRequest for entity {} - no target bubble", entityId);
            return;
        }

        // Send CommitRequest to target
        sendCommitRequest(entityId, state.targetBubble);
        state.state = MigrationState.COMMIT_SENT;
        totalCommits.incrementAndGet();

        log.debug("Sent CommitRequest for entity {} to {}", entityId, state.targetBubble);

        // Clean up coordination state
        coordinatedEntities.remove(entityId);
    }

    /**
     * Handle MIGRATING_OUT → ROLLBACK_OWNED transition.
     * Send AbortRequest to target bubble.
     */
    private void handleMigratingOutToRollback(Object entityId) {
        var state = coordinatedEntities.get(entityId);
        if (state == null || state.targetBubble == null) {
            log.warn("Cannot send AbortRequest for entity {} - no target bubble", entityId);
            return;
        }

        // Send AbortRequest to target
        sendAbortRequest(entityId, state.targetBubble);
        state.state = MigrationState.ABORT_SENT;
        totalAborts.incrementAndGet();

        log.debug("Sent AbortRequest for entity {} to {}", entityId, state.targetBubble);

        // Clean up coordination state
        coordinatedEntities.remove(entityId);
    }

    /**
     * Handle MIGRATING_IN → OWNED transition.
     * Accept entity locally.
     */
    private void handleMigratingInToOwned(Object entityId) {
        var state = coordinatedEntities.get(entityId);
        if (state == null) {
            log.warn("Entity {} transitioned to OWNED but no coordination state", entityId);
            return;
        }

        state.state = MigrationState.ACCEPTED_ENTITY;
        log.debug("Entity {} accepted locally and now OWNED", entityId);

        // Clean up coordination state
        coordinatedEntities.remove(entityId);
    }

    /**
     * Send PrepareRequest to target bubble (via CrossProcessMigration).
     */
    private void sendPrepareRequest(Object entityId, UUID sourceBubble, UUID targetBubble) {
        try {
            // Use reflection to call sendPrepareRequest on crossProcessMigration
            var method = crossProcessMigration.getClass()
                .getMethod("sendPrepareRequest", Object.class, UUID.class, UUID.class);
            method.invoke(crossProcessMigration, entityId, sourceBubble, targetBubble);
        } catch (Exception e) {
            log.error("Failed to send PrepareRequest for entity {}", entityId, e);
        }
    }

    /**
     * Send CommitRequest to target bubble (via CrossProcessMigration).
     */
    private void sendCommitRequest(Object entityId, UUID targetBubble) {
        try {
            var method = crossProcessMigration.getClass()
                .getMethod("sendCommitRequest", Object.class, UUID.class);
            method.invoke(crossProcessMigration, entityId, targetBubble);
        } catch (Exception e) {
            log.error("Failed to send CommitRequest for entity {}", entityId, e);
        }
    }

    /**
     * Send AbortRequest to target bubble (via CrossProcessMigration).
     */
    private void sendAbortRequest(Object entityId, UUID targetBubble) {
        try {
            var method = crossProcessMigration.getClass()
                .getMethod("sendAbortRequest", Object.class, UUID.class);
            method.invoke(crossProcessMigration, entityId, targetBubble);
        } catch (Exception e) {
            log.error("Failed to send AbortRequest for entity {}", entityId, e);
        }
    }

    /**
     * Get total PrepareRequests sent.
     *
     * @return Total prepares count
     */
    public long getTotalPrepares() {
        return totalPrepares.get();
    }

    /**
     * Get total CommitRequests sent.
     *
     * @return Total commits count
     */
    public long getTotalCommits() {
        return totalCommits.get();
    }

    /**
     * Get total AbortRequests sent.
     *
     * @return Total aborts count
     */
    public long getTotalAborts() {
        return totalAborts.get();
    }

    /**
     * Get total view change aborts.
     *
     * @return Total view change aborts count
     */
    public long getTotalViewChangeAborts() {
        return totalViewChangeAborts.get();
    }

    @Override
    public String toString() {
        return String.format(
            "MigrationCoordinator{bubble=%s, prepares=%d, commits=%d, aborts=%d, viewChangeAborts=%d}",
            localBubbleId, totalPrepares.get(), totalCommits.get(),
            totalAborts.get(), totalViewChangeAborts.get()
        );
    }
}
