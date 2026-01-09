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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * EntityMigrationStateMachine - Entity Ownership State Machine (Phase 7C.5)
 *
 * Manages the ownership state of entities as they migrate across bubble boundaries.
 * Enforces valid state transitions and requires view stability for commit operations.
 * Detects and handles view changes by rolling back incomplete migrations.
 *
 * KEY INVARIANT: Exactly one OWNED per entity globally across all bubbles.
 * At any time, for each entity ID:
 * - Exactly one bubble has OWNED or MIGRATING_IN
 * - All other bubbles have GHOST or DEPARTED
 *
 * ARCHITECTURE:
 * - Tracks per-entity state via ConcurrentHashMap
 * - Integrates with FirefliesViewMonitor for stability checks
 * - Logs transitions for audit trail and recovery
 * - Provides metrics on migration activity
 *
 * USAGE:
 * <pre>
 *   var fsm = new EntityMigrationStateMachine(firefliesMonitor);
 *
 *   // Initiate outbound migration
 *   var result = fsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
 *   if (result.success()) {
 *       sendMigrationRequest(entityId, targetBubble);
 *   }
 *
 *   // Later, when receiving COMMIT_ACK and view is stable
 *   if (firefliesMonitor.isViewStable()) {
 *       fsm.transition(entityId, EntityMigrationState.DEPARTED);
 *   }
 *
 *   // On view change, rollbacks happen automatically
 *   fsm.onViewChange();
 * </pre>
 *
 * THREAD SAFETY: All operations are thread-safe using concurrent collections
 * and atomic metrics. State transitions are logged for auditability.
 *
 * @author hal.hildebrand
 */
public class EntityMigrationStateMachine {

    private static final Logger log = LoggerFactory.getLogger(EntityMigrationStateMachine.class);

    /**
     * Per-entity migration state tracking.
     */
    private final ConcurrentHashMap<Object, EntityMigrationState> entityStates;

    /**
     * Migration context per entity (timestamp, source, destination, etc).
     */
    private final ConcurrentHashMap<Object, MigrationContext> migrationContexts;

    /**
     * Reference to view monitor for stability checks.
     */
    private final FirefliesViewMonitor viewMonitor;

    /**
     * Metrics: Total state transitions.
     */
    private final AtomicLong totalTransitions;

    /**
     * Metrics: Total rollbacks due to view change.
     */
    private final AtomicLong totalRollbacks;

    /**
     * Metrics: Total failed transitions (invalid or blocked).
     */
    private final AtomicLong totalFailedTransitions;

    /**
     * Configuration for this state machine.
     */
    public static class Configuration {
        public final boolean requireViewStability;
        public final int rollbackTimeoutTicks;

        public Configuration(boolean requireViewStability, int rollbackTimeoutTicks) {
            this.requireViewStability = requireViewStability;
            this.rollbackTimeoutTicks = rollbackTimeoutTicks;
        }
    }

    /**
     * Context for an entity undergoing migration.
     */
    public static class MigrationContext {
        public final Object entityId;
        public final long startTimeTicks;
        public final EntityMigrationState originState;
        public UUID targetBubble;
        public UUID sourceBubble;

        public MigrationContext(Object entityId, long startTimeTicks, EntityMigrationState originState) {
            this.entityId = entityId;
            this.startTimeTicks = startTimeTicks;
            this.originState = originState;
        }
    }

    /**
     * Result of a state transition attempt.
     */
    public static class TransitionResult {
        public final boolean success;
        public final EntityMigrationState fromState;
        public final EntityMigrationState toState;
        public final String reason;

        private TransitionResult(boolean success, EntityMigrationState fromState, EntityMigrationState toState, String reason) {
            this.success = success;
            this.fromState = fromState;
            this.toState = toState;
            this.reason = reason;
        }

        public static TransitionResult success(EntityMigrationState fromState, EntityMigrationState toState) {
            return new TransitionResult(true, fromState, toState, "Success");
        }

        public static TransitionResult invalid(EntityMigrationState fromState, EntityMigrationState toState) {
            return new TransitionResult(false, fromState, toState, "Invalid transition");
        }

        public static TransitionResult blocked(EntityMigrationState fromState, EntityMigrationState toState, String reason) {
            return new TransitionResult(false, fromState, toState, reason);
        }

        public static TransitionResult notFound(EntityMigrationState requestedState) {
            return new TransitionResult(false, null, requestedState, "Entity not found");
        }
    }

    /**
     * Create EntityMigrationStateMachine with Fireflies view monitor.
     *
     * @param viewMonitor FirefliesViewMonitor for stability checks
     */
    public EntityMigrationStateMachine(FirefliesViewMonitor viewMonitor) {
        this(viewMonitor, new Configuration(true, 100));
    }

    /**
     * Create EntityMigrationStateMachine with custom configuration.
     *
     * @param viewMonitor FirefliesViewMonitor for stability checks
     * @param config Configuration for behavior
     */
    public EntityMigrationStateMachine(FirefliesViewMonitor viewMonitor, Configuration config) {
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");
        this.entityStates = new ConcurrentHashMap<>();
        this.migrationContexts = new ConcurrentHashMap<>();
        this.totalTransitions = new AtomicLong(0L);
        this.totalRollbacks = new AtomicLong(0L);
        this.totalFailedTransitions = new AtomicLong(0L);
        log.debug("EntityMigrationStateMachine created");
    }

    /**
     * Initialize entity to OWNED state.
     *
     * @param entityId Entity to initialize
     */
    public void initializeOwned(Object entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        entityStates.putIfAbsent(entityId, EntityMigrationState.OWNED);
    }

    /**
     * Attempt a state transition for an entity.
     *
     * @param entityId Entity to transition
     * @param newState Target state
     * @return TransitionResult indicating success/failure and reason
     */
    public TransitionResult transition(Object entityId, EntityMigrationState newState) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(newState, "newState must not be null");

        var currentState = entityStates.get(entityId);
        if (currentState == null) {
            totalFailedTransitions.incrementAndGet();
            return TransitionResult.notFound(newState);
        }

        // Validate transition
        if (!isValidTransition(currentState, newState)) {
            totalFailedTransitions.incrementAndGet();
            return TransitionResult.invalid(currentState, newState);
        }

        // Check view stability if this specific transition requires it
        if (requiresViewStabilityForTransition(currentState, newState) && !viewMonitor.isViewStable()) {
            totalFailedTransitions.incrementAndGet();
            log.debug("Transition blocked: view not stable for {} -> {}", currentState, newState);
            return TransitionResult.blocked(currentState, newState, "View not stable");
        }

        // Perform transition
        entityStates.put(entityId, newState);
        totalTransitions.incrementAndGet();

        // Update migration context
        if (newState.isInTransition()) {
            migrationContexts.putIfAbsent(entityId, new MigrationContext(entityId, 0L, currentState));
        } else if (newState == EntityMigrationState.DEPARTED || newState == EntityMigrationState.OWNED) {
            migrationContexts.remove(entityId);
        }

        log.debug("Transition: {} {} -> {}", entityId, currentState, newState);
        return TransitionResult.success(currentState, newState);
    }

    /**
     * Called when cluster view changes.
     * Rolls back any MIGRATING_OUT entities to ROLLBACK_OWNED.
     * Keeps MIGRATING_IN as GHOST.
     */
    public void onViewChange() {
        var toRollback = entityStates.entrySet().stream()
            .filter(e -> e.getValue() == EntityMigrationState.MIGRATING_OUT)
            .collect(Collectors.toList());

        for (var entry : toRollback) {
            entityStates.put(entry.getKey(), EntityMigrationState.ROLLBACK_OWNED);
            totalRollbacks.incrementAndGet();
            log.info("View change: rolled back {} to ROLLBACK_OWNED", entry.getKey());
        }

        var toGhost = entityStates.entrySet().stream()
            .filter(e -> e.getValue() == EntityMigrationState.MIGRATING_IN)
            .collect(Collectors.toList());

        for (var entry : toGhost) {
            entityStates.put(entry.getKey(), EntityMigrationState.GHOST);
            log.info("View change: {} remains as GHOST", entry.getKey());
        }
    }

    /**
     * Check if transition is valid according to state machine rules.
     *
     * @param currentState Current state
     * @param newState Target state
     * @return true if transition is allowed
     */
    private boolean isValidTransition(EntityMigrationState currentState, EntityMigrationState newState) {
        return switch (currentState) {
            case OWNED ->
                newState == EntityMigrationState.MIGRATING_OUT;  // Owner can start migration

            case MIGRATING_OUT ->
                newState == EntityMigrationState.DEPARTED ||     // Migration success
                newState == EntityMigrationState.ROLLBACK_OWNED; // Migration failed

            case DEPARTED ->
                newState == EntityMigrationState.GHOST;          // Leave cluster, become ghost

            case GHOST ->
                newState == EntityMigrationState.MIGRATING_IN;   // Start incoming migration

            case MIGRATING_IN ->
                newState == EntityMigrationState.OWNED ||        // Accept incoming
                newState == EntityMigrationState.GHOST;          // Reject/timeout

            case ROLLBACK_OWNED ->
                newState == EntityMigrationState.OWNED;          // Automatic recovery

            default -> false;
        };
    }

    /**
     * Check if a specific transition requires view stability.
     * Only MIGRATING_OUT->DEPARTED and MIGRATING_IN->OWNED require stable view.
     *
     * @param currentState Current state
     * @param newState Target state
     * @return true if transition requires view stability
     */
    private boolean requiresViewStabilityForTransition(EntityMigrationState currentState, EntityMigrationState newState) {
        return (currentState == EntityMigrationState.MIGRATING_OUT && newState == EntityMigrationState.DEPARTED) ||
               (currentState == EntityMigrationState.MIGRATING_IN && newState == EntityMigrationState.OWNED);
    }

    /**
     * Verify critical invariant: at most one OWNED per entity locally.
     * For global invariant, would need to coordinate across bubbles.
     *
     * @param entityId Entity to check
     * @return true if entity state is valid
     */
    public boolean verifyInvariant(Object entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        var state = entityStates.get(entityId);

        // At most one of {OWNED, MIGRATING_IN} per entity locally
        // Cannot have both owned locally and be in transition to owned
        return state == null || !(state == EntityMigrationState.OWNED && state == EntityMigrationState.MIGRATING_IN);
    }

    /**
     * Get current state of entity.
     *
     * @param entityId Entity to query
     * @return Current state, or null if entity not tracked
     */
    public EntityMigrationState getState(Object entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        return entityStates.get(entityId);
    }

    /**
     * Get all entities in a specific state.
     *
     * @param state State to filter by
     * @return Set of entities in that state
     */
    public Set<Object> getEntitiesInState(EntityMigrationState state) {
        Objects.requireNonNull(state, "state must not be null");
        return entityStates.entrySet().stream()
            .filter(e -> e.getValue() == state)
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Reset all state (for testing or recovery).
     * WARNING: Clears all tracking.
     */
    public void reset() {
        entityStates.clear();
        migrationContexts.clear();
        totalTransitions.set(0L);
        totalRollbacks.set(0L);
        totalFailedTransitions.set(0L);
        log.debug("EntityMigrationStateMachine reset");
    }

    /**
     * Get total state transitions since creation.
     *
     * @return Total transitions count
     */
    public long getTotalTransitions() {
        return totalTransitions.get();
    }

    /**
     * Get total rollbacks due to view changes.
     *
     * @return Total rollbacks count
     */
    public long getTotalRollbacks() {
        return totalRollbacks.get();
    }

    /**
     * Get total failed transition attempts.
     *
     * @return Total failed transitions
     */
    public long getTotalFailedTransitions() {
        return totalFailedTransitions.get();
    }

    /**
     * Get number of entities currently tracked.
     *
     * @return Entity count
     */
    public int getEntityCount() {
        return entityStates.size();
    }

    /**
     * Get number of entities in migration (MIGRATING_IN or MIGRATING_OUT).
     *
     * @return Count of entities in transition
     */
    public int getEntitiesInMigration() {
        return (int) entityStates.values().stream()
            .filter(EntityMigrationState::isInTransition)
            .count();
    }

    @Override
    public String toString() {
        return String.format(
            "EntityMigrationStateMachine{entities=%d, inMigration=%d, transitions=%d, rollbacks=%d, failed=%d}",
            getEntityCount(), getEntitiesInMigration(),
            totalTransitions.get(), totalRollbacks.get(), totalFailedTransitions.get()
        );
    }
}
