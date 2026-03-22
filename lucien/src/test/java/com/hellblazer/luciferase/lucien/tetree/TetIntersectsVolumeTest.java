// SPDX-License-Identifier: AGPL-3.0-or-later
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Spatial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Tet.tetrahedronIntersectsVolume() — specifically the edge-face crossing case
 * that the simple vertex-in-AABB and center-in-tet checks miss.
 *
 * @author hal.hildebrand
 */
public class TetIntersectsVolumeTest {

    /**
     * Demonstrates the false-negative bug where a tet edge crosses an AABB face but:
     *   - no tet vertex is inside the AABB, and
     *   - the AABB center is outside the tetrahedron.
     *
     * Test geometry (type=0, level=10, anchor=(0,0,0), h=2048):
     *   V0=(0,0,0), V1=(2048,0,0), V2=(2048,2048,0), V3=(2048,2048,2048)
     *
     * Edge V0→V2 traces (2048t, 2048t, 0). At t≈0.3 the point (614,614,0) falls
     * inside AABB [500..900] × [600..1100] × [-50..50].
     *
     * AABB center (700, 850, 0) has y > x so it lies outside the S0 tetrahedron
     * (S0 requires z ≤ y ≤ x in the cube). The centre-in-tet check therefore
     * returns false, and since no vertex of the tet is inside the AABB either,
     * the pre-fix implementation returns false — a false negative.
     */
    @Test
    void edgeCrossesAabbFace_noVertexInsideAndCenterOutsideTet_shouldReturnTrue() {
        // S0 tet at level 10, anchor (0,0,0):  h = 2^(21-10) = 2048
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        // Vertices: V0=(0,0,0), V1=(2048,0,0), V2=(2048,2048,0), V3=(2048,2048,2048)

        // AABB: originX=500, originY=600, originZ=-50, extentX=900, extentY=1100, extentZ=50
        // (Spatial.aabb stores extent fields as max coordinates, per VolumeBounds.from)
        var volume = new Spatial.aabb(500f, 600f, -50f, 900f, 1100f, 50f);

        // Edge V0→V2 passes through (≈614, 614, 0) which lies inside the AABB.
        // The SAT fallback is required to detect this intersection.
        assertTrue(Tet.tetrahedronIntersectsVolume(tet, volume),
                   "tetrahedronIntersectsVolume should detect edge-face crossing "
                   + "even when no vertex is in the AABB and AABB center is outside the tet");
    }

    /**
     * Sanity check: simple cases still work after the fix.
     * Vertex-in-AABB case (AABB contains tet vertex V0 at origin).
     */
    @Test
    void vertexInsideAabb_shouldReturnTrue() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        // AABB that contains V0=(0,0,0)
        var volume = new Spatial.aabb(-10f, -10f, -10f, 10f, 10f, 10f);
        assertTrue(Tet.tetrahedronIntersectsVolume(tet, volume),
                   "vertex-in-AABB case must still return true");
    }

    /**
     * Sanity check: AABB center inside tet case.
     * The centroid of the S0 tet is ((0+2048+2048+2048)/4, (0+0+2048+2048)/4, (0+0+0+2048)/4)
     * = (1536, 1024, 512).
     */
    @Test
    void aabbCenterInsideTet_shouldReturnTrue() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        // Small AABB around centroid (1536, 1024, 512)
        var volume = new Spatial.aabb(1500f, 1000f, 500f, 1572f, 1048f, 524f);
        assertTrue(Tet.tetrahedronIntersectsVolume(tet, volume),
                   "AABB-center-in-tet case must still return true");
    }
}
