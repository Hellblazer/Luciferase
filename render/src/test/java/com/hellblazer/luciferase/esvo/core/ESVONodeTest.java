/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ESVONode child indexing correctness, in particular
 * octant-mirrored child lookup (getChildNodeIndexWithOctant).
 *
 * CUDA reference algorithm (Laine & Karras 2010):
 *   childShift  = childIdx ^ octantMask
 *   childMasks  = childDescriptor << childShift
 *   exists      = (childMasks & 0x8000) != 0
 *   actualSlot  = 7 ^ childShift         (child slot in the unmirrored childMask)
 *   rank        = popcount(childMask & ((1 << actualSlot) - 1))
 *   index       = childPointer + rank
 *
 * The bug (Luciferase-7wzml.152): bitsBeforeChild used the unshifted
 * childIdx instead of actualSlot = 7 ^ childShift, so the rank and the
 * existence test disagreed about which child slot was being addressed.
 *
 * For a traversal idx and octantMask, this method is equivalent to
 * getChildNodeIndex(idx ^ octantMask ^ 7).
 */
class ESVONodeTest {

    // childDescriptor layout:
    //   bits  0- 7: leafmask
    //   bits  8-15: childmask  <- sparse indexing lives here
    //   bit  16:    far bit
    //   bits 17-30: childptr (14 bits)
    private static int makeDescriptor(int childPtr, int childMask, int leafMask) {
        return (childPtr << 17) | ((childMask & 0xFF) << 8) | (leafMask & 0xFF);
    }

    // -----------------------------------------------------------------------
    // Core algorithm: existence and rank both use actualSlot = 7 ^ (idx ^ octantMask)
    // -----------------------------------------------------------------------

    /**
     * Only child at actualSlot 0 (childMask=0b00000001).
     * actualSlot = 7 ^ (childIdx ^ octantMask) = 0  =>  childIdx ^ octantMask = 7.
     * With octantMask=0: childIdx=7, childShift=7, actualSlot=0.
     * rank = popcount(0b00000001 & 0) = 0 => index = childPtr = 10.
     */
    @Test
    void singleChildAtSlot0_accessedViaIdx7_octantMask0() {
        ESVONode node = new ESVONode(makeDescriptor(10, 0b00000001, 0), 0);
        // actualSlot=0: exists, rank=0 => 10
        assertEquals(10, node.getChildNodeIndexWithOctant(7, 0));
        // actualSlot=7^(0^0)=7: bit 7 not set => -1
        assertEquals(-1, node.getChildNodeIndexWithOctant(0, 0));
    }

    /**
     * Only child at actualSlot 7 (childMask=0b10000000).
     * actualSlot = 7 ^ (childIdx ^ octantMask) = 7  =>  childIdx ^ octantMask = 0.
     * With octantMask=0: childIdx=0, childShift=0, actualSlot=7.
     * rank = popcount(0b10000000 & 0b01111111) = popcount(0) = 0 => 5.
     */
    @Test
    void singleChildAtSlot7_accessedViaIdx0_octantMask0() {
        ESVONode node = new ESVONode(makeDescriptor(5, 0b10000000, 0), 0);
        assertEquals(5, node.getChildNodeIndexWithOctant(0, 0));
        assertEquals(-1, node.getChildNodeIndexWithOctant(7, 0));
    }

    /**
     * All 8 children present, childPtr=0, octantMask=0.
     * actualSlot = 7 ^ childIdx.
     * rank of slot k = k (all bits below k set in 0xFF).
     * So getChildNodeIndexWithOctant(idx, 0) == 7 ^ idx.
     */
    @Test
    void allChildren_octantMask0_rankIsActualSlot() {
        ESVONode node = new ESVONode(makeDescriptor(0, 0xFF, 0), 0);
        for (int idx = 0; idx < 8; idx++) {
            int actualSlot = 7 ^ idx;
            // rank = popcount(0xFF & ((1 << actualSlot) - 1)) = actualSlot
            assertEquals(actualSlot, node.getChildNodeIndexWithOctant(idx, 0),
                "idx=" + idx + " => actualSlot=" + actualSlot + " => rank=" + actualSlot);
        }
    }

    /**
     * All 8 children present, childPtr=0, octantMask=7.
     * actualSlot = 7 ^ (idx ^ 7) = idx.
     * rank = idx.
     * So getChildNodeIndexWithOctant(idx, 7) == idx.
     */
    @Test
    void allChildren_octantMask7_rankIsIdx() {
        ESVONode node = new ESVONode(makeDescriptor(0, 0xFF, 0), 0);
        for (int idx = 0; idx < 8; idx++) {
            assertEquals(idx, node.getChildNodeIndexWithOctant(idx, 7),
                "idx=" + idx + " octantMask=7 => rank=" + idx);
        }
    }

    /**
     * Single child at slot 6 (bit 6 of childMask), childPtr=20, octantMask=5.
     * We need actualSlot = 6 => 7 ^ (childIdx ^ 5) = 6 => childIdx ^ 5 = 1 => childIdx = 4.
     * rank = popcount(childMask & ((1<<6)-1)) = popcount(0b01000000 & 0b00111111) = 0 => 20.
     */
    @Test
    void singleChildAtSlot6_octantMask5() {
        ESVONode node = new ESVONode(makeDescriptor(20, 1 << 6, 0), 0);
        // childIdx=4, octantMask=5: childShift=1, actualSlot=7^1=6 => exists, rank=0 => 20
        assertEquals(20, node.getChildNodeIndexWithOctant(4, 5),
            "slot 6 via childIdx=4 octantMask=5 should give pointer base 20");
        // childIdx=3, octantMask=5: childShift=6, actualSlot=7^6=1 => bit 1 not set => -1
        assertEquals(-1, node.getChildNodeIndexWithOctant(3, 5),
            "slot 1 absent => -1");
    }

    /**
     * Sparse childMask = 0b10100101 (slots 0,2,5,7 set), childPtr=3, octantMask=1.
     * For each traversal idx, actualSlot = 7 ^ (idx ^ 1):
     *   idx=0 => actualSlot=7^1=6  absent => -1
     *   idx=1 => actualSlot=7^0=7  present; rank=popcount(mask&0x7F)=3 => 6
     *   idx=2 => actualSlot=7^3=4  absent => -1
     *   idx=3 => actualSlot=7^2=5  present; rank=popcount(mask&0x1F)=2 => 5
     *   idx=4 => actualSlot=7^5=2  present; rank=popcount(mask&0x03)=1 => 4
     *   idx=5 => actualSlot=7^4=3  absent => -1
     *   idx=6 => actualSlot=7^7=0  present; rank=popcount(mask&0x00)=0 => 3
     *   idx=7 => actualSlot=7^6=1  absent => -1
     */
    @Test
    void sparseChildMask_octantMask1_perSlotCorrectness() {
        int childMask = 0b10100101; // slots 0,2,5,7 set
        ESVONode node = new ESVONode(makeDescriptor(3, childMask, 0), 0);
        int octantMask = 1;

        assertEquals(-1, node.getChildNodeIndexWithOctant(0, octantMask), "slot 6 absent");
        assertEquals(6,  node.getChildNodeIndexWithOctant(1, octantMask), "slot 7 rank=3 => 6");
        assertEquals(-1, node.getChildNodeIndexWithOctant(2, octantMask), "slot 4 absent");
        assertEquals(5,  node.getChildNodeIndexWithOctant(3, octantMask), "slot 5 rank=2 => 5");
        assertEquals(4,  node.getChildNodeIndexWithOctant(4, octantMask), "slot 2 rank=1 => 4");
        assertEquals(-1, node.getChildNodeIndexWithOctant(5, octantMask), "slot 3 absent");
        assertEquals(3,  node.getChildNodeIndexWithOctant(6, octantMask), "slot 0 rank=0 => 3");
        assertEquals(-1, node.getChildNodeIndexWithOctant(7, octantMask), "slot 1 absent");
    }

    /**
     * Parity: getChildNodeIndexWithOctant(idx, mask) must equal
     * getChildNodeIndex(idx ^ mask ^ 7) for all idx and mask.
     * This is the traversal equivalence from ESVOTraversal.java line 184:
     *   actualChild = childShift ^ 7 = (idx ^ octantMask) ^ 7
     */
    @Test
    void parityWithGetChildNodeIndex_allMasks() {
        int childMask = 0b01101011; // children at slots 0,1,3,5,6
        ESVONode node = new ESVONode(makeDescriptor(7, childMask, 0), 0);

        for (int octantMask = 0; octantMask < 8; octantMask++) {
            for (int idx = 0; idx < 8; idx++) {
                int expected = node.getChildNodeIndex(idx ^ octantMask ^ 7);
                int actual = node.getChildNodeIndexWithOctant(idx, octantMask);
                assertEquals(expected, actual,
                    "Parity failed: getChildNodeIndexWithOctant(" + idx + "," + octantMask
                    + ") should equal getChildNodeIndex(" + (idx ^ octantMask ^ 7) + ")");
            }
        }
    }
}
