// SPDX-License-Identifier: AGPL-3.0
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD test for Tet.contains12DOP() — the 11-op ordering-based containment test for S0-S5 Kuhn tetrahedra.
 * <p>
 * Orderings derived from vertex geometry (Kuhn simplex edge paths in coordinates()):
 * <ul>
 *   <li>S0: V0,V1,V3,V7 → x ≥ y ≥ z</li>
 *   <li>S1: V0,V2,V3,V7 → y ≥ x ≥ z</li>
 *   <li>S2: V0,V4,V5,V7 → z ≥ x ≥ y</li>
 *   <li>S3: V0,V4,V6,V7 → z ≥ y ≥ x</li>
 *   <li>S4: V0,V1,V5,V7 → x ≥ z ≥ y</li>
 *   <li>S5: V0,V2,V6,V7 → y ≥ z ≥ x</li>
 * </ul>
 * Tests verify: (1) interior points per S-type, (2) cross-type exclusion for strict interiors,
 * (3) consistency with containsUltraFast, (4) gap-free coverage.
 */
public class Tet12DOPContainmentTest {

    // Level 10, h = 1 << (21 - 10) = 2048, anchor = (0,0,0)
    private static final byte LEVEL = 10;
    private static final int H = 1 << (21 - LEVEL); // 2048

    /**
     * Interior points strictly inside each S-type region. All three local coords are distinct,
     * so these are in the open interior of exactly one type.
     */
    @ParameterizedTest(name = "S{0}: interior point ({1},{2},{3})")
    @CsvSource({
        "0, 1500, 1000, 500",  // S0: u > v > w  (x > y > z)
        "1, 1000, 1500, 500",  // S1: v > u > w  (y > x > z)
        "2, 700, 500, 1500",   // S2: w > u > v  (z > x > y)
        "3, 500, 700, 1500",   // S3: w > v > u  (z > y > x)
        "4, 1500, 500, 1000",  // S4: u > w > v  (x > z > y)
        "5, 500, 1500, 1000",  // S5: v > w > u  (y > z > x)
    })
    void interiorPointContained(int type, float px, float py, float pz) {
        var tet = new Tet(0, 0, 0, LEVEL, (byte) type);
        assertTrue(tet.contains12DOP(px, py, pz),
                   "S%d should contain interior point (%.0f,%.0f,%.0f)".formatted(type, px, py, pz));
    }

    /**
     * Each strict-interior point should be rejected by all OTHER S-types (no overlap in open interiors).
     */
    @ParameterizedTest(name = "S{0}: interior point rejected by other types")
    @CsvSource({
        "0, 1500, 1000, 500",
        "1, 1000, 1500, 500",
        "2, 700, 500, 1500",
        "3, 500, 700, 1500",
        "4, 1500, 500, 1000",
        "5, 500, 1500, 1000",
    })
    void interiorPointExcludedByOtherTypes(int ownerType, float px, float py, float pz) {
        for (int otherType = 0; otherType < 6; otherType++) {
            if (otherType == ownerType) continue;
            var tet = new Tet(0, 0, 0, LEVEL, (byte) otherType);
            assertFalse(tet.contains12DOP(px, py, pz),
                        "S%d should NOT contain S%d's interior point (%.0f,%.0f,%.0f)".formatted(
                            otherType, ownerType, px, py, pz));
        }
    }

    /**
     * Points outside the AABB [0, 2048]^3 should be rejected by all types.
     */
    @Test
    void pointsOutsideAABBRejected() {
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, LEVEL, type);
            assertFalse(tet.contains12DOP(-1, 500, 500), "Below x min");
            assertFalse(tet.contains12DOP(500, -1, 500), "Below y min");
            assertFalse(tet.contains12DOP(500, 500, -1), "Below z min");
            assertFalse(tet.contains12DOP(H + 1, 500, 500), "Above x max");
            assertFalse(tet.contains12DOP(500, H + 1, 500), "Above y max");
            assertFalse(tet.contains12DOP(500, 500, H + 1), "Above z max");
        }
    }

    /**
     * Non-origin anchor: tet at (2048, 0, 0) level 10 (grid-aligned).
     */
    @Test
    void nonOriginAnchor() {
        int ax = H, ay = 0, az = 0;
        // S0 interior: u > v > w → local (1500,1000,500) → global (3548, 1000, 500)
        var tet = new Tet(ax, ay, az, LEVEL, (byte) 0);
        assertTrue(tet.contains12DOP(ax + 1500, ay + 1000, az + 500));
        assertFalse(tet.contains12DOP(ax - 1, ay + 1000, az + 500), "Outside AABB");
    }

    /**
     * Boundary points (two equal local coords) are in multiple types with the closed-simplex (>=) convention.
     * This matches containsUltraFast behavior. Verify they are in at least one type and at most two.
     */
    @Test
    void boundaryPointsInMultipleTypes() {
        // u == v, w < u: local (1000, 1000, 500) — shared face between S0 (u≥v≥w) and S1 (v≥u≥w)
        assertContains12DOPBetween(1000, 1000, 500, 1, 3);

        // u == w, v < u: local (1000, 500, 1000) — shared face between S2 (w≥u≥v) and S4 (u≥w≥v)
        assertContains12DOPBetween(1000, 500, 1000, 1, 3);

        // v == w, u < v: local (500, 1000, 1000) — shared face between S3 (w≥v≥u) and S5 (v≥w≥u)
        assertContains12DOPBetween(500, 1000, 1000, 1, 3);

        // Triple equality: u == v == w → in all 6 types
        assertContains12DOPBetween(1000, 1000, 1000, 6, 6);
    }

    /**
     * contains12DOP must agree with containsUltraFast for a grid of points across all S-types.
     */
    @Test
    void consistencyWithContainsUltraFast() {
        int step = H / 8; // 256
        int mismatches = 0;
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, LEVEL, type);
            for (int ix = step / 2; ix < H; ix += step) {
                for (int iy = step / 2; iy < H; iy += step) {
                    for (int iz = step / 2; iz < H; iz += step) {
                        float px = ix, py = iy, pz = iz;
                        boolean ultra = tet.containsUltraFast(px, py, pz);
                        boolean dop = tet.contains12DOP(px, py, pz);
                        if (ultra != dop) {
                            mismatches++;
                        }
                    }
                }
            }
        }
        assertEquals(0, mismatches, "contains12DOP must match containsUltraFast everywhere");
    }

    /**
     * Every interior point of the cube must be contained by at least one S-type (gap-free).
     * With closed-simplex (>=), boundary points may be in multiple types — that's expected.
     */
    @Test
    void gapFreeCoverage() {
        int step = H / 16; // 128
        int uncovered = 0;
        for (int ix = step / 2; ix < H; ix += step) {
            for (int iy = step / 2; iy < H; iy += step) {
                for (int iz = step / 2; iz < H; iz += step) {
                    float px = ix, py = iy, pz = iz;
                    boolean found = false;
                    for (byte type = 0; type < 6; type++) {
                        var tet = new Tet(0, 0, 0, LEVEL, type);
                        if (tet.contains12DOP(px, py, pz)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) uncovered++;
                }
            }
        }
        assertEquals(0, uncovered, "Every cube interior point must be in at least one S-type");
    }

    // -- helpers --

    private void assertContains12DOPBetween(float px, float py, float pz, int minTypes, int maxTypes) {
        int count = 0;
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, LEVEL, type);
            if (tet.contains12DOP(px, py, pz)) count++;
        }
        assertTrue(count >= minTypes && count <= maxTypes,
                   "Point (%.0f,%.0f,%.0f) should be in %d-%d S-types, found %d".formatted(
                       px, py, pz, minTypes, maxTypes, count));
    }
}
