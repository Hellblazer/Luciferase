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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for TetreeKey range merging functionality.
 *
 * @author hal.hildebrand
 */
class TetreeKeyRangeMergingTest {

    @Test
    void testIsAdjacentTo() {
        // Test adjacent keys at the same level
        var key1 = new CompactTetreeKey((byte) 5, 100L);
        var key2 = new CompactTetreeKey((byte) 5, 101L);
        var key3 = new CompactTetreeKey((byte) 5, 102L);
        var key4 = new CompactTetreeKey((byte) 6, 101L); // Different level

        assertTrue(key1.isAdjacentTo(key2), "Keys with consecutive indices should be adjacent");
        assertTrue(key2.isAdjacentTo(key1), "Adjacency should be symmetric");
        assertTrue(key2.isAdjacentTo(key3), "Keys with consecutive indices should be adjacent");
        
        assertFalse(key1.isAdjacentTo(key3), "Keys with gap should not be adjacent");
        assertFalse(key1.isAdjacentTo(key4), "Keys at different levels should not be adjacent");
        assertFalse(key1.isAdjacentTo(null), "Null key should not be adjacent");
    }

    @Test
    void testIsAdjacentToAtBoundary() {
        // Test adjacency at the boundary between low and high bits
        // This would occur at very deep levels (>10)
        var key1 = new ExtendedTetreeKey((byte) 11, 0xFFFFFFFFFFFFFFFFL, 0L);
        var key2 = new ExtendedTetreeKey((byte) 11, 0L, 1L);
        
        assertTrue(key1.isAdjacentTo(key2), "Keys at bit boundary should be adjacent");
        assertTrue(key2.isAdjacentTo(key1), "Adjacency at boundary should be symmetric");
    }

    @Test
    void testCanMergeWith() {
        var key1 = new CompactTetreeKey((byte) 5, 100L);
        var key2 = new CompactTetreeKey((byte) 5, 101L);
        var key3 = new CompactTetreeKey((byte) 5, 103L);
        
        assertTrue(key1.canMergeWith(key2), "Adjacent keys should be mergeable");
        assertFalse(key1.canMergeWith(key3), "Non-adjacent keys should not be mergeable");
        assertTrue(key1.canMergeWith(key1), "Key should be mergeable with itself");
    }

    @Test
    void testMax() {
        var key1 = new CompactTetreeKey((byte) 5, 100L);
        var key2 = new CompactTetreeKey((byte) 5, 200L);
        var key3 = new CompactTetreeKey((byte) 6, 100L);
        
        assertEquals(key2, key1.max(key2), "Should return larger key");
        assertEquals(key2, key2.max(key1), "Max should be commutative");
        assertEquals(key1, key1.max(key1), "Max with self should return self");
        assertEquals(key1, key1.max(null), "Max with null should return self");
        
        assertThrows(IllegalArgumentException.class, () -> key1.max(key3),
                     "Should throw for different levels");
    }

    @Test
    void testMaxWithExtendedKeys() {
        var key1 = new ExtendedTetreeKey((byte) 15, 100L, 1L);
        var key2 = new ExtendedTetreeKey((byte) 15, 200L, 1L);
        var key3 = new ExtendedTetreeKey((byte) 15, 100L, 2L);
        
        assertEquals(key2, key1.max(key2), "Should compare low bits when high bits equal");
        assertEquals(key3, key1.max(key3), "Should compare high bits first");
    }
}