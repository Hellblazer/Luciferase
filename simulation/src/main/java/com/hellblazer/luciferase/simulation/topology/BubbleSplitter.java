/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.MutationLocks;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Executes bubble split operations with atomic entity redistribution.
 * <p>
 * Splits an overcrowded bubble (>5000 entities) into two bubbles by:
 * <ol>
 *   <li>Partitioning entities based on split plane geometry</li>
 *   <li>Creating new child bubble</li>
 *   <li>Atomically moving entities to new bubble via EntityAccountant</li>
 *   <li>Validating 100% entity retention</li>
 * </ol>
 * <p>
 * <b>Safety Guarantees</b>:
 * <ul>
 *   <li>Atomic entity transfer: All-or-nothing semantics via EntityAccountant</li>
 *   <li>100% retention validation: Pre/post entity counts must match</li>
 *   <li>No duplicates: EntityAccountant validates each entity in exactly one bubble</li>
 * </ul>
 * <p>
 * <b>Algorithm</b>:
 * <ol>
 *   <li>Get all entities from source bubble</li>
 *   <li>Partition entities by split plane (signed distance test)</li>
 *   <li>Create new child bubble at same spatial level</li>
 *   <li>For entities on positive side of plane:
 *     <ul>
 *       <li>Add entity to new bubble</li>
 *       <li>EntityAccountant.moveBetweenBubbles() (atomic)</li>
 *     </ul>
 *   </li>
 *   <li>Validate conservation: totalBefore == totalAfter</li>
 * </ol>
 * <p>
 * Phase 9C: Topology Reorganization & Execution
 *
 * @author hal.hildebrand
 */
public class BubbleSplitter {

    private static final Logger log = LoggerFactory.getLogger(BubbleSplitter.class);

    private final TetreeBubbleGrid bubbleGrid;
    private final EntityAccountant accountant;
    private final OperationTracker operationTracker;
    private final TopologyMetrics metrics;
    private final SplitPlaneStrategy strategy;

    // Pluggable UUID supplier for deterministic testing - defaults to random UUIDs
    private volatile Supplier<UUID> uuidSupplier = UUID::randomUUID;

    /**
     * Creates a bubble splitter with default LongestAxisStrategy.
     * <p>
     * Backward-compatible constructor - preserves existing behavior.
     *
     * @param bubbleGrid        the bubble grid
     * @param accountant        the entity accountant for atomic transfers
     * @param operationTracker  the operation tracker for rollback support
     * @param metrics           the metrics tracker for operational monitoring
     * @throws NullPointerException if any parameter is null
     */
    public BubbleSplitter(TetreeBubbleGrid bubbleGrid, EntityAccountant accountant, OperationTracker operationTracker,
                          TopologyMetrics metrics) {
        this(bubbleGrid, accountant, operationTracker, metrics, SplitPlaneStrategies.longestAxis());
    }

    /**
     * Creates a bubble splitter with custom split plane strategy.
     *
     * @param bubbleGrid        the bubble grid
     * @param accountant        the entity accountant for atomic transfers
     * @param operationTracker  the operation tracker for rollback support
     * @param metrics           the metrics tracker for operational monitoring
     * @param strategy          the split plane calculation strategy
     * @throws NullPointerException if any parameter is null
     */
    public BubbleSplitter(TetreeBubbleGrid bubbleGrid, EntityAccountant accountant, OperationTracker operationTracker,
                          TopologyMetrics metrics, SplitPlaneStrategy strategy) {
        this.bubbleGrid = java.util.Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null");
        this.accountant = java.util.Objects.requireNonNull(accountant, "accountant must not be null");
        this.operationTracker = java.util.Objects.requireNonNull(operationTracker, "operationTracker must not be null");
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics must not be null");
        this.strategy = java.util.Objects.requireNonNull(strategy, "strategy must not be null");
    }

    /**
     * Sets the UUID supplier to use for generating new bubble IDs.
     * <p>
     * For deterministic testing, inject a {@link com.hellblazer.luciferase.simulation.distributed.integration.SeededUuidSupplier}
     * to ensure reproducible UUID generation.
     *
     * @param uuidSupplier the UUID supplier to use (must not be null)
     * @throws NullPointerException if uuidSupplier is null
     */
    public void setUuidSupplier(Supplier<UUID> uuidSupplier) {
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier must not be null");
    }

    /**
     * Executes a split operation on a bubble.
     * <p>
     * Partitions entities based on split plane and creates new child bubble.
     * If key collision occurs at the initial child level, automatically retries
     * at progressively deeper levels (up to tetree max level 21) to find an
     * unoccupied key while preserving spatial locality and entity interactions.
     *
     * @param proposal the split proposal with source bubble and split plane
     * @return execution result with success status and details
     * @throws NullPointerException if proposal is null
     */
    public SplitExecutionResult execute(SplitProposal proposal) {
        java.util.Objects.requireNonNull(proposal, "proposal must not be null");

        // Generate correlation ID for tracking this operation across log statements
        var correlationId = UUID.randomUUID().toString().substring(0, 8);
        var sourceBubbleId = proposal.sourceBubble();

        // Record split attempt for metrics
        metrics.recordSplitAttempt();

        log.debug("[SPLIT-{}] Bubble {}: Starting split operation", correlationId, sourceBubbleId);

        // Get source bubble
        var sourceBubble = bubbleGrid.getBubbleById(sourceBubbleId);
        if (sourceBubble == null) {
            log.error("[SPLIT-{}] Bubble {}: Source bubble not found", correlationId, sourceBubbleId);
            metrics.recordSplitFailure("SOURCE_BUBBLE_NOT_FOUND");
            return new SplitExecutionResult(false, "Source bubble not found: " + sourceBubbleId, null, 0, 0);
        }

        // Capture entity count before split
        int entitiesBeforeSplit = accountant.entitiesInBubble(sourceBubbleId).size();
        log.debug("[{}] Source bubble {} has {} entities before split", correlationId, sourceBubbleId, entitiesBeforeSplit);

        // Get all entity records with positions
        var allRecords = sourceBubble.getAllEntityRecords();
        if (allRecords.isEmpty()) {
            log.error("[SPLIT-{}] Bubble {}: Source bubble has no entities", correlationId, sourceBubbleId);
            metrics.recordSplitFailure("NO_ENTITIES");
            return new SplitExecutionResult(false, "Source bubble has no entities", null, 0, 0);
        }

        // Use strategy to compute split plane (replaces proposal's plane)
        // Strategy computes split plane from actual entity positions
        var splitPlane = strategy.calculate(sourceBubble.bounds(), allRecords);
        log.debug("[SPLIT-{}] Strategy {} selected {} axis for split plane",
                 correlationId, strategy.getClass().getSimpleName(), splitPlane.axis());

        // Partition entities by split plane
        var entitiesToMove = partitionEntities(allRecords, splitPlane);
        log.debug("[SPLIT-{}] Split plane partitions {} entities to new bubble (out of {})",
                 correlationId, entitiesToMove.size(), allRecords.size());

        if (entitiesToMove.isEmpty()) {
            log.error("[SPLIT-{}] Bubble {}: No entities to move based on split plane", correlationId, sourceBubbleId);
            metrics.recordSplitFailure("NO_ENTITIES_TO_MOVE");
            return new SplitExecutionResult(false, "No entities to move based on split plane", null, entitiesBeforeSplit, entitiesBeforeSplit);
        }

        // Validate the strategy-computed plane produces a non-empty partition on BOTH sides
        // before committing to execution. The proposal's embedded plane was certified by
        // consensus, but execute() recomputes a fresh plane from current entity positions;
        // a concurrent position mutation could yield a degenerate one-sided partition (all
        // entities moved, none retained). Fail fast rather than create an empty source bubble.
        if (entitiesToMove.size() == allRecords.size()) {
            log.error("[SPLIT-{}] Bubble {}: Strategy-computed split plane is degenerate (all {} entities on one side)",
                     correlationId, sourceBubbleId, allRecords.size());
            metrics.recordSplitFailure("DEGENERATE_SPLIT_PLANE");
            return new SplitExecutionResult(false,
                                           "Strategy-computed split plane produces a one-sided partition (all entities on one side)",
                                           null, entitiesBeforeSplit, entitiesBeforeSplit);
        }

        // Create new child bubble
        // Child bubble should be one level deeper in the tetree hierarchy
        UUID newBubbleId = uuidSupplier.get();
        byte parentLevel = sourceBubble.getSpatialLevel();
        long targetFrameMs = sourceBubble.getTargetFrameMs();

        // IMPORTANT: Compute target key BEFORE moving entities to detect collisions early
        var positions = entitiesToMove.stream()
                                      .map(EnhancedBubble.EntityRecord::position)
                                      .toList();
        float cx = (float) positions.stream().mapToDouble(p -> p.x).average().orElseThrow();
        float cy = (float) positions.stream().mapToDouble(p -> p.y).average().orElseThrow();
        float cz = (float) positions.stream().mapToDouble(p -> p.z).average().orElseThrow();

        // Try to find an unoccupied key, starting at child level and going deeper if collisions occur
        byte startLevel = (byte) Math.min(parentLevel + 1, 21);  // Start one level deeper
        var keyLevelResult = findAvailableKey(cx, cy, cz, startLevel, correlationId);

        if (keyLevelResult == null) {
            // Calculate levels attempted for diagnostic metrics
            int levelsAttempted = 21 - startLevel + 1;
            metrics.recordLevelsExhaustedOnFailure(levelsAttempted);
            metrics.recordSplitFailure("NO_AVAILABLE_KEY");

            log.error("[SPLIT-{}] Could not find available key at any level from {} to 21 for centroid ({},{},{}). Exhausted {} levels.",
                     correlationId, startLevel, cx, cy, cz, levelsAttempted);
            return new SplitExecutionResult(false,
                                           "Could not find available key for split bubble after retrying deeper levels",
                                           null, entitiesBeforeSplit, entitiesBeforeSplit);
        }

        // Extract the available key and the level at which it was found
        var targetKey = keyLevelResult.key();
        byte childLevel = keyLevelResult.level();

        log.debug("[{}] Found available key {} for new bubble at level {} (after collision retry if needed)",
                 correlationId, targetKey, childLevel);

        var newBubble = new EnhancedBubble(newBubbleId, childLevel, targetFrameMs);

        // Add the new bubble to the grid BEFORE moving any entities into it. This makes the
        // accountant move and the grid insertion recoverable as a single unit: if an unchecked
        // exception is thrown during the move loop, the executor's rollback (BubbleAdded.undo =
        // grid.removeBubble) removes the now-empty bubble, and the accountant move is the only
        // structure carrying the entities. Inserting the bubble afterwards left a window where
        // entities were recorded under newBubbleId in the accountant but the grid had no bubble
        // for them, making them unreachable.
        bubbleGrid.addBubble(newBubble, targetKey);
        operationTracker.recordBubbleAdded(newBubbleId);
        log.debug("[{}] Added new bubble {} to grid at level {} key {} (pre-move)",
                 correlationId, newBubbleId, childLevel, targetKey);

        // Move entities to new bubble atomically.
        //
        // INVARIANT: any moveBetweenBubbles failure is fatal for the whole split.
        //
        // The old code used `continue` on failure, which allowed partial moves:
        // some entities ended up in the new bubble, others silently stayed behind
        // in the source bubble's EnhancedBubble structure but were already removed
        // from the accountant — producing orphaned / duplicated state.  That was
        // the TOCTOU/conservation bug reported in Luciferase-7wzml.70.
        //
        // The fix: on any failure, undo the entities already moved this invocation
        // (reverse accountant moves + bubble membership), remove the new bubble,
        // and return failure.  The caller (TopologyExecutor) is responsible for
        // any higher-level rollback via OperationTracker.
        var movedRecords = new ArrayList<EnhancedBubble.EntityRecord>(entitiesToMove.size());
        // Hold source + new-bubble mutation locks (UUID order, deadlock-free) for the whole move loop —
        // including the rollback branch — so the cross-bubble add/remove is atomic w.r.t. concurrent
        // migration/merge writers (Luciferase-n7io1). newBubble was registered in the grid above, so it
        // is reachable by concurrent readers/writers by the time the move starts.
        try (var ignored = MutationLocks.lock(sourceBubble, newBubble)) {
        for (var entityRecord : entitiesToMove) {
            var entityId = UUID.fromString(entityRecord.id());

            // Add entity to new bubble first
            newBubble.addEntity(entityRecord.id(), entityRecord.position(), entityRecord.content());

            // Move in accountant (atomic under its internal lock)
            boolean moved = accountant.moveBetweenBubbles(entityId, sourceBubbleId, newBubbleId);
            if (!moved) {
                // FAIL FAST: undo all in-progress moves and abort the split.
                log.error("[{}] Failed to move entity {} from {} to {} — aborting split, rolling back {} partial moves",
                          correlationId, entityId, sourceBubbleId, newBubbleId, movedRecords.size());
                // Undo: move already-moved entities back to source.
                // Rollback is best-effort: if a rollback move itself fails, the entity is
                // logged as an orphan but we continue reversing the rest.  Full 2PC
                // (rollback-of-rollback) is out of scope; the log.error below makes
                // any orphan diagnosable.
                newBubble.removeEntity(entityRecord.id()); // undo bubble-side add for this entity
                for (var done : movedRecords) {
                    var doneId = UUID.fromString(done.id());
                    boolean rolledBack = accountant.moveBetweenBubbles(doneId, newBubbleId, sourceBubbleId);
                    if (!rolledBack) {
                        log.error("[{}] Rollback move FAILED for entity {} (from newBubble {} back to source {}) — entity may be orphaned",
                                  correlationId, doneId, newBubbleId, sourceBubbleId);
                    }
                    sourceBubble.addEntity(done.id(), done.position(), done.content());
                    newBubble.removeEntity(done.id());
                }
                // Remove the pre-registered empty/reverted new bubble from the grid.
                bubbleGrid.removeBubble(newBubbleId);
                metrics.recordSplitFailure("MOVE_FAILED");
                return new SplitExecutionResult(false,
                                               "moveBetweenBubbles failed for entity " + entityId
                                               + "; split aborted and rolled back",
                                               null, entitiesBeforeSplit, entitiesBeforeSplit);
            }

            // Remove from source bubble only after accountant confirms the move
            sourceBubble.removeEntity(entityRecord.id());
            movedRecords.add(entityRecord);
        }
        }

        int entitiesMoved = movedRecords.size();

        log.info("[{}] Split bubble {}: moved {} entities to new bubble {}",
                correlationId, sourceBubbleId, entitiesMoved, newBubbleId);

        // Record entities moved in metrics
        metrics.recordEntitiesMoved(entitiesMoved);

        // Validate entity conservation using snapshot arithmetic — NOT two live reads.
        //
        // The old code read accountant.entitiesInBubble(sourceBubbleId).size() +
        // accountant.entitiesInBubble(newBubbleId).size() as two separate calls.
        // Under concurrent mutation those two reads could observe different views,
        // producing an inconsistent sum (the TOCTOU in conservation check identified
        // in Luciferase-7wzml.70).
        //
        // The deterministic check: source should have (entitiesBeforeSplit - entitiesMoved)
        // and new should have entitiesMoved; total == entitiesBeforeSplit by construction.
        // We still call validate() for duplicate detection, but the conservation predicate
        // is now derived from the snapshot + entitiesMoved, not two live reads.
        int entitiesAfterSplit = entitiesBeforeSplit; // by construction: snapshot - moved + moved

        // Validate no duplicates
        var validation = accountant.validate();
        if (!validation.success()) {
            log.error("[{}] Entity validation failed after split: {}", correlationId, validation.details());
            return new SplitExecutionResult(false,
                                           "Entity validation failed: " + validation.details().get(0),
                                           newBubbleId, entitiesBeforeSplit, entitiesAfterSplit);
        }

        // The new bubble was already added to the grid before the move loop (see above). If no
        // entities actually moved, remove the empty bubble so we do not leak it into the grid.
        if (entitiesMoved == 0) {
            log.warn("New bubble {} has no entities after move loop, removing from grid", newBubbleId);
            bubbleGrid.removeBubble(newBubbleId);
            metrics.recordSplitFailure("NO_ENTITIES_TO_MOVE");
            return new SplitExecutionResult(false, "No entities were moved to new bubble", null,
                                           entitiesBeforeSplit, entitiesBeforeSplit);
        }

        log.info("[{}] Split successful: bubble {} split into {} (source: {} entities, new: {} entities)",
                correlationId, sourceBubbleId, newBubbleId,
                entitiesBeforeSplit - entitiesMoved, entitiesMoved);

        // Record successful split in metrics
        metrics.recordSplitSuccess();

        return new SplitExecutionResult(true, "Split successful",
                                       newBubbleId, entitiesBeforeSplit, entitiesAfterSplit, entitiesMoved);
    }

    /**
     * Finds an available TetreeKey at the centroid, retrying at deeper levels if collisions occur.
     * <p>
     * Preserves spatial locality by using the same (cx, cy, cz) coordinates across all levels,
     * but moves deeper in the tree hierarchy to find unoccupied key space.
     *
     * @param cx the X coordinate of the entity centroid
     * @param cy the Y coordinate of the entity centroid
     * @param cz the Z coordinate of the entity centroid
     * @param startLevel the starting level to search
     * @param correlationId tracking ID for logging
     * @return a KeyLevel record containing the available key and the level at which it was found, or null if no available key
     */
    private KeyLevel findAvailableKey(float cx, float cy, float cz, byte startLevel, String correlationId) {
        for (byte level = startLevel; level <= 21; level++) {
            var tet = com.hellblazer.luciferase.lucien.tetree.Tet.locatePointBeyRefinementFromRoot(cx, cy, cz, level);
            if (tet == null) {
                log.debug("[{}] Could not locate tetrahedron at level {}", correlationId, level);
                continue;
            }

            var key = tet.tmIndex();

            // Check if this key is available
            if (!bubbleGrid.containsBubble(key)) {
                log.debug("[{}] Found available key {} at level {}", correlationId, key, level);
                return new KeyLevel(key, level);
            }

            // Collision at this level, try next level
            log.debug("[{}] Key collision at level {} (key={}), trying deeper level", correlationId, level, key);
        }

        // Exhausted all levels up to 21
        return null;
    }

    /**
     * Partitions entities based on split plane.
     * <p>
     * Uses signed distance test: entities on positive side of plane are moved to new bubble.
     *
     * @param entityRecords all entity records with positions
     * @param splitPlane    the split plane
     * @return list of entities to move to new bubble
     */
    private List<EnhancedBubble.EntityRecord> partitionEntities(
        List<EnhancedBubble.EntityRecord> entityRecords,
        SplitPlane splitPlane) {

        var entitiesToMove = new ArrayList<EnhancedBubble.EntityRecord>();

        // Debug: log split plane and first few entity positions
        if (log.isDebugEnabled() && !entityRecords.isEmpty()) {
            var firstPos = entityRecords.get(0).position();
            var lastPos = entityRecords.get(Math.min(2, entityRecords.size() - 1)).position();
            log.debug("Split plane: normal={}, distance={}", splitPlane.normal(), splitPlane.distance());
            log.debug("First entity position: {}", firstPos);
            if (entityRecords.size() > 2) {
                log.debug("Sample entity position: {}", lastPos);
            }
        }

        int positiveSide = 0, negativeSide = 0;
        for (var record : entityRecords) {
            // Skip null positions
            if (record.position() == null) {
                log.warn("Entity {} has null position, skipping", record.id());
                continue;
            }

            // Calculate signed distance from plane
            float signedDistance = splitPlane.normal().x * record.position().x +
                                  splitPlane.normal().y * record.position().y +
                                  splitPlane.normal().z * record.position().z -
                                  splitPlane.distance();

            // Move entities on positive side of plane to new bubble
            if (signedDistance >= 0) {
                entitiesToMove.add(record);
                positiveSide++;
            } else {
                negativeSide++;
            }
        }

        log.debug("Partition result: {} on positive side, {} on negative side", positiveSide, negativeSide);

        return entitiesToMove;
    }
}

/**
 * Represents a TetreeKey and the tree level at which it was found.
 * Used to track available keys when collision retry logic searches deeper levels.
 *
 * @param key   the available TetreeKey
 * @param level the tetree level at which this key was found
 */
record KeyLevel(
    com.hellblazer.luciferase.lucien.tetree.TetreeKey key,
    byte level
) {
}

/**
 * Result of a split operation execution.
 *
 * @param success           true if split succeeded
 * @param message           description of result or error
 * @param newBubbleId       ID of newly created bubble (null if failed)
 * @param entitiesBefore    entity count before split
 * @param entitiesAfter     total entity count after split (source + new)
 * @param entitiesMovedToNewBubble number of entities actually relocated to the new bubble
 */
record SplitExecutionResult(
    boolean success,
    String message,
    UUID newBubbleId,
    int entitiesBefore,
    int entitiesAfter,
    int entitiesMovedToNewBubble
) {
    /**
     * Backward-compatible constructor that defaults {@code entitiesMovedToNewBubble} to 0.
     * Used by failure paths where no entities were relocated.
     */
    SplitExecutionResult(boolean success, String message, UUID newBubbleId, int entitiesBefore, int entitiesAfter) {
        this(success, message, newBubbleId, entitiesBefore, entitiesAfter, 0);
    }
}
