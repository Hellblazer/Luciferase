/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.SpatialKeySerdeRegistry;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the {@link PyramidKey} 6D-Morton encoding (Knapp Eq 3.4), the parent walk, the level-first
 * SFC ordering, and {@link PyramidKeySerde} round-tripping (RDR-010 pi1.1).
 *
 * @author hal.hildebrand
 */
class PyramidKeyTest {

    @Test
    void perStepCoordAndTypeBitsRoundTrip() {
        var rng = new Random(42);
        for (int trial = 0; trial < 1000; trial++) {
            byte level = (byte) (1 + rng.nextInt(PyramidKey.MAX_PYRAMID_LEVEL));
            int[] coord = new int[level + 1];
            int[] type = new int[level + 1];
            for (int l = 1; l <= level; l++) {
                coord[l] = rng.nextInt(8);
                type[l] = rng.nextInt(8);
            }
            var key = PyramidKey.fromLevels(level, coord, type);
            assertEquals(level, key.getLevel());
            for (int l = 1; l <= level; l++) {
                assertEquals(coord[l], key.getCoordBitsAtLevel(l), "coord bits at step " + l);
                assertEquals(type[l], key.getTypeAtLevel(l), "type bits at step " + l);
            }
            assertTrue(key.isValid(), "freshly built key must be valid");
        }
    }

    @Test
    void parentDropsDeepestStepAndWalksToRoot() {
        var rng = new Random(7);
        byte level = (byte) PyramidKey.MAX_PYRAMID_LEVEL;
        int[] coord = new int[level + 1];
        int[] type = new int[level + 1];
        for (int l = 1; l <= level; l++) {
            coord[l] = rng.nextInt(8);
            type[l] = rng.nextInt(8);
        }
        var key = PyramidKey.fromLevels(level, coord, type);

        PyramidKey cur = key;
        for (int expectedLevel = level; expectedLevel >= 1; expectedLevel--) {
            assertEquals(expectedLevel, cur.getLevel());
            var parent = cur.parent();
            assertNotNull(parent);
            assertEquals(expectedLevel - 1, parent.getLevel());
            // Parent preserves all shallower steps unchanged.
            for (int l = 1; l < expectedLevel; l++) {
                assertEquals(key.getCoordBitsAtLevel(l), parent.getCoordBitsAtLevel(l));
                assertEquals(key.getTypeAtLevel(l), parent.getTypeAtLevel(l));
            }
            cur = parent;
        }
        assertEquals(0, cur.getLevel());
        assertNull(cur.parent(), "root has no parent");
        assertEquals(PyramidKey.getRoot(), cur);
    }

    @Test
    void orderingIsLevelFirst() {
        var deepLow = PyramidKey.fromLevels((byte) 2, new int[] { 0, 0, 0 }, new int[] { 0, 0, 0 });
        var shallowHigh = PyramidKey.fromLevels((byte) 1, new int[] { 0, 7 }, new int[] { 0, 7 });
        assertTrue(shallowHigh.compareTo(deepLow) < 0, "lower level sorts first regardless of bits");
        assertTrue(deepLow.compareTo(shallowHigh) > 0);
        assertEquals(0, deepLow.compareTo(deepLow));
    }

    @Test
    void orderingIsCoarseDominantAcrossLongBoundary() {
        // Canonical m_P order (Knapp Eq 3.4): the shallowest (coarsest) step dominates. At level 15
        // step 1 lives in the high long (bit (15-1)*6 = 84) and the deepest step lives in the low long.
        // A difference at the shallow step must outweigh any difference at deeper steps.
        byte level = 15;
        int[] cA = new int[level + 1];
        int[] cB = new int[level + 1];
        int[] type = new int[level + 1];
        // A: shallow step small, deepest step maximal.   B: shallow step large, deepest step zero.
        cA[1] = 0;
        cA[level] = 7;
        type[level] = 7; // any
        cB[1] = 1;
        cB[level] = 0;
        var a = PyramidKey.fromLevels(level, cA, type);
        var b = PyramidKey.fromLevels(level, cB, type);
        assertTrue(a.compareTo(b) < 0, "shallow step must dominate ordering over deeper steps");
        assertTrue(b.compareTo(a) > 0);

        // Differing only at the deepest step orders by that step (the tiebreaker).
        int[] cC = new int[level + 1];
        int[] cD = new int[level + 1];
        cC[level] = 0;
        cD[level] = 1;
        assertTrue(PyramidKey.fromLevels(level, cC, type).compareTo(PyramidKey.fromLevels(level, cD, type)) < 0);
    }

    @Test
    void isValidRejectsBitsAboveOccupiedGroups() {
        // Level 5: usedBits = 30; low bits >= 30 must be zero, high must be zero.
        assertTrue(new PyramidKey((byte) 5, (1L << 30) - 1, 0L).isValid());
        assertTrue(!new PyramidKey((byte) 5, 1L << 30, 0L).isValid(), "low bits above level-5 groups invalid");
        assertTrue(!new PyramidKey((byte) 5, 0L, 1L).isValid(), "high bits must be zero for shallow levels");
        // Level 11: usedBits = 66; low fully used, high bits >= 2 must be zero.
        assertTrue(new PyramidKey((byte) 11, -1L, 0x3L).isValid());
        assertTrue(!new PyramidKey((byte) 11, -1L, 0x4L).isValid(), "high bits above level-11 groups invalid");
        // Level 21 (max): usedBits = 126; high bits >= 62 must be zero.
        assertTrue(new PyramidKey((byte) 21, -1L, (1L << 62) - 1).isValid());
        assertTrue(!new PyramidKey((byte) 21, -1L, 1L << 62).isValid(), "high bits above level-21 groups invalid");
    }

    @Test
    void rootIsZeroAndValid() {
        var root = PyramidKey.getRoot();
        assertEquals(0, root.getLevel());
        assertEquals(0L, root.getLowBits());
        assertEquals(0L, root.getHighBits());
        assertTrue(root.isValid());
        assertNull(root.parent());
        assertSame(PyramidKey.class, root.root().getClass());
    }

    @Test
    void rejectsOutOfRangeLevel() {
        assertThrows(IllegalArgumentException.class,
                     () -> new PyramidKey((byte) (PyramidKey.MAX_PYRAMID_LEVEL + 1), 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new PyramidKey((byte) -1, 0L, 0L));
    }

    @Test
    void serdeRoundTrips() {
        var rng = new Random(99);
        for (int trial = 0; trial < 500; trial++) {
            byte level = (byte) rng.nextInt(PyramidKey.MAX_PYRAMID_LEVEL + 1);
            int[] coord = new int[level + 1];
            int[] type = new int[level + 1];
            for (int l = 1; l <= level; l++) {
                coord[l] = rng.nextInt(8);
                type[l] = rng.nextInt(8);
            }
            var key = PyramidKey.fromLevels(level, coord, type);
            var bytes = PyramidKeySerde.INSTANCE.serialize(key);
            assertEquals(17, bytes.length);
            var back = PyramidKeySerde.INSTANCE.deserialize(bytes);
            assertEquals(key, back);
            assertEquals(0, key.compareTo(back));
        }
    }

    @Test
    void serdeRegistersWithRegistry() {
        SpatialKeySerdeRegistry.register(PyramidKeySerde.INSTANCE);
        assertSame(PyramidKeySerde.INSTANCE, SpatialKeySerdeRegistry.forKey(PyramidKey.getRoot()));
        assertEquals("pyramid", PyramidKeySerde.INSTANCE.typeId());
    }

    @Test
    void serdeRejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> PyramidKeySerde.INSTANCE.deserialize(new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> PyramidKeySerde.INSTANCE.deserialize(null));
    }

    @Test
    void estimateSFCRangeIsOrderedAndLevelConsistent() {
        var center = new Point3f(1000, 2000, 3000);
        for (float radius : new float[] { 1f, 16f, 256f, 4096f, 65536f }) {
            var range = PyramidKey.estimateSFCRange(center, radius);
            assertTrue(range.lower().compareTo(range.upper()) <= 0, "range must be ordered for radius " + radius);
            assertEquals(range.lower().getLevel(), range.upper().getLevel(), "bounds share a level");
        }
        assertThrows(IllegalArgumentException.class, () -> PyramidKey.estimateSFCRange(center, 0f));
        assertThrows(IllegalArgumentException.class,
                     () -> PyramidKey.estimateSFCRange(new Point3f(-1, 0, 0), 5f));
    }
}
