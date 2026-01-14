/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.von.VonBubble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages cross-bubble entity migration using two-phase commit protocol.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Identify migration candidates (entities past hysteresis threshold)</li>
 *   <li>Enforce migration cooldowns (prevent boundary oscillation)</li>
 *   <li>Execute two-phase commit protocol (PREPARE → COMMIT/ROLLBACK)</li>
 *   <li>Record migration metrics via SimulationTickMetrics</li>
 * </ul>
 * <p>
 * Two-Phase Commit Protocol:
 * <pre>
 * PREPARE Phase:
 *   1. Validate entity exists in source bubble
 *   2. Validate entity NOT in target bubble
 *   3. Check migration cooldown
 *   4. Check hysteresis threshold
 *   5. Create MigrationIntent with snapshot of entity state
 *
 * COMMIT Phase:
 *   1. Remove ghost from target (if present)
 *   2. Add entity to target bubble
 *   3. Add velocity to target
 *   4. Remove entity from source bubble
 *   5. Remove velocity from source
 *   6. Update metrics and cooldown
 *
 * ROLLBACK (on failure):
 *   - Remove entity from target
 *   - Remove velocity from target
 *   - Restore velocity to source (if needed)
 * </pre>
 * <p>
 * Atomicity: Add-before-remove ordering ensures entity never lost.
 * If COMMIT fails, ROLLBACK ensures entity stays in source.
 *
 * @author hal.hildebrand
 */
public class CrossBubbleMigrationManager {

    private static final Logger log = LoggerFactory.getLogger(CrossBubbleMigrationManager.class);

    private final VonBubble bubble1;
    private final VonBubble bubble2;
    private final float boundaryX;
    private final int cooldownTicks;
    private final float hysteresisDistance;
    private final ConcurrentHashMap<String, Long> migrationCooldowns;
    private final SimulationTickMetrics tickMetrics;

    /**
     * Create migration manager for two bubbles.
     *
     * @param bubble1            First bubble (left side)
     * @param bubble2            Second bubble (right side)
     * @param boundaryX          X coordinate dividing the bubbles
     * @param cooldownTicks      Minimum ticks between migrations for same entity
     * @param hysteresisDistance Distance past boundary to trigger migration
     * @param tickMetrics        Metrics tracker for recording migrations
     */
    public CrossBubbleMigrationManager(VonBubble bubble1, VonBubble bubble2,
                                        float boundaryX, int cooldownTicks,
                                        float hysteresisDistance,
                                        SimulationTickMetrics tickMetrics) {
        this.bubble1 = bubble1;
        this.bubble2 = bubble2;
        this.boundaryX = boundaryX;
        this.cooldownTicks = cooldownTicks;
        this.hysteresisDistance = hysteresisDistance;
        this.migrationCooldowns = new ConcurrentHashMap<>();
        this.tickMetrics = tickMetrics;
    }

    /**
     * Check for entities that need migration and execute two-phase commit protocol.
     * <p>
     * Phase 1 (PREPARE): Validate migration is safe, create intents
     * Phase 2 (COMMIT): Execute migrations atomically with rollback on failure
     *
     * @param currentTick Current simulation tick
     * @param velocities1 Velocity map for bubble 1
     * @param velocities2 Velocity map for bubble 2
     */
    public void checkAndMigrate(long currentTick,
                                 Map<String, Vector3f> velocities1,
                                 Map<String, Vector3f> velocities2) {
        // Snapshot entities atomically - use List.copyOf() to prevent modifications during iteration
        var bubble1Snapshot = List.copyOf(bubble1.getAllEntityRecords());
        var bubble2Snapshot = List.copyOf(bubble2.getAllEntityRecords());

        // Phase 1: PREPARE - identify migration candidates with hysteresis and cooldown checks
        var intentsTo2 = new ArrayList<MigrationIntent>();
        for (var entity : bubble1Snapshot) {
            if (shouldMigrate(entity, MigrationDirection.TO_BUBBLE_2, currentTick)) {
                var intent = prepareMigration(entity, MigrationDirection.TO_BUBBLE_2, currentTick, velocities1);
                if (intent != null) {
                    intentsTo2.add(intent);
                }
            }
        }

        var intentsTo1 = new ArrayList<MigrationIntent>();
        for (var entity : bubble2Snapshot) {
            if (shouldMigrate(entity, MigrationDirection.TO_BUBBLE_1, currentTick)) {
                var intent = prepareMigration(entity, MigrationDirection.TO_BUBBLE_1, currentTick, velocities2);
                if (intent != null) {
                    intentsTo1.add(intent);
                }
            }
        }

        // Phase 2: COMMIT - execute migrations with rollback on failure
        for (var intent : intentsTo2) {
            var result = commitMigration(intent, currentTick, velocities1, velocities2);
            if (!result.success()) {
                log.warn("Migration failed: {}", result.message());
            }
        }

        for (var intent : intentsTo1) {
            var result = commitMigration(intent, currentTick, velocities1, velocities2);
            if (!result.success()) {
                log.warn("Migration failed: {}", result.message());
            }
        }
    }

    /**
     * Clean up expired cooldown entries.
     * Prevents memory growth from accumulated cooldown entries in long-running simulations.
     *
     * @param currentTick Current simulation tick
     */
    public void cleanupExpiredCooldowns(long currentTick) {
        int sizeBefore = migrationCooldowns.size();
        migrationCooldowns.entrySet().removeIf(e -> e.getValue() <= currentTick);
        int removed = sizeBefore - migrationCooldowns.size();

        if (removed > 0) {
            log.debug("Cooldown cleanup: removed {} expired entries", removed);
        }
    }

    /**
     * Get count of entities currently in migration cooldown.
     *
     * @param currentTick Current simulation tick
     * @return Number of entities that cannot migrate yet
     */
    public int getActiveCooldownCount(long currentTick) {
        return (int) migrationCooldowns.entrySet().stream()
            .filter(e -> e.getValue() > currentTick)
            .count();
    }

    // ========== Private Migration Protocol ==========

    /**
     * Check if entity should migrate based on hysteresis and cooldown.
     *
     * @param entity      Entity to check
     * @param direction   Target direction
     * @param currentTick Current simulation tick
     * @return true if entity should migrate
     */
    private boolean shouldMigrate(EnhancedBubble.EntityRecord entity, MigrationDirection direction, long currentTick) {
        // Check cooldown first (cheaper check)
        if (isInCooldown(entity.id(), currentTick)) {
            return false;
        }

        // Apply hysteresis: entity must be past boundary by hysteresisDistance
        float x = entity.position().x;
        if (direction == MigrationDirection.TO_BUBBLE_2) {
            // Entity in bubble1 needs to be hysteresisDistance past boundary into bubble2's region
            return x >= (boundaryX + hysteresisDistance);
        } else {
            // Entity in bubble2 needs to be hysteresisDistance past boundary into bubble1's region
            return x < (boundaryX - hysteresisDistance);
        }
    }

    /**
     * Check if entity is in migration cooldown.
     *
     * @param entityId    Entity ID to check
     * @param currentTick Current simulation tick
     * @return true if entity cannot migrate yet
     */
    private boolean isInCooldown(String entityId, long currentTick) {
        var cooldownExpires = migrationCooldowns.get(entityId);
        if (cooldownExpires == null) {
            return false;
        }
        return currentTick < cooldownExpires;
    }

    /**
     * PREPARE phase: Validate migration and create intent.
     *
     * @param entity      Entity to migrate
     * @param direction   Target direction
     * @param currentTick Current simulation tick
     * @param velocities  Source velocity map
     * @return MigrationIntent if valid, null if migration should not proceed
     */
    private MigrationIntent prepareMigration(EnhancedBubble.EntityRecord entity, MigrationDirection direction,
                                              long currentTick, Map<String, Vector3f> velocities) {
        String entityId = entity.id();

        // Validate entity exists in source bubble
        var sourceBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble1 : bubble2;
        var targetBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble2 : bubble1;

        if (!sourceBubble.getEntities().contains(entityId)) {
            log.trace("Prepare failed: entity {} not in source bubble", entityId);
            return null;
        }

        // Validate entity NOT in target bubble (prevents duplicates)
        if (targetBubble.getEntities().contains(entityId)) {
            log.warn("Prepare failed: entity {} already in target bubble", entityId);
            return null;
        }

        // Get velocity from source bubble's velocity map
        var velocity = velocities.get(entityId);

        return new MigrationIntent(
            entityId,
            new Point3f(entity.position()),  // Copy position
            entity.content(),
            velocity != null ? new Vector3f(velocity) : null,
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
     * @param intent      Migration intent from prepare phase
     * @param currentTick Current simulation tick
     * @param velocities1 Velocity map for bubble 1
     * @param velocities2 Velocity map for bubble 2
     * @return MigrationResult indicating success or failure
     */
    private MigrationResult commitMigration(MigrationIntent intent, long currentTick,
                                             Map<String, Vector3f> velocities1,
                                             Map<String, Vector3f> velocities2) {
        String entityId = intent.entityId();
        var direction = intent.direction();

        var sourceBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble1 : bubble2;
        var targetBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble2 : bubble1;
        var sourceVelocities = (direction == MigrationDirection.TO_BUBBLE_2) ? velocities1 : velocities2;
        var targetVelocities = (direction == MigrationDirection.TO_BUBBLE_2) ? velocities2 : velocities1;

        try {
            // Step 1: Add entity to target bubble (target now owns entity)
            targetBubble.addEntity(entityId, intent.position(), intent.content());
        } catch (Exception e) {
            // Add failed - no rollback needed, entity stays in source
            tickMetrics.recordMigrationFailure();
            return MigrationResult.failure(entityId, direction, "Failed to add to target: " + e.getMessage());
        }

        try {
            // Step 2: Add velocity to target (but don't remove from source yet)
            if (intent.velocity() != null) {
                targetVelocities.put(entityId, intent.velocity());
            }

            // Step 3: Remove entity from source bubble
            sourceBubble.removeEntity(entityId);

            // Step 4: Now safe to remove velocity from source (source removal succeeded)
            sourceVelocities.remove(entityId);

            // Step 5: Update metrics and cooldown
            if (direction == MigrationDirection.TO_BUBBLE_2) {
                tickMetrics.recordMigrationTo2();
            } else {
                tickMetrics.recordMigrationTo1();
            }
            migrationCooldowns.put(entityId, currentTick + cooldownTicks);

            log.debug("Migrated {} from bubble{} to bubble{}", entityId,
                      direction == MigrationDirection.TO_BUBBLE_2 ? 1 : 2,
                      direction == MigrationDirection.TO_BUBBLE_2 ? 2 : 1);
            return MigrationResult.success(entityId, direction);

        } catch (Exception e) {
            // Rollback: remove from target since it was added but migration failed
            // Note: velocity was NOT removed from source, so no need to restore it
            rollbackMigration(intent, e, sourceVelocities, targetVelocities);
            tickMetrics.recordMigrationFailure();
            return MigrationResult.failure(entityId, direction, "Rollback after failure: " + e.getMessage());
        }
    }

    /**
     * Rollback a failed migration by removing entity from target and restoring velocity.
     * <p>
     * Called when migration fails after entity was added to target but before
     * source removal completed. Ensures entity stays in source with velocity intact.
     *
     * @param intent           Original migration intent
     * @param cause            Exception that caused the failure
     * @param sourceVelocities Source velocity map
     * @param targetVelocities Target velocity map
     */
    private void rollbackMigration(MigrationIntent intent, Exception cause,
                                    Map<String, Vector3f> sourceVelocities,
                                    Map<String, Vector3f> targetVelocities) {
        String entityId = intent.entityId();
        var direction = intent.direction();
        var targetBubble = (direction == MigrationDirection.TO_BUBBLE_2) ? bubble2 : bubble1;

        try {
            // Remove entity from target bubble
            targetBubble.removeEntity(entityId);

            // Remove velocity from target (it was copied there)
            targetVelocities.remove(entityId);

            // Restore velocity to source if it was transferred
            // (With new ordering, velocity is NOT removed from source until after
            // source removal succeeds, so this is just a safety net)
            if (intent.velocity() != null && !sourceVelocities.containsKey(entityId)) {
                sourceVelocities.put(entityId, intent.velocity());
            }

            log.warn("Rolled back migration of {} from bubble{} to bubble{}: {}",
                     entityId,
                     direction == MigrationDirection.TO_BUBBLE_2 ? 1 : 2,
                     direction == MigrationDirection.TO_BUBBLE_2 ? 2 : 1,
                     cause.getMessage());
        } catch (Exception rollbackError) {
            log.error("Rollback failed for {}: original error={}, rollback error={}",
                      entityId, cause.getMessage(), rollbackError.getMessage());
        }
    }

    // ========== Data Structures ==========

    /**
     * Migration direction enum.
     */
    public enum MigrationDirection {
        TO_BUBBLE_1,
        TO_BUBBLE_2
    }

    /**
     * Migration intent: captures entity state and validates migration is safe.
     * Created during PREPARE phase, consumed during COMMIT phase.
     */
    public record MigrationIntent(
        String entityId,
        Point3f position,
        Object content,
        Vector3f velocity,
        MigrationDirection direction,
        long preparedAtTick
    ) {}

    /**
     * Migration result: outcome of a migration attempt.
     */
    public record MigrationResult(
        String entityId,
        MigrationDirection direction,
        boolean success,
        String message
    ) {
        public static MigrationResult success(String entityId, MigrationDirection direction) {
            return new MigrationResult(entityId, direction, true, "Success");
        }

        public static MigrationResult failure(String entityId, MigrationDirection direction, String message) {
            return new MigrationResult(entityId, direction, false, message);
        }
    }
}
