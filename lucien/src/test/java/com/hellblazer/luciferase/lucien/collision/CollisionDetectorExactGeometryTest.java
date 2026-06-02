/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision;

import org.junit.jupiter.api.Test;

import javax.vecmath.Matrix3f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Luciferase-i1mlg: narrow-phase pairs that fabricated penetrationDepth=0.1f and stripped OBB
 * orientation (testing only AABBs). The fixes use a 15-axis SAT for box/OBB pairs (exact depth + normal respecting
 * orientation) and GJK+EPA for convex-hull pairs; heightmap-vs-heightmap reports no collision instead of fabricating.
 *
 * @author hal.hildebrand
 */
class CollisionDetectorExactGeometryTest {

    private static Matrix3f identity() {
        var m = new Matrix3f();
        m.setIdentity();
        return m;
    }

    private static Matrix3f rotZ(float radians) {
        var m = new Matrix3f();
        m.rotZ(radians);
        return m;
    }

    @Test
    void obbPenetrationDepthIsGeometricNotFabricated() {
        // Two unit OBBs (identity orientation) overlapping 0.5 along x. SAT must report depth ~0.5, not the old 0.1f.
        var a = new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1), identity());
        var b = new OrientedBoxShape(new Point3f(1.5f, 0, 0), new Vector3f(1, 1, 1), identity());

        var result = a.collidesWith(b);
        assertTrue(result.collides, "overlapping OBBs must collide");
        assertEquals(0.5f, result.penetrationDepth, 0.02f, "penetration must be geometry-derived (Luciferase-i1mlg)");
        assertEquals(1.0f, Math.abs(result.contactNormal.x), 0.02f, "separating axis is x for an x-overlap");
        assertTrue(Math.abs(result.contactNormal.y) < 0.05f && Math.abs(result.contactNormal.z) < 0.05f);
        // Normal must point from A toward B (+x here); swapping the operands flips it.
        assertTrue(result.contactNormal.x > 0, "contact normal points from A to B (+x)");
        var swapped = b.collidesWith(a);
        assertTrue(swapped.contactNormal.x < 0, "swapping operands flips the contact normal (B to A is -x)");
    }

    @Test
    void obbRestingContactHasNearZeroPenetration() {
        // Faces just touching at x=1: depth ~0, so the resolver injects no correction energy (old code fabricated 0.1).
        var a = new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1), identity());
        var b = new OrientedBoxShape(new Point3f(2.0f, 0, 0), new Vector3f(1, 1, 1), identity());

        var result = a.collidesWith(b);
        // Touching is the boundary; the depth must be ~0, never a fabricated 0.1 (the resting-contact / no-energy-
        // injection acceptance item). SAT reports zero-overlap contact as a collision with depth 0.
        assertEquals(0.0f, result.penetrationDepth, 0.05f, "resting contact penetration must be ~0, not fabricated 0.1");
    }

    @Test
    void obbGapDespiteAabbOverlapRespectsOrientation() {
        // The canonical 45-degree gap case: a box and a 45-deg-rotated OBB whose WORLD-AXIS-ALIGNED bounding boxes
        // overlap, but whose oriented hulls are separated along the rotated box's face normal. The pre-fix code
        // (AABB gate then fabricate) returned collides=true here; the 15-axis SAT correctly returns no collision.
        //
        // Box A: axis-aligned unit box at origin -> occupies [-1,1] in x and y.
        // Box B: unit box rotated 45 deg about Z, centred at (2.2, 2.2, 0). Its AABB half-width is sqrt(2)~1.414, so
        //        AABB(B) spans [0.786, 3.614] in x and y -> overlaps AABB(A) in the corner region [0.786,1]^2.
        //        Projected onto B's own (1,1,0)/sqrt2 face axis: A spans [-1.414,1.414], B spans [2.111,4.111]
        //        -> a real gap of ~0.7. Oriented boxes are separated.
        var a = new BoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1));
        var b = new OrientedBoxShape(new Point3f(2.2f, 2.2f, 0), new Vector3f(1, 1, 1), rotZ((float) (Math.PI / 4)));

        // Sanity: the world AABBs genuinely overlap, so the AABB prefilter does NOT short-circuit — the SAT runs.
        assertTrue(CollisionShape.boundsIntersect(a.getAABB(), b.getAABB()),
                   "fixture must have overlapping AABBs so the SAT (not the prefilter) decides");

        assertFalse(a.collidesWith(b).collides,
                    "oriented boxes are separated despite AABB overlap — SAT must find the gap (Luciferase-i1mlg)");
    }

    @Test
    void convexHullPenetrationViaGjkEpa() {
        // Two unit cubes as convex hulls overlapping 0.5 along x.
        var cube = List.of(
            new Point3f(-1, -1, -1), new Point3f(1, -1, -1), new Point3f(1, 1, -1), new Point3f(-1, 1, -1),
            new Point3f(-1, -1, 1), new Point3f(1, -1, 1), new Point3f(1, 1, 1), new Point3f(-1, 1, 1));
        var hullA = new ConvexHullShape(new Point3f(0, 0, 0), cube);
        var hullB = new ConvexHullShape(new Point3f(1.5f, 0, 0), cube);

        var result = hullA.collidesWith(hullB);
        assertTrue(result.collides, "overlapping convex hulls must collide (GJK)");
        assertTrue(result.penetrationDepth > 0.2f && result.penetrationDepth < 0.8f,
                   "EPA penetration must be geometry-derived (~0.5), not fabricated 0.1 (Luciferase-i1mlg)");
        assertTrue(Math.abs(result.contactNormal.x) > 0.8f, "penetration normal lies along the x overlap axis");
    }

    @Test
    void separatedConvexHullsDoNotCollide() {
        var cube = List.of(
            new Point3f(-1, -1, -1), new Point3f(1, -1, -1), new Point3f(1, 1, -1), new Point3f(-1, 1, -1),
            new Point3f(-1, -1, 1), new Point3f(1, -1, 1), new Point3f(1, 1, 1), new Point3f(-1, 1, 1));
        var hullA = new ConvexHullShape(new Point3f(0, 0, 0), cube);
        var hullB = new ConvexHullShape(new Point3f(5, 0, 0), cube);

        assertFalse(hullA.collidesWith(hullB).collides, "well-separated convex hulls must not collide");
    }
}
