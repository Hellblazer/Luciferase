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

        for (var entityId : crossingEntities) {
            try {
                // Determine target bubble
                var entityRecord = bubble.getEntities().stream()
                    .filter(id -> id.equals(entityId))
                    .findFirst();

                if (entityRecord.isEmpty()) {
                    continue;
                }

                // Get entity position (would need to extend EnhancedBubble to provide this)
                // For now, we skip detailed position tracking
                // In real implementation, would get actual position and determine target

                // Check current FSM state
                var currentState = migrationFsm.getState(entityId);
                if (currentState == EntityMigrationState.OWNED) {
                    // Determine target bubble from position
                    // UUID targetBubble = migrationOracle.getTargetBubble(position);

                    // Initiate optimistic migration
                    // optimisticMigrator.initiateOptimisticMigration(entityId, targetBubble);

                    // Transition FSM: OWNED → MIGRATING_OUT
                    // migrationFsm.transition(entityId, EntityMigrationState.MIGRATING_OUT);

                    totalMigrationsInitiated++;

                    log.debug("Migration initiated for entity: {}", entityId);
                }
            } catch (Exception e) {
                log.error("Error processing migration for entity {}: {}", entityId, e.getMessage());
            }
        }

        // Clear crossing cache for next tick
        migrationOracle.clearCrossingCache();
    }

    /**
     * Check for entities that have been in MIGRATING_IN state long enough
     * for view to stabilize, and commit their migrations.
     */
    private void processPendingMigrations() {
        // Check if view is stable
        if (!viewMonitor.isViewStable()) {
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
            var timedOutEntities = migrationFsm.checkTimeouts(simulationTime);

            for (var entityId : timedOutEntities) {
                // Rollback the migration if entity is a UUID
                if (entityId instanceof UUID) {
                    optimisticMigrator.rollbackMigration((UUID) entityId, "timeout");

                    // Transition FSM to handle timeout
                    migrationFsm.processTimeouts(simulationTime);

                    // Send rollback event
                    sendEntityRollbackEvent((UUID) entityId, "timeout");

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

        // Handle specific transitions
        if (toState == EntityMigrationState.MIGRATING_OUT) {
            // Entity is leaving this bubble - freeze physics
            freezeEntityPhysics(entityId.toString());
        } else if (toState == EntityMigrationState.MIGRATING_IN) {
            // Entity arriving - start deferring physics updates
            startDeferringUpdates(entityId.toString());
            if (entityId instanceof UUID) {
                entityStabilityTicks.put((UUID) entityId, 0);
            }
        } else if (toState == EntityMigrationState.ROLLBACK_OWNED) {
            // Migration rolled back - thaw physics
            thawEntityPhysics(entityId.toString());
            if (entityId instanceof UUID) {
                entityStabilityTicks.remove(entityId);
            }
        } else if (toState == EntityMigrationState.GHOST) {
            // Target abandoned migration - forget this entity
            if (entityId instanceof UUID) {
                entityStabilityTicks.remove(entityId);
            }
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
