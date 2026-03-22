// SPDX-License-Identifier: AGPL-3.0
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness test: validates {@link Tet#contains12DOP(float, float, float)} against
 * {@link Tet#containsUltraFast(float, float, float)} via exhaustive random sampling.
 *
 * <p>For every S-type (0-5), across five representative levels, 100 000 random points
 * are drawn uniformly from the tetrahedron's bounding AABB (plus a small margin) and
 * both methods must agree on every single point.  Boundary points — on face-sharing
 * planes (u==v, v==w, u==w) and on AABB faces — are also injected explicitly.</p>
 *
 * <p>Seeded {@link Random#Random(long) Random(42)} ensures reproducibility.</p>
 */
public class Tet12DOPCorrectnessTest {

    /** Levels used for sampling: coarse → fine. */
    private static final byte[] LEVELS = { 5, 8, 10, 15, 18 };

    /** Random points per S-type per level. */
    private static final int SAMPLES_PER_TYPE_PER_LEVEL = 100_000;

    // -----------------------------------------------------------------------
    // Main random-sampling test
    // -----------------------------------------------------------------------

    @Test
    void contains12DOPMatchesContainsUltraFastRandomSampling() {
        var rng = new Random(42);
        int totalDisagreements = 0;
        int totalChecked = 0;

        for (byte level : LEVELS) {
            int h = Constants.lengthAtLevel(level);
            // Anchor at a grid-aligned position that leaves room in the positive octant
            int anchor = h * 3; // arbitrary multiple — well inside the addressable space

            for (int type = 0; type <= 5; type++) {
                var tet = new Tet(anchor, anchor, anchor, level, (byte) type);

                // --- random interior/exterior points ---
                for (int i = 0; i < SAMPLES_PER_TYPE_PER_LEVEL; i++) {
                    // Sample uniformly from a box slightly larger than the AABB so we
                    // also test points that are outside the tetrahedron.
                    float range = h * 1.2f;
                    float px = anchor - h * 0.1f + rng.nextFloat() * range;
                    float py = anchor - h * 0.1f + rng.nextFloat() * range;
                    float pz = anchor - h * 0.1f + rng.nextFloat() * range;

                    boolean ultraFast = tet.containsUltraFast(px, py, pz);
                    boolean dop12    = tet.contains12DOP(px, py, pz);

                    if (ultraFast != dop12) {
                        totalDisagreements++;
                        // Emit details for the first few mismatches to aid diagnosis
                        if (totalDisagreements <= 10) {
                            System.err.printf(
                                "MISMATCH level=%d type=%d h=%d  p=(%.4f,%.4f,%.4f)  ultraFast=%b  12DOP=%b%n",
                                level, type, h, px, py, pz, ultraFast, dop12);
                        }
                    }
                    totalChecked++;
                }

                // --- boundary points: face-sharing planes u==v, v==w, u==w ---
                float[] fracs = { 0.0f, 0.25f, 0.5f, 0.75f, 1.0f };
                for (float a : fracs) {
                    for (float b : fracs) {
                        float av = a * h;
                        float bv = b * h;

                        // u == v plane: px - anchor == py - anchor, i.e. px == py
                        totalDisagreements += checkPoint(tet, anchor + av, anchor + av, anchor + bv, totalDisagreements);
                        totalChecked++;

                        // v == w plane: py == pz
                        totalDisagreements += checkPoint(tet, anchor + bv, anchor + av, anchor + av, totalDisagreements);
                        totalChecked++;

                        // u == w plane: px == pz
                        totalDisagreements += checkPoint(tet, anchor + av, anchor + bv, anchor + av, totalDisagreements);
                        totalChecked++;
                    }
                }

                // --- AABB boundary points ---
                for (float t : fracs) {
                    float tv = t * h;
                    // Face px == anchor
                    totalDisagreements += checkPoint(tet, anchor,       anchor + tv,   anchor + tv,   totalDisagreements); totalChecked++;
                    // Face px == anchor + h
                    totalDisagreements += checkPoint(tet, anchor + h,   anchor + tv,   anchor + tv,   totalDisagreements); totalChecked++;
                    // Face py == anchor
                    totalDisagreements += checkPoint(tet, anchor + tv,  anchor,        anchor + tv,   totalDisagreements); totalChecked++;
                    // Face py == anchor + h
                    totalDisagreements += checkPoint(tet, anchor + tv,  anchor + h,    anchor + tv,   totalDisagreements); totalChecked++;
                    // Face pz == anchor
                    totalDisagreements += checkPoint(tet, anchor + tv,  anchor + tv,   anchor,        totalDisagreements); totalChecked++;
                    // Face pz == anchor + h
                    totalDisagreements += checkPoint(tet, anchor + tv,  anchor + tv,   anchor + h,    totalDisagreements); totalChecked++;
                }
            }
        }

        assertEquals(0, totalDisagreements,
                     "contains12DOP disagreed with containsUltraFast on %d / %d points"
                     .formatted(totalDisagreements, totalChecked));
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Returns 1 if the two methods disagree (and prints a diagnostic for early mismatches),
     * 0 if they agree.
     */
    private static int checkPoint(Tet tet, float px, float py, float pz, int priorDisagreements) {
        boolean ultraFast = tet.containsUltraFast(px, py, pz);
        boolean dop12    = tet.contains12DOP(px, py, pz);
        if (ultraFast != dop12) {
            if (priorDisagreements < 10) {
                System.err.printf(
                    "MISMATCH level=%d type=%d  p=(%.4f,%.4f,%.4f)  ultraFast=%b  12DOP=%b%n",
                    tet.l, tet.type, px, py, pz, ultraFast, dop12);
            }
            return 1;
        }
        return 0;
    }
}
