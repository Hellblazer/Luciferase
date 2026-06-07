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
import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult;
import com.hellblazer.luciferase.simulation.topology.events.*;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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
    private final Set<TopologyEventListener> listeners = ConcurrentHashMap.newKeySet();

    // Pluggable clock for deterministic testing - defaults to system time
    private volatile Clock clock = Clock.system();

    // Pluggable UUID supplier for deterministic event IDs - defaults to random UUIDs
    private volatile Supplier<UUID> uuidSupplier = UUID::randomUUID;

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

    /**
     * Sets the clock to use for event timestamps.
     * <p>
     * For deterministic testing, inject a {@link com.hellblazer.luciferase.simulation.distributed.integration.TestClock}
     * to control time progression.
     *
     * @param clock the clock to use (must not be null)
     * @throws NullPointerException if clock is null
     */
    public void setClock(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Sets the UUID supplier to use for generating event IDs.
     * <p>
     * For deterministic testing, inject a {@link com.hellblazer.luciferase.simulation.distributed.integration.SeededUuidSupplier}
     * to ensure reproducible event ID generation.
     *
     * @param uuidSupplier the UUID supplier to use (must not be null)
     * @throws NullPointerException if uuidSupplier is null
     */
    public void setUuidSupplier(Supplier<UUID> uuidSupplier) {
        this.uuidSupplier = java.util.Objects.requireNonNull(uuidSupplier, "uuidSupplier must not be null");
    }

    /**
     * Add a topology event listener.
     *
     * @param listener the listener to add
     */
    public void addListener(TopologyEventListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a topology event listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(TopologyEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Fire a topology event to all registered listeners.
     *
     * @param event the event to fire
     */
    private void fireEvent(TopologyEvent event) {
        for (var listener : listeners) {
            try {
                listener.onTopologyEvent(event);
            } catch (Exception e) {
                log.warn("Topology event listener threw exception: {}", e.getMessage(), e);
            }
        }
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

            // Execute based on proposal type.
            //
            // Metrics ownership (Luciferase-0frcy.42/.45): operation-level split metrics
            // (recordSplitSuccess/recordSplitFailure) are recorded by BubbleSplitter, the
            // owner of the split's categorized failure reasons. The executor must NOT record
            // them again or every split counter doubles. Merge/move success/failure are NOT
            // recorded by their executors, so the executor records those.
            //
            // Event ordering (Luciferase-0frcy.47): events are NOT fired inside this switch.
            // We compute a deferred event factory and fire it only AFTER the post-operation
            // conservation/validation checks decide the final committed/rolled-back outcome.
            // Firing here would broadcast success=true to listeners before a possible rollback,
            // leaving downstream consumers diverged from the actual grid state.
            boolean success;
            String message;
            // Factory takes the FINAL success flag (post-validation) and produces the event.
            java.util.function.Function<Boolean, TopologyEvent> eventFactory;

            switch (proposal) {
                case SplitProposal split -> {
                    var result = splitter.execute(split);
                    success = result.success();
                    message = result.message();
                    if (!success) {
                        rollback(snapshot, "Split failed: " + message);
                    }
                    // entitiesMoved must come from the actual relocation count, not
                    // (after - before): a conserving split has after == before, so that
                    // difference is always zero (Luciferase-0frcy.46).
                    int entitiesMoved = result.entitiesMovedToNewBubble();
                    var newBubbleId = result.newBubbleId();
                    eventFactory = committed -> new SplitEvent(
                        uuidSupplier.get(),
                        clock.currentTimeMillis(),
                        split.sourceBubble(),
                        newBubbleId,
                        entitiesMoved,
                        committed
                    );
                }
                case MergeProposal merge -> {
                    var result = merger.execute(merge);
                    success = result.success();
                    message = result.message();
                    if (success) {
                        metrics.recordMergeSuccess();
                    } else {
                        // RDR-018 AC-4 interim: BubbleMerger.execute() hard-fences every arbitrary
                        // two-bubble merge fail-loud WITHOUT mutating state. On that path this
                        // rollback is a benign no-op (empty operationHistory, unchanged
                        // distribution) and rollback() will emit "No grid operations to rollback"
                        // at WARN — that log line is EXPECTED for a fenced merge, not an error.
                        // Stage-2 observability (separating fence-rejection from attempt-failure in
                        // the event/metric schema) is tracked as an explicit boundary, see
                        // Luciferase-xtyki / RDR-018 AC-2.5.
                        metrics.recordMergeFailure();
                        rollback(snapshot, "Merge failed: " + message);
                    }
                    // Use the merger's actual relocation count, not (after - before)
                    // (Luciferase-0frcy.46).
                    int entitiesMoved = result.entitiesMoved();
                    eventFactory = committed -> new MergeEvent(
                        uuidSupplier.get(),
                        clock.currentTimeMillis(),
                        merge.bubble1(),
                        merge.bubble2(),
                        entitiesMoved,
                        committed
                    );
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
                    // Capture the centroid defensively: on a failed move the source bubble may
                    // be absent (getBubbleById returns null) or have no bounds (Luciferase-0frcy.48).
                    var bubble = bubbleGrid.getBubbleById(move.sourceBubble());
                    float cx = 0f, cy = 0f, cz = 0f;
                    UUID eventBubbleId = move.sourceBubble();
                    if (bubble != null && bubble.bounds() != null) {
                        var centroid = bubble.bounds().centroid();
                        cx = (float) centroid.getX();
                        cy = (float) centroid.getY();
                        cz = (float) centroid.getZ();
                        eventBubbleId = bubble.id();
                    }
                    final float fcx = cx, fcy = cy, fcz = cz;
                    final UUID fBubbleId = eventBubbleId;
                    // Note: We don't have the old centroid, so using current for both (visualization will handle)
                    eventFactory = committed -> new MoveEvent(
                        uuidSupplier.get(),
                        clock.currentTimeMillis(),
                        fBubbleId,
                        fcx, fcy, fcz,
                        fcx, fcy, fcz,
                        committed
                    );
                }
                default -> throw new IllegalStateException("Unknown proposal type: " + proposal.getClass());
            }

            // Validate entity conservation. If validation fails we roll back and fire the event
            // with success=false so listeners observe the actual (reverted) outcome.
            var validation = accountant.validate();
            if (!validation.success()) {
                rollback(snapshot, "Entity validation failed: " + validation.details());
                fireEvent(eventFactory.apply(false));
                return new TopologyExecutionResult(false,
                                                  "Entity validation failed: " + validation.details().get(0),
                                                  totalBefore, getTotalEntityCount());
            }

            int totalAfter = getTotalEntityCount();
            if (totalAfter != totalBefore) {
                rollback(snapshot, "Entity count mismatch: before=" + totalBefore + ", after=" + totalAfter);
                fireEvent(eventFactory.apply(false));
                return new TopologyExecutionResult(false,
                                                  "Entity count mismatch: before=" + totalBefore + ", after=" + totalAfter,
                                                  totalBefore, totalAfter);
            }

            // Conservation holds and no rollback occurred: the operation outcome is now committed.
            // Fire the event with the operation's own success flag (Luciferase-0frcy.47).
            fireEvent(eventFactory.apply(success));

            log.info("Topology change successful: {} entities retained", totalAfter);
            return new TopologyExecutionResult(success, message, totalBefore, totalAfter);

        } finally {
            // Luciferase-zwyf2: drop the thread-local operation history so the list (and the
            // GridOperation snapshots it holds) is not retained on the calling thread after the
            // execution completes — important when execute() runs on a pooled thread.
            operationHistory.remove();
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
     * Performs two complementary restores:
     * <ol>
     *   <li><b>Grid structure</b>: Undoes all tracked {@link GridOperation}s in LIFO order
     *       (BubbleAdded → remove bubble, BubbleRemoved → re-add bubble). Grid undo runs
     *       first so that snapshot bubble IDs (e.g. bubble2 removed by a merge) are live
     *       again before any entity move targets them.</li>
     *   <li><b>Entity distribution</b>: Iterates the snapshot and, for every entity whose
     *       current accountant location differs from its snapshot location, issues a
     *       {@code moveBetweenBubbles} call. This is general: it covers splits (entity on
     *       transient newBubble → restored to sourceBubble), merges (entity moved from
     *       bubble2 to bubble1 → restored to bubble2 after grid undo re-adds bubble2), and
     *       any future operation. After entity moves, any bubble that is still absent from
     *       the snapshot is purged.</li>
     * </ol>
     *
     * @param snapshot the pre-operation entity distribution (bubble → entity-set)
     * @param reason   human-readable reason for the rollback
     */
    private void rollback(Map<UUID, Set<UUID>> snapshot, String reason) {
        log.warn("ROLLBACK TRIGGERED: {}", reason);
        log.warn("Snapshot state: {} bubbles with {} total entities",
                snapshot.size(),
                snapshot.values().stream().mapToInt(Set::size).sum());

        // --- Step 1: undo grid structural changes (LIFO) ---
        // Must run BEFORE entity restore so that snapshot bubble IDs removed by the
        // operation (e.g. bubble2 in a merge) are present in the grid when entity
        // moveBetweenBubbles targets them.
        var operations = operationHistory.get();
        if (operations.isEmpty()) {
            log.warn("No grid operations to rollback - operation may have failed early");
        } else {
            log.warn("Rolling back {} grid operations in reverse order", operations.size());
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
            log.warn("Grid structure restored ({} operations undone)", operations.size());
        }

        // --- Step 2: restore accountant entity distribution to match snapshot ---
        //
        // For every entity in the snapshot, if its current accountant location differs
        // from the snapshot location, move it back.  This is O(all snapshot entities)
        // and handles both:
        //   - SPLIT: entities on the transient newBubble → moved back to sourceBubble
        //   - MERGE: entities moved from bubble2 to bubble1 → moved back to bubble2
        //     (bubble2 was re-added by grid undo in Step 1 above)
        int restored = 0;
        int failed = 0;

        for (var entry : snapshot.entrySet()) {
            var snapBubble = entry.getKey();
            for (var entityId : entry.getValue()) {
                var current = accountant.getLocationOfEntity(entityId);
                if (current == null) {
                    log.error("ROLLBACK: entity {} has no bubble assignment — cannot restore (potential orphan)",
                             entityId);
                    failed++;
                    continue;
                }
                if (!current.equals(snapBubble)) {
                    boolean moved = accountant.moveBetweenBubbles(entityId, current, snapBubble);
                    if (moved) {
                        restored++;
                    } else {
                        log.error("ROLLBACK: moveBetweenBubbles({}, {} → {}) failed — entity may be orphaned",
                                 entityId, current, snapBubble);
                        failed++;
                    }
                }
            }
        }

        // Purge any bubbles that are still present in the accountant but were not in
        // the snapshot (transient bubbles that should no longer exist after rollback).
        for (var bubbleId : new HashSet<>(accountant.getDistribution().keySet())) {
            if (!snapshot.containsKey(bubbleId)) {
                accountant.purgeBubble(bubbleId);
            }
        }

        log.warn("Accountant distribution restored: {} entities moved back to snapshot bubbles, {} failed",
                restored, failed);
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
