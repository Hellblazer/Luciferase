/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code MortonKey.firstDescendantAtLevel}/{@code lastDescendantAtLevel} (Luciferase-3uwx S1) — the
 * SFC-subrange bounds used by t8code owner-range pruning.
 *
 * @author hal.hildebrand
 */
class MortonKeyDescendantTest {

    @Test
    void firstAndLastDescendant_boundTheSubtreeSfcRange() {
        var node = new MortonKey(0b101L, (byte) 1); // arbitrary level-1 node

        byte target = 4;
        var first = node.firstDescendantAtLevel(target);
        var last = node.lastDescendantAtLevel(target);

        assertEquals(target, first.getLevel());
        assertEquals(target, last.getLevel());
        // 3 levels deeper => code shifted left by 9 bits; first appends zeros, last appends all-ones (0x1FF).
        assertEquals(0b101L << 9, first.getMortonCode());
        assertEquals((0b101L << 9) | 0x1FFL, last.getMortonCode());
        // The subrange is non-empty and ordered.
        assertTrue(first.getMortonCode() <= last.getMortonCode());
    }

    @Test
    void descendantAtOwnLevel_isIdentity() {
        var node = new MortonKey(0b110L, (byte) 2);
        assertEquals(node, node.firstDescendantAtLevel((byte) 2));
        assertEquals(node, node.lastDescendantAtLevel((byte) 2));
    }

    @Test
    void firstDescendant_isLeftmostChildChain() {
        var node = new MortonKey(0b1L, (byte) 3);
        // Repeatedly taking child 0 must equal firstDescendantAtLevel.
        var chained = node;
        for (int l = 3; l < 6; l++) {
            chained = chained.getChild(0);
        }
        assertEquals(chained, node.firstDescendantAtLevel((byte) 6));
    }

    @Test
    void lastDescendant_isRightmostChildChain() {
        var node = new MortonKey(0b1L, (byte) 3);
        var chained = node;
        for (int l = 3; l < 6; l++) {
            chained = chained.getChild(7);
        }
        assertEquals(chained, node.lastDescendantAtLevel((byte) 6));
    }

    @Test
    void rootSubrangeSpansWholeCurveAtMaxLevel() {
        var root = new MortonKey(0L, (byte) 0);
        byte max = Constants.getMaxRefinementLevel();
        assertEquals(0L, root.firstDescendantAtLevel(max).getMortonCode());
        // Last descendant of the root at max level is all-ones across 3*max bits.
        assertEquals((1L << (3 * max)) - 1L, root.lastDescendantAtLevel(max).getMortonCode());
    }

    @Test
    void targetLevelAboveOwnLevel_throws() {
        var node = new MortonKey(0b1L, (byte) 5);
        assertThrows(IllegalArgumentException.class, () -> node.firstDescendantAtLevel((byte) 4));
        assertThrows(IllegalArgumentException.class, () -> node.lastDescendantAtLevel((byte) 4));
    }
}
