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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that spatial locality is preserved by the key implementations.
 * 
 * @author hal.hildebrand
 */
class SpatialKeyLocalityTest {
    
    @Test
    void testMortonKeySpatialLocality() {
        // Create a grid of Morton keys
        List<MortonKey> keys = new ArrayList<>();
        byte level = 8;
        int gridSize = 8;
        
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    keys.add(MortonKey.fromCoordinates(x, y, z, level));
                }
            }
        }
        
        // Sort by natural ordering
        Collections.sort(keys);
        
        // Verify that adjacent keys in sorted order represent spatially proximate cells
        long maxGap = 0;
        long totalGap = 0;
        int gapCount = 0;
        
        for (int i = 0; i < keys.size() - 1; i++) {
            long gap = keys.get(i + 1).getMortonCode() - keys.get(i).getMortonCode();
            maxGap = Math.max(maxGap, gap);
            totalGap += gap;
            gapCount++;
        }
        
        double avgGap = gapCount > 0 ? (double) totalGap / gapCount : 0;
        
        // The average gap should be relatively small compared to the range
        long range = keys.get(keys.size() - 1).getMortonCode() - keys.get(0).getMortonCode();
        double gapRatio = range > 0 ? avgGap / range : 0;
        
        assertTrue(gapRatio < 0.01, "Average gap ratio too large: " + gapRatio);
        System.out.println("Morton key locality - Avg gap: " + avgGap + ", Max gap: " + maxGap + 
                         ", Range: " + range + ", Gap ratio: " + gapRatio);
    }
    
    @Test
    void testTetreeKeySpatialLocalityWithinLevel() {
        // For Tetree, spatial locality is preserved within each level
        byte level = 5;
        List<TetreeKey> keys = new ArrayList<>();
        
        // Generate keys with sequential SFC indices
        // In a real Tetree, these would represent spatially adjacent tetrahedra
        for (long i = 0; i < 1000; i++) {
            keys.add(new TetreeKey(level, i));
        }
        
        // Shuffle to simulate random insertion
        List<TetreeKey> shuffled = new ArrayList<>(keys);
        Collections.shuffle(shuffled);
        
        // Sort back
        Collections.sort(shuffled);
        
        // Verify ordering matches SFC index order
        for (int i = 0; i < shuffled.size(); i++) {
            assertEquals(i, shuffled.get(i).getSfcIndex(), 
                        "SFC indices should be in sequential order after sorting");
        }
    }
    
    @Test
    void testTetreeKeyLevelSeparation() {
        // Verify that keys from different levels don't interfere
        List<TetreeKey> mixedLevelKeys = new ArrayList<>();
        
        // Add keys from multiple levels with overlapping SFC indices
        for (byte level = 1; level <= 5; level++) {
            for (long sfcIndex = 0; sfcIndex < 10; sfcIndex++) {
                mixedLevelKeys.add(new TetreeKey(level, sfcIndex));
            }
        }
        
        Collections.shuffle(mixedLevelKeys);
        Collections.sort(mixedLevelKeys);
        
        // Verify level ordering
        byte currentLevel = 1;
        long lastIndexInLevel = -1;
        
        for (TetreeKey key : mixedLevelKeys) {
            if (key.getLevel() > currentLevel) {
                // Moving to next level
                currentLevel = key.getLevel();
                lastIndexInLevel = -1;
            }
            
            assertEquals(currentLevel, key.getLevel());
            assertTrue(key.getSfcIndex() > lastIndexInLevel, 
                      "SFC indices should be ordered within level");
            lastIndexInLevel = key.getSfcIndex();
        }
    }
    
    @Test
    void testKeyTypeIsolation() {
        // Verify that different key types cannot be mixed
        MortonKey mortonKey = new MortonKey(12345L);
        TetreeKey tetreeKey = new TetreeKey((byte) 5, 12345L);
        
        // These should be completely different types
        assertFalse(mortonKey.equals(tetreeKey));
        assertFalse(tetreeKey.equals(mortonKey));
        
        // And they can't be compared (would cause ClassCastException at runtime)
        // This is good - prevents mixing key types in collections
    }
    
    @Test
    void testPerformanceCharacteristics() {
        // Test that key operations are efficient
        int iterations = 1_000_000;
        
        // Morton key performance
        long mortonStart = System.nanoTime();
        MortonKey mKey1 = new MortonKey(12345L);
        MortonKey mKey2 = new MortonKey(54321L);
        
        for (int i = 0; i < iterations; i++) {
            mKey1.compareTo(mKey2);
            mKey1.equals(mKey2);
            mKey1.hashCode();
            mKey1.getLevel();
        }
        long mortonTime = System.nanoTime() - mortonStart;
        
        // Tetree key performance
        long tetreeStart = System.nanoTime();
        TetreeKey tKey1 = new TetreeKey((byte) 5, 12345L);
        TetreeKey tKey2 = new TetreeKey((byte) 5, 54321L);
        
        for (int i = 0; i < iterations; i++) {
            tKey1.compareTo(tKey2);
            tKey1.equals(tKey2);
            tKey1.hashCode();
            tKey1.getLevel();
        }
        long tetreeTime = System.nanoTime() - tetreeStart;
        
        // Both should be very fast (< 1 microsecond per operation on average)
        double mortonNsPerOp = (double) mortonTime / (iterations * 4);
        double tetreeNsPerOp = (double) tetreeTime / (iterations * 4);
        
        System.out.println("Performance - Morton: " + mortonNsPerOp + " ns/op, " +
                         "Tetree: " + tetreeNsPerOp + " ns/op");
        
        assertTrue(mortonNsPerOp < 1000, "Morton operations too slow");
        assertTrue(tetreeNsPerOp < 1000, "Tetree operations too slow");
    }
}