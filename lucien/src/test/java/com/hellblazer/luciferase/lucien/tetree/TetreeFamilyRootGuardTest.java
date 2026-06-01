/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
// TetreeConnectivity is in the same package (com.hellblazer.luciferase.lucien.tetree) — no import needed.

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-t6su: {@link TetreeFamily#isFamily}/{@link TetreeFamily#canMerge} must guard level 0. The six
 * root-cube tetrahedra have no parent ({@code Tet.parent()} throws at the root), so they cannot form a
 * mergeable family — t8code returns {@code false} rather than faulting. Without an {@code l==0} guard the
 * methods call {@code tets[0].parent()} at the root and throw.
 *
 * @author hal.hildebrand
 */
class TetreeFamilyRootGuardTest {

    // A single-tree Tetree's level 0 is a single type-0 root tet (the Tet constructor enforces level 0 =>
    // type 0), so a "family" of distinct root tets is not constructible. The array MUST be
    // CHILDREN_PER_TET (== 8) long to pass isFamily's length gate and actually reach the l==0 guard; eight
    // references to the type-0 root do that (the guard returns false before the duplicate-child check).
    private static Tet[] eightRootRefs() {
        var root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        return new Tet[] { root, root, root, root, root, root, root, root };
    }

    @Test
    void isFamilyReturnsFalseAtRootInsteadOfThrowing() {
        var roots = eightRootRefs();
        assertEquals(TetreeConnectivity.CHILDREN_PER_TET, roots.length,
                     "array must be CHILDREN_PER_TET long to pass the length gate and reach the l==0 guard");
        assertFalse(assertDoesNotThrow(() -> TetreeFamily.isFamily(roots),
                                       "isFamily must guard level 0, not call parent() and throw"),
                    "level-0 tets are not a mergeable family (the root has no parent)");
    }

    @Test
    void canMergeReturnsFalseAtRootInsteadOfThrowing() {
        // canMerge takes a Set; eight identical root references dedupe to one, so canMerge returns false at its
        // own size gate. Exercise the isFamily l==0 guard directly with the full-length array.
        var roots = new HashSet<Tet>();
        roots.add(new Tet(0, 0, 0, (byte) 0, (byte) 0));
        assertFalse(assertDoesNotThrow(() -> TetreeFamily.canMerge(roots),
                                       "canMerge must not throw at the root"),
                    "a single root tet is not a mergeable family");
        assertFalse(assertDoesNotThrow(() -> TetreeFamily.isFamily(eightRootRefs()),
                                       "isFamily must guard level 0, not throw"),
                    "level-0 tets cannot be merged (no parent at the root)");
    }
}
