/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.sfc;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-lield: validates the canonical BIGMIN bit-manipulation against a brute-force oracle, and shows the
 * above-query case no longer linearly scans the whole Morton range.
 *
 * @author hal.hildebrand
 */
class BigminOracleTest {

    /** Brute-force: smallest Morton code in [start, maxMorton] whose decoded point is inside the box, else -1. */
    private static long oracleNextInRange(long start, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                          long maxMorton) {
        for (long m = start; m <= maxMorton; m++) {
            var c = MortonCurve.decode(m);
            if (c[0] >= minX && c[0] <= maxX && c[1] >= minY && c[1] <= maxY && c[2] >= minZ && c[2] <= maxZ) {
                return m;
            }
        }
        return -1;
    }

    @Test
    void findNextInRangeMatchesBruteForceOracle() {
        var rnd = new Random(424242L); // seeded — deterministic
        int span = 8;                  // coords 0..7 -> morton 0..511, brute force is cheap
        long maxMorton = MortonCurve.encode(span - 1, span - 1, span - 1);

        for (int iter = 0; iter < 4000; iter++) {
            int x0 = rnd.nextInt(span), x1 = x0 + rnd.nextInt(span - x0);
            int y0 = rnd.nextInt(span), y1 = y0 + rnd.nextInt(span - y0);
            int z0 = rnd.nextInt(span), z1 = z0 + rnd.nextInt(span - z0);
            long start = rnd.nextInt((int) maxMorton + 1);

            long expected = oracleNextInRange(start, x0, y0, z0, x1, y1, z1, maxMorton);
            long actual = LitmaxBigmin.findNextInRange(start, x0, y0, z0, x1, y1, z1, maxMorton);

            assertEquals(expected, actual,
                         "findNextInRange disagrees with brute force at iter " + iter + " start=" + start
                         + " box=[" + x0 + "," + y0 + "," + z0 + "]..[" + x1 + "," + y1 + "," + z1 + "]");
        }
    }

    @Test
    void rangeQueryIsSubLinearOnLargeSparseSpace() {
        // Luciferase-lield performance contract: a small query box high in a full 21-level Morton space. The Morton
        // range spanned is astronomically large (~10^11 between start and the box), so the old current+1 scan would
        // take ~10^11 iterations. Canonical BIGMIN must converge to the first in-box code in O(tree-depth*dims)
        // jumps. We replicate findNextInRange's loop here to COUNT the bigmin jumps and bound them.
        int lvl = MortonCurve.MAX_REFINEMENT_LEVEL; // 21 -> coords up to 2^21
        int base = (1 << lvl) - 4;                  // box near the top corner: [base..base+1]^3
        int minX = base, minY = base, minZ = base, maxX = base + 1, maxY = base + 1, maxZ = base + 1;
        long maxMorton = MortonCurve.encode(maxX, maxY, maxZ);
        long boxFirst = MortonCurve.encode(minX, minY, minZ);

        long current = 0;
        int jumps = 0;
        long found = -1;
        while (current <= maxMorton) {
            var c = MortonCurve.decode(current);
            if (c[0] >= minX && c[0] <= maxX && c[1] >= minY && c[1] <= maxY && c[2] >= minZ && c[2] <= maxZ) {
                found = current;
                break;
            }
            current = LitmaxBigmin.bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);
            jumps++;
            assertTrue(jumps < 5000, "BIGMIN must converge in O(depth*dims) jumps, not O(morton-range); jumps=" + jumps);
        }
        assertEquals(boxFirst, found, "must locate the first in-box Morton code");
        // The Morton range from 0 to the box is enormous; the jump count must be a tiny fraction of it.
        assertTrue(jumps < 200, "expected far fewer than 200 jumps over a ~10^11 Morton range, got " + jumps);
    }

    @Test
    void aboveQueryBigminJumpsNotScans() {
        // A point far above the box on every axis: BIGMIN must NOT return current+1 (the old O(range) scan), it must
        // jump (here: past the box entirely -> Long.MAX_VALUE, since no in-box code is greater).
        int minX = 0, minY = 0, minZ = 0, maxX = 2, maxY = 2, maxZ = 2;
        long current = MortonCurve.encode(20, 20, 20); // well above the box
        long next = LitmaxBigmin.bigmin(current, minX, minY, minZ, maxX, maxY, maxZ);
        assertTrue(next != current + 1, "above-query BIGMIN must jump, not increment by one (Luciferase-lield)");
        assertTrue(next > current, "BIGMIN must make forward progress");
    }
}
