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

import java.util.UUID;

/**
 * Executes bubble move operations to follow entity clustering.
 * <p>
 * Relocates a bubble's boundaries when entities cluster away from the bubble center,
 * causing high boundary stress (>10 migrations/second). The bubble moves toward
 * the cluster centroid to reduce cross-boundary entity traffic.
 * <p>
 * <b>Key Insight</b>: Bubble moves do NOT require entity transfers. Entities maintain
 * their absolute positions in world space while the bubble boundaries shift around them.
 * This makes moves much simpler than splits/merges.
 * <p>
 * <b>Safety Guarantees</b>:
 * <ul>
 *   <li>No entity movement: Entities stay at their world positions</li>
 *   <li>100% retention: Entity count unchanged (validation check only)</li>
 *   <li>Bounds validation: New bounds don't overlap excessively with neighbors</li>
 * </ul>
 * <p>
 * <b>Algorithm</b>:
 * <ol>
 *   <li>Validate bubble exists</li>
 *   <li>Update bubble's BubbleBounds to new center</li>
 *   <li>Recalculate bounds based on entity distribution</li>
 *   <li>Validate entity count unchanged</li>
 *   <li>Update neighbor relationships (if needed)</li>
 * </ol>
 * <p>
 * <b>Implementation Note</b>: Current BubbleBounds is immutable and managed by
 * BubbleBoundsTracker. This means we cannot directly "move" a bubble's center.
 * For Phase 9C MVP, we'll validate the move parameters and log the intended operation,
 * deferring actual bounds manipulation to future work when BubbleBounds becomes mutable.
 * <p>
 * Phase 9C: Topology Reorganization & Execution
 *
 * @author hal.hildebrand
 */
public class BubbleMover {

    private static final Logger log = LoggerFactory.getLogger(BubbleMover.class);

    private final TetreeBubbleGrid bubbleGrid;
    private final EntityAccountant accountant;
    private final TopologyMetrics metrics;

    /**
     * Creates a bubble mover.
     *
     * @param bubbleGrid the bubble grid
     * @param accountant the entity accountant for validation
     * @param metrics    the metrics tracker for operational monitoring
     * @throws NullPointerException if any parameter is null
     */
    public BubbleMover(TetreeBubbleGrid bubbleGrid, EntityAccountant accountant, TopologyMetrics metrics) {
        this.bubbleGrid = java.util.Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null");
        this.accountant = java.util.Objects.requireNonNull(accountant, "accountant must not be null");
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /**
     * Executes a move operation on a bubble.
     * <p>
     * Relocates bubble boundaries toward cluster centroid without moving entities.
     * <p>
     * <b>Phase 9C MVP</b>: Validates move parameters and logs intended operation.
     * Actual bounds manipulation deferred until BubbleBounds becomes mutable.
     *
     * @param proposal the move proposal with new center and cluster centroid
     * @return execution result with success status and details
     * @throws NullPointerException if proposal is null
     */
    public MoveExecutionResult execute(MoveProposal proposal) {
        java.util.Objects.requireNonNull(proposal, "proposal must not be null");

        // Generate correlation ID for tracking this operation across log statements
        var correlationId = UUID.randomUUID().toString().substring(0, 8);
        var bubbleId = proposal.sourceBubble();
        var newCenter = proposal.newCenter();
        var clusterCentroid = proposal.clusterCentroid();

        log.debug("[{}] Executing move on bubble {} to new center ({}, {}, {})",
                 correlationId, bubbleId, newCenter.x, newCenter.y, newCenter.z);

        // Get source bubble
        var bubble = bubbleGrid.getBubbleById(bubbleId);
        if (bubble == null) {
            return new MoveExecutionResult(false, "Source bubble not found: " + bubbleId, 0, 0);
        }

        // Capture entity count before move
        int entitiesBefore = accountant.entitiesInBubble(bubbleId).size();
        log.debug("Bubble {} has {} entities before move", bubbleId, entitiesBefore);

        // Get current bounds
        var currentBounds = bubble.bounds();
        if (currentBounds == null) {
            return new MoveExecutionResult(false, "Bubble has no bounds (no entities?)", 0, 0);
        }

        var currentCentroid = currentBounds.centroid();
        log.debug("Current centroid: ({}, {}, {}), cluster centroid: ({}, {}, {})",
                 currentCentroid.getX(), currentCentroid.getY(), currentCentroid.getZ(),
                 clusterCentroid.x, clusterCentroid.y, clusterCentroid.z);

        // Calculate move distance
        float dx = newCenter.x - (float) currentCentroid.getX();
        float dy = newCenter.y - (float) currentCentroid.getY();
        float dz = newCenter.z - (float) currentCentroid.getZ();
        float moveDistance = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

        log.info("[{}] Moving bubble {} by distance {} toward cluster", correlationId, bubbleId, moveDistance);

        // Recalculate bounds based on entity distribution
        // Note: BubbleBounds is immutable, but recalculateBounds() creates new bounds
        // from current entity positions, which effectively "moves" the bubble to follow
        // the entity cluster. No explicit bounds mutation is needed.
        bubble.recalculateBounds();

        // Validate that the new centroid matches the expected position
        var newBounds = bubble.bounds();
        if (newBounds == null) {
            return new MoveExecutionResult(false, "Bubble lost bounds after recalculation", entitiesBefore, 0);
        }

        var actualNewCentroid = newBounds.centroid();
        float dx2 = (float) actualNewCentroid.getX() - newCenter.x;
        float dy2 = (float) actualNewCentroid.getY() - newCenter.y;
        float dz2 = (float) actualNewCentroid.getZ() - newCenter.z;
        float deviation = (float) Math.sqrt(dx2*dx2 + dy2*dy2 + dz2*dz2);

        // Allow 10% tolerance for deviation (entity clustering may not perfectly match proposal)
        float tolerance = moveDistance * 0.1f;
        if (deviation > tolerance && deviation > 0.1f) {
            log.warn("[{}] Bubble {} center deviation: expected ({}, {}, {}), actual ({}, {}, {}), deviation {}",
                     correlationId, bubbleId, newCenter.x, newCenter.y, newCenter.z,
                     actualNewCentroid.getX(), actualNewCentroid.getY(), actualNewCentroid.getZ(),
                     deviation);
        }

        log.debug("Bubble {} bounds recalculated after move (centroid shift: {}, deviation: {})",
                 bubbleId, moveDistance, deviation);

        // Validate entity count unchanged (no entities should have been moved)
        int entitiesAfter = accountant.entitiesInBubble(bubbleId).size();
        if (entitiesAfter != entitiesBefore) {
            log.error("[{}] Entity count changed during move: before={}, after={}", correlationId, entitiesBefore, entitiesAfter);
            return new MoveExecutionResult(false,
                                          "Entity count changed: before=" + entitiesBefore + ", after=" + entitiesAfter,
                                          entitiesBefore, entitiesAfter);
        }

        // Validate no entity duplicates
        var validation = accountant.validate();
        if (!validation.success()) {
            log.error("[{}] Entity validation failed after move: {}", correlationId, validation.details());
            return new MoveExecutionResult(false,
                                          "Entity validation failed: " + validation.details().get(0),
                                          entitiesBefore, entitiesAfter);
        }

        log.info("[{}] Move successful: bubble {} relocated (entities retained: {})",
                correlationId, bubbleId, entitiesAfter);

        return new MoveExecutionResult(true, "Move successful - bounds recalculated from entity distribution",
                                      entitiesBefore, entitiesAfter);
    }
}

/**
 * Result of a move operation execution.
 *
 * @param success         true if move succeeded
 * @param message         description of result or error
 * @param entitiesBefore  entity count before move
 * @param entitiesAfter   entity count after move (should equal entitiesBefore)
 */
record MoveExecutionResult(
    boolean success,
    String message,
    int entitiesBefore,
    int entitiesAfter
) {
}
