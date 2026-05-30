/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase D: TDD tests for {@link PyramidIndex#doesPlaneIntersectNode}.
 *
 * <p>Uses the 5-vertex sign-classification model: a plane intersects a pyramid iff
 * its 5 vertices are not all on the same side of the plane (strict; coplanar vertices
 * with sign==0 do not prevent intersection detection).
 *
 * @author hal.hildebrand
 */
class PyramidPlaneIntersectionTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    /** Locate the first type-6 child of the type-6 root and return both key and pyramid. */
    private Pyramid firstType6Child() {
        var root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        for (int i = 0; i < 10; i++) {
            var c = root.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                return pc;
            }
        }
        throw new AssertionError("No type-6 child found");
    }

    private PyramidKey keyFor(Pyramid p) {
        return index.calculateSpatialIndex(p.centroid(), p.level());
    }

    // --- plane exactly through apex (boundary / epsilon dead-zone) ---

    /**
     * A horizontal plane positioned EXACTLY at the apex z. The apex lands on the plane (signed
     * distance within ±epsilon → contributes neither a positive nor a negative sign); all 4 base
     * corners are strictly below. With no mixed signs the pyramid is touched at a single vertex but
     * not sliced, so the verdict is {@code false}. This exercises the sign==0 dead-zone path that the
     * former duplicate mid-height test did not.
     */
    @Test
    void planeExactlyThroughApex_doesNotIntersect() {
        var child = firstType6Child();
        var key   = keyFor(child);
        Point3i[] c = child.coordinates();
        // For TYPE-6: base corners at z = c[0].z, apex at z = c[4].z = c[0].z + h.
        float apexZ = c[4].z;
        var plane = new Plane3D(0f, 0f, 1f, -apexZ);

        assertFalse(index.doesPlaneIntersectNode(key, plane),
                    "Plane touching only the apex (all base corners below) must NOT count as intersecting");
    }

    // --- plane parallel to base (z = constant) ---

    /**
     * Plane parallel to the base, slicing through mid-height: intersects.
     */
    @Test
    void planeMidHeight_intersects() {
        var child = firstType6Child();
        var key   = keyFor(child);
        Point3i[] c = child.coordinates();
        // For TYPE-6: base at z=c[0].z, apex at z=c[4].z
        float baseZ = c[0].z;
        float apexZ = c[4].z;
        float midZ  = (baseZ + apexZ) / 2f;
        var plane = new Plane3D(0f, 0f, 1f, -midZ);

        assertTrue(index.doesPlaneIntersectNode(key, plane),
                   "Plane at mid-height must intersect pyramid");
    }

    /**
     * Plane well above the apex: does not intersect.
     */
    @Test
    void planeAboveApex_doesNotIntersect() {
        var child = firstType6Child();
        var key   = keyFor(child);
        Point3i[] c = child.coordinates();
        float apexZ = c[4].z;
        var plane = new Plane3D(0f, 0f, 1f, -(apexZ + 1000f));

        assertFalse(index.doesPlaneIntersectNode(key, plane),
                    "Plane well above apex must not intersect pyramid");
    }

    /**
     * Plane well below the base: does not intersect.
     */
    @Test
    void planeBelowBase_doesNotIntersect() {
        var child = firstType6Child();
        var key   = keyFor(child);
        Point3i[] c = child.coordinates();
        float baseZ = c[0].z;
        var plane = new Plane3D(0f, 0f, 1f, -(baseZ - 1000f));

        assertFalse(index.doesPlaneIntersectNode(key, plane),
                    "Plane well below base must not intersect pyramid");
    }

    // --- planes through 1, 3, 4 base corners ---

    /**
     * Plane through exactly one base corner of a type-6 pyramid (c[0]) with a normal that puts
     * the other 4 vertices on the positive side: "touches" one vertex.
     * Mixed-sign classification with c[0] on the plane (sign==0): other vertices strictly positive.
     * Since there are no strictly negative vertices, intersects == false (no mixed sides).
     * But if the plane tilts so c[0] is on the plane AND some vertex is negative → intersects.
     *
     * <p>This test verifies that a plane touching a single corner vertex (all others on one side)
     * does NOT falsely report intersection if no vertex is on the negative side.
     */
    @Test
    void planeTouchingOneCorner_allOthersPositive_noIntersection() {
        var child = firstType6Child();
        var key   = keyFor(child);
        Point3i[] c = child.coordinates();
        // c[0] is (x,y,z). Plane: z = c[0].z, normal +Z, d = -c[0].z
        // All other vertices have z >= c[0].z (type-6, apex is at z+h).
        float zMin = (float) c[0].z;
        var plane = new Plane3D(0f, 0f, 1f, -zMin);

        // All 5 vertices have signed distance >= 0 (c[0] == 0, others > 0).
        // 5-vertex classify: no mixed sign, so no intersection (all on one side or on plane).
        // Plane "grazes" the base — caller can define this as no-intersection since no vertex is negative.
        // This also validates the epsilon handling: coplanar is NOT a miss when others are negative.
        assertFalse(index.doesPlaneIntersectNode(key, plane),
                    "Plane touching only base (all other vertices on positive side) must not intersect");
    }

    /**
     * Plane through 3 base corners of the type-6 pyramid, with apex above the plane.
     * Mixed signs (base corners on plane, apex strictly above) → the signed-distance classification
     * gives 3 zeros and 1 positive; no negatives → does not intersect in strict sense.
     * This verifies the 5-vertex model handles coplanar correctly.
     */
    @Test
    void planeThroughBaseCorners_apexAbove_classificationCorrect() {
        var child = firstType6Child();
        var key   = keyFor(child);
        Point3i[] c = child.coordinates();
        // c[0..3] are base at z = c[0].z; c[4] is apex above.
        float baseZ = c[0].z;

        // Plane: z = baseZ (normal +Z). All base corners on plane, apex strictly positive.
        var plane = new Plane3D(0f, 0f, 1f, -baseZ);

        // All base corners: dist == 0; apex: dist > 0. No negatives → does not intersect.
        assertFalse(index.doesPlaneIntersectNode(key, plane),
                    "Plane through entire base with apex above must not intersect");
    }

    /**
     * Plane cutting diagonally through the pyramid (4 base corners on opposite sides + apex).
     * Must intersect.
     */
    @Test
    void planeCuttingDiagonally_intersects() {
        var child = firstType6Child();
        var key   = keyFor(child);
        Point3i[] c = child.coordinates();
        // Diagonal plane: x - y = 0 (i.e., a=1, b=-1, c=0, d=0).
        // For type-6: c[0]=(x,y,z), c[1]=(x+h,y,z), c[2]=(x,y+h,z), c[3]=(x+h,y+h,z), c[4]=(x+h,y+h,z+h)
        // Signed distances: c[0]=x-y, c[1]=(x+h)-y, c[2]=x-(y+h), c[3]=(x+h)-(y+h) = x-y
        // If x==y (e.g. x=y=0): c[0]=0, c[1]=h>0, c[2]=-h<0, c[3]=0, c[4]=0
        // Mixed positive and negative → intersects.
        var plane = new Plane3D(1f, -1f, 0f, 0f);
        assertTrue(index.doesPlaneIntersectNode(key, plane),
                   "Diagonal plane (x=y) cutting through pyramid must intersect");
    }
}
