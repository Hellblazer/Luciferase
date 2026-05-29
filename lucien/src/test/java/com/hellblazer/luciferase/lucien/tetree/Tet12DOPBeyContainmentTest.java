// SPDX-License-Identifier: AGPL-3.0
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1.1 / B3.1 / B3.2 — Characterize and validate Bey child containment under contains12DOP().
 *
 * <p>Before the B2.1 fix, BeySubdivision used {@code subdivisionCoordinates()} (t8code ei/ej convention)
 * to compute midpoints, while {@code contains12DOP()} uses {@code coordinates()} (S0-S5 Kuhn geometry).
 * The vertex sets differ for 5 of 6 types, causing containment failures.</p>
 *
 * <p>After the fix both methods use {@code coordinates()}, ensuring that any point located inside a child
 * via {@code contains12DOP()} is also inside the parent.</p>
 *
 * <p>B3.1 invariant: for every child c produced by BeySubdivision.getBeyChild(parent, i),
 * any point P satisfying c.contains12DOP(P) also satisfies parent.contains12DOP(P).
 * We verify this using the canonical S-type centroid of each child's contains12DOP region.</p>
 */
public class Tet12DOPBeyContainmentTest {

    // Level 5: cell size = 1 << (21-5) = 65536
    private static final byte LEVEL_5  = 5;
    // Level 10: cell size = 1 << (21-10) = 2048
    private static final byte LEVEL_10 = 10;

    /**
     * Compute the centroid of the contains12DOP region for a given tet.
     * This is the center of the S-type simplex: average of its 4 canonical Kuhn vertices.
     * The result is guaranteed to satisfy child.contains12DOP(centroid).
     */
    private static float[] canonicalCentroid(Tet t) {
        // Labeling-agnostic: the centroid of the 4 actual tet vertices is strictly
        // inside the (closed) simplex, so it always satisfies contains12DOP.
        var v = t.coordinates();
        float cx = 0, cy = 0, cz = 0;
        for (var p : v) {
            cx += p.x;
            cy += p.y;
            cz += p.z;
        }
        return new float[]{ cx / 4f, cy / 4f, cz / 4f };
    }

    /**
     * B3.1: For all 6 parent types × 8 Bey children at levels 5 and 10,
     * verify that the canonical centroid of each child's contains12DOP region
     * is also contained by the parent via contains12DOP().
     *
     * This validates the core invariant: child ⊆ parent under the 12-DOP containment geometry.
     */
    @Test
    void allBeyChildRegionCentroidsContainedByParent() {
        List<String> failures = new ArrayList<>();

        for (byte level : new byte[]{ LEVEL_5, LEVEL_10 }) {
            int cellSize = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel(level);

            for (byte parentType = 0; parentType < 6; parentType++) {
                // Anchor chosen to be a valid grid point at this level.
                int anchorX = cellSize;
                int anchorY = 2 * cellSize;
                int anchorZ = 3 * cellSize;

                Tet parent = new Tet(anchorX, anchorY, anchorZ, level, parentType);

                for (int beyIdx = 0; beyIdx < 8; beyIdx++) {
                    Tet child = BeySubdivision.getBeyChild(parent, beyIdx);

                    // Compute the canonical centroid of child's contains12DOP region
                    float[] c = canonicalCentroid(child);
                    float cx = c[0], cy = c[1], cz = c[2];

                    // The centroid must be inside the child
                    assertTrue(child.contains12DOP(cx, cy, cz),
                        String.format("level=%d parentType=%d beyChild=%d: centroid not in child (sanity check failed)",
                            level, parentType, beyIdx));

                    // And must be inside the parent
                    boolean contained = parent.contains12DOP(cx, cy, cz);
                    if (!contained) {
                        failures.add(String.format(
                            "level=%d parentType=%d beyChild=%d centroid=(%.1f,%.1f,%.1f) NOT in parent",
                            level, parentType, beyIdx, cx, cy, cz));
                    }
                }
            }
        }

        assertTrue(failures.isEmpty(),
            "Child region centroid containment failures:\n" + String.join("\n", failures));
    }

    /**
     * B3.2: 1000 random points located via {@code locatePointBeyRefinementFromRoot} to level 15
     * must: (a) return a tet at the TARGET level, and (b) satisfy tet.contains12DOP(point).
     *
     * Condition (a) verifies there are no coverage gaps (fallback to ancestor would give level < 15).
     * Condition (b) verifies containment correctness.
     */
    @Test
    void randomPointsLocatedToLevel15AreContained() {
        final int N = 1000;
        final byte TARGET_LEVEL = 15;
        final long SEED = 0xDEADBEEFL;

        Random rng = new Random(SEED);
        int maxCoord = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel((byte) 0);
        float margin = 1.0f;
        float range = maxCoord - 2 * margin;

        List<String> failures = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            float px = margin + rng.nextFloat() * range;
            float py = margin + rng.nextFloat() * range;
            float pz = margin + rng.nextFloat() * range;

            Tet tet = Tet.locatePointBeyRefinementFromRoot(px, py, pz, TARGET_LEVEL);

            assertNotNull(tet,
                String.format("locatePointBeyRefinementFromRoot returned null for (%.2f,%.2f,%.2f)", px, py, pz));

            if (tet.l() != TARGET_LEVEL) {
                failures.add(String.format(
                    "point=(%.4f,%.4f,%.4f) located to level=%d (expected %d) — coverage gap",
                    px, py, pz, tet.l(), TARGET_LEVEL));
            } else if (!tet.contains12DOP(px, py, pz)) {
                failures.add(String.format(
                    "point=(%.4f,%.4f,%.4f) located to tet(anchor=%d,%d,%d level=%d type=%d) but NOT contained",
                    px, py, pz, tet.x(), tet.y(), tet.z(), tet.l(), tet.type()));
            }
        }

        assertTrue(failures.isEmpty(),
            N + " random points at level " + TARGET_LEVEL + " — failures:\n"
            + String.join("\n", failures));
    }
}
