/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.prism;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-009 Phase 5 (Luciferase-dvk): prism subdivision tiles both halves at every level. Refining a
 * prism (in S0 or S1) into its 8 children (4 triangle × 2 line) must exactly tile the parent's
 * volume — every interior point is contained by exactly one child (no gaps, no overlap) — with the
 * children staying in the parent's root half and their SFC indices forming a contiguous ancestor
 * group. The two roots together still tile the full cube.
 *
 * <p>The tiling is a property of {@link PrismKey#child(int)} / {@link Triangle#child(int)} (the
 * t8 Bey refinement established in P2/P3, with the {@code half} propagated in P3);
 * {@link PrismSubdivisionStrategy} selects among these children. This test pins the tiling.
 *
 * @author hal.hildebrand
 */
class PrismSubdivisionTilingTest {

    private static final int CHILDREN = PrismKey.CHILDREN; // 8

    @Test
    @DisplayName("the 8 children tile the parent prism (exactly one contains each interior point) — S0 and S1")
    void childrenTileParentBothHalves() {
        var rnd = new Random(424242L);
        for (var parent : sampleParents()) {
            var children = new PrismKey[CHILDREN];
            for (int i = 0; i < CHILDREN; i++) {
                children[i] = parent.child(i);
            }
            // Sample interior points of the parent prism (barycentric on the triangle × interior z).
            var triVerts = parent.getTriangle().getVertices(); // world (x,y), half-aware
            var lineBounds = parent.getLine().getWorldBounds(); // [minZ, maxZ]
            for (int s = 0; s < 400; s++) {
                float[] bary = interiorBarycentric(rnd);
                float px = bary[0] * triVerts[0][0] + bary[1] * triVerts[1][0] + bary[2] * triVerts[2][0];
                float py = bary[0] * triVerts[0][1] + bary[1] * triVerts[1][1] + bary[2] * triVerts[2][1];
                float pz = lineBounds[0] + (0.05f + 0.9f * rnd.nextFloat()) * (lineBounds[1] - lineBounds[0]);

                int containing = 0;
                for (var child : children) {
                    if (child.contains(px, py, pz)) {
                        containing++;
                    }
                }
                assertEquals(1, containing, String.format(
                    "interior point (%.5f,%.5f,%.5f) of %s must be in exactly one child, was in %d",
                    px, py, pz, parent, containing));
            }
        }
    }

    @Test
    @DisplayName("children stay in the parent's root half (S0 children are S0, S1 children are S1)")
    void childrenStayInSameHalf() {
        for (var parent : sampleParents()) {
            int parentHalf = parent.getTriangle().getHalf();
            for (int i = 0; i < CHILDREN; i++) {
                assertEquals(parentHalf, parent.child(i).getTriangle().getHalf(),
                    "child " + i + " must stay in the parent's half");
            }
        }
    }

    @Test
    @DisplayName("children's SFC indices are the contiguous ancestor group [I(parent)*8, +8)")
    void childrenAreContiguousSfcGroup() {
        for (var parent : sampleParents()) {
            long base = parent.consecutiveIndex();
            var seen = new HashSet<Long>();
            for (int i = 0; i < CHILDREN; i++) {
                long ci = parent.child(i).consecutiveIndex();
                assertEquals(base * 8 + i, ci, "child " + i + " index must be parent*8+i");
                seen.add(ci);
            }
            assertEquals(CHILDREN, seen.size(), "8 distinct child indices");
        }
    }

    @Test
    @DisplayName("refining a triangle yields the Bey child-type multiset [b,b,b,1-b] in both halves")
    void childTypesFollowBeyBothHalves() {
        for (int half = 0; half < 2; half++) {
            for (int parentType = 0; parentType < Triangle.TYPES; parentType++) {
                var t = new Triangle(2, parentType, 1, 1, half);
                int[] counts = new int[Triangle.TYPES];
                for (int i = 0; i < Triangle.CHILDREN; i++) {
                    counts[t.child(i).getType()]++;
                }
                assertEquals(3, counts[parentType], "three children keep parent type (half " + half + ")");
                assertEquals(1, counts[1 - parentType], "one child flips (half " + half + ")");
            }
        }
    }

    @Test
    @DisplayName("the two roots' subdivisions together tile the full cube (no gap across the diagonal)")
    void twoRootsTileFullCube() {
        // Every point in [0,1)^2 (off the exact diagonal) maps to exactly one half, and to a child
        // of that half's root after one refinement.
        var rnd = new Random(99L);
        var s0Root = new PrismKey(new Triangle(0, 0, 0, 0), new Line(0, 0));
        var s1Root = PrismKey.createRootS1();
        for (int s = 0; s < 2000; s++) {
            float x = 0.001f + 0.997f * rnd.nextFloat();
            float y = 0.001f + 0.997f * rnd.nextFloat();
            if (Math.abs(x - y) < 1e-3f) {
                continue; // skip the shared diagonal
            }
            float z = 0.001f + 0.997f * rnd.nextFloat();
            int s0c = countContaining(s0Root, x, y, z);
            int s1c = countContaining(s1Root, x, y, z);
            assertEquals(1, s0c + s1c, String.format(
                "point (%.4f,%.4f) must be in exactly one root's children (S0=%d, S1=%d)", x, y, s0c, s1c));
        }
    }

    @Test
    @DisplayName("on the shared diagonal both roots contain the point (intentional shared edge, pinned)")
    void diagonalIsSharedEdgeOfBothRoots() {
        // The S0 and S1 roots share the main diagonal y=x as a closed edge, so a point exactly on
        // it is contained by both (geometrically correct). This pins the documented overlap as a
        // specification rather than a bug; canonical single-half assignment is via
        // fromWorldCoordinates (which gives the diagonal to S0). See Triangle.contains javadoc.
        var s0Root = new PrismKey(new Triangle(0, 0, 0, 0), new Line(0, 0));
        var s1Root = PrismKey.createRootS1();
        for (float t = 0.1f; t < 1.0f; t += 0.1f) {
            assertTrue(countContaining(s0Root, t, t, 0.5f) >= 1 && countContaining(s1Root, t, t, 0.5f) >= 1,
                String.format("diagonal point (%.2f,%.2f) must lie in both roots (shared edge)", t, t));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private static int countContaining(PrismKey root, float x, float y, float z) {
        int c = 0;
        for (int i = 0; i < CHILDREN; i++) {
            if (root.child(i).contains(x, y, z)) {
                c++;
            }
        }
        return c;
    }

    /** A representative set of S0 and S1 parent prisms at several levels. */
    private static PrismKey[] sampleParents() {
        var s0Root = new PrismKey(new Triangle(0, 0, 0, 0), new Line(0, 0));
        var s1Root = PrismKey.createRootS1();
        // Descend a few levels in each half (child preserves half).
        var s0Mid = s0Root.child(0).child(3);
        var s1Mid = s1Root.child(1).child(2);
        var s0Deep = s0Mid.child(5).child(2);
        var s1Deep = s1Mid.child(7).child(0);
        return new PrismKey[] { s0Root, s1Root, s0Mid, s1Mid, s0Deep, s1Deep };
    }

    /** A strictly-interior barycentric coordinate (all components > 0). */
    private static float[] interiorBarycentric(Random rnd) {
        float a = 0.05f + 0.9f * rnd.nextFloat();
        float b = 0.05f + 0.9f * rnd.nextFloat();
        if (a + b > 0.95f) {
            float scale = 0.95f / (a + b);
            a *= scale;
            b *= scale;
        }
        return new float[] { a, b, 1f - a - b };
    }
}
