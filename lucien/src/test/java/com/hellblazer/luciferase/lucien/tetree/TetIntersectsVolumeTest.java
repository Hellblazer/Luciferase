// SPDX-License-Identifier: AGPL-3.0-or-later
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for tet-AABB intersection — specifically the edge-face crossing case
 * that the simple vertex-in-AABB and center-in-tet checks miss.
 *
 * @author hal.hildebrand
 */
public class TetIntersectsVolumeTest {

    /**
     * Demonstrates the edge-face crossing case where a tet edge crosses an AABB face but:
     *   - no tet vertex is inside the AABB, and
     *   - the AABB center is outside the tetrahedron.
     *
     * Test geometry (type=0, level=10, anchor=(0,0,0), h=2048):
     *   V0=(0,0,0), V1=(2048,0,0), V2=(2048,0,2048), V3=(2048,2048,2048)
     *
     * Edge V0→V2 traces (2048t, 0, 2048t). At t≈0.3 the point (614,0,614) falls
     * inside AABB [500..900] × [-50..50] × [500..900].
     *
     * AABB center (700, 0, 700) has x == z (not x > z) so it lies outside the
     * type-0 tetrahedron (which requires strict x > z > y). The centre-in-tet
     * check therefore returns false, and no vertex of the tet is in the AABB.
     */
    @Test
    void edgeCrossesAabbFace_noVertexInsideAndCenterOutsideTet_shouldReturnTrue() {
        // type-0 tet at level 10, anchor (0,0,0):  h = 2^(21-10) = 2048
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        // Vertices: V0=(0,0,0), V1=(2048,0,0), V2=(2048,0,2048), V3=(2048,2048,2048)

        // AABB: [500..900] × [-50..50] × [500..900]
        assertTrue(tet.intersects12DOP(500f, -50f, 500f, 900f, 50f, 900f),
                   "intersects12DOP should detect edge-face crossing "
                   + "even when no vertex is in the AABB and AABB center is outside the tet");
    }

    /**
     * Sanity check: simple cases still work.
     * Vertex-in-AABB case (AABB contains tet vertex V0 at origin).
     */
    @Test
    void vertexInsideAabb_shouldReturnTrue() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        // AABB that contains V0=(0,0,0)
        assertTrue(tet.intersects12DOP(-10f, -10f, -10f, 10f, 10f, 10f),
                   "vertex-in-AABB case must still return true");
    }

    /**
     * Sanity check: AABB center inside tet case.
     * The centroid of the type-0 tet (vertices (0,0,0),(h,0,0),(h,0,h),(h,h,h)) is
     * ((0+2048+2048+2048)/4, (0+0+0+2048)/4, (0+0+2048+2048)/4) = (1536, 512, 1024).
     */
    @Test
    void aabbCenterInsideTet_shouldReturnTrue() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        // Small AABB around centroid (1536, 512, 1024)
        assertTrue(tet.intersects12DOP(1500f, 488f, 1000f, 1572f, 536f, 1048f),
                   "AABB-center-in-tet case must still return true");
    }
}
