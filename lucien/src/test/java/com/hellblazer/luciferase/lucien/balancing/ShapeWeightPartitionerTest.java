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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.6 Phase B: weighted space-filling-curve cumulative-offset partition (Knapp Algorithm 5.1 /
 * Burstedde+Holke weighted partition). Each contiguous unit (forest tree) is assigned to the rank its
 * cumulative-weight start offset falls into, so every rank receives ≈ W/P total weight.
 *
 * <p>The shape-correctness point: weights come from {@link ShapeWeightProvider#elementCount(int)}, so a
 * pyramid tree (N = 2·8^ℓ − 6^ℓ) carries more weight than a hex tree (N = 8^ℓ) and shifts the partition
 * boundaries accordingly — a shape-blind 1:8 weighting would mis-balance.
 */
class ShapeWeightPartitionerTest {

    private static long total(long[] w) {
        long s = 0;
        for (long x : w) {
            s += x;
        }
        return s;
    }

    private static long[] rankWeights(long[] w, int[] assign, int p) {
        long[] rw = new long[p];
        for (int i = 0; i < w.length; i++) {
            rw[assign[i]] += w[i];
        }
        return rw;
    }

    @Test
    void ranksAreContiguousAndInRange() {
        long[] w = { 5, 3, 8, 1, 4, 9, 2, 6 };
        int p = 3;
        int[] a = ShapeWeightPartitioner.assign(w, p);
        assertEquals(w.length, a.length);
        for (int i = 0; i < a.length; i++) {
            assertTrue(a[i] >= 0 && a[i] < p, "rank in range at " + i);
            if (i > 0) {
                assertTrue(a[i] >= a[i - 1], "ranks must be non-decreasing (contiguous SFC order)");
            }
        }
        assertEquals(0, a[0], "first unit goes to rank 0");
    }

    @Test
    void equalWeightsSplitEvenlyWhenDivisible() {
        long[] w = { 1, 1, 1, 1, 1, 1 };
        int[] a = ShapeWeightPartitioner.assign(w, 3);
        assertArrayEquals(new int[] { 0, 0, 1, 1, 2, 2 }, a, "6 equal units over 3 ranks → 2 each");
    }

    @Test
    void weightedBalanceWithinLargestUnit() {
        // SFC weighted-partition quality bound: each rank's total weight stays within one max-unit-weight
        // of the ideal W/P. (Indivisible units cannot do better than this.)
        long[] w = { 7, 2, 5, 9, 1, 4, 6, 3, 8 };
        int p = 4;
        int[] a = ShapeWeightPartitioner.assign(w, p);
        long W = total(w);
        long ideal = W / p;
        long maxUnit = 9;
        for (long rw : rankWeights(w, a, p)) {
            // SFC partition of indivisible units: each rank stays within one max-unit of ideal on each
            // side (theoretical bound 2·maxUnit on the spread).
            assertTrue(rw <= ideal + 2 * maxUnit,
                       "rank weight " + rw + " must be within 2 max-units of ideal " + ideal);
        }
        // Total weight conserved across ranks.
        assertEquals(W, total(rankWeights(w, a, p)));
    }

    @Test
    void singlePartitionPutsEverythingInRankZero() {
        assertArrayEquals(new int[] { 0, 0, 0 }, ShapeWeightPartitioner.assign(new long[] { 5, 1, 9 }, 1));
        assertArrayEquals(new int[] { 0, 0, 0 }, ShapeWeightPartitioner.assign(new long[] { 0, 0, 0 }, 1),
                          "P=1 with zero weights must also land all in rank 0 (clamp)");
    }

    @Test
    void singleUnitGoesToRankZero() {
        assertArrayEquals(new int[] { 0 }, ShapeWeightPartitioner.assign(new long[] { 42 }, 4));
    }

    @Test
    void negativeWeightThrows() {
        assertThrows(IllegalArgumentException.class,
                     () -> ShapeWeightPartitioner.assign(new long[] { 1, -1, 1 }, 2));
    }

    @Test
    void highLevelWeightsDoNotOverflowTheRankComputation() {
        // Regression for the prefix·P long-overflow: 3 hex trees at level 20 (8^20 = 2^60 each), P=4.
        // prefix at unit 2 = 2^61; prefix·4 = 2^63 overflows a signed long — the double reformulation
        // must still yield a valid contiguous assignment (no negative ranks).
        long n8_20 = ShapeWeightProvider.eightToThe(20); // 2^60
        long[] w = { n8_20, n8_20, n8_20 };
        int[] a = ShapeWeightPartitioner.assign(w, 4);
        for (int i = 0; i < a.length; i++) {
            assertTrue(a[i] >= 0 && a[i] < 4, "rank must be in [0,4) at " + i + ", got " + a[i]);
            if (i > 0) {
                assertTrue(a[i] >= a[i - 1], "ranks must stay non-decreasing under high weights");
            }
        }
    }

    @Test
    void allZeroWeightsFallBackToEvenIndexSplit() {
        long[] w = { 0, 0, 0, 0 };
        int[] a = ShapeWeightPartitioner.assign(w, 2);
        assertArrayEquals(new int[] { 0, 0, 1, 1 }, a, "zero weights → contiguous index split");
    }

    @Test
    void invalidPartitionCountThrows() {
        assertThrows(IllegalArgumentException.class, () -> ShapeWeightPartitioner.assign(new long[] { 1 }, 0));
        assertThrows(IllegalArgumentException.class, () -> ShapeWeightPartitioner.assign(new long[] { 1 }, -1));
    }

    @Test
    void emptyWeightsYieldEmptyAssignment() {
        assertEquals(0, ShapeWeightPartitioner.assign(new long[0], 3).length);
    }

    @Test
    void shapeAwareWeightsShiftBoundariesVsShapeBlind() {
        // Hybrid weighting (the pi1.6 fix): a forest of [hex,hex,hex,pyramid,hex,hex] at level 1.
        // Shape-aware: N_hex(1)=8, N_pyramid(1)=10 → [8,8,8,10,8,8], W=50, P=3.
        //   boundaries at 50/3≈16.7 and 33.3 → assignment [0,0,0,1,2,2].
        // Shape-blind (every tree 1:8 = 8): [8,8,8,8,8,8], W=48 → [0,0,1,1,2,2].
        // The pyramid's extra weight pushes unit index 2 from rank 1 back into rank 0 — a real,
        // boundary-shifting difference, proving the weight is consulted (non-vacuous).
        var hex = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var pyr = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        long[] shapeAware = ShapeWeightPartitioner.weightsAtLevel(List.of(hex, hex, hex, pyr, hex, hex), 1);
        assertArrayEquals(new long[] { 8, 8, 8, 10, 8, 8 }, shapeAware, "shape-aware weights via provider");

        long[] shapeBlind = { 8, 8, 8, 8, 8, 8 };
        int[] aware = ShapeWeightPartitioner.assign(shapeAware, 3);
        int[] blind = ShapeWeightPartitioner.assign(shapeBlind, 3);
        assertArrayEquals(new int[] { 0, 0, 0, 1, 2, 2 }, aware, "shape-aware partition");
        assertArrayEquals(new int[] { 0, 0, 1, 1, 2, 2 }, blind, "shape-blind partition");
        assertFalse(java.util.Arrays.equals(aware, blind),
                    "the pyramid tree's weight must shift a partition boundary vs shape-blind");
    }

    // ---- S2 (Luciferase-3uwx): cut-points + ownerOf for owner-range descent ----

    @Test
    void cutPointsAreContiguousRankOffsets() {
        // ranks = [0,0,0,1,1,1,2,2] → offsets[r] = first unit index owned by rank r; offsets[P]=n.
        long[] w = { 5, 3, 8, 1, 4, 9, 2, 6 };
        int p = 3;
        int[] ranks = ShapeWeightPartitioner.assign(w, p);
        int[] cp = ShapeWeightPartitioner.cutPoints(ranks, p);
        assertArrayEquals(new int[] { 0, 3, 6, 8 }, cp, "offsets length P+1, non-decreasing, [0]=0, [P]=n");
    }

    @Test
    void ownerOfRecoversTheRankOfEachUnit() {
        long[] w = { 5, 3, 8, 1, 4, 9, 2, 6 };
        int p = 3;
        int[] ranks = ShapeWeightPartitioner.assign(w, p);
        int[] cp = ShapeWeightPartitioner.cutPoints(ranks, p);
        for (int i = 0; i < ranks.length; i++) {
            assertEquals(ranks[i], ShapeWeightPartitioner.ownerOf(cp, i),
                         "ownerOf(cutPoints, i) must equal ranks[i] at unit " + i);
        }
    }

    @Test
    void cutPointsHandleEmptyRanks() {
        // All weight on the middle unit: ranks = [0,0,2], rank 1 is empty (zero-width band).
        long[] w = { 0, 10, 0 };
        int p = 3;
        int[] ranks = ShapeWeightPartitioner.assign(w, p);
        assertArrayEquals(new int[] { 0, 0, 2 }, ranks, "high weight on last unit clamps it to rank P-1");
        int[] cp = ShapeWeightPartitioner.cutPoints(ranks, p);
        assertArrayEquals(new int[] { 0, 2, 2, 3 }, cp, "empty rank 1 → offsets[1]==offsets[2]");
        assertEquals(0, ShapeWeightPartitioner.ownerOf(cp, 0));
        assertEquals(0, ShapeWeightPartitioner.ownerOf(cp, 1));
        assertEquals(2, ShapeWeightPartitioner.ownerOf(cp, 2), "unit 2 owned by rank 2, skipping empty rank 1");
    }

    @Test
    void ownerOfRejectsOutOfRangeUnitIndex() {
        int[] cp = ShapeWeightPartitioner.cutPoints(new int[] { 0, 0, 1 }, 2);
        assertThrows(IndexOutOfBoundsException.class, () -> ShapeWeightPartitioner.ownerOf(cp, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> ShapeWeightPartitioner.ownerOf(cp, 3));
    }

    @Test
    void cutPointsOfEmptyAssignment() {
        int[] cp = ShapeWeightPartitioner.cutPoints(new int[0], 3);
        assertArrayEquals(new int[] { 0, 0, 0, 0 }, cp, "no units → all offsets zero");
    }
}
