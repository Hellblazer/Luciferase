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
    }

    @Test
    void obbRestingContactHasNearZeroPenetration() {
        // Faces just touching at x=1: depth ~0, so the resolver injects no correction energy (old code fabricated 0.1).
        var a = new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1), identity());
        var b = new OrientedBoxShape(new Point3f(2.0f, 0, 0), new Vector3f(1, 1, 1), identity());

        var result = a.collidesWith(b);
        // Touching is the boundary; if reported as a collision the depth must be ~0, never a fabricated 0.1.
        if (result.collides) {
            assertEquals(0.0f, result.penetrationDepth, 0.05f, "resting contact penetration must be ~0, not fabricated");
        }
    }

    @Test
    void obbCollisionRespectsOrientation() {
        // Box A axis-aligned at origin. Box B centred at (2.3,0,0) with unit half-extents:
        //  - identity orientation: B spans x in [1.3, 3.3] -> a clean gap from A (max x = 1), no collision.
        //  - rotated 45 deg about Z: B's diamond profile reaches x = 2.3 - sqrt(2) ~ 0.886 < 1 -> it overlaps A.
        // The verdict flipping with orientation proves orientation is respected (old code tested only AABBs).
        var a = new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1), identity());

        var bAligned = new OrientedBoxShape(new Point3f(2.3f, 0, 0), new Vector3f(1, 1, 1), identity());
        assertFalse(a.collidesWith(bAligned).collides, "axis-aligned B is separated from A");

        var bRotated = new OrientedBoxShape(new Point3f(2.3f, 0, 0), new Vector3f(1, 1, 1), rotZ((float) (Math.PI / 4)));
        assertTrue(a.collidesWith(bRotated).collides, "rotating B into A must be detected (orientation respected)");
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
