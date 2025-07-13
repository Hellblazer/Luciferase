/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static com.hellblazer.luciferase.lucien.tetree.TetreeKeyTestHelper.*;
import static com.hellblazer.luciferase.lucien.tetree.TetreeKeyTestHelper.BitPatterns.*;
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
        // Create valid TetreeKeys with proper tmIndex values for their levels
        long tmIndex1 = createValidTmIndex(5, fixed(2, 3));  // Level 5 with fixed pattern
        long tmIndex2 = createValidTmIndex(5, fixed(2, 3));  // Same as tmIndex1
        long tmIndex3 = createValidTmIndex(5, fixed(2, 4));  // Different type
        long tmIndex5 = createValidTmIndex(5, fixed(5, 1));  // Different coords and type
        
        CompactTetreeKey key1 = new CompactTetreeKey((byte) 5, tmIndex1);
        CompactTetreeKey key2 = new CompactTetreeKey((byte) 5, tmIndex2);
        CompactTetreeKey key3 = new CompactTetreeKey((byte) 5, tmIndex3);
        CompactTetreeKey key5 = new CompactTetreeKey((byte) 5, tmIndex5);
        
        // Create a child of key1 at level 6
        long childTmIndex = createChildTmIndex(tmIndex1, 5, 5, 3);  // coords=5, type=3
        CompactTetreeKey key4 = new CompactTetreeKey((byte) 6, childTmIndex);

        // Test equality
        assertEquals(key1, key2);
        assertNotEquals(key1, key3);
        assertNotEquals(key1, key4);
        assertNotEquals(key1, key5);

        // Test comparison at same level
        assertEquals(0, key1.compareTo(key2));
        assertTrue(key1.compareTo(key3) < 0);
        assertTrue(key3.compareTo(key1) > 0);
        assertTrue(key1.compareTo(key5) < 0);
        assertTrue(key5.compareTo(key1) > 0);

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

        // Test factory create method for level 8 (48 bits)
        long level8TmIndex = createValidTmIndex(8, INCREMENTING);
        TetreeKey<?> compact = TetreeKey.create((byte) 8, level8TmIndex, 0L);
        assertInstanceOf(CompactTetreeKey.class, compact);

        // Test factory create method for level 15 (90 bits = 60 in low + 30 in high)
        long[] extendedBits = createExtendedTmIndex(15, INCREMENTING);
        TetreeKey<?> full = TetreeKey.create((byte) 15, extendedBits[0], extendedBits[1]);
        assertInstanceOf(ExtendedTetreeKey.class, full);
    }

    @Test
    void testMemoryEfficiency() {
        // Verify that CompactTetreeKey uses less memory than ExtendedTetreeKey
        // This is more of a documentation test

        // CompactTetreeKey: 1 byte + 1 long = 9 bytes (plus object overhead)
        // ExtendedTetreeKey: 1 byte + 2 longs = 17 bytes (plus object overhead)

        // For level 10 key: we need 10 * 6 = 60 bits
        // Use the helper to build a valid tmIndex
        long tmIndex = createValidTmIndex(10, INCREMENTING);
        
        CompactTetreeKey compact = new CompactTetreeKey((byte) 10, tmIndex);
        ExtendedTetreeKey full = new ExtendedTetreeKey((byte) 10, tmIndex, 0L);

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
        // Level 3: requires exactly 3*6 = 18 bits
        // Create a valid tmIndex with proper structure
        long level3TmIndex = 0L;
        // Level 0: type=0, coords=0 -> 000000
        level3TmIndex |= 0L;
        // Level 1: type=1, coords=2 -> 010001 = 0x11
        level3TmIndex |= (0x11L << 6);
        // Level 2: type=2, coords=4 -> 100010 = 0x22
        level3TmIndex |= (0x22L << 12);
        CompactTetreeKey key3 = new CompactTetreeKey((byte) 3, level3TmIndex);
        assertTrue(key3.isValid());

        // Test with maximum valid values
        // Each level can have type 0-5 and coords 0-7
        long maxValid = 0L;
        // Level 0: type=5, coords=7 -> 111101 = 0x3D
        maxValid |= 0x3DL;
        // Level 1: type=5, coords=7 -> 111101 = 0x3D
        maxValid |= (0x3DL << 6);
        CompactTetreeKey key2Max = new CompactTetreeKey((byte) 2, maxValid);
        assertTrue(key2Max.isValid());
    }
}
