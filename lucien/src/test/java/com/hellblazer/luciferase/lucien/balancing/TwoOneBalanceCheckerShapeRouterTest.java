/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.pyramid.PyramidIndex;
import com.hellblazer.luciferase.lucien.pyramid.PyramidKey;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.6 Phase C (Approach §4d): {@link TwoOneBalanceChecker} must route non-Morton ghost keys
 * (TetreeKey, PyramidKey) through the shape's
 * {@link com.hellblazer.luciferase.lucien.neighbor.NeighborDetector} instead of silently skipping them.
 *
 * <p><b>Non-vacuous:</b> on the pre-pi1.6 checker (MortonKey-only {@code instanceof}), every positive
 * assertion here returns zero violations — the silent-skip gap the RDR flags. Each test seeds a real
 * ghost-fine / local-coarse arrangement (level difference 2) and asserts the violation is DETECTED.
 *
 * <p>Construction: insert one local coarse element at level 1 (its key {@code Kc}); insert several
 * <em>remote</em> fine elements at level 3 in the same region; pick a remote fine key whose detector
 * face-neighbor has {@code Kc} as its level-1 grandparent. The router walks that neighbor's parent chain
 * to {@code Kc} (local), levels 3 vs 1 → diff 2 → violation.
 */
class TwoOneBalanceCheckerShapeRouterTest {

    private static GhostLayer<TetreeKey<?>, LongEntityID, String> tetGhostLayer(TetreeKey<?> ghostKey, int rank) {
        var layer = new GhostLayer<TetreeKey<?>, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(new GhostElement<>(ghostKey, new LongEntityID(1), "g",
                                                 new Point3f(0, 0, 0), rank, 0L));
        return layer;
    }

    private static GhostLayer<PyramidKey, LongEntityID, String> pyrGhostLayer(PyramidKey ghostKey, int rank) {
        var layer = new GhostLayer<PyramidKey, LongEntityID, String>(GhostType.FACES);
        layer.addGhostElement(new GhostElement<>(ghostKey, new LongEntityID(1), "g",
                                                 new Point3f(0, 0, 0), rank, 0L));
        return layer;
    }

    @Test
    void detectsViolationOnTetreeKeyGhostNeighbor() {
        var local = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        var forest = new Forest<TetreeKey<?>, LongEntityID, String>();
        forest.addTree(local);
        var detector = local.getNeighborDetector();
        assertNotNull(detector, "Tetree must wire a neighbor detector");

        // Local coarse node at level 1.
        var base = new Point3f(300.5f, 250.5f, 100.5f);
        local.insert(base, (byte) 1, "local");
        TetreeKey<?> kc = firstAtLevel(local.getSpatialKeys(), 1);
        assertNotNull(kc, "expected a local level-1 coarse key");

        // Remote fine cells (level 3) INSIDE Kc (grandparent == Kc), then take their face neighbors as
        // ghost candidates: a Bey face neighbor of an inside-Kc cell sits in a different level-1 cell, but
        // by reciprocity the inside-Kc cell is one of ITS face neighbors — so the router, probing the
        // ghost's neighbors, walks the inside-Kc cell's parent chain to the local Kc.
        var remote = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        TetreeKey<?> ghostKey = pickGhostWithNeighborUnderCoarse(remote, detector, local, base, kc);
        assertNotNull(ghostKey, "expected a fine ghost whose face-neighbor descends from the local coarse key");

        var checker = new TwoOneBalanceChecker<TetreeKey<?>, LongEntityID, String>();
        var violations = checker.findViolations(tetGhostLayer(ghostKey, 7), forest);

        assertFalse(violations.isEmpty(), "TetreeKey ghost must produce a balance violation (no silent skip)");
        final var gk = ghostKey;
        assertTrue(violations.stream().anyMatch(v -> v.ghostKey().equals(gk) && v.localKey().equals(kc)
                                                     && v.levelDifference() == 2 && v.sourceRank() == 7),
                   "violation must name the coarse local + fine ghost with diff 2 and the ghost's rank");
    }

    @Test
    void detectsViolationOnPyramidKeyGhostNeighbor() {
        var local = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        var forest = new Forest<PyramidKey, LongEntityID, String>();
        forest.addTree(local);
        var detector = local.getNeighborDetector();
        assertNotNull(detector, "PyramidIndex must wire a neighbor detector");

        var base = new Point3f(300.5f, 250.5f, 100.5f);
        local.insert(base, (byte) 1, "local");
        PyramidKey kc = firstAtLevel(local.getSpatialKeys(), 1);
        assertNotNull(kc, "expected a local level-1 coarse key");

        var remote = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        PyramidKey ghostKey = pickGhostWithNeighborUnderCoarse(remote, detector, local, base, kc);
        assertNotNull(ghostKey, "expected a fine pyramid ghost whose face-neighbor descends from the local coarse");

        var checker = new TwoOneBalanceChecker<PyramidKey, LongEntityID, String>();
        var violations = checker.findViolations(pyrGhostLayer(ghostKey, 5), forest);

        assertFalse(violations.isEmpty(), "PyramidKey ghost must produce a balance violation (no silent skip)");
        final var gk = ghostKey;
        assertTrue(violations.stream().anyMatch(v -> v.ghostKey().equals(gk) && v.localKey().equals(kc)
                                                     && v.levelDifference() == 2),
                   "violation must name the coarse local pyramid + fine ghost with diff 2");
    }

    @Test
    void noFalsePositiveOnEmptyLocalTetree() {
        // Ghost with no local neighbors at all → no violations (guards against spurious detection).
        var local = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        var forest = new Forest<TetreeKey<?>, LongEntityID, String>();
        forest.addTree(local); // empty: nothing local to violate against

        var remote = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        remote.insert(new Point3f(10.5f, 8.5f, 3.5f), (byte) 3, "f");
        var ghostKey = remote.getSpatialKeys().iterator().next();

        var checker = new TwoOneBalanceChecker<TetreeKey<?>, LongEntityID, String>();
        var violations = checker.findViolations(tetGhostLayer(ghostKey, 1), forest);
        assertTrue(violations.isEmpty(), "no local elements → no balance violations");
    }

    /**
     * Find a level-3 ghost key whose detector face-neighbor set contains a cell descending (grandparent)
     * from the local coarse key {@code kc}. Seeds {@code remote} with fine cells inside Kc's region, then
     * uses their face neighbors (which sit outside Kc) as ghost candidates — the router, probing such a
     * candidate's neighbors, walks the inside-Kc cell's parent chain back to the local {@code kc}.
     */
    private static <K extends com.hellblazer.luciferase.lucien.SpatialKey<K>>
    K pickGhostWithNeighborUnderCoarse(
        com.hellblazer.luciferase.lucien.SpatialIndex<K, LongEntityID, String> remote,
        com.hellblazer.luciferase.lucien.neighbor.NeighborDetector<K> detector,
        com.hellblazer.luciferase.lucien.SpatialIndex<K, LongEntityID, String> local,
        Point3f base, K kc) {
        for (int i = 0; i < 40; i++) {
            remote.insert(new Point3f(base.x + i, base.y + (i % 7), base.z + (i % 5)), (byte) 3, "f" + i);
        }
        for (var inside : remote.getSpatialKeys()) {
            if (inside.getLevel() != 3 || !isGrandchildOf(inside, kc)) {
                continue;
            }
            for (var candidate : detector.findFaceNeighbors(inside)) {
                if (candidate.getLevel() != 3 || local.containsSpatialKey(candidate)) {
                    continue;
                }
                if (hasGrandparent(detector.findFaceNeighbors(candidate), kc)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static <K extends com.hellblazer.luciferase.lucien.SpatialKey<K>> boolean isGrandchildOf(
        K key, K coarse) {
        var p = key.parent();
        if (p == null) {
            return false;
        }
        var gp = p.parent();
        return gp != null && gp.equals(coarse);
    }

    @Test
    void coarseGhostWithFinerLocalIsNotProbedLocally() {
        // Documents the locked "Morton-behavior" scope (RDR-010 pi1.6): the detector router probes only
        // the COARSER direction. A COARSE ghost (level 1) adjacent to FINER local elements (level 3) is
        // NOT detected locally — by design it is caught from the partition owning the finer elements.
        var local = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        var forest = new Forest<TetreeKey<?>, LongEntityID, String>();
        forest.addTree(local);

        var base = new Point3f(300.5f, 250.5f, 100.5f);
        for (int i = 0; i < 10; i++) {
            local.insert(new Point3f(base.x + i, base.y + (i % 7), base.z + (i % 5)), (byte) 3, "fine" + i);
        }
        assertFalse(local.getSpatialKeys().isEmpty(), "precondition: local has fine elements");

        var remote = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        remote.insert(base, (byte) 1, "coarseGhost");
        var coarseGhost = firstAtLevel(remote.getSpatialKeys(), 1);
        assertNotNull(coarseGhost);

        var checker = new TwoOneBalanceChecker<TetreeKey<?>, LongEntityID, String>();
        var violations = checker.findViolations(tetGhostLayer(coarseGhost, 2), forest);
        assertTrue(violations.isEmpty(),
                   "coarse ghost / finer local is intentionally NOT probed locally (Morton-behavior scope)");
    }

    private static <K extends com.hellblazer.luciferase.lucien.SpatialKey<K>> K firstAtLevel(
        java.util.Set<K> keys, int level) {
        for (var k : keys) {
            if (k.getLevel() == level) {
                return k;
            }
        }
        return null;
    }

    private static <K extends com.hellblazer.luciferase.lucien.SpatialKey<K>> boolean hasGrandparent(
        java.util.List<K> neighbors, K target) {
        for (var n : neighbors) {
            var p = n.parent();
            if (p == null) {
                continue;
            }
            var gp = p.parent();
            if (gp != null && gp.equals(target)) {
                return true;
            }
        }
        return false;
    }
}
