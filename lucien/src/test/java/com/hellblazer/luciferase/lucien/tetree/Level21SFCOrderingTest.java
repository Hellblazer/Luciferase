/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level-21 (maximum refinement) key tests for the coarsest-at-MSB uniform encoding (Luciferase-tkvb).
 * Level 21 uses the same 6-bits-per-level layout as every other level (21 * 6 = 126 bits across two
 * longs); there is no special split-bit packing. These tests verify that real level-21 keys round-trip,
 * compute parents correctly, and preserve the coarse-dominant space-filling-curve order.
 *
 * @author hal.hildebrand
 */
class Level21SFCOrderingTest {

    /**
     * Descend from the root to a real level-21 tetrahedron via a deterministic child chain. The
     * resulting key is valid by construction in the coarsest-at-MSB uniform layout.
     */
    private static Tet descendToLevel21(int seed) {
        var tet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        for (int lvl = 0; lvl < 21; lvl++) {
            tet = tet.child((seed + lvl * 3) % 8);
        }
        return tet;
    }

    /** Independent 128-bit unsigned comparison of (highBits, lowBits) — the coarse-dominant order. */
    private static int compare128(TetreeKey<?> a, TetreeKey<?> b) {
        int hi = Long.compareUnsigned(a.getHighBits(), b.getHighBits());
        return hi != 0 ? hi : Long.compareUnsigned(a.getLowBits(), b.getLowBits());
    }

    /**
     * A real level-21 key round-trips Tet -> tmIndex -> Tet and is valid (uniform layout, no split).
     */
    @Test
    void testLevel21BitPacking() {
        var tet = descendToLevel21(1);
        var key = tet.tmIndex();

        assertEquals(21, key.getLevel());
        assertTrue(key.isValid(), "real level-21 key must be valid");

        // The leaf (deepest) group is step level-1 == 20, at bits 0-5; its type is the tet's type.
        assertEquals(tet.type(), key.getTypeAtLevel(20), "leaf type at step 20");

        // Decode round-trips back to the same tetrahedron.
        assertEquals(tet, Tet.tetrahedron(key), "level-21 tmIndex must round-trip");
    }

    /**
     * Parent of a real level-21 key is the level-20 key of the parent tetrahedron.
     */
    @Test
    void testLevel21ParentChild() {
        var tet = descendToLevel21(2);
        var level21Key = tet.tmIndex();
        var parent = level21Key.parent();

        assertEquals(20, parent.getLevel());
        assertTrue(parent instanceof ExtendedTetreeKey);

        // The key-level parent must equal the ground-truth parent (encode the parent Tet).
        assertEquals(tet.parent().tmIndex(), parent, "level-21 parent key must match parent tet key");
    }

    /**
     * SFC ordering preservation across MANY distinct subtrees. The keys descend from 200 distinct
     * child chains, so their coarse (upper) bits genuinely vary — a vacuous version that shares all
     * but the leaf 6 bits cannot detect an upper-bit scramble, but this one can. {@code compareTo}
     * must reproduce the independent 128-bit unsigned (highBits, lowBits) order exactly.
     */
    @Test
    void testLevel21SFCOrdering() {
        var keys = new ArrayList<TetreeKey<?>>();
        for (int seed = 0; seed < 200; seed++) {
            keys.add(descendToLevel21(seed).tmIndex());
        }

        // compareTo must agree with the independent 128-bit oracle for every ordered pair.
        for (var a : keys) {
            for (var b : keys) {
                assertEquals(Integer.signum(compare128(a, b)), Integer.signum(a.compareTo(b)),
                             "compareTo must match the 128-bit (high,low) unsigned order for "
                             + a + " vs " + b);
            }
        }

        // Sorting by compareTo must equal sorting by the independent oracle.
        var byCompareTo = new ArrayList<>(keys);
        byCompareTo.sort((x, y) -> x.compareTo(y));
        var byOracle = new ArrayList<>(keys);
        byOracle.sort(Level21SFCOrderingTest::compare128);
        assertEquals(byOracle, byCompareTo, "compareTo sort order must match the 128-bit oracle order");
    }

    /**
     * Real level-21 sibling keys (the 8 children of a common level-20 parent) are all valid, share
     * the common parent, and form a strict total order under {@code compareTo}.
     */
    @Test
    void testLevel21BitBoundaries() {
        var parent = descendToLevel21(3).parent(); // a level-20 tetrahedron
        var parentKey = parent.tmIndex();

        var keys = new ArrayList<TetreeKey<?>>();
        for (int child = 0; child < 8; child++) {
            var key = parent.child(child).tmIndex();
            assertEquals(21, key.getLevel());
            assertTrue(key.isValid(), "level-21 child key must be valid for child " + child);
            assertEquals(parentKey, key.parent(), "child's parent key must equal the parent");
            keys.add(key);
        }
        // Pairwise distinct with antisymmetric ordering (a strict total order).
        for (int a = 0; a < keys.size(); a++) {
            for (int b = a + 1; b < keys.size(); b++) {
                int cmp = keys.get(a).compareTo(keys.get(b));
                assertTrue(cmp != 0, "distinct level-21 siblings must not compare equal");
                assertEquals(Integer.signum(cmp), -Integer.signum(keys.get(b).compareTo(keys.get(a))),
                             "compareTo must be antisymmetric");
            }
        }
    }
}
