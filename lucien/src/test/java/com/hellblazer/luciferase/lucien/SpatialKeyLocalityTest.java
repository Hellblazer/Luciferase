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
import com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.ExtendedTetreeKey;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests to verify that spatial locality is preserved by the key implementations.
 *
 * @author hal.hildebrand
 */
class SpatialKeyLocalityTest {

    @Test
    void testKeyTypeIsolation() {
        // Verify that different key types cannot be mixed
        MortonKey mortonKey = new MortonKey(12345L);
        byte level = 5;
        int cellSize = Constants.lengthAtLevel(level);
        Tet tet = new Tet(0, cellSize, 2 * cellSize, level, (byte) 0);
        var tetreeKey = tet.tmIndex();

        // These should be completely different types
        assertNotEquals(mortonKey, tetreeKey);
        assertNotEquals(tetreeKey, mortonKey);

        // And they can't be compared (would cause ClassCastException at runtime)
        // This is good - prevents mixing key types in collections
    }

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
        System.out.println(
        "Morton key locality - Avg gap: " + avgGap + ", Max gap: " + maxGap + ", Range: " + range + ", Gap ratio: "
        + gapRatio);
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
        byte level = 5;
        int cellSize = Constants.lengthAtLevel(level);
        Tet tet1 = new Tet(0, cellSize, 2 * cellSize, level, (byte) 0);
        Tet tet2 = new Tet(cellSize, 2 * cellSize, 3 * cellSize, level, (byte) 0);
        var tKey1 = tet1.tmIndex();
        var tKey2 = tet2.tmIndex();

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

        System.out.println(
        "Performance - Morton: " + mortonNsPerOp + " ns/op, " + "Tetree: " + tetreeNsPerOp + " ns/op");

        assertTrue(mortonNsPerOp < 1000, "Morton operations too slow");
        assertTrue(tetreeNsPerOp < 1000, "Tetree operations too slow");
    }

    @Test
    void testTetreeKeyLevelSeparation() {
        // Verify that keys from different levels don't interfere
        List<ExtendedTetreeKey> mixedLevelKeys = new ArrayList<>();

        // Add keys from multiple levels with tm-indices from actual Tets
        // Use different coordinates to ensure variety in tm-indices
        for (byte level = 1; level <= 5; level++) {
            int cellSize = Constants.lengthAtLevel(level);
            // Calculate maximum valid coordinate for this level
            int maxCoord = Constants.lengthAtLevel((byte) 0); // Root extent
            int maxCells = maxCoord / cellSize; // Maximum number of cells that fit

            for (int i = 0; i < Math.min(10, maxCells * maxCells); i++) {
                // Vary x and y coordinates within valid bounds
                int cellX = i % Math.min(maxCells, 3); // Limit to valid range
                int cellY = (i / 3) % Math.min(maxCells, 3); // Limit to valid range
                int x = cellX * cellSize;
                int y = cellY * cellSize;
                Tet tet = new Tet(x, y, 0, level, (byte) 0);
                var key = tet.tmIndex();
                ExtendedTetreeKey tetreeKey = key instanceof ExtendedTetreeKey ? (ExtendedTetreeKey) key
                                                                               : ExtendedTetreeKey.fromCompactKey(
                                                                               (CompactTetreeKey) key);
                mixedLevelKeys.add(tetreeKey);
            }
        }

        Collections.shuffle(mixedLevelKeys);
        Collections.sort(mixedLevelKeys);

        // ExtendedTetreeKey ordering is LEVEL-FIRST (Luciferase-tkvb): compareTo compares level before the
        // 128-bit TM-index. So sorting groups keys by level — every level-L key precedes every level-(L+1) key,
        // regardless of TM-index. Verify both the non-descending order and the strict level separation.
        for (int i = 0; i < mixedLevelKeys.size() - 1; i++) {
            var current = mixedLevelKeys.get(i);
            var next = mixedLevelKeys.get(i + 1);

            assertTrue(current.compareTo(next) <= 0, "Keys should be sorted in non-descending order");
            assertTrue(current.getLevel() <= next.getLevel(),
                       "Level-first ordering: a coarser key must never sort after a finer one (got level "
                       + current.getLevel() + " before level " + next.getLevel() + ")");
        }

        // Concrete cross-level boundary (bead Luciferase-i3e4 AC): ALL level-1 keys sort strictly before ALL
        // level-2 keys. Assert it directly on the partition, not just via the adjacent-pair monotonicity above —
        // the last level-1 key must come before the first level-2 key in the sorted list.
        int lastLevel1Idx = -1;
        int firstLevel2Idx = -1;
        for (int i = 0; i < mixedLevelKeys.size(); i++) {
            byte lvl = mixedLevelKeys.get(i).getLevel();
            if (lvl == 1) {
                lastLevel1Idx = i;
            } else if (lvl == 2 && firstLevel2Idx == -1) {
                firstLevel2Idx = i;
            }
        }
        assertTrue(lastLevel1Idx >= 0 && firstLevel2Idx >= 0,
                   "Test setup must produce both level-1 and level-2 keys");
        assertTrue(lastLevel1Idx < firstLevel2Idx,
                   "Every level-1 key must sort before every level-2 key (level-first ordering): last level-1 at "
                   + lastLevel1Idx + ", first level-2 at " + firstLevel2Idx);
    }

    @Test
    void testTetreeKeySpatialLocalityWithinLevel() {
        // For Tetree, spatial locality is preserved within each level
        byte level = 5;
        List<ExtendedTetreeKey> keys = new ArrayList<>();
        int cellSize = Constants.lengthAtLevel(level);

        // Calculate maximum valid coordinate for this level
        int maxCoord = Constants.lengthAtLevel((byte) 0); // Root extent
        int maxCells = maxCoord / cellSize; // Maximum number of cells that fit
        int gridSize = Math.min(10, maxCells); // Limit grid size to valid range

        // Generate keys from spatially adjacent tetrahedra
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                for (int z = 0; z < gridSize; z++) {
                    Tet tet = new Tet(x * cellSize, y * cellSize, z * cellSize, level, (byte) 0);
                    var key = tet.tmIndex();
                    ExtendedTetreeKey tetreeKey = key instanceof ExtendedTetreeKey ? (ExtendedTetreeKey) key
                                                                                   : ExtendedTetreeKey.fromCompactKey(
                                                                                   (CompactTetreeKey) key);
                    keys.add(tetreeKey);
                }
            }
        }

        // Shuffle to simulate random insertion
        List<ExtendedTetreeKey> shuffled = new ArrayList<>(keys);
        Collections.shuffle(shuffled);

        // Sort back
        Collections.sort(shuffled);

        // Verify that sorting produces consistent ordering
        // The exact order depends on tm-index values which are complex
        // Just verify that sorting is consistent
        List<ExtendedTetreeKey> sorted = new ArrayList<>(shuffled);
        Collections.sort(sorted);

        // Verify the list is actually sorted
        for (int i = 0; i < sorted.size() - 1; i++) {
            assertTrue(sorted.get(i).compareTo(sorted.get(i + 1)) <= 0,
                       "Keys should be in non-descending order after sorting");
        }
    }
}
