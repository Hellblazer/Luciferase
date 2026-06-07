/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-018 AC-6 (Luciferase-wu7vn): cross-level ghost-neighbour resolution over the Option B refinement
 * forest, plus neighbour-cache invalidation on a leaf-set change.
 * <p>
 * lucien's {@code TetreeNeighborDetector.findVertexNeighbors} returns neighbour keys at the query key's
 * OWN level only. In a mixed-level forest that is insufficient: a refined level-{@code (L+1)} child leaf's
 * same-level neighbour keys never include its coarser level-{@code L} neighbour (which is registered one
 * level up), and a coarse level-{@code L} leaf's same-level neighbour key for a refined region is no longer
 * registered (the split removed it). {@link TetreeBubbleGrid#resolveAdjacentLeafKeys} bridges both
 * directions by resolving each geometric neighbour key to the actual registered leaf — up-walking to a
 * coarser ancestor, or collecting registered finer descendants — so a refined leaf's ghosts reach its
 * coarser neighbour and vice-versa (gate O2 / RQ-5b, AC-6 requirement 1).
 * <p>
 * Requirement 2 (cache invalidation on leaf-set change) is already met by {@code addBubble}/{@code
 * removeBubble} calling {@code neighborFinder.clearCache()}; {@link #cacheInvalidated_afterRefine_staleParentNotReturned()}
 * pins it (the stale parent neighbour is gone, the children appear).
 * <p>
 * <b>Explicit scope boundary (AC-7):</b> {@code TetreeGhostSyncAdapter} builds its per-bubble ghost
 * infrastructure ({@code ghostSyncByBubble} / {@code ghostsByBubble}) at construction time. Two
 * consequences of a split/merge DURING a live run are out of scope here and tracked on Luciferase-xtyki:
 * (a) bubbles created by a split have no ghost infrastructure, so they neither send nor receive ghosts
 * until the adapter is rebuilt; (b) the ghost store keyed by a removed bubble's UUID is not reclaimed
 * (a stale entry, drained only by TTL; a ghost targeting it is dropped with a warning). Both are benign
 * today because split/merge are not on the live tick path (RDR-018 F1 / RDR-012 D2) — there is no
 * live-split ghost workload. The topological half of the AC-3 Obs3 carry-forward (no stale removed-bubble
 * key is ever returned as a ghost neighbour) IS covered here by
 * {@link #cacheInvalidated_afterRefine_staleParentNotReturned()}; the ghost-STORE consistency half is the
 * xtyki boundary above. These tests construct the adapter over the already-refined grid, validating the
 * neighbour-resolution + delivery path AC-6 adds.
 *
 * @author hal.hildebrand
 */
class TetreeGhostCrossLevelTest {

    private static final WorldBounds WORLD = new WorldBounds(0.0f, 100.0f);

    private static TetreeBubbleGrid partitionGrid() {
        var grid = new TetreeBubbleGrid((byte) 21);
        grid.createBubbles(8, WORLD, 10L);
        return grid;
    }

    /** Pick a base leaf (S) that has a registered reciprocal face-neighbour (Ncoarse). */
    private record AdjacentPair(TetreeKey<?> s, Tet sTet, TetreeKey<?> nCoarse, Tet nCoarseTet) {}

    private static AdjacentPair findAdjacentBaseLeaves(TetreeBubbleGrid grid) {
        for (var key : grid.getBubblesWithKeys().keySet()) {
            var sTet = Tet.tetrahedron(key);
            for (int face = 0; face < 4; face++) {
                var fn = sTet.faceNeighbor(face);
                if (fn == null) {
                    continue;
                }
                var nb   = fn.tet();
                var back = nb.faceNeighbor(fn.face());
                // Involution reciprocity, never shared-vertex count (non-conforming Bey-SFC faces).
                if (back != null && sTet.equals(back.tet()) && grid.containsBubble(nb.tmIndex())) {
                    return new AdjacentPair(key, sTet, nb.tmIndex(), nb);
                }
            }
        }
        return null;
    }

    /** Refine base leaf S into its 8 Bey children (remove parent, add 8 L+1 children). Returns child keys. */
    private static TetreeKey<?>[] refine(TetreeBubbleGrid grid, AdjacentPair p, byte base) {
        var children = p.sTet().geometricSubdivide();
        grid.removeBubble(grid.getBubble(p.s()).id());
        TetreeKey<?>[] childKeys = new TetreeKey[8];
        for (int i = 0; i < 8; i++) {
            childKeys[i] = children[i].tmIndex();
            grid.addBubble(new EnhancedBubble(UUID.randomUUID(), (byte) (base + 1), 10L), childKeys[i]);
        }
        return childKeys;
    }

    // -----------------------------------------------------------------------
    // 1. Refined leaf → coarser neighbour: at least one L+1 child resolves the coarse L neighbour.
    // -----------------------------------------------------------------------

    @Test
    void refinedChild_resolvesCoarserNeighbour() {
        var grid = partitionGrid();
        byte base = grid.getBaseLevel();
        var pair = findAdjacentBaseLeaves(grid);
        assertNotNull(pair, "setup: need a base leaf with a registered reciprocal face-neighbour");

        var childKeys = refine(grid, pair, base);

        // Every adjacent key returned must be a registered leaf; the removed parent must never appear.
        var unionAdjacent = new HashSet<TetreeKey<?>>();
        for (var ck : childKeys) {
            var adj = grid.resolveAdjacentLeafKeys(ck);
            for (var a : adj) {
                assertTrue(grid.containsBubble(a), "resolved adjacent key must be a registered leaf: " + a);
                assertNotEquals(pair.s(), a, "the removed parent leaf must never be returned");
            }
            unionAdjacent.addAll(adj);
        }
        assertTrue(unionAdjacent.contains(pair.nCoarse()),
                   "a refined child must resolve the coarser registered neighbour (cross-level up-walk)");
    }

    // -----------------------------------------------------------------------
    // 2. Coarse leaf → refined neighbours: the coarse neighbour resolves the child leaves, not the
    //    removed parent.
    // -----------------------------------------------------------------------

    @Test
    void coarseNeighbour_resolvesRefinedChildren() {
        var grid = partitionGrid();
        byte base = grid.getBaseLevel();
        var pair = findAdjacentBaseLeaves(grid);
        assertNotNull(pair, "setup: need a base leaf with a registered reciprocal face-neighbour");

        var childKeys = refine(grid, pair, base);
        var childSet  = new HashSet<TetreeKey<?>>();
        for (var ck : childKeys) {
            childSet.add(ck);
        }

        var adj = grid.resolveAdjacentLeafKeys(pair.nCoarse());
        for (var a : adj) {
            assertTrue(grid.containsBubble(a), "resolved adjacent key must be a registered leaf: " + a);
            assertNotEquals(pair.s(), a, "the removed parent leaf must never be returned");
        }
        boolean reachesAChild = adj.stream().anyMatch(childSet::contains);
        assertTrue(reachesAChild,
                   "the coarse neighbour must resolve at least one refined child leaf (cross-level down-resolve)");
    }

    // -----------------------------------------------------------------------
    // 3. Uniform grid: cross-level resolution degenerates to the registered same-level neighbours.
    // -----------------------------------------------------------------------

    @Test
    void uniformGrid_resolvesRegisteredSameLevelNeighbours() {
        var grid = partitionGrid();
        byte base = grid.getBaseLevel();

        for (var key : grid.getBubblesWithKeys().keySet()) {
            var adj = grid.resolveAdjacentLeafKeys(key);
            assertFalse(adj.contains(key), "a leaf is never its own neighbour");
            for (var a : adj) {
                assertTrue(grid.containsBubble(a), "resolved adjacent key must be registered");
                assertEquals(base, a.getLevel(), "in a uniform grid all neighbours are at the base level");
            }
            // Must agree with the registered subset of the raw same-level topological neighbours.
            var expected = new HashSet<TetreeKey<?>>();
            for (var nk : grid.getNeighborFinder().findNeighbors(key)) {
                if (grid.containsBubble(nk)) {
                    expected.add(nk);
                }
            }
            assertEquals(expected, adj, "uniform-grid resolution must equal the registered same-level neighbours");
        }
    }

    // -----------------------------------------------------------------------
    // 4. Cache invalidation on leaf-set change (gate O2): after refining S, the coarse neighbour no
    //    longer resolves the removed parent S, and now resolves S's children — proving the key-keyed
    //    neighbour cache was invalidated by the split's add/removeBubble.
    // -----------------------------------------------------------------------

    @Test
    void cacheInvalidated_afterRefine_staleParentNotReturned() {
        var grid = partitionGrid();
        byte base = grid.getBaseLevel();
        var pair = findAdjacentBaseLeaves(grid);
        assertNotNull(pair, "setup: need a base leaf with a registered reciprocal face-neighbour");

        // Warm the cache: before the refine, the coarse neighbour resolves S (a registered same-level leaf).
        var before = grid.resolveAdjacentLeafKeys(pair.nCoarse());
        assertTrue(before.contains(pair.s()), "pre-refine: coarse neighbour must resolve the base leaf S");

        var childKeys = refine(grid, pair, base);
        var childSet  = new HashSet<TetreeKey<?>>();
        for (var ck : childKeys) {
            childSet.add(ck);
        }

        var after = grid.resolveAdjacentLeafKeys(pair.nCoarse());
        assertFalse(after.contains(pair.s()),
                    "post-refine: the stale removed parent S must NOT be returned (cache invalidated)");
        assertTrue(after.stream().anyMatch(childSet::contains),
                   "post-refine: the coarse neighbour must now resolve S's children");
    }

    // -----------------------------------------------------------------------
    // 5. Adapter end-to-end: a refined child's ghost-boundary neighbours include the coarser leaf, and
    //    the coarse leaf's include a refined child (the AC-6 "ghosts reach a coarser neighbour" goal).
    // -----------------------------------------------------------------------

    @Test
    void adapter_refinedChildAndCoarseNeighbour_areMutualBoundaryNeighbours() {
        var grid = partitionGrid();
        byte base = grid.getBaseLevel();
        var pair = findAdjacentBaseLeaves(grid);
        assertNotNull(pair, "setup: need a base leaf with a registered reciprocal face-neighbour");

        var childKeys = refine(grid, pair, base);

        // Fill each cell with its 4 vertices so adaptive bounds span the whole cell (face-adjacent cells'
        // AABBs overlap), making the bounds-overlap boundary filter fire for genuine topological neighbours.
        fillCellBounds(grid.getBubble(pair.nCoarse()), pair.nCoarseTet());
        var children = pair.sTet().geometricSubdivide();
        for (int i = 0; i < 8; i++) {
            fillCellBounds(grid.getBubble(childKeys[i]), children[i]);
        }

        var adapter = new TetreeGhostSyncAdapter(grid, grid.getNeighborFinder());
        var coarseId = grid.getBubble(pair.nCoarse()).id();

        // Guard against a vacuous pass: the cross-level adjacency set itself must be non-empty for the
        // children adjacent to the coarse cell (else the loop below would pass only because nothing resolved).
        boolean someChildHasCrossLevelAdjacency = false;
        for (var ck : childKeys) {
            if (grid.resolveAdjacentLeafKeys(ck).contains(pair.nCoarse())) {
                someChildHasCrossLevelAdjacency = true;
                break;
            }
        }
        assertTrue(someChildHasCrossLevelAdjacency,
                   "setup invariant: a child must topologically resolve the coarse neighbour (cross-level)");

        // At least one refined child must list the coarse neighbour as a boundary neighbour.
        boolean childReachesCoarse = false;
        for (var ck : childKeys) {
            if (adapter.findBoundaryNeighbors(grid.getBubble(ck)).contains(coarseId)) {
                childReachesCoarse = true;
                break;
            }
        }
        assertTrue(childReachesCoarse, "a refined child's ghost-boundary neighbours must include the coarse leaf");

        // Reciprocally, the coarse neighbour must list at least one refined child.
        var coarseNeighbours = adapter.findBoundaryNeighbors(grid.getBubble(pair.nCoarse()));
        var childIds = new HashSet<UUID>();
        for (var ck : childKeys) {
            childIds.add(grid.getBubble(ck).id());
        }
        assertTrue(coarseNeighbours.stream().anyMatch(childIds::contains),
                   "the coarse leaf's ghost-boundary neighbours must include a refined child");
    }

    // -----------------------------------------------------------------------
    // 6. Adapter DELIVERY end-to-end: a ghost entity placed in a refined child near the coarse-neighbour
    //    boundary is actually SENT to the coarse leaf's ghost store (not merely discovered as a neighbour).
    //    This exercises processBoundaryEntities → addGhost → ghostSender → ghostsByBubble for a cross-level
    //    pair — the spec's "a refined leaf's ghosts REACH a coarser neighbour".
    // -----------------------------------------------------------------------

    @Test
    void adapter_refinedChildGhostIsDeliveredToCoarseNeighbour() {
        var grid = partitionGrid();
        byte base = grid.getBaseLevel();
        var pair = findAdjacentBaseLeaves(grid);
        assertNotNull(pair, "setup: need a base leaf with a registered reciprocal face-neighbour");

        var childKeys = refine(grid, pair, base);
        var coarseBubble = grid.getBubble(pair.nCoarse());
        fillCellBounds(coarseBubble, pair.nCoarseTet());
        var children = pair.sTet().geometricSubdivide();
        for (int i = 0; i < 8; i++) {
            fillCellBounds(grid.getBubble(childKeys[i]), children[i]);
        }
        var coarseBounds = coarseBubble.bounds();
        assertNotNull(coarseBounds, "coarse cell must have bounds after fill");

        var adapter  = new TetreeGhostSyncAdapter(grid, grid.getNeighborFinder());
        var coarseId = coarseBubble.id();

        // Find a refined child that is a boundary neighbour of the coarse leaf, and a position inside that
        // child that also lies within the coarse cell's bounds (so the AOI proximity check fires).
        EnhancedBubble childAdj = null;
        Point3f        marker   = null;
        for (int i = 0; i < 8; i++) {
            var cb = grid.getBubble(childKeys[i]);
            if (!adapter.findBoundaryNeighbors(cb).contains(coarseId)) {
                continue;
            }
            for (var v : children[i].coordinates()) {
                var p = new Point3f(v.x, v.y, v.z);
                if (coarseBounds.contains(p)) {   // on/inside the coarse cell ⇒ within AOI of its bounds
                    childAdj = cb;
                    marker   = p;
                    break;
                }
            }
            if (childAdj != null) {
                break;
            }
        }
        assertNotNull(childAdj, "must find a refined child adjacent to the coarse leaf with a boundary-side point");

        var markedId = "marked-cross-level-ghost";
        childAdj.addEntity(markedId, marker, null);

        adapter.processBoundaryEntities(1L);
        adapter.onBucketComplete(1L);

        var sourceChild  = childAdj;   // effectively-final capture for the lambda
        var coarseGhosts = adapter.getGhostsForBubble(coarseId);
        assertTrue(coarseGhosts.stream().anyMatch(g -> g.entityId().toString().equals(markedId)
                                                       && g.isFromBubble(sourceChild.id())),
                   "the refined child's boundary entity must be DELIVERED as a ghost to the coarse neighbour");
    }

    private static void fillCellBounds(EnhancedBubble bubble, Tet tet) {
        for (var v : tet.coordinates()) {
            bubble.addEntity(UUID.randomUUID().toString(), new Point3f(v.x, v.y, v.z), null);
        }
    }
}
