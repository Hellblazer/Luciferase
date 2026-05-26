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

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-009 Phase 1 (Luciferase-al5): pins the reconciliation of {@link Triangle#getType()}
 * to the t8code/Bey orientation definition and the {@code n} auxiliary-coordinate
 * single-source-of-truth ({@code n = min(x, y)}) with a consistent parent/child round-trip.
 *
 * <p><b>Geometry (t8 main-diagonal split).</b> A type-0 triangle is the lower-right
 * Kuhn simplex of its grid cell — vertices {@code (anchor, anchor+x̂, anchor+x̂+ŷ)} —
 * and a type-1 triangle is the upper-left simplex — {@code (anchor, anchor+ŷ, anchor+x̂+ŷ)}.
 * The two halves are split along the cell's <i>main</i> diagonal (anchor → opposite
 * corner), matching t8code's reference where the root S0 is the lower-right triangle
 * {@code {0 ≤ y ≤ x}} of the square. (The prior implementation split each cell along the
 * <i>anti</i>-diagonal, which is not the Bey orientation.)
 *
 * <p><b>Bey classification.</b> {@code Type(T) = i ⟺ T ≃ Sᵢ}. Refining a triangle of type
 * {@code b} yields children whose types form the multiset {@code [b, b, b, 1-b]} (Burstedde &
 * Holke, TM-SFC paper, Table 1 / Fig 7): three corner-children keep the parent's orientation
 * and one flips. The prior {@code (type + i%2)%2} produced the non-Bey alternating multiset
 * {@code [b, b, 1-b, 1-b]}.
 *
 * <p><b>{@code n} fate.</b> {@code n} is derivable as {@code min(x, y)} and carries no term in
 * the TM-index, so it is retained this phase only as a derived <i>cache</i> (its elimination is
 * deferred to the TM-index adoption in RDR-009 P2/P7, which drops it from the key). Every
 * triangle produced by the world-coordinate constructors or by {@code child()}/{@code parent()}
 * must satisfy {@code n == min(x, y)}, and {@code child(i).parent()} must reproduce the parent's
 * {@code n} for every child index.
 *
 * @author hal.hildebrand
 */
class TriangleBeyTypeTest {

    private static final float EPS = 1e-5f;

    // ── Geometry: type → t8 main-diagonal orientation ────────────────────────────────

    @Test
    @DisplayName("type-0 vertices are the lower-right Kuhn simplex (main-diagonal split)")
    void typeZeroIsLowerRight() {
        // Level-1 cell at anchor (0,0): world cell [0,0.5]×[0,0.5].
        var t0 = new Triangle(1, 0, 0, 0, 0);
        assertVertices(t0.getVertices(), new float[][] { { 0f, 0f }, { 0.5f, 0f }, { 0.5f, 0.5f } });
    }

    @Test
    @DisplayName("type-1 vertices are the upper-left Kuhn simplex (main-diagonal split)")
    void typeOneIsUpperLeft() {
        var t1 = new Triangle(1, 1, 0, 0, 0);
        assertVertices(t1.getVertices(), new float[][] { { 0f, 0f }, { 0f, 0.5f }, { 0.5f, 0.5f } });
    }

    // ── Point location matches orientation, and is consistent with contains() ─────────

    @Test
    @DisplayName("point location assigns the main-diagonal half-cell type and contains the point")
    void pointLocationMatchesOrientationAndContains() {
        for (int level = 1; level <= 6; level++) {
            int scale = 1 << level;
            // Sweep a grid of interior sample points, skipping any that land on a cell's
            // main diagonal (localX == localY), where the type is boundary-ambiguous.
            for (int i = 1; i < 8; i++) {
                for (int j = 1; j < 8; j++) {
                    float wx = i / 8.0f * 0.999f;
                    float wy = j / 8.0f * 0.999f;
                    if (wy > wx) {
                        continue; // RDR-009 P2: only the S0 root (y <= x) is indexable until P3
                    }
                    int qx = Math.min((int) (wx * scale), scale - 1);
                    int qy = Math.min((int) (wy * scale), scale - 1);
                    float localX = wx * scale - qx;
                    float localY = wy * scale - qy;
                    if (Math.abs(localX - localY) < 1e-3f) {
                        continue; // on/near the diagonal — skip ambiguous boundary
                    }
                    int expectedType = (localY > localX) ? 1 : 0;
                    var tri = Triangle.fromWorldCoordinates(wx, wy, level);
                    assertEquals(expectedType, tri.getType(), String.format(
                        "type mismatch at level=%d (%.4f,%.4f) localX=%.3f localY=%.3f", level, wx, wy, localX,
                        localY));
                    assertTrue(tri.contains(wx, wy), String.format(
                        "located triangle must contain its own point at level=%d (%.4f,%.4f)", level, wx, wy));
                }
            }
        }
    }

    @Test
    @DisplayName("fromWorldCoordinate and fromWorldCoordinates agree on type, n, x, y")
    void bothConstructionPathsAgree() {
        for (int level = 1; level <= 8; level++) {
            for (int i = 1; i < 10; i++) {
                for (int j = 1; j < 10; j++) {
                    float wx = i / 10.0f * 0.999f;
                    float wy = j / 10.0f * 0.999f;
                    if (wy > wx) {
                        continue; // RDR-009 P2: only the S0 root (y <= x) is indexable until P3
                    }
                    var a = Triangle.fromWorldCoordinate(wx, wy, level);
                    var b = Triangle.fromWorldCoordinates(wx, wy, level);
                    assertEquals(b.getType(), a.getType(), "type disagreement at " + wx + "," + wy + " L" + level);
                    assertEquals(b.getN(), a.getN(), "n disagreement at " + wx + "," + wy + " L" + level);
                    assertEquals(b.getX(), a.getX(), "x disagreement at " + wx + "," + wy + " L" + level);
                    assertEquals(b.getY(), a.getY(), "y disagreement at " + wx + "," + wy + " L" + level);
                }
            }
        }
    }

    // ── Bey child-type classification ─────────────────────────────────────────────────

    @Test
    @DisplayName("refining type b yields the Bey multiset [b,b,b,1-b] (three keep, one flips)")
    void childTypesFollowBeyPattern() {
        // This asserts the Bey multiset SHAPE only (three children keep the parent's orientation,
        // one flips) — NOT which cube-id carries the flip. The current placeholder flips cube-id 3;
        // the geometrically-faithful Bey interior-child is type-dependent and is reconciled in
        // RDR-009 Phase 2 (see Triangle.computeChildType javadoc). Pinning the index here would
        // pre-commit a value P2 must change, so the test deliberately checks counts, not position.
        for (int parentType = 0; parentType < Triangle.TYPES; parentType++) {
            // Anchor (1,1) at level 2 → children at level 3 with coords ≤ 3 < 2^3.
            var parent = new Triangle(2, parentType, 1, 1, Math.min(1, 1));
            int[] counts = new int[Triangle.TYPES];
            for (int i = 0; i < Triangle.CHILDREN; i++) {
                counts[parent.child(i).getType()]++;
            }
            assertEquals(3, counts[parentType], "three corner-children must keep parent type " + parentType);
            assertEquals(1, counts[1 - parentType], "exactly one child must flip from type " + parentType);
        }
    }

    // ── Parent/child round-trips: type and n ───────────────────────────────────────────

    @Test
    @DisplayName("child(i).parent() reproduces the parent's type for all children and types")
    void typeRoundTrip() {
        int[][] anchors = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 2, 1 }, { 3, 2 }, { 5, 3 } };
        for (int level = 1; level < Triangle.MAX_LEVEL; level++) {
            for (int type = 0; type < Triangle.TYPES; type++) {
                for (int[] a : anchors) {
                    int max = 1 << level;
                    if (a[0] >= max || a[1] >= max) {
                        continue;
                    }
                    var parent = new Triangle(level, type, a[0], a[1], Math.min(a[0], a[1]));
                    for (int i = 0; i < Triangle.CHILDREN; i++) {
                        var child = parent.child(i);
                        assertEquals(i, child.getChildIndex(), "child index must round-trip");
                        assertEquals(parent.getType(), child.parent().getType(), String.format(
                            "type round-trip failed: L=%d type=%d anchor=(%d,%d) child=%d", level, type, a[0], a[1],
                            i));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("n == min(x,y) for produced triangles, and child(i).parent() reproduces parent n")
    void nIsSingleSourceOfTruthAndRoundTrips() {
        for (int level = 1; level < Triangle.MAX_LEVEL; level++) {
            int scale = 1 << level;
            for (int i = 1; i < 8; i++) {
                for (int j = 1; j < 8; j++) {
                    float wx = i / 8.0f * 0.999f;
                    float wy = j / 8.0f * 0.999f;
                    if (wy > wx) {
                        continue; // RDR-009 P2: only the S0 root (y <= x) is indexable until P3
                    }
                    var parent = Triangle.fromWorldCoordinates(wx, wy, level);
                    assertEquals(Math.min(parent.getX(), parent.getY()), parent.getN(),
                        "constructed triangle must satisfy n = min(x,y)");
                    if (level + 1 > Triangle.MAX_LEVEL) {
                        continue;
                    }
                    for (int c = 0; c < Triangle.CHILDREN; c++) {
                        var child = parent.child(c);
                        assertEquals(Math.min(child.getX(), child.getY()), child.getN(),
                            "child must satisfy n = min(x,y)");
                        assertEquals(parent.getN(), child.parent().getN(),
                            "child(i).parent() must reproduce parent n");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("n round-trip holds for the root and its descendants")
    void nRoundTripFromRoot() {
        var root = new Triangle(0, 0, 0, 0, 0);
        assertEquals(0, root.getN());
        // Descend an arbitrary child path and verify the invariant at each level.
        var t = root;
        int[] path = { 3, 1, 2, 0, 3, 2 };
        for (int step : path) {
            var child = t.child(step);
            assertEquals(Math.min(child.getX(), child.getY()), child.getN(), "n=min(x,y) at each refinement");
            assertEquals(t.getN(), child.parent().getN(), "parent n reproduced after child→parent");
            assertEquals(t.getType(), child.parent().getType(), "parent type reproduced after child→parent");
            t = child;
        }
    }

    // ── PrismGeometry must use the triangle's orientation (regression guard) ──────────

    @Test
    @DisplayName("PrismGeometry vertices match Triangle.getVertices() orientation for both types")
    void prismGeometryUsesTriangleOrientation() {
        // Regression guard: PrismGeometry.getTriangleVertices() previously hardcoded a fixed
        // lower-left shape that ignored the triangle's type, so the prism mesh used by ray
        // intersection / collision / nearest-neighbor disagreed with Triangle.getVertices().
        // It must delegate to the triangle's own (t8 main-diagonal) vertices for both types.
        for (int type = 0; type < Triangle.TYPES; type++) {
            var triangle = new Triangle(2, type, 1, 1, Math.min(1, 1));
            var prism = new PrismKey(triangle, new Line(2, 1));
            var triVerts = triangle.getVertices();              // float[3][2]
            var prismVerts = PrismGeometry.getVertices(prism);  // 6 Point3f: bottom (minZ) then top
            for (int i = 0; i < 3; i++) {
                Point3f bottom = prismVerts.get(i);
                assertEquals(triVerts[i][0], bottom.x, EPS, "prism bottom vertex " + i + " x, type " + type);
                assertEquals(triVerts[i][1], bottom.y, EPS, "prism bottom vertex " + i + " y, type " + type);
                // Top triangle (indices 3-5) shares the same (x,y), only z differs.
                Point3f top = prismVerts.get(i + 3);
                assertEquals(triVerts[i][0], top.x, EPS, "prism top vertex " + i + " x, type " + type);
                assertEquals(triVerts[i][1], top.y, EPS, "prism top vertex " + i + " y, type " + type);
            }
        }
    }

    // ── n=min(x,y) invariant under neighbor finding ───────────────────────────────────

    @Test
    @DisplayName("neighbors()/neighbor() preserve the n = min(x,y) invariant for both types")
    void neighborNInvariant() {
        // Guards the RDR-009 P1 change that recomputes n from each neighbor's coordinates
        // (was: copied the source triangle's n). A P2 regression in neighbor n-derivation would
        // otherwise have no trip-wire — neighbor traversal is correctness-critical for the TM-index.
        for (int type = 0; type < Triangle.TYPES; type++) {
            var t = new Triangle(3, type, 3, 2, Math.min(3, 2));
            for (var nb : t.neighbors()) {
                if (nb != null) {
                    assertEquals(Math.min(nb.getX(), nb.getY()), nb.getN(),
                        "neighbors()[*] must satisfy n = min(x,y), type " + type);
                }
            }
            for (int e = 0; e < Triangle.EDGES; e++) {
                var nb = t.neighbor(e);
                if (nb != null) {
                    assertEquals(Math.min(nb.getX(), nb.getY()), nb.getN(),
                        "neighbor(" + e + ") must satisfy n = min(x,y), type " + type);
                }
            }
        }
    }

    // ── point-location boundary + clamp-removal conventions (RDR-009 P1) ───────────────

    @Test
    @DisplayName("a point exactly on a cell's main diagonal (localX==localY) classifies as type 0 and is contained")
    void diagonalBoundaryIsTypeZero() {
        // localX == localY → the (localY > localX) rule yields type 0; getVertices()'s inclusive
        // barycentric test must contain the on-diagonal point. Pins the documented boundary convention.
        int level = 2;                 // scale = 4
        float w = 1.5f / 4.0f;         // cell (1,1), localX = localY = 0.5 exactly
        var tri = Triangle.fromWorldCoordinates(w, w, level);
        assertEquals(1, tri.getX());
        assertEquals(1, tri.getY());
        assertEquals(0, tri.getType(), "on-diagonal point must classify as type 0");
        assertTrue(tri.contains(w, w), "type-0 triangle must contain its on-diagonal boundary point");
    }

    @Test
    @DisplayName("x+y>=scale points are NOT relocated (the silent diagonal clamp was removed)")
    void nearBoundaryPointsAreNotClamped() {
        // Old fromWorldCoordinate relocated x+y>=scale onto the diagonal: (0.9,0.9,2) → quant (3,3)
        // with x+y=6 >= scale=4 was clamped to (1,2). The clamp is gone; the point keeps its true cell.
        var tri = Triangle.fromWorldCoordinates(0.9f, 0.9f, 2);
        assertEquals(3, tri.getX(), "x must not be relocated off its quantized cell");
        assertEquals(3, tri.getY(), "y must not be relocated off its quantized cell");
        assertEquals(Math.min(3, 3), tri.getN());
        assertTrue(tri.contains(0.9f, 0.9f), "the located triangle must still contain its own point");
        // fromWorldCoordinate (delegating) must agree — no residual clamp on that path either.
        var viaSingular = Triangle.fromWorldCoordinate(0.9f, 0.9f, 2);
        assertEquals(tri, viaSingular);
    }

    @Test
    @DisplayName("level-0 roots are per-half (RDR-009 P3): S0 root contains only y<=x")
    void levelZeroRootIsPerHalf() {
        // RDR-009 P3 replaced the level-0 full-square placeholder with per-root containment: the
        // S0 root contains only its lower-right half {y <= x}; the upper-left half is the S1 root.
        var s0Root = new Triangle(0, 0, 0, 0, 0);
        assertTrue(s0Root.contains(0.8f, 0.2f), "S0 root must contain lower-right (y < x) points");
        assertTrue(s0Root.contains(0.5f, 0.5f), "S0 root must contain on-diagonal points");
        assertTrue(!s0Root.contains(0.2f, 0.8f), "S0 root must NOT contain upper-left (y > x) points");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private static void assertVertices(float[][] actual, float[][] expected) {
        assertEquals(expected.length, actual.length, "vertex count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], actual[i][0], EPS, "vertex " + i + " x");
            assertEquals(expected[i][1], actual[i][1], EPS, "vertex " + i + " y");
        }
    }
}
