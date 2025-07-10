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
 * Analyze TetreeKey bit capacity for levels 0-21
 * 
 * @author hal.hildebrand
 */
public class TetreeKeyBitAnalysisTest {
    
    @Test
    void analyzeTetreeKeyBitCapacity() {
        System.out.println("TetreeKey Bit Capacity Analysis");
        System.out.println("==============================");
        System.out.println();
        
        // Each level uses 6 bits (3 for type, 3 for coordinates)
        int bitsPerLevel = 6;
        
        System.out.println("Bits per level: " + bitsPerLevel);
        System.out.println("Compact form: 1 long (64 bits)");
        System.out.println("Extended form: 2 longs (128 bits)");
        System.out.println();
        
        System.out.println("Level | Total Bits | Storage Type | Bit Position Range");
        System.out.println("------|------------|--------------|-------------------");
        
        for (int level = 0; level <= 21; level++) {
            int totalBits = level * bitsPerLevel;
            String storageType = level <= 10 ? "Compact" : "Extended";
            
            String bitRange;
            if (level == 0) {
                bitRange = "N/A (root)";
            } else if (level <= 10) {
                int startBit = 0;
                int endBit = (level - 1) * bitsPerLevel + 5;
                bitRange = String.format("Low[%d-%d]", startBit, endBit);
            } else {
                // Levels 0-9 in low bits
                int lowEndBit = 9 * bitsPerLevel + 5; // 59
                // Levels 10+ in high bits
                int highStartBit = 0;
                int highEndBit = (level - 11) * bitsPerLevel + 5;
                bitRange = String.format("Low[0-%d], High[%d-%d]", lowEndBit, highStartBit, highEndBit);
            }
            
            System.out.printf("%5d | %10d | %-12s | %s%n", level, totalBits, storageType, bitRange);
        }
        
        System.out.println();
        System.out.println("Analysis:");
        System.out.println("- Levels 0-10: " + (10 * bitsPerLevel) + " bits (fits in 64-bit long)");
        System.out.println("- Level 21: " + (21 * bitsPerLevel) + " bits = 126 bits (requires 128 bits)");
        System.out.println("- Maximum theoretical capacity: " + (128 / bitsPerLevel) + " levels");
        
        // Test actual creation
        System.out.println("\nTesting actual TetreeKey creation for levels 0-21:");
        for (byte level = 0; level <= 21; level++) {
            try {
                TetreeKey<?> key = TetreeKey.create(level, 0L, 0L);
                System.out.println("Level " + level + ": SUCCESS - " + key.getClass().getSimpleName());
            } catch (Exception e) {
                System.out.println("Level " + level + ": FAILED - " + e.getMessage());
            }
        }
        
        // Test overflow behavior at level 21
        System.out.println("\nTesting bit overflow at level 21:");
        
        // For level 21, we have:
        // - Levels 0-9 in low bits (60 bits used)
        // - Levels 10-20 in high bits (66 bits needed, but only 64 available)
        
        int highBitsNeeded = (21 - 10) * bitsPerLevel; // 11 levels * 6 bits = 66 bits
        System.out.println("High bits needed for levels 10-20: " + highBitsNeeded);
        System.out.println("High bits available: 64");
        System.out.println("Overflow: " + (highBitsNeeded > 64));
        
        // Level 20 starts at bit position (20-10)*6 = 60
        // Level 20 type occupies bits 60-62
        // Level 20 coords would occupy bits 63-65 (overflow!)
        int level20TypeStart = (20 - 10) * 6;
        int level20CoordsStart = level20TypeStart + 3;
        System.out.println("\nLevel 20 bit positions in high bits:");
        System.out.println("- Type bits: " + level20TypeStart + "-" + (level20TypeStart + 2));
        System.out.println("- Coord bits: " + level20CoordsStart + "-" + (level20CoordsStart + 2) + 
                          " (bits 64-65 overflow!)");
        
        // This explains why level 20 coords return 0 - they overflow beyond 64 bits
        assertTrue(level20CoordsStart + 2 >= 64, "Level 20 coordinates should overflow");
    }
}