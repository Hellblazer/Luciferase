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

package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.causality.*;
import com.hellblazer.luciferase.simulation.distributed.migration.MigrationOracle;
import com.hellblazer.luciferase.simulation.distributed.migration.OptimisticMigrator;
import com.hellblazer.luciferase.simulation.events.EntityDepartureEvent;
import com.hellblazer.luciferase.simulation.events.EntityRollbackEvent;
import com.hellblazer.luciferase.simulation.events.ViewSynchronyAck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EnhancedBubbleMigrationIntegration - Migration coordination for EnhancedBubble (Phase 7E Day 4)
 *
 * Integrates boundary crossing detection, optimistic migration, and view stability
 * into EnhancedBubble's simulation loop. Implements MigrationStateListener to coordinate
 * with EntityMigrationStateMachine for FSM state transitions.
 *
 * INTEGRATION POINTS:
 * 1. MigrationOracle: Detects boundary crossings on tick()
 * 2. OptimisticMigrator: Manages deferred physics updates
 * 3. EntityMigrationStateMachine: Tracks entity ownership states
 * 4. FirefliesViewMonitor: Detects view stability for migration commits
 * 5. MigrationStateListener: Observes FSM transitions
 *
 * MIGRATION WORKFLOW:
 * Source Bubble:
 *   Entity crosses boundary
 *     → MigrationOracle detects crossing
 *     → OptimisticMigrator.initiateOptimisticMigration()
 *     → FSM: OWNED → MIGRATING_OUT (physics frozen)
 *     → EntityDepartureEvent sent to target
 *
 * Target Bubble:
 *   Receive EntityDepartureEvent
 *     → FSM: GHOST → MIGRATING_IN
 *     → OptimisticMigrator queues updates
 *     → Wait for view stability (3 ticks)
 *     → FSM: MIGRATING_IN → OWNED
 *     → OptimisticMigrator.flushDeferredUpdates()
 *     → ViewSynchronyAck sent to source
 *
 * Rollback (on view change):
 *   View change detected
 *     → FSM.onViewChange() triggers rollback
 *     → MIGRATING_OUT → ROLLBACK_OWNED (source)
 *     → MIGRATING_IN → GHOST (target)
 *     → EntityRollbackEvent sent both directions
 *
 * THREAD SAFETY:
 * Uses ConcurrentHashMap for concurrent entity tracking.
 * FSM transitions are synchronized via EntityMigrationStateMachine.
 * MigrationStateListener callbacks are synchronous (< 1ms).
 *
 * PERFORMANCE:
 * - Boundary detection: O(n) where n = entities in bubble
 * - Migration initiation: O(1) per entity
 * - Deferred queue processing: O(m) where m = queued updates
 * - View stability check: O(1)
 * - Target: < 50ms for 100 concurrent migrations
 *
 * @author hal.hildebrand
 */
public class EnhancedBubbleMigrationIntegration implements MigrationStateListener {

    private static final Logger log = LoggerFactory.getLogger(EnhancedBubbleMigrationIntegration.class);

    // Reference to bubble being integrated
    private final EnhancedBubble bubble;

    // Migration components
    private final EntityMigrationStateMachine migrationFsm;
    private final MigrationOracle migrationOracle;
    private final OptimisticMigrator optimisticMigrator;
    private final FirefliesViewMonitor viewMonitor;

    // View stability configuration
    private final int viewStabilityTicks;
    private final Map<UUID, Integer> entityStabilityTicks = new ConcurrentHashMap<>();

    // Most recent EntityDepartureEvent produced by detectAndInitiateMigrations (diagnostics/testing).
    private volatile EntityDepartureEvent lastDepartureEvent;

    // Metrics
    private long totalMigrationsInitiated = 0;
    private long totalMigrationsCompleted = 0;
    private long totalMigrationsRolledBack = 0;
    private long totalTimeoutsProcessed = 0;

    /**
     * Create migration integration for an EnhancedBubble.
     *
     * @param bubble Bubble to integrate with
     * @param migrationFsm Entity migration state machine
     * @param migrationOracle Boundary crossing detection
     * @param optimisticMigrator Deferred update management
     * @param viewMonitor Fireflies view stability detection
     * @param viewStabilityTicks Number of ticks required for view stability (e.g., 3 for 30ms at 100Hz)
     */
    public EnhancedBubbleMigrationIntegration(EnhancedBubble bubble,
                                             EntityMigrationStateMachine migrationFsm,
                                             MigrationOracle migrationOracle,
                                             OptimisticMigrator optimisticMigrator,
                                             FirefliesViewMonitor viewMonitor,
                                             int viewStabilityTicks) {
        this.bubble = Objects.requireNonNull(bubble, "bubble must not be null");
        this.migrationFsm = Objects.requireNonNull(migrationFsm, "migrationFsm must not be null");
        this.migrationOracle = Objects.requireNonNull(migrationOracle, "migrationOracle must not be null");
        this.optimisticMigrator = Objects.requireNonNull(optimisticMigrator, "optimisticMigrator must not be null");
        this.viewMonitor = Objects.requireNonNull(viewMonitor, "viewMonitor must not be null");
        this.viewStabilityTicks = viewStabilityTicks;

        // Register as FSM listener
        migrationFsm.addListener(this);

        log.debug("EnhancedBubbleMigrationIntegration initialized: bubble={}, stability_ticks={}",
                bubble.id(), viewStabilityTicks);
    }

    /**
     * Execute migration phase of bubble tick.
     * Called from bubble's main simulation loop (tick method).
     * Coordinates boundary detection, migration initiation, state transitions, and rollback.
     *
     * @param simulationTime Current simulation time
     */
    public void processMigrations(long simulationTime) {
        // Phase 1: Detect boundary crossings
        detectAndInitiateMigrations();

        // Phase 2: Check for view stability and commit pending migrations
        processPendingMigrations();

        // Phase 3: Process timeouts for stuck migrations
        processTimeouts(simulationTime);
    }

    /**
     * Detect entities crossing bubble boundaries and initiate migrations.
     * Called each tick to check if any entities have left this bubble.
     */
    private void detectAndInitiateMigrations() {
        // Get entities that crossed boundaries
        var crossingEntities = migrationOracle.getEntitiesCrossingBoundaries();

        for (var crossingId : crossingEntities) {
            try {
                // Resolve the entity's current record (position + content) from the bubble. The
                // oracle reports IDs as Strings; match them against this bubble's entity records.
                var entityRecord = bubble.getAllEntityRecords().stream()
                    .filter(record -> record.id().equals(crossingId))
                    .findFirst();

                if (entityRecord.isEmpty()) {
                    // Crossing reported for an entity this bubble does not own — nothing to migrate.
                    continue;
                }

                var position = entityRecord.get().position();

                // Entity IDs flow through the FSM and OptimisticMigrator as UUIDs. The oracle
                // reports the UUID's string form; parse it back so the FSM key, the optimistic
                // migrator, and the downstream stability/commit path (which key on UUID) all
                // agree on identity.
                final UUID entityId;
                try {
                    entityId = UUID.fromString(crossingId);
                } catch (IllegalArgumentException e) {
                    log.warn("Crossing entity id {} is not a UUID; cannot migrate", crossingId);
                    continue;
                }

                // Only own (OWNED) entities can begin an outbound migration. Initialize tracking
                // if the FSM has not yet seen this entity.
                var currentState = migrationFsm.getState(entityId);
                if (currentState == null) {
                    migrationFsm.initializeOwned(entityId);
                    currentState = migrationFsm.getState(entityId);
                }

                if (currentState == EntityMigrationState.OWNED) {
                    // Resolve the destination bubble for the entity's new position.
                    var targetBubble = migrationOracle.getTargetBubble(position);
                    if (targetBubble == null || targetBubble.equals(bubble.id())) {
                        // No distinct destination — not actually leaving this bubble.
                        continue;
                    }

                    // Begin optimistic migration: subsequent physics updates are deferred until
                    // the migration commits or rolls back.
                    optimisticMigrator.initiateOptimisticMigration(entityId, targetBubble);

                    // Drive the FSM: OWNED → MIGRATING_OUT. This fires onEntityStateTransition,
                    // which freezes physics and records the migration context. If the transition
                    // is rejected (e.g. invalid/blocked), undo the optimistic migration.
                    var transition = migrationFsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);
                    if (!transition.success) {
                        log.debug("FSM rejected OWNED->MIGRATING_OUT for entity {}: {}",
                                entityId, transition.reason);
                        optimisticMigrator.rollbackMigration(entityId, "fsm_rejected");
                        continue;
                    }

                    // Notify the destination that the entity is departing this bubble.
                    sendEntityDepartureEvent(entityId, targetBubble);

                    totalMigrationsInitiated++;

                    log.debug("Migration initiated for entity {} -> target bubble {}", entityId, targetBubble);
                }
            } catch (Exception e) {
                log.error("Error processing migration for entity {}: {}", crossingId, e.getMessage());
            }
        }

        // Clear crossing cache for next tick
        migrationOracle.clearCrossingCache();
    }

    /**
     * Send an EntityDepartureEvent to the destination bubble announcing that the entity is
     * leaving this bubble (source side of the migration handshake).
     * <p>
     * The cross-bubble transport channel is not owned by this class; consistent with the other
     * {@code send*} handlers here, the event is constructed (so the payload is real, not a stub)
     * and logged. The constructed event is retained for diagnostics/testing.
     *
     * @param entityId     entity departing this bubble
     * @param targetBubble destination bubble
     */
    private void sendEntityDepartureEvent(UUID entityId, UUID targetBubble) {
        // No Lamport clock source is wired into this bubble integration; use 0 rather than
        // fabricate a timestamp. Cross-bubble ordering is handled by the transport layer when
        // the channel is wired.
        var event = new EntityDepartureEvent(entityId, bubble.id(), targetBubble,
                EntityMigrationState.MIGRATING_OUT, 0L);
        lastDepartureEvent = event;
        log.debug("EntityDepartureEvent sent for entity {} -> target {}", entityId, targetBubble);
    }

    /**
     * Check for entities that have been in MIGRATING_IN state long enough
     * for view to stabilize, and commit their migrations.
     * <p>
     * TOCTOU Race Fix (Luciferase-yag5): Use viewId validation to prevent
     * migrations from committing after view changes.
     */
    private void processPendingMigrations() {
        // Check stability and capture viewId (TOCTOU race prevention)
        var stabilityCheck = viewMonitor.checkStability();
        if (!stabilityCheck.stable()) {
            return; // View not stable yet, wait
        }

        // Iterate through entities waiting for stability
        var iterator = entityStabilityTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var entityId = entry.getKey();
            var stableTicks = entry.getValue() + 1;

            // Check if stable for required ticks
            if (stableTicks >= viewStabilityTicks) {
                try {
                    // Validate viewId hasn't changed (TOCTOU race detection)
                    var currentViewId = viewMonitor.getCurrentViewId();
                    if (stabilityCheck.viewId() != null && !stabilityCheck.viewId().equals(currentViewId)) {
                        log.debug("View changed during migration prep for entity {}, aborting commit", entityId);
                        iterator.remove();  // Remove from pending, will retry in new view
                        continue;
                    }

                    // Flush deferred updates
                    optimisticMigrator.flushDeferredUpdates(entityId);

                    // Transition FSM: MIGRATING_IN → OWNED
                    migrationFsm.transition(entityId, EntityMigrationState.OWNED);

                    // Send ViewSynchronyAck to source
                    sendViewSynchronyAck(entityId);

                    totalMigrationsCompleted++;

                    log.debug("Migration completed for entity: {} (stable for {} ticks)",
                            entityId, stableTicks);

                    iterator.remove();
                } catch (Exception e) {
                    log.error("Error completing migration for entity {}: {}", entityId, e.getMessage());
                }
            } else {
                // Increment tick counter
                entry.setValue(stableTicks);
            }
        }
    }

    /**
     * Process timed-out migrations (default 8 seconds).
     * Called each tick to check for migrations stuck in MIGRATING_OUT/MIGRATING_IN states.
     *
     * @param simulationTime Current simulation time
     */
    private void processTimeouts(long simulationTime) {
        try {
            // checkTimeouts() reports the timed-out entities WITHOUT mutating FSM state.
            // Drive the FSM rollback once per timed-out entity via an explicit per-entity
            // transition. (Calling migrationFsm.processTimeouts() here would re-run
            // checkTimeouts() internally and reprocess ALL timed-out entities once per
            // iteration — N+1 processing per tick, triggering spurious rollbacks. See
            // Luciferase-0frcy.13.)
            var timedOutEntities = migrationFsm.checkTimeouts(simulationTime);

            for (var entityId : timedOutEntities) {
                if (entityId instanceof UUID uuid) {
                    optimisticMigrator.rollbackMigration(uuid, "timeout");

                    // Drive the FSM rollback for THIS entity only.
                    migrationFsm.transition(uuid, EntityMigrationState.ROLLBACK_OWNED);

                    // Send rollback event
                    sendEntityRollbackEvent(uuid, "timeout");

                    totalTimeoutsProcessed++;
                }

                log.warn("Migration timeout for entity: {}", entityId);
            }
        } catch (Exception e) {
            log.error("Error processing migration timeouts: {}", e.getMessage());
        }
    }

    /**
     * Notify of entity state transition (MigrationStateListener callback).
     * Invoked synchronously when FSM transitions an entity.
     *
     * @param entityId Entity changing state
     * @param fromState Previous state
     * @param toState New state
     * @param result Transition result
     */
    @Override
    public void onEntityStateTransition(Object entityId,
                                       EntityMigrationState fromState,
                                       EntityMigrationState toState,
                                       EntityMigrationStateMachine.TransitionResult result) {
        if (!result.success) {
            log.debug("Failed migration transition for {}: {} → {}: {}",
                    entityId, fromState, toState, result.reason);
            return;
        }

        log.debug("Entity {} transitioned: {} → {}", entityId, fromState, toState);

        // This integration keys the FSM exclusively on UUID (see detectAndInitiateMigrations,
        // which parses the oracle's String ids back to UUID). The stability map is therefore
        // UUID-keyed. A non-UUID key reaching here means an upstream contract was violated;
        // surface it loudly rather than silently dropping the MIGRATING_IN→OWNED commit path
        // (the only path by which a migration completes on the target bubble). See
        // Luciferase-0frcy.12.
        if (!(entityId instanceof UUID uuid)) {
            log.error("Migration FSM emitted non-UUID entity id {} ({}) for transition {}→{}; "
                      + "stability/commit tracking requires UUID keys and will be skipped",
                      entityId, entityId == null ? "null" : entityId.getClass().getName(),
                      fromState, toState);
            return;
        }

        // Handle specific transitions
        if (toState == EntityMigrationState.MIGRATING_OUT) {
            // Entity is leaving this bubble - freeze physics
            freezeEntityPhysics(uuid.toString());
        } else if (toState == EntityMigrationState.MIGRATING_IN) {
            // Entity arriving - start deferring physics updates
            startDeferringUpdates(uuid.toString());
            entityStabilityTicks.put(uuid, 0);
        } else if (toState == EntityMigrationState.ROLLBACK_OWNED) {
            // Migration rolled back - thaw physics
            thawEntityPhysics(uuid.toString());
            entityStabilityTicks.remove(uuid);
        } else if (toState == EntityMigrationState.GHOST) {
            // Target abandoned migration - forget this entity
            entityStabilityTicks.remove(uuid);
        }
    }

    /**
     * Notify of view change rollback (MigrationStateListener callback).
     * Invoked when group membership changes.
     *
     * @param rolledBackCount Entities rolled back to ROLLBACK_OWNED
     * @param ghostCount Entities converted to GHOST
     */
    @Override
    public void onViewChangeRollback(int rolledBackCount, int ghostCount) {
        log.warn("View change: rolled back {} migrations, {} ghosts", rolledBackCount, ghostCount);

        // Clear stability tracking for rolled-back entities
        entityStabilityTicks.clear();

        totalMigrationsRolledBack += rolledBackCount;
    }

    /**
     * Freeze entity physics (called when transitioning to MIGRATING_OUT).
     * In real implementation, would pause physics engine updates for this entity.
     *
     * @param entityId Entity to freeze
     */
    private void freezeEntityPhysics(String entityId) {
        // Placeholder: In real implementation, would disable physics updates
        // for this entity until migration completes
        log.debug("Physics frozen for entity: {}", entityId);
    }

    /**
     * Thaw entity physics (called when transitioning to ROLLBACK_OWNED).
     * In real implementation, would resume physics engine updates.
     *
     * @param entityId Entity to thaw
     */
    private void thawEntityPhysics(String entityId) {
        // Placeholder: In real implementation, would resume physics updates
        log.debug("Physics thawed for entity: {}", entityId);
    }

    /**
     * Start deferring physics updates (called when transitioning to MIGRATING_IN).
     * Queue mode: subsequent position/velocity updates are queued instead of applied.
     *
     * @param entityId Entity to defer updates for
     */
    private void startDeferringUpdates(String entityId) {
        // Placeholder: In real implementation, would switch entity to deferred-update mode
        log.debug("Deferred updates started for entity: {}", entityId);
    }

    /**
     * Send ViewSynchronyAck to source bubble after migration completion.
     * Signals that target has confirmed ownership and stabilized.
     *
     * @param entityId Entity that completed migration
     */
    private void sendViewSynchronyAck(UUID entityId) {
        // Placeholder: In real implementation, would send:
        // ViewSynchronyAck(entityId, sourceBubbleId, bubble.id(), 3, lamportClock)
        // via cross-bubble communication channel
        log.debug("ViewSynchronyAck sent for entity: {}", entityId);
    }

    /**
     * Send EntityRollbackEvent on migration failure.
     * Notifies source that migration failed and entity remains on source.
     *
     * @param entityId Entity that was rolled back
     * @param reason Rollback reason: "timeout", "view_change", "manual"
     */
    private void sendEntityRollbackEvent(UUID entityId, String reason) {
        // Placeholder: In real implementation, would send:
        // EntityRollbackEvent(entityId, bubble.id(), targetBubbleId, reason, lamportClock)
        // via cross-bubble communication channel
        log.debug("EntityRollbackEvent sent for entity {}: reason={}", entityId, reason);
    }

    /**
     * Get metrics for diagnostics.
     *
     * @return String containing migration statistics
     */
    public String getMetrics() {
        return String.format(
            "EnhancedBubbleMigrationIntegration{initiated=%d, completed=%d, rolledBack=%d, " +
            "timeouts=%d, pending=%d}",
            totalMigrationsInitiated,
            totalMigrationsCompleted,
            totalMigrationsRolledBack,
            totalTimeoutsProcessed,
            entityStabilityTicks.size()
        );
    }

    /**
     * Get the migration oracle for boundary detection.
     *
     * @return MigrationOracle instance
     */
    public MigrationOracle getMigrationOracle() {
        return migrationOracle;
    }

    /**
     * Get the number of migrations initiated by boundary-crossing detection.
     *
     * @return total migrations initiated
     */
    public long getTotalMigrationsInitiated() {
        return totalMigrationsInitiated;
    }

    /**
     * Get the most recent EntityDepartureEvent produced during migration initiation
     * (diagnostics/testing).
     *
     * @return last departure event, or {@code null} if none has been produced
     */
    public EntityDepartureEvent getLastDepartureEvent() {
        return lastDepartureEvent;
    }

    /**
     * Get the optimistic migrator for migration coordination.
     *
     * @return OptimisticMigrator instance
     */
    public OptimisticMigrator getOptimisticMigrator() {
        return optimisticMigrator;
    }

    @Override
    public String toString() {
        return String.format(
            "EnhancedBubbleMigrationIntegration{bubble=%s, fsm=%s, oracle=%s, " +
            "migrator=%s, stability_ticks=%d}",
            bubble.id(),
            migrationFsm.getClass().getSimpleName(),
            migrationOracle.getClass().getSimpleName(),
            optimisticMigrator.getClass().getSimpleName(),
            viewStabilityTicks
        );
    }
}
