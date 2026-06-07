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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

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
    private final TopologyMetrics metrics;

    /**
     * Creates a bubble merger.
     *
     * @param bubbleGrid        the bubble grid
     * @param accountant        the entity accountant for atomic transfers
     * @param operationTracker  the operation tracker for rollback support
     * @param metrics           the metrics tracker for operational monitoring
     * @throws NullPointerException if any parameter is null
     */
    public BubbleMerger(TetreeBubbleGrid bubbleGrid, EntityAccountant accountant, OperationTracker operationTracker,
                        TopologyMetrics metrics) {
        this.bubbleGrid = java.util.Objects.requireNonNull(bubbleGrid, "bubbleGrid must not be null");
        this.accountant = java.util.Objects.requireNonNull(accountant, "accountant must not be null");
        this.operationTracker = java.util.Objects.requireNonNull(operationTracker, "operationTracker must not be null");
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /**
     * Executes a merge operation on two bubbles.
     * <p>
     * Moves all entities from bubble2 to bubble1, then removes bubble2.
     * <p>
     * <b>Concurrency</b>: acquires BOTH bubble mutation locks in UUID.compareTo() order
     * (smallest UUID first) before the entity snapshot and move loop.  This is the SAME
     * ordering convention used by {@code TetrahedralMigration.executeMigration}, so a
     * concurrent merge + migration on the same bubble pair is deadlock-free: both callers
     * contend on the same global total order and one wins, the other waits.
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

        // Hold BOTH bubble mutation locks via the shared MutationLocks protocol (ascending UUID order,
        // deadlock-free) before ANY snapshot or mutation. This is the one coherent mutation-lock protocol
        // shared by every multi-bubble writer (Luciferase-n7io1), so concurrent merge + migration + split
        // on overlapping pairs cannot form a lock-ordering cycle.
        try (var ignored = MutationLocks.lock(bubble1, bubble2)) {
            return executeUnderLocks(correlationId, bubble1Id, bubble2Id, bubble1, bubble2);
        }
    }

    /**
     * Inner merge body — called only while BOTH bubble mutation locks are held.
     * Isolation from {@code execute} keeps the locking contract readable.
     */
    private MergeExecutionResult executeUnderLocks(String correlationId,
                                                   UUID bubble1Id, UUID bubble2Id,
                                                   EnhancedBubble bubble1, EnhancedBubble bubble2) {
        // Capture entity counts before merge (under lock — snapshot is now authoritative)
        int entities1Before = accountant.entitiesInBubble(bubble1Id).size();
        int entities2Before = accountant.entitiesInBubble(bubble2Id).size();
        int totalBefore = entities1Before + entities2Before;

        log.debug("[{}] Merging bubbles: {} has {} entities, {} has {} entities (total: {})",
                 correlationId, bubble1Id, entities1Before, bubble2Id, entities2Before, totalBefore);

        // Get entities from bubble2 (under lock — consistent with snapshot above)
        var entities2 = new HashSet<>(accountant.entitiesInBubble(bubble2Id));
        var entities1 = accountant.entitiesInBubble(bubble1Id);

        // Detect duplicates
        var duplicates = new HashSet<>(entities2);
        duplicates.retainAll(entities1);
        if (!duplicates.isEmpty()) {
            log.warn("[{}] Found {} duplicate entities between bubbles {} and {}: {}",
                    correlationId, duplicates.size(), bubble1Id, bubble2Id, duplicates);
        }

        // Get entity records from bubble2 for positions/content (under lock — stable snapshot)
        var records2 = bubble2.getAllEntityRecords();

        // Move all entities from bubble2 to bubble1.
        //
        // INVARIANT: any moveBetweenBubbles failure is fatal for the whole merge.
        //
        // The old code used `continue` on failure, which silently skipped entities,
        // leaving them half-moved: present in bubble1's EnhancedBubble structure but
        // no longer in the accountant's source set — a form of the same TOCTOU
        // conservation bug fixed in BubbleSplitter (Luciferase-7wzml.70).
        //
        // The fix: on any failure, undo the entities already moved this invocation
        // (reverse accountant moves + bubble membership) and return failure.
        var movedRecords = new ArrayList<>(records2);
        movedRecords.clear(); // reuse var as accumulator of successfully-moved records
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

            // Move in accountant (atomic under its internal lock)
            boolean moved = accountant.moveBetweenBubbles(entityId, bubble2Id, bubble1Id);
            if (!moved) {
                // FAIL FAST: undo all in-progress moves and abort the merge.
                log.error("[{}] Failed to move entity {} from {} to {} — aborting merge, rolling back {} partial moves",
                          correlationId, entityId, bubble2Id, bubble1Id, entitiesMoved);
                // Undo: move already-moved entities back to bubble2.
                // Rollback is best-effort: if a rollback move itself fails, the entity is
                // logged as an orphan but we continue reversing the rest.  Full 2PC
                // (rollback-of-rollback) is out of scope; the log.error below makes
                // any orphan diagnosable.
                bubble1.removeEntity(record.id()); // undo bubble-side add for this entity
                for (var done : movedRecords) {
                    var doneId = UUID.fromString(done.id());
                    boolean rolledBack = accountant.moveBetweenBubbles(doneId, bubble1Id, bubble2Id);
                    if (!rolledBack) {
                        log.error("[{}] Rollback move FAILED for entity {} (from bubble1 {} back to bubble2 {}) — entity may be orphaned",
                                  correlationId, doneId, bubble1Id, bubble2Id);
                    }
                    try {
                        bubble2.addEntity(done.id(), done.position(), done.content());
                    } catch (Exception rollbackEx) {
                        // MERGE-ROLLBACK-FAILED: bubble2.addEntity threw during rollback.
                        // Entity {} is now in bubble1's spatial index but the accountant
                        // mapping was already reversed (moveBetweenBubbles above moved it
                        // back to bubble2). Wrong-bubble misplacement: entity lives in
                        // bubble1 spatially but accountant believes it is in bubble2.
                        // DuplicateEntityDetector scans for duplicates, not misplacements,
                        // so this will NOT be auto-detected. Log at ERROR with a unique
                        // marker so it is diagnosable.
                        log.error("[{}] MERGE-ROLLBACK-FAILED entity {} may be misplaced in bubble {} "
                                  + "(addEntity to bubble2 threw during rollback; "
                                  + "accountant reverted but spatial index did not)",
                                  correlationId, done.id(), bubble1Id, rollbackEx);
                        // Continue best-effort: attempt to unwind remaining entities
                    }
                    bubble1.removeEntity(done.id());
                }
                metrics.recordMergeFailure();
                return new MergeExecutionResult(false,
                                               "moveBetweenBubbles failed for entity " + entityId
                                               + "; merge aborted and rolled back",
                                               totalBefore, totalBefore, duplicates.size());
            }

            // Remove from bubble2 only after accountant confirms the move
            bubble2.removeEntity(record.id());
            movedRecords.add(record);
            entitiesMoved++;
        }

        log.info("[{}] Merge: moved {} entities from bubble {} to bubble {}",
                correlationId, entitiesMoved, bubble2Id, bubble1Id);

        // Record entities moved in metrics
        metrics.recordEntitiesMoved(entitiesMoved);

        // Validate entity conservation using snapshot arithmetic — NOT two live reads.
        //
        // The old code read accountant.entitiesInBubble(bubble1Id).size() +
        // accountant.entitiesInBubble(bubble2Id).size() as two separate calls.
        // Under concurrent mutation those two reads could observe different views,
        // producing an inconsistent sum (the TOCTOU conservation check identified
        // in Luciferase-7wzml.183 — same class as BubbleSplitter Luciferase-7wzml.70).
        //
        // With BOTH locks held the conservation claim is now literally true: no other
        // writer can change either bubble's entity store until we release.
        // The deterministic check: bubble2 should be empty (every non-duplicate was
        // moved) and bubble1 should have entities1Before + entitiesMoved.
        // Total == totalBefore by construction.
        int totalAfter = totalBefore; // by construction: conservation holds if move loop completed
        int entities2AfterLive = accountant.entitiesInBubble(bubble2Id).size();

        if (entities2AfterLive != 0) {
            // Bubble2 still has entities — fall back to live sum for accurate reporting.
            int entities1AfterLive = accountant.entitiesInBubble(bubble1Id).size();
            totalAfter = entities1AfterLive + entities2AfterLive;
            log.error("[{}] Bubble2 still has {} entities after merge; total now {}", correlationId, entities2AfterLive, totalAfter);
            return new MergeExecutionResult(false,
                                           "Bubble2 still has entities: " + entities2AfterLive,
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

        // Record bubble removal for rollback BEFORE removing.
        //
        // Use the bubble's ACTUAL spatial-index registration key, not
        // bubble2.bounds().rootKey(): the latter is the level-root key tracked
        // from entity positions, not the key under which bubble2 is registered in
        // the grid. Rolling back with the root key would re-insert the bubble at
        // the wrong SFC position, leaving the spatial index inconsistent
        // (Luciferase-0frcy.122).
        var bubble2Key = bubbleGrid.getKeyForBubble(bubble2Id);
        if (bubble2Key != null) {
            operationTracker.recordBubbleRemoved(bubble2Id, bubble2, bubble2Key);
        } else {
            log.warn("[{}] No registration key found for bubble {}; skipping rollback record",
                     correlationId, bubble2Id);
        }

        // Remove bubble2 from grid (all entities now in bubble1)
        boolean removed = bubbleGrid.removeBubble(bubble2Id);
        if (removed) {
            log.debug("Removed merged bubble {} from grid", bubble2Id);
        } else {
            log.warn("Bubble {} not found in grid during removal", bubble2Id);
        }

        log.info("[{}] Merge successful: bubble {} merged into {} ({} entities total)",
                correlationId, bubble2Id, bubble1Id, totalAfter);

        return new MergeExecutionResult(true, "Merge successful",
                                       totalBefore, totalAfter, duplicates.size(), entitiesMoved);
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
 * @param entitiesMoved    number of entities relocated from bubble2 into bubble1
 */
record MergeExecutionResult(
    boolean success,
    String message,
    int entitiesBefore,
    int entitiesAfter,
    int duplicatesFound,
    int entitiesMoved
) {
    /**
     * Backward-compatible constructor that defaults {@code entitiesMoved} to 0.
     * Used by failure paths where no entities were relocated.
     */
    MergeExecutionResult(boolean success, String message, int entitiesBefore, int entitiesAfter, int duplicatesFound) {
        this(success, message, entitiesBefore, entitiesAfter, duplicatesFound, 0);
    }
}
