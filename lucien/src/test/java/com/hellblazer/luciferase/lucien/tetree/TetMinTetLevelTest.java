/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-010 pi1.2: validates the {@code minTetLevel} field on {@link Tet} (Knapp 2026 Algorithm 4.1)
 * and its propagation through {@link Tet#child(int)} / {@link Tet#parent()}, while confirming
 * pure-Tetree behaviour is byte-for-byte unchanged (sentinel {@link Tet#NO_TET_ANCESTOR}).
 *
 * @author hal.hildebrand
 */
class TetMinTetLevelTest {

    private static Tet validTetAtLevel3() {
        return new Tet(0, 0, 0, (byte) 0, (byte) 0).child(0).child(0).child(0);
    }

    @Test
    void defaultConstructorUsesSentinel() {
        var t = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertEquals(Tet.NO_TET_ANCESTOR, t.minTetLevel());
        assertEquals(-1, Tet.NO_TET_ANCESTOR);
    }

    @Test
    void explicitConstructorAndWithMinTetLevelCarryTheValue() {
        var t = validTetAtLevel3();
        assertEquals(3, t.l);
        var withMtl = t.withMinTetLevel((byte) 2);
        assertEquals(2, withMtl.minTetLevel());
        // Same geometry => equal (minTetLevel is excluded from identity).
        assertEquals(t, withMtl);
        assertEquals(t.hashCode(), withMtl.hashCode());
        // withMinTetLevel of the same value is identity.
        assertSame(t, t.withMinTetLevel(Tet.NO_TET_ANCESTOR));
    }

    @Test
    void minTetLevelExcludedFromGeometricIdentity() {
        var t = validTetAtLevel3();
        var a = t.withMinTetLevel((byte) 1);
        var b = t.withMinTetLevel((byte) 2);
        assertEquals(a, b, "same geometry, differing minTetLevel => still equal");
        assertNotEquals(a.minTetLevel(), b.minTetLevel());
    }

    @Test
    void childPropagatesMinTetLevel() {
        var t = validTetAtLevel3().withMinTetLevel((byte) 2);
        var child = t.child(0);
        assertEquals(2, child.minTetLevel(), "Knapp Alg 4.2b: tet child inherits parent's minTetLevel");
        assertEquals(4, child.l);
    }

    @Test
    void childOfPureTetStaysPure() {
        var pure = validTetAtLevel3();
        var child = pure.child(0);
        assertEquals(Tet.NO_TET_ANCESTOR, child.minTetLevel());
    }

    @Test
    void parentPropagatesMinTetLevelForTetOfTet() {
        var t = validTetAtLevel3().withMinTetLevel((byte) 2); // boundary at level 2, this at level 3
        var parent = t.parent();
        assertEquals(2, parent.l);
        assertEquals(2, parent.minTetLevel(), "Knapp Alg 4.1 else-branch: propagate minTetLevel to tet parent");
    }

    @Test
    void parentAtBoundaryDefersToUnificationBead() {
        // minTetLevel == level => the parent is the birthing pyramid; cross-type return is q3p.
        var boundary = validTetAtLevel3().withMinTetLevel((byte) 3);
        var ex = assertThrows(IllegalStateException.class, boundary::parent);
        assertTrue(ex.getMessage().contains("q3p"), "boundary parent must point to the deferred unification bead");
    }

    @Test
    void withMinTetLevelRejectsOutOfRange() {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true; // side-effect sets true only when -ea
        if (!assertionsEnabled) {
            return; // invariant is assert-guarded; nothing to verify without -ea
        }
        var t = validTetAtLevel3(); // level 3
        assertThrows(AssertionError.class, () -> t.withMinTetLevel((byte) (t.l + 1)),
                     "minTetLevel > level must violate the constructor invariant");
    }

    @Test
    void geometricSubdividePropagatesMinTetLevel() {
        var t = validTetAtLevel3().withMinTetLevel((byte) 2);
        for (var child : t.geometricSubdivide()) {
            assertEquals(2, child.minTetLevel(), "geometricSubdivide children inherit minTetLevel");
        }
        // Pure-tet geometric subdivision stays pure.
        for (var child : validTetAtLevel3().geometricSubdivide()) {
            assertEquals(Tet.NO_TET_ANCESTOR, child.minTetLevel());
        }
    }

    @Test
    void computeTypeFailsLoudOnPyramidRootedTet() {
        var pyramidRooted = validTetAtLevel3().withMinTetLevel((byte) 2);
        var ex = assertThrows(IllegalStateException.class, () -> pyramidRooted.computeType((byte) 1));
        assertTrue(ex.getMessage().contains("q3p"), "computeType must defer pyramid-rooted case to q3p");
        // Pure-tet computeType is unchanged.
        var pure = validTetAtLevel3();
        assertEquals(pure.type, pure.computeType(pure.l));
    }

    @Test
    void siblingInheritsMinTetLevelTransitively() {
        var t = validTetAtLevel3().withMinTetLevel((byte) 2);
        var sib = t.sibling(1);
        assertEquals(2, sib.minTetLevel(), "sibling propagates minTetLevel via parent()->child()");
    }

    @Test
    void pureTetParentUnchanged() {
        var pure = validTetAtLevel3();
        var parent = pure.parent();
        assertEquals(2, parent.l);
        assertEquals(Tet.NO_TET_ANCESTOR, parent.minTetLevel());
        // The pure path round-trips: a child's parent is the original tet.
        assertEquals(pure.parent(), pure.child(0).parent().parent());
    }
}
