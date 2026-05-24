/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.prism;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collision-freeness tests for {@link Triangle#consecutiveIndex()}.
 *
 * <p>The original implementation used {@code 1L << (level * 2)} as the
 * per-field scale, which doubled bit consumption and overflowed signed long
 * at level 11+, producing key collisions across distinct triangles. The
 * fix in PR #86 corrected the scale to {@code 1L << level}. These tests
 * pin the corrected encoding so a regression to {@code level * 2} (or any
 * other scaling mistake) is detected mechanically.
 */
class TriangleTest {

    @Test
    @DisplayName("consecutiveIndex is collision-free across levels 1..6 (exhaustive)")
    void testConsecutiveIndexCollisionFreeLowLevels() {
        // Exhaustive sweep at low levels — cheap and complete. The level
        // cap is 6 because exhaustive at level 10 would be 2 * 1024^3 = 2B
        // combinations, too slow for unit tests. Levels 7..21 are covered
        // by the sampled test below.
        for (int level = 1; level <= 6; level++) {
            var seen = new HashSet<Long>();
            int maxCoord = 1 << level;
            for (int type = 0; type < 2; type++) {
                for (int x = 0; x < maxCoord; x++) {
                    for (int y = 0; y < maxCoord; y++) {
                        for (int n = 0; n < maxCoord; n++) {
                            long key = new Triangle(level, type, x, y, n).consecutiveIndex();
                            boolean added = seen.add(key);
                            assertTrue(added, String.format(
                                "Triangle.consecutiveIndex collision at level=%d type=%d x=%d y=%d n=%d (key=%d)",
                                level, type, x, y, n, key));
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("consecutiveIndex is collision-free at levels 7..MAX_LEVEL (sampled)")
    void testConsecutiveIndexCollisionFreeAtHighLevels() {
        // Regression test for the level*2 overflow. Levels 7..MAX_LEVEL with
        // a sparse sample — exhaustive would be O(2^(3*L)) which is intractable
        // at L=21. The sample covers corners + interior representatives, which
        // is sufficient to catch multiplicative-overflow collisions because
        // overflow folds high values into low ones (any collision-producing
        // wrap manifests across this coord set).
        for (int level = 7; level <= Triangle.MAX_LEVEL; level++) {
            var seen = new HashSet<Long>();
            int maxCoord = 1 << level;
            int[] coords = { 0, 1, maxCoord / 3, maxCoord / 2, maxCoord - 2, maxCoord - 1 };
            for (int type = 0; type < 2; type++) {
                for (int x : coords) {
                    for (int y : coords) {
                        for (int n : coords) {
                            long key = new Triangle(level, type, x, y, n).consecutiveIndex();
                            boolean added = seen.add(key);
                            assertTrue(added, String.format(
                                "Triangle.consecutiveIndex collision at level=%d type=%d x=%d y=%d n=%d (key=%d)",
                                level, type, x, y, n, key));
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("consecutiveIndex root (level=0) is 0")
    void testRootIndexZero() {
        // Anchor for the SFC: root triangle always indexes to 0. Used by
        // PrismKey.createRoot and asserted by PrismKeyTest.testCompositeSFC.
        assertEquals(0L, new Triangle(0, 0, 0, 0, 0).consecutiveIndex());
    }
}
