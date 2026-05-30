/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.pyramid.PyramidIndex;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.6 Phase A: the per-shape element-count weight {@code N_shape(level)} (Knapp Eq 5.1).
 *
 * <p>{@code N_hex = N_tet = 8^ℓ} (1:8 refinement); {@code N_pyramid = 2·8^ℓ − 6^ℓ}. Overflow-guarded
 * (level ≤ 20, since {@code 8^21 = 2^63} overflows a signed long).
 */
class ShapeWeightProviderTest {

    private static long pow(long base, int exp) {
        long r = 1;
        for (int i = 0; i < exp; i++) {
            r *= base;
        }
        return r;
    }

    private Octree<LongEntityID, String> octree() {
        return new Octree<>(new SequentialLongIDGenerator());
    }

    private Tetree<LongEntityID, String> tetree() {
        return new Tetree<>(new SequentialLongIDGenerator());
    }

    private PyramidIndex<LongEntityID, String> pyramid() {
        return new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    @Test
    void octreeAndTetreeUse8ToTheLevel() {
        var hex = octree();
        var tet = tetree();
        for (int l = 0; l <= 15; l++) {
            long expected = pow(8, l);
            assertEquals(expected, hex.elementCount(l), "N_hex(" + l + ") = 8^" + l);
            assertEquals(expected, tet.elementCount(l), "N_tet(" + l + ") = 8^" + l);
        }
        assertEquals(1L, hex.elementCount(0), "N_hex(0) = 1");
        assertEquals(8L, hex.elementCount(1), "N_hex(1) = 8");
    }

    @Test
    void pyramidUsesKnappEq51() {
        var pyr = pyramid();
        // N_pyramid(ℓ) = 2·8^ℓ − 6^ℓ. Golden values:
        // ℓ=0: 2−1 = 1; ℓ=1: 16−6 = 10 (== 6 pyramids + 4 tets); ℓ=2: 128−36 = 92;
        // ℓ=3: 1024−216 = 808; ℓ=4: 8192−1296 = 6896.
        assertEquals(1L, pyr.elementCount(0), "N_pyramid(0) = 1");
        assertEquals(10L, pyr.elementCount(1), "N_pyramid(1) = 10 (6 pyr + 4 tet)");
        assertEquals(92L, pyr.elementCount(2), "N_pyramid(2) = 92");
        assertEquals(808L, pyr.elementCount(3), "N_pyramid(3) = 808");
        assertEquals(6896L, pyr.elementCount(4), "N_pyramid(4) = 6896");
        for (int l = 0; l <= 15; l++) {
            assertEquals(2 * pow(8, l) - pow(6, l), pyr.elementCount(l), "N_pyramid(" + l + ")");
        }
    }

    @Test
    void pyramidWeightExceedsHexAtEveryPositiveLevel() {
        // The load-bearing property: a pyramid root holds MORE elements than a hex/tet root at every
        // ℓ ≥ 1, so a shape-blind 8^ℓ weight under-counts pyramid trees (the partition bug pi1.6 fixes).
        var pyr = pyramid();
        var hex = octree();
        for (int l = 1; l <= 15; l++) {
            assertTrue(pyr.elementCount(l) > hex.elementCount(l),
                       "N_pyramid(" + l + ") must exceed N_hex(" + l + ")");
        }
        assertEquals(hex.elementCount(0), pyr.elementCount(0), "both = 1 at level 0");
    }

    @Test
    void negativeLevelThrows() {
        assertThrows(IllegalArgumentException.class, () -> octree().elementCount(-1));
        assertThrows(IllegalArgumentException.class, () -> pyramid().elementCount(-1));
    }

    @Test
    void overflowingLevelThrows() {
        // 8^21 = 2^63 overflows signed long; level must be <= 20.
        assertThrows(IllegalArgumentException.class, () -> octree().elementCount(21));
        assertThrows(IllegalArgumentException.class, () -> pyramid().elementCount(21));
        // Level 20 is the largest that fits.
        assertDoesNotThrow(() -> octree().elementCount(20));
        assertDoesNotThrow(() -> pyramid().elementCount(20));
    }
}
