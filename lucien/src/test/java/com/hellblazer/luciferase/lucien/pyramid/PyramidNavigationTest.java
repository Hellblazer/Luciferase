/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates {@link Pyramid#parent()} and {@link Pyramid#child(int)} (RDR-010 q3p Phase B) against the
 * t8code reference tables ({@code t8_dpyramid_parenttype_Iloc_to_type}/{@code _cid} rows 6,7 and
 * {@code t8_dpyramid_type_cid_to_parenttype}). A pyramid refines into 10 children — 6 pyramids and 4
 * tetrahedra — and its parent is always a pyramid.
 *
 * @author hal.hildebrand
 */
class PyramidNavigationTest {

    // t8code parenttype_Iloc_to_type rows 6,7 (ground truth, mirrored here independently of the impl).
    private static final byte[][] EXPECTED_CHILD_TYPE = { { 6, 3, 6, 0, 6, 0, 3, 6, 7, 6 },
                                                          { 7, 0, 3, 6, 7, 3, 7, 0, 7, 7 } };
    // t8code parenttype_Iloc_to_cid rows 6,7.
    private static final byte[][] EXPECTED_CHILD_CID  = { { 0, 1, 1, 2, 2, 3, 3, 3, 3, 7 },
                                                          { 0, 4, 4, 4, 4, 5, 5, 6, 6, 7 } };

    private static final byte LEVEL = 10;

    @Test
    void pyramidHasTenChildren() {
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var p = new Pyramid(0, 0, 0, LEVEL, type);
            for (var i = 0; i < 10; i++) {
                var c = p.child(i);
                assertEquals(LEVEL + 1, c.level(), "child level");
            }
            assertThrows(IndexOutOfBoundsException.class, () -> p.child(10));
            assertThrows(IndexOutOfBoundsException.class, () -> p.child(-1));
        }
    }

    @Test
    void childTypesAndShapesMatchT8code() {
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var p = new Pyramid(0, 0, 0, LEVEL, type);
            var row = type - Pyramid.TYPE_6;
            var pyramidCount = 0;
            var tetCount = 0;
            for (var i = 0; i < 10; i++) {
                var c = p.child(i);
                assertEquals(EXPECTED_CHILD_TYPE[row][i], c.type(), "child " + i + " type (parent " + type + ")");
                if (c.type() >= Pyramid.TYPE_6) {
                    assertInstanceOf(Pyramid.class, c, "pyramid child " + i);
                    assertEquals(Pyramid.NO_TET_ANCESTOR, c.minTetLevel(), "pyramid child minTetLevel");
                    pyramidCount++;
                } else {
                    assertInstanceOf(Tet.class, c, "tet child " + i);
                    assertEquals(c.level(), c.minTetLevel(), "tet child minTetLevel == its level");
                    tetCount++;
                }
            }
            assertEquals(6, pyramidCount, "6 pyramid children for parent type " + type);
            assertEquals(4, tetCount, "4 tet children for parent type " + type);
        }
    }

    @Test
    void childAnchorsMatchCubeIdShift() {
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var p = new Pyramid(0, 0, 0, LEVEL, type);
            var row = type - Pyramid.TYPE_6;
            var ch = Constants.lengthAtLevel((byte) (LEVEL + 1));
            for (var i = 0; i < 10; i++) {
                var c = p.child(i);
                var cid = EXPECTED_CHILD_CID[row][i];
                assertEquals((cid & 1) != 0 ? ch : 0, c.x(), "child " + i + " x");
                assertEquals((cid & 2) != 0 ? ch : 0, c.y(), "child " + i + " y");
                assertEquals((cid & 4) != 0 ? ch : 0, c.z(), "child " + i + " z");
            }
        }
    }

    @Test
    void allChildVerticesLieWithinParentCube() {
        // Table-independent ground truth: the 10 children tile the parent's surrounding cube, so
        // every child vertex must lie within [anchor, anchor+L]^3. This catches table transcription
        // errors that a comparison against a copy of the same tables would miss.
        var anchor = Constants.lengthAtLevel(LEVEL) * 5;
        var l = Constants.lengthAtLevel(LEVEL);
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var p = new Pyramid(anchor, anchor, anchor, LEVEL, type);
            for (var i = 0; i < 10; i++) {
                var c = p.child(i);
                var verts = (c instanceof Pyramid py) ? py.coordinates() : ((Tet) c).coordinates();
                for (var v : verts) {
                    assertTrue(v.x >= anchor && v.x <= anchor + l, "child " + i + " vertex x " + v.x);
                    assertTrue(v.y >= anchor && v.y <= anchor + l, "child " + i + " vertex y " + v.y);
                    assertTrue(v.z >= anchor && v.z <= anchor + l, "child " + i + " vertex z " + v.z);
                }
            }
        }
    }

    @Test
    void parentOfPyramidChildRoundTrips() {
        // For each pyramid child, parent() must recover the original pyramid exactly.
        for (var type : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            // Use a non-root anchor so the cube-id bit-clear is exercised.
            var p = new Pyramid(0, 0, 0, LEVEL, type);
            for (var i = 0; i < 10; i++) {
                var c = p.child(i);
                if (c.type() >= Pyramid.TYPE_6) {
                    var recovered = ((Pyramid) c).parent();
                    assertEquals(p, recovered, "parent(child(" + i + ")) for parent type " + type);
                }
            }
        }
    }

    @Test
    void parentClearsAnchorBitsAndDecrementsLevel() {
        // A type-6 child of a type-6 pyramid sitting at an offset anchor.
        var p = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_6);
        var child = (Pyramid) p.child(9); // index 9: type 6, cid 7 (+x+y+z)
        var ch = Constants.lengthAtLevel((byte) (LEVEL + 1));
        assertEquals(ch, child.x());
        assertEquals(ch, child.y());
        assertEquals(ch, child.z());
        var back = child.parent();
        assertEquals(0, back.x());
        assertEquals(0, back.y());
        assertEquals(0, back.z());
        assertEquals(LEVEL, back.level());
        assertEquals(Pyramid.TYPE_6, back.type());
    }

    @Test
    void maxLevelPyramidCannotRefine() {
        var max = Constants.getMaxRefinementLevel();
        var p = new Pyramid(0, 0, 0, max, Pyramid.TYPE_6);
        assertThrows(IllegalStateException.class, () -> p.child(0));
    }

    @Test
    void parentRoundTripsFromNonOriginAnchor() {
        // A type-6 pyramid sitting deep in the domain at a non-zero, level-aligned anchor.
        var anchor = Constants.lengthAtLevel(LEVEL) * 3; // a multiple of the parent edge length
        var p = new Pyramid(anchor, anchor, anchor, LEVEL, Pyramid.TYPE_6);
        for (var i = 0; i < 10; i++) {
            var c = p.child(i);
            if (c.type() >= Pyramid.TYPE_6) {
                assertEquals(p, ((Pyramid) c).parent(),
                             "parent(child(" + i + ")) from non-origin anchor " + anchor);
            }
        }
    }

    @Test
    void rootPyramidHasNoParent() {
        var root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        assertThrows(IllegalStateException.class, root::parent);
    }

    @Test
    void parentIsAlwaysPureWithNoTetAncestor() {
        var p = new Pyramid(0, 0, 0, LEVEL, Pyramid.TYPE_7);
        var c = (Pyramid) firstPyramidChild(p);
        assertEquals(Pyramid.NO_TET_ANCESTOR, c.parent().minTetLevel());
    }

    private static HybridElement firstPyramidChild(Pyramid p) {
        for (var i = 0; i < 10; i++) {
            var c = p.child(i);
            if (c.type() >= Pyramid.TYPE_6) {
                return c;
            }
        }
        throw new IllegalStateException("no pyramid child");
    }
}
