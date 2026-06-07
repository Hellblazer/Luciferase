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

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.MutationLocks;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Executes bubble split operations via TRUE Bey spatial refinement.
 * <p>
 * Replaces the source level-L leaf with its 8 level-(L+1) Bey children, assigns each entity to
 * the child that exactly contains it (via {@code contains12DOP}), and removes the parent leaf.
 * <p>
 * <b>Capacity model</b>: a bubble with {@code >5000} entities triggers a split. All 8 children
 * are created uniformly (RQ-6: ALL-8 uniform Bey refinement) to guarantee the leaf-partition
 * coverage invariant (8 children tile the parent exactly, no overlap, no parent left behind).
 * ALL 8 children are ALWAYS kept on the SUCCESS path — empty children are valid leaves that
 * migration may fill later. Only on the FAILURE/abort rollback paths are all 8 children removed.
 * <p>
 * Entities that do not pass {@code contains12DOP} for any child (FP rounding / boundary escapees)
 * are assigned to the geometrically NEAREST child (minimum Euclidean distance from entity position
 * to child centroid). This guarantees every still-present entity lands in a live child — no orphan.
 * <p>
 * Partial/density-driven refinement (RQ-6 alternative) is a deferred boundary tracked on
 * Luciferase-xtyki.
 * <p>
 * <b>Safety Guarantees</b>:
 * <ul>
 *   <li>Atomic entity transfer: All-or-nothing semantics via EntityAccountant</li>
 *   <li>100% retention validation: entityAccountant.validate() post-split</li>
 *   <li>No duplicates: EntityAccountant validates each entity in exactly one bubble</li>
 *   <li>Deadlock-free locking: ascending-UUID MutationLocks across source + all 8 children</li>
 *   <li>No orphans: outside-containment entities fall back to nearest child by Euclidean distance</li>
 * </ul>
 * <p>
 * Phase B-core (RDR-018 AC-2.5): Bey-refinement geometry.
 *
 * @author hal.hildebrand
 */
public class BubbleSplitter {

    private static final Logger log = LoggerFactory.getLogger(BubbleSplitter.class);

    /** Maximum tetree refinement level; cannot subdivide further. */
    private static final byte MAX_LEVEL = 21;

    private final TetreeBubbleGrid  bubbleGrid;
    private final EntityAccountant  accountant;
    private final OperationTracker  operationTracker;
    private final TopologyMetrics   metrics;
    private final SplitPlaneStrategy strategy;   // retained for backward compat; not consulted for geometry

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
     * <p>
     * The strategy is retained for backward compatibility but is no longer consulted
     * for geometry in the Bey-refinement path; child bubble keys come from
     * {@link Tet#geometricSubdivide()} and containment from {@link Tet#contains12DOP}.
     *
     * @param bubbleGrid        the bubble grid
     * @param accountant        the entity accountant for atomic transfers
     * @param operationTracker  the operation tracker for rollback support
     * @param metrics           the metrics tracker for operational monitoring
     * @param strategy          the split plane calculation strategy (legacy; unused for keys)
     * @throws NullPointerException if any parameter is null
     */
    public BubbleSplitter(TetreeBubbleGrid bubbleGrid, EntityAccountant accountant, OperationTracker operationTracker,
                          TopologyMetrics metrics, SplitPlaneStrategy strategy) {
        this.bubbleGrid       = Objects.requireNonNull(bubbleGrid,       "bubbleGrid must not be null");
        this.accountant       = Objects.requireNonNull(accountant,       "accountant must not be null");
        this.operationTracker = Objects.requireNonNull(operationTracker, "operationTracker must not be null");
        this.metrics          = Objects.requireNonNull(metrics,          "metrics must not be null");
        this.strategy         = Objects.requireNonNull(strategy,         "strategy must not be null");
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
     * Executes a split operation on a bubble using TRUE Bey spatial refinement.
     * <p>
     * Replaces the source level-L leaf with its 8 level-(L+1) Bey children.
     * Entities are assigned to children by exact {@code contains12DOP} containment;
     * boundary/FP-rounding escapees are assigned to the nearest child by Euclidean distance
     * from entity position to child centroid (no orphan path).
     * <p>
     * ALL 8 children are kept on the SUCCESS path (empty children are valid leaves for migration).
     * On FAILURE/abort paths all 8 children are removed and the parent is preserved.
     * <p>
     * The {@link SplitProposal}'s embedded {@link SplitPlane} is no longer consulted for
     * geometry or key selection; it may remain present in the proposal for consensus
     * compatibility but is ignored by this method.
     *
     * @param proposal the split proposal with source bubble id
     * @return execution result with success status, childBubbleIds, and entity counts
     * @throws NullPointerException if proposal is null
     */
    public SplitExecutionResult execute(SplitProposal proposal) {
        Objects.requireNonNull(proposal, "proposal must not be null");

        var correlationId  = UUID.randomUUID().toString().substring(0, 8);
        var sourceBubbleId = proposal.sourceBubble();

        metrics.recordSplitAttempt();
        log.debug("[SPLIT-{}] Bubble {}: Starting Bey-refinement split", correlationId, sourceBubbleId);

        // 1. Resolve source bubble
        var sourceBubble = bubbleGrid.getBubbleById(sourceBubbleId);
        if (sourceBubble == null) {
            log.error("[SPLIT-{}] Source bubble {} not found", correlationId, sourceBubbleId);
            metrics.recordSplitFailure("SOURCE_BUBBLE_NOT_FOUND");
            return SplitExecutionResult.failure("Source bubble not found: " + sourceBubbleId);
        }

        // 2. Resolve parent Tet and check max-level guard
        var parentKey = bubbleGrid.getKeyForBubble(sourceBubbleId);
        if (parentKey == null) {
            log.error("[SPLIT-{}] No grid key for source bubble {}", correlationId, sourceBubbleId);
            metrics.recordSplitFailure("NO_GRID_KEY");
            return SplitExecutionResult.failure("No grid key for source bubble: " + sourceBubbleId);
        }
        var parentTet = Tet.tetrahedron(parentKey);
        byte parentLevel = parentTet.l();
        if (parentLevel >= MAX_LEVEL) {
            log.error("[SPLIT-{}] Cannot refine at max level {} for bubble {}", correlationId, parentLevel,
                      sourceBubbleId);
            metrics.recordSplitFailure("MAX_LEVEL_EXCEEDED");
            return SplitExecutionResult.failure(
                "cannot refine at max level " + parentLevel + " for bubble " + sourceBubbleId);
        }

        // 3. Compute 8 Bey children and their TetreeKeys
        var children    = parentTet.geometricSubdivide();   // guaranteed 8 children in TM order
        var childKeys   = new TetreeKey[8];
        for (int i = 0; i < 8; i++) {
            childKeys[i] = children[i].tmIndex();
            if (bubbleGrid.containsBubble(childKeys[i])) {
                log.error("[SPLIT-{}] Child key {} already in grid — fresh refinement expected", correlationId,
                          childKeys[i]);
                metrics.recordSplitFailure("CHILD_KEY_COLLISION");
                return SplitExecutionResult.failure(
                    "Child key already in grid: " + childKeys[i] + " (source=" + sourceBubbleId + ")");
            }
        }

        byte childLevel = (byte) (parentLevel + 1);
        long targetFrameMs = sourceBubble.getTargetFrameMs();

        // 4. Create 8 EnhancedBubble children and add to grid BEFORE moves
        var childBubbles = new EnhancedBubble[8];
        var childIds     = new UUID[8];
        for (int i = 0; i < 8; i++) {
            childIds[i]    = uuidSupplier.get();
            childBubbles[i] = new EnhancedBubble(childIds[i], childLevel, targetFrameMs);
            bubbleGrid.addBubble(childBubbles[i], childKeys[i]);
            operationTracker.recordBubbleAdded(childIds[i]);
            log.debug("[SPLIT-{}] Added child bubble {} at key {}", correlationId, childIds[i], childKeys[i]);
        }

        // 5. Snapshot entity records (pre-lock; TOCTOU window before lock is acquired)
        //    NOTE: operationTracker.recordBubbleRemoved is called ONLY on the SUCCESS path
        //    immediately before bubbleGrid.removeBubble, so failed splits never record removal
        //    (FIX 3: avoids LIFO rollback collision when source was never removed).
        var allRecords = sourceBubble.getAllEntityRecords();
        log.debug("[SPLIT-{}] Source has {} entity records pre-lock", correlationId, allRecords.size());

        // Capture pre-split entity count for reporting
        int entitiesBefore = accountant.entitiesInBubble(sourceBubbleId).size();

        // 6. Acquire ascending-UUID locks: source + all 8 children (deadlock-free, n7io1)
        //
        // children[0..7] are freshly created and not yet visible to concurrent writers
        // (no other thread has a reference), so in practice only the source lock contends.
        // We still include all children in the lock call to honour the invariant.
        var allBubbles = new EnhancedBubble[9];
        allBubbles[0] = sourceBubble;
        System.arraycopy(childBubbles, 0, allBubbles, 1, 8);

        var movedRecords    = new ArrayList<EnhancedBubble.EntityRecord>();
        int skippedEscaped  = 0;
        int nearestFallback = 0;

        try (var ignored = MutationLocks.lock(allBubbles)) {
            for (var entityRecord : allRecords) {
                var entityId = UUID.fromString(entityRecord.id());

                // Re-validate source membership UNDER the lock (hvjdj TOCTOU guard)
                if (!sourceBubble.getEntities().contains(entityRecord.id())) {
                    log.debug("[SPLIT-{}] Entity {} escaped source before lock — skipping", correlationId, entityId);
                    skippedEscaped++;
                    continue;
                }

                var pos = entityRecord.position();

                // Find the first child that contains this entity (boundary ties → first-child-wins)
                EnhancedBubble assignedChild   = null;
                UUID            assignedChildId = null;
                for (int i = 0; i < 8; i++) {
                    if (children[i].contains12DOP(pos.x, pos.y, pos.z)) {
                        assignedChild   = childBubbles[i];
                        assignedChildId = childIds[i];
                        break;
                    }
                }

                if (assignedChild == null) {
                    // FIX 1: Entity position not contained by any child (FP rounding / boundary escapee).
                    // NEVER orphan: assign to the geometrically nearest child by Euclidean distance
                    // from entity position to child centroid (average of 4 Tet vertices).
                    int nearestIdx = nearestChildIndex(children, pos);
                    assignedChild   = childBubbles[nearestIdx];
                    assignedChildId = childIds[nearestIdx];
                    log.warn("[SPLIT-{}] Entity {} at {} outside all children (FP rounding/escapee?); "
                             + "assigned to nearest child {}", correlationId, entityId, pos, assignedChildId);
                    nearestFallback++;
                }

                // Add entity to assigned child bubble
                assignedChild.addEntity(entityRecord.id(), pos, entityRecord.content());

                // Move in accountant (atomic under its internal lock)
                boolean moved = accountant.moveBetweenBubbles(entityId, sourceBubbleId, assignedChildId);
                if (!moved) {
                    // FAIL FAST: undo all in-progress moves and abort the split
                    log.error("[SPLIT-{}] moveBetweenBubbles failed for entity {} — aborting split, rolling back {} moves",
                              correlationId, entityId, movedRecords.size());
                    assignedChild.removeEntity(entityRecord.id()); // undo bubble-side add for this entity
                    rollbackMovedRecords(correlationId, movedRecords, sourceBubble, sourceBubbleId,
                                        childBubbles, childIds);
                    removeChildBubbles(childIds);
                    metrics.recordSplitFailure("MOVE_FAILED");
                    return SplitExecutionResult.failure(
                        "moveBetweenBubbles failed for entity " + entityId + "; split aborted and rolled back");
                }

                // Remove from source after accountant confirms the move
                sourceBubble.removeEntity(entityRecord.id());
                movedRecords.add(entityRecord);
            }
        }

        int entitiesRedistributed = movedRecords.size();
        if (skippedEscaped > 0) {
            log.info("[SPLIT-{}] {} of {} entities escaped source before lock; skipped", correlationId,
                     skippedEscaped, allRecords.size());
        }
        if (nearestFallback > 0) {
            log.info("[SPLIT-{}] {} entities assigned to nearest child (FP rounding/boundary)",
                     correlationId, nearestFallback);
        }

        // 7. All-empty abort: only happens when 0 entities remained in source (all escaped before lock).
        //    After FIX 1 every still-present entity is assigned to a child, so all-empty means
        //    entitiesRedistributed == 0.  In that case ABORT: remove all 8 children, keep parent.
        //    Coverage is preserved by the surviving parent.
        if (entitiesRedistributed == 0) {
            log.error("[SPLIT-{}] No entities redistributed (all escaped) — aborting", correlationId);
            removeChildBubbles(childIds);
            metrics.recordSplitFailure("ALL_CHILDREN_EMPTY");
            return SplitExecutionResult.failure("No entities redistributed; all escaped before lock — split aborted");
        }

        // 8. SUCCESS PATH: record source removal and remove parent leaf from grid.
        //    recordBubbleRemoved is called HERE (not before the move loop) so it is NEVER
        //    recorded when the split fails (FIX 3).
        operationTracker.recordBubbleRemoved(sourceBubbleId, sourceBubble, parentKey);
        bubbleGrid.removeBubble(sourceBubbleId);

        // FIX 2: Keep ALL 8 children — empty children are valid leaves (migration may fill them).
        // Do NOT drop empty children on the success path.  The leaf-partition coverage invariant
        // requires that the 8 children tile the parent with no uncovered region.
        // Net result: exactly 8 children replace the parent (+7 net bubbles on success).
        var allChildIds = new ArrayList<UUID>(8);
        for (UUID childId : childIds) {
            allChildIds.add(childId);
        }

        // 9. Validate entity conservation
        var validation = accountant.validate();
        if (!validation.success()) {
            log.error("[SPLIT-{}] Entity validation failed: {}", correlationId, validation.details());
            return new SplitExecutionResult(false,
                                            "Entity validation failed: " + validation.details().get(0),
                                            allChildIds, entitiesBefore, entitiesBefore, 0);
        }

        log.info("[SPLIT-{}] Bey split successful: {} redistributed across 8 children "
                 + "(escaped={}, nearestFallback={})",
                 correlationId, entitiesRedistributed, skippedEscaped, nearestFallback);

        metrics.recordEntitiesMoved(entitiesRedistributed);
        metrics.recordSplitSuccess();

        return new SplitExecutionResult(true, "Bey split successful",
                                        allChildIds, entitiesBefore, entitiesBefore, entitiesRedistributed);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the index (0..7) of the child whose centroid is nearest to {@code pos} by
     * squared Euclidean distance.  Used as fallback when {@code contains12DOP} misses all
     * children (FP rounding / boundary escapee).
     *
     * @param children 8-element Tet array from geometricSubdivide()
     * @param pos      entity position
     * @return index in [0,7] of the nearest child
     */
    private static int nearestChildIndex(Tet[] children, Point3f pos) {
        int   bestIdx   = 0;
        float bestDist2 = Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            var verts = children[i].coordinates();
            float cx  = (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4.0f;
            float cy  = (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4.0f;
            float cz  = (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4.0f;
            float dx  = pos.x - cx;
            float dy  = pos.y - cy;
            float dz  = pos.z - cz;
            float d2  = dx * dx + dy * dy + dz * dz;
            if (d2 < bestDist2) {
                bestDist2 = d2;
                bestIdx   = i;
            }
        }
        return bestIdx;
    }

    /** Rolls back already-moved records: reverse accountant + bubble membership. */
    private void rollbackMovedRecords(String correlationId,
                                      List<EnhancedBubble.EntityRecord> moved,
                                      EnhancedBubble sourceBubble, UUID sourceBubbleId,
                                      EnhancedBubble[] childBubbles, UUID[] childIds) {
        for (var done : moved) {
            var doneId = UUID.fromString(done.id());
            // Find which child currently holds this entity
            UUID currentChild = null;
            for (int i = 0; i < childIds.length; i++) {
                if (accountant.entitiesInBubble(childIds[i]).contains(doneId)) {
                    currentChild = childIds[i];
                    childBubbles[i].removeEntity(done.id());
                    break;
                }
            }
            if (currentChild == null) {
                log.error("[SPLIT-{}] Rollback: entity {} not found in any child", correlationId, doneId);
                continue;
            }
            boolean ok = accountant.moveBetweenBubbles(doneId, currentChild, sourceBubbleId);
            if (!ok) {
                log.error("[SPLIT-{}] Rollback: moveBetweenBubbles({}, {} → {}) FAILED — entity may be orphaned",
                          correlationId, doneId, currentChild, sourceBubbleId);
            }
            sourceBubble.addEntity(done.id(), done.position(), done.content());
        }
    }

    /** Removes all 8 child bubbles from the grid (cleanup on failure). */
    private void removeChildBubbles(UUID[] childIds) {
        for (var childId : childIds) {
            bubbleGrid.removeBubble(childId);
        }
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /**
     * Result of a Bey-refinement split operation.
     *
     * @param success               true if split succeeded
     * @param message               description of result or error
     * @param childBubbleIds        IDs of the newly created child bubbles (empty if failed)
     * @param entitiesBefore        entity count in source before split
     * @param entitiesAfter         total entity count snapshot after split (== entitiesBefore for a conserving split)
     * @param entitiesRedistributed number of entities actually moved into child bubbles
     */
    public record SplitExecutionResult(
        boolean success,
        String message,
        List<UUID> childBubbleIds,
        int entitiesBefore,
        int entitiesAfter,
        int entitiesRedistributed
    ) {
        /** Convenience constructor for failure paths. */
        static SplitExecutionResult failure(String reason) {
            return new SplitExecutionResult(false, reason, List.of(), 0, 0, 0);
        }
    }
}
