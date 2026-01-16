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

import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Executes bubble merge operations with duplicate detection.
 * <p>
 * Merges two underpopulated bubbles (<500 entities each) by:
 * <ol>
 *   <li>Moving all entities from bubble2 to bubble1</li>
 *   <li>Atomically transferring via EntityAccountant</li>
 *   <li>Detecting and preventing duplicate entities</li>
 *   <li>Validating 100% entity retention</li>
 *   <li>Removing bubble2 from grid</li>
 * </ol>
 * <p>
 * <b>Safety Guarantees</b>:
 * <ul>
 *   <li>Duplicate detection: Entities already in bubble1 are not moved</li>
 *   <li>Atomic transfer: EntityAccountant ensures no entity in multiple bubbles</li>
 *   <li>100% retention: Pre/post entity counts must match</li>
 * </ul>
 * <p>
 * <b>Algorithm</b>:
 * <ol>
 *   <li>Get entities from both bubbles</li>
 *   <li>Identify entities unique to bubble2 (no duplicates)</li>
 *   <li>For each entity in bubble2:
 *     <ul>
 *       <li>Add entity to bubble1</li>
 *       <li>EntityAccountant.moveBetweenBubbles() (atomic)</li>
 *       <li>Remove from bubble2</li>
 *     </ul>
 *   </li>
 *   <li>Validate conservation: totalBefore == totalAfter</li>
 *   <li>Remove bubble2 from grid</li>
 * </ol>
 * <p>
 * Phase 9C: Topology Reorganization & Execution
 *
 * @author hal.hildebrand
 */
public class BubbleMerger {

    private static final Logger log = LoggerFactory.getLogger(BubbleMerger.class);

    private final TetreeBubbleGrid bubbleGrid;
    private final EntityAccountant accountant;
    private final OperationTracker operationTracker;

    /**
     * Creates a bubble merger.
     *
     * @param bubbleGrid        the bubble grid
     * @param accountant        the entity accountant for atomic transfers
     * @param operationTracker  the operation tracker for rollback support
     * @throws NullPointerException if any parameter is null
     */
    public BubbleMerger(TetreeBubbleGrid bubbleGrid, EntityAccountant accountant, OperationTracker operationTracker) {
        this.bubbleGrid = java.util.Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null");
        this.accountant = java.util.Objects.requireNonNull(accountant, "accountant must not be null");
        this.operationTracker = java.util.Objects.requireNonNull(operationTracker, "operationTracker must not be null");
    }

    /**
     * Executes a merge operation on two bubbles.
     * <p>
     * Moves all entities from bubble2 to bubble1, then removes bubble2.
     *
     * @param proposal the merge proposal with two bubble IDs
     * @return execution result with success status and details
     * @throws NullPointerException if proposal is null
     */
    public MergeExecutionResult execute(MergeProposal proposal) {
        java.util.Objects.requireNonNull(proposal, "proposal must not be null");

        // Generate correlation ID for tracking this operation across log statements
        var correlationId = UUID.randomUUID().toString().substring(0, 8);
        var bubble1Id = proposal.bubble1();
        var bubble2Id = proposal.bubble2();

        log.debug("[{}] Executing merge: {} + {}", correlationId, bubble1Id, bubble2Id);

        // Get both bubbles
        var bubble1 = bubbleGrid.getBubbleById(bubble1Id);
        var bubble2 = bubbleGrid.getBubbleById(bubble2Id);

        if (bubble1 == null) {
            return new MergeExecutionResult(false, "Bubble1 not found: " + bubble1Id, 0, 0, 0);
        }
        if (bubble2 == null) {
            return new MergeExecutionResult(false, "Bubble2 not found: " + bubble2Id, 0, 0, 0);
        }

        // Capture entity counts before merge
        int entities1Before = accountant.entitiesInBubble(bubble1Id).size();
        int entities2Before = accountant.entitiesInBubble(bubble2Id).size();
        int totalBefore = entities1Before + entities2Before;

        log.debug("[{}] Merging bubbles: {} has {} entities, {} has {} entities (total: {})",
                 correlationId, bubble1Id, entities1Before, bubble2Id, entities2Before, totalBefore);

        // Get entities from bubble2
        var entities2 = new HashSet<>(accountant.entitiesInBubble(bubble2Id));
        var entities1 = accountant.entitiesInBubble(bubble1Id);

        // Detect duplicates
        var duplicates = new HashSet<>(entities2);
        duplicates.retainAll(entities1);
        if (!duplicates.isEmpty()) {
            log.warn("[{}] Found {} duplicate entities between bubbles {} and {}: {}",
                    correlationId, duplicates.size(), bubble1Id, bubble2Id, duplicates);
        }

        // Get entity records from bubble2 for positions/content
        var records2 = bubble2.getAllEntityRecords();

        // Move all entities from bubble2 to bubble1
        int entitiesMoved = 0;
        for (var record : records2) {
            var entityId = UUID.fromString(record.id());

            // Skip if entity already in bubble1 (duplicate)
            if (entities1.contains(entityId)) {
                log.debug("Skipping duplicate entity {} already in bubble1", entityId);
                continue;
            }

            // Add entity to bubble1 first
            bubble1.addEntity(record.id(), record.position(), record.content());

            // Move in accountant (atomic)
            boolean moved = accountant.moveBetweenBubbles(entityId, bubble2Id, bubble1Id);
            if (!moved) {
                log.error("Failed to move entity {} from {} to {}", entityId, bubble2Id, bubble1Id);
                // Rollback: remove entity from bubble1
                bubble1.removeEntity(record.id());
                continue;
            }

            // Remove from bubble2
            bubble2.removeEntity(record.id());
            entitiesMoved++;
        }

        log.info("[{}] Merge: moved {} entities from bubble {} to bubble {}",
                correlationId, entitiesMoved, bubble2Id, bubble1Id);

        // Validate entity conservation
        int entities1After = accountant.entitiesInBubble(bubble1Id).size();
        int entities2After = accountant.entitiesInBubble(bubble2Id).size();
        int totalAfter = entities1After + entities2After;

        if (totalAfter != totalBefore) {
            log.error("[{}] Entity conservation violated: before={}, after={}", correlationId, totalBefore, totalAfter);
            return new MergeExecutionResult(false,
                                           "Entity count mismatch: before=" + totalBefore + ", after=" + totalAfter,
                                           totalBefore, totalAfter, duplicates.size());
        }

        // Validate bubble2 is now empty
        if (entities2After != 0) {
            log.error("[{}] Bubble2 still has {} entities after merge", correlationId, entities2After);
            return new MergeExecutionResult(false,
                                           "Bubble2 still has entities: " + entities2After,
                                           totalBefore, totalAfter, duplicates.size());
        }

        // Validate no duplicates
        var validation = accountant.validate();
        if (!validation.success()) {
            log.error("[{}] Entity validation failed after merge: {}", correlationId, validation.details());
            return new MergeExecutionResult(false,
                                           "Entity validation failed: " + validation.details().get(0),
                                           totalBefore, totalAfter, duplicates.size());
        }

        // Record bubble removal for rollback BEFORE removing
        // Capture bubble state and compute key from bounds
        var bubble2Bounds = bubble2.bounds();
        if (bubble2Bounds != null) {
            var bubble2Key = bubble2Bounds.rootKey();
            operationTracker.recordBubbleRemoved(bubble2Id, bubble2, bubble2Key);
        }

        // Remove bubble2 from grid (all entities now in bubble1)
        boolean removed = bubbleGrid.removeBubble(bubble2Id);
        if (removed) {
            log.debug("Removed merged bubble {} from grid", bubble2Id);
        } else {
            log.warn("Bubble {} not found in grid during removal", bubble2Id);
        }

        log.info("[{}] Merge successful: bubble {} merged into {} ({} entities total)",
                correlationId, bubble2Id, bubble1Id, entities1After);

        return new MergeExecutionResult(true, "Merge successful",
                                       totalBefore, totalAfter, duplicates.size());
    }
}

/**
 * Result of a merge operation execution.
 *
 * @param success          true if merge succeeded
 * @param message          description of result or error
 * @param entitiesBefore   total entity count before merge (bubble1 + bubble2)
 * @param entitiesAfter    entity count after merge (should equal entitiesBefore)
 * @param duplicatesFound  number of duplicate entities detected
 */
record MergeExecutionResult(
    boolean success,
    String message,
    int entitiesBefore,
    int entitiesAfter,
    int duplicatesFound
) {
}
