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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-bsibi: {@code triangleIntersectsOBB} built a BoxShape from the OBB's AABB, stripping the orientation,
 * so a triangle in the AABB corner (outside the rotated box) was a false positive. The fix transforms the triangle
 * into the box's local frame and runs the real 13-axis SAT.
 *
 * @author hal.hildebrand
 */
class CollisionDetectorTriangleOBBTest {

    private static OrientedBoxShape unitBoxRotatedZ(float radians) {
        var rot = new Matrix3f();
        rot.rotZ(radians);
        return new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1), rot);
    }

    @Test
    void triangleInAabbCornerOutsideRotatedBoxDoesNotCollide() {
        // 45-degree rotated unit box: its AABB reaches ~1.414 in x and y, but along the (1,1) diagonal the box face
        // is only 1 away. A triangle near (1.3,1.3) sits inside the AABB but outside the rotated box (local-x ~1.8).
        var obb = unitBoxRotatedZ((float) Math.toRadians(45));
        var v0 = new Point3f(1.3f, 1.2f, 0);
        var v1 = new Point3f(1.4f, 1.3f, 0);
        var v2 = new Point3f(1.3f, 1.4f, 0);

        assertFalse(CollisionDetector.triangleIntersectsOBB(v0, v1, v2, obb),
                    "triangle in the AABB corner but outside the rotated box must NOT collide (Luciferase-bsibi)");
    }

    @Test
    void triangleInsideRotatedBoxCollides() {
        var obb = unitBoxRotatedZ((float) Math.toRadians(45));
        // A triangle straddling the origin is well inside the box for any orientation.
        var v0 = new Point3f(-0.2f, 0, 0);
        var v1 = new Point3f(0.2f, 0.1f, 0);
        var v2 = new Point3f(0, 0.2f, 0.1f);

        assertTrue(CollisionDetector.triangleIntersectsOBB(v0, v1, v2, obb),
                   "triangle through the box centre must collide");
    }

    @Test
    void penetrationDepthIsTheRealMtvNotFabricated() {
        // The core bsibi deliverable: a real penetration depth (was a fabricated 0.1f). Flat triangle at z=0.5,
        // laterally inside a unit box at the origin. The minimum-translation axis is the triangle normal (z); the
        // box reaches z=1, so separating depth = 1 - 0.5 = 0.5. (A fabricated 0.1f would fail this.)
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1));
        var v0 = new Point3f(-0.5f, -0.5f, 0.5f);
        var v1 = new Point3f(0.5f, -0.5f, 0.5f);
        var v2 = new Point3f(0.0f, 0.5f, 0.5f);

        float penetration = CollisionDetector.triangleBoxPenetrationForTest(v0, v1, v2, box);
        assertEquals(0.5f, penetration, 1e-4f, "real MTV penetration depth, not 0.1f (Luciferase-bsibi)");

        var axis = CollisionDetector.triangleBoxAxisForTest(v0, v1, v2, box);
        assertTrue(axis != null && Math.abs(axis.z) > Math.abs(axis.x) && Math.abs(axis.z) > Math.abs(axis.y),
                   "the MTV axis for a flat z=0.5 triangle must be z-dominant, got " + axis);
    }

    @Test
    void axisAlignedTriangleBoxSatMatchesGeometry() {
        // Sanity on the AABB path: a triangle overlapping an axis-aligned unit box collides; one clearly outside not.
        var box = new BoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1));
        assertTrue(CollisionDetector.triangleIntersectsBox(new Point3f(0, 0, 0), new Point3f(0.5f, 0, 0),
                                                           new Point3f(0, 0.5f, 0), box));
        assertFalse(CollisionDetector.triangleIntersectsBox(new Point3f(3, 3, 3), new Point3f(3.5f, 3, 3),
                                                            new Point3f(3, 3.5f, 3), box));
    }
}
