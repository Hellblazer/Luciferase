/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates {@link PyramidKeyCodec#encode(Pyramid)} — the fail-safe, round-trip-verified inverse of
 * {@link PyramidIndex#pyramidFromKey(PyramidKey)} (RDR-010 pi1.4 Phase A, bead Luciferase-8zv).
 *
 * <p>Three contracts:
 * <ul>
 *   <li><b>decode∘encode == identity</b> for every valid SFC pyramid (types 6 and 7, multiple levels).</li>
 *   <li><b>encode∘decode == identity</b> (key-side bijection).</li>
 *   <li><b>non-SFC / unreachable candidate → null</b> with no exception on the hot path.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class PyramidKeyCodecTest {

    /**
     * Enumerate genuine SFC pyramids by descending the two roots via {@link Pyramid#child(int)},
     * keeping only the pyramid children, down to {@code maxLevel}.
     */
    private static List<Pyramid> validPyramids(int maxLevel) {
        var out = new ArrayList<Pyramid>();
        var roots = new Pyramid[] { new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6),
                                    new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7) };
        for (var root : roots) {
            descend(root, maxLevel, out);
        }
        return out;
    }

    private static void descend(Pyramid p, int maxLevel, List<Pyramid> out) {
        if (p.level() >= 1) {
            out.add(p); // level-0 roots are the virtual cover, not encodable SFC elements
        }
        if (p.level() >= maxLevel) {
            return;
        }
        for (int i = 0; i < com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            HybridElement child = p.child(i);
            if (child instanceof Pyramid pc) {
                descend(pc, maxLevel, out);
            }
        }
    }

    @Test
    void decodeOfEncodeIsIdentityForValidPyramids() {
        var pyramids = validPyramids(4);
        assertFalse(pyramids.isEmpty(), "expected to enumerate some SFC pyramids");
        boolean sawType6 = false, sawType7 = false;
        for (var p : pyramids) {
            var key = PyramidKeyCodec.encode(p);
            assertNotNull(key, () -> "valid SFC pyramid must encode: " + p);
            assertEquals(p.level(), key.getLevel(), () -> "encoded level must match: " + p);
            var decoded = PyramidIndex.pyramidFromKey(key);
            assertEquals(p, decoded, () -> "decode∘encode must be identity for " + p + " key=" + key);
            sawType6 |= p.type() == Pyramid.TYPE_6;
            sawType7 |= p.type() == Pyramid.TYPE_7;
        }
        assertTrue(sawType6 && sawType7, "coverage must include both pyramid types");
    }

    @Test
    void encodeOfDecodeIsIdentityForValidKeys() {
        for (var p : validPyramids(4)) {
            var key = PyramidKeyCodec.encode(p);
            assertNotNull(key);
            var decoded = PyramidIndex.pyramidFromKey(key);
            assertNotNull(decoded);
            var key2 = PyramidKeyCodec.encode(decoded);
            assertEquals(key, key2, () -> "encode∘decode must be identity (key-side) for " + p);
        }
    }

    @Test
    void rootEncodesToRootKey() {
        var root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        var key = PyramidKeyCodec.encode(root);
        assertNotNull(key);
        assertEquals(PyramidKey.getRoot(), key);
    }

    @Test
    void goldenKeyForKnownPyramidIndependentOfFromLevels() {
        // Independent bit-value oracle (defends against a co-consistent error in fromLevels + decode).
        // P = type-6 pyramid at (0,0,0) level 2 = root6.child0.child0 (both steps: cubeId 0, type 6).
        // Per the documented coarse-dominant layout: each step's 6-bit group = (coordBits<<3)|typeBits,
        // step 1 (shallowest) at the MSB end. group1 = (0<<3)|6 = 6; group2 = 6.
        // lowBits = (group1 << 6) | group2 = (6 << 6) | 6 = 390; highBits = 0.
        var p = new Pyramid(0, 0, 0, (byte) 2, Pyramid.TYPE_6);
        var key = PyramidKeyCodec.encode(p);
        assertNotNull(key);
        assertEquals((byte) 2, key.getLevel());
        assertEquals(390L, key.getLowBits(), "golden lowBits computed from the spec layout");
        assertEquals(0L, key.getHighBits());
        assertEquals(p, PyramidIndex.pyramidFromKey(key), "golden key must decode back to P");
    }

    @Test
    void hybridPathPyramidEncodesToNull() {
        // A pyramid carrying minTetLevel != -1 (reached via a tet ancestor) is not a pure-pyramid SFC
        // element. Even when it shares geometry with a reachable pure-pyramid cell — and Pyramid.equals
        // is minTetLevel-blind — encode must reject it (null), not emit the pure cell's key.
        var hybrid = new Pyramid(0, 0, 0, (byte) 2, Pyramid.TYPE_6, (byte) 1); // minTetLevel = 1
        var pure = new Pyramid(0, 0, 0, (byte) 2, Pyramid.TYPE_6);             // same geometry
        assertEquals(hybrid, pure, "precondition: equals is minTetLevel-blind");
        assertNotNull(PyramidKeyCodec.encode(pure), "the pure-pyramid cell does encode");
        assertNull(PyramidKeyCodec.encode(hybrid), "the hybrid-path pyramid must encode to null");
    }

    @Test
    void type7RootIsNotADistinctSfcElementAndEncodesToNull() {
        // The SFC root is the virtual type-6 cover; pyramidFromKey returns type 6 at level 0. A
        // level-0 type-7 pyramid is therefore not a distinct SFC element and must encode to null
        // rather than aliasing onto the single root key.
        var type7Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7);
        assertNull(PyramidKeyCodec.encode(type7Root));
    }

    @Test
    void unreachablePyramidReturnsNullWithoutThrowing() {
        // PYRAMID_TYPE_CID_TO_PARENT_TYPE[type6][cubeId 5] == -1 (unreachable). A level-1 type-6
        // pyramid at cube-id 5 (anchor bits x=1,z=1) is geometrically non-SFC: the round-trip
        // self-check (or the parent() "Unreachable pyramid" throw on deeper variants) must funnel
        // it to null rather than emitting a bogus key.
        int h = Constants.lengthAtLevel((byte) 1);
        var nonSfc = new Pyramid(h, 0, h, (byte) 1, Pyramid.TYPE_6); // cubeId 5
        PyramidKey[] holder = new PyramidKey[1];
        assertDoesNotThrow(() -> holder[0] = PyramidKeyCodec.encode(nonSfc),
                           "encode must be fail-safe on the hot path (no throw)");
        assertNull(holder[0], "non-SFC unreachable pyramid must encode to null");
    }

    @Test
    void deepUnreachablePyramidReturnsNull() {
        // A type-7 pyramid at cube-id 1 (anchor bit x=1) is unreachable
        // (PYRAMID_TYPE_CID_TO_PARENT_TYPE[type7][1] == -1). Place it at level 2 so the parent()
        // walk reaches the unreachable step and its IllegalStateException is caught.
        int h2 = Constants.lengthAtLevel((byte) 2);
        var nonSfc = new Pyramid(h2, 0, 0, (byte) 2, Pyramid.TYPE_7); // level-2 cubeId 1
        assertDoesNotThrow(() -> assertNull(PyramidKeyCodec.encode(nonSfc)));
    }
}
