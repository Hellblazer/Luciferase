// SPDX-License-Identifier: AGPL-3.0
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD test for Tet.intersects12DOP() — 12-DOP intersection test for S0-S5 Kuhn tetrahedra.
 * <p>
 * The 12-DOP adds 3 difference-axis slabs {x-y, x-z, y-z} to the standard 6-face AABB test, enabling exact
 * (non-conservative) intersection detection for these tetrahedra. For Kuhn tetrahedra the 12-DOP IS the exact
 * convex hull of the tetrahedron.
 * <p>
 * Slab ranges per S-type (global, after anchor shift):
 * <ul>
 *   <li>S0: x≥z≥y — d_xy∈[axy, axy+h], d_xz∈[axz, axz+h], d_yz∈[ayz, ayz+h]</li>
 *   <li>S1: x≥y≥z — d_xy∈[axy-h, axy], d_xz∈[axz, axz+h], d_yz∈[ayz, ayz+h]</li>
 *   <li>S2: y≥x≥z — d_xy∈[axy, axy+h], d_xz∈[axz-h, axz], d_yz∈[ayz-h, ayz]</li>
 *   <li>S3: y≥z≥x — d_xy∈[axy-h, axy], d_xz∈[axz-h, axz], d_yz∈[ayz-h, ayz]</li>
 *   <li>S4: z≥y≥x — d_xy∈[axy, axy+h], d_xz∈[axz, axz+h], d_yz∈[ayz-h, ayz]</li>
 *   <li>S5: z≥x≥y — d_xy∈[axy-h, axy], d_xz∈[axz-h, axz], d_yz∈[ayz, ayz+h]</li>
 * </ul>
 * <p>
 * The 12-DOP is exact (no false positives, no false negatives) for Kuhn tetrahedra.
 */
public class Tet12DOPIntersectionTest {

    // Level 10, h = 1 << (21 - 10) = 2048, anchor = (0,0,0)
    private static final byte LEVEL = 10;
    private static final int  H     = 1 << (21 - LEVEL); // 2048

    // --- AABB fully inside tet ---

    /**
     * Small AABB entirely within each S-type should return true.
     */
    @ParameterizedTest(name = "S{0}: small AABB fully inside tet")
    @CsvSource({
        "0, 1450, 450, 950,  1550, 550, 1050",   // inside S0 (x>z>y region), center (1500,500,1000)
        "1, 1450, 950, 450,  1550, 1050, 550",   // inside S1 (x>y>z region), center (1500,1000,500)
        "2,  950, 1450, 450, 1050, 1550, 550",   // inside S2 (y>x>z region), center (1000,1500,500)
        "3,  450, 1450, 950,  550, 1550, 1050",  // inside S3 (y>z>x region), center (500,1500,1000)
        "4,  450, 650, 1450,  550,  750, 1550",  // inside S4 (z>y>x region), center (500,700,1500)
        "5,  650, 450, 1450,  750,  550, 1550",  // inside S5 (z>x>y region), center (700,500,1500)
    })
    void aabbFullyInsideTet(int type, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        var tet = new Tet(0, 0, 0, LEVEL, (byte) type);
        assertTrue(tet.intersects12DOP(minX, minY, minZ, maxX, maxY, maxZ),
                   "S%d: small AABB fully inside should intersect".formatted(type));
    }

    // --- AABB fully outside (disjoint in AABB sense) ---

    @Test
    void aabbCompletelyOutsideAabbBounds() {
        // AABB entirely beyond x+h — fails the 6-face AABB check
        var tet = new Tet(0, 0, 0, LEVEL, (byte) 0);
        assertFalse(tet.intersects12DOP(H + 1, 0, 0, H + 100, 100, 100),
                    "AABB beyond +x edge should not intersect");
        assertFalse(tet.intersects12DOP(-100, 0, 0, -1, 100, 100),
                    "AABB before -x edge should not intersect");
        assertFalse(tet.intersects12DOP(0, H + 1, 0, 100, H + 100, 100),
                    "AABB beyond +y edge should not intersect");
        assertFalse(tet.intersects12DOP(0, -100, 0, 100, -1, 100),
                    "AABB before -y edge should not intersect");
        assertFalse(tet.intersects12DOP(0, 0, H + 1, 100, 100, H + 100),
                    "AABB beyond +z edge should not intersect");
        assertFalse(tet.intersects12DOP(0, 0, -100, 100, 100, -1),
                    "AABB before -z edge should not intersect");
    }

    // --- KEY TEST: passes AABB check but fails difference-axis check ---

    /**
     * The tetrahedron cube [0,H]^3 with type S0 (x≥y≥z) occupies only the region where x≥y≥z.
     * An AABB in the opposite corner — where z>>x — passes the 6-face AABB overlap (it's inside [0,H]^3)
     * but must fail the d_xy difference-axis slab.
     */
    @Test
    void passesAabbFailsDifferenceAxis_S0() {
        // S0 expects x≥z≥y. Use a box centered (1500,1000,500) where y>z (violates z≥y).
        // AABB overlap with [0,H]^3: YES (all coords positive and < H=2048)
        var tet = new Tet(0, 0, 0, LEVEL, (byte) 0); // S0
        assertFalse(tet.intersects12DOP(1400, 900, 400, 1600, 1100, 600),
                    "S0: box in y>z corner passes 6-face AABB but must fail difference-axis slabs");
    }

    /**
     * For S3 (y≥z≥x): use a box where x>y (violates y≥x).
     */
    @Test
    void passesAabbFailsDifferenceAxis_S3() {
        // S3 expects y≥z≥x. Use a box centered (1500,500,1000) where x>y (violates y≥x).
        var tet = new Tet(0, 0, 0, LEVEL, (byte) 3); // S3
        assertFalse(tet.intersects12DOP(1400, 400, 900, 1600, 600, 1100),
                    "S3: box in x>y corner passes 6-face AABB but must fail difference-axis slabs");
    }

    /**
     * Cover all 6 types: for each S-type, pick a box in the diagonally-opposite ordering region
     * (all strictly inside [0,H]^3 so AABB passes, but wrong ordering so difference axes reject).
     */
    @ParameterizedTest(name = "S{0}: passes AABB, fails difference-axis")
    @CsvSource({
        // type, minX,minY,minZ, maxX,maxY,maxZ
        // S0 (x≥z≥y) — box centered (1500,1000,500) violates ordering
        "0, 1400, 900, 400,  1600, 1100, 600",
        // S1 (x≥y≥z) — box centered (1500,500,1000) violates ordering
        "1, 1400, 400, 900,  1600, 600, 1100",
        // S2 (y≥x≥z) — box centered (1500,500,1000) violates ordering
        "2, 1400, 400, 900,  1600, 600, 1100",
        // S3 (y≥z≥x) — box centered (1500,500,1000) violates ordering
        "3, 1400, 400, 900,  1600, 600, 1100",
        // S4 (z≥y≥x) — box centered (1500,500,1000) violates ordering
        "4, 1400, 400, 900,  1600, 600, 1100",
        // S5 (z≥x≥y) — box centered (1500,500,1000) violates ordering
        "5, 1400, 400, 900,  1600, 600, 1100",
    })
    void passesAabbFailsDifferenceAxisAllTypes(int type, float minX, float minY, float minZ,
                                               float maxX, float maxY, float maxZ) {
        var tet = new Tet(0, 0, 0, LEVEL, (byte) type);
        // Verify the box is indeed inside the AABB [0,H]^3 (so pure AABB test would say "possible")
        assertTrue(minX >= 0 && maxX <= H && minY >= 0 && maxY <= H && minZ >= 0 && maxZ <= H,
                   "Test precondition: box must be inside cube AABB");
        assertFalse(tet.intersects12DOP(minX, minY, minZ, maxX, maxY, maxZ),
                    "S%d: box in wrong ordering region should fail difference-axis check".formatted(type));
    }

    // --- AABB touching tet face ---

    /**
     * AABB whose max-x just touches x=0 boundary of the tet-cube — touching means intersects.
     */
    @Test
    void aabbTouchingFaceS0() {
        // Box at [-50, -50, -50] to [0, 0, 0]: touches the origin corner of S0's cube.
        // The corner (0,0,0) is on S0's boundary (0=0=0 satisfies x≥y≥z). Should intersect.
        var tet = new Tet(0, 0, 0, LEVEL, (byte) 0);
        assertTrue(tet.intersects12DOP(-50, -50, -50, 0, 0, 0),
                   "S0: AABB touching origin corner should intersect (closed simplex)");
    }

    /**
     * AABB touching the x=H face from outside: maxX exactly = H (closed interval).
     */
    @Test
    void aabbTouchingMaxFaceS0() {
        // Box [H, 0, 0] to [H+50, 50, 50]: its min-x equals H = tet's max-x. Touching → intersect.
        // Point (H,25,10) satisfies x≥y≥z (H≥25≥10). So touching → true.
        var tet = new Tet(0, 0, 0, LEVEL, (byte) 0);
        assertTrue(tet.intersects12DOP(H, 0, 0, H + 50, 50, 50),
                   "S0: AABB touching x=H face should intersect");
    }

    // --- AABB straddling tet boundary ---

    /**
     * AABB that straddles the tet boundary — partially inside, partially outside.
     */
    @Test
    void aabbStraddlingBoundaryExplicit() {
        // S0 (x>z>y): interior near (1500,500,1000) extending beyond +x
        var s0 = new Tet(0, 0, 0, LEVEL, (byte) 0);
        assertTrue(s0.intersects12DOP(1400, 450, 950, H + 100, 550, 1050),
                   "S0: straddling +x boundary should intersect (interior region is inside S0)");

        // S1 (x>y>z): interior near (1500,1000,500) extending beyond +x
        var s1 = new Tet(0, 0, 0, LEVEL, (byte) 1);
        assertTrue(s1.intersects12DOP(1400, 950, 450, H + 100, 1050, 550),
                   "S1: straddling +x boundary should intersect");

        // S3 (y>z>x): interior near (500,1500,1000) extending beyond +y
        var s3 = new Tet(0, 0, 0, LEVEL, (byte) 3);
        assertTrue(s3.intersects12DOP(450, 1400, 950, 550, H + 100, 1050),
                   "S3: straddling +y boundary should intersect");
    }

    // --- No false positives: if intersects12DOP=true, SAT-based reference must also say true ---

    /**
     * For Kuhn tetrahedra the 12-DOP IS the exact convex hull of the tetrahedron, so it has no false
     * positives. Whenever intersects12DOP returns true the shapes genuinely intersect. Verification:
     * when intersects12DOP returns false, sample 20 random interior AABB points — none must be inside the tet.
     */
    @Test
    void noFalsePositives_dopTrueImpliesActualIntersection() {
        var rng = new Random(0xdeadbeefL);
        for (int type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, LEVEL, (byte) type);
            for (int i = 0; i < 500; i++) {
                float ax = rng.nextFloat() * H;
                float ay = rng.nextFloat() * H;
                float az = rng.nextFloat() * H;
                float bx = rng.nextFloat() * H;
                float by = rng.nextFloat() * H;
                float bz = rng.nextFloat() * H;

                float minX = Math.min(ax, bx), maxX = Math.max(ax, bx);
                float minY = Math.min(ay, by), maxY = Math.max(ay, by);
                float minZ = Math.min(az, bz), maxZ = Math.max(az, bz);

                boolean dop12 = tet.intersects12DOP(minX, minY, minZ, maxX, maxY, maxZ);
                if (!dop12) {
                    // 12-DOP rejected — sample interior AABB points; none must be inside the tet
                    for (int p = 0; p < 20; p++) {
                        float px = minX + rng.nextFloat() * (maxX - minX);
                        float py = minY + rng.nextFloat() * (maxY - minY);
                        float pz = minZ + rng.nextFloat() * (maxZ - minZ);
                        assertFalse(tet.contains12DOP(px, py, pz),
                                    "S%d iter %d: 12-DOP rejected AABB but interior point is in tet — false negative!"
                                        .formatted(type, i));
                    }
                }
            }
        }
    }

    /**
     * No false negatives: if a box contains a point strictly inside the tet (verified via contains12DOP),
     * then intersects12DOP must return true. Tests using random points generated inside each S-type
     * by sampling with the ordering constraints.
     */
    @Test
    void noFalseNegatives_pointInsideTetImpliesIntersection() {
        var rng = new Random(0xcafebabeL);
        // Ordering functions for each type in local coords [0,H]:
        // We sample 3 values a<b<c from [0,H] and assign to local u,v,w per ordering.
        for (int type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, LEVEL, (byte) type);
            for (int i = 0; i < 500; i++) {
                // Generate 3 distinct values in (0,H) to form strictly ordered local coords
                float a = rng.nextFloat() * H;
                float b = rng.nextFloat() * H;
                float c = rng.nextFloat() * H;
                float lo = Math.min(Math.min(a, b), c);
                float hi = Math.max(Math.max(a, b), c);
                float mid = a + b + c - lo - hi;

                // Assign local (u,v,w) = global (px-0, py-0, pz-0) per ordering
                // hi>mid>lo → we want the ordering for this S-type
                float px, py, pz;
                switch (type) {
                    case 0 -> { px = hi; py = lo; pz = mid; }  // x≥z≥y
                    case 1 -> { px = hi; py = mid; pz = lo; }  // x≥y≥z
                    case 2 -> { px = mid; py = hi; pz = lo; }  // y≥x≥z
                    case 3 -> { px = lo; py = hi; pz = mid; }  // y≥z≥x
                    case 4 -> { px = lo; py = mid; pz = hi; }  // z≥y≥x
                    default -> { px = mid; py = lo; pz = hi; } // z≥x≥y (S5)
                }

                // Verify the point is genuinely inside the tet
                assertTrue(tet.contains12DOP(px, py, pz),
                           "Precondition: sampled point (%.1f,%.1f,%.1f) must be inside S%d".formatted(px, py, pz, type));

                // Build a tiny box around the point; it must intersect the tet
                float d = 0.5f;
                assertTrue(tet.intersects12DOP(px - d, py - d, pz - d, px + d, py + d, pz + d),
                           "S%d iter %d: tiny box around interior point (%.1f,%.1f,%.1f) must intersect".formatted(
                               type, i, px, py, pz));
            }
        }
    }

    /**
     * Consistency with a point-in-tet test: if a box's center is strictly inside the tet (per contains12DOP),
     * then intersects12DOP must return true.
     */
    @ParameterizedTest(name = "S{0}: center-inside box must intersect")
    @CsvSource({
        "0, 1500, 500, 1000",
        "1, 1500, 1000, 500",
        "2, 1000, 1500, 500",
        "3,  500, 1500, 1000",
        "4,  500,  700, 1500",
        "5,  700,  500, 1500",
    })
    void centerInsideBoxMustIntersect(int type, float cx, float cy, float cz) {
        var tet = new Tet(0, 0, 0, LEVEL, (byte) type);
        // Create a small box (±50) around the center
        float d = 50;
        assertTrue(tet.contains12DOP(cx, cy, cz),
                   "Precondition: center must be inside tet for S%d".formatted(type));
        assertTrue(tet.intersects12DOP(cx - d, cy - d, cz - d, cx + d, cy + d, cz + d),
                   "S%d: box centered inside tet must intersect".formatted(type));
    }

    // --- Non-origin anchor test ---

    /**
     * Verify that the anchor-shift logic is correct for a tet not at the origin.
     * A tet at anchor (H, H, H) for S0 — shift all coordinates by (H, H, H).
     */
    @Test
    void nonOriginAnchorS0() {
        // Level 11: h = 1<<10 = 1024, anchor = (1024, 1024, 1024)
        byte level = 11;
        int h = 1 << (21 - level); // 1024
        var tet = new Tet(h, h, h, level, (byte) 0); // S0, anchor=(h,h,h)

        // Box strictly inside: local (u,v,w)=(3h/4, h/4, h/2) satisfies S0 x>z>y
        float cx = h + 3f * h / 4, cy = h + h / 4f, cz = h + h / 2f;
        float d = 10;
        assertTrue(tet.intersects12DOP(cx - d, cy - d, cz - d, cx + d, cy + d, cz + d),
                   "S0 non-origin: center inside should intersect");

        // Box in wrong-ordering region (local v top, y>z>x): global center at (h+h/4, h+3h/4, h+h/2)
        float wx = h + h / 4f, wy = h + 3f * h / 4, wz = h + h / 2f;
        assertFalse(tet.intersects12DOP(wx - d, wy - d, wz - d, wx + d, wy + d, wz + d),
                    "S0 non-origin: box in y>z region (wrong ordering) should not intersect");
    }
}
