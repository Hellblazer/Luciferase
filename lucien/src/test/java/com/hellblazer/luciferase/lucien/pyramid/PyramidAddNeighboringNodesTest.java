/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PyramidIndex.addNeighboringNodes (Phase E minimum contract, bead Luciferase-ioz).
 *
 * <p>Pi1.3 minimum contract: addNeighboringNodes must emit AT LEAST the SFC-adjacent
 * same-level PyramidKeys. Full same/cross-shape topology is deferred to pi1.4
 * (PyramidNeighborDetector). The test must verify that at least some neighbors are
 * produced (not silently empty), and that all emitted keys are valid (non-null, correct
 * level, not equal to the seed node).
 */
class PyramidAddNeighboringNodesTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    @Test
    void addNeighboringNodes_doesNotThrow() {
        var key = PyramidKey.getRoot();
        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(key);

        assertDoesNotThrow(() -> index.addNeighboringNodes(key, toVisit, visited),
                           "addNeighboringNodes must not throw UnsupportedOperationException (Phase E)");
    }

    @Test
    void addNeighboringNodes_emitsAtLeastOneNeighbor() {
        // Use a non-root key so there are guaranteed to be siblings
        // Level-1 keys have siblings (the other pyramid children of the root)
        var key = PyramidKey.getRoot();
        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(key);

        index.addNeighboringNodes(key, toVisit, visited);

        assertFalse(toVisit.isEmpty(),
                    "addNeighboringNodes must emit at least one SFC-adjacent neighbor (pi1.3 minimum contract)");
    }

    @Test
    void addNeighboringNodes_doesNotEmitSelfOrAlreadyVisited() {
        var key = PyramidKey.getRoot();
        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(key);

        index.addNeighboringNodes(key, toVisit, visited);

        assertFalse(toVisit.contains(key), "Must not re-emit the seed node");
        for (var neighbor : toVisit) {
            assertFalse(visited.contains(neighbor),
                        "Must not emit already-visited node: " + neighbor);
        }
    }

    @Test
    void addNeighboringNodes_emittedKeysAreValid() {
        var key = PyramidKey.getRoot();
        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(key);

        index.addNeighboringNodes(key, toVisit, visited);

        for (var neighbor : toVisit) {
            assertNotNull(neighbor, "Emitted neighbor keys must be non-null");
            assertTrue(neighbor.isValid(), "Emitted neighbor keys must pass isValid()");
        }
    }

    @Test
    void addNeighboringNodes_respectsAlreadyVisitedSet() {
        // Pre-populate visited with all possible neighbors to check none are re-emitted
        var key = PyramidKey.getRoot();
        var toVisit1 = new LinkedList<PyramidKey>();
        var visited1 = new HashSet<PyramidKey>();
        visited1.add(key);
        index.addNeighboringNodes(key, toVisit1, visited1);

        // Now mark all those as visited and call again
        var allNeighbors = new HashSet<PyramidKey>(toVisit1);
        var visited2 = new HashSet<PyramidKey>();
        visited2.add(key);
        visited2.addAll(allNeighbors);

        var toVisit2 = new LinkedList<PyramidKey>();
        index.addNeighboringNodes(key, toVisit2, visited2);

        // None of the previously-discovered neighbors should be re-emitted
        for (var k : toVisit2) {
            assertFalse(allNeighbors.contains(k),
                        "Should not re-emit already-visited neighbor: " + k);
        }
    }

    @Test
    void addNeighboringNodes_level1Key_emitsNeighbors() {
        // Build a level-1 type-6 key and verify neighbors are emitted
        // (A deeper key has more potential SFC-adjacent siblings)
        var root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        int row = root.type() - Pyramid.TYPE_6;
        PyramidKey level1Key = null;
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            var child = root.child(i);
            if (child instanceof Pyramid p && p.type() == Pyramid.TYPE_6) {
                int cb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
                int tb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
                level1Key = PyramidKey.fromLevels((byte) 1, new int[]{ 0, cb }, new int[]{ 0, tb });
                break;
            }
        }
        assertNotNull(level1Key, "Must be able to construct a level-1 key");

        final var finalLevel1Key = level1Key;
        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(finalLevel1Key);

        assertDoesNotThrow(() -> index.addNeighboringNodes(finalLevel1Key, toVisit, visited));
        // At level 1 there are sibling pyramid children
        assertFalse(toVisit.isEmpty(),
                    "Level-1 key must produce at least one neighbor (sibling pyramid children)");
    }

    @Test
    void addNeighboringNodes_unionsTheQuadBaseFaceNeighbor() {
        // pi1.4 Phase C graduation (Luciferase-3zs): addNeighboringNodes now unions the same-shape
        // (quad-base, f4) face neighbor — a DIFFERENT parent's child the sibling-only walk misses —
        // with the existing sibling set. Cross-shape (triangular tet faces) remains deferred to pi1.5.
        var pair = firstPyramidWithSfcQuadBaseNeighbor();
        assertNotNull(pair, "expected an interior SFC pyramid with an SFC quad-base neighbor");
        var selfKey = PyramidKeyCodec.encode(pair[0]);
        var f4Key = PyramidKeyCodec.encode(pair[1]);

        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(selfKey);
        index.addNeighboringNodes(selfKey, toVisit, visited);

        assertTrue(toVisit.contains(f4Key),
                   "graduated addNeighboringNodes must emit the quad-base face neighbor " + pair[1]);
    }

    @Test
    void addNeighboringNodes_doesNotEmitTheF4NeighborWhenAlreadyVisited() {
        var pair = firstPyramidWithSfcQuadBaseNeighbor();
        assertNotNull(pair);
        var selfKey = PyramidKeyCodec.encode(pair[0]);
        var f4Key = PyramidKeyCodec.encode(pair[1]);

        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(selfKey);
        visited.add(f4Key); // already visited — must be suppressed
        index.addNeighboringNodes(selfKey, toVisit, visited);

        assertFalse(toVisit.contains(f4Key), "must not re-emit an already-visited f4 neighbor");
    }

    @Test
    void addNeighboringNodes_f4UnionIsReciprocal() {
        // BFS symmetry: if addNeighboringNodes(self) emits the f4 neighbor, then
        // addNeighboringNodes(f4) must emit self back — else a kNN/range BFS could reach a cell from
        // one side but not the other and silently miss entities.
        var pair = firstPyramidWithSfcQuadBaseNeighbor();
        assertNotNull(pair);
        var selfKey = PyramidKeyCodec.encode(pair[0]);
        var f4Key = PyramidKeyCodec.encode(pair[1]);

        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(f4Key);
        index.addNeighboringNodes(f4Key, toVisit, visited);

        assertTrue(toVisit.contains(selfKey),
                   "addNeighboringNodes(f4) must emit self back (BFS symmetry)");
    }

    // ===== pi1.5 Phase C (Luciferase-azwr): cross-shape graduation =====

    @Test
    void addNeighboringNodes_unionsCrossShapeTetFaceNeighbor() {
        // pi1.5 Phase C graduation (Luciferase-azwr): addNeighboringNodes consumes the now-cross-shape
        // detector.findFaceNeighbors, so a pyramid's triangular-face TET neighbor (a tet-leaf key) must
        // enter the BFS frontier. Non-vacuous: empty / same-shape-only would fail this.
        var pyr = firstPyramidWithCrossShapeTetFace();
        assertNotNull(pyr, "expected an SFC pyramid with an in-domain triangular-face tet neighbor");
        var selfKey = PyramidKeyCodec.encode(pyr);
        assertNotNull(selfKey);

        // The cross-shape tet face neighbors surfaced by the wired detector.
        var detector = index.getNeighborDetector();
        var tetFaceKeys = new HashSet<PyramidKey>();
        for (var fk : detector.findFaceNeighbors(selfKey)) {
            if (PyramidIndex.elementFromKey(fk) instanceof com.hellblazer.luciferase.lucien.tetree.Tet) {
                tetFaceKeys.add(fk);
            }
        }
        assertFalse(tetFaceKeys.isEmpty(), "precondition: detector surfaces >=1 cross-shape tet face");

        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(selfKey);
        index.addNeighboringNodes(selfKey, toVisit, visited);

        var frontier = new HashSet<>(toVisit);
        for (var tk : tetFaceKeys) {
            assertTrue(frontier.contains(tk),
                       "graduated addNeighboringNodes must union the cross-shape tet face neighbor " + tk);
        }
    }

    @Test
    void addNeighboringNodes_tetLeafNodeIndex_emitsItsCrossShapeNeighbors() {
        // Red-guard #1 (Phase B substantive-critic): a TET-LEAF nodeIndex was contributing nothing in
        // pi1.4 (detector returned empty for tet-leaf keys). pi1.5 makes tet-leaf keys first-class BFS
        // entries: addNeighboringNodes(tetLeafKey) must emit the tet's cross-shape face neighbors.
        //
        // Bound (documented, not silent): the sibling walk enqueues only the Pyramid children of the
        // enclosing parent — non-face-adjacent tet siblings are intentionally NOT enqueued. BFS
        // connectivity (kNN / range / collision) only traverses face-adjacent cells, and every
        // face-adjacent neighbor IS emitted via findFaceNeighbors, so the omission is safe.
        var tetLeafKey = firstCrossShapeTetLeafKey();
        assertNotNull(tetLeafKey, "expected an in-domain shallow tet-leaf key with face neighbors");

        var detector = index.getNeighborDetector();
        var faceNeighbors = new HashSet<>(detector.findFaceNeighbors(tetLeafKey));
        assertFalse(faceNeighbors.isEmpty(), "precondition: tet-leaf key has cross-shape face neighbors");

        var toVisit = new LinkedList<PyramidKey>();
        var visited = new HashSet<PyramidKey>();
        visited.add(tetLeafKey);
        index.addNeighboringNodes(tetLeafKey, toVisit, visited);

        var frontier = new HashSet<>(toVisit);
        for (var fn : faceNeighbors) {
            assertTrue(frontier.contains(fn),
                       "tet-leaf nodeIndex must emit its cross-shape face neighbor " + fn);
        }
    }

    @Test
    void addNeighboringNodes_crossShapeUnionIsReciprocal() {
        // BFS symmetry across the cross-shape (pyramid <-> tet) seam: if addNeighboringNodes(pyramid)
        // emits a tet face neighbor, addNeighboringNodes(tet) must emit the pyramid back — else a
        // kNN/range BFS could reach a cell from one side but not the other and silently miss entities.
        var pyr = firstPyramidWithCrossShapeTetFace();
        assertNotNull(pyr);
        var pyrKey = PyramidKeyCodec.encode(pyr);
        var detector = index.getNeighborDetector();

        PyramidKey tetKey = null;
        for (var fk : detector.findFaceNeighbors(pyrKey)) {
            if (PyramidIndex.elementFromKey(fk) instanceof com.hellblazer.luciferase.lucien.tetree.Tet) {
                tetKey = fk;
                break;
            }
        }
        assertNotNull(tetKey, "precondition: pyramid has a cross-shape tet face neighbor");

        // Forward: pyramid emits the tet.
        var fwd = new LinkedList<PyramidKey>();
        var vf = new HashSet<PyramidKey>();
        vf.add(pyrKey);
        index.addNeighboringNodes(pyrKey, fwd, vf);
        assertTrue(new HashSet<>(fwd).contains(tetKey), "pyramid must emit its tet face neighbor");

        // Reverse: tet emits the pyramid back.
        var rev = new LinkedList<PyramidKey>();
        var vr = new HashSet<PyramidKey>();
        vr.add(tetKey);
        index.addNeighboringNodes(tetKey, rev, vr);
        assertTrue(new HashSet<>(rev).contains(pyrKey),
                   "addNeighboringNodes(tet) must emit the pyramid back (cross-shape BFS symmetry)");
    }

    /** First SFC pyramid (level 2..5) whose triangular faces yield an in-domain cross-shape tet neighbor. */
    private Pyramid firstPyramidWithCrossShapeTetFace() {
        var detector = index.getNeighborDetector();
        var roots = new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                    new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) };
        var stack = new java.util.ArrayDeque<Pyramid>();
        for (var r : roots) {
            stack.push(r);
        }
        while (!stack.isEmpty()) {
            var p = stack.pop();
            if (p.level() >= 2) {
                var pk = PyramidKeyCodec.encode(p);
                if (pk != null) {
                    for (var fk : detector.findFaceNeighbors(pk)) {
                        if (PyramidIndex.elementFromKey(fk) instanceof com.hellblazer.luciferase.lucien.tetree.Tet) {
                            return p;
                        }
                    }
                }
            }
            if (p.level() < 5) {
                for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                    if (p.child(i) instanceof Pyramid pc) {
                        stack.push(pc);
                    }
                }
            }
        }
        return null;
    }

    /**
     * First in-domain shallow tet-leaf key (level 2..5) that has at least one cross-shape face neighbor.
     * Sourced from a pyramid's triangular-face neighbor — that tet is guaranteed to report the pyramid
     * back (reciprocity), so its face-neighbor set is non-empty (mirrors PyramidNeighborParityTest).
     */
    private PyramidKey firstCrossShapeTetLeafKey() {
        var detector = index.getNeighborDetector();
        var roots = new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                    new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) };
        var stack = new java.util.ArrayDeque<Pyramid>();
        for (var r : roots) {
            stack.push(r);
        }
        while (!stack.isEmpty()) {
            var p = stack.pop();
            if (p.level() >= 2) {
                for (int f = 0; f < 4; f++) {
                    var fn = p.faceNeighbor(f);
                    if (fn != null && fn.element() instanceof com.hellblazer.luciferase.lucien.tetree.Tet t) {
                        var tk = PyramidKeyCodec.encode(t);
                        if (tk != null && !detector.findFaceNeighbors(tk).isEmpty()) {
                            return tk;
                        }
                    }
                }
            }
            if (p.level() < 5) {
                for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                    if (p.child(i) instanceof Pyramid pc) {
                        stack.push(pc);
                    }
                }
            }
        }
        return null;
    }

    private Pyramid[] firstPyramidWithSfcQuadBaseNeighbor() {
        var roots = new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                    new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) };
        var stack = new java.util.ArrayDeque<Pyramid>();
        for (var r : roots) {
            stack.push(r);
        }
        while (!stack.isEmpty()) {
            var p = stack.pop();
            if (p.level() >= 2) { // need a parent to compare against
                var fn = p.faceNeighbor(4);
                if (fn != null && fn.element() instanceof Pyramid pn) {
                    var pk = PyramidKeyCodec.encode(p);
                    var nk = PyramidKeyCodec.encode(pn);
                    // Require the f4 neighbor to be CROSS-PARENT (not a sibling) — otherwise the
                    // sibling-only walk already emits it and the graduation test would be vacuous.
                    if (pk != null && nk != null && pk.parent() != null
                        && !pk.parent().equals(nk.parent())) {
                        return new Pyramid[] { p, pn };
                    }
                }
            }
            if (p.level() < 5) {
                for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                    if (p.child(i) instanceof Pyramid pc) {
                        stack.push(pc);
                    }
                }
            }
        }
        return null;
    }
}
