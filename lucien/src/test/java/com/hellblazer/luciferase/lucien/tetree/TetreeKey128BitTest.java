package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the 128-bit TetreeKey implementation
 */
public class TetreeKey128BitTest {

    @Test
    public void testRootKey() {
        // Test root key creation
        TetreeKey root = TetreeKey.getRoot();
        TetreeKey root128 = new TetreeKey((byte) 0, 0L, 0L);
        
        assertEquals(root.getLevel(), root128.getLevel());
        assertEquals(root.getLowBits(), root128.getLowBits());
        assertEquals(root.getHighBits(), root128.getHighBits());
        assertEquals(0L, root128.getLowBits());
        assertEquals(0L, root128.getHighBits());
        assertEquals(root, root128);
        assertTrue(root.isValid());
    }

    @Test
    public void testLowLevelKeys() {
        // Test level 5 - fits entirely in low bits
        long lowBits = 0x0000000000123456L;
        TetreeKey key128 = new TetreeKey((byte) 5, lowBits, 0L);
        
        assertEquals(5, key128.getLevel());
        assertEquals(lowBits, key128.getLowBits());
        assertEquals(0L, key128.getHighBits());
        assertTrue(key128.isValid());
    }

    @Test
    public void testMidLevelKeys() {
        // Test level 10 - maximum that fits in low bits
        long lowBits = 0x0FFFFFFFFFFFFFFFL; // 60 bits set
        TetreeKey key128 = new TetreeKey((byte) 10, lowBits, 0L);
        
        assertEquals(10, key128.getLevel());
        assertEquals(lowBits, key128.getLowBits());
        assertEquals(0L, key128.getHighBits());
        assertTrue(key128.isValid());
    }

    @Test
    public void testHighLevelKeys() {
        // Test level 15 - requires both low and high bits
        long lowBits = 0xFFFFFFFFFFFFFFFFL;
        long highBits = 0x000000000000003FL; // 6 bits for 5 additional levels
        TetreeKey key128 = new TetreeKey((byte) 15, lowBits, highBits);
        
        assertEquals(15, key128.getLevel());
        assertEquals(lowBits, key128.getLowBits());
        assertEquals(highBits, key128.getHighBits());
        assertTrue(key128.isValid());
    }

    @Test
    public void testMaxLevelKey() {
        // Test level 21 - maximum level
        long lowBits = 0xFFFFFFFFFFFFFFFFL;
        long highBits = 0x3FFFFFFFFFFFFFFFL; // All bits used
        TetreeKey key128 = new TetreeKey((byte) 21, lowBits, highBits);
        
        assertEquals(21, key128.getLevel());
        assertEquals(lowBits, key128.getLowBits());
        assertEquals(highBits, key128.getHighBits());
        assertTrue(key128.isValid());
    }

    @Test
    public void testEquality() {
        // Test that keys with same values are equal
        long lowBits = 0x123456789ABCDEFL;
        long highBits = 0x0000000000000FFFL;
        
        TetreeKey key1 = new TetreeKey((byte) 20, lowBits, highBits);
        TetreeKey key2 = new TetreeKey((byte) 20, lowBits, highBits);
        TetreeKey key3 = new TetreeKey((byte) 20, lowBits + 1, highBits);
        
        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
        assertNotEquals(key1, key3);
        
        // Test comparison
        assertEquals(0, key1.compareTo(key2));
        assertTrue(key1.compareTo(key3) < 0);
    }

    @Test
    public void testComparison() {
        // Test that comparison works correctly
        TetreeKey key1 = new TetreeKey((byte) 10, 100L, 0L);
        TetreeKey key2 = new TetreeKey((byte) 10, 200L, 0L);
        TetreeKey key3 = new TetreeKey((byte) 15, 100L, 1L);
        
        assertTrue(key1.compareTo(key2) < 0);
        assertTrue(key2.compareTo(key1) > 0);
        assertTrue(key1.compareTo(key3) < 0);
    }

    @Test
    public void testValidation() {
        // Test that validation still works
        TetreeKey validKey = new TetreeKey((byte) 10, 0x123456L, 0L);
        assertTrue(validKey.isValid());
        
        // Root key
        TetreeKey rootKey = new TetreeKey((byte) 0, 0L, 0L);
        assertTrue(rootKey.isValid());
    }
}