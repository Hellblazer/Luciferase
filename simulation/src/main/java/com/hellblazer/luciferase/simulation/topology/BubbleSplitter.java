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

        // Move entities to new bubble atomically
        int entitiesMoved = 0;
        for (var entityRecord : entitiesToMove) {
            var entityId = UUID.fromString(entityRecord.id());

            // Add entity to new bubble first
            newBubble.addEntity(entityRecord.id(), entityRecord.position(), entityRecord.content());

            // Move in accountant (atomic)
            boolean moved = accountant.moveBetweenBubbles(entityId, sourceBubbleId, newBubbleId);
            if (!moved) {
                log.error("Failed to move entity {} from {} to {}", entityId, sourceBubbleId, newBubbleId);
                // Rollback: remove entity from new bubble
                newBubble.removeEntity(entityRecord.id());
                continue;
            }

            // Remove from source bubble
            sourceBubble.removeEntity(entityRecord.id());
            entitiesMoved++;
        }

        log.info("[{}] Split bubble {}: moved {} entities to new bubble {}",
                correlationId, sourceBubbleId, entitiesMoved, newBubbleId);

        // Record entities moved in metrics
        metrics.recordEntitiesMoved(entitiesMoved);

        // Validate entity conservation
        int entitiesAfterSplit = accountant.entitiesInBubble(sourceBubbleId).size() +
                                 accountant.entitiesInBubble(newBubbleId).size();

        if (entitiesAfterSplit != entitiesBeforeSplit) {
            log.error("[{}] Entity conservation violated: before={}, after={}", correlationId, entitiesBeforeSplit, entitiesAfterSplit);
            return new SplitExecutionResult(false,
                                           "Entity count mismatch: before=" + entitiesBeforeSplit
                                           + ", after=" + entitiesAfterSplit,
                                           newBubbleId, entitiesBeforeSplit, entitiesAfterSplit);
        }

        // Validate no duplicates
        var validation = accountant.validate();
        if (!validation.success()) {
            log.error("[{}] Entity validation failed after split: {}", correlationId, validation.details());
            return new SplitExecutionResult(false,
                                           "Entity validation failed: " + validation.details().get(0),
                                           newBubbleId, entitiesBeforeSplit, entitiesAfterSplit);
        }

        // Add new bubble to grid using pre-computed targetKey (collision already checked)
        if (entitiesMoved > 0) {
            bubbleGrid.addBubble(newBubble, targetKey);
            operationTracker.recordBubbleAdded(newBubbleId);
            log.debug("[{}] Added new bubble {} to grid at level {} key {}",
                     correlationId, newBubbleId, childLevel, targetKey);
        } else {
            log.warn("New bubble {} has no entities, not adding to grid", newBubbleId);
        }

        log.info("[{}] Split successful: bubble {} split into {} (source: {} entities, new: {} entities)",
                correlationId, sourceBubbleId, newBubbleId,
                accountant.entitiesInBubble(sourceBubbleId).size(),
                accountant.entitiesInBubble(newBubbleId).size());

        // Record successful split in metrics
        metrics.recordSplitSuccess();

        return new SplitExecutionResult(true, "Split successful",
                                       newBubbleId, entitiesBeforeSplit, entitiesAfterSplit);
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
 */
record SplitExecutionResult(
    boolean success,
    String message,
    UUID newBubbleId,
    int entitiesBefore,
    int entitiesAfter
) {
}
