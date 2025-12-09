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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive edge case tests for MortonKey focusing on boundary conditions at level 0 (root), level 21 (max depth),
 * negative coordinates, and parent-child transitions.
 *
 * @author hal.hildebrand
 */
class MortonKeyEdgeCaseTest {

    // ===== Phase 1: Level 0 Edge Cases =====

    @Test
    @DisplayName("Level 0: fromCoordinates at root level")
    void testLevel0_FromCoordinates() {
        // Test origin at level 0
        MortonKey origin = MortonKey.fromCoordinates(0, 0, 0, (byte) 0);
        assertNotNull(origin);
        assertEquals(0, origin.getLevel(), "Origin at level 0 should have level 0");
        assertEquals(0L, origin.getMortonCode(), "Origin should have Morton code 0");
        assertTrue(origin.isValid(), "Level 0 origin should be valid");

        // Test coordinates near 2^21 boundary at level 0
        int maxCoord = Constants.MAX_COORD; // 2^21 - 1 = 2,097,151
        int level0Length = Constants.lengthAtLevel((byte) 0); // 2^21 = 2,097,152

        // Coordinates within valid range
        MortonKey nearMax = MortonKey.fromCoordinates(maxCoord / 2, maxCoord / 2, maxCoord / 2, (byte) 0);
        assertNotNull(nearMax);
        assertTrue(nearMax.isValid(), "Level 0 key with coordinates near max should be valid");

        // At level 0, any coordinate maps to origin due to quantization
        // Level 0 cell size (2^21) is larger than max coordinate (2^21 - 1)
        // So all coordinates [0, 2^21-1] quantize to 0
        assertEquals(0L, nearMax.getMortonCode(),
                     "At level 0, all coordinates < 2^21 quantize to origin (Morton 0)");
    }

    @Test
    @DisplayName("Level 0: getChild from root")
    void testLevel0_GetChildFromRoot() {
        MortonKey root = MortonKey.getRoot();
        assertEquals(0, root.getLevel(), "Root should be at level 0");
        assertEquals(0L, root.getMortonCode(), "Root should have Morton code 0");

        // Get all 8 children of root
        Set<Long> childMortonCodes = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            MortonKey child = root.getChild(i);
            assertNotNull(child, "Root should have child " + i);
            assertEquals(1, child.getLevel(), "Root children should be at level 1");
            assertTrue(child.isValid(), "Root child " + i + " should be valid");

            // Verify Morton codes are distinct
            assertTrue(childMortonCodes.add(child.getMortonCode()),
                       "Child " + i + " should have unique Morton code");
        }

        // Verify we got exactly 8 unique Morton codes
        assertEquals(8, childMortonCodes.size(), "Root should have 8 children with distinct Morton codes");

        // Test getChildren() convenience method
        MortonKey[] children = root.getChildren();
        assertNotNull(children, "Root.getChildren() should not return null");
        assertEquals(8, children.length, "Root should have 8 children");

        // Verify all children are at level 1 and valid
        for (int i = 0; i < 8; i++) {
            assertEquals(1, children[i].getLevel(), "All root children should be at level 1");
            assertTrue(children[i].isValid(), "All root children should be valid");
        }
    }

    @Test
    @DisplayName("Level 0: parent from level 1 to root")
    void testLevel0_ParentToRoot() {
        MortonKey root = MortonKey.getRoot();

        // Get all children and verify they parent back to root
        for (int i = 0; i < 8; i++) {
            MortonKey child = root.getChild(i);
            MortonKey parent = child.parent();

            assertNotNull(parent, "Level 1 child should have a parent");
            assertEquals(root.getMortonCode(), parent.getMortonCode(),
                         "Child " + i + " should parent back to root Morton code");
            assertEquals(0, parent.getLevel(), "Parent of level 1 should be level 0");
        }

        // Verify root has no parent
        assertNull(root.parent(), "Root (level 0) should have no parent");
    }

    @Test
    @DisplayName("Level 0: cell boundaries")
    void testLevel0_CellBoundaries() {
        byte level0 = 0;
        int level0Length = Constants.lengthAtLevel(level0);

        // Level 0 cell size should be 2^21
        assertEquals(1 << MortonCurve.MAX_REFINEMENT_LEVEL, level0Length, "Level 0 cell size should be 2^21");
        assertEquals(2097152, level0Length, "Level 0 cell size should be 2,097,152");

        // This exceeds the maximum encodable coordinate
        int maxCoord = Constants.MAX_COORD; // 2^21 - 1 = 2,097,151
        assertTrue(level0Length > maxCoord, "Level 0 cell size exceeds maximum coordinate");

        // Verify that the entire coordinate space [0, MAX_COORD] is covered by level 0
        // Since level 0 cell size > MAX_COORD, there's effectively one cell at origin
        MortonKey atOrigin = MortonKey.fromCoordinates(0, 0, 0, level0);
        MortonKey atMax = MortonKey.fromCoordinates(maxCoord, maxCoord, maxCoord, level0);

        // Both should map to Morton code 0 due to quantization
        assertEquals(0L, atOrigin.getMortonCode(), "Origin at level 0 should have Morton 0");
        assertEquals(0L, atMax.getMortonCode(), "MAX_COORD at level 0 should quantize to Morton 0");
    }

    @Test
    @DisplayName("Level 0: validation")
    void testLevel0_Validation() {
        MortonKey root = MortonKey.getRoot();
        assertTrue(root.isValid(), "Root should be valid");

        // Test various level 0 keys
        MortonKey level0Key = new MortonKey(0L, (byte) 0);
        assertTrue(level0Key.isValid(), "Level 0 key should be valid");

        // Test that level 0 is within valid range
        assertTrue(0 >= 0 && 0 <= Constants.getMaxRefinementLevel(), "Level 0 should be in valid range");
    }

    @Test
    @DisplayName("Level 0: quantization does not overflow")
    void testLevel0_QuantizationNoOverflow() {
        byte level0 = 0;
        int maxCoord = Constants.MAX_COORD;

        // Test coordinates at and near the boundary
        Point3f[] testPoints = { new Point3f(0, 0, 0), new Point3f(maxCoord, 0, 0), new Point3f(0, maxCoord, 0),
                                 new Point3f(0, 0, maxCoord), new Point3f(maxCoord, maxCoord, maxCoord),
                                 new Point3f(maxCoord / 2, maxCoord / 2, maxCoord / 2) };

        for (Point3f point : testPoints) {
            // Should not throw or overflow
            assertDoesNotThrow(() -> {
                long morton = Constants.calculateMortonIndex(point, level0);
                // All should quantize to 0 at level 0
                assertEquals(0L, morton,
                             "At level 0, coordinates [0, MAX_COORD] should quantize to Morton 0: " + point);
            }, "Quantization should not overflow for point: " + point);
        }
    }

    // ===== Phase 2: Level 21 Edge Cases =====

    @Test
    @DisplayName("Level 21: getChild returns null")
    void testLevel21_GetChild_ReturnsNull() {
        byte maxLevel = MortonCurve.MAX_REFINEMENT_LEVEL;

        // Create a level 21 key - need small coordinates to get level 21
        // Constants.toLevel() infers level from coordinate magnitude
        MortonKey level21Key = MortonKey.fromCoordinates(1, 1, 1, maxLevel);
        assertEquals(maxLevel, level21Key.getLevel(), "Should be at max level");

        // Attempting to get children should return null (cannot subdivide further)
        for (int i = 0; i < 8; i++) {
            assertNull(level21Key.getChild(i), "Level 21 key should not have child " + i);
        }
    }

    @Test
    @DisplayName("Level 21: getChildren returns null")
    void testLevel21_GetChildren_ReturnsNull() {
        byte maxLevel = MortonCurve.MAX_REFINEMENT_LEVEL;

        // Create a level 21 key - need small coordinates to get level 21
        MortonKey level21Key = MortonKey.fromCoordinates(7, 7, 7, maxLevel);
        assertEquals(maxLevel, level21Key.getLevel(), "Should be at max level");

        // getChildren() should return null at max level
        assertNull(level21Key.getChildren(), "Level 21 key should not have children array");
    }

    @Test
    @DisplayName("Level 21: parent chain to root")
    void testLevel21_ParentChain() {
        byte maxLevel = MortonCurve.MAX_REFINEMENT_LEVEL;

        // Create a level 21 key - need small coordinates to get level 21
        MortonKey current = MortonKey.fromCoordinates(3, 3, 3, maxLevel);
        assertEquals(maxLevel, current.getLevel(), "Should start at max level");

        // Walk parent chain from level 21 → 0
        Set<Byte> levelsEncountered = new HashSet<>();
        levelsEncountered.add(current.getLevel());

        while (current.parent() != null) {
            current = current.parent();
            levelsEncountered.add(current.getLevel());
            assertTrue(current.isValid(), "All parents should be valid");
        }

        // Should have encountered all levels from 0 to 21
        assertEquals(22, levelsEncountered.size(), "Should encounter all 22 levels (0-21)");
        for (byte level = 0; level <= maxLevel; level++) {
            assertTrue(levelsEncountered.contains(level), "Should encounter level " + level);
        }

        // Final parent should be at level 0
        assertEquals(0, current.getLevel(), "Parent chain should end at level 0");
        assertNull(current.parent(), "Level 0 should have no parent");
    }

    @Test
    @DisplayName("Level 21: maximum coordinates")
    void testLevel21_MaxCoordinates() {
        byte maxLevel = MortonCurve.MAX_REFINEMENT_LEVEL;
        int maxCoord = Constants.MAX_COORD;

        // Test with maximum valid coordinates
        // Note: toLevel() infers level from coordinate magnitude, so MAX_COORD won't be level 21
        // But we can verify it encodes/decodes correctly
        MortonKey maxKey = MortonKey.fromCoordinates(maxCoord, maxCoord, maxCoord, maxLevel);
        assertNotNull(maxKey, "Should be able to create key at MAX_COORD");
        assertTrue(maxKey.isValid(), "Max coordinate key should be valid");

        // Morton code should be non-negative
        assertTrue(maxKey.getMortonCode() >= 0, "Morton code should be non-negative");

        // Should encode and decode correctly
        int[] decoded = com.hellblazer.luciferase.geometry.MortonCurve.decode(maxKey.getMortonCode());
        assertEquals(maxCoord, decoded[0], "X should decode to MAX_COORD");
        assertEquals(maxCoord, decoded[1], "Y should decode to MAX_COORD");
        assertEquals(maxCoord, decoded[2], "Z should decode to MAX_COORD");
    }

    @Test
    @DisplayName("Level 21: all octants from level 20")
    void testLevel21_AllOctants() {
        byte level20 = (byte) (MortonCurve.MAX_REFINEMENT_LEVEL - 1);
        byte level21 = MortonCurve.MAX_REFINEMENT_LEVEL;

        // Create a level 20 key - need small coordinates to get level 20
        MortonKey level20Key = MortonKey.fromCoordinates(8, 8, 8, level20);
        assertEquals(level20, level20Key.getLevel(), "Should be at level 20");

        // Get all 8 children (should be at level 21)
        MortonKey[] children = level20Key.getChildren();
        assertNotNull(children, "Level 20 should have children");
        assertEquals(8, children.length, "Should have 8 children");

        Set<Long> mortonCodes = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            MortonKey child = children[i];
            assertNotNull(child, "Child " + i + " should not be null");
            assertEquals(level21, child.getLevel(), "Child " + i + " should be at level 21");
            assertTrue(child.isValid(), "Child " + i + " should be valid");

            // Verify uniqueness
            assertTrue(mortonCodes.add(child.getMortonCode()), "Child " + i + " should have unique Morton code");

            // Verify these level 21 children cannot subdivide further
            assertNull(child.getChild(0), "Level 21 child should not have children");
        }

        assertEquals(8, mortonCodes.size(), "Should have 8 unique Morton codes");
    }

    @Test
    @DisplayName("Level > 21: invalid")
    void testInvalidLevel_Above21() {
        byte invalidLevel = (byte) (MortonCurve.MAX_REFINEMENT_LEVEL + 1); // 22

        // Create a key with level > 21
        MortonKey invalidKey = new MortonKey(12345L, invalidLevel);

        // Should be marked as invalid
        assertFalse(invalidKey.isValid(), "Level > 21 should be invalid");

        // Test with even higher level
        MortonKey veryInvalidKey = new MortonKey(0L, (byte) 100);
        assertFalse(veryInvalidKey.isValid(), "Level 100 should be invalid");
    }

    // ===== Phase 4: Boundary Transitions =====

    @Test
    @DisplayName("Root children: all octants unique")
    void testRootChildren_AllOctants() {
        MortonKey root = MortonKey.getRoot();
        MortonKey[] children = root.getChildren();

        assertNotNull(children, "Root should have children");
        assertEquals(8, children.length, "Root should have 8 children");

        Set<Long> mortonCodes = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            assertEquals(1, children[i].getLevel(), "All root children should be at level 1");
            assertTrue(children[i].isValid(), "All root children should be valid");
            assertTrue(mortonCodes.add(children[i].getMortonCode()),
                       "Child " + i + " should have unique Morton code");
        }

        assertEquals(8, mortonCodes.size(), "Should have 8 distinct Morton codes");
    }

    @Test
    @DisplayName("Octant boundaries: distinct Morton codes")
    void testOctantBoundaries_Distinct() {
        // Test at level 10 (mid-range)
        // Need coordinates that will actually result in level 10 after toLevel() inference
        byte testLevel = 10;
        MortonKey testKey = MortonKey.fromCoordinates(2048, 2048, 2048, testLevel);

        MortonKey[] children = testKey.getChildren();
        assertNotNull(children, "Should have children");

        // Verify all octants 0-7 produce distinct Morton codes
        Set<Long> mortonCodes = new HashSet<>();
        for (int octant = 0; octant < 8; octant++) {
            MortonKey child = children[octant];
            assertTrue(mortonCodes.add(child.getMortonCode()),
                       "Octant " + octant + " should have unique Morton code");

            // Verify child is at next level
            byte expectedChildLevel = (byte) (testKey.getLevel() + 1);
            assertEquals(expectedChildLevel, child.getLevel(), 
                         "Child should be at level " + expectedChildLevel + " (parent is " + testKey.getLevel() + ")");
        }
    }

    @Test
    @DisplayName("Parent-child round trip: all levels")
    void testParentChildRoundTrip_AllLevels() {
        // Test parent-child round trip for each level [0, 20]
        // (Level 21 cannot have children)
        for (byte level = 0; level < MortonCurve.MAX_REFINEMENT_LEVEL; level++) {
            MortonKey parent = MortonKey.fromCoordinates(1000 + level * 10, 1000 + level * 10, 1000 + level * 10,
                                                         level);

            // Get first child
            MortonKey child = parent.getChild(0);
            assertNotNull(child, "Level " + level + " should have children");

            // Get parent of child
            MortonKey roundTripParent = child.parent();
            assertNotNull(roundTripParent, "Child should have parent");

            // Verify round trip
            assertEquals(parent.getMortonCode(), roundTripParent.getMortonCode(),
                         "Round trip parent should match original at level " + level);
            assertEquals(parent.getLevel(), roundTripParent.getLevel(),
                         "Round trip parent level should match at level " + level);
        }
    }

    @Test
    @DisplayName("getChild: all octants valid")
    void testGetChild_AllOctants() {
        byte testLevel = 10;
        MortonKey testKey = MortonKey.fromCoordinates(8192, 8192, 8192, testLevel);

        // Test all octants 0-7
        for (int octant = 0; octant < 8; octant++) {
            MortonKey child = testKey.getChild(octant);
            assertNotNull(child, "Octant " + octant + " should exist");
            assertEquals(testLevel + 1, child.getLevel(), "Child should be at next level");
            assertTrue(child.isValid(), "Child " + octant + " should be valid");
        }

        // Test invalid octant indices
        assertThrows(IllegalArgumentException.class, () -> testKey.getChild(-1), "Octant -1 should throw");
        assertThrows(IllegalArgumentException.class, () -> testKey.getChild(8), "Octant 8 should throw");
        assertThrows(IllegalArgumentException.class, () -> testKey.getChild(100), "Octant 100 should throw");
    }

    @Test
    @DisplayName("getChildren: uniqueness at all levels")
    void testGetChildren_Uniqueness() {
        // Test uniqueness of getChildren() at various levels
        // Note: Can't easily test all levels 0-20 because fromCoordinates() infers level from coordinates
        // Instead, test that children are unique regardless of parent level
        for (byte requestedLevel = 0; requestedLevel < MortonCurve.MAX_REFINEMENT_LEVEL; requestedLevel++) {
            MortonKey key = MortonKey.fromCoordinates(2000, 2000, 2000, requestedLevel);
            MortonKey[] children = key.getChildren();

            // Skip if this key is already at max level
            if (children == null) {
                continue;
            }

            byte actualLevel = key.getLevel();
            assertNotNull(children, "Level " + actualLevel + " should have children");
            assertEquals(8, children.length, "Should have 8 children at level " + actualLevel);

            // Verify all children are unique
            Set<Long> mortonCodes = new HashSet<>();
            for (MortonKey child : children) {
                assertTrue(mortonCodes.add(child.getMortonCode()),
                           "All children should have unique Morton codes at level " + actualLevel);
                assertEquals(actualLevel + 1, child.getLevel(), "Child should be at level " + (actualLevel + 1));
            }

            assertEquals(8, mortonCodes.size(), "Should have 8 unique Morton codes at level " + actualLevel);
        }
    }

    // ===== Phase 3: Negative Coordinates (will be implemented after adding validation) =====

    @Test
    @DisplayName("Negative coordinates: fromCoordinates throws exception")
    void testNegativeCoordinates_ThrowsException() {
        // Test all negative axis combinations
        assertThrows(IllegalArgumentException.class, () -> MortonKey.fromCoordinates(-1, 0, 0, (byte) 10),
                     "Negative X should throw");

        assertThrows(IllegalArgumentException.class, () -> MortonKey.fromCoordinates(0, -1, 0, (byte) 10),
                     "Negative Y should throw");

        assertThrows(IllegalArgumentException.class, () -> MortonKey.fromCoordinates(0, 0, -1, (byte) 10),
                     "Negative Z should throw");

        assertThrows(IllegalArgumentException.class, () -> MortonKey.fromCoordinates(-100, -100, -100, (byte) 10),
                     "All negative should throw");

        assertThrows(IllegalArgumentException.class, () -> MortonKey.fromCoordinates(-1, 100, 100, (byte) 10),
                     "Mixed negative X should throw");

        assertThrows(IllegalArgumentException.class, () -> MortonKey.fromCoordinates(100, -1, 100, (byte) 10),
                     "Mixed negative Y should throw");

        assertThrows(IllegalArgumentException.class, () -> MortonKey.fromCoordinates(100, 100, -1, (byte) 10),
                     "Mixed negative Z should throw");
    }

    @Test
    @DisplayName("Negative coordinates: SFC range throws exception")
    void testSFCRange_NegativeCenter() {
        // Test negative center coordinates
        assertThrows(IllegalArgumentException.class, () -> MortonKey.estimateSFCRange(new Point3f(-100, 0, 0), 50.0f),
                     "Negative X center should throw");

        assertThrows(IllegalArgumentException.class, () -> MortonKey.estimateSFCRange(new Point3f(0, -100, 0), 50.0f),
                     "Negative Y center should throw");

        assertThrows(IllegalArgumentException.class, () -> MortonKey.estimateSFCRange(new Point3f(0, 0, -100), 50.0f),
                     "Negative Z center should throw");

        assertThrows(IllegalArgumentException.class,
                     () -> MortonKey.estimateSFCRange(new Point3f(-10, -10, -10), 50.0f),
                     "All negative center should throw");
    }

    @Test
    @DisplayName("Negative coordinates: calculateMortonIndex throws exception")
    void testCalculateMortonIndex_NegativePoint() {
        // Test negative point coordinates
        assertThrows(IllegalArgumentException.class,
                     () -> Constants.calculateMortonIndex(new Point3f(-10, 0, 0), (byte) 5),
                     "Negative X point should throw");

        assertThrows(IllegalArgumentException.class,
                     () -> Constants.calculateMortonIndex(new Point3f(0, -10, 0), (byte) 5),
                     "Negative Y point should throw");

        assertThrows(IllegalArgumentException.class,
                     () -> Constants.calculateMortonIndex(new Point3f(0, 0, -10), (byte) 5),
                     "Negative Z point should throw");

        assertThrows(IllegalArgumentException.class,
                     () -> Constants.calculateMortonIndex(new Point3f(-100, -100, -100), (byte) 5),
                     "All negative point should throw");
    }
}
