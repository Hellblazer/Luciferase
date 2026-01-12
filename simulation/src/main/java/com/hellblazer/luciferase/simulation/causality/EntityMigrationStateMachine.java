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

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
     * Configuration for timeout and rollback behavior (Phase 7D.1 Part 1).
     */
    private final Configuration config;

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
     * Metrics: Total timeout-based rollbacks (Phase 7D.1 Part 2).
     */
    private final AtomicLong totalTimeoutRollbacks;

    /**
     * Listeners for state transition notifications (Phase 7D Day 1).
     * Thread-safe collection using CopyOnWriteArrayList for concurrent reads during iteration.
     */
    private final CopyOnWriteArrayList<MigrationStateListener> listeners;

    /**
     * Clock for deterministic testing.
     */
    private volatile Clock clock = Clock.system();

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Context for an entity undergoing migration.
     * Tracks the origin state and timing information for debugging and metrics.
     */
    public static class MigrationContext {
        public final Object entityId;
        public final long startTimeTicks;
        public final EntityMigrationState originState;
        public UUID targetBubble;
        public UUID sourceBubble;

        // Phase 7D.1 Part 1: Wall clock time tracking
        public final long startTimeMs;      // When migration started (wall clock)
        public final long timeoutMs;        // Deadline for migration (wall clock)
        public volatile int retryCount;     // Number of retries attempted

        // Legacy constructor for backward compatibility
        @Deprecated
        public MigrationContext(Object entityId, long startTimeTicks, EntityMigrationState originState) {
            var now = Clock.system().currentTimeMillis();
            this(entityId, startTimeTicks, originState, now, now + 8000L); // Default 8s timeout
        }

        // Phase 7D.1 Part 1: New constructor with wall clock timeout
        public MigrationContext(Object entityId, long startTimeTicks,
                               EntityMigrationState originState,
                               long startTimeMs, long timeoutMs) {
            this.entityId = Objects.requireNonNull(entityId, "entityId must not be null");
            this.startTimeTicks = startTimeTicks;
            this.originState = Objects.requireNonNull(originState, "originState must not be null");
            this.startTimeMs = startTimeMs;
            this.timeoutMs = timeoutMs;
            this.retryCount = 0;
        }

        /**
         * Check if migration has timed out.
         *
         * @param currentTimeMs Current wall clock time in milliseconds
         * @return true if migration has exceeded timeout
         */
        public boolean isTimedOut(long currentTimeMs) {
            return currentTimeMs >= timeoutMs;
        }

        /**
         * Get remaining time until timeout.
         *
         * @param currentTimeMs Current wall clock time in milliseconds
         * @return Remaining milliseconds until timeout (0 if already timed out)
         */
        public long remainingTimeMs(long currentTimeMs) {
            return Math.max(0, timeoutMs - currentTimeMs);
        }

        /**
         * Get elapsed time since migration started.
         *
         * @param currentTimeMs Current wall clock time in milliseconds
         * @return Elapsed milliseconds since start
         */
        public long elapsedTimeMs(long currentTimeMs) {
            return currentTimeMs - startTimeMs;
        }

        /**
         * Format time information for logging.
         *
         * @param currentTimeMs Current wall clock time in milliseconds
         * @return Formatted string with elapsed, remaining, and retry count
         */
        public String formatTimeInfo(long currentTimeMs) {
            return String.format("elapsed=%dms, remaining=%dms, retries=%d",
                elapsedTimeMs(currentTimeMs), remainingTimeMs(currentTimeMs), retryCount);
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
     * Configuration for EntityMigrationStateMachine (Phase 7D.1 Part 1).
     * Controls timeout behavior, stability requirements, and retry policies.
     */
    public static class Configuration {
        public final boolean requireViewStability;
        public final int rollbackTimeoutTicks;

        // Phase 7D.1 Part 1: Timeout configuration
        public final long migrationTimeoutMs;      // How long migration can take
        public final int minStabilityTicks;        // Minimum stability ticks
        public final boolean enableTimeoutRollback; // Enable timeout-based rollback
        public final int maxRetries;               // Max retry attempts

        public Configuration(boolean requireViewStability, int rollbackTimeoutTicks,
                            long migrationTimeoutMs, int minStabilityTicks,
                            boolean enableTimeoutRollback, int maxRetries) {
            this.requireViewStability = requireViewStability;
            this.rollbackTimeoutTicks = rollbackTimeoutTicks;
            this.migrationTimeoutMs = migrationTimeoutMs;
            this.minStabilityTicks = minStabilityTicks;
            this.enableTimeoutRollback = enableTimeoutRollback;
            this.maxRetries = maxRetries;
        }

        /**
         * Default configuration (balanced settings).
         */
        public static Configuration defaultConfig() {
            return new Configuration(true, 100, 8000L, 3, true, 3);
        }

        /**
         * Aggressive configuration (shorter timeouts, fewer retries).
         */
        public static Configuration aggressive() {
            return new Configuration(true, 50, 2000L, 2, true, 2);
        }

        /**
         * Conservative configuration (longer timeouts, more retries).
         */
        public static Configuration conservative() {
            return new Configuration(true, 200, 15000L, 5, true, 5);
        }

        /**
         * Adaptive configuration based on observed latency.
         *
         * @param observedLatencyMs Observed network latency in milliseconds
         * @return Configuration with timeout scaled to 10x latency (min 2000ms)
         */
        public static Configuration adaptive(long observedLatencyMs) {
            var timeout = Math.max(2000L, observedLatencyMs * 10);
            return new Configuration(true, 100, timeout, 3, true, 3);
        }

        /**
         * Builder for custom configurations.
         */
        public static class Builder {
            private boolean requireViewStability = true;
            private int rollbackTimeoutTicks = 100;
            private long migrationTimeoutMs = 8000L;
            private int minStabilityTicks = 3;
            private boolean enableTimeoutRollback = true;
            private int maxRetries = 3;

            public Builder requireViewStability(boolean value) {
                this.requireViewStability = value;
                return this;
            }

            public Builder rollbackTimeoutTicks(int value) {
                this.rollbackTimeoutTicks = value;
                return this;
            }

            public Builder migrationTimeoutMs(long value) {
                this.migrationTimeoutMs = value;
                return this;
            }

            public Builder minStabilityTicks(int value) {
                this.minStabilityTicks = value;
                return this;
            }

            public Builder enableTimeoutRollback(boolean value) {
                this.enableTimeoutRollback = value;
                return this;
            }

            public Builder maxRetries(int value) {
                this.maxRetries = value;
                return this;
            }

            public Configuration build() {
                return new Configuration(requireViewStability, rollbackTimeoutTicks,
                    migrationTimeoutMs, minStabilityTicks, enableTimeoutRollback, maxRetries);
            }
        }

        public static Builder builder() {
            return new Builder();
        }
    }

    /**
     * Create EntityMigrationStateMachine with Fireflies view monitor and default configuration.
     *
     * @param viewMonitor FirefliesViewMonitor for stability checks
     */
    public EntityMigrationStateMachine(FirefliesViewMonitor viewMonitor) {
        this(viewMonitor, Configuration.defaultConfig());
    }

    /**
     * Create EntityMigrationStateMachine with Fireflies view monitor and custom configuration.
     *
     * @param viewMonitor FirefliesViewMonitor for stability checks
     * @param config Configuration for timeout and rollback behavior
     */
    public EntityMigrationStateMachine(FirefliesViewMonitor viewMonitor, Configuration config) {
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.entityStates = new ConcurrentHashMap<>();
        this.migrationContexts = new ConcurrentHashMap<>();
        this.totalTransitions = new AtomicLong(0L);
        this.totalRollbacks = new AtomicLong(0L);
        this.totalFailedTransitions = new AtomicLong(0L);
        this.totalTimeoutRollbacks = new AtomicLong(0L);
        this.listeners = new CopyOnWriteArrayList<>();
        log.debug("EntityMigrationStateMachine created with config: timeout={}ms, maxRetries={}",
                 config.migrationTimeoutMs, config.maxRetries);
    }

    /**
     * Add a listener for state transition notifications (Phase 7D Day 1).
     *
     * @param listener Listener to register (must not be null)
     * @throws NullPointerException if listener is null
     */
    public void addListener(MigrationStateListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
        log.debug("Listener added: {}", listener.getClass().getSimpleName());
    }

    /**
     * Remove a listener from state transition notifications (Phase 7D Day 1).
     *
     * @param listener Listener to remove
     */
    public void removeListener(MigrationStateListener listener) {
        listeners.remove(listener);
        log.debug("Listener removed: {}", listener == null ? "null" : listener.getClass().getSimpleName());
    }

    /**
     * Get unmodifiable set of registered listeners (Phase 7D Day 1).
     *
     * @return Unmodifiable set of listeners
     */
    public Set<MigrationStateListener> getListeners() {
        return Collections.unmodifiableSet(new HashSet<>(listeners));
    }

    /**
     * Get count of registered listeners (Phase 7D Day 1).
     *
     * @return Number of listeners
     */
    public int getListenerCount() {
        return listeners.size();
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
            var result = TransitionResult.notFound(newState);
            notifyListeners(entityId, null, newState, result);
            return result;
        }

        // Validate transition
        if (!isValidTransition(currentState, newState)) {
            totalFailedTransitions.incrementAndGet();
            var result = TransitionResult.invalid(currentState, newState);
            notifyListeners(entityId, currentState, newState, result);
            return result;
        }

        // Check view stability if this specific transition requires it
        if (requiresViewStabilityForTransition(currentState, newState) && !viewMonitor.isViewStable()) {
            totalFailedTransitions.incrementAndGet();
            log.debug("Transition blocked: view not stable for {} -> {}", currentState, newState);
            var result = TransitionResult.blocked(currentState, newState, "View not stable");
            notifyListeners(entityId, currentState, newState, result);
            return result;
        }

        // Perform transition
        entityStates.put(entityId, newState);
        totalTransitions.incrementAndGet();

        // Update migration context
        if (newState.isInTransition()) {
            var startTimeMs = clock.currentTimeMillis();
            var timeoutMs = startTimeMs + config.migrationTimeoutMs;
            migrationContexts.putIfAbsent(entityId,
                new MigrationContext(entityId, 0L, currentState, startTimeMs, timeoutMs));
        } else {
            // Clean up context for ALL terminal/non-transitional states
            // Prevents memory leak from contexts piling up for ROLLBACK_OWNED, GHOST, DEPARTED, OWNED
            migrationContexts.remove(entityId);
        }

        log.debug("Transition: {} {} -> {}", entityId, currentState, newState);
        var result = TransitionResult.success(currentState, newState);
        notifyListeners(entityId, currentState, newState, result);
        return result;
    }

    /**
     * Called when cluster view changes.
     * Rolls back any MIGRATING_OUT entities to ROLLBACK_OWNED.
     * Converts MIGRATING_IN entities to GHOST.
     *
     * Thread-safe: Uses atomic replaceAll to prevent TOCTOU race conditions
     * where entities change state between snapshot and update.
     */
    public void onViewChange() {
        var rolledBackCount = new java.util.concurrent.atomic.AtomicInteger(0);
        var ghostCount = new java.util.concurrent.atomic.AtomicInteger(0);

        entityStates.replaceAll((entityId, currentState) -> {
            if (currentState == EntityMigrationState.MIGRATING_OUT) {
                totalRollbacks.incrementAndGet();
                rolledBackCount.incrementAndGet();
                log.info("View change: rolled back {} to ROLLBACK_OWNED", entityId);
                return EntityMigrationState.ROLLBACK_OWNED;
            } else if (currentState == EntityMigrationState.MIGRATING_IN) {
                ghostCount.incrementAndGet();
                log.info("View change: {} becomes GHOST", entityId);
                return EntityMigrationState.GHOST;
            }
            return currentState;
        });

        // Notify listeners once with aggregate counts (Phase 7D Day 1)
        notifyViewChangeRollback(rolledBackCount.get(), ghostCount.get());
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
        totalTimeoutRollbacks.set(0L);
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

    /**
     * Get migration context for an entity (Phase 7D.1 Part 1).
     *
     * @param entityId Entity to query
     * @return Migration context, or null if entity not in migration
     */
    public MigrationContext getMigrationContext(Object entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        return migrationContexts.get(entityId);
    }

    /**
     * Get total number of timeout-based rollbacks (Phase 7D.1 Part 2).
     *
     * @return Total timeout rollbacks
     */
    public long getTotalTimeoutRollbacks() {
        return totalTimeoutRollbacks.get();
    }

    /**
     * Check for timed-out migrations (Phase 7D.1 Part 2).
     * Only checks entities in transitional states (MIGRATING_OUT, MIGRATING_IN).
     *
     * @param currentTimeMs Current wall clock time in milliseconds
     * @return List of entity IDs that have timed out (unmodifiable)
     */
    public List<Object> checkTimeouts(long currentTimeMs) {
        if (!config.enableTimeoutRollback) {
            return List.of();
        }

        var timedOut = new ArrayList<Object>();

        for (var entry : migrationContexts.entrySet()) {
            var entityId = entry.getKey();
            var context = entry.getValue();
            var state = entityStates.get(entityId);

            // Only check entities in transitional states that have contexts
            if (state != null && state.isInTransition() && context != null &&
                context.isTimedOut(currentTimeMs)) {
                timedOut.add(entityId);
            }
        }

        return List.copyOf(timedOut);  // Return unmodifiable list
    }

    /**
     * Process timed-out entities by triggering appropriate rollback (Phase 7D.1 Part 2).
     *
     * Timeout handling:
     * - MIGRATING_OUT state -> transition to ROLLBACK_OWNED
     * - MIGRATING_IN state -> transition to GHOST
     *
     * @param currentTimeMs Current wall clock time in milliseconds
     * @return Number of entities rolled back
     */
    public synchronized int processTimeouts(long currentTimeMs) {
        var timedOut = checkTimeouts(currentTimeMs);
        int rolledBack = 0;

        for (var entityId : timedOut) {
            var state = entityStates.get(entityId);

            if (state == null) {
                log.warn("Entity {} timeout check found entity in timeouts but not in states", entityId);
                continue;
            }

            try {
                // Capture context before transition (it gets cleared during transition)
                var context = getMigrationContext(entityId);
                var elapsedMs = context != null ? context.elapsedTimeMs(currentTimeMs) : -1;

                if (state == EntityMigrationState.MIGRATING_OUT) {
                    // Rolling out timed out - revert to ROLLBACK_OWNED
                    var result = transition(entityId, EntityMigrationState.ROLLBACK_OWNED);
                    if (result.success) {
                        rolledBack++;
                        totalTimeoutRollbacks.incrementAndGet();
                        log.info("Timeout rollback: {} from MIGRATING_OUT ({}ms elapsed)",
                                entityId, elapsedMs);
                    } else {
                        log.warn("Failed to rollback {} from MIGRATING_OUT: {}",
                                entityId, result.reason);
                    }
                } else if (state == EntityMigrationState.MIGRATING_IN) {
                    // Rolling in timed out - revert to GHOST
                    var result = transition(entityId, EntityMigrationState.GHOST);
                    if (result.success) {
                        rolledBack++;
                        totalTimeoutRollbacks.incrementAndGet();
                        log.info("Timeout rollback: {} from MIGRATING_IN to GHOST ({}ms elapsed)",
                                entityId, elapsedMs);
                    } else {
                        log.warn("Failed to rollback {} from MIGRATING_IN: {}",
                                entityId, result.reason);
                    }
                }
            } catch (Exception e) {
                log.error("Error processing timeout for entity {}", entityId, e);
            }
        }

        return rolledBack;
    }

    /**
     * Notify all registered listeners of state transition (Phase 7D Day 1).
     *
     * @param entityId Entity that transitioned
     * @param fromState Previous state
     * @param toState New state
     * @param result Transition result
     */
    private void notifyListeners(Object entityId, EntityMigrationState fromState,
                                EntityMigrationState toState, TransitionResult result) {
        for (var listener : listeners) {
            try {
                listener.onEntityStateTransition(entityId, fromState, toState, result);
            } catch (Exception e) {
                log.error("Listener {} threw exception during transition notification: {}",
                         listener.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Notify all registered listeners of view change rollback (Phase 7D Day 1).
     *
     * @param rolledBackCount Number of entities rolled back to ROLLBACK_OWNED
     * @param ghostCount Number of entities converted to GHOST
     */
    private void notifyViewChangeRollback(int rolledBackCount, int ghostCount) {
        for (var listener : listeners) {
            try {
                listener.onViewChangeRollback(rolledBackCount, ghostCount);
            } catch (Exception e) {
                log.error("Listener {} threw exception during view change notification: {}",
                         listener.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
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
