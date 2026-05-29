/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.pyramid.Pyramid;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * RDR-010 q3p Phase A: both {@link Tet} and {@link Pyramid} implement {@link HybridElement}, and the
 * interface accessors return values identical to the concrete types' own accessors / fields (no
 * behaviour change). This is the conformance foundation the cross-shape navigation methods build on.
 *
 * @author hal.hildebrand
 */
class HybridElementContractTest {

    @Test
    void tetImplementsHybridElementWithConsistentAccessors() {
        var tet = new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).child(0); // valid level-2 tet
        HybridElement e = tet; // compile-time proof of conformance

        assertEquals(tet.x, e.x());
        assertEquals(tet.y, e.y());
        assertEquals(tet.z, e.z());
        assertEquals(tet.l, e.level());
        assertEquals(tet.type, e.type());
        assertEquals(tet.minTetLevel(), e.minTetLevel());
        assertEquals(tet.length(), e.length());
    }

    @Test
    void pyramidImplementsHybridElementWithConsistentAccessors() {
        var pyr = new Pyramid(0, 0, 0, (byte) 3, Pyramid.TYPE_7);
        HybridElement e = pyr; // compile-time proof of conformance

        assertEquals(pyr.x(), e.x());
        assertEquals(pyr.y(), e.y());
        assertEquals(pyr.z(), e.z());
        assertEquals(pyr.level(), e.level());
        assertEquals(pyr.type(), e.type());
        assertEquals(pyr.minTetLevel(), e.minTetLevel());
        assertEquals(pyr.length(), e.length());
    }

    @Test
    void typeDiscriminatesShapeForGeometryBranching() {
        HybridElement tet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        HybridElement pyr = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        assertInstanceOf(Tet.class, tet);
        assertInstanceOf(Pyramid.class, pyr);
        // Geometry consumers branch on type(): 0-5 => tetrahedron (4 verts), 6-7 => pyramid (5 verts).
        assertEquals(0, tet.type());
        assertEquals(Pyramid.TYPE_6, pyr.type());
    }

    @Test
    void pyramidMinTetLevelSentinelMatchesTetSentinel() {
        // Both shapes share the -1 "no tetrahedral ancestor" sentinel value across packages.
        assertEquals(Tet.NO_TET_ANCESTOR, Pyramid.NO_TET_ANCESTOR);
        assertEquals(-1, new Pyramid(0, 0, 0, (byte) 2, Pyramid.TYPE_6).minTetLevel());
    }
}
