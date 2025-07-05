/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the CompactTetreeKey implementation.
 */
public class CompactTetreeKeyTest {

    @Test
    void testCompactKeyCreation() {
        // Test creating compact keys at various levels
        CompactTetreeKey key0 = new CompactTetreeKey((byte) 0, 0L);
        assertEquals(0, key0.getLevel());
        assertEquals(0L, key0.getLowBits());
        assertEquals(0L, key0.getHighBits());

        CompactTetreeKey key5 = new CompactTetreeKey((byte) 5, 0x123456789AL);
        assertEquals(5, key5.getLevel());
        assertEquals(0x123456789AL, key5.getLowBits());
        assertEquals(0L, key5.getHighBits());

        CompactTetreeKey key10 = new CompactTetreeKey((byte) 10, 0xFFFFFFFFFFFFFFL);
        assertEquals(10, key10.getLevel());
        assertEquals(0xFFFFFFFFFFFFFFL, key10.getLowBits());
        assertEquals(0L, key10.getHighBits());
    }

    @Test
    void testCompactKeyLevelLimit() {
        // Should throw exception for level > 10
        assertThrows(IllegalArgumentException.class, () -> {
            new CompactTetreeKey((byte) 11, 0L);
        });
    }

    @Test
    void testComparisonAndEquality() {
        CompactTetreeKey key1 = new CompactTetreeKey((byte) 5, 0x12345L);
        CompactTetreeKey key2 = new CompactTetreeKey((byte) 5, 0x12345L);
        CompactTetreeKey key3 = new CompactTetreeKey((byte) 5, 0x12346L);
        CompactTetreeKey key4 = new CompactTetreeKey((byte) 6, 0x12345L);

        // Test equality
        assertEquals(key1, key2);
        assertNotEquals(key1, key3);
        assertNotEquals(key1, key4);

        // Test comparison
        assertEquals(0, key1.compareTo(key2));
        assertTrue(key1.compareTo(key3) < 0);
        assertTrue(key3.compareTo(key1) > 0);

        // Different levels - shallower comes first
        assertTrue(key1.compareTo(key4) < 0);
        assertTrue(key4.compareTo(key1) > 0);
    }

    @Test
    void testFactoryMethods() {
        // Test root creation
        TetreeKey<?> root = TetreeKey.getRoot();
        assertInstanceOf(CompactTetreeKey.class, root);
        assertEquals(0, root.getLevel());

        // Test factory create method
        TetreeKey<?> compact = TetreeKey.create((byte) 8, 0x12345L, 0L);
        assertInstanceOf(CompactTetreeKey.class, compact);

        TetreeKey<?> full = TetreeKey.create((byte) 15, 0x12345L, 0x67890L);
        assertInstanceOf(ExtendedTetreeKey.class, full);
    }

    @Test
    void testMemoryEfficiency() {
        // Verify that CompactTetreeKey uses less memory than ExtendedTetreeKey
        // This is more of a documentation test

        // CompactTetreeKey: 1 byte + 1 long = 9 bytes (plus object overhead)
        // ExtendedTetreeKey: 1 byte + 2 longs = 17 bytes (plus object overhead)

        // For level 10 key:
        CompactTetreeKey compact = new CompactTetreeKey((byte) 10, 0x123456789ABCDEFL);
        ExtendedTetreeKey full = new ExtendedTetreeKey((byte) 10, 0x123456789ABCDEFL, 0L);

        // Both represent the same key
        assertEquals(compact.getLevel(), full.getLevel());
        assertEquals(compact.getLowBits(), full.getLowBits());
        assertEquals(compact.getHighBits(), full.getHighBits());

        // But compact version uses ~47% less memory for the fields
    }

    @Test
    void testParentCalculation() {
        // Test parent calculation for compact keys
        long tmIndex = 0b111111_101010_001100_110011_010101L; // 5 levels of 6-bit groups
        CompactTetreeKey key5 = new CompactTetreeKey((byte) 5, tmIndex);

        // Parent at level 4 should remove the last 6 bits
        CompactTetreeKey parent = (CompactTetreeKey) key5.parent();
        assertNotNull(parent);
        assertEquals(4, parent.getLevel());
        assertEquals(tmIndex >>> 6, parent.getLowBits());

        // Continue up to root
        CompactTetreeKey grandparent = (CompactTetreeKey) parent.parent();
        assertEquals(3, grandparent.getLevel());
        assertEquals(tmIndex >>> 12, grandparent.getLowBits());

        // Root has no parent
        CompactTetreeKey root = new CompactTetreeKey((byte) 0, 0L);
        assertNull(root.parent());
    }

    @Test
    void testTypeAndCoordExtraction() {
        // Create a key with known bit pattern
        // Level 2: two 6-bit groups
        // Group 0: coords=101 (5), type=011 (3) -> 101011 = 43
        // Group 1: coords=110 (6), type=010 (2) -> 110010 = 50
        long tmIndex = (50L << 6) | 43L;
        CompactTetreeKey key = new CompactTetreeKey((byte) 2, tmIndex);

        // Extract type at level 0
        assertEquals(3, key.getTypeAtLevel(0));
        // Extract coords at level 0
        assertEquals(5, key.getCoordBitsAtLevel(0));

        // Extract type at level 1
        assertEquals(2, key.getTypeAtLevel(1));
        // Extract coords at level 1
        assertEquals(6, key.getCoordBitsAtLevel(1));
    }

    @Test
    void testValidation() {
        // Root is valid
        CompactTetreeKey root = new CompactTetreeKey((byte) 0, 0L);
        assertTrue(root.isValid());

        // Non-root with appropriate bits is valid
        CompactTetreeKey key3 = new CompactTetreeKey((byte) 3, 0x3FFFFL); // 18 bits (3*6)
        assertTrue(key3.isValid());

        // Non-root with too many bits set is invalid
        CompactTetreeKey invalid = new CompactTetreeKey((byte) 2, 0xFFFFFL); // Too many bits for level 2
        assertFalse(invalid.isValid());
    }
}
