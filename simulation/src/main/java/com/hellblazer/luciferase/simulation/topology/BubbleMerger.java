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
import com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes bubble merge operations with duplicate detection.
 * <p>
 * <b>RDR-018 AC-4 coverage fence:</b> the public {@link #execute(MergeProposal)} entry point
 * currently REJECTS every arbitrary two-bubble merge fail-loud. Removing one of two arbitrary
 * bubbles leaves its tetrahedral region untiled (a partition coverage hole, RDR-018 F4); the
 * only coverage-preserving merge under Option B is the inverse-Bey collapse of a complete
 * sibling-leaf set, which lands with B-core (AC-2.5). The entity-move machinery below survives
 * as the package-private {@link #executeMerge(MergeProposal)} and is exercised directly by unit
 * tests; it is the foundation the coverage-preserving merge will wrap.
 * <p>
 * The remainder of this contract describes the {@link #executeMerge(MergeProposal)} mechanism,
 * which merges two underpopulated bubbles (<500 entities each) by:
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

        var bubble1Id = proposal.bubble1();
        var bubble2Id = proposal.bubble2();

        // Resolve both bubbles first so a malformed proposal still reports the precise
        // "not found" reason (these checks are part of the public contract and are
        // exercised independently of the coverage fence below).
        var bubble1 = bubbleGrid.getBubbleById(bubble1Id);
        var bubble2 = bubbleGrid.getBubbleById(bubble2Id);
        if (bubble1 == null) {
            return new MergeExecutionResult(false, "Bubble1 not found: " + bubble1Id, 0, 0, 0);
        }
        if (bubble2 == null) {
            return new MergeExecutionResult(false, "Bubble2 not found: " + bubble2Id, 0, 0, 0);
        }

        // RDR-018 AC-4 / F4 COVERAGE FENCE — fail-loud, NO mutation.
        //
        // An arbitrary two-bubble merge moves bubble2's entities into bubble1 and then
        // removes bubble2 from the grid (see executeMerge -> removeBubble). Because routing
        // is key-as-geometry — every bubble owns exactly its TetreeKey's tetrahedron and an
        // escaping entity is routed by locateTetrahedron(position, level) — removing bubble2
        // leaves bubble2's tetrahedral region UNTILED. Any entity that later escapes into
        // that region routes to a key with no owning bubble and is silently dropped: a
        // partition coverage hole.
        //
        // Under RDR-018 Option B the only coverage-preserving merge is the inverse-Bey
        // collapse of a COMPLETE set of sibling child leaves back into their parent leaf — now
        // implemented as executeCollapse(CollapseProposal) and driven via TopologyExecutor with a
        // CollapseProposal. THIS arbitrary two-bubble path is NOT coverage-safe (the two bubbles are
        // not a complete sibling set), so it remains a coverage-hole-punching operation and is
        // rejected here. Use a CollapseProposal for the coverage-preserving merge.
        //
        // NOTE: unlike the RDR-012 split fence, a documentation-only note is INSUFFICIENT for
        // merge (RDR-018 gate S5): merge is reachable independently of the live tick path and
        // actively removes coverage, so it must be hard-fenced in code. The entity-move
        // mechanism survives as the package-private executeMerge(), gated here until B-core
        // supplies coverage-safe (sibling-collapse) proposals.
        // The result's entity-count fields carry no operational meaning on a rejected proposal
        // (nothing is mutated), so they are reported as 0 rather than reading the accountant
        // outside any lock — which would otherwise produce a stale count under concurrent
        // splits/migrations. Callers that need live counts query the accountant directly.
        log.warn("Rejecting arbitrary two-bubble merge {} + {} (RDR-018 AC-4 coverage fence): "
                 + "would leave bubble2 region untiled; coverage-preserving sibling-collapse merge "
                 + "deferred to B-core (AC-2.5)", bubble1Id, bubble2Id);
        return new MergeExecutionResult(false,
                                        "RDR-018 AC-4 fence: arbitrary two-bubble merge rejected — removing bubble2 "
                                        + "would leave its tetrahedral region untiled (coverage hole); "
                                        + "coverage-preserving sibling-collapse merge deferred to B-core (AC-2.5)",
                                        0, 0, 0);
    }

    /**
     * The merge entity-move mechanism: relocate every non-duplicate entity from bubble2 into
     * bubble1 under both mutation locks, validate conservation, roll back atomically on any
     * failure, and remove the now-empty bubble2 from the grid.
     * <p>
     * <b>Package-private and intentionally NOT the public entry point.</b> On its own this
     * mechanism does NOT preserve partition coverage — its terminal {@code removeBubble(bubble2)}
     * leaves bubble2's tetrahedral region untiled (RDR-018 F4). It is gated behind
     * {@link #execute(MergeProposal)}'s coverage fence and is invoked directly only by unit
     * tests of the move/conservation/rollback machinery (the machinery itself is correct and
     * is the foundation the B-core sibling-collapse merge — RDR-018 AC-2.5 — will build on,
     * wrapping it with the coverage-preserving re-tile step).
     *
     * @param proposal the merge proposal with two existing bubble IDs
     * @return execution result with success status and details
     * @throws NullPointerException if proposal is null
     */
    MergeExecutionResult executeMerge(MergeProposal proposal) {
        java.util.Objects.requireNonNull(proposal, "proposal must not be null");

        // Generate correlation ID for tracking this operation across log statements
        var correlationId = UUID.randomUUID().toString().substring(0, 8);
        var bubble1Id = proposal.bubble1();
        var bubble2Id = proposal.bubble2();

        log.debug("[{}] Executing merge mechanism: {} + {}", correlationId, bubble1Id, bubble2Id);

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

        // Remove bubble2 from grid (all entities now in bubble1).
        //
        // NOTE (RDR-018): THIS LINE IS THE COVERAGE HOLE (F4) for the ARBITRARY two-bubble mechanism.
        // Removing bubble2 untiles its tetrahedral region, which is why execute(MergeProposal)
        // hard-fences this path (AC-4) and it is reachable only via unit tests of the move machinery.
        // The coverage-PRESERVING merge now lives separately as executeCollapse(CollapseProposal)
        // (RDR-018 AC-3 prerequisite, Luciferase-q37mx): it collapses a COMPLETE sibling-leaf set into
        // the re-registered parent leaf so the region stays tiled. Do NOT un-fence
        // execute(MergeProposal) — arbitrary two-bubble merge stays a coverage-hole operation.
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

    /**
     * Collapse a COMPLETE set of 8 sibling Bey child leaves back into their parent leaf — the
     * coverage-preserving inverse-Bey merge under RDR-018 Option B (AC-3 prerequisite).
     * <p>
     * This is the coverage-safe counterpart to {@link #executeMerge(MergeProposal)}: where an
     * arbitrary two-bubble merge untiles the removed bubble's region (F4 — hard-fenced at
     * {@link #execute(MergeProposal)}), a sibling collapse removes a full set of 8 level-{@code (L+1)}
     * children whose union is exactly their level-{@code L} parent tetrahedron, then re-registers
     * that parent. Coverage is preserved by construction. The 8 children's entities are moved into
     * the parent; the parent absorbs the entire region.
     * <p>
     * <b>Derivation.</b> The proposal carries one {@code anchorChild}; the parent and the full
     * sibling set are derived from its registration key
     * ({@code parent = Tet.tetrahedron(key).parent()}, {@code siblings = parent.geometricSubdivide()}).
     * The collapse is rejected fail-loud (NO mutation) unless ALL 8 siblings are registered and the
     * parent key is free.
     * <p>
     * <b>Re-tile atomicity (RDR-018 AC-2 carry-forward, surfaced by the pg344 router review).</b> The
     * up-walk router {@link TetreeBubbleGrid#resolveLeafKey} prefers the DEEPEST existing leaf and
     * reads {@code bubblesByKey} lock-free (the grid has no router-shared lock — see
     * {@link TetreeBubbleGrid#getMaxLeafLevel} tolerated-race note). The grid re-tile therefore removes
     * the 8 children FIRST and adds the parent LAST. The only transient a concurrent reader can observe
     * is "children gone, parent not yet present" → {@code resolveLeafKey} returns {@code null} → the
     * migration router treats the entity as staying (no relocation this tick), which is harmless. The
     * inverse order (add parent first) would expose a window where the up-walk still finds an
     * about-to-be-removed deeper child and routes an entity into it — the orphan-to-removed-bubble
     * class (Luciferase-c1ka5) — and is deliberately NOT used. The {@code maxLeafLevel} watermark is
     * monotonic-up and stays at {@code L+1} after collapse; the up-walk still resolves to the parent at
     * {@code L} because {@link TetreeBubbleGrid#removeBubble} purges the child keys from
     * {@code bubblesByKey}.
     *
     * @param proposal the collapse proposal carrying any one of the 8 sibling children
     * @return execution result with success status and details
     * @throws NullPointerException if proposal is null
     */
    CollapseExecutionResult executeCollapse(CollapseProposal proposal) {
        java.util.Objects.requireNonNull(proposal, "proposal must not be null");

        var correlationId = UUID.randomUUID().toString().substring(0, 8);
        var anchorChildId = proposal.anchorChild();

        // 1. Resolve the anchor child and its registration key.
        var anchorChild = bubbleGrid.getBubbleById(anchorChildId);
        if (anchorChild == null) {
            return CollapseExecutionResult.failure("Anchor child bubble not found: " + anchorChildId);
        }
        var anchorKey = bubbleGrid.getKeyForBubble(anchorChildId);
        if (anchorKey == null) {
            return CollapseExecutionResult.failure("No grid key for anchor child: " + anchorChildId);
        }

        // 2. Derive the parent tetrahedron and the full 8-sibling key set.
        var anchorTet = Tet.tetrahedron(anchorKey);
        if (anchorTet.l() < 1) {
            return CollapseExecutionResult.failure(
                "Anchor child at level " + anchorTet.l() + " has no parent to collapse into");
        }
        var parentTet   = anchorTet.parent();
        var parentKey   = parentTet.tmIndex();
        byte parentLevel = parentTet.l();
        if (bubbleGrid.containsBubble(parentKey)) {
            return CollapseExecutionResult.failure(
                "Parent key already registered (" + parentKey + ") — not a collapsible refinement state");
        }

        // 3. Verify the COMPLETE sibling set is present and collect the child bubbles. An incomplete
        //    set cannot collapse without leaving the missing siblings' regions untiled (coverage hole).
        var siblings     = parentTet.geometricSubdivide();   // 8 children in TM order
        var childKeys    = new TetreeKey[8];
        var childBubbles = new EnhancedBubble[8];
        var childIds     = new UUID[8];
        for (int i = 0; i < 8; i++) {
            childKeys[i] = siblings[i].tmIndex();
            if (!bubbleGrid.containsBubble(childKeys[i])) {
                log.warn("[COLLAPSE-{}] Incomplete sibling set: child key {} not registered — rejecting collapse",
                         correlationId, childKeys[i]);
                metrics.recordMergeFailure();
                return CollapseExecutionResult.failure(
                    "Incomplete sibling set: child key " + childKeys[i]
                    + " is not registered — cannot collapse without untiling its region");
            }
            childBubbles[i] = bubbleGrid.getBubble(childKeys[i]);
            childIds[i]     = childBubbles[i].id();
        }

        // 4. Create the parent bubble (NOT yet registered in the grid — registered last, after moves).
        var parentId     = UUID.randomUUID();
        long targetFrameMs = anchorChild.getTargetFrameMs();
        var parentBubble = new EnhancedBubble(parentId, parentLevel, targetFrameMs);

        // 5. Acquire ascending-UUID locks across all 8 children + the parent (deadlock-free, n7io1).
        var allBubbles = new EnhancedBubble[9];
        System.arraycopy(childBubbles, 0, allBubbles, 0, 8);
        allBubbles[8] = parentBubble;

        // Track (record, sourceChildId) for fail-fast rollback of partial moves.
        var movedRecords   = new ArrayList<EnhancedBubble.EntityRecord>();
        var movedFromChild = new ArrayList<UUID>();
        int entitiesBefore = 0;
        int entitiesAfter  = 0;

        try (var ignored = MutationLocks.lock(allBubbles)) {
            for (int i = 0; i < 8; i++) {
                entitiesBefore += accountant.entitiesInBubble(childIds[i]).size();
            }

            for (int i = 0; i < 8; i++) {
                var childId = childIds[i];
                var records = childBubbles[i].getAllEntityRecords();
                for (var record : records) {
                    var entityId = UUID.fromString(record.id());

                    // Add to parent first, then move in accountant, then remove from child.
                    parentBubble.addEntity(record.id(), record.position(), record.content());
                    boolean moved = accountant.moveBetweenBubbles(entityId, childId, parentId);
                    if (!moved) {
                        log.error("[COLLAPSE-{}] moveBetweenBubbles failed for entity {} (child {} → parent {}) "
                                  + "— aborting collapse, rolling back {} moves",
                                  correlationId, entityId, childId, parentId, movedRecords.size());
                        parentBubble.removeEntity(record.id()); // undo this entity's parent-side add
                        rollbackCollapseMoves(correlationId, movedRecords, movedFromChild,
                                              parentBubble, parentId, childBubbles, childIds);
                        metrics.recordMergeFailure();
                        return CollapseExecutionResult.failure(
                            "moveBetweenBubbles failed for entity " + entityId + "; collapse aborted and rolled back");
                    }
                    childBubbles[i].removeEntity(record.id());
                    movedRecords.add(record);
                    movedFromChild.add(childId);
                }
            }

            // 6. Validate entity conservation (authoritative) — STILL UNDER the mutation locks so the
            //    rollback below cannot race a concurrent migration on one of the children/parent (H1).
            var validation = accountant.validate();
            if (!validation.success()) {
                log.error("[COLLAPSE-{}] Entity validation failed after moves: {}", correlationId, validation.details());
                // Moves completed but accountant is inconsistent: roll the moves back before any grid change.
                rollbackCollapseMoves(correlationId, movedRecords, movedFromChild,
                                      parentBubble, parentId, childBubbles, childIds);
                metrics.recordMergeFailure();
                return CollapseExecutionResult.failure(
                    "Entity validation failed after collapse moves: " + validation.details().get(0));
            }

            // Measure the post-move parent count (still under lock — authoritative snapshot) rather
            // than aliasing entitiesBefore, so the result reports a real count (S2).
            entitiesAfter = accountant.entitiesInBubble(parentId).size();
        }

        int entitiesMoved = movedRecords.size();

        // 7. SUCCESS grid re-tile. ORDER MATTERS (see javadoc): remove the 8 children FIRST, then
        //    register the parent LAST, so the only observable transient resolves to null ("stay"),
        //    never to an about-to-be-removed deeper child.
        for (int i = 0; i < 8; i++) {
            operationTracker.recordBubbleRemoved(childIds[i], childBubbles[i], childKeys[i]);
            boolean removed = bubbleGrid.removeBubble(childIds[i]);
            if (!removed) {
                log.warn("[COLLAPSE-{}] Child bubble {} not found in grid during removal", correlationId, childIds[i]);
            }
        }
        bubbleGrid.addBubble(parentBubble, parentKey);
        operationTracker.recordBubbleAdded(parentId);

        metrics.recordEntitiesMoved(entitiesMoved);
        log.info("[COLLAPSE-{}] Collapse successful: 8 children → parent {} at level {} ({} entities absorbed)",
                 correlationId, parentId, parentLevel, entitiesMoved);

        var childIdList = new ArrayList<UUID>(8);
        for (var id : childIds) {
            childIdList.add(id);
        }
        return new CollapseExecutionResult(true, "Collapse successful", parentId, childIdList,
                                           entitiesBefore, entitiesAfter, entitiesMoved);
    }

    /**
     * Rolls back partial collapse moves: for each entity already moved into the parent, move it back
     * to its source child (accountant + bubble membership). Best-effort — a failed reverse move is
     * logged as a potential orphan but the unwind continues. No grid structural change has occurred at
     * the point this is called, so only entity distribution must be restored.
     */
    private void rollbackCollapseMoves(String correlationId,
                                       List<EnhancedBubble.EntityRecord> moved,
                                       List<UUID> movedFromChild,
                                       EnhancedBubble parentBubble, UUID parentId,
                                       EnhancedBubble[] childBubbles, UUID[] childIds) {
        for (int j = moved.size() - 1; j >= 0; j--) {
            var record  = moved.get(j);
            var srcChild = movedFromChild.get(j);
            var entityId = UUID.fromString(record.id());
            boolean ok = accountant.moveBetweenBubbles(entityId, parentId, srcChild);
            if (!ok) {
                log.error("[COLLAPSE-{}] Rollback: moveBetweenBubbles({}, parent {} → child {}) FAILED — entity may be orphaned",
                          correlationId, entityId, parentId, srcChild);
            }
            // Restore child-side membership.
            for (int i = 0; i < childIds.length; i++) {
                if (childIds[i].equals(srcChild)) {
                    childBubbles[i].addEntity(record.id(), record.position(), record.content());
                    break;
                }
            }
            parentBubble.removeEntity(record.id());
        }
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

/**
 * Result of a coverage-preserving sibling-collapse merge (RDR-018 AC-3 prerequisite).
 *
 * @param success         true if the collapse succeeded
 * @param message         description of result or error
 * @param parentBubbleId  the parent bubble re-registered by the collapse (null on failure)
 * @param childBubbleIds  the 8 child bubbles that were collapsed (empty on failure)
 * @param entitiesBefore  total entity count across the 8 children before the collapse
 * @param entitiesAfter   measured entity count in the parent after the collapse moves (under lock);
 *                        equals {@code entitiesBefore} for a conserving collapse
 * @param entitiesMoved   number of entities moved from the children into the parent
 */
record CollapseExecutionResult(
    boolean success,
    String message,
    UUID parentBubbleId,
    List<UUID> childBubbleIds,
    int entitiesBefore,
    int entitiesAfter,
    int entitiesMoved
) {
    static CollapseExecutionResult failure(String reason) {
        return new CollapseExecutionResult(false, reason, null, List.of(), 0, 0, 0);
    }
}
