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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-3avp: the MortonKey hierarchy primitives ({@code parent}, {@code getChild},
 * {@code firstDescendantAtLevel}, {@code lastDescendantAtLevel}) must agree with the ABSOLUTE Morton
 * convention used by {@link MortonKey#fromCoordinates} / {@link Constants#calculateMortonIndex} /
 * {@link MortonKey#neighbor} and by every key STORED in the spatial index. A level-{@code L} cell's
 * absolute code has its low {@code 3*(maxLevel-L)} bits zero (octant digits sit in the high bits).
 *
 * <p>The previous implementations used the level-relative convention ({@code >>3} / {@code <<3}, octant
 * digits in the LOW bits), which produced codes that do NOT match the absolute keys the index stores at
 * any level &lt; maxLevel. These tests pin the absolute contract: {@code parent()} of a cell must equal
 * the same point quantized one level up, etc.
 *
 * @author hal.hildebrand
 */
class MortonKeyHierarchyConventionTest {

    private static final int MAX = MortonCurve.MAX_REFINEMENT_LEVEL;

    @Test
    @DisplayName("parent() equals the absolute ancestor cell at every level")
    void parentMatchesAbsoluteAncestor() {
        // Several points spread across the coordinate space.
        int[][] points = { { 32768, 32768, 32768 }, { 100000, 250000, 7000 }, { 1, 1, 1 }, { 2, 2, 2 },
                           { 524288, 131072, 917504 } };
        for (int[] p : points) {
            for (byte level = 1; level <= MAX; level++) {
                var cell = MortonKey.fromCoordinates(p[0], p[1], p[2], level);
                var expectedParent = MortonKey.fromCoordinates(p[0], p[1], p[2], (byte) (level - 1));
                assertEquals(expectedParent, cell.parent(),
                             "parent() must equal the absolute ancestor at level " + (level - 1) + " for point ("
                             + p[0] + "," + p[1] + "," + p[2] + ")");
            }
        }
    }

    @Test
    @DisplayName("getChild(i) is the absolute child cell whose origin lies inside the parent")
    void getChildMatchesAbsoluteChild() {
        // For each octant, getChild(i) must equal fromCoordinates(childOrigin, level+1) and parent back.
        for (byte level = 0; level < MAX; level++) {
            int cellSize = Constants.lengthAtLevel((byte) (level + 1)); // child cell size
            // A parent origin aligned to the parent cell so children are exact.
            int parentSize = Constants.lengthAtLevel(level);
            int ox = 4 * parentSize % (Constants.MAX_COORD + 1);
            var parent = MortonKey.fromCoordinates(ox, ox, ox, level);
            for (int i = 0; i < 8; i++) {
                var child = parent.getChild(i);
                assertNotNull(child);
                assertEquals(level + 1, child.getLevel());
                // The child's decoded origin must round-trip back to the parent.
                assertEquals(parent, child.parent(), "getChild(" + i + ").parent() must return the parent at level "
                                                     + level);
                // The child origin must be inside the parent cell [origin, origin+parentSize).
                int[] c = MortonCurve.decode(child.getMortonCode());
                assertTrue(c[0] >= ox && c[0] < ox + parentSize && c[1] >= ox && c[1] < ox + parentSize
                           && c[2] >= ox && c[2] < ox + parentSize,
                           "child " + i + " origin " + c[0] + "," + c[1] + "," + c[2] + " must lie within the parent "
                           + "cell at level " + level);
                // And the child must be a valid stored key: fromCoordinates at its origin/level reproduces it.
                assertEquals(MortonKey.fromCoordinates(c[0], c[1], c[2], (byte) (level + 1)), child,
                             "getChild must produce an absolute key matching fromCoordinates");
            }
        }
    }

    @Test
    @DisplayName("firstDescendantAtLevel is the min-corner (same origin) deeper cell")
    void firstDescendantIsMinCorner() {
        var cell = MortonKey.fromCoordinates(100000, 250000, 7000, (byte) 6);
        int[] origin = MortonCurve.decode(cell.getMortonCode());
        for (byte target = 6; target <= MAX; target++) {
            var first = cell.firstDescendantAtLevel(target);
            assertEquals(target, first.getLevel());
            // The first (SFC-smallest) descendant shares the parent's origin corner.
            assertEquals(MortonKey.fromCoordinates(origin[0], origin[1], origin[2], target), first,
                         "firstDescendantAtLevel(" + target + ") must be the min-corner absolute cell");
        }
    }

    @Test
    @DisplayName("lastDescendantAtLevel is the max-corner deeper cell")
    void lastDescendantIsMaxCorner() {
        byte level = 6;
        var cell = MortonKey.fromCoordinates(100000, 250000, 7000, level);
        int[] origin = MortonCurve.decode(cell.getMortonCode());
        int parentSize = Constants.lengthAtLevel(level);
        for (byte target = level; target <= MAX; target++) {
            var last = cell.lastDescendantAtLevel(target);
            assertEquals(target, last.getLevel());
            int childSize = Constants.lengthAtLevel(target);
            // Max-corner child origin = parentOrigin + parentSize - childSize in each axis.
            int mx = origin[0] + parentSize - childSize;
            int my = origin[1] + parentSize - childSize;
            int mz = origin[2] + parentSize - childSize;
            assertEquals(MortonKey.fromCoordinates(mx, my, mz, target), last,
                         "lastDescendantAtLevel(" + target + ") must be the max-corner absolute cell");
        }
    }

    @Test
    @DisplayName("first/last descendant bracket the absolute SFC code range of the subtree")
    void descendantBracketsSubtreeRange() {
        // Every absolute key whose ancestor at `level` is `cell` must fall within
        // [first.code, last.code] at the target level — the property owner-range pruning depends on.
        byte level = 4;
        byte target = 7;
        var cell = MortonKey.fromCoordinates(100000, 250000, 7000, level);
        long lo = cell.firstDescendantAtLevel(target).getMortonCode();
        long hi = cell.lastDescendantAtLevel(target).getMortonCode();
        assertTrue(Long.compareUnsigned(lo, hi) <= 0, "first must not exceed last");

        int parentSize = Constants.lengthAtLevel(level);
        int childSize = Constants.lengthAtLevel(target);
        int[] o = MortonCurve.decode(cell.getMortonCode());
        for (int dx = 0; dx < parentSize; dx += childSize) {
            for (int dy = 0; dy < parentSize; dy += childSize) {
                for (int dz = 0; dz < parentSize; dz += childSize) {
                    long code = MortonKey.fromCoordinates(o[0] + dx, o[1] + dy, o[2] + dz, target).getMortonCode();
                    assertTrue(Long.compareUnsigned(lo, code) <= 0 && Long.compareUnsigned(code, hi) <= 0,
                               "descendant code must lie within [first,last]");
                }
            }
        }
    }
}
