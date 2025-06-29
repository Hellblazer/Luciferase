/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for TetreeBits utility class
 *
 * @author hal.hildebrand
 */
class TetreeBitsTest {

    @Test
    void testLowestCommonAncestorLevel_AllAxesDifferent() {
        // Test where all three coordinates differ
        Tet tet1 = new Tet(10, 20, 30, (byte) 5, (byte) 2);
        Tet tet2 = new Tet(11, 21, 31, (byte) 5, (byte) 3);

        byte ncaLevel = TetreeBits.lowestCommonAncestorLevel(tet1, tet2);
        assertTrue(ncaLevel <= 5);

        // All coordinates differ by 1, which affects bit 0
        // So the NCA should be at a level that masks out these differences
    }

    @Test
    void testLowestCommonAncestorLevel_DifferentCoordinates() {
        // Test with coordinates that differ at a specific bit position
        // Binary: 4 = 100, 5 = 101 (differ at bit 0)
        Tet tet1 = new Tet(4, 4, 4, (byte) 3, (byte) 0);
        Tet tet2 = new Tet(5, 4, 4, (byte) 3, (byte) 0);

        byte ncaLevel = TetreeBits.lowestCommonAncestorLevel(tet1, tet2);
        assertTrue(ncaLevel <= 3);

        // The NCA should be at a level where the differing bit is masked out
        // Since they differ at bit 0, and max level is 21, the NCA should be at level 21-1=20
        // But limited by min(3,3) = 3, so result should be 3
    }

    @Test
    void testLowestCommonAncestorLevel_LargeDifference() {
        // Test with coordinates that have a large difference
        Tet tet1 = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        Tet tet2 = new Tet(Integer.MAX_VALUE >> 11, 0, 0, (byte) 10, (byte) 0);

        byte ncaLevel = TetreeBits.lowestCommonAncestorLevel(tet1, tet2);
        assertTrue(ncaLevel < 10, "Large coordinate differences should result in low NCA level");
    }

    @Test
    void testLowestCommonAncestorLevel_RootLevel() {
        // Test with tetrahedra at different levels where NCA is root
        Tet tet1 = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        Tet tet2 = new Tet(Constants.lengthAtLevel((byte) 1), 0, 0, (byte) 1, (byte) 0);

        byte ncaLevel = TetreeBits.lowestCommonAncestorLevel(tet1, tet2);
        assertEquals(0, ncaLevel, "Tetrahedra in different level-1 cells should have root as NCA");
    }

    @Test
    void testLowestCommonAncestorLevel_SameCoordinates() {
        // Two tetrahedra at same coordinates should have NCA at minimum level
        Tet tet1 = new Tet(100, 100, 100, (byte) 5, (byte) 0);
        Tet tet2 = new Tet(100, 100, 100, (byte) 7, (byte) 2);

        byte ncaLevel = TetreeBits.lowestCommonAncestorLevel(tet1, tet2);
        assertEquals(5, ncaLevel); // min(5, 7)
    }

    @Test
    void testLowestCommonAncestorLevel_TypeDifference() {
        // Test case where tetrahedra have different types at the cube level
        // This tests the t8code parity fix we just implemented

        // Create two tetrahedra that will have different types at their initial common level
        // Using different types should trigger the while loop that decreases the level
        Tet tet1 = new Tet(0, 0, 0, (byte) 5, (byte) 0);
        Tet tet2 = new Tet(0, 0, 0, (byte) 5, (byte) 3);

        byte ncaLevel = TetreeBits.lowestCommonAncestorLevel(tet1, tet2);

        // Since they have same coordinates but different types, the initial c_level is 5
        // But if their ancestor types at level 5 are different, it should decrease
        assertEquals(5, ncaLevel); // They're at same position, so NCA is at their level

        // More complex case with actual coordinate differences
        int cellSize = Constants.lengthAtLevel((byte) 8);
        Tet tet3 = new Tet(cellSize * 2, cellSize * 2, cellSize * 2, (byte) 8, (byte) 1);
        Tet tet4 = new Tet(cellSize * 2 + 1, cellSize * 2, cellSize * 2, (byte) 8, (byte) 4);

        byte ncaLevel2 = TetreeBits.lowestCommonAncestorLevel(tet3, tet4);
        assertTrue(ncaLevel2 <= 8, "NCA level should be at most the minimum input level");
    }

    @Test
    void testLowestCommonAncestorLevel_VerifyTypeCheck() {
        // This test specifically verifies that the type checking logic works
        // by creating a scenario where types differ at the initial computed level

        // We need to create tetrahedra where computeType returns different values
        // at the initial c_level but same values at a lower level

        // Using coordinates that put them in adjacent cells
        Tet tet1 = new Tet(7, 0, 0, (byte) 4, (byte) 0);
        Tet tet2 = new Tet(8, 0, 0, (byte) 4, (byte) 1);

        byte ncaLevel = TetreeBits.lowestCommonAncestorLevel(tet1, tet2);

        // Verify that both tetrahedra have the same type at the NCA level
        byte tet1TypeAtNCA = tet1.computeType(ncaLevel);
        byte tet2TypeAtNCA = tet2.computeType(ncaLevel);

        assertEquals(tet1TypeAtNCA, tet2TypeAtNCA,
                     "Tetrahedra should have same type at their lowest common ancestor level");

        // The NCA level should be valid (not negative)
        assertTrue(ncaLevel >= 0, "NCA level should be non-negative");
        assertTrue(ncaLevel <= Math.min(tet1.l(), tet2.l()),
                   "NCA level should not exceed minimum of the two input levels");
    }
}
