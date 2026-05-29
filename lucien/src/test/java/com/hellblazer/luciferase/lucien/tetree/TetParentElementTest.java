/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.pyramid.Pyramid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates {@link Tet#parentElement()} (RDR-010 q3p Phase C), the cross-type parent that bridges the
 * tet/pyramid boundary which {@link Tet#parent()} cannot represent. Three regimes are covered:
 * pure-Tetree ({@code minTetLevel == -1}), interior pyramid-rooted tet ({@code 0 < minTetLevel < l}),
 * and the boundary tet ({@code minTetLevel == l}) whose parent is a pyramid.
 *
 * <p>The boundary round-trip is the key correctness proof: every tetrahedral child produced by
 * {@link Pyramid#child(int)} must, via {@code parentElement()}, recover the exact originating pyramid
 * (anchor, level, and type) — independent ground truth from the Phase B child construction.
 *
 * @author hal.hildebrand
 */
class TetParentElementTest {

    private static final byte LEVEL = 10;

    @Test
    void pureTetreeParentElementMatchesParent() {
        // A normal Tetree element (no pyramidal ancestor) must behave exactly like parent().
        var t = new Tet(0, 0, 0, LEVEL, (byte) 0).child(3); // some child tet, minTetLevel == -1
        assertEquals(Tet.NO_TET_ANCESTOR, t.minTetLevel());
        var pe = t.parentElement();
        assertInstanceOf(Tet.class, pe);
        assertEquals(t.parent(), pe);
    }

    @Test
    void boundaryTetParentIsThePyramidThatBirthedIt() {
        // For each pyramid type, every tetrahedral child's parentElement() recovers the pyramid.
        // Distinct per-axis anchors (3L,5L,7L) so a bug corrupting x or y (not just z) is caught.
        var l = Constants.lengthAtLevel(LEVEL);
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var p = new Pyramid(3 * l, 5 * l, 7 * l, LEVEL, type);
            var tetChildren = 0;
            for (var i = 0; i < 10; i++) {
                var c = p.child(i);
                if (c instanceof Tet tet) {
                    tetChildren++;
                    assertEquals(tet.l, tet.minTetLevel(), "boundary tet has minTetLevel == level");
                    var pe = tet.parentElement();
                    assertInstanceOf(Pyramid.class, pe, "boundary tet's parent is a pyramid");
                    assertEquals(p, pe, "parentElement() recovers the originating pyramid (child " + i + ")");
                    // Every boundary parent pyramid is pure (no tetrahedral ancestor).
                    assertEquals(Pyramid.NO_TET_ANCESTOR, ((Pyramid) pe).minTetLevel(), "pure parent pyramid");
                }
            }
            assertEquals(4, tetChildren, "4 tet children per pyramid type " + type);
        }
    }

    @Test
    void boundaryRoundTripAtLevelOne() {
        // The shallowest boundary: a level-0 pyramid's tetrahedral children (level 1, minTetLevel 1)
        // must recover the level-0 pyramid, exercising the l-1==0 anchor/level arithmetic.
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var root = new Pyramid(0, 0, 0, (byte) 0, type);
            var tetChildren = 0;
            for (var i = 0; i < 10; i++) {
                if (root.child(i) instanceof Tet tet) {
                    tetChildren++;
                    assertEquals(1, tet.l);
                    assertEquals(1, tet.minTetLevel());
                    var pe = tet.parentElement();
                    assertInstanceOf(Pyramid.class, pe);
                    assertEquals(0, pe.level());
                    assertEquals(root, pe, "level-1 tet recovers the level-0 pyramid (type " + type + ")");
                }
            }
            assertEquals(4, tetChildren);
        }
    }

    @Test
    void parentStillThrowsAtBoundary() {
        // parent() keeps its contract: it cannot return a pyramid, so it throws at the boundary.
        var anchor = Constants.lengthAtLevel(LEVEL) * 3;
        var p = new Pyramid(anchor, anchor, anchor, LEVEL, Pyramid.TYPE_6);
        for (var i = 0; i < 10; i++) {
            if (p.child(i) instanceof Tet tet) {
                assertThrows(IllegalStateException.class, tet::parent);
                return;
            }
        }
    }

    @Test
    void interiorPyramidRootedTetReturnsTetWithPropagatedMinTetLevel() {
        // A tet two levels below a pyramid: minTetLevel < l, parent is a tet carrying the same
        // minTetLevel. Build by descending one more tet level from a boundary tet.
        var anchor = Constants.lengthAtLevel(LEVEL) * 3;
        var p = new Pyramid(anchor, anchor, anchor, LEVEL, Pyramid.TYPE_6);
        Tet boundary = null;
        for (var i = 0; i < 10; i++) {
            if (p.child(i) instanceof Tet tet) {
                boundary = tet;
                break;
            }
        }
        var deeper = boundary.child(0); // level l+2, minTetLevel still == boundary.l (propagated)
        assertEquals(boundary.minTetLevel(), deeper.minTetLevel(), "minTetLevel propagated to deeper tet");
        assertEquals(boundary.l + 1, deeper.l);

        var pe = deeper.parentElement();
        assertInstanceOf(Tet.class, pe, "interior tet's parent is a tet");
        assertEquals(deeper.parent(), pe);
        assertEquals(boundary.minTetLevel(), ((Tet) pe).minTetLevel(), "parent tet carries minTetLevel");
    }

    @Test
    void rootHasNoParentElement() {
        var root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertThrows(IllegalStateException.class, root::parentElement);
    }
}
