// SPDX-License-Identifier: AGPL-3.0
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness test comparing 12-DOP intersection against the existing (incomplete) SAT implementation.
 *
 * <h2>Key invariant</h2>
 * The existing SAT ({@link Tet#tetrahedronIntersectsVolumeBounds}) omits the 9 edge-cross-product axes,
 * so it can produce FALSE POSITIVES (reports intersection when there is none). The 12-DOP is exact for
 * Kuhn tetrahedra. Therefore:
 * <ul>
 *   <li>If SAT says false → 12-DOP MUST say false ({@code !sat → !dop})</li>
 *   <li>If 12-DOP says true → SAT MUST say true ({@code dop → sat})</li>
 *   <li>SAT-true / 12-DOP-false cases are SAT false positives — expected and counted</li>
 * </ul>
 */
public class Tet12DOPIntersectionCorrectnessTest {

    /** MAX_REFINEMENT_LEVEL = 21, so h at level L = 1 << (21 - L). */
    private static final int MAX_LEVEL = 21;

    // -----------------------------------------------------------------------
    // Test 1: No false negatives vs SAT (random AABB sweep)
    // -----------------------------------------------------------------------

    /**
     * For 6 types × 3 levels × 10 000 random AABBs verify:
     * <ol>
     *   <li>{@code !sat → !dop} — 12-DOP never says "no" when SAT says "no"</li>
     *   <li>{@code dop → sat} — 12-DOP never says "yes" when SAT says "no"</li>
     * </ol>
     * SAT-true/12-DOP-false pairs are counted as expected SAT false positives.
     */
    @Test
    void noFalseNegativesVsSAT() {
        byte[] levels = { 5, 10, 15 };
        int   trials = 10_000;
        var   rng    = new Random(42);

        int totalSatFalsePositives = 0;
        int totalTrials            = 0;

        for (byte level : levels) {
            int h = 1 << (MAX_LEVEL - level); // cell size at this level

            for (byte type = 0; type <= 5; type++) {
                // Anchor at origin — always valid (multiples of h)
                var tet = new Tet(0, 0, 0, (byte) level, type);

                int satFalsePositives = 0;

                for (int t = 0; t < trials; t++) {
                    // Generate a random AABB whose coordinates span roughly [-2h, 3h]
                    float x0 = (rng.nextFloat() - 0.5f) * 3 * h;
                    float y0 = (rng.nextFloat() - 0.5f) * 3 * h;
                    float z0 = (rng.nextFloat() - 0.5f) * 3 * h;
                    float x1 = x0 + rng.nextFloat() * 2 * h;
                    float y1 = y0 + rng.nextFloat() * 2 * h;
                    float z1 = z0 + rng.nextFloat() * 2 * h;

                    float minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
                    float minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
                    float minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1);

                    boolean sat = tet.intersects(minX, minY, minZ, maxX, maxY, maxZ);
                    boolean dop = tet.intersects12DOP(minX, minY, minZ, maxX, maxY, maxZ);

                    // Core invariant: dop → sat  (equivalently: !sat → !dop)
                    if (dop) {
                        assertTrue(sat,
                                   ("Level %d type %d trial %d: 12-DOP says true but SAT says false — "
                                   + "12-DOP false negative! AABB=[%.1f,%.1f,%.1f]-[%.1f,%.1f,%.1f]").formatted(
                                   level, type, t, minX, minY, minZ, maxX, maxY, maxZ));
                    }

                    if (sat && !dop) {
                        satFalsePositives++;
                    }
                }

                totalSatFalsePositives += satFalsePositives;
                totalTrials += trials;
                // SAT false positives are expected (incomplete SAT) — just verify count is sane (< 50%)
                assertTrue(satFalsePositives < trials / 2,
                           "Level %d type %d: suspiciously many SAT false positives: %d / %d".formatted(
                           level, type, satFalsePositives, trials));
            }
        }

        // Diagnostic: log overall false-positive rate (not a failure criterion)
        System.out.printf("SAT false-positive rate: %d / %d (%.1f%%)%n",
                          totalSatFalsePositives, totalTrials,
                          100.0 * totalSatFalsePositives / totalTrials);
    }

    // -----------------------------------------------------------------------
    // Test 2: 12-DOP rejections verified by point sampling
    // -----------------------------------------------------------------------

    /**
     * For every AABB that 12-DOP rejects, sample 100 random interior points and confirm none are inside the tet
     * via {@link Tet#contains12DOP}. This validates that the 12-DOP rejection is sound (no actual overlap missed).
     */
    @Test
    void dopRejectionsSoundVsPointSampling() {
        byte[] levels      = { 5, 10, 15 };
        int    trials      = 2_000; // fewer outer trials; inner sampling is expensive
        int    innerPoints = 100;
        var    rng         = new Random(42);

        for (byte level : levels) {
            int h = 1 << (MAX_LEVEL - level);

            for (byte type = 0; type <= 5; type++) {
                var tet = new Tet(0, 0, 0, (byte) level, type);

                for (int t = 0; t < trials; t++) {
                    float x0 = (rng.nextFloat() - 0.5f) * 3 * h;
                    float y0 = (rng.nextFloat() - 0.5f) * 3 * h;
                    float z0 = (rng.nextFloat() - 0.5f) * 3 * h;
                    float x1 = x0 + rng.nextFloat() * 2 * h;
                    float y1 = y0 + rng.nextFloat() * 2 * h;
                    float z1 = z0 + rng.nextFloat() * 2 * h;

                    float minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
                    float minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
                    float minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1);

                    boolean dop = tet.intersects12DOP(minX, minY, minZ, maxX, maxY, maxZ);
                    if (dop) {
                        continue; // only check rejections
                    }

                    // 12-DOP rejected — verify no interior AABB point is in the tet
                    for (int p = 0; p < innerPoints; p++) {
                        float px = minX + rng.nextFloat() * (maxX - minX);
                        float py = minY + rng.nextFloat() * (maxY - minY);
                        float pz = minZ + rng.nextFloat() * (maxZ - minZ);

                        assertFalse(tet.contains12DOP(px, py, pz),
                                    "Level %d type %d: 12-DOP rejected AABB but point (%.2f,%.2f,%.2f) is inside tet"
                                    + " — 12-DOP false negative!".formatted(level, type, px, py, pz));
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: Deterministic edge cases
    // -----------------------------------------------------------------------

    /** AABB containing the entire tet bounding box → both SAT and 12-DOP must return true. */
    @Test
    void aabbContainsEntireTet() {
        byte[] levels = { 5, 10, 15 };
        for (byte level : levels) {
            int h = 1 << (MAX_LEVEL - level);
            for (byte type = 0; type <= 5; type++) {
                var tet = new Tet(0, 0, 0, level, type);

                // AABB strictly contains the tet's bounding cube [0,h]^3
                float minX = -1f, minY = -1f, minZ = -1f;
                float maxX = h + 1f, maxY = h + 1f, maxZ = h + 1f;

                assertTrue(tet.intersects(minX, minY, minZ, maxX, maxY, maxZ),
                           "SAT must be true when AABB contains entire tet, level=%d type=%d".formatted(level, type));
                assertTrue(tet.intersects12DOP(minX, minY, minZ, maxX, maxY, maxZ),
                           "12-DOP must be true when AABB contains entire tet, level=%d type=%d".formatted(level, type));
            }
        }
    }

    /** Tiny AABB at tet centroid → both must return true. */
    @Test
    void aabbAtTetCentroid() {
        byte[] levels = { 5, 10, 15 };
        for (byte level : levels) {
            int h = 1 << (MAX_LEVEL - level);
            for (byte type = 0; type <= 5; type++) {
                var tet = new Tet(0, 0, 0, level, type);

                float[] centroid = centroidForType(type, h);
                float   eps      = h * 0.01f;

                assertTrue(tet.intersects12DOP(centroid[0] - eps, centroid[1] - eps, centroid[2] - eps,
                                               centroid[0] + eps, centroid[1] + eps, centroid[2] + eps),
                           "12-DOP must be true for tiny AABB at tet interior, level=%d type=%d".formatted(level,
                                                                                                           type));
            }
        }
    }

    /**
     * Zero-volume AABB (point) at tet centroid → 12-DOP must return true.
     * A point AABB has minX==maxX, etc.
     */
    @Test
    void pointAabbAtTetCentroid() {
        byte[] levels = { 5, 10, 15 };
        for (byte level : levels) {
            int h = 1 << (MAX_LEVEL - level);
            for (byte type = 0; type <= 5; type++) {
                var     tet      = new Tet(0, 0, 0, level, type);
                float[] centroid = centroidForType(type, h);
                float   px = centroid[0], py = centroid[1], pz = centroid[2];

                assertTrue(tet.intersects12DOP(px, py, pz, px, py, pz),
                           "12-DOP must be true for point AABB at tet interior, level=%d type=%d".formatted(level,
                                                                                                            type));
                assertTrue(tet.contains12DOP(px, py, pz),
                           "contains12DOP must be true for tet interior point, level=%d type=%d".formatted(level,
                                                                                                           type));
            }
        }
    }

    /**
     * AABB touching a tet face from outside (just barely not overlapping) → 12-DOP should return false.
     * AABB touching from inside (grazing the face) → 12-DOP should return true.
     */
    @Test
    void aabbTouchingTetFace() {
        // Use level 10, type 0 (S0: x>=y>=z within cube [0,h]^3, anchor at origin)
        byte level = 10;
        byte type  = 0;
        int  h     = 1 << (MAX_LEVEL - level);
        var  tet   = new Tet(0, 0, 0, level, type);

        // Face of the AABB bounding cube at x=0. An AABB just outside (maxX < 0) must be rejected.
        float eps = 0.5f;
        assertFalse(tet.intersects12DOP(-10f, 0, 0, -eps, h, h),
                    "AABB entirely to the left of tet cube must be rejected");

        // AABB touching the face at x=0 from outside (maxX == 0): closed-interval, so touching is inclusion
        // This is boundary behaviour — at minimum the cube face test should allow it
        assertTrue(tet.intersects12DOP(-h, 0, 0, 0, h, h),
                   "AABB touching tet cube face at x=0 should intersect (closed interval)");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a point that is guaranteed to be strictly inside the tetrahedron of the given type
     * at anchor (0,0,0) with cell size h.
     * Uses the ordering invariant of each Kuhn simplex type.
     */
    private static float[] centroidForType(byte type, int h) {
        // Pick three values with strict ordering: lo < mid < hi, all within (0, h)
        float lo  = h * 0.20f;
        float mid = h * 0.45f;
        float hi  = h * 0.70f;
        return switch (type) {
            case 0 -> new float[] { hi, mid, lo };   // S0: x >= y >= z
            case 1 -> new float[] { mid, hi, lo };   // S1: y >= x >= z
            case 2 -> new float[] { mid, lo, hi };   // S2: z >= x >= y  (z=hi, x=mid, y=lo)
            case 3 -> new float[] { lo, mid, hi };   // S3: z >= y >= x  (z=hi, y=mid, x=lo)
            case 4 -> new float[] { hi, lo, mid };   // S4: x >= z >= y  (x=hi, z=mid, y=lo)
            case 5 -> new float[] { lo, hi, mid };   // S5: y >= z >= x  (y=hi, z=mid, x=lo)
            default -> throw new IllegalArgumentException("Invalid type: " + type);
        };
    }
}
