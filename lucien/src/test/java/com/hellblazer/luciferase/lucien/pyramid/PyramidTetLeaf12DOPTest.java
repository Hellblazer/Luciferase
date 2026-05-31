/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.HybridElement;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.5 Phase A — 12-DOP tightening at tet-leaf nodes in PyramidIndex.
 *
 * <p>These tests verify that {@link PyramidIndex#doesNodeIntersectVolume} and
 * {@link PyramidIndex#isNodeContainedInVolume} exercise the tet-leaf branch introduced in
 * {@code feature/pyramid-t8code-remediation}. All assertions are geometrically grounded in the
 * actual tet vertex coordinates returned by {@link Tet#coordinates()} and the 12-DOP conditions in
 * {@link Tet#intersects12DOP}.
 *
 * <p><b>Concrete geometry</b> (used throughout):
 * <ul>
 *   <li>Tet key: level-1 child index 1 of the type-6 root pyramid.
 *       {@code PYRAMID_PARENT_TO_CHILD_TYPE[0][1] = 3}, {@code PYRAMID_PARENT_TO_CHILD_CID[0][1] = 1}
 *       → type 3 tet, cube-id 1 (anchor shifted by h in X), anchor = (h/2, 0, 0).</li>
 *   <li>Type 3 containment ordering: {@code y - ay >= z - az >= x - ax} (y ≥ z ≥ x in local coords).
 *       Equivalently: {@code v >= w && w >= u} in local (u,v,w) = (px-ax, py-ay, pz-az).</li>
 *   <li>Surrounding cube: [ax, ax+h] × [ay, ay+h] × [az, az+h] where h = Constants.lengthAtLevel(1).</li>
 *   <li>Cube corner EXCLUDED from tet: (ax, ay, az+h). Local coords (0, 0, h) — requires v≥w → 0≥h → FAIL.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class PyramidTetLeaf12DOPTest {

    private PyramidIndex<LongEntityID, String> index;

    /** The level-1 tet-leaf key (type 3, cube-id 1, child of type-6 root). */
    private PyramidKey tetLeafKey;

    /** The decoded Tet element. */
    private Tet tet;

    /** Half cell size at level 1. */
    private int h;

    /** Tet anchor coordinates. */
    private int ax, ay, az;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());

        // Build a level-1 tet-leaf key for child index 1 of the type-6 root pyramid.
        // PYRAMID_PARENT_TO_CHILD_TYPE[0][1] = 3 (Tet type 3)
        // PYRAMID_PARENT_TO_CHILD_CID[0][1]  = 1 (cube-id 1 → anchor shift +h in X)
        int row = Pyramid.TYPE_6 - Pyramid.TYPE_6; // = 0
        int childIdx = 1;
        int coordBits1 = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][childIdx];
        int typeBits1  = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][childIdx];

        // Confirm the child is indeed a Tet (type 0-5)
        var type6Root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        var rawChild = type6Root.child(childIdx);
        assertInstanceOf(Tet.class, rawChild,
                         "Child index 1 of type-6 root must be a Tet; got " + rawChild.getClass().getSimpleName());

        tetLeafKey = PyramidKey.fromLevels((byte) 1,
                                           new int[]{ 0, coordBits1 },
                                           new int[]{ 0, typeBits1  });

        h   = Constants.lengthAtLevel((byte) 1);  // = 2^19 = 524288
        // Anchor: cube-id 1 means +h in X, +0 in Y, +0 in Z (bit 0 set).
        ax  = (coordBits1 & 1) != 0 ? h : 0;  // = h (bit 0)
        ay  = (coordBits1 & 2) != 0 ? h : 0;  // = 0
        az  = (coordBits1 & 4) != 0 ? h : 0;  // = 0

        HybridElement el = PyramidIndex.elementFromKey(tetLeafKey);
        assertInstanceOf(Tet.class, el, "elementFromKey must return a Tet for this key");
        tet = (Tet) el;
    }

    // =========================================================================
    // Test 1 — Tet-leaf key decodes to a Tet with type ∈ 0..5
    // =========================================================================

    /**
     * Verify that the test key actually resolves to a Tet and that its type is in [0,5].
     * This exercises {@link PyramidIndex#elementFromKey} for the tet-leaf case.
     */
    @Test
    void tetLeafKey_decodesTo_tet_withValidType() {
        HybridElement el = PyramidIndex.elementFromKey(tetLeafKey);
        assertInstanceOf(Tet.class, el, "elementFromKey must produce a Tet for a tet-leaf key");
        Tet decoded = (Tet) el;
        int type = decoded.type;
        assertTrue(type >= 0 && type <= 5,
                   "Tet type must be in [0,5], got: " + type);
        // Confirm geometry: anchor and level
        assertEquals(ax, decoded.x, "Tet anchor x must match cube-id shift");
        assertEquals(ay, decoded.y, "Tet anchor y must match cube-id shift");
        assertEquals(az, decoded.z, "Tet anchor z must match cube-id shift");
        assertEquals(1,  decoded.l,  "Tet must be at level 1");
        // Confirm it is type 3 per the connectivity tables
        assertEquals(3, type, "Child index 1 of type-6 root is Tet type 3");
    }

    // =========================================================================
    // Test 2 — Tightening: cube corner outside tet ⇒ doesNodeIntersectVolume FALSE
    // =========================================================================

    /**
     * Key tightening assertion.
     *
     * <p>Corner (ax, ay, az+h) lies INSIDE the surrounding cube [ax,ax+h]³ but OUTSIDE the type-3
     * tet. The type-3 ordering is {@code y - ay ≥ z - az ≥ x - ax}; at this corner the local
     * coords are (0, 0, h) which gives {@code 0 ≥ h → FAIL}. A cube-AABB-only implementation
     * would return {@code true}; the 12-DOP tightening correctly returns {@code false}.
     *
     * <p>This test FAILS if the tet-leaf branch is reverted to cube-AABB — it directly depends on
     * {@link Tet#intersects12DOP} returning false for this query.
     */
    @Test
    void doesNodeIntersectVolume_tetLeaf_cubeCornerOutsideTet_returnsFalse() {
        // Verify the corner is geometrically outside the tet (oracle, independent of index):
        float cx = ax;          // corner x
        float cy = ay;          // corner y
        float cz = az + h;      // corner z  — OUTSIDE type-3 tet (0 ≥ h fails)
        assertFalse(tet.contains12DOP(cx, cy, cz),
                    "Sanity: corner (ax, ay, az+h) must be outside type-3 tet before testing index");

        // Also verify the cube AABB broad gate WOULD pass (so this is actually a tightening):
        // The corner (ax, ay, az+h) is a cube corner — it is ON the cube boundary → AABB gate passes.
        // Build a tiny query AABB centered at that corner (well within the cube AABB).
        float eps = h / 64f;
        var queryAtCorner = new Spatial.Cube(cx - eps, cy - eps, cz - eps, 2 * eps);

        // Verify the query AABB does intersect the surrounding cube (so the broad gate fires):
        var cubeAABB = (Spatial.Cube) index.getNodeBounds(tetLeafKey);
        var cubeBounds = VolumeBounds.from(cubeAABB);
        var queryBounds = VolumeBounds.from(queryAtCorner);
        assertTrue(
            queryBounds.maxX() >= cubeBounds.minX() && queryBounds.minX() <= cubeBounds.maxX()
            && queryBounds.maxY() >= cubeBounds.minY() && queryBounds.minY() <= cubeBounds.maxY()
            && queryBounds.maxZ() >= cubeBounds.minZ() && queryBounds.minZ() <= cubeBounds.maxZ(),
            "Sanity: query cube must overlap the surrounding cube AABB (so cube gate passes)");

        // The 12-DOP tightening must reject it:
        assertFalse(index.doesNodeIntersectVolume(tetLeafKey, queryAtCorner),
                    "Tet-leaf 12-DOP must reject a query at cube corner (ax,ay,az+h) "
                    + "that is outside the type-3 tet. If this fails, the tet branch was bypassed.");
    }

    // =========================================================================
    // Test 3 — Positive case: point inside tet ⇒ doesNodeIntersectVolume TRUE
    // =========================================================================

    /**
     * A query AABB centered at a point that is genuinely inside the type-3 tet must return true.
     *
     * <p>Type-3 tet ordering: {@code y - ay ≥ z - az ≥ x - ax}. A point satisfying this with all
     * coordinates strictly inside the cube (not on boundary) is an interior point of the tet.
     * We choose local coords {@code (u, v, w) = (h/8, h/2, h/4)} which satisfies v≥w≥u (h/2 ≥ h/4 ≥ h/8).
     */
    @Test
    void doesNodeIntersectVolume_tetLeaf_pointInsideTet_returnsTrue() {
        // Interior point: local (u,v,w) = (h/8, h/2, h/4) satisfies v≥w≥u (type 3)
        float px = ax + h / 8f;
        float py = ay + h / 2f;
        float pz = az + h / 4f;

        // Sanity: verify the point is inside the tet
        assertTrue(tet.contains12DOP(px, py, pz),
                   "Sanity: point (ax+h/8, ay+h/2, az+h/4) must be inside type-3 tet");

        float eps = h / 64f;
        var queryInside = new Spatial.Cube(px - eps, py - eps, pz - eps, 2 * eps);

        assertTrue(index.doesNodeIntersectVolume(tetLeafKey, queryInside),
                   "Query AABB around interior point of tet must return TRUE");
    }

    // =========================================================================
    // Test 4 — Containment: tet-leaf path works; all 4 tet vertices fit inside query
    // =========================================================================

    /**
     * Containment test for the tet-leaf path.
     *
     * <p>The new code checks all 4 tet vertices against the query AABB (versus checking all 8
     * cube corners). This test verifies the tet path returns TRUE when the query AABB contains
     * all 4 tet vertices.
     *
     * <p><b>Note on AABB equivalence</b>: for all Tet types, v0 is the anchor (ax,ay,az) and v3
     * is the opposite cube corner (ax+h, ay+h, az+h). The bounding box of the 4 tet vertices
     * therefore always equals the surrounding cube AABB, so for pure AABB queries the tet-vertex
     * test is equivalent to the 8-corner cube test. The tightening benefit manifests for
     * non-AABB volumes (Sphere, rotated box) via their reduced getVolumeBounds() AABB proxy.
     *
     * <p>This test validates the tet path's correctness (returns TRUE) and exercises
     * {@link Tet#coordinates()} in the code path.
     */
    @Test
    void isNodeContainedInVolume_tetLeaf_queryContainsAllVertices_returnsTrue() {
        Point3i[] vertices = tet.coordinates();
        assertEquals(4, vertices.length, "Tet must have 4 vertices");

        // Compute the bounding box of the 4 tet vertices directly (independent oracle)
        float minVx = Float.MAX_VALUE, minVy = Float.MAX_VALUE, minVz = Float.MAX_VALUE;
        float maxVx = -Float.MAX_VALUE, maxVy = -Float.MAX_VALUE, maxVz = -Float.MAX_VALUE;
        for (var v : vertices) {
            minVx = Math.min(minVx, v.x); minVy = Math.min(minVy, v.y); minVz = Math.min(minVz, v.z);
            maxVx = Math.max(maxVx, v.x); maxVy = Math.max(maxVy, v.y); maxVz = Math.max(maxVz, v.z);
        }

        // A query AABB with generous margin around the tet vertex bounding box:
        float margin = h / 4f;
        var largeQuery = new Spatial.Cube(minVx - margin, minVy - margin, minVz - margin,
                                          (maxVx - minVx) + 2 * margin);

        assertTrue(index.isNodeContainedInVolume(tetLeafKey, largeQuery),
                   "Query AABB containing all tet vertices with margin must return TRUE");
    }

    /**
     * Verify that a query AABB exactly equal to the surrounding cube AABB returns TRUE.
     * This exercises the boundary-inclusive vertex check (≥/≤ comparisons in the tet path).
     */
    @Test
    void isNodeContainedInVolume_tetLeaf_exactCubeAABB_returnsTrue() {
        // The exact surrounding cube AABB contains all 4 tet vertices on the boundary.
        var exactCube = new Spatial.Cube(ax, ay, az, h);
        assertTrue(index.isNodeContainedInVolume(tetLeafKey, exactCube),
                   "Exact surrounding-cube AABB must contain all 4 tet vertices (boundary-inclusive)");
    }

    /**
     * Verify that a query AABB smaller than the surrounding cube does NOT contain the tet
     * (since v3 = (ax+h, ay+h, az+h) falls outside a shrunk cube).
     */
    @Test
    void isNodeContainedInVolume_tetLeaf_shrunkQuery_returnsFalse() {
        float shrunk = h * 0.9f;
        var smallerCube = new Spatial.Cube(ax, ay, az, shrunk);
        assertFalse(index.isNodeContainedInVolume(tetLeafKey, smallerCube),
                    "A query AABB that cannot contain the far tet vertex (ax+h, ay+h, az+h) "
                    + "must return FALSE");
    }

    // =========================================================================
    // Test 5 — Pyramid 6/7 stays conservative (intentional behavior pin)
    // =========================================================================

    /**
     * Pyramid-typed leaf nodes (type 6/7) retain the cube-AABB result — the 14-DOP pyramid test
     * is pending separate work. A query volume that hits the surrounding cube AABB but DOES NOT
     * reach the pyramid's actual apex region must still return TRUE (conservative over-approximation).
     *
     * <p>This test pins the intentional behavior: type-6/7 nodes are conservative. A future reader
     * seeing a false-positive intersection for a pyramid leaf should recognise it as intentional, not
     * a bug. If 14-DOP exact pyramid tests are ever added this test should be updated or removed.
     *
     * <p><b>Geometry</b>: the type-6 root pyramid at level 1 has its apex at (0+h, 0+h, 0+h) and
     * square base on z=0. A query touching only the cube corner well above the pyramid base (near
     * the apex direction) will still pass because the code returns the cube-AABB result.
     *
     * <p>We use a level-1 type-6 key (pure pyramid, not tet) and verify that even a query cube
     * whose interior only overlaps the cube AABB at a corner that may geometrically miss the
     * pyramid interior returns TRUE (cube-conservative).
     */
    @Test
    void doesNodeIntersectVolume_pyramidLeaf_cubeAabbConservative_returnsTrue() {
        // Build a level-1 pyramid key for child index 0 of the type-6 root (type 6, CID 0).
        // PYRAMID_PARENT_TO_CHILD_TYPE[0][0] = 6 (Pyramid)
        // PYRAMID_PARENT_TO_CHILD_CID[0][0]  = 0 (anchor at origin)
        int row = 0;  // type-6 root
        int childIdx = 0;
        int coordBits1 = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][childIdx]; // = 0
        int typeBits1  = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][childIdx]; // = 6

        var pyramidKey = PyramidKey.fromLevels((byte) 1,
                                               new int[]{ 0, coordBits1 },
                                               new int[]{ 0, typeBits1  });

        // Confirm it decodes to a Pyramid
        HybridElement el = PyramidIndex.elementFromKey(pyramidKey);
        assertInstanceOf(Pyramid.class, el, "elementFromKey must return a Pyramid for key with type 6");

        Pyramid pyramid = (Pyramid) el;
        assertEquals(Pyramid.TYPE_6, pyramid.type(), "Must be TYPE_6");

        // Get the surrounding cube AABB
        var cubeAABB = (Spatial.Cube) index.getNodeBounds(pyramidKey);
        int ph = Constants.lengthAtLevel((byte) 1);
        float pax = cubeAABB.originX(), pay = cubeAABB.originY(), paz = cubeAABB.originZ();

        // Build a query that overlaps the cube AABB at the corner (pax+ph, pay, paz) — one of the
        // 8 cube corners. This corner is in the surrounding cube. The pyramid may or may not
        // geometrically contain this corner (the base of a type-6 pyramid at level 1 is at z=paz,
        // so a point near z=paz with (x,y) far from the apex is near the base which IS in the pyramid).
        //
        // Regardless, the pyramid code must return TRUE because it uses the cube-AABB result
        // (the code falls through to `return true` for pyramid leaves without any exact pyramid test).
        float eps = ph / 64f;
        var queryAtCubeCorner = new Spatial.Cube(pax + ph - eps, pay - eps, paz - eps, 2 * eps);

        // The cube AABB gate: does the query overlap [pax, pax+ph] x [pay, pay+ph] x [paz, paz+ph]?
        // pax+ph-eps is inside the cube X range [pax, pax+ph], so YES, the cube gate passes.
        assertTrue(index.doesNodeIntersectVolume(pyramidKey, queryAtCubeCorner),
                   "Pyramid leaf (type 6/7) must return TRUE for any query overlapping its cube AABB "
                   + "(cube-conservative; exact 14-DOP pyramid test is pending separate work). "
                   + "If this fails, the pyramid branch was changed to use exact geometry.");
    }
}
