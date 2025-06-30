package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that Tet validation properly prevents invalid tetrahedra from being created.
 */
class TetValidationTest {

    @Test
    void testInvalidCoordinates() {
        // Negative coordinates should fail
        assertThrows(AssertionError.class, () -> new Tet(-1, 0, 0, (byte) 1, (byte) 0));
        assertThrows(AssertionError.class, () -> new Tet(0, -1, 0, (byte) 1, (byte) 0));
        assertThrows(AssertionError.class, () -> new Tet(0, 0, -1, (byte) 1, (byte) 0));
    }

    @Test
    void testInvalidLevel() {
        // Negative level
        assertThrows(AssertionError.class, () -> new Tet(0, 0, 0, (byte) -1, (byte) 0));

        // Level too high
        assertThrows(AssertionError.class, () -> new Tet(0, 0, 0, (byte) 22, (byte) 0));
    }

    @Test
    void testInvalidRootTetrahedron() {
        // Root tetrahedron must be at origin with type 0
        assertThrows(IllegalArgumentException.class, () -> Tet.createValidated(1, 0, 0, (byte) 0, (byte) 0));
        assertThrows(IllegalArgumentException.class, () -> Tet.createValidated(0, 1, 0, (byte) 0, (byte) 0));
        assertThrows(IllegalArgumentException.class, () -> Tet.createValidated(0, 0, 1, (byte) 0, (byte) 0));
        assertThrows(IllegalArgumentException.class, () -> Tet.createValidated(0, 0, 0, (byte) 0, (byte) 1));
    }

    @Test
    void testInvalidType() {
        // Type too low
        assertThrows(AssertionError.class, () -> new Tet(0, 0, 0, (byte) 1, (byte) -1));

        try {
            // Type too high
            new Tet(0, 0, 0, (byte) 1, (byte) 6);
            fail("Expected AssertionError");
        } catch (AssertionError e) {
            // expected
        }
    }

    @Test
    void testUnalignedCoordinates() {
        // Coordinates must be aligned to grid at the tetrahedron's level
        int cellSize = Constants.lengthAtLevel((byte) 5);

        // These coordinates are not aligned to the grid
        assertThrows(IllegalArgumentException.class, () -> Tet.createValidated(cellSize + 1, 0, 0, (byte) 5, (byte) 0));
        assertThrows(IllegalArgumentException.class, () -> Tet.createValidated(0, cellSize + 1, 0, (byte) 5, (byte) 0));
        assertThrows(IllegalArgumentException.class, () -> Tet.createValidated(0, 0, cellSize + 1, (byte) 5, (byte) 0));
    }

    @Test
    void testValidChildrenConstruction() {
        // Create a valid parent and verify all its children are valid
        Tet parent = new Tet(0, 0, 0, (byte) 1, (byte) 0);

        for (int i = 0; i < 8; i++) {
            Tet child = parent.child(i);
            assertNotNull(child);
            assertEquals(2, child.l());

            // Verify the child can be created directly
            Tet directChild = new Tet(child.x(), child.y(), child.z(), child.l(), child.type());
            assertEquals(child, directChild);
        }
    }

    @Test
    void testValidConstruction() {
        // Valid root tetrahedron
        Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        assertEquals(0, root.x());
        assertEquals(0, root.y());
        assertEquals(0, root.z());
        assertEquals(0, root.l());
        assertEquals(0, root.type());

        // Valid tetrahedron at level 1
        int cellSize = Constants.lengthAtLevel((byte) 1);
        Tet level1 = new Tet(0, 0, 0, (byte) 1, (byte) 0);
        assertNotNull(level1);

        // Valid tetrahedron at level 5
        cellSize = Constants.lengthAtLevel((byte) 5);
        Tet level5 = new Tet(cellSize, 0, 0, (byte) 5, (byte) 0);
        assertNotNull(level5);
    }

    @Test
    void testValidTypesAtGridPositions() {
        // Currently, our validation accepts any valid type (0-5) at valid grid positions
        // This is a limitation of the current validation approach

        // At level 1, position (0,0,0) with any valid type should be accepted
        for (byte validType = 0; validType <= 5; validType++) {
            Tet tet = Tet.createValidated(0, 0, 0, (byte) 1, validType);
            assertNotNull(tet);
            assertEquals(validType, tet.type());
        }

        // Invalid types should still be rejected
        assertThrows(AssertionError.class, () -> new Tet(0, 0, 0, (byte) 1, (byte) -1));
        assertThrows(AssertionError.class, () -> new Tet(0, 0, 0, (byte) 1, (byte) 6));
    }
}
