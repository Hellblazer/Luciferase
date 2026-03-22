// SPDX-License-Identifier: AGPL-3.0
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link Tet#contains12DOP} covers every point in a cube with no gaps.
 *
 * <p>Boundary semantics: {@code contains12DOP} uses {@code >=} (closed simplex), matching
 * {@code containsUltraFast}. Consequently:
 * <ul>
 *   <li>Strict interior (all 3 local coords distinct): exactly 1 type claims it</li>
 *   <li>Face boundary (exactly 2 local coords equal): exactly 2 types claim it</li>
 *   <li>Main diagonal (u == v == w): all 6 types claim it</li>
 *   <li>AABB corners V0 and V7: all 6 types claim them</li>
 * </ul>
 */
class Tet12DOPPartitioningTest {

    private static final int[] TEST_LEVELS = { 5, 10, 15 };
    private static final int   GRID_STEPS  = 32;

    /** Build one Tet of the given type anchored at the origin for the given level. */
    private static Tet tet(byte level, byte type) {
        return new Tet(0, 0, 0, level, type);
    }

    /** Count how many of the 6 S-types contain the point at (px, py, pz). */
    private static int countContaining(byte level, float px, float py, float pz) {
        int count = 0;
        for (byte t = 0; t < 6; t++) {
            if (tet(level, t).contains12DOP(px, py, pz)) {
                count++;
            }
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // Grid sweep: no gaps, interior uniqueness
    // -----------------------------------------------------------------------

    @Test
    void noGapsAcrossCube() {
        for (int level : TEST_LEVELS) {
            int h = Constants.lengthAtLevel((byte) level);
            // step = h / (2 * GRID_STEPS) keeps us off exact multiples of (h / GRID_STEPS)
            float step = (float) h / (2.0f * GRID_STEPS);

            for (int i = 0; i < 2 * GRID_STEPS; i++) {
                float px = i * step;
                for (int j = 0; j < 2 * GRID_STEPS; j++) {
                    float py = j * step;
                    for (int k = 0; k < 2 * GRID_STEPS; k++) {
                        float pz = k * step;

                        int count = countContaining((byte) level, px, py, pz);
                        assertTrue(count >= 1,
                                   String.format("GAP at level=%d (%.3f, %.3f, %.3f): 0 types claim it",
                                                 level, px, py, pz));
                    }
                }
            }
        }
    }

    @Test
    void strictInteriorContainedByExactlyOneType() {
        for (int level : TEST_LEVELS) {
            int h = Constants.lengthAtLevel((byte) level);
            // Use step = h/7, h/11, h/13 — odd-ish fractions that avoid coord equalities
            float[] offsets = { h / 7.0f, h / 11.0f, h / 13.0f };

            for (float dx : offsets) {
                for (float dy : offsets) {
                    for (float dz : offsets) {
                        if (dx == dy || dy == dz || dx == dz) {
                            continue; // skip any accidental equalities
                        }
                        float px = dx, py = dy, pz = dz;
                        int count = countContaining((byte) level, px, py, pz);
                        assertEquals(1, count,
                                     String.format("Interior point at level=%d (%.4f, %.4f, %.4f) claimed by %d types",
                                                   level, px, py, pz, count));
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Boundary diagonals: face (2 coords equal) => exactly 2 types
    // -----------------------------------------------------------------------

    @Test
    void faceBoundaryDiagonalUEqualsV() {
        // u == v, w distinct: face shared by S0 & S1 (u>=v>=w and v>=u>=w saturate to u==v)
        for (int level : TEST_LEVELS) {
            int h = Constants.lengthAtLevel((byte) level);
            float val = h / 3.0f;   // u == v
            float w   = h / 7.0f;   // w < val, ensuring strict inequality w != val

            float px = val, py = val, pz = w;
            int count = countContaining((byte) level, px, py, pz);
            assertEquals(2, count,
                         String.format("u==v face at level=%d (%.4f,%.4f,%.4f) claimed by %d types (expected 2)",
                                       level, px, py, pz, count));
        }
    }

    @Test
    void faceBoundaryDiagonalVEqualsW() {
        // v == w, u distinct
        for (int level : TEST_LEVELS) {
            int h = Constants.lengthAtLevel((byte) level);
            float val = h / 3.0f;
            float u   = h * 2.0f / 3.0f; // u > val

            float px = u, py = val, pz = val;
            int count = countContaining((byte) level, px, py, pz);
            assertEquals(2, count,
                         String.format("v==w face at level=%d (%.4f,%.4f,%.4f) claimed by %d types (expected 2)",
                                       level, px, py, pz, count));
        }
    }

    @Test
    void faceBoundaryDiagonalUEqualsW() {
        // u == w, v distinct
        for (int level : TEST_LEVELS) {
            int h = Constants.lengthAtLevel((byte) level);
            float val = h / 3.0f;
            float v   = h / 7.0f; // v < val

            float px = val, py = v, pz = val;
            int count = countContaining((byte) level, px, py, pz);
            assertEquals(2, count,
                         String.format("u==w face at level=%d (%.4f,%.4f,%.4f) claimed by %d types (expected 2)",
                                       level, px, py, pz, count));
        }
    }

    // -----------------------------------------------------------------------
    // Main diagonal (u == v == w): all 6 types
    // -----------------------------------------------------------------------

    @Test
    void mainDiagonalClaimedByAllSixTypes() {
        for (int level : TEST_LEVELS) {
            int h = Constants.lengthAtLevel((byte) level);
            // Test several points along the main diagonal
            float[] fractions = { 0.0f, h / 4.0f, h / 2.0f, h * 3.0f / 4.0f, (float) h };
            for (float val : fractions) {
                int count = countContaining((byte) level, val, val, val);
                assertEquals(6, count,
                             String.format("Main diagonal at level=%d (%.2f,%.2f,%.2f) claimed by %d types (expected 6)",
                                           level, val, val, val, count));
            }
        }
    }

    // -----------------------------------------------------------------------
    // AABB corners V0 and V7
    // -----------------------------------------------------------------------

    @Test
    void v0OriginClaimedByAllSixTypes() {
        for (int level : TEST_LEVELS) {
            int count = countContaining((byte) level, 0.0f, 0.0f, 0.0f);
            assertEquals(6, count,
                         String.format("V0 origin at level=%d claimed by %d types (expected 6)", level, count));
        }
    }

    @Test
    void v7OppositeCornerClaimedByAllSixTypes() {
        for (int level : TEST_LEVELS) {
            int h = Constants.lengthAtLevel((byte) level);
            int count = countContaining((byte) level, (float) h, (float) h, (float) h);
            assertEquals(6, count,
                         String.format("V7 corner at level=%d (h=%d) claimed by %d types (expected 6)", level, h, count));
        }
    }

    // -----------------------------------------------------------------------
    // Points strictly outside the AABB must never be claimed
    // -----------------------------------------------------------------------

    @Test
    void pointsOutsideAabbNotClaimed() {
        for (int level : TEST_LEVELS) {
            int h = Constants.lengthAtLevel((byte) level);
            float outside = h + 1.0f;

            assertEquals(0, countContaining((byte) level, outside, h / 2.0f, h / 2.0f),
                         "Point beyond +X face should not be claimed");
            assertEquals(0, countContaining((byte) level, -1.0f, h / 2.0f, h / 2.0f),
                         "Point beyond -X face should not be claimed");
            assertEquals(0, countContaining((byte) level, h / 2.0f, outside, h / 2.0f),
                         "Point beyond +Y face should not be claimed");
            assertEquals(0, countContaining((byte) level, h / 2.0f, h / 2.0f, outside),
                         "Point beyond +Z face should not be claimed");
        }
    }
}
