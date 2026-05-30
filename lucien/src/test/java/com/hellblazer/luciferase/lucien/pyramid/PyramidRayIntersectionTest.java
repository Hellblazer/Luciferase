/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase D: TDD tests for {@link PyramidIndex#doesRayIntersectNode} and
 * {@link PyramidIndex#getRayNodeIntersectionDistance}.
 *
 * <p>Tests use analytic geometry to derive exact expected distances. Pyramid faces are conforming
 * (each face shared by exactly two cells), so shared-vertex checks are valid.
 *
 * @author hal.hildebrand
 */
class PyramidRayIntersectionTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    // --- helpers ---

    /**
     * Build a PyramidKey at level 1 for a type-6 pyramid rooted at the origin.
     * Level-1 cell size = Constants.lengthAtLevel(1).
     */
    private PyramidKey level1Type6Key() {
        float cellSize = Constants.lengthAtLevel((byte) 1);
        // calculateSpatialIndex of the centroid of the type-6 root child should give us a level-1 key.
        // Use the Pyramid directly: type-6, origin 0,0,0, level 0 → child 0 is a type-6 level-1 pyramid.
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        // child(0) is the first child; find a pyramid child
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                var centroid = pc.centroid();
                return index.calculateSpatialIndex(centroid, (byte) 1);
            }
        }
        throw new AssertionError("No type-6 child found");
    }

    /**
     * Compute the AABB (surrounding cube) for a given key and return anchor + extent.
     * Returns float[4]: { minX, minY, minZ, extent }.
     */
    private float[] nodeAABB(PyramidKey key) {
        float px = 0, py = 0, pz = 0;
        byte level = key.getLevel();
        for (int l = 1; l <= level; l++) {
            float childSize = Constants.lengthAtLevel((byte) l);
            int cubeId = key.getCoordBitsAtLevel(l);
            if ((cubeId & 1) != 0) px += childSize;
            if ((cubeId & 2) != 0) py += childSize;
            if ((cubeId & 4) != 0) pz += childSize;
        }
        float cellSize = Constants.lengthAtLevel(level);
        return new float[] { px, py, pz, cellSize };
    }

    // --- doesRayIntersectNode ---

    /**
     * Ray aimed directly at a pyramid from outside (from the -X side), entering through
     * a triangular face.  Must return true.
     */
    @Test
    void rayFromNegativeXHitsPyramid() {
        // Use a level-1 type-6 pyramid: apex is at (x+h, y+h, z+h), base is the z=z plane.
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Pyramid child = null;
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                child = pc;
                break;
            }
        }
        assertNotNull(child, "Expected a type-6 child pyramid");
        var centroid = child.centroid();
        var key = index.calculateSpatialIndex(centroid, (byte) 1);

        // Ray origin is well to the left of the node AABB; direction +X
        float[] aabb = nodeAABB(key);
        var origin = new Point3f(aabb[0] - 10f, aabb[1] + aabb[3] / 2f, aabb[2] + aabb[3] / 2f);
        var dir    = new Vector3f(1f, 0f, 0f);
        var ray    = new Ray3D(origin, dir);

        assertTrue(index.doesRayIntersectNode(key, ray),
                   "Ray from -X aimed at pyramid centre should intersect");
    }

    /**
     * Ray that clearly misses the pyramid (passes well below it).
     */
    @Test
    void rayMissesPyramidBelow() {
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Pyramid child = null;
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                child = pc;
                break;
            }
        }
        assertNotNull(child);
        var centroid = child.centroid();
        var key = index.calculateSpatialIndex(centroid, (byte) 1);
        float[] aabb = nodeAABB(key);

        // Ray travels at z well below the node's minZ
        var origin = new Point3f(aabb[0] - 10f, aabb[1] + aabb[3] / 2f, aabb[2] - 100f);
        var dir    = new Vector3f(1f, 0f, 0f);
        var ray    = new Ray3D(origin, dir);

        assertFalse(index.doesRayIntersectNode(key, ray),
                    "Ray well below pyramid should not intersect");
    }

    /**
     * Ray aimed at the quad base (f4) from directly below: critical case.
     * The quad base must be split into 2 triangles; a single-triangle test would miss half the base.
     *
     * <p>TYPE-6 pyramid: base corners are c[0]=(x,y,z), c[1]=(x+h,y,z), c[2]=(x,y+h,z), c[3]=(x+h,y+h,z).
     * A ray aimed from z = z-100 through the centre of the base ((x+h/2, y+h/2, z)) enters via f4.
     */
    @Test
    void rayFromBelowHitsQuadBase_criticalCase() {
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Pyramid child = null;
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                child = pc;
                break;
            }
        }
        assertNotNull(child);
        Point3i[] corners = child.coordinates();
        // c[0..3] = base, c[4] = apex
        float baseZ  = corners[0].z;
        float midX   = (corners[0].x + corners[3].x) / 2f;
        float midY   = (corners[0].y + corners[3].y) / 2f;

        // Ray from well below, aimed straight up through quad base centre
        var origin = new Point3f(midX, midY, baseZ - 100f);
        var dir    = new Vector3f(0f, 0f, 1f);
        var ray    = new Ray3D(origin, dir);

        var key = index.calculateSpatialIndex(child.centroid(), (byte) 1);
        assertTrue(index.doesRayIntersectNode(key, ray),
                   "Ray through quad base centre must intersect (f4 triangle-split correctness)");
    }

    /**
     * TYPE-7 variant of the critical quad-base case (review coverage gap). A TYPE-7 pyramid has its
     * apex at the low-z corner and its quad base at the high-z face, so the f4 base is reached by a
     * ray aimed DOWNWARD from above through the base centre. Exercises the c[0]→c[3] diagonal split
     * on the opposite-handed pyramid.
     */
    @Test
    void rayFromAboveHitsQuadBaseType7_criticalCase() {
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_7);
        Pyramid child = null;
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_7) {
                child = pc;
                break;
            }
        }
        assertNotNull(child);
        Point3i[] corners = child.coordinates();
        // c[0..3] = base, c[4] = apex. Aim through the base-quad centre along the base diagonal mid.
        float midX = (corners[0].x + corners[3].x) / 2f;
        float midY = (corners[0].y + corners[3].y) / 2f;
        // Base z of a TYPE-7 pyramid is the MAX z of the base corners; approach from above.
        float baseZ = Math.max(Math.max(corners[0].z, corners[1].z),
                               Math.max(corners[2].z, corners[3].z));

        var origin = new Point3f(midX, midY, baseZ + 100f);
        var dir    = new Vector3f(0f, 0f, -1f);
        var ray    = new Ray3D(origin, dir);

        var key = index.calculateSpatialIndex(child.centroid(), (byte) 1);
        assertTrue(index.doesRayIntersectNode(key, ray),
                   "TYPE-7 ray through quad base centre must intersect (f4 split correctness, opposite handedness)");
    }

    /**
     * A ray striking the f4 quad base EXACTLY at the c[0]→c[3] diagonal seam (the boundary shared by
     * the two split triangles). This is the known-hard f4 case: the seam point must be claimed by at
     * least one of the two triangles (no gap), and the reported entry distance must be the analytic
     * standoff. A naive split that left the diagonal uncovered would miss here.
     */
    @Test
    void rayThroughQuadBaseDiagonalSeam_isCovered() {
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Pyramid child = null;
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                child = pc;
                break;
            }
        }
        assertNotNull(child);
        Point3i[] corners = child.coordinates();
        // TYPE-6 base is at z = baseZ; the split diagonal is c[0]→c[3]. Aim a ray straight up
        // (perpendicular to the base) through the MIDPOINT of that diagonal — the exact seam point.
        float baseZ = corners[0].z;
        float seamX = (corners[0].x + corners[3].x) / 2f;
        float seamY = (corners[0].y + corners[3].y) / 2f;
        float standoff = 100f;

        var origin = new Point3f(seamX, seamY, baseZ - standoff);
        var dir    = new Vector3f(0f, 0f, 1f);
        var ray    = new Ray3D(origin, dir);

        var key = index.calculateSpatialIndex(child.centroid(), (byte) 1);
        assertTrue(index.doesRayIntersectNode(key, ray),
                   "Ray hitting the f4 diagonal seam must be claimed by at least one split triangle (no gap)");
        float t = index.getRayNodeIntersectionDistance(key, ray);
        assertEquals(standoff, t, 1e-2f,
                     "Entry distance at the seam must equal the analytic standoff to the base plane");
    }

    /**
     * Ray aimed from apex direction (from outside above) toward the apex: must intersect.
     */
    @Test
    void rayFromApexDirection_intersects() {
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Pyramid child = null;
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                child = pc;
                break;
            }
        }
        assertNotNull(child);
        Point3i[] corners = child.coordinates();
        float apexX = corners[4].x, apexY = corners[4].y, apexZ = corners[4].z;

        // Ray from well above apex, aimed straight down
        var origin = new Point3f(apexX, apexY, apexZ + 100f);
        var dir    = new Vector3f(0f, 0f, -1f);
        var ray    = new Ray3D(origin, dir);

        var key = index.calculateSpatialIndex(child.centroid(), (byte) 1);
        assertTrue(index.doesRayIntersectNode(key, ray),
                   "Ray from above aimed at apex should intersect");
    }

    // --- getRayNodeIntersectionDistance ---

    /**
     * Ray aimed from -X through the node's AABB centre: entry distance must be positive, finite,
     * and the hit point must land on the near side of the pyramid's AABB (x >= aabb.minX).
     *
     * <p>Note: pyramid faces are triangular (non-AABB-aligned), so the entry t is NOT simply the
     * distance from origin to the AABB min-x face. The test asserts a real geometric hit point
     * rather than an AABB-derived expected value.
     */
    @Test
    void rayEntryDistance_hitPointInsideAABB() {
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Pyramid child = null;
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                child = pc;
                break;
            }
        }
        assertNotNull(child);
        var key = index.calculateSpatialIndex(child.centroid(), (byte) 1);
        float[] aabb = nodeAABB(key);

        float midY = aabb[1] + aabb[3] / 2f;
        float midZ = aabb[2] + aabb[3] / 2f;
        // Origin well to the left of the node's AABB
        var origin = new Point3f(aabb[0] - aabb[3], midY, midZ);
        var dir    = new Vector3f(1f, 0f, 0f);
        var ray    = new Ray3D(origin, dir);

        float dist = index.getRayNodeIntersectionDistance(key, ray);

        // Must be a finite positive hit (not MAX_VALUE)
        assertTrue(dist < Float.MAX_VALUE, "Ray aimed at pyramid must return finite distance");
        assertTrue(dist > 0f, "Entry distance must be positive");
        // The hit point must be within or at the boundary of the AABB (x in [aabb[0], aabb[0]+extent])
        float hitX = origin.x + dist * dir.x;
        assertTrue(hitX >= aabb[0] - 1f,
                   "Hit point x=" + hitX + " must be >= aabb.minX=" + aabb[0]);
        assertTrue(hitX <= aabb[0] + aabb[3] + 1f,
                   "Hit point x=" + hitX + " must be <= aabb.maxX=" + (aabb[0] + aabb[3]));
    }

    /**
     * Ray that misses: getRayNodeIntersectionDistance must return Float.MAX_VALUE.
     */
    @Test
    void missRay_returnsMaxValue() {
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Pyramid child = null;
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                child = pc;
                break;
            }
        }
        assertNotNull(child);
        var key = index.calculateSpatialIndex(child.centroid(), (byte) 1);
        float[] aabb = nodeAABB(key);

        // Ray passing completely below the node
        var origin = new Point3f(aabb[0] - 10f, aabb[1] + aabb[3] / 2f, aabb[2] - 100f);
        var dir    = new Vector3f(1f, 0f, 0f);
        var ray    = new Ray3D(origin, dir);

        float dist = index.getRayNodeIntersectionDistance(key, ray);
        assertEquals(Float.MAX_VALUE, dist,
                     "Miss ray must return Float.MAX_VALUE");
    }

    /**
     * Entry distance monotonically increases as the ray origin moves further away.
     * Tests that the distance is a real measurement, not a constant or zero.
     */
    @Test
    void entryDistanceMonotonicallyIncreasesWithStandoff() {
        var rootPyramid = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        Pyramid child = null;
        for (int i = 0; i < 10; i++) {
            var c = rootPyramid.child(i);
            if (c instanceof Pyramid pc && pc.type() == Pyramid.TYPE_6) {
                child = pc;
                break;
            }
        }
        assertNotNull(child);
        var key = index.calculateSpatialIndex(child.centroid(), (byte) 1);
        float[] aabb = nodeAABB(key);
        float midY = aabb[1] + aabb[3] / 2f;
        float midZ = aabb[2] + aabb[3] / 2f;

        float prevDist = -1f;
        for (float standoff : new float[] { 10f, 20f, 50f, 100f }) {
            var origin = new Point3f(aabb[0] - standoff, midY, midZ);
            var ray    = new Ray3D(origin, new Vector3f(1f, 0f, 0f));
            float dist = index.getRayNodeIntersectionDistance(key, ray);
            assertTrue(dist > prevDist,
                       "Entry distance must increase as origin moves further away (standoff=" + standoff + ")");
            prevDist = dist;
        }
    }
}
