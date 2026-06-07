/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import com.hellblazer.luciferase.simulation.topology.events.CollapseEvent;
import com.hellblazer.luciferase.simulation.topology.events.TopologyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD test for the coverage-preserving inverse-Bey sibling-collapse merge (RDR-018 AC-3
 * prerequisite, Luciferase-q37mx).
 * <p>
 * The collapse is the inverse of a Bey split: a COMPLETE set of 8 level-{@code (L+1)} sibling
 * children collapses back into their level-{@code L} parent leaf, which absorbs all their entities.
 * These tests pin: conservation (no entity dropped), coverage maintenance (the parent region stays
 * tiled — interior points resolve to the parent, not null and not a removed child), fail-loud on an
 * incomplete sibling set (no mutation), the never-lowered watermark with a still-correct up-walk
 * after collapse, and the un-fenced executor path firing a {@link CollapseEvent}.
 *
 * @author hal.hildebrand
 */
class BubbleMergerCollapseTest {

    private static final TestClock CLOCK = new TestClock(1_000L);
    private static final WorldBounds WORLD = new WorldBounds(0.0f, 100.0f);

    private TetreeBubbleGrid bubbleGrid;
    private EntityAccountant accountant;
    private TopologyMetrics  metrics;
    private BubbleMerger     merger;

    @BeforeEach
    void setUp() {
        bubbleGrid = new TetreeBubbleGrid((byte) 5);
        accountant = new EntityAccountant();
        metrics    = new TopologyMetrics();
        merger     = new BubbleMerger(bubbleGrid, accountant, OperationTracker.NOOP, metrics);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Centroid of a tet (average of 4 vertices) — strictly interior, never on a face. */
    private static Point3f centroid(Tet tet) {
        var v = tet.coordinates();
        return new Point3f((v[0].x + v[1].x + v[2].x + v[3].x) / 4f,
                           (v[0].y + v[1].y + v[2].y + v[3].y) / 4f,
                           (v[0].z + v[1].z + v[2].z + v[3].z) / 4f);
    }

    /**
     * Refine a level-{@code parentLevel} tet (descend child(0) from root) into its 8 Bey children,
     * register each child bubble in the grid, and place {@code perChild} entities at each child's
     * centroid (registered in the accountant). Returns the 8 child bubbles. The parent is NOT
     * registered (a refined leaf set has no parent — exactly the collapsible state).
     */
    private List<EnhancedBubble> refineWithEntities(byte parentLevel, int perChild) {
        var parentTet = parentTetAtLevel(parentLevel);
        var children  = parentTet.geometricSubdivide();
        var result    = new ArrayList<EnhancedBubble>(8);
        byte childLevel = (byte) (parentLevel + 1);
        for (var child : children) {
            var bubble = new EnhancedBubble(UUID.randomUUID(), childLevel, 10L);
            bubbleGrid.addBubble(bubble, child.tmIndex());
            var here = centroid(child);
            for (int e = 0; e < perChild; e++) {
                var id = UUID.randomUUID();
                bubble.addEntity(id.toString(), here, null);
                accountant.register(bubble.id(), id);
            }
            result.add(bubble);
        }
        return result;
    }

    /** A valid tet at {@code level} obtained by descending child(0) from the S0 root. */
    private static Tet parentTetAtLevel(byte level) {
        var t = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        for (byte l = 1; l <= level; l++) {
            t = t.child(0);
        }
        return t;
    }

    private CollapseProposal proposalAnchoredOn(UUID anchorChildId) {
        return new CollapseProposal(UUID.randomUUID(), anchorChildId,
                                    DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis());
    }

    // -----------------------------------------------------------------------
    // A. Conservation: all 8 children's entities absorbed by the parent
    // -----------------------------------------------------------------------

    @Test
    void collapse_conservation_allChildEntitiesAbsorbedByParent() {
        var children = refineWithEntities((byte) 2, 7);  // 8 children × 7 = 56 entities
        var parentTet = parentTetAtLevel((byte) 2);
        var parentKey = parentTet.tmIndex();
        int totalBefore = children.stream().mapToInt(b -> accountant.entitiesInBubble(b.id()).size()).sum();
        assertEquals(56, totalBefore);

        var anchorId = children.get(0).id();
        var result = merger.executeCollapse(proposalAnchoredOn(anchorId));

        assertTrue(result.success(), "Collapse must succeed: " + result.message());
        assertEquals(56, result.entitiesMoved(), "all 56 entities must move into the parent");
        assertEquals(8, result.childBubbleIds().size(), "result must report the 8 collapsed children");

        // Parent now registered, all 8 children gone.
        assertTrue(bubbleGrid.containsBubble(parentKey), "parent key must be registered after collapse");
        var parentId = result.parentBubbleId();
        assertNotNull(bubbleGrid.getBubbleById(parentId), "parent bubble must be live in grid");
        for (var child : children) {
            assertNull(bubbleGrid.getBubbleById(child.id()), "child " + child.id() + " must be removed");
            assertEquals(0, accountant.entitiesInBubble(child.id()).size(),
                         "no entity may remain mapped to a removed child");
        }
        // Every entity now lives in the parent.
        assertEquals(56, accountant.entitiesInBubble(parentId).size(),
                     "all 56 entities must be accounted in the parent");
        assertTrue(accountant.validate().success(), "accountant must be valid after collapse");
    }

    // -----------------------------------------------------------------------
    // B. Incomplete sibling set → fail-loud, NO mutation
    // -----------------------------------------------------------------------

    @Test
    void collapse_incompleteSiblingSet_failsLoud_noMutation() {
        var children = refineWithEntities((byte) 2, 3);
        var parentKey = parentTetAtLevel((byte) 2).tmIndex();

        // Remove ONE child so the sibling set is incomplete (its region would be left untiled).
        var dropped = children.get(3);
        bubbleGrid.removeBubble(dropped.id());

        int before = accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();

        var anchorId = children.get(0).id();
        var result = merger.executeCollapse(proposalAnchoredOn(anchorId));

        assertFalse(result.success(), "collapse of an incomplete sibling set must fail");
        assertTrue(result.message().contains("Incomplete sibling set"),
                   "failure must name the incomplete set; got: " + result.message());

        // NO mutation: the remaining 7 children survive, the parent is NOT registered, counts unchanged.
        assertFalse(bubbleGrid.containsBubble(parentKey), "parent must NOT be registered on failure");
        int survivors = 0;
        for (var child : children) {
            if (bubbleGrid.getBubbleById(child.id()) != null) {
                survivors++;
            }
        }
        assertEquals(7, survivors, "the 7 still-registered children must be untouched");
        int after = accountant.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(before, after, "entity distribution must be unchanged after a rejected collapse");
        assertTrue(accountant.validate().success(), "accountant must remain valid after a rejected collapse");
    }

    // -----------------------------------------------------------------------
    // C. Coverage maintained — interior points resolve to the parent (partition grid)
    // -----------------------------------------------------------------------

    @Test
    void collapse_coverageMaintained_interiorResolvesToParent() {
        var grid = new TetreeBubbleGrid((byte) 21);
        grid.createBubbles(8, WORLD, 10L);
        var acc = new EntityAccountant();
        var mrg = new BubbleMerger(grid, acc, OperationTracker.NOOP, new TopologyMetrics());
        var tetree = grid.getSpatialIndex();
        byte base = grid.getBaseLevel();
        assertTrue(base > 0, "must be a single-level partition");

        // Refine one base cell into 8 children (empty), mirroring a Bey split.
        var parentKey = grid.getBubblesWithKeys().keySet().iterator().next();
        var parentTet = Tet.tetrahedron(parentKey);
        var children  = parentTet.geometricSubdivide();
        grid.removeBubble(grid.getBubble(parentKey).id());
        var childIds = new ArrayList<UUID>(8);
        for (int i = 0; i < 8; i++) {
            var b = new EnhancedBubble(UUID.randomUUID(), (byte) (base + 1), 10L);
            grid.addBubble(b, children[i].tmIndex());
            childIds.add(b.id());
        }

        // Before collapse: interior points resolve to the deep child leaves.
        for (int i = 0; i < 8; i++) {
            assertEquals(children[i].tmIndex(), grid.resolveLeafKey(tetree, centroid(children[i])),
                         "pre-collapse: interior point must resolve to its deep child leaf");
        }

        var result = mrg.executeCollapse(new CollapseProposal(
            UUID.randomUUID(), childIds.get(0), DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis()));
        assertTrue(result.success(), "collapse must succeed: " + result.message());

        // After collapse: the parent key is back, and EVERY interior point of the (former) children
        // now resolves to the PARENT leaf — coverage is preserved, no untiled hole, no orphan child.
        assertTrue(grid.containsBubble(parentKey), "parent key must be re-registered");
        for (int i = 0; i < 8; i++) {
            var resolved = grid.resolveLeafKey(tetree, centroid(children[i]));
            assertEquals(parentKey, resolved,
                         "post-collapse: interior point of former child " + i + " must resolve to the parent");
            assertFalse(grid.containsBubble(children[i].tmIndex()),
                        "former child key " + i + " must be gone from the grid");
        }
    }

    // -----------------------------------------------------------------------
    // D. Watermark is never lowered; the up-walk still resolves to the parent after collapse
    // -----------------------------------------------------------------------

    @Test
    void collapse_watermarkStaysHigh_upWalkStillResolves() {
        var grid = new TetreeBubbleGrid((byte) 21);
        grid.createBubbles(8, WORLD, 10L);
        var acc = new EntityAccountant();
        var mrg = new BubbleMerger(grid, acc, OperationTracker.NOOP, new TopologyMetrics());
        var tetree = grid.getSpatialIndex();
        byte base = grid.getBaseLevel();

        var parentKey = grid.getBubblesWithKeys().keySet().iterator().next();
        var parentTet = Tet.tetrahedron(parentKey);
        var children  = parentTet.geometricSubdivide();
        grid.removeBubble(grid.getBubble(parentKey).id());
        var childIds = new ArrayList<UUID>(8);
        for (int i = 0; i < 8; i++) {
            var b = new EnhancedBubble(UUID.randomUUID(), (byte) (base + 1), 10L);
            grid.addBubble(b, children[i].tmIndex());
            childIds.add(b.id());
        }
        assertEquals((byte) (base + 1), grid.getMaxLeafLevel(), "watermark raised to base+1 by refinement");

        var result = mrg.executeCollapse(new CollapseProposal(
            UUID.randomUUID(), childIds.get(0), DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis()));
        assertTrue(result.success(), "collapse must succeed: " + result.message());

        // The watermark is monotonic-up: it STAYS at base+1 after collapse (never lowered).
        assertEquals((byte) (base + 1), grid.getMaxLeafLevel(),
                     "watermark must NOT be lowered after collapse (monotonic-up)");

        // Despite locating at base+1 (one level too deep), the up-walk still resolves correctly because
        // removeBubble purged the child keys: locate at base+1 → child key absent → walk to parent.
        for (int i = 0; i < 8; i++) {
            assertEquals(parentKey, grid.resolveLeafKey(tetree, centroid(children[i])),
                         "up-walk must resolve to the parent even with the watermark left at base+1");
        }
    }

    // -----------------------------------------------------------------------
    // E. Executor path: CollapseProposal is NOT fenced; fires a CollapseEvent
    // -----------------------------------------------------------------------

    @Test
    void collapse_viaExecutor_notFenced_firesCollapseEvent() {
        var children = refineWithEntities((byte) 2, 4);  // 32 entities
        var parentKey = parentTetAtLevel((byte) 2).tmIndex();
        var executor = new TopologyExecutor(bubbleGrid, accountant, metrics);
        executor.setClock(CLOCK);
        var captured = new CopyOnWriteArrayList<TopologyEvent>();
        executor.addListener(captured::add);

        var proposal = proposalAnchoredOn(children.get(0).id());
        var execResult = executor.execute(proposal);

        assertTrue(execResult.success(), "executor must run the collapse (not fenced): " + execResult.message());
        assertEquals(execResult.entitiesBefore(), execResult.entitiesAfter(), "conservation: before == after");

        var collapseEvents = captured.stream().filter(e -> e instanceof CollapseEvent).toList();
        assertEquals(1, collapseEvents.size(), "exactly one CollapseEvent must be fired");
        var event = (CollapseEvent) collapseEvents.get(0);
        assertTrue(event.success(), "CollapseEvent must report success");
        assertEquals(8, event.childBubbleIds().size(), "event must carry the 8 collapsed children");
        assertEquals(32, event.entitiesMoved(), "event must report 32 entities moved");

        assertTrue(bubbleGrid.containsBubble(parentKey), "parent must be registered after executor collapse");
        assertTrue(accountant.validate().success(), "accountant valid after executor collapse");
    }

    // -----------------------------------------------------------------------
    // F. Round trip: split then collapse conserves entities and restores a single leaf
    // -----------------------------------------------------------------------

    @Test
    void collapse_inverseOfSplit_roundTripConservesEntities() {
        // Build a level-2 source leaf with entities at its child centroids, split it into 8, then
        // collapse the 8 back — the AC-3 split→merge composition in miniature.
        var sourceTet = parentTetAtLevel((byte) 2);
        var sourceKey = sourceTet.tmIndex();
        var source = new EnhancedBubble(UUID.randomUUID(), (byte) 2, 10L);
        bubbleGrid.addBubble(source, sourceKey);
        var splitChildren = sourceTet.geometricSubdivide();
        for (int i = 0; i < 5100; i++) {
            var here = centroid(splitChildren[i % 8]);
            var id = UUID.randomUUID();
            source.addEntity(id.toString(), here, null);
            accountant.register(source.id(), id);
        }
        int totalEntities = accountant.entitiesInBubble(source.id()).size();
        assertEquals(5100, totalEntities);

        var splitter = new BubbleSplitter(bubbleGrid, accountant, OperationTracker.NOOP, metrics);
        var plane = new SplitPlane(new Point3f(1f, 0f, 0f), 0f);
        var splitResult = splitter.execute(new SplitProposal(
            UUID.randomUUID(), source.id(), plane, DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis()));
        assertTrue(splitResult.success(), "split must succeed: " + splitResult.message());
        assertEquals(8, splitResult.childBubbleIds().size());
        assertFalse(bubbleGrid.containsBubble(sourceKey), "source leaf gone after split");

        // Now collapse the 8 split children back into the parent.
        var anchorId = splitResult.childBubbleIds().get(0);
        var collapseResult = merger.executeCollapse(proposalAnchoredOn(anchorId));
        assertTrue(collapseResult.success(), "collapse must succeed: " + collapseResult.message());

        // The parent leaf is restored, the 8 children are gone, and all 5100 entities are conserved.
        assertTrue(bubbleGrid.containsBubble(sourceKey), "parent (== original source) key restored after collapse");
        var parentId = collapseResult.parentBubbleId();
        assertEquals(5100, accountant.entitiesInBubble(parentId).size(), "all 5100 entities conserved in the parent");
        assertEquals(5100, collapseResult.entitiesMoved(), "collapse moved all 5100 entities");
        for (var childId : splitResult.childBubbleIds()) {
            assertNull(bubbleGrid.getBubbleById(childId), "split child must be gone after collapse");
        }
        assertTrue(accountant.validate().success(), "accountant valid after split→collapse round trip");
    }

    // -----------------------------------------------------------------------
    // G. Move-failure mid-loop → fail-fast rollback, NO grid change, conservation restored
    // -----------------------------------------------------------------------

    @Test
    void collapse_moveFailureMidLoop_rollsBackEntities_noGridChange() {
        // Wire an accountant that fails the 11th child→parent move so the move loop aborts partway.
        var childIds = new java.util.HashSet<UUID>();
        var failingAcc = new FailAfterNCollapseMovesAccountant(11, childIds);
        var grid = new TetreeBubbleGrid((byte) 5);
        var mrg = new BubbleMerger(grid, failingAcc, OperationTracker.NOOP, new TopologyMetrics());

        var parentTet = parentTetAtLevel((byte) 2);
        var parentKey = parentTet.tmIndex();
        var children  = parentTet.geometricSubdivide();
        var childBubbles = new ArrayList<EnhancedBubble>(8);
        for (var child : children) {
            var b = new EnhancedBubble(UUID.randomUUID(), (byte) 3, 10L);
            grid.addBubble(b, child.tmIndex());
            childIds.add(b.id());
            childBubbles.add(b);
            var here = centroid(child);
            for (int e = 0; e < 4; e++) {                  // 8 × 4 = 32 entities; failure at move #11
                var id = UUID.randomUUID();
                b.addEntity(id.toString(), here, null);
                failingAcc.register(b.id(), id);
            }
        }
        int totalBefore = failingAcc.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(32, totalBefore);
        var perChildBefore = new ArrayList<Integer>();
        for (var b : childBubbles) {
            perChildBefore.add(failingAcc.entitiesInBubble(b.id()).size());
        }

        var result = mrg.executeCollapse(new CollapseProposal(
            UUID.randomUUID(), childBubbles.get(0).id(), DigestAlgorithm.DEFAULT.getOrigin(),
            CLOCK.currentTimeMillis()));

        assertFalse(result.success(), "collapse must fail when a move fails");
        assertTrue(result.message().contains("moveBetweenBubbles failed"),
                   "failure must name the move failure; got: " + result.message());

        // NO grid structural change: the parent is NOT registered, all 8 children still present.
        assertFalse(grid.containsBubble(parentKey), "parent must NOT be registered after a failed collapse");
        for (var b : childBubbles) {
            assertNotNull(grid.getBubbleById(b.id()), "child " + b.id() + " must still be in the grid");
        }
        // Conservation restored: every child's accountant count is back to its original value,
        // total unchanged, and the accountant is valid (no orphan stranded on the parent).
        int totalAfter = failingAcc.getDistribution().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(totalBefore, totalAfter, "total entity count must be unchanged after rollback");
        for (int i = 0; i < 8; i++) {
            assertEquals(perChildBefore.get(i), failingAcc.entitiesInBubble(childBubbles.get(i).id()).size(),
                         "child " + i + " entity count must be restored after rollback");
        }
        assertTrue(failingAcc.validate().success(), "accountant must be valid after move-failure rollback");
    }

    // -----------------------------------------------------------------------
    // H. Executor LIFO rollback: collapse succeeds internally but executor-level validate fails
    // -----------------------------------------------------------------------

    @Test
    void collapse_executorValidateFailsAfterSuccess_lifoRollbackRestoresChildren() {
        // Accountant fails the 2nd validate() call: executeCollapse's internal validate is call #1
        // (passes), the executor's post-operation validate is call #2 (fails), triggering the
        // executor's LIFO rollback of the 8 BubbleRemoved + 1 BubbleAdded operations.
        var failingAcc = new FailOnNthValidateAccountant(2);
        var grid = new TetreeBubbleGrid((byte) 5);
        var executor = new TopologyExecutor(grid, failingAcc, metrics);
        executor.setClock(CLOCK);

        var parentTet = parentTetAtLevel((byte) 2);
        var parentKey = parentTet.tmIndex();
        var children  = parentTet.geometricSubdivide();
        var childBubbles = new ArrayList<EnhancedBubble>(8);
        var childKeys = new ArrayList<TetreeKey<?>>(8);
        for (var child : children) {
            var b = new EnhancedBubble(UUID.randomUUID(), (byte) 3, 10L);
            grid.addBubble(b, child.tmIndex());
            childBubbles.add(b);
            childKeys.add(child.tmIndex());
            var here = centroid(child);
            for (int e = 0; e < 3; e++) {
                var id = UUID.randomUUID();
                b.addEntity(id.toString(), here, null);
                failingAcc.register(b.id(), id);
            }
        }
        var snapshotBefore = new java.util.HashMap<UUID, java.util.Set<UUID>>();
        for (var bId : failingAcc.getDistribution().keySet()) {
            snapshotBefore.put(bId, failingAcc.entitiesInBubble(bId));
        }

        var result = executor.execute(new CollapseProposal(
            UUID.randomUUID(), childBubbles.get(0).id(), DigestAlgorithm.DEFAULT.getOrigin(),
            CLOCK.currentTimeMillis()));

        assertFalse(result.success(), "executor must return failure when post-operation validate fails");

        // LIFO rollback must have re-added the 8 children and removed the parent.
        assertFalse(grid.containsBubble(parentKey), "parent must be removed by LIFO rollback");
        for (int i = 0; i < 8; i++) {
            assertTrue(grid.containsBubble(childKeys.get(i)),
                       "child key " + i + " must be re-registered by LIFO rollback");
        }
        // Every entity resolves to a LIVE bubble (none stranded on the removed parent).
        var liveIds = grid.getAllBubbles().stream().map(EnhancedBubble::id)
                          .collect(java.util.stream.Collectors.toSet());
        for (var entry : snapshotBefore.entrySet()) {
            for (var entityId : entry.getValue()) {
                var loc = failingAcc.getLocationOfEntity(entityId);
                assertNotNull(loc, "entity " + entityId + " must have a bubble after rollback");
                assertTrue(liveIds.contains(loc),
                           "entity " + entityId + " must resolve to a live bubble, not the removed parent");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Failure-injection accountants (delegating subclasses; state lives in super)
    // -----------------------------------------------------------------------

    /** Fails the child→parent forward move once {@code failAfter} forward moves have succeeded. */
    static final class FailAfterNCollapseMovesAccountant extends EntityAccountant {
        private final int failAfter;
        private final java.util.Set<UUID> childIds;          // populated by the test before executeCollapse
        private final java.util.concurrent.atomic.AtomicInteger forwardSuccesses =
            new java.util.concurrent.atomic.AtomicInteger(0);

        FailAfterNCollapseMovesAccountant(int failAfter, java.util.Set<UUID> childIds) {
            this.failAfter = failAfter;
            this.childIds  = childIds;
        }

        @Override
        public boolean moveBetweenBubbles(UUID entityId, UUID fromBubble, UUID toBubble) {
            // Forward (collapse) move: child → parent. Parent id is not in the child set.
            boolean forward = childIds.contains(fromBubble) && !childIds.contains(toBubble);
            if (forward && forwardSuccesses.get() >= failAfter) {
                return false; // inject failure
            }
            boolean ok = super.moveBetweenBubbles(entityId, fromBubble, toBubble);
            if (ok && forward) {
                forwardSuccesses.incrementAndGet();
            }
            return ok;
        }
    }

    /** Returns a synthetic validation failure on the {@code failOn}-th validate() call. */
    static final class FailOnNthValidateAccountant extends EntityAccountant {
        private final int failOn;
        private final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger(0);

        FailOnNthValidateAccountant(int failOn) {
            this.failOn = failOn;
        }

        @Override
        public com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult validate() {
            if (calls.incrementAndGet() == failOn) {
                return new com.hellblazer.luciferase.simulation.distributed.integration.EntityValidationResult(
                    false, 1, List.of("synthetic collapse executor-level validate failure"));
            }
            return super.validate();
        }
    }
}
