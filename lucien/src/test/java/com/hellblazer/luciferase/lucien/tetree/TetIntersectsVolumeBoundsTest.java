package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Tet.intersects12DOP() specifically targeting the geometric-only case:
 * tet and AABB whose bounding boxes overlap but whose actual geometries do not intersect.
 */
public class TetIntersectsVolumeBoundsTest {

    /**
     * False-positive regression: SAT fallback case.
     *
     * S0 tet at origin, level 10: vertices are (0,0,0), (h,0,0), (h,h,0), (h,h,h).
     * The tet's volume is concentrated in the high-x region; at z ≈ h, only the vertex
     * (h,h,h) exists — far from x=0.
     *
     * The AABB is placed near (0, 0, h): its bounding box overlaps the tet's bounding
     * box, but no tet vertex lies inside the AABB, no AABB corner lies inside the tet,
     * and no tet edge crosses the AABB. The shapes are geometrically separate.
     *
     * Before the fix the method returned true (conservative fallback). After the fix it
     * must return false (SAT confirms separation).
     */
    @Test
    void falsePositiveFallback_noVertexNoCornerNoEdge_shouldReturnFalse() {
        // level 10 → h = 2^(21-10) = 2^11 = 2048
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        int h = Constants.lengthAtLevel((byte) 10);

        // AABB near (0, 0, h) — overlaps tet bounding box but misses the actual tet body.
        // Tet bounding box: [0,h]×[0,h]×[0,h]; AABB: [-1,1]×[-1,1]×[h-1,h+1]
        float margin = 1.0f;
        var bounds = new VolumeBounds(-margin, -margin, h - margin, margin, margin, h + margin);

        assertFalse(tet.intersects12DOP(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()),
                    "AABB near (0,0,h) should not intersect S0 tet whose body is in the high-x region");
    }

    /**
     * Sanity check: an AABB that genuinely overlaps the tet's interior must still return true.
     *
     * The S0 tet centroid is approximately ((0+h+h+h)/4, (0+0+h+h)/4, (0+0+0+h)/4)
     * = (3h/4, h/2, h/4). A small AABB centred there is inside the tet.
     */
    @Test
    void truePositive_aabbInsideTet_shouldReturnTrue() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        int h = Constants.lengthAtLevel((byte) 10);

        // Place AABB at the tet centroid ≈ (3h/4, h/2, h/4) with small extent
        float cx = 3f * h / 4f;
        float cy = h / 2f;
        float cz = h / 4f;
        float half = h / 20f;  // small extent relative to tet size
        var bounds = new VolumeBounds(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);

        assertTrue(tet.intersects12DOP(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()),
                   "AABB centred on S0 tet centroid should intersect");
    }

    /**
     * Sanity check: an AABB entirely outside the tet bounding box is rejected early (not
     * the SAT case, but should still return false).
     */
    @Test
    void aabbCompletelyOutsideBoundingBox_shouldReturnFalse() {
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        int h = Constants.lengthAtLevel((byte) 10);

        // AABB far beyond the tet in all axes
        var bounds = new VolumeBounds(2f * h, 2f * h, 2f * h, 3f * h, 3f * h, 3f * h);

        assertFalse(tet.intersects12DOP(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()),
                    "AABB entirely outside tet bounding box should not intersect");
    }
}
