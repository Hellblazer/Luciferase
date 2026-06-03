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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-nm9dj: {@code satBoxes} returned a single contact point (midpoint of the support vertices), giving wrong
 * angular-impulse arms for face-face / face-edge contacts. The fix clips the incident face against the reference face
 * (Sutherland-Hodgman) to build a contact manifold.
 *
 * @author hal.hildebrand
 */
class CollisionDetectorManifoldTest {

    private static OrientedBoxShape obb(Point3f center, float hx, float hy, float hz) {
        var id = new Matrix3f();
        id.setIdentity();
        return new OrientedBoxShape(center, new Vector3f(hx, hy, hz), id);
    }

    @Test
    void faceFaceContactProducesFourPointManifold() {
        // Two unit boxes overlapping along x: A=[-1,1]^3, B centred at (1.5,0,0) => overlap x in [0.5,1], depth 0.5.
        // The face-face contact is the full y-z square; the manifold must be its 4 corners (was a single point).
        var a = obb(new Point3f(0, 0, 0), 1, 1, 1);
        var b = obb(new Point3f(1.5f, 0, 0), 1, 1, 1);

        var result = a.collidesWith(b);

        assertTrue(result.collides, "boxes overlap");
        assertEquals(4, result.contactManifold.size(),
                     "face-face contact must yield a 4-point manifold, not a single point (Luciferase-nm9dj)");
        assertEquals(0.5f, result.penetrationDepth, 1e-3f, "penetration along x");
        assertTrue(Math.abs(result.contactNormal.x) > 0.9f, "contact normal is ~x, got " + result.contactNormal);

        // All manifold points are projected onto the reference face plane (A's +x face at x=1.0) at the y/z corners.
        for (var p : result.contactManifold) {
            assertEquals(1.0f, p.x, 1e-3f, "manifold point projected onto the reference face plane");
            assertEquals(1.0f, Math.abs(p.y), 1e-3f, "manifold corner in y");
            assertEquals(1.0f, Math.abs(p.z), 1e-3f, "manifold corner in z");
        }
    }

    @Test
    void rotatedBoxFaceContactClipsToManifold() {
        // Exercise the actual S-H clipping (and the refIsA=false branch): a 45-degree Z-rotated box A overlapping an
        // axis-aligned box B. The contact still yields a multi-point manifold (not a single point), with all points
        // on the shared contact plane. Pins that clipping produces >= 2 points without over-claiming the exact count.
        var rot = new Matrix3f();
        rot.rotZ((float) Math.toRadians(45));
        var a = new OrientedBoxShape(new Point3f(0, 0, 0), new Vector3f(1, 1, 1), rot);
        var b = obb(new Point3f(1.6f, 0, 0), 1, 1, 1);

        var result = a.collidesWith(b);

        assertTrue(result.collides, "rotated box overlaps");
        assertTrue(result.contactManifold.size() >= 2,
                   "face contact must yield a manifold (>=2 points), not a single point (Luciferase-nm9dj), got "
                   + result.contactManifold.size());
    }

    @Test
    void manifoldNeverNullAndContainsRepresentativePoint() {
        var a = obb(new Point3f(0, 0, 0), 1, 1, 1);
        var b = obb(new Point3f(1.5f, 0, 0), 1, 1, 1);

        var result = a.collidesWith(b);

        // contactPoint is the manifold centroid; manifold is non-empty for a collision.
        assertTrue(!result.contactManifold.isEmpty(), "manifold non-empty on collision");
        assertEquals(1.0f, result.contactPoint.x, 1e-3f, "representative contactPoint is the manifold centroid");
        assertEquals(0.0f, result.contactPoint.y, 1e-3f);
    }
}
