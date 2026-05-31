/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to validate that TetreeKey can encode levels 0-21
 * 
 * @author hal.hildebrand
 */
public class TetreeKeyLevelEncodingTest {
    
    @Test
    void testTetreeKeyCanEncodeAllLevels() {
        // Coarsest-at-MSB uniform layout (Luciferase-tkvb): each step's 6-bit group is appended at
        // the LSB, so step 0 (shallowest) ends in the most significant bits and the leaf
        // (step level-1) is at bits 0-5. All 21 levels fit two longs with no split and no
        // truncation. Plant a known (coord, type) per step, then read it back exactly.
        for (byte level = 0; level <= 21; level++) {
            long lowBits = 0L;
            long highBits = 0L;
            for (int i = 0; i < level; i++) {
                byte type = (byte) (i % 6);   // valid types 0-5
                byte coords = (byte) (i % 8); // 3 coordinate bits
                int sixBits = (coords << 3) | type;
                // Append at the LSB (shift the running 128-bit value left by one group).
                highBits = (highBits << 6) | (lowBits >>> 58);
                lowBits = (lowBits << 6) | sixBits;
            }

            TetreeKey<?> key = TetreeKey.create(level, lowBits, highBits);

            assertNotNull(key, "Key should not be null for level " + level);
            assertEquals(level, key.getLevel(), "Key level should match");

            // Every step round-trips exactly - no truncation in the uniform layout.
            for (int i = 0; i < level; i++) {
                byte expectedType = (byte) (i % 6);
                byte expectedCoords = (byte) (i % 8);
                assertEquals(expectedType, key.getTypeAtLevel(i),
                    "Type at step " + i + " should match for key at level " + level);
                assertEquals(expectedCoords, key.getCoordBitsAtLevel(i),
                    "Coords at step " + i + " should match for key at level " + level);
            }
            
            // Test that the key is valid
            assertTrue(key.isValid(), "Key should be valid for level " + level);
        }
        
        // Test that level 22 is rejected
        assertThrows(IllegalArgumentException.class, () -> {
            TetreeKey.create((byte) 22, 0L, 0L);
        }, "Level 22 should be rejected");
        
        // Test that negative levels are rejected
        assertThrows(IllegalArgumentException.class, () -> {
            TetreeKey.create((byte) -1, 0L, 0L);
        }, "Negative levels should be rejected");
    }
    
    @Test
    void testTetreeKeyBitCapacity() {
        // Verify bit capacity for different levels
        
        // Compact form (levels 0-10) uses single long (64 bits)
        // Each level uses 6 bits, so max 10 levels = 60 bits
        for (byte level = 0; level <= 10; level++) {
            TetreeKey<?> key = TetreeKey.create(level, -1L, 0L); // All bits set
            assertEquals(0L, key.getHighBits(), 
                "Compact key should have no high bits for level " + level);
        }
        
        // Extended form (levels 11-21) uses two longs (128 bits)
        // Levels 0-9 in low bits (60 bits), levels 10+ in high bits
        for (byte level = 11; level <= 21; level++) {
            TetreeKey<?> key = TetreeKey.create(level, -1L, -1L); // All bits set
            assertNotEquals(0L, key.getHighBits(), 
                "Extended key should have high bits for level " + level);
        }
        
        // Test maximum level 21
        // 21 levels * 6 bits = 126 bits (fits in 128 bits)
        TetreeKey<?> maxKey = TetreeKey.create((byte) 21, -1L, -1L);
        assertEquals(21, maxKey.getLevel());
        
        // Verify we can access all 21 levels
        for (int i = 0; i <= 20; i++) {
            // Should not throw
            maxKey.getTypeAtLevel(i);
            maxKey.getCoordBitsAtLevel(i);
        }
    }
    
    @Test 
    void testTetIntegrationWithLevel21() {
        // Test that Tet can work with level 21
        // Note: Tet constructor uses MortonCurve.MAX_REFINEMENT_LEVEL in assertion
        
        // Create a Tet at level 21
        int cellSize = 1 << (21 - 21); // cellSize = 1 at level 21
        Tet tet = new Tet(0, 0, 0, (byte) 21, (byte) 0);
        
        // Verify the tet was created
        assertEquals(21, tet.l);
        assertEquals(0, tet.type);
        
        // Get its tmIndex
        TetreeKey<?> key = tet.tmIndex();
        assertNotNull(key);
        assertEquals(21, key.getLevel());
        
        // Verify it's an extended key
        assertTrue(key instanceof ExtendedTetreeKey, 
            "Level 21 should produce an ExtendedTetreeKey");
    }
}