/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PyramidIndex.doesFrustumIntersectNode (Phase E, bead Luciferase-ioz).
 * Validates the 5-vertex-vs-6-plane convex-hull test for pyramid frustum intersection.
 */
class PyramidFrustumIntersectionTest {

    private PyramidIndex<LongEntityID, String> index;

    @BeforeEach
    void setUp() {
        index = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    /**
     * Build a simple axis-aligned frustum box defined by six planes.
     * Each plane is an inward-facing half-space; points inside the frustum
     * have non-negative signed distance to all six planes.
     *
     * The box spans [minX..maxX] × [minY..maxY] × [minZ..maxZ].
     */
    private static Frustum3D boxFrustum(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
        // +X face: normal (-1,0,0), pass through maxX → points inside have x <= maxX
        var pNX = Plane3D.fromPointAndNormal(new Point3f(maxX, 0, 0), new Vector3f(-1, 0, 0));
        // -X face: normal (+1,0,0), pass through minX → points inside have x >= minX
        var pPX = Plane3D.fromPointAndNormal(new Point3f(minX, 0, 0), new Vector3f(1, 0, 0));
        // +Y face
        var pNY = Plane3D.fromPointAndNormal(new Point3f(0, maxY, 0), new Vector3f(0, -1, 0));
        // -Y face
        var pPY = Plane3D.fromPointAndNormal(new Point3f(0, minY, 0), new Vector3f(0, 1, 0));
        // +Z face
        var pNZ = Plane3D.fromPointAndNormal(new Point3f(0, 0, maxZ), new Vector3f(0, 0, -1));
        // -Z face
        var pPZ = Plane3D.fromPointAndNormal(new Point3f(0, 0, minZ), new Vector3f(0, 0, 1));
        return new Frustum3D(pPZ, pNZ, pPX, pNX, pNY, pPY);
    }

    /**
     * Returns a PyramidKey for the level-1 type-6 child (first pyramid child of the root).
     */
    private PyramidKey level1Type6Key() {
        var root = new Pyramid(0, 0, 0, (byte) 0, Pyramid.TYPE_6);
        int row = root.type() - Pyramid.TYPE_6;
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_PYRAMID; i++) {
            var child = root.child(i);
            if (child instanceof Pyramid p && p.type() == Pyramid.TYPE_6) {
                int cb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_CID[row][i];
                int tb = TetreeConnectivity.PYRAMID_PARENT_TO_CHILD_TYPE[row][i];
                return PyramidKey.fromLevels((byte) 1, new int[]{ 0, cb }, new int[]{ 0, tb });
            }
        }
        throw new IllegalStateException("No type-6 child at level 1");
    }

    @Test
    void frustumFullyContainingPyramid_intersects() {
        // A giant box that contains everything should intersect any pyramid key
        var frustum = boxFrustum(0, 1_000_000, 0, 1_000_000, 0, 1_000_000);
        var key = PyramidKey.getRoot();
        assertTrue(index.doesFrustumIntersectNode(key, frustum),
                   "A frustum containing all space must intersect the root");
    }

    @Test
    void frustumCompletelyOutside_noIntersect() {
        // Frustum at very high coordinates, root pyramid is at (0,0,0)
        int edge = Constants.lengthAtLevel((byte) 1);
        var frustum = boxFrustum(edge * 10f, edge * 20f, edge * 10f, edge * 20f, edge * 10f, edge * 20f);
        var key = PyramidKey.getRoot();
        assertFalse(index.doesFrustumIntersectNode(key, frustum),
                    "A frustum far away from origin must NOT intersect the root pyramid");
    }

    @Test
    void frustumContainingApexOnly() {
        // TYPE_6 apex is at (x+h, y+h, z+h); TYPE_7 apex is at (x, y, z).
        // Root (level 0) type-6: x=y=z=0, h = lengthAtLevel(0) (the full cube).
        // Apex of root type-6 = (h, h, h). Build a tiny box just around that point.
        int h = Constants.lengthAtLevel((byte) 0);
        float apexX = h, apexY = h, apexZ = h;
        float eps = h * 0.001f;
        var frustum = boxFrustum(apexX - eps, apexX + eps,
                                 apexY - eps, apexY + eps,
                                 apexZ - eps, apexZ + eps);
        var key = PyramidKey.getRoot();
        assertTrue(index.doesFrustumIntersectNode(key, frustum),
                   "A frustum containing only the apex must still intersect the pyramid");
    }

    @Test
    void frustumContainingBaseCenterOnly() {
        // TYPE_6 base is the square at z=0; base center = (h/2, h/2, 0).
        int h = Constants.lengthAtLevel((byte) 0);
        float cx = h / 2f, cy = h / 2f, cz = 0f;
        float eps = h * 0.001f;
        // Use a slim box at the base; z must be ≥ 0 (frustum requires positive coords)
        var frustum = boxFrustum(cx - eps, cx + eps, cy - eps, cy + eps, 0, eps);
        var key = PyramidKey.getRoot();
        assertTrue(index.doesFrustumIntersectNode(key, frustum),
                   "A frustum at the base center must intersect the pyramid");
    }

    @Test
    void frustumAlignedWithOneSeparatingAxis_noIntersect() {
        // Place a frustum entirely on one side of the X-axis beyond the root pyramid's extent.
        // Root type-6 has all X in [0..h]; frustum starts at h + large delta.
        int h = Constants.lengthAtLevel((byte) 0);
        float gap = h * 2f;
        var frustum = boxFrustum(h + gap, h + gap * 2, 0, h, 0, h);
        var key = PyramidKey.getRoot();
        assertFalse(index.doesFrustumIntersectNode(key, frustum),
                    "Frustum beyond pyramid on X axis must NOT intersect");
    }

    @Test
    void degenerateFrustum_cameraAtApex_intersects() {
        // A frustum built with very small dimensions centered on the apex.
        // This tests the degenerate camera-at-apex case from the bead spec.
        int h = Constants.lengthAtLevel((byte) 0);
        float apexX = h, apexY = h, apexZ = h;
        float tiny = 1.0f; // 1 unit box
        var frustum = boxFrustum(apexX, apexX + tiny, apexY, apexY + tiny, apexZ, apexZ + tiny);
        // The apex is a vertex of the root pyramid; a box touching the apex should intersect
        var key = PyramidKey.getRoot();
        assertTrue(index.doesFrustumIntersectNode(key, frustum),
                   "Degenerate frustum at the apex should intersect the pyramid");
    }
}
