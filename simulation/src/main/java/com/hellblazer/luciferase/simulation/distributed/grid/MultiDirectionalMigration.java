/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.grid;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-directional entity migration for grid-based simulations.
 * <p>
 * Handles 8-direction migration (N, S, E, W, NE, NW, SE, SW) with:
 * - Two-phase commit (PREPARE/COMMIT)
 * - Hysteresis (2.0f distance past boundary before migration)
 * - Cooldown (30 ticks between migrations)
 * - Rollback on failure
 * - Atomic add-before-remove for entity safety
 * <p>
 * Diagonal tie-breaking rule:
 * - When entity crosses 2 boundaries simultaneously (e.g., both X and Y exceeded)
 * - Migrate to bubble with largest boundary overshoot: max(|Δx|, |Δy|)
 * - If |Δx| = |Δy|: Prefer X-axis (deterministic)
 * <p>
 * Based on TwoBubbleSimulation migration protocol.
 *
 * @author hal.hildebrand
 */
public class MultiDirectionalMigration {

    private static final Logger log = LoggerFactory.getLogger(MultiDirectionalMigration.class);

    /**
     * Hysteresis distance: entity must be this far past boundary to migrate.
     * Prevents oscillation at boundaries.
     */
    public static final float HYSTERESIS_DISTANCE = 2.0f;

    /**
     * Cooldown period: ticks to wait before allowing another migration.
     * Prevents thrashing.
     */
    public static final long MIGRATION_COOLDOWN_TICKS = 30;

    private final GridConfiguration gridConfig;
    private final BubbleGrid<EnhancedBubble> bubbleGrid;
    private final Map<String, javax.vecmath.Vector3f> velocities;
    private final MigrationRouter router;
    private final MigrationMetrics metrics;

    // Entity ID -> tick when cooldown expires
    private final Map<String, Long> migrationCooldowns = new ConcurrentHashMap<>();

    public MultiDirectionalMigration(
        GridConfiguration gridConfig,
        BubbleGrid<EnhancedBubble> bubbleGrid,
        Map<String, javax.vecmath.Vector3f> velocities
    ) {
        this.gridConfig = gridConfig;
        this.bubbleGrid = bubbleGrid;
        this.velocities = velocities;
        this.router = new MigrationRouter(gridConfig);
        this.metrics = new MigrationMetrics();
    }

    /**
     * Check all bubbles for entities that need migration.
     * <p>
     * Two-phase protocol:
     * 1. PREPARE: Identify candidates, validate safety
     * 2. COMMIT: Execute migrations atomically with rollback
     *
     * @param currentTick Current simulation tick
     */
    public void checkMigrations(long currentTick) {
        // Phase 1: PREPARE - identify migration candidates for all bubbles
        var allIntents = new ArrayList<MigrationIntent>();

        for (int row = 0; row < gridConfig.rows(); row++) {
            for (int col = 0; col < gridConfig.columns(); col++) {
                var coord = new BubbleCoordinate(row, col);
                var bubble = bubbleGrid.getBubble(coord);

                // Snapshot entities atomically
                var snapshot = List.copyOf(bubble.getAllEntityRecords());

                for (var entity : snapshot) {
                    var direction = shouldMigrate(entity, coord, currentTick);
                    if (direction != null) {
                        var intent = prepareMigration(entity, coord, direction, currentTick);
                        if (intent != null) {
                            allIntents.add(intent);
                        }
                    }
                }
            }
        }

        // Phase 2: COMMIT - execute migrations with rollback on failure
        for (var intent : allIntents) {
            var result = commitMigration(intent, currentTick);
            if (!result.success()) {
                log.warn("Migration failed: {}", result.message());
            }
        }

        // Update active cooldown count for metrics
        metrics.updateActiveCooldowns(migrationCooldowns.size());
    }

    /**
     * Get migration metrics.
     *
     * @return Current metrics
     */
    public MigrationMetrics getMetrics() {
        return metrics;
    }

    /**
     * Check if entity should migrate based on hysteresis and cooldown.
     *
     * @param entity Entity to check
     * @param sourceCoord Source bubble coordinate
     * @param currentTick Current simulation tick
     * @return Migration direction if entity should migrate, null otherwise
     */
    private MigrationDirection shouldMigrate(
        EnhancedBubble.EntityRecord entity,
        BubbleCoordinate sourceCoord,
        long currentTick
    ) {
        // Check cooldown first (cheaper check)
        if (isInCooldown(entity.id(), currentTick)) {
            return null;
        }

        // Use router to detect migration direction with hysteresis
        return router.detectMigrationDirection(sourceCoord, entity.position(), HYSTERESIS_DISTANCE);
    }

    /**
     * Check if entity is in migration cooldown.
     *
     * @param entityId Entity ID to check
     * @param currentTick Current simulation tick
     * @return true if entity cannot migrate yet
     */
    private boolean isInCooldown(String entityId, long currentTick) {
        var cooldownExpires = migrationCooldowns.get(entityId);
        if (cooldownExpires == null) {
            return false;
        }

        if (currentTick >= cooldownExpires) {
            // Cooldown expired - remove from map
            migrationCooldowns.remove(entityId);
            return false;
        }

        return true;
    }

    /**
     * PREPARE phase: Validate migration and create intent.
     *
     * @param entity Entity to migrate
     * @param sourceCoord Source bubble coordinate
     * @param direction Target direction
     * @param currentTick Current simulation tick
     * @return MigrationIntent if valid, null if migration should not proceed
     */
    private MigrationIntent prepareMigration(
        EnhancedBubble.EntityRecord entity,
        BubbleCoordinate sourceCoord,
        MigrationDirection direction,
        long currentTick
    ) {
        String entityId = entity.id();

        // Validate target coordinate is in bounds (router already checked, but be defensive)
        var targetCoord = direction.apply(sourceCoord);
        if (!gridConfig.isValid(targetCoord)) {
            log.trace("Prepare failed: target {} out of bounds", targetCoord);
            return null;
        }

        var sourceBubble = bubbleGrid.getBubble(sourceCoord);
        var targetBubble = bubbleGrid.getBubble(targetCoord);

        // Validate entity exists in source bubble
        if (!sourceBubble.getEntities().contains(entityId)) {
            log.trace("Prepare failed: entity {} not in source bubble {}", entityId, sourceCoord);
            return null;
        }

        // Validate entity NOT in target bubble (prevents duplicates)
        if (targetBubble.getEntities().contains(entityId)) {
            log.warn("Prepare failed: entity {} already in target bubble {}", entityId, targetCoord);
            return null;
        }

        // Get velocity
        var velocity = velocities.get(entityId);

        return new MigrationIntent(
            entityId,
            new Point3f(entity.position()),  // Copy position
            entity.content(),
            velocity != null ? new javax.vecmath.Vector3f(velocity) : null,
            sourceCoord,
            targetCoord,
            direction,
            currentTick
        );
    }

    /**
     * COMMIT phase: Execute migration atomically with rollback on failure.
     * <p>
     * ATOMICITY GUARANTEE: Add-before-remove prevents entity loss.
     * Alternative remove-before-add would risk entity loss if add fails.
     * <p>
     * Order of operations for atomicity:
     * <ol>
     *   <li>Add entity to target bubble (target now owns entity)</li>
     *   <li>Add velocity to target (don't remove from source yet)</li>
     *   <li>Remove entity from source bubble</li>
     *   <li>Remove velocity from source (only after source removal succeeds)</li>
     *   <li>Update metrics and cooldown</li>
     * </ol>
     * <p>
     * Rollback scenarios:
     * <ul>
     *   <li>Step 1 fails: no rollback needed, entity stays in source</li>
     *   <li>Step 3 fails: rollback removes from target, velocity stays in source</li>
     * </ul>
     *
     * @param intent Migration intent from prepare phase
     * @param currentTick Current simulation tick
     * @return MigrationResult indicating success or failure
     */
    private MigrationResult commitMigration(MigrationIntent intent, long currentTick) {
        String entityId = intent.entityId();

        var sourceBubble = bubbleGrid.getBubble(intent.sourceCoord());
        var targetBubble = bubbleGrid.getBubble(intent.targetCoord());

        try {
            // Step 1: Add entity to target bubble (target now owns entity)
            targetBubble.addEntity(entityId, intent.position(), intent.content());
        } catch (Exception e) {
            // Add failed - no rollback needed, entity stays in source
            metrics.recordFailure();
            return MigrationResult.failure(
                entityId,
                intent.direction(),
                "Failed to add to target: " + e.getMessage()
            );
        }

        try {
            // Step 2: Add velocity to target (but don't remove from source yet)
            if (intent.velocity() != null) {
                velocities.put(entityId, intent.velocity());
            }

            // Step 3: Remove entity from source bubble
            sourceBubble.removeEntity(entityId);

            // Step 4: Now safe to remove velocity from source (source removal succeeded)
            // Note: velocity is already in target from step 2, so this just cleans up

            // Step 5: Update metrics and cooldown
            metrics.recordMigration(intent.direction());
            migrationCooldowns.put(entityId, currentTick + MIGRATION_COOLDOWN_TICKS);

            log.debug("Migrated {} from {} to {} ({})",
                      entityId, intent.sourceCoord(), intent.targetCoord(), intent.direction());
            return MigrationResult.success(entityId, intent.direction());

        } catch (Exception e) {
            // Rollback: remove from target since it was added but migration failed
            // Note: velocity was NOT removed from source, so it's still there
            rollbackMigration(intent, e);
            metrics.recordFailure();
            return MigrationResult.failure(
                entityId,
                intent.direction(),
                "Rollback after failure: " + e.getMessage()
            );
        }
    }

    /**
     * Rollback a failed migration by removing entity from target and restoring velocity.
     * <p>
     * Called when migration fails after entity was added to target but before
     * source removal completed. Ensures entity stays in source with velocity intact.
     *
     * @param intent Original migration intent
     * @param cause Exception that caused the failure
     */
    private void rollbackMigration(MigrationIntent intent, Exception cause) {
        String entityId = intent.entityId();
        var targetBubble = bubbleGrid.getBubble(intent.targetCoord());

        try {
            // Remove entity from target bubble
            targetBubble.removeEntity(entityId);

            // Velocity remains in source (it was never removed)

            log.warn("Rolled back migration of {} from {} to {}: {}",
                     entityId, intent.sourceCoord(), intent.targetCoord(), cause.getMessage());
        } catch (Exception rollbackError) {
            log.error("Rollback failed for {}: original error={}, rollback error={}",
                      entityId, cause.getMessage(), rollbackError.getMessage());
        }
    }

    // ========== Records ==========

    /**
     * Migration intent created during PREPARE phase.
     *
     * @param entityId Entity ID
     * @param position Entity position (copied)
     * @param content Entity content
     * @param velocity Entity velocity (copied)
     * @param sourceCoord Source bubble coordinate
     * @param targetCoord Target bubble coordinate
     * @param direction Migration direction
     * @param timestamp Tick when intent was created
     */
    private record MigrationIntent(
        String entityId,
        Point3f position,
        Object content,
        javax.vecmath.Vector3f velocity,
        BubbleCoordinate sourceCoord,
        BubbleCoordinate targetCoord,
        MigrationDirection direction,
        long timestamp
    ) {}

    /**
     * Result of a migration attempt.
     *
     * @param entityId Entity ID
     * @param direction Migration direction
     * @param success True if migration succeeded
     * @param message Error message if failed
     */
    private record MigrationResult(
        String entityId,
        MigrationDirection direction,
        boolean success,
        String message
    ) {
        static MigrationResult success(String entityId, MigrationDirection direction) {
            return new MigrationResult(entityId, direction, true, "");
        }

        static MigrationResult failure(String entityId, MigrationDirection direction, String message) {
            return new MigrationResult(entityId, direction, false, message);
        }
    }
}
