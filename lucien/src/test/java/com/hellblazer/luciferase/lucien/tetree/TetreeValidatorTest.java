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

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TetreeValidator.
 *
 * @author hal.hildebrand
 */
class TetreeValidatorTest {

    @BeforeEach
    void setUp() {
        // Ensure validation is enabled for tests
        TetreeValidator.setValidationEnabled(true);
    }

    @Test
    void testAssertions() {
        Tet validTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertDoesNotThrow(() -> TetreeValidator.assertValidTet(validTet));

        // Cannot create invalid tet with negative coordinates anymore
        assertThrows(AssertionError.class, () -> new Tet(-1, 0, 0, (byte) 0, (byte) 0));

        // Parent-child assertion
        Tet parent = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        Tet child = parent.child(0);
        assertDoesNotThrow(() -> TetreeValidator.assertValidParentChild(parent, child));

        int cellSize2 = Constants.lengthAtLevel((byte) 2);
        // Use smaller multiplier to stay within bounds
        Tet notChild = new Tet(cellSize2 * 2, cellSize2 * 2, cellSize2 * 2, (byte) 2, (byte) 0);
        assertThrows(AssertionError.class, () -> TetreeValidator.assertValidParentChild(parent, notChild));

        // Family assertion
        Tet[] family = new Tet[8];
        for (int i = 0; i < 8; i++) {
            family[i] = parent.child(i);
        }
        assertDoesNotThrow(() -> TetreeValidator.assertValidFamily(family));

        family[7] = family[0]; // Duplicate
        assertThrows(AssertionError.class, () -> TetreeValidator.assertValidFamily(family));
    }

    @Test
    void testCoordinatesOutOfBounds() {
        int maxCoord = Constants.lengthAtLevel((byte) 0);

        // At boundary should be invalid (coordinates must be < maxCoord)
        assertThrows(IllegalArgumentException.class, () -> new Tet(maxCoord, 0, 0, (byte) 0, (byte) 0));

        // Well beyond boundary should also fail in constructor
        assertThrows(IllegalArgumentException.class,
                     () -> new Tet(maxCoord * 2, maxCoord * 2, maxCoord * 2, (byte) 0, (byte) 0));
    }

    @Test
    void testDebugInfo() {
        Tet tet = new Tet(0, 0, 0, (byte) 2, (byte) 0);
        String debugInfo = TetreeValidator.getDebugInfo(tet);

        // Should contain basic info
        assertTrue(debugInfo.contains("Tetrahedron Debug Info"));
        assertTrue(debugInfo.contains("Vertices:"));
        assertTrue(debugInfo.contains("Parent:"));
        assertTrue(debugInfo.contains("Children:"));
        assertTrue(debugInfo.contains("Face Neighbors:"));

        // Should list 4 vertices
        assertTrue(debugInfo.contains("v0:"));
        assertTrue(debugInfo.contains("v1:"));
        assertTrue(debugInfo.contains("v2:"));
        assertTrue(debugInfo.contains("v3:"));

        // Should list 8 children
        for (int i = 0; i < 8; i++) {
            assertTrue(debugInfo.contains("Child " + i + ":"));
        }

        // Should list 4 face neighbors
        for (int i = 0; i < 4; i++) {
            assertTrue(debugInfo.contains("Face " + i + ":"));
        }
    }

    @Test
    void testDescribeTet() {
        int cellSize5 = Constants.lengthAtLevel((byte) 5);
        Tet tet = new Tet(cellSize5, cellSize5 * 2, cellSize5 * 3, (byte) 5, (byte) 3);
        String description = TetreeValidator.describeTet(tet);

        assertTrue(description.contains("x=" + cellSize5));
        assertTrue(description.contains("y=" + (cellSize5 * 2)));
        assertTrue(description.contains("z=" + (cellSize5 * 3)));
        assertTrue(description.contains("level=5"));
        assertTrue(description.contains("type=3"));
        assertTrue(description.contains("tmIndex="));
    }

    @Test
    void testFamilyValidation() {
        Tet parent = new Tet(0, 0, 0, (byte) 1, (byte) 0);

        // Create valid family
        Tet[] validFamily = new Tet[8];
        for (int i = 0; i < 8; i++) {
            validFamily[i] = parent.child(i);
        }
        assertTrue(TetreeValidator.isValidFamily(validFamily));

        // Invalid - wrong size
        Tet[] wrongSize = new Tet[7];
        System.arraycopy(validFamily, 0, wrongSize, 0, 7);
        assertFalse(TetreeValidator.isValidFamily(wrongSize));

        // Invalid - duplicate child
        Tet[] duplicates = Arrays.copyOf(validFamily, 8);
        duplicates[7] = duplicates[0]; // Duplicate first child
        assertFalse(TetreeValidator.isValidFamily(duplicates));

        // Invalid - different parents
        Tet otherParent = new Tet(Constants.lengthAtLevel((byte) 1), 0, 0, (byte) 1, (byte) 0);
        Tet[] mixedFamily = Arrays.copyOf(validFamily, 8);
        mixedFamily[4] = otherParent.child(0);
        assertFalse(TetreeValidator.isValidFamily(mixedFamily));

        // Invalid - different levels
        Tet[] mixedLevels = Arrays.copyOf(validFamily, 8);
        mixedLevels[3] = new Tet(0, 0, 0, (byte) 3, (byte) 0);
        assertFalse(TetreeValidator.isValidFamily(mixedLevels));
    }

    @Test
    void testInvalidCoordinates() {
        // The Tet constructor now validates coordinates, so negative coordinates throw exceptions
        assertThrows(AssertionError.class, () -> new Tet(-1, 0, 0, (byte) 0, (byte) 0));
        assertThrows(AssertionError.class, () -> new Tet(0, -10, 0, (byte) 0, (byte) 0));
        assertThrows(AssertionError.class, () -> new Tet(0, 0, -100, (byte) 0, (byte) 0));
    }

    @Test
    void testInvalidLevel() {
        // Since Tet constructor validates, we need to test differently
        // Test that constructor throws for invalid levels
        assertThrows(AssertionError.class, () -> {
            new Tet(0, 0, 0, (byte) -1, (byte) 0);
        });

        // Level exceeding maximum
        byte maxLevel = Constants.getMaxRefinementLevel();
        assertThrows(AssertionError.class, () -> {
            new Tet(0, 0, 0, (byte) (maxLevel + 1), (byte) 0);
        });
    }

    @Test
    void testInvalidType() {
        // Since Tet constructor validates, test that it throws for invalid types
        assertThrows(AssertionError.class, () -> {
            new Tet(0, 0, 0, (byte) 0, (byte) -1);
        });

        // Type >= 6
        assertThrows(AssertionError.class, () -> {
            new Tet(0, 0, 0, (byte) 0, (byte) 6);
        });

        // Type way out of bounds
        assertThrows(AssertionError.class, () -> {
            new Tet(0, 0, 0, (byte) 0, (byte) 100);
        });
    }

    @Test
    void testMisalignedCoordinates() {
        // Coordinates not aligned to grid at level 3 should throw exception in constructor
        int cellSize = Constants.lengthAtLevel((byte) 3);
        assertThrows(IllegalArgumentException.class, () -> {
            new Tet(cellSize + 1, cellSize * 2, cellSize * 3, (byte) 3, (byte) 0);
        }, "Misaligned coordinates should throw exception");

        // Properly aligned should be valid
        Tet aligned = new Tet(cellSize, cellSize * 2, cellSize * 3, (byte) 3, (byte) 0);
        assertTrue(TetreeValidator.isValidTet(aligned));
    }

    @Test
    void testNeighborValidation() {
        Tet tet1 = new Tet(0, 0, 0, (byte) 3, (byte) 0);

        // Valid face neighbors
        for (int face = 0; face < TetreeConnectivity.FACES_PER_TET; face++) {
            Tet.FaceNeighbor neighbor = tet1.faceNeighbor(face);
            // Skip if neighbor is at boundary (null)
            if (neighbor != null) {
                assertTrue(TetreeValidator.isValidNeighbor(tet1, neighbor.tet()));
            }
        }

        // Invalid - too far apart in levels
        Tet farLevel = new Tet(0, 0, 0, (byte) 6, (byte) 0);
        assertFalse(TetreeValidator.isValidNeighbor(tet1, farLevel));

        // Invalid - spatially distant (reduce coordinates to stay within bounds)
        int cellSize = Constants.lengthAtLevel((byte) 3);
        Tet distant = new Tet(cellSize * 5, cellSize * 5, cellSize * 5, (byte) 3, (byte) 0);
        assertFalse(TetreeValidator.isValidNeighbor(tet1, distant));
    }

    @Test
    void testParentChildValidation() {
        Tet parent = new Tet(0, 0, 0, (byte) 2, (byte) 0);

        // Valid children
        for (int i = 0; i < TetreeConnectivity.CHILDREN_PER_TET; i++) {
            Tet child = parent.child(i);
            assertTrue(TetreeValidator.isValidParentChild(parent, child));
        }

        // Invalid - same level
        Tet sameLevel = new Tet(0, 0, 0, (byte) 2, (byte) 1);
        assertFalse(TetreeValidator.isValidParentChild(parent, sameLevel));

        // Invalid - wrong level
        Tet wrongLevel = new Tet(0, 0, 0, (byte) 4, (byte) 0);
        assertFalse(TetreeValidator.isValidParentChild(parent, wrongLevel));

        // Invalid - not a child (reduce coordinates to stay within bounds)
        int cellSize = Constants.lengthAtLevel((byte) 3);
        Tet notChild = new Tet(cellSize * 5, cellSize * 5, cellSize * 5, (byte) 3, (byte) 0);
        assertFalse(TetreeValidator.isValidParentChild(parent, notChild));
    }

    @Test
    void testValidTet() {
        // Valid root tetrahedron
        Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertTrue(TetreeValidator.isValidTet(root));

        // Valid tetrahedron at level 1
        Tet tet1 = new Tet(0, 0, 0, (byte) 1, (byte) 2);
        assertTrue(TetreeValidator.isValidTet(tet1));

        // Valid tetrahedron at deeper level
        int cellSize = Constants.lengthAtLevel((byte) 5);
        Tet tet5 = new Tet(cellSize * 3, cellSize * 2, cellSize * 4, (byte) 5, (byte) 4);
        assertTrue(TetreeValidator.isValidTet(tet5));
    }

    @Test
    void testValidationException() {
        TetreeValidator.ValidationException ex1 = new TetreeValidator.ValidationException("Test error");
        assertEquals("Test error", ex1.getMessage());

        Exception cause = new RuntimeException("Root cause");
        TetreeValidator.ValidationException ex2 = new TetreeValidator.ValidationException("Wrapped error", cause);
        assertEquals("Wrapped error", ex2.getMessage());
        assertEquals(cause, ex2.getCause());
    }

    @Test
    void testValidationResultToString() {
        TetreeValidator.ValidationResult valid = TetreeValidator.ValidationResult.valid();
        assertEquals("ValidationResult: VALID", valid.toString());

        List<String> errors = Arrays.asList("Error 1", "Error 2", "Error 3");
        TetreeValidator.ValidationResult invalid = TetreeValidator.ValidationResult.invalid(errors);
        String resultString = invalid.toString();
        assertTrue(resultString.contains("ValidationResult: INVALID"));
        assertTrue(resultString.contains("Error 1"));
        assertTrue(resultString.contains("Error 2"));
        assertTrue(resultString.contains("Error 3"));
    }

    @Test
    void testValidationToggle() {
        assertTrue(TetreeValidator.isValidationEnabled());

        TetreeValidator.setValidationEnabled(false);
        assertFalse(TetreeValidator.isValidationEnabled());

        // When validation is disabled, the validator should return true even for
        // tets that would normally be invalid. However, we can't create an actually
        // invalid Tet since the constructor validates, so we just test that validation
        // is properly disabled.
        Tet validTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertTrue(TetreeValidator.isValidTet(validTet));

        TetreeValidator.setValidationEnabled(true);
        assertTrue(TetreeValidator.isValidationEnabled());
    }
}
