/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * RDR-010 q3p Phase E integration: walks a hand-built hybrid pyramid/tetrahedron tree across the
 * unified {@link HybridElement} navigation surface ({@link Pyramid#child(int)}, {@link Pyramid#parent()},
 * {@link Tet#parentElement()}, {@link Tet#child(int)}), verifying that parent/child chains round-trip
 * consistently and the {@code minTetLevel} invariant holds transitively down the tree. This is the
 * re-injection contract pi1.3 PyramidIndex consumes; it deliberately exercises only the validated
 * navigation paths (not the deep-tet face-neighbor path deferred to Luciferase-4pd, Finding #16).
 *
 * @author hal.hildebrand
 */
class HybridTraversalIntegrationTest {

    private static final byte LEVEL = 8;

    @Test
    void pyramidRootChildrenRoundTripToRoot() {
        var root = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6);
        var pyramids = 0;
        var tets = 0;
        for (var i = 0; i < 10; i++) {
            var c = root.child(i);
            if (c instanceof Pyramid pc) {
                pyramids++;
                assertEquals(Pyramid.NO_TET_ANCESTOR, pc.minTetLevel(), "pyramid child is pure");
                assertEquals(root, pc.parent(), "pyramid child round-trips to root (child " + i + ")");
            } else {
                var t = (Tet) c;
                tets++;
                assertEquals(LEVEL + 1, t.minTetLevel(), "tet child minTetLevel == its level");
                assertEquals(root, t.parentElement(), "tet child round-trips to root (child " + i + ")");
            }
        }
        assertEquals(6, pyramids);
        assertEquals(4, tets);
    }

    @Test
    void pyramidChainParentRoundTrips() {
        // pyramid root -> pyramid child -> pyramid grandchild, walking parent() back up.
        var root = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6);
        var child = firstPyramidChild(root);
        var grandchild = firstPyramidChild(child);
        assertEquals(LEVEL + 1, child.level());
        assertEquals(LEVEL + 2, grandchild.level());
        assertEquals(child, grandchild.parent(), "grandchild -> child");
        assertEquals(root, child.parent(), "child -> root");
        assertEquals(Pyramid.NO_TET_ANCESTOR, grandchild.minTetLevel(), "pyramid descendants stay pure");
    }

    @Test
    void tetBranchMinTetLevelTransitiveAndParentElementChain() {
        // pyramid root -> tet child (shallowest, boundary) -> tet grandchild (Bey, interior).
        var root = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6);
        var tetChild = firstTetChild(root);
        assertEquals(LEVEL + 1, tetChild.l);
        assertEquals(LEVEL + 1, tetChild.minTetLevel(), "shallowest tet: minTetLevel == level");

        var tetGrandchild = tetChild.child(0);
        assertEquals(LEVEL + 2, tetGrandchild.l);
        assertEquals(LEVEL + 1, tetGrandchild.minTetLevel(), "minTetLevel propagates unchanged to deeper tet");

        // Interior tet (minTetLevel < level): parentElement() is a tetrahedron == the boundary tet.
        var interiorParent = tetGrandchild.parentElement();
        assertInstanceOf(Tet.class, interiorParent, "interior tet parent is a tet");
        assertEquals(tetChild, interiorParent, "grandchild.parentElement -> boundary tet");

        // Boundary tet (minTetLevel == level): parentElement() crosses the shape boundary to the root.
        var boundaryParent = tetChild.parentElement();
        assertInstanceOf(Pyramid.class, boundaryParent, "boundary tet parent is the pyramid");
        assertEquals(root, boundaryParent, "boundary tet.parentElement -> root pyramid");
    }

    @Test
    void fullChainFromDeepTetUpToRootIsConsistent() {
        // Transitive walk: deep tet -> ... -> root pyramid via parentElement, levels strictly decreasing.
        var root = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6);
        var tetChild = firstTetChild(root);
        HybridElement cursor = tetChild.child(0).child(0); // two Bey levels below the boundary tet
        var guard = 0;
        while (!(cursor instanceof Pyramid)) {
            var tet = (Tet) cursor;
            var next = tet.parentElement();
            assertEquals(tet.level() - 1, next.level(), "each parentElement step drops exactly one level");
            cursor = next;
            if (++guard > 32) {
                throw new AssertionError("parentElement chain did not reach a pyramid");
            }
        }
        assertEquals(root, cursor, "chain terminates at the root pyramid");
    }

    private static Pyramid firstPyramidChild(Pyramid p) {
        for (var i = 0; i < 10; i++) {
            if (p.child(i) instanceof Pyramid pc) {
                return pc;
            }
        }
        throw new IllegalStateException("no pyramid child");
    }

    private static Tet firstTetChild(Pyramid p) {
        for (var i = 0; i < 10; i++) {
            if (p.child(i) instanceof Tet t) {
                return t;
            }
        }
        throw new IllegalStateException("no tet child");
    }
}
