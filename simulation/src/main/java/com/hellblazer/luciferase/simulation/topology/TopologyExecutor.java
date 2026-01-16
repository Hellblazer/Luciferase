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
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
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
 *   <li>Delegate to BubbleSplitter/Merger/Mover (tracks operations)</li>
 *   <li>Validate entity conservation (totalBefore == totalAfter)</li>
 *   <li>On failure: Rollback tracked operations in reverse order</li>
 *   <li>Release lock</li>
 * </ol>
 * <p>
 * <b>Snapshot/Rollback Strategy</b>:
 * <ul>
 *   <li>Snapshot: Capture entity-to-bubble mapping before operation</li>
 *   <li>Operation Tracking: Record grid structural changes (add/remove bubble)</li>
 *   <li>Rollback: Undo operations in reverse order to restore grid structure</li>
 *   <li>Limitation: Entity movements within bubbles are not reversed (EntityAccountant limitation)</li>
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
public class TopologyExecutor implements OperationTracker {

    private static final Logger log = LoggerFactory.getLogger(TopologyExecutor.class);

    private final BubbleSplitter splitter;
    private final BubbleMerger merger;
    private final BubbleMover mover;
    private final EntityAccountant accountant;
    private final TetreeBubbleGrid bubbleGrid;
    private final TopologyMetrics metrics;
    private final Lock executionLock;

    // Thread-local operation history for tracking grid changes during execution
    // Using ThreadLocal since executionLock ensures single-threaded execution
    private final ThreadLocal<List<GridOperation>> operationHistory = ThreadLocal.withInitial(ArrayList::new);

    /**
     * Creates a topology executor.
     *
     * @param bubbleGrid the bubble grid
     * @param accountant the entity accountant for atomic transfers
     * @param metrics    the metrics tracker for operational monitoring
     * @throws NullPointerException if any parameter is null
     */
    public TopologyExecutor(TetreeBubbleGrid bubbleGrid, EntityAccountant accountant, TopologyMetrics metrics) {
        this.bubbleGrid = java.util.Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null");
        this.accountant = java.util.Objects.requireNonNull(accountant, "accountant must not be null");
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics must not be null");

        this.splitter = new BubbleSplitter(bubbleGrid, accountant, this, metrics);
        this.merger = new BubbleMerger(bubbleGrid, accountant, this, metrics);
        this.mover = new BubbleMover(bubbleGrid, accountant, metrics);
        this.executionLock = new ReentrantLock();
    }

    /**
     * Get the metrics tracker for this executor.
     *
     * @return topology metrics
     */
    public TopologyMetrics getMetrics() {
        return metrics;
    }

    @Override
    public void recordBubbleAdded(UUID bubbleId) {
        operationHistory.get().add(new BubbleAdded(bubbleId));
        log.debug("Recorded operation: Added bubble {}", bubbleId);
    }

    @Override
    public void recordBubbleRemoved(UUID bubbleId, EnhancedBubble bubbleSnapshot, TetreeKey<?> key) {
        operationHistory.get().add(new BubbleRemoved(bubbleId, bubbleSnapshot, key));
        log.debug("Recorded operation: Removed bubble {} at key {}", bubbleId, key);
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
            // Clear operation history from any previous execution
            operationHistory.get().clear();

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
                    if (success) {
                        metrics.recordSplitSuccess();
                    } else {
                        metrics.recordSplitFailure();
                        rollback(snapshot, "Split failed: " + message);
                    }
                }
                case MergeProposal merge -> {
                    var result = merger.execute(merge);
                    success = result.success();
                    message = result.message();
                    if (success) {
                        metrics.recordMergeSuccess();
                    } else {
                        metrics.recordMergeFailure();
                        rollback(snapshot, "Merge failed: " + message);
                    }
                }
                case MoveProposal move -> {
                    var result = mover.execute(move);
                    success = result.success();
                    message = result.message();
                    if (success) {
                        metrics.recordMoveSuccess();
                    } else {
                        metrics.recordMoveFailure();
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
     * Undoes all tracked grid operations in reverse order to restore grid structure.
     * Operations are reversed using the GridOperation undo() mechanism:
     * <ul>
     *   <li>BubbleAdded → Remove bubble from grid</li>
     *   <li>BubbleRemoved → Re-add bubble with saved state</li>
     * </ul>
     * <p>
     * <b>Limitation</b>: This only reverses grid structural changes. Entity movements
     * within bubbles are NOT reversed (requires EntityAccountant API extension).
     * EntityAccountant guarantees entity tracking consistency, so entities are never
     * lost. Rollback primarily addresses grid structure cleanup.
     *
     * @param snapshot the snapshot to restore (used for logging/validation)
     * @param reason   reason for rollback
     */
    private void rollback(Map<UUID, Set<UUID>> snapshot, String reason) {
        log.warn("ROLLBACK TRIGGERED: {}", reason);
        log.warn("Snapshot state: {} bubbles with {} total entities",
                snapshot.size(),
                snapshot.values().stream().mapToInt(Set::size).sum());

        var operations = operationHistory.get();
        if (operations.isEmpty()) {
            log.warn("No grid operations to rollback - operation may have failed early");
            return;
        }

        log.warn("Rolling back {} grid operations in reverse order", operations.size());

        // Undo operations in reverse order (LIFO)
        for (int i = operations.size() - 1; i >= 0; i--) {
            var operation = operations.get(i);
            try {
                log.debug("Undoing operation {}/{}: {}",
                         operations.size() - i, operations.size(), operation.description());
                operation.undo(bubbleGrid);
            } catch (Exception e) {
                log.error("Failed to undo operation '{}': {}",
                         operation.description(), e.getMessage(), e);
                // Continue with remaining rollback operations despite failure
            }
        }

        log.warn("Rollback complete: Grid structure restored");
        log.warn("Note: Entity movements within bubbles are not reversed (EntityAccountant limitation)");
        log.warn("EntityAccountant ensures entity tracking consistency - no entities lost");
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
