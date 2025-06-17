/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
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
 * Test to demonstrate cache key collision issues in TetreeLevelCache
 * 
 * The current implementation has a bug in the cache key generation:
 * long key = ((long) x << 32) | ((long) y << 16) | ((long) z) | ((long) level << 8) | type;
 * 
 * The problem is that z is a 32-bit int that's not shifted, so it overlaps with level and type.
 * 
 * @author hal.hildebrand
 */
public class TetreeLevelCacheKeyCollisionTest {
    
    @Test
    public void testCacheKeyCollision() {
        // Demonstrate the collision issue
        
        // Case 1: Two different tetrahedra that produce the same cache key
        int x1 = 100;
        int y1 = 200;
        int z1 = 0x00000501;  // z with bits set in the lower 16 bits
        byte level1 = 5;
        byte type1 = 1;
        
        int x2 = 100;
        int y2 = 200;
        int z2 = 0x00000000;  // z = 0
        byte level2 = 5;
        byte type2 = 1;
        
        // Calculate keys using the buggy formula
        long buggyKey1 = ((long) x1 << 32) | ((long) y1 << 16) | ((long) z1) | ((long) level1 << 8) | type1;
        long buggyKey2 = ((long) x2 << 32) | ((long) y2 << 16) | ((long) z2) | ((long) level2 << 8) | type2;
        
        // With z1 = 0x00000501, the key calculation is:
        // key1 = (100L << 32) | (200L << 16) | 0x00000501 | (5L << 8) | 1
        //      = 0x64000000_00000000 | 0x00000000_00C80000 | 0x00000000_00000501 | 0x00000000_00000500 | 0x00000000_00000001
        //      = 0x64000000_00C80501 | 0x00000000_00000500 | 0x00000000_00000001
        //      = 0x64000000_00C80501 (the OR with 0x500 and 0x1 doesn't change because those bits are already set)
        
        // Let's show a clearer collision
        z1 = 0x00000501;  // This will collide with level=5, type=1
        long actualKey1 = ((long) x1 << 32) | ((long) y1 << 16) | ((long) z1) | ((long) level1 << 8) | type1;
        
        // This is different data but produces the same key due to overlap
        int x3 = 100;
        int y3 = 200;
        int z3 = 0x00000501;  // Same z value
        byte level3 = 0;      // Different level!
        byte type3 = 0;       // Different type!
        
        long actualKey3 = ((long) x3 << 32) | ((long) y3 << 16) | ((long) z3) | ((long) level3 << 8) | type3;
        
        // These should be different but they're the same!
        assertEquals(actualKey1, actualKey3, 
            "Different tetrahedra (with different level and type) produce the same cache key!");
        
        System.out.println("Collision demonstration:");
        System.out.println("Tet1: x=" + x1 + ", y=" + y1 + ", z=" + z1 + ", level=" + level1 + ", type=" + type1);
        System.out.println("Tet3: x=" + x3 + ", y=" + y3 + ", z=" + z3 + ", level=" + level3 + ", type=" + type3);
        System.out.println("Both produce key: 0x" + Long.toHexString(actualKey1));
    }
    
    @Test
    public void testBitOverlap() {
        // Show exactly how bits overlap
        
        // The current formula puts:
        // - x in bits 63-32 (32 bits)
        // - y in bits 31-16 (16 bits)
        // - z in bits 31-0  (32 bits) - OVERLAPS with y!
        // - level in bits 15-8 (8 bits) - OVERLAPS with z!
        // - type in bits 7-0 (8 bits) - OVERLAPS with z!
        
        int x = 0x12345678;
        int y = 0xABCD;
        int z = 0xFFFFFFFF;  // All bits set
        byte level = 0x7F;    // Max 7-bit value
        byte type = 0x05;     // Type 5
        
        long key = ((long) x << 32) | ((long) y << 16) | ((long) z) | ((long) level << 8) | type;
        
        // Extract the components to show the overlap
        long extractedX = (key >>> 32) & 0xFFFFFFFFL;
        long extractedY = (key >>> 16) & 0xFFFFL;
        long extractedZ = key & 0xFFFFFFFFL;
        long extractedLevel = (key >>> 8) & 0xFFL;
        long extractedType = key & 0xFFL;
        
        System.out.println("\nBit overlap demonstration:");
        System.out.println("Original x:     0x" + Integer.toHexString(x));
        System.out.println("Extracted x:    0x" + Long.toHexString(extractedX));
        System.out.println("Match: " + (x == extractedX));
        
        System.out.println("\nOriginal y:     0x" + Integer.toHexString(y));
        System.out.println("Extracted y:    0x" + Long.toHexString(extractedY));
        System.out.println("Match: " + (y == extractedY) + " (corrupted by z overlap!)");
        
        System.out.println("\nOriginal z:     0x" + Integer.toHexString(z));
        System.out.println("Extracted z:    0x" + Long.toHexString(extractedZ));
        System.out.println("Match: " + (z == extractedZ));
        
        System.out.println("\nOriginal level: 0x" + Integer.toHexString(level));
        System.out.println("Extracted level: 0x" + Long.toHexString(extractedLevel));
        System.out.println("Match: " + ((level & 0xFF) == extractedLevel) + " (corrupted by z overlap!)");
        
        System.out.println("\nOriginal type:  0x" + Integer.toHexString(type));
        System.out.println("Extracted type: 0x" + Long.toHexString(extractedType));
        System.out.println("Match: " + ((type & 0xFF) == extractedType) + " (corrupted by z overlap!)");
    }
    
    @Test
    public void testCorrectBitPacking() {
        // Demonstrate the correct way to pack the bits
        
        // We need to ensure no overlaps:
        // For x, y, z as 32-bit ints, level as byte, type as byte:
        // Total bits needed: 32 + 32 + 32 + 8 + 8 = 112 bits
        // But we only have 64 bits in a long!
        
        // Solution 1: Use smaller ranges for coordinates
        // If we limit coordinates to 20 bits each (max value 1,048,575):
        // - x: bits 63-44 (20 bits)
        // - y: bits 43-24 (20 bits)
        // - z: bits 23-4  (20 bits)
        // - level: bits 3-0 and extended into next byte (4 bits + overflow)
        // - type: separate storage or packed differently
        
        // Solution 2: Use only 16 bits per coordinate (max value 65,535):
        // - x: bits 63-48 (16 bits)
        // - y: bits 47-32 (16 bits)
        // - z: bits 31-16 (16 bits)
        // - level: bits 15-8 (8 bits)
        // - type: bits 7-0 (8 bits)
        
        // Let's implement Solution 2 (16-bit coordinates)
        int x = 0x1234;      // 16-bit value
        int y = 0x5678;      // 16-bit value
        int z = 0x9ABC;      // 16-bit value
        byte level = 21;     // Max level
        byte type = 5;       // Type 5
        
        // Correct packing with 16-bit coordinates
        long correctKey = ((long)(x & 0xFFFF) << 48) | 
                         ((long)(y & 0xFFFF) << 32) | 
                         ((long)(z & 0xFFFF) << 16) | 
                         ((long)(level & 0xFF) << 8) | 
                         (type & 0xFF);
        
        // Extract components to verify no overlap
        int extractedX = (int)((correctKey >>> 48) & 0xFFFF);
        int extractedY = (int)((correctKey >>> 32) & 0xFFFF);
        int extractedZ = (int)((correctKey >>> 16) & 0xFFFF);
        byte extractedLevel = (byte)((correctKey >>> 8) & 0xFF);
        byte extractedType = (byte)(correctKey & 0xFF);
        
        assertEquals(x, extractedX, "X should be preserved");
        assertEquals(y, extractedY, "Y should be preserved");
        assertEquals(z, extractedZ, "Z should be preserved");
        assertEquals(level, extractedLevel, "Level should be preserved");
        assertEquals(type, extractedType, "Type should be preserved");
        
        System.out.println("\nCorrect bit packing (16-bit coordinates):");
        System.out.println("Key: 0x" + Long.toHexString(correctKey));
        System.out.println("Successfully packed and extracted all components!");
    }
    
    @Test
    public void testCacheSlotCollisions() {
        // Test how many cache slot collisions we get with the buggy implementation
        
        int collisions = 0;
        int totalTests = 10000;
        
        // Track which slots are used
        boolean[] slotsUsed = new boolean[4096]; // INDEX_CACHE_SIZE
        
        for (int i = 0; i < totalTests; i++) {
            // Generate semi-random coordinates
            int x = i % 256;
            int y = (i / 256) % 256;
            int z = i;  // This will have many bits set
            byte level = (byte)(i % 22);
            byte type = (byte)(i % 6);
            
            // Calculate cache slot using buggy formula
            long key = ((long) x << 32) | ((long) y << 16) | ((long) z) | ((long) level << 8) | type;
            int slot = (int) (key & (4096 - 1));
            
            if (slotsUsed[slot]) {
                collisions++;
            }
            slotsUsed[slot] = true;
        }
        
        System.out.println("\nCache slot collision rate:");
        System.out.println("Total tests: " + totalTests);
        System.out.println("Unique slots used: " + (totalTests - collisions));
        System.out.println("Collisions: " + collisions);
        System.out.println("Collision rate: " + (100.0 * collisions / totalTests) + "%");
        
        // With the bug, we expect more collisions than necessary
        assertTrue(collisions > 0, "Should have some collisions with buggy key generation");
    }
    
    @Test 
    public void demonstrateFixedImplementation() {
        // Show how to fix the cache key generation
        
        System.out.println("\n=== FIXED IMPLEMENTATION ===");
        System.out.println("For 32-bit coordinates, we need to use a different approach:");
        System.out.println();
        System.out.println("Option 1: Use coordinate bits more efficiently");
        System.out.println("- Limit coordinates to 20 bits each (max ~1 million)");
        System.out.println("- Pack as: (x:20)(y:20)(z:20)(level:3)(type:1) = 64 bits");
        System.out.println();
        System.out.println("Option 2: Use a hash function");
        System.out.println("- Compute a proper hash of all inputs");
        System.out.println("- long hash = x * 31L + y * 961L + z * 29791L + level * 923521L + type");
        System.out.println();
        System.out.println("Option 3: Use two cache levels");
        System.out.println("- First level: (x, y) -> intermediate key");
        System.out.println("- Second level: (intermediate key, z, level, type) -> index");
        System.out.println();
        System.out.println("Recommended fix for TetreeLevelCache.java:");
        System.out.println("Replace lines 66 and 114 with:");
        System.out.println("long key = ((long)(x & 0xFFFFF) << 44) | ((long)(y & 0xFFFFF) << 24) | ((long)(z & 0xFFFFF) << 4) | ((level & 0xF) << 1) | (type & 0x1);");
        System.out.println("This limits coordinates to 20 bits, level to 4 bits, and type to 1 bit.");
    }
}