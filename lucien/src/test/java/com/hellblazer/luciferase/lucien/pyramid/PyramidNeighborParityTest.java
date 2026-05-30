/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.neighbor.NeighborDetector;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dedicated parity suite for the pi1.4 PyramidNeighborDetector seam (RDR-010 pi1.4 Phase C, bead
 * Luciferase-3zs). Follows the Prism/Pyramid convention of a peer-index dedicated suite — PyramidIndex
 * and its detector are NOT registered in the shared {@code SpatialIndex*} parameterized providers.
 *
 * <p>Unlike {@link PyramidNeighborDetectorTest} (which pins specific properties on a single fixture),
 * this suite sweeps the whole level-≤5 SFC pyramid tree to exercise face-neighbor reciprocity across
 * many elements and levels, and verifies the {@code GhostCoordinator} seam is effectively wired (a
 * call THROUGH {@code getNeighborDetector()} returns a real same-shape neighbor, not the Phase-A
 * fail-loud stub).
 *
 * @author hal.hildebrand
 */
class PyramidNeighborParityTest {

    private PyramidIndex<LongEntityID, String> index;
    private PyramidNeighborDetector detector;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
        detector = new PyramidNeighborDetector(index);
    }

    private static List<Pyramid> validPyramids(int maxLevel) {
        var out = new ArrayList<Pyramid>();
        for (var root : new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                        new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) }) {
            descend(root, maxLevel, out);
        }
        return out;
    }

    private static void descend(Pyramid p, int maxLevel, List<Pyramid> out) {
        if (p.level() >= 1) {
            out.add(p);
        }
        if (p.level() >= maxLevel) {
            return;
        }
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            HybridElement child = p.child(i);
            if (child instanceof Pyramid pc) {
                descend(pc, maxLevel, out);
            }
        }
    }

    /** First interior SFC pyramid whose quad-base neighbor is also an in-domain SFC element. */
    private static Pyramid[] selfAndQuadBaseNeighbor() {
        for (var p : validPyramids(5)) {
            var fn = p.faceNeighbor(4);
            if (fn == null || !(fn.element() instanceof Pyramid pn)) {
                continue;
            }
            if (PyramidKeyCodec.encode(p) != null && PyramidKeyCodec.encode(pn) != null) {
                return new Pyramid[] { p, pn };
            }
        }
        return null;
    }

    @Test
    void faceNeighborReciprocityAcrossTheRefinedTree() {
        int checked = 0;
        int crossParent = 0;
        for (var p : validPyramids(5)) {
            var fn = p.faceNeighbor(4);
            if (fn == null || !(fn.element() instanceof Pyramid pn)) {
                continue;
            }
            var pk = PyramidKeyCodec.encode(p);
            var nk = PyramidKeyCodec.encode(pn);
            if (pk == null || nk == null) {
                continue;
            }
            assertTrue(detector.findFaceNeighbors(pk).contains(nk),
                       "detector must find the quad-base neighbor of " + p);
            assertTrue(detector.findFaceNeighbors(nk).contains(pk),
                       "face-neighbor relation must be reciprocal for " + p + " <-> " + pn);
            checked++;
            if (pk.parent() != null && nk.parent() != null && !pk.parent().equals(nk.parent())) {
                crossParent++;
            }
        }
        assertTrue(checked >= 20, "expected a broad multi-level sweep, only checked " + checked);
        assertTrue(crossParent >= 1, "sweep must include cross-parent f4 pairs (the graduation target)");
    }

    @Test
    void edgeNeighborReciprocityAcrossTheRefinedTree() {
        reciprocitySweep(detector::findEdgeNeighbors, "edge");
    }

    @Test
    void vertexNeighborReciprocityAcrossTheRefinedTree() {
        reciprocitySweep(detector::findVertexNeighbors, "vertex");
    }

    /**
     * Sweep the level-≤5 SFC pyramid tree and assert the chosen neighbor relation is symmetric: every
     * neighbor {@code nk} of {@code pk} lists {@code pk} as one of its neighbors. Catches a tree-level
     * regression (a dropped or spurious neighbor) that single-fixture tests would miss.
     */
    private void reciprocitySweep(java.util.function.Function<PyramidKey, List<PyramidKey>> bucket,
                                  String label) {
        int checked = 0;
        for (var p : validPyramids(5)) {
            var pk = PyramidKeyCodec.encode(p);
            if (pk == null) {
                continue;
            }
            for (var nk : bucket.apply(pk)) {
                assertTrue(bucket.apply(nk).contains(pk),
                           label + "-neighbor relation must be reciprocal for " + pk + " -> " + nk);
                checked++;
            }
        }
        assertTrue(checked >= 20, "expected a broad " + label + " sweep, only checked " + checked);
    }

    @Test
    void ghostCoordinatorSeamWiresTheRealDetector() {
        NeighborDetector<PyramidKey> wired = index.getNeighborDetector();
        assertInstanceOf(PyramidNeighborDetector.class, wired,
                         "the GhostCoordinator seam must hold the real pyramid detector");

        var pair = selfAndQuadBaseNeighbor();
        assertNotNull(pair);
        var selfKey = PyramidKeyCodec.encode(pair[0]);
        var neighborKey = PyramidKeyCodec.encode(pair[1]);

        // Effective wiring: a call THROUGH the seam returns the real cross-shape neighbor set.
        var faces = wired.findFaceNeighbors(selfKey);
        assertTrue(faces.contains(neighborKey), "seam must yield the real f4 pyramid neighbor");

        // pi1.5 (honesty-trap pin rewrite): the four triangular faces now contribute cross-shape tet
        // neighbors, so GhostType.FACES yields MORE than one neighbor for a pyramid whose triangular
        // faces are in-domain SFC tets. This pins the cross-shape graduation in test output (was: == 1).
        var ghostFaces = wired.findNeighbors(selfKey, GhostType.FACES);
        assertTrue(ghostFaces.size() > 1,
                   "cross-shape: a pyramid's triangular faces now add tet ghost entries (was 1 in pi1.4)");
        // At least one ghost-face neighbor must be a tet (the cross-shape contribution).
        assertTrue(ghostFaces.stream().anyMatch(k -> PyramidIndex.elementFromKey(k) instanceof com.hellblazer.luciferase.lucien.tetree.Tet),
                   "GhostType.FACES must include at least one cross-shape tet neighbor");
    }

    @Test
    void crossShapeFaceNeighborReciprocityAcrossTheRefinedTree() {
        // BFS-symmetry guard (pi1.4 critic forward-flag): a pyramid's triangular-face tet neighbor must
        // list the pyramid back, and vice versa — a one-sided union would silently miss ghost entities.
        int checkedPyrToTet = 0;
        int checkedTetToPyr = 0;
        for (var p : validPyramids(5)) {
            var pk = PyramidKeyCodec.encode(p);
            if (pk == null) {
                continue;
            }
            for (int f = 0; f < 4; f++) {
                var fn = p.faceNeighbor(f);
                if (fn == null || !(fn.element() instanceof com.hellblazer.luciferase.lucien.tetree.Tet t)) {
                    continue;
                }
                var tk = PyramidKeyCodec.encode(t);
                if (tk == null) {
                    continue;
                }
                // pyramid -> tet present, and reciprocally tet -> pyramid present.
                assertTrue(detector.findFaceNeighbors(pk).contains(tk),
                           "pyramid " + p + " must report its triangular-face tet neighbor " + t);
                assertTrue(detector.findFaceNeighbors(tk).contains(pk),
                           "tet " + t + " must report its pyramid back (BFS symmetry) for " + p);
                checkedPyrToTet++;
                checkedTetToPyr++;
            }
        }
        assertTrue(checkedPyrToTet >= 5, "expected a broad cross-shape sweep, only checked " + checkedPyrToTet);
        assertTrue(checkedTetToPyr >= 5, "cross-shape reciprocity must be exercised both directions");
    }
}
