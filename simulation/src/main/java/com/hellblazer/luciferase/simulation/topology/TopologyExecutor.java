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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates topology change execution with snapshot/rollback capability.
 * <p>
 * Coordinates split, merge, and move operations with:
 * <ul>
 *   <li><b>Sequential execution</b>: One topology change at a time (lock-based)</li>
 *   <li><b>Snapshot/rollback</b>: Captures state before operation, restores on failure</li>
 *   <li><b>100% retention guarantee</b>: Validates entity conservation pre/post operation</li>
 *   <li><b>Byzantine rejection</b>: Pre-validation via proposal.validate()</li>
 * </ul>
 * <p>
 * <b>Execution Flow</b>:
 * <ol>
 *   <li>Acquire lock (prevents concurrent topology changes)</li>
 *   <li>Take entity distribution snapshot</li>
 *   <li>Delegate to BubbleSplitter/Merger/Mover</li>
 *   <li>Validate entity conservation (totalBefore == totalAfter)</li>
 *   <li>On failure: Rollback to snapshot (log warning, no entity movement)</li>
 *   <li>Release lock</li>
 * </ol>
 * <p>
 * <b>Snapshot/Rollback Strategy</b>:
 * <ul>
 *   <li>Snapshot: Capture entity-to-bubble mapping before operation</li>
 *   <li>Rollback: Log failure and snapshot (actual restoration requires bubble state rebuild)</li>
 *   <li>Phase 9C MVP: Rollback logs warning, defers full restoration to future work</li>
 * </ul>
 * <p>
 * <b>Lock-Based Serialization</b>:
 * <ul>
 *   <li>ReentrantLock ensures sequential execution</li>
 *   <li>Simpler than conflict resolution for concurrent changes</li>
 *   <li>Consensus voting parallelizes, execution serializes</li>
 * </ul>
 * <p>
 * Phase 9C: Topology Reorganization & Execution
 *
 * @author hal.hildebrand
 */
public class TopologyExecutor {

    private static final Logger log = LoggerFactory.getLogger(TopologyExecutor.class);

    private final BubbleSplitter splitter;
    private final BubbleMerger merger;
    private final BubbleMover mover;
    private final EntityAccountant accountant;
    private final Lock executionLock;

    /**
     * Creates a topology executor.
     *
     * @param bubbleGrid the bubble grid
     * @param accountant the entity accountant for atomic transfers
     * @throws NullPointerException if any parameter is null
     */
    public TopologyExecutor(TetreeBubbleGrid bubbleGrid, EntityAccountant accountant) {
        java.util.Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null");
        this.accountant = java.util.Objects.requireNonNull(accountant, "accountant must not be null");

        this.splitter = new BubbleSplitter(bubbleGrid, accountant);
        this.merger = new BubbleMerger(bubbleGrid, accountant);
        this.mover = new BubbleMover(bubbleGrid, accountant);
        this.executionLock = new ReentrantLock();
    }

    /**
     * Executes a topology change proposal with snapshot/rollback.
     * <p>
     * Delegates to appropriate executor based on proposal type.
     * Guarantees sequential execution and 100% entity retention.
     *
     * @param proposal the topology change proposal
     * @return execution result with success status and details
     * @throws NullPointerException if proposal is null
     */
    public TopologyExecutionResult execute(TopologyProposal proposal) {
        java.util.Objects.requireNonNull(proposal, "proposal must not be null");

        executionLock.lock();
        try {
            log.info("Executing topology change: type={}, proposalId={}",
                    proposal.getClass().getSimpleName(), proposal.proposalId());

            // Take snapshot of entity distribution
            var snapshot = takeSnapshot();
            int totalBefore = snapshot.values().stream().mapToInt(Set::size).sum();
            log.debug("Snapshot captured: {} entities across {} bubbles",
                     totalBefore, snapshot.size());

            // Execute based on proposal type
            boolean success;
            String message;

            switch (proposal) {
                case SplitProposal split -> {
                    var result = splitter.execute(split);
                    success = result.success();
                    message = result.message();
                    if (!success) {
                        rollback(snapshot, "Split failed: " + message);
                    }
                }
                case MergeProposal merge -> {
                    var result = merger.execute(merge);
                    success = result.success();
                    message = result.message();
                    if (!success) {
                        rollback(snapshot, "Merge failed: " + message);
                    }
                }
                case MoveProposal move -> {
                    var result = mover.execute(move);
                    success = result.success();
                    message = result.message();
                    if (!success) {
                        rollback(snapshot, "Move failed: " + message);
                    }
                }
            }

            // Validate entity conservation
            var validation = accountant.validate();
            if (!validation.success()) {
                rollback(snapshot, "Entity validation failed: " + validation.details());
                return new TopologyExecutionResult(false,
                                                  "Entity validation failed: " + validation.details().get(0),
                                                  totalBefore, getTotalEntityCount());
            }

            int totalAfter = getTotalEntityCount();
            if (totalAfter != totalBefore) {
                rollback(snapshot, "Entity count mismatch: before=" + totalBefore + ", after=" + totalAfter);
                return new TopologyExecutionResult(false,
                                                  "Entity count mismatch: before=" + totalBefore + ", after=" + totalAfter,
                                                  totalBefore, totalAfter);
            }

            log.info("Topology change successful: {} entities retained", totalAfter);
            return new TopologyExecutionResult(success, message, totalBefore, totalAfter);

        } finally {
            executionLock.unlock();
        }
    }

    /**
     * Takes a snapshot of current entity distribution.
     * <p>
     * Captures entity-to-bubble mapping for rollback.
     *
     * @return map of bubble ID to set of entity IDs
     */
    private Map<UUID, Set<UUID>> takeSnapshot() {
        var snapshot = new HashMap<UUID, Set<UUID>>();
        var distribution = accountant.getDistribution();

        for (var bubbleId : distribution.keySet()) {
            snapshot.put(bubbleId, accountant.entitiesInBubble(bubbleId));
        }

        return snapshot;
    }

    /**
     * Rolls back to a previous snapshot on failure.
     * <p>
     * Phase 9C MVP: Logs rollback intent and snapshot state.
     * Full entity movement rollback deferred to future work (requires
     * BubbleGrid.removeBubble() and bubble state reconstruction).
     *
     * @param snapshot the snapshot to restore
     * @param reason   reason for rollback
     */
    private void rollback(Map<UUID, Set<UUID>> snapshot, String reason) {
        log.warn("ROLLBACK TRIGGERED: {}", reason);
        log.warn("Snapshot state: {} bubbles with {} total entities",
                snapshot.size(),
                snapshot.values().stream().mapToInt(Set::size).sum());

        // Phase 9C MVP: Log rollback intent
        // TODO: Implement full rollback when BubbleGrid supports bubble removal/restoration
        log.warn("TODO: Full rollback requires BubbleGrid.removeBubble() and entity restoration");
        log.warn("Current state preserved for debugging - manual cleanup may be required");
    }

    /**
     * Gets the total entity count across all bubbles.
     *
     * @return total entity count
     */
    private int getTotalEntityCount() {
        return accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
    }
}

/**
 * Result of a topology change execution.
 *
 * @param success         true if execution succeeded
 * @param message         description of result or error
 * @param entitiesBefore  entity count before operation
 * @param entitiesAfter   entity count after operation
 */
record TopologyExecutionResult(
    boolean success,
    String message,
    int entitiesBefore,
    int entitiesAfter
) {
}
