package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the 128-bit ExtendedTetreeKey implementation
 */
public class TetreeKey128BitTest {

    @Test
    public void testComparison() {
        // Test that comparison works correctly
        ExtendedTetreeKey key1 = new ExtendedTetreeKey((byte) 10, 100L, 0L);
        ExtendedTetreeKey key2 = new ExtendedTetreeKey((byte) 10, 200L, 0L);
        ExtendedTetreeKey key3 = new ExtendedTetreeKey((byte) 15, 100L, 1L);

        assertTrue(key1.compareTo(key2) < 0);
        assertTrue(key2.compareTo(key1) > 0);
        assertTrue(key1.compareTo(key3) < 0);
    }

    @Test
    public void testEquality() {
        // Test that keys with same values are equal
        long lowBits = 0x123456789ABCDEFL;
        long highBits = 0x0000000000000FFFL;

        ExtendedTetreeKey key1 = new ExtendedTetreeKey((byte) 20, lowBits, highBits);
        ExtendedTetreeKey key2 = new ExtendedTetreeKey((byte) 20, lowBits, highBits);
        ExtendedTetreeKey key3 = new ExtendedTetreeKey((byte) 20, lowBits + 1, highBits);

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
        assertNotEquals(key1, key3);

        // Test comparison
        assertEquals(0, key1.compareTo(key2));
        assertTrue(key1.compareTo(key3) < 0);
    }

    @Test
    public void testHighLevelKeys() {
        // Test level 15 - requires both low and high bits
        long lowBits = 0xFFFFFFFFFFFFFFFFL;
        long highBits = 0x000000000000003FL; // 6 bits for 5 additional levels
        ExtendedTetreeKey key128 = new ExtendedTetreeKey((byte) 15, lowBits, highBits);

        assertEquals(15, key128.getLevel());
        assertEquals(lowBits, key128.getLowBits());
        assertEquals(highBits, key128.getHighBits());
        assertTrue(key128.isValid());
    }

    @Test
    public void testLowLevelKeys() {
        // Test level 5 - fits entirely in low bits
        long lowBits = 0x0000000000123456L;
        ExtendedTetreeKey key128 = new ExtendedTetreeKey((byte) 5, lowBits, 0L);

        assertEquals(5, key128.getLevel());
        assertEquals(lowBits, key128.getLowBits());
        assertEquals(0L, key128.getHighBits());
        assertTrue(key128.isValid());
    }

    @Test
    public void testMaxLevelKey() {
        // Test level 21 - maximum level
        long lowBits = 0xFFFFFFFFFFFFFFFFL;
        long highBits = 0x3FFFFFFFFFFFFFFFL; // All bits used
        ExtendedTetreeKey key128 = new ExtendedTetreeKey((byte) 21, lowBits, highBits);

        assertEquals(21, key128.getLevel());
        assertEquals(lowBits, key128.getLowBits());
        assertEquals(highBits, key128.getHighBits());
        assertTrue(key128.isValid());
    }

    @Test
    public void testMidLevelKeys() {
        // Test level 10 - maximum that fits in low bits
        long lowBits = 0x0FFFFFFFFFFFFFFFL; // 60 bits set
        ExtendedTetreeKey key128 = new ExtendedTetreeKey((byte) 10, lowBits, 0L);

        assertEquals(10, key128.getLevel());
        assertEquals(lowBits, key128.getLowBits());
        assertEquals(0L, key128.getHighBits());
        assertTrue(key128.isValid());
    }

    @Test
    public void testValidation() {
        // Test that validation still works
        ExtendedTetreeKey validKey = new ExtendedTetreeKey((byte) 10, 0x123456L, 0L);
        assertTrue(validKey.isValid());

        // Root key
        ExtendedTetreeKey rootKey = new ExtendedTetreeKey((byte) 0, 0L, 0L);
        assertTrue(rootKey.isValid());
    }
}
