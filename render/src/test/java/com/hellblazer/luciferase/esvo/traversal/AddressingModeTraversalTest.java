/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvo.traversal;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test pointer addressing mode calculations for both RELATIVE (SVO) and ABSOLUTE (DAG).
 *
 * <p>Validates that the correct child index is computed based on addressing mode:
 * <ul>
 * <li>RELATIVE: childIdx = parentIdx + childPtr + sparseIdx</li>
 * <li>ABSOLUTE: childIdx = childPtr + sparseIdx</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@DisplayName("Addressing Mode Traversal Tests")
class AddressingModeTraversalTest {

    /**
     * Test RELATIVE addressing (SVO mode).
     *
     * <p>Example:
     * <pre>
     * parentIdx = 10
     * childPtr = 5
     * childMask = 0b11110000 (children 4,5,6,7 exist)
     * octant = 5
     * sparseIdx = bitCount(0b00010000) = 1
     * childIdx = 10 + 5 + 1 = 16
     * </pre>
     */
    @Test
    @DisplayName("RELATIVE addressing computes: parentIdx + childPtr + sparseIdx")
    void testRelativeAddressing() {
        // Create parent node with children at octants 4,5,6,7
        var node = new ESVONodeUnified();
        node.setChildMask(0b11110000);  // children 4,5,6,7 exist
        node.setChildPtr(5);  // relative offset to first child

        int parentIdx = 10;
        int octant = 5;

        // Expected: sparseIdx = bitCount(0b00010000) = 1
        // childIdx = parentIdx(10) + childPtr(5) + sparseIdx(1) = 16
        int expectedChildIdx = 16;

        // Simulate RELATIVE addressing calculation
        int sparseIdx = Integer.bitCount(node.getChildMask() & ((1 << octant) - 1));
        int actualChildIdx = parentIdx + node.getChildPtr() + sparseIdx;

        assertEquals(expectedChildIdx, actualChildIdx,
            "RELATIVE addressing should compute parentIdx + childPtr + sparseIdx");
        assertEquals(1, sparseIdx, "Sparse index should be 1 for octant 5 with mask 0b11110000");
    }

    /**
     * Test ABSOLUTE addressing (DAG mode).
     *
     * <p>Example:
     * <pre>
     * parentIdx = 10 (IGNORED)
     * childPtr = 100 (absolute index to first child)
     * childMask = 0b11110000 (children 4,5,6,7 exist)
     * octant = 5
     * sparseIdx = bitCount(0b00010000) = 1
     * childIdx = 100 + 1 = 101
     * </pre>
     */
    @Test
    @DisplayName("ABSOLUTE addressing computes: childPtr + sparseIdx (ignores parentIdx)")
    void testAbsoluteAddressing() {
        // Create parent node with children at octants 4,5,6,7
        var node = new ESVONodeUnified();
        node.setChildMask(0b11110000);  // children 4,5,6,7 exist
        node.setChildPtr(100);  // absolute index to first child

        int parentIdx = 10;  // IGNORED in ABSOLUTE mode
        int octant = 5;

        // Expected: sparseIdx = bitCount(0b00010000) = 1
        // childIdx = childPtr(100) + sparseIdx(1) = 101
        int expectedChildIdx = 101;

        // Simulate ABSOLUTE addressing calculation
        int sparseIdx = Integer.bitCount(node.getChildMask() & ((1 << octant) - 1));
        int actualChildIdx = node.getChildPtr() + sparseIdx;

        assertEquals(expectedChildIdx, actualChildIdx,
            "ABSOLUTE addressing should compute childPtr + sparseIdx (ignoring parentIdx)");
        assertEquals(1, sparseIdx, "Sparse index should be 1 for octant 5 with mask 0b11110000");
    }

    /**
     * Test sparse index calculation with all octants.
     *
     * <p>Validates that sparse index correctly counts children before target octant.
     */
    @Test
    @DisplayName("Sparse index calculation for all octants")
    void testSparseIndexCalculation() {
        // Create node with children at octants 0,2,4,6 (alternating)
        var node = new ESVONodeUnified();
        node.setChildMask(0b01010101);  // binary pattern for octants 0,2,4,6

        // Expected sparse indices for each octant:
        // octant 0: bitCount(0b00000000) = 0
        // octant 1: bitCount(0b00000001) = 1 (child at 0)
        // octant 2: bitCount(0b00000001) = 1
        // octant 3: bitCount(0b00000101) = 2 (children at 0,2)
        // octant 4: bitCount(0b00000101) = 2
        // octant 5: bitCount(0b00010101) = 3 (children at 0,2,4)
        // octant 6: bitCount(0b00010101) = 3
        // octant 7: bitCount(0b01010101) = 4 (children at 0,2,4,6)

        int[] expectedSparseIndices = {0, 1, 1, 2, 2, 3, 3, 4};

        for (int octant = 0; octant < 8; octant++) {
            int sparseIdx = Integer.bitCount(node.getChildMask() & ((1 << octant) - 1));
            assertEquals(expectedSparseIndices[octant], sparseIdx,
                String.format("Sparse index for octant %d should be %d",
                    octant, expectedSparseIndices[octant]));
        }
    }

    /**
     * Test RELATIVE vs ABSOLUTE modes produce different results.
     *
     * <p>Demonstrates that the same node with the same octant produces different
     * child indices depending on addressing mode.
     */
    @Test
    @DisplayName("RELATIVE vs ABSOLUTE modes produce different child indices")
    void testRelativeVsAbsoluteDifference() {
        var node = new ESVONodeUnified();
        node.setChildMask(0b11111111);  // all children exist
        node.setChildPtr(50);  // offset/absolute index

        int parentIdx = 10;
        int octant = 3;
        int sparseIdx = Integer.bitCount(node.getChildMask() & ((1 << octant) - 1));

        int relativeChildIdx = parentIdx + node.getChildPtr() + sparseIdx;
        int absoluteChildIdx = node.getChildPtr() + sparseIdx;

        assertNotEquals(relativeChildIdx, absoluteChildIdx,
            "RELATIVE and ABSOLUTE addressing should produce different indices");
        assertEquals(10 + 50 + 3, relativeChildIdx, "RELATIVE: parentIdx + childPtr + sparseIdx");
        assertEquals(50 + 3, absoluteChildIdx, "ABSOLUTE: childPtr + sparseIdx");
    }

    /**
     * Test edge case: single child at octant 0.
     */
    @Test
    @DisplayName("Edge case: single child at octant 0")
    void testSingleChildAtOctant0() {
        var node = new ESVONodeUnified();
        node.setChildMask(0b00000001);  // only child 0 exists
        node.setChildPtr(20);

        int parentIdx = 5;
        int octant = 0;
        int sparseIdx = Integer.bitCount(node.getChildMask() & ((1 << octant) - 1));

        assertEquals(0, sparseIdx, "Sparse index for octant 0 should always be 0");

        int relativeChildIdx = parentIdx + node.getChildPtr() + sparseIdx;
        int absoluteChildIdx = node.getChildPtr() + sparseIdx;

        assertEquals(25, relativeChildIdx, "RELATIVE: 5 + 20 + 0 = 25");
        assertEquals(20, absoluteChildIdx, "ABSOLUTE: 20 + 0 = 20");
    }

    /**
     * Test edge case: single child at octant 7.
     */
    @Test
    @DisplayName("Edge case: single child at octant 7")
    void testSingleChildAtOctant7() {
        var node = new ESVONodeUnified();
        node.setChildMask(0b10000000);  // only child 7 exists
        node.setChildPtr(15);

        int parentIdx = 8;
        int octant = 7;
        int sparseIdx = Integer.bitCount(node.getChildMask() & ((1 << octant) - 1));

        assertEquals(0, sparseIdx, "Sparse index for only child should be 0");

        int relativeChildIdx = parentIdx + node.getChildPtr() + sparseIdx;
        int absoluteChildIdx = node.getChildPtr() + sparseIdx;

        assertEquals(23, relativeChildIdx, "RELATIVE: 8 + 15 + 0 = 23");
        assertEquals(15, absoluteChildIdx, "ABSOLUTE: 15 + 0 = 15");
    }

    /**
     * Test full node (all 8 children).
     */
    @Test
    @DisplayName("Full node with all 8 children")
    void testFullNode() {
        var node = new ESVONodeUnified();
        node.setChildMask(0b11111111);  // all children exist
        node.setChildPtr(100);

        int parentIdx = 10;

        // Verify sparse indices for all octants
        for (int octant = 0; octant < 8; octant++) {
            int sparseIdx = Integer.bitCount(node.getChildMask() & ((1 << octant) - 1));
            assertEquals(octant, sparseIdx,
                "For full node, sparse index should equal octant index");

            int relativeChildIdx = parentIdx + node.getChildPtr() + sparseIdx;
            int absoluteChildIdx = node.getChildPtr() + sparseIdx;

            assertEquals(10 + 100 + octant, relativeChildIdx,
                String.format("RELATIVE for octant %d", octant));
            assertEquals(100 + octant, absoluteChildIdx,
                String.format("ABSOLUTE for octant %d", octant));
        }
    }
}
