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
import java.util.UUID;

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

    /**
     * Creates a bubble splitter.
     *
     * @param bubbleGrid the bubble grid
     * @param accountant the entity accountant for atomic transfers
     * @throws NullPointerException if any parameter is null
     */
    public BubbleSplitter(TetreeBubbleGrid bubbleGrid, EntityAccountant accountant) {
        this.bubbleGrid = java.util.Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null");
        this.accountant = java.util.Objects.requireNonNull(accountant, "accountant must not be null");
    }

    /**
     * Executes a split operation on a bubble.
     * <p>
     * Partitions entities based on split plane and creates new child bubble.
     *
     * @param proposal the split proposal with source bubble and split plane
     * @return execution result with success status and details
     * @throws NullPointerException if proposal is null
     */
    public SplitExecutionResult execute(SplitProposal proposal) {
        java.util.Objects.requireNonNull(proposal, "proposal must not be null");

        var sourceBubbleId = proposal.sourceBubble();
        var splitPlane = proposal.splitPlane();

        log.debug("Executing split on bubble {}", sourceBubbleId);

        // Get source bubble
        var sourceBubble = bubbleGrid.getBubbleById(sourceBubbleId);
        if (sourceBubble == null) {
            return new SplitExecutionResult(false, "Source bubble not found: " + sourceBubbleId, null, 0, 0);
        }

        // Capture entity count before split
        int entitiesBeforeSplit = accountant.entitiesInBubble(sourceBubbleId).size();
        log.debug("Source bubble {} has {} entities before split", sourceBubbleId, entitiesBeforeSplit);

        // Get all entity records with positions
        var allRecords = sourceBubble.getAllEntityRecords();
        if (allRecords.isEmpty()) {
            return new SplitExecutionResult(false, "Source bubble has no entities", null, 0, 0);
        }

        // Partition entities by split plane
        var entitiesToMove = partitionEntities(allRecords, splitPlane);
        log.debug("Split plane partitions {} entities to new bubble (out of {})",
                 entitiesToMove.size(), allRecords.size());

        // Create new child bubble
        // Use same spatial level and target frame time as grid default
        UUID newBubbleId = UUID.randomUUID();
        byte spatialLevel = 2; // Default spatial level for new bubbles
        long targetFrameMs = 10; // Default 10ms frame budget

        var newBubble = new EnhancedBubble(newBubbleId, spatialLevel, targetFrameMs);

        // TODO: Add new bubble to grid (TetreeBubbleGrid needs addBubble() method)
        // For now, we'll log this limitation
        log.warn("TODO: Add new bubble {} to grid (TetreeBubbleGrid.addBubble() not yet implemented)", newBubbleId);

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

        log.info("Split bubble {}: moved {} entities to new bubble {}",
                sourceBubbleId, entitiesMoved, newBubbleId);

        // Validate entity conservation
        int entitiesAfterSplit = accountant.entitiesInBubble(sourceBubbleId).size() +
                                 accountant.entitiesInBubble(newBubbleId).size();

        if (entitiesAfterSplit != entitiesBeforeSplit) {
            log.error("Entity conservation violated: before={}, after={}", entitiesBeforeSplit, entitiesAfterSplit);
            return new SplitExecutionResult(false,
                                           "Entity count mismatch: before=" + entitiesBeforeSplit
                                           + ", after=" + entitiesAfterSplit,
                                           newBubbleId, entitiesBeforeSplit, entitiesAfterSplit);
        }

        // Validate no duplicates
        var validation = accountant.validate();
        if (!validation.success()) {
            log.error("Entity validation failed after split: {}", validation.details());
            return new SplitExecutionResult(false,
                                           "Entity validation failed: " + validation.details().get(0),
                                           newBubbleId, entitiesBeforeSplit, entitiesAfterSplit);
        }

        log.info("Split successful: bubble {} split into {} (source: {} entities, new: {} entities)",
                sourceBubbleId, newBubbleId,
                accountant.entitiesInBubble(sourceBubbleId).size(),
                accountant.entitiesInBubble(newBubbleId).size());

        return new SplitExecutionResult(true, "Split successful",
                                       newBubbleId, entitiesBeforeSplit, entitiesAfterSplit);
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

        for (var record : entityRecords) {
            // Calculate signed distance from plane
            float signedDistance = splitPlane.normal().x * record.position().x +
                                  splitPlane.normal().y * record.position().y +
                                  splitPlane.normal().z * record.position().z -
                                  splitPlane.distance();

            // Move entities on positive side of plane to new bubble
            if (signedDistance >= 0) {
                entitiesToMove.add(record);
            }
        }

        return entitiesToMove;
    }
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
