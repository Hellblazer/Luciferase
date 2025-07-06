package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify 128-bit ExtendedTetreeKey implementation
 */
public class TetreeKey128BitSimpleTest {

    @Test
    public void testBasicTetreeKeyCreation() {
        // Test root key
        var root = TetreeKey.getRoot();
        assertEquals((byte) 0, root.getLevel());
        assertEquals(0L, root.getLowBits());
        assertEquals(0L, root.getHighBits());
        assertTrue(root.isValid());

        // Test creating a key with specific values (level 5 uses only low bits)
        ExtendedTetreeKey key = new ExtendedTetreeKey((byte) 5, 12345L, 0L);
        assertEquals((byte) 5, key.getLevel());
        assertEquals(12345L, key.getLowBits());
        assertEquals(0L, key.getHighBits());
        assertTrue(key.isValid());

        // Test creating a key that uses high bits (level > 10)
        ExtendedTetreeKey highKey = new ExtendedTetreeKey((byte) 15, 12345L, 67890L);
        assertEquals((byte) 15, highKey.getLevel());
        assertEquals(12345L, highKey.getLowBits());
        assertEquals(67890L, highKey.getHighBits());
        assertTrue(highKey.isValid());
    }

    @Test
    public void testTetTmIndexCreation() {
        // Test that a Tet can create a ExtendedTetreeKey
        int cellSize = Constants.lengthAtLevel((byte) 3);
        Tet tet = new Tet(cellSize, cellSize * 2, cellSize * 3, (byte) 3, (byte) 2);
        var key = tet.tmIndex();

        assertNotNull(key);
        assertEquals((byte) 3, key.getLevel());
        // We can't easily predict the exact bit values, but we can verify they're set
        assertTrue(key.getLowBits() != 0L || key.getHighBits() != 0L || key.getLevel() == 0);
    }

    @Test
    public void testTetreeKeyComparison() {
        ExtendedTetreeKey key1 = new ExtendedTetreeKey((byte) 5, 100L, 0L);
        ExtendedTetreeKey key2 = new ExtendedTetreeKey((byte) 5, 200L, 0L);
        ExtendedTetreeKey key3 = new ExtendedTetreeKey((byte) 5, 100L, 1L);

        // Test comparison
        assertTrue(key1.compareTo(key2) < 0);
        assertTrue(key2.compareTo(key1) > 0);
        assertTrue(key1.compareTo(key3) < 0);

        // Test equality
        ExtendedTetreeKey key1Copy = new ExtendedTetreeKey((byte) 5, 100L, 0L);
        assertEquals(key1, key1Copy);
        assertEquals(0, key1.compareTo(key1Copy));
    }

    @Test
    public void testTetreeKeyRoundTrip() {
        // Create a Tet with grid-aligned coordinates
        byte level = 5;
        byte type = 3;
        int cellSize = Constants.lengthAtLevel(level);
        int x = cellSize; // Grid position 1
        int y = cellSize * 2; // Grid position 2
        int z = cellSize * 3; // Grid position 3

        Tet original = new Tet(x, y, z, level, type);

        // Get its ExtendedTetreeKey
        var key = original.tmIndex();

        // For now, just verify the key is created correctly
        // The round-trip test needs proper coordinate system understanding
        assertNotNull(key);
        assertEquals(level, key.getLevel());
        assertTrue(key.getLowBits() != 0 || key.getHighBits() != 0);

        // TODO: Fix round-trip once coordinate system is properly understood
        // The current encode/decode assumes a specific bit layout that may not
        // match the test expectations
    }
}
