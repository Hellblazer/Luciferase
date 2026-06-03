/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.collision;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-p6e5g: meshVsMesh used trianglesIntersect, which was a triangle-AABB-vs-triangle-AABB approximation
 * — it returned true whenever the two triangles' bounding boxes overlapped, even when the triangles did not touch,
 * and then fabricated penetration=0.1f. trianglesIntersect is now a real Möller triangle-triangle test, and
 * meshVsMesh derives penetration from the geometry. These tests pin the true intersection contract (especially the
 * AABB-overlap-but-disjoint case the old approximation got wrong) and the geometry-derived penetration.
 *
 * @author hal.hildebrand
 */
class TriangleNarrowPhaseTest {

    // Triangle A lies in the z=0 plane, lower-left of the x+y<=4 region.
    private static final Point3f A0 = new Point3f(0, 0, 0);
    private static final Point3f A1 = new Point3f(4, 0, 0);
    private static final Point3f A2 = new Point3f(0, 4, 0);

    @Test
    void detectsCrossingTriangles() {
        // B pierces z=0 near (1,1,0) — inside A (1+1 <= 4).
        var b0 = new Point3f(1, 1, -1);
        var b1 = new Point3f(1, 1, 1);
        var b2 = new Point3f(3, 1, 0);
        assertTrue(CollisionDetector.trianglesIntersect(A0, A1, A2, b0, b1, b2),
                   "triangles that genuinely cross must intersect");
    }

    @Test
    void rejectsAabbOverlappingButDisjointTriangles() {
        // B pierces z=0 only near (3,3,0)/(4,4,0) — OUTSIDE A (3+3 > 4), but its AABB (x[3,4] y[3,4] z[-1,1])
        // overlaps A's AABB (x[0,4] y[0,4] z[0,0]). The old triangle-AABB approximation returned true here.
        var b0 = new Point3f(3, 3, -1);
        var b1 = new Point3f(3, 3, 1);
        var b2 = new Point3f(4, 4, 0);
        assertFalse(CollisionDetector.trianglesIntersect(A0, A1, A2, b0, b1, b2),
                    "AABB-overlapping but geometrically disjoint triangles must NOT intersect (Luciferase-p6e5g)");
    }

    @Test
    void rejectsParallelSeparatedTriangles() {
        // B is a copy of A lifted to z=5 — parallel planes, no contact.
        var b0 = new Point3f(0, 0, 5);
        var b1 = new Point3f(4, 0, 5);
        var b2 = new Point3f(0, 4, 5);
        assertFalse(CollisionDetector.trianglesIntersect(A0, A1, A2, b0, b1, b2),
                    "triangles in parallel, separated planes must not intersect");
    }

    @Test
    void coplanarOverlapDetectedAndDisjointRejected() {
        // Coplanar overlapping (B's vertex inside A).
        var ovl0 = new Point3f(1, 1, 0);
        var ovl1 = new Point3f(3, 1, 0);
        var ovl2 = new Point3f(1, 3, 0);
        assertTrue(CollisionDetector.trianglesIntersect(A0, A1, A2, ovl0, ovl1, ovl2),
                   "coplanar overlapping triangles must intersect");

        // Coplanar but disjoint (far corner of the same plane), AABBs may still overlap.
        var d0 = new Point3f(10, 10, 0);
        var d1 = new Point3f(12, 10, 0);
        var d2 = new Point3f(10, 12, 0);
        assertFalse(CollisionDetector.trianglesIntersect(A0, A1, A2, d0, d1, d2),
                    "coplanar but separated triangles must not intersect");
    }

    @Test
    void meshVsMeshProducesGeometryDerivedPenetration() {
        // Two single-triangle meshes that cross. Penetration must be a real, positive, geometry-derived depth — and
        // it must SCALE with how deeply they overlap (a constant 0.1f would not), proving it is not fabricated.
        float shallow = penetrationForBDepth(0.25f);
        float deep = penetrationForBDepth(1.0f);
        assertTrue(shallow > 0f, "intersecting meshes must report positive penetration");
        assertTrue(deep > shallow + 1e-3f,
                   "penetration must grow with overlap depth (geometry-derived, not a constant 0.1f): shallow="
                   + shallow + " deep=" + deep);
    }

    /**
     * Build mesh A = triangle A in z=0 and mesh B = a triangle straddling z=0 by +/- {@code depth} (so it pokes
     * {@code depth} below A's plane), centred inside A; return the meshVsMesh penetration.
     */
    private static float penetrationForBDepth(float depth) {
        var meshA = new TriangleMeshData();
        meshA.addVertex(A0);
        meshA.addVertex(A1);
        meshA.addVertex(A2);
        meshA.addTriangle(0, 1, 2);
        var a = new MeshShape(new Point3f(0, 0, 0), meshA);

        var meshB = new TriangleMeshData();
        meshB.addVertex(new Point3f(1, 1, -depth));
        meshB.addVertex(new Point3f(2, 1, depth));
        meshB.addVertex(new Point3f(1, 2, depth));
        meshB.addTriangle(0, 1, 2);
        var b = new MeshShape(new Point3f(0, 0, 0), meshB);

        var result = a.collidesWith(b);
        assertTrue(result.collides, "the crossing meshes must collide");
        return result.penetrationDepth;
    }
}
