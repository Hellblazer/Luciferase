/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.BaseTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the parent() method implementation in SpatialKey implementations.
 */
public class SpatialKeyParentTest {

    @Test
    void testMortonKeyParent() {
        // Test root has no parent
        MortonKey root = MortonKey.getRoot();
        assertNull(root.parent(), "Root should have no parent");

        // Test level 1 node
        Point3f pos = new Point3f(100, 100, 100);
        long mortonCode = Constants.calculateMortonIndex(pos, (byte) 5);
        MortonKey level5Key = new MortonKey(mortonCode, (byte) 5);

        // Get parent
        MortonKey parent = level5Key.parent();
        assertNotNull(parent, "Level 5 key should have a parent");
        assertEquals(4, parent.getLevel(), "Parent should be at level 4");

        // Verify parent code is correct (shifted right by 3 bits)
        assertEquals(mortonCode >> 3, parent.getMortonCode(), "Parent Morton code should be child >> 3");

        // Test multiple levels up
        MortonKey grandparent = parent.parent();
        assertNotNull(grandparent, "Level 4 key should have a parent");
        assertEquals(3, grandparent.getLevel(), "Grandparent should be at level 3");
        assertEquals(mortonCode >> 6, grandparent.getMortonCode(), "Grandparent Morton code should be child >> 6");
    }

    @Test
    void testParentConsistency() {
        // Test that parent-child relationships are consistent
        Point3f pos = new Point3f(512, 512, 512);

        // Create a key at level 10
        long mortonCode = Constants.calculateMortonIndex(pos, (byte) 10);
        MortonKey childKey = new MortonKey(mortonCode, (byte) 10);

        // Walk up to root
        MortonKey current = childKey;
        int expectedLevel = 10;

        while (current != null && current.getLevel() > 0) {
            assertEquals(expectedLevel, current.getLevel(), "Level should decrease by 1 each step");
            MortonKey parent = current.parent();

            if (parent != null) {
                assertEquals(expectedLevel - 1, parent.getLevel(), "Parent level should be current level - 1");
                // Verify parent code relationship
                assertEquals(current.getMortonCode() >> 3, parent.getMortonCode(),
                             "Parent code should be child code >> 3");
            }

            current = parent;
            expectedLevel--;
        }

        // Should have reached root
        assertNotNull(current, "Should reach root");
        assertEquals(0, current.getLevel(), "Should end at level 0");
        assertNull(current.parent(), "Root should have no parent");
    }

    @Test
    void testTetreeKeyBitShifting() {
        // Test the bit-shifting logic for TetreeKey parent calculation

        // Test level transitions around the 10-level boundary
        // Level 9 -> 8 (entirely in low bits)
        BaseTetreeKey<? extends BaseTetreeKey> level9 = new CompactTetreeKey((byte) 9, 0x123456789ABCDEFL);
        BaseTetreeKey<? extends BaseTetreeKey> parent9to8 = level9.parent();
        assertNotNull(parent9to8);
        assertEquals(8, parent9to8.getLevel());
        assertEquals(0x123456789ABCDEFL >>> 6, parent9to8.getLowBits());
        assertEquals(0L, parent9to8.getHighBits());

        // Level 10 -> 9 (only low bits are used for levels <= 10)
        BaseTetreeKey<? extends BaseTetreeKey> level10 = new CompactTetreeKey((byte) 10, 0xFFFFFFFFFFFFFFFFL);
        BaseTetreeKey<? extends BaseTetreeKey> parent10to9 = level10.parent();
        assertNotNull(parent10to9);
        assertEquals(9, parent10to9.getLevel());
        // Just shift the low bits
        assertEquals(0xFFFFFFFFFFFFFFFFL >>> 6, parent10to9.getLowBits());
        assertEquals(0L, parent10to9.getHighBits());

        // Level 11 -> 10 (transition from using highBits to not using them)
        // lowBits contains levels 0-9, highBits contains level 10 and up
        BaseTetreeKey<? extends BaseTetreeKey> level11 = new TetreeKey((byte) 11, 0xAAAAAAAAAAAAAAAAL,
                                                                       0xBBL); // highBits has 6 bits for level 10
        BaseTetreeKey<? extends BaseTetreeKey> parent11to10 = level11.parent();
        assertNotNull(parent11to10);
        assertEquals(10, parent11to10.getLevel());
        // Low bits stay the same (contains levels 0-9)
        assertEquals(0xAAAAAAAAAAAAAAAAL, parent11to10.getLowBits());
        // High bits are shifted right by 6 to remove level 10
        assertEquals(0xBBL >>> 6, parent11to10.getHighBits());
    }

    @Test
    void testTetreeKeyParent() {
        // Test root has no parent
        BaseTetreeKey<? extends BaseTetreeKey> root = BaseTetreeKey.getRoot();
        assertNull(root.parent(), "Root should have no parent");

        // Test with a specific tetrahedron at level 3
        var cellSize = Constants.lengthAtLevel((byte) 3);
        Tet tet = new Tet(cellSize, cellSize, cellSize, (byte) 3, (byte) 0);
        var level3Key = tet.tmIndex();

        // Get parent
        BaseTetreeKey<? extends BaseTetreeKey> parent = level3Key.parent();
        assertNotNull(parent, "Level 3 key should have a parent");
        assertEquals(2, parent.getLevel(), "Parent should be at level 2");

        // Get grandparent
        BaseTetreeKey<? extends BaseTetreeKey> grandparent = parent.parent();
        assertNotNull(grandparent, "Level 2 key should have a parent");
        assertEquals(1, grandparent.getLevel(), "Grandparent should be at level 1");

        // Get great-grandparent (should be root)
        BaseTetreeKey<? extends BaseTetreeKey> greatGrandparent = grandparent.parent();
        assertNotNull(greatGrandparent, "Level 1 key should have a parent");
        assertEquals(0, greatGrandparent.getLevel(), "Great-grandparent should be root at level 0");

        // Root's parent should be null
        assertNull(greatGrandparent.parent(), "Root should have no parent");
    }
}
