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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TetreeKey implementation.
 * 
 * @author hal.hildebrand
 */
class TetreeKeyTest {
    
    @Test
    void testBasicConstruction() {
        byte level = 5;
        long sfcIndex = 12345L;
        TetreeKey key = new TetreeKey(level, sfcIndex);
        
        assertEquals(level, key.getLevel());
        assertEquals(sfcIndex, key.getSfcIndex());
        assertNotNull(key.toString());
        assertTrue(key.toString().contains("level=5"));
        assertTrue(key.toString().contains("sfcIndex=12345"));
    }
    
    @Test
    void testInvalidConstruction() {
        // Negative level
        assertThrows(IllegalArgumentException.class, () -> new TetreeKey((byte) -1, 0L));
        
        // Level too high
        assertThrows(IllegalArgumentException.class, () -> new TetreeKey((byte) 22, 0L));
        
        // Negative SFC index
        assertThrows(IllegalArgumentException.class, () -> new TetreeKey((byte) 5, -1L));
    }
    
    @Test
    void testEquality() {
        TetreeKey key1 = new TetreeKey((byte) 5, 12345L);
        TetreeKey key2 = new TetreeKey((byte) 5, 12345L);
        TetreeKey key3 = new TetreeKey((byte) 5, 54321L);
        TetreeKey key4 = new TetreeKey((byte) 6, 12345L);
        
        // Reflexive
        assertEquals(key1, key1);
        
        // Symmetric
        assertEquals(key1, key2);
        assertEquals(key2, key1);
        
        // Different SFC index
        assertNotEquals(key1, key3);
        
        // Different level
        assertNotEquals(key1, key4);
        
        // Null and different type
        assertNotEquals(key1, null);
        assertNotEquals(key1, "not a key");
        
        // Hash code consistency
        assertEquals(key1.hashCode(), key2.hashCode());
        assertNotEquals(key1.hashCode(), key3.hashCode());
        assertNotEquals(key1.hashCode(), key4.hashCode());
    }
    
    @Test
    void testLexicographicOrdering() {
        TetreeKey key1 = new TetreeKey((byte) 1, 100L);
        TetreeKey key2 = new TetreeKey((byte) 1, 200L);
        TetreeKey key3 = new TetreeKey((byte) 2, 50L);
        TetreeKey key4 = new TetreeKey((byte) 2, 150L);
        
        // Within same level, ordered by SFC index
        assertTrue(key1.compareTo(key2) < 0);
        assertTrue(key2.compareTo(key1) > 0);
        
        // Different levels, lower level comes first regardless of SFC index
        assertTrue(key1.compareTo(key3) < 0);  // level 1 < level 2
        assertTrue(key2.compareTo(key3) < 0);  // level 1 < level 2
        assertTrue(key3.compareTo(key1) > 0);
        
        // Within level 2
        assertTrue(key3.compareTo(key4) < 0);
        
        // Null handling
        assertThrows(NullPointerException.class, () -> key1.compareTo(null));
    }
    
    @Test
    void testNonUniqueIndexAcrossLevels() {
        // This test demonstrates the problem that TetreeKey solves:
        // The same SFC index can appear at different levels
        long sameIndex = 7L;
        
        TetreeKey level1 = new TetreeKey((byte) 1, sameIndex);
        TetreeKey level2 = new TetreeKey((byte) 2, sameIndex);
        TetreeKey level3 = new TetreeKey((byte) 3, sameIndex);
        
        // All have the same SFC index
        assertEquals(sameIndex, level1.getSfcIndex());
        assertEquals(sameIndex, level2.getSfcIndex());
        assertEquals(sameIndex, level3.getSfcIndex());
        
        // But they are different keys
        assertNotEquals(level1, level2);
        assertNotEquals(level2, level3);
        assertNotEquals(level1, level3);
        
        // And they have different hash codes
        Set<Integer> hashCodes = new HashSet<>();
        hashCodes.add(level1.hashCode());
        hashCodes.add(level2.hashCode());
        hashCodes.add(level3.hashCode());
        assertEquals(3, hashCodes.size(), "Hash codes should be unique");
        
        // They sort by level first
        List<TetreeKey> keys = Arrays.asList(level3, level1, level2);
        Collections.sort(keys);
        assertEquals(level1, keys.get(0));
        assertEquals(level2, keys.get(1));
        assertEquals(level3, keys.get(2));
    }
    
    @Test
    void testValidation() {
        // Root level - only index 0 is valid
        assertTrue(new TetreeKey((byte) 0, 0L).isValid());
        assertFalse(new TetreeKey((byte) 0, 1L).isValid());
        
        // Level 1 - indices 0-7 are valid
        assertTrue(new TetreeKey((byte) 1, 0L).isValid());
        assertTrue(new TetreeKey((byte) 1, 7L).isValid());
        assertFalse(new TetreeKey((byte) 1, 8L).isValid());
        
        // Level 2 - indices 0-63 are valid
        assertTrue(new TetreeKey((byte) 2, 0L).isValid());
        assertTrue(new TetreeKey((byte) 2, 63L).isValid());
        assertFalse(new TetreeKey((byte) 2, 64L).isValid());
        
        // Level 3 - indices 0-511 are valid
        assertTrue(new TetreeKey((byte) 3, 0L).isValid());
        assertTrue(new TetreeKey((byte) 3, 511L).isValid());
        assertFalse(new TetreeKey((byte) 3, 512L).isValid());
    }
    
    @Test
    void testFromTet() {
        // Create a Tet with coordinates, level and type
        Tet tet = new Tet(100, 200, 300, (byte) 5, (byte) 0);
        TetreeKey key = TetreeKey.fromTet(tet);
        
        assertEquals(tet.l(), key.getLevel());
        assertEquals(tet.index(), key.getSfcIndex());
    }
    
    @Test
    void testRootFactory() {
        TetreeKey root = TetreeKey.root();
        
        assertEquals(0, root.getLevel());
        assertEquals(0L, root.getSfcIndex());
        assertTrue(root.isValid());
    }
    
    @Test
    void testSpatialLocalityWithinLevel() {
        // Within a level, spatial locality is preserved by SFC index ordering
        List<TetreeKey> keys = new ArrayList<>();
        byte level = 5;
        
        // Add keys with sequential SFC indices
        for (long i = 0; i < 100; i++) {
            keys.add(new TetreeKey(level, i));
        }
        
        // Shuffle and sort
        Collections.shuffle(keys);
        Collections.sort(keys);
        
        // Verify they're back in SFC order
        for (int i = 0; i < keys.size() - 1; i++) {
            assertEquals(i, keys.get(i).getSfcIndex());
            assertTrue(keys.get(i).compareTo(keys.get(i + 1)) < 0);
        }
    }
    
    @Test
    void testUnsupportedToLong() {
        TetreeKey key = new TetreeKey((byte) 5, 12345L);
        
        // TetreeKey cannot be represented as a single long
        assertThrows(UnsupportedOperationException.class, key::toLong);
    }
}