/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the fail-safe, round-trip-verified {@link PyramidKeyCodec} encoders: {@code encode(Pyramid)}
 * (RDR-010 pi1.4 Phase A, bead Luciferase-8zv) and {@code encode(Tet)} + {@code elementFromKey} for a
 * shallowest tet leaf (RDR-010 pi1.5 Phase A, bead Luciferase-uqik).
 *
 * <p>Contracts:
 * <ul>
 *   <li><b>decode∘encode == identity</b> for every valid SFC pyramid (types 6 and 7, multiple levels).</li>
 *   <li><b>encode∘decode == identity</b> (key-side bijection).</li>
 *   <li><b>non-SFC / unreachable candidate → null</b> with no exception on the hot path.</li>
 *   <li><b>pyramid-rooted tet leaves round-trip</b> via {@code encode(Tet)} / {@code elementFromKey} —
 *       both shallowest ({@code minTetLevel == level}) and deep ({@code minTetLevel < level}) tets
 *       (RDR-010 cjwr Phase B); a pure-Tetree tet ({@code minTetLevel == -1}) → null.</li>
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

    // ===== pi1.5 Phase A (Luciferase-uqik): Tet->PyramidKey codec + leaf-aware decode =====

    /**
     * A shallowest tet leaf (minTetLevel == level) — the four triangular-face neighbors of a pyramid —
     * round-trips: {@code encode(Tet)} yields a non-null key whose {@code elementFromKey} recovers the
     * same Tet geometry (x,y,z,level,type). This is the bridge Phase B's detector needs to surface
     * cross-shape neighbors as tet-leaf PyramidKeys.
     */
    @Test
    void shallowTetLeafRoundTrips() {
        // root6.child(1) = tet type 3 at cubeId 1 (anchor x-bit set), level 1, minTetLevel 1.
        int h = Constants.lengthAtLevel((byte) 1);
        var tet = new Tet(h, 0, 0, (byte) 1, (byte) 3, (byte) 1);
        var key = PyramidKeyCodec.encode(tet);
        assertNotNull(key, "a shallowest tet leaf must encode to a tet-leaf PyramidKey");
        assertEquals((byte) 1, key.getLevel());
        var decoded = PyramidIndex.elementFromKey(key);
        assertTrue(decoded instanceof Tet, () -> "leaf must decode to a Tet, got " + decoded);
        var dt = (Tet) decoded;
        assertEquals(tet.x(), dt.x());
        assertEquals(tet.y(), dt.y());
        assertEquals(tet.z(), dt.z());
        assertEquals(tet.level(), dt.level());
        assertEquals(tet.type(), dt.type(), "leaf tet type must survive the round trip");
    }

    /**
     * Level-2 round-trip — the path Phase B actually exercises (a pyramid at level &gt;= 1 whose
     * triangular faces / children are shallowest tets at level &gt;= 2). This drives the {@code encode}
     * pyramid-parent walk loop and the {@code elementFromKey} descent loop, both dead at level 1.
     */
    @Test
    void shallowTetLeafRoundTripsAtLevel2() {
        // root6.child(0) = type-6 pyramid at level 1; its child(1) = tet type 3 at level 2 (shallowest).
        var p1 = new Pyramid(0, 0, 0, (byte) 1, Pyramid.TYPE_6);
        var child = p1.child(1);
        assertTrue(child instanceof Tet, () -> "precondition: level-1 pyramid child(1) is a tet, got " + child);
        var tet2 = (Tet) child;
        assertEquals((byte) 2, tet2.level());
        assertEquals(tet2.level(), tet2.minTetLevel(), "precondition: shallowest tet (minTetLevel==level)");
        var key = PyramidKeyCodec.encode(tet2);
        assertNotNull(key, "a level-2 shallowest tet leaf must encode");
        assertEquals((byte) 2, key.getLevel());
        var decoded = PyramidIndex.elementFromKey(key);
        assertTrue(decoded instanceof Tet, () -> "level-2 tet-leaf key must decode to a Tet, got " + decoded);
        var dt = (Tet) decoded;
        assertEquals(tet2.x(), dt.x());
        assertEquals(tet2.y(), dt.y());
        assertEquals(tet2.z(), dt.z());
        assertEquals(tet2.level(), dt.level());
        assertEquals(tet2.type(), dt.type());
    }

    /**
     * A pure-Tetree tet (minTetLevel == -1) has no pyramidal ancestor, so it is not an element of the
     * pyramid SFC and encodes to {@code null} — fail-safe, never a throw.
     */
    @Test
    void pureTetEncodesToNull() {
        int h2 = Constants.lengthAtLevel((byte) 2);
        var pure = new Tet(h2, 0, 0, (byte) 2, (byte) 0, (byte) -1); // minTetLevel -1 = pure-Tetree
        assertNull(PyramidKeyCodec.encode(pure), "pure-Tetree tet must encode to null");
    }

    /**
     * Deep pyramid-rooted tets (minTetLevel &lt; level, a tet-of-tet refinement below the boundary) now
     * round-trip through {@code encode(Tet)} / {@code elementFromKey} (RDR-010 cjwr Phase B). Generated
     * via the real child chain (pyramid → shallowest tet → deeper tets) so every tet is a genuine SFC
     * element. Distinct deep tets must yield distinct keys.
     */
    @Test
    void deepTetRoundTrips() {
        var seen = new java.util.HashMap<PyramidKey, Tet>();
        var checked = 0;
        // Arm 1: shallowest tets at level 2 (minTetLevel == 2), refined 2 more levels.
        for (var rootType : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var root = new Pyramid(0, 0, 0, (byte) 1, rootType);
            for (var i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (!(root.child(i) instanceof Tet shallow)) {
                    continue; // shallowest tet (minTetLevel == its level == 2)
                }
                checked += roundTripDeep(shallow, shallow, 2, seen);
            }
        }
        // Arm 2: shallowest tets at level 1 (minTetLevel == 1) — the distinct l=1 boundary code path
        // (encode/decode handle the level-1 group as a TET type, not a pyramid type). Refine 1 level.
        for (var rootType : new byte[] { Pyramid.TYPE_6, Pyramid.TYPE_7 }) {
            var root0 = new Pyramid(0, 0, 0, (byte) 0, rootType);
            for (var i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
                if (!(root0.child(i) instanceof Tet shallow1)) {
                    continue; // shallowest tet at level 1 (minTetLevel == 1)
                }
                checked += roundTripDeep(shallow1, shallow1, 1, seen);
            }
        }
        assertTrue(checked > 0, "must exercise deep pyramid-rooted tets");
    }

    /** Recurse {@code remainingDepth} below a shallowest tet, asserting each deep tet round-trips. */
    private int roundTripDeep(Tet shallow, Tet current, int remainingDepth, java.util.Map<PyramidKey, Tet> seen) {
        var count = 0;
        if (current.l > shallow.l) {
            var key = PyramidKeyCodec.encode(current);
            assertNotNull(key, "deep tet must encode (level " + current.l + ", minTetLevel "
                               + current.minTetLevel() + ", type " + current.type() + ")");
            var prior = seen.put(key, current);
            assertNull(prior, "distinct deep tets must yield distinct keys: " + current + " vs " + prior);
            var decoded = PyramidIndex.elementFromKey(key);
            assertInstanceOf(Tet.class, decoded, "deep tet key decodes to a Tet");
            var dt = (Tet) decoded;
            assertEquals(current.x(), dt.x(), "deep round-trip x");
            assertEquals(current.y(), dt.y(), "deep round-trip y");
            assertEquals(current.z(), dt.z(), "deep round-trip z");
            assertEquals(current.level(), dt.level(), "deep round-trip level");
            assertEquals(current.type(), dt.type(), "deep round-trip type");
            assertEquals(current.minTetLevel(), dt.minTetLevel(), "deep round-trip minTetLevel");
            count++;
        }
        if (remainingDepth > 0) {
            for (var k = 0; k < TetreeConnectivity.CHILDREN_PER_TET; k++) {
                count += roundTripDeep(shallow, current.child(k), remainingDepth - 1, seen);
            }
        }
        return count;
    }

    /**
     * Independent bit-value oracle for a DEEP tet-leaf key (RDR-010 cjwr Phase B; parallels the 0utt
     * SIG-3 golden-key discipline). Defends against a co-evolved error in {@code encode(Tet)} +
     * {@code elementFromKey} (e.g. a wrong {@code lengthAtLevel}) that would still round-trip
     * self-consistently. The per-level coord/type bits are derived from the leaf and its shallowest-tet
     * ancestor using <em>literal</em> level lengths ({@code 1 << (21 - l)}) — independent of
     * {@code Constants.lengthAtLevel} — and compared against the key's long-unpacking accessors.
     */
    @Test
    void goldenDeepTetKeyBitsIndependentOfLengthAtLevel() {
        // A deep tet: a level-1 shallowest tet (minTetLevel 1) refined once via Bey child 0.
        var root6 = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Tet shallow1 = null;
        for (var i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            if (root6.child(i) instanceof Tet t) {
                shallow1 = t;
                break;
            }
        }
        assertNotNull(shallow1, "root6 has a tet child");
        assertEquals(1, shallow1.minTetLevel(), "shallowest tet at level 1");
        var deep2 = (Tet) shallow1.child(0); // level 2, minTetLevel 1 (deep)
        assertEquals(2, deep2.level());
        assertEquals(1, deep2.minTetLevel(), "deep tet keeps the level-1 boundary");

        var key = PyramidKeyCodec.encode(deep2);
        assertNotNull(key, "deep tet encodes");

        final int h1 = 1 << (21 - 1); // == lengthAtLevel(1), literal — independent of Constants
        final int h2 = 1 << (21 - 2); // == lengthAtLevel(2)
        int expectCb1 = ((shallow1.x() & h1) != 0 ? 1 : 0) | ((shallow1.y() & h1) != 0 ? 2 : 0)
                        | ((shallow1.z() & h1) != 0 ? 4 : 0);
        int expectCb2 = ((deep2.x() & h2) != 0 ? 1 : 0) | ((deep2.y() & h2) != 0 ? 2 : 0)
                        | ((deep2.z() & h2) != 0 ? 4 : 0);
        assertEquals(expectCb1, key.getCoordBitsAtLevel(1), "level-1 coord bits");
        assertEquals(shallow1.type(), key.getTypeAtLevel(1), "level-1 type bits (shallowest tet type)");
        assertEquals(expectCb2, key.getCoordBitsAtLevel(2), "level-2 coord bits");
        assertEquals(deep2.type(), key.getTypeAtLevel(2), "level-2 type bits (deep tet type)");
    }

    /**
     * Independent bit-value oracle for a tet-leaf key (defends against a co-consistent error in
     * encode + elementFromKey). The level-1 tet root6.child(1) has cubeId 1, type 3, so its single
     * 6-bit group = (coordBits &lt;&lt; 3) | typeBits = (1 &lt;&lt; 3) | 3 = 11.
     */
    @Test
    void goldenTetLeafKeyIndependentOfFromLevels() {
        int h = Constants.lengthAtLevel((byte) 1);
        var tet = new Tet(h, 0, 0, (byte) 1, (byte) 3, (byte) 1);
        var key = PyramidKeyCodec.encode(tet);
        assertNotNull(key);
        assertEquals((byte) 1, key.getLevel());
        assertEquals(11L, key.getLowBits(), "golden tet-leaf lowBits = (1<<3)|3");
        assertEquals(0L, key.getHighBits());
    }

    /**
     * {@code elementFromKey} discriminates leaf shape by the leaf type bits: a tet-leaf key decodes to
     * a {@link Tet}, a pyramid key to a {@link Pyramid}.
     */
    @Test
    void elementFromKeyDiscriminatesLeafShape() {
        int h = Constants.lengthAtLevel((byte) 1);
        var tetKey = PyramidKeyCodec.encode(new Tet(h, 0, 0, (byte) 1, (byte) 3, (byte) 1));
        assertNotNull(tetKey);
        assertTrue(PyramidIndex.elementFromKey(tetKey) instanceof Tet, "tet-leaf key -> Tet");

        var pyrKey = PyramidKeyCodec.encode(new Pyramid(0, 0, 0, (byte) 2, Pyramid.TYPE_6));
        assertNotNull(pyrKey);
        assertTrue(PyramidIndex.elementFromKey(pyrKey) instanceof Pyramid, "pyramid key -> Pyramid");
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
