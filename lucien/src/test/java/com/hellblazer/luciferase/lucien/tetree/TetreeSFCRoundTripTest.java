package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test SFC round-trip compliance to validate that the index() and tetrahedron() methods
 * are exact inverses, ensuring t8code parity for space-filling curve operations.
 *
 * <p><b>Critical Validation:</b></p>
 * These tests verify that the SFC implementation correctly follows t8code's algorithm:
 * <ul>
 *   <li>index() correctly encodes the path from root to tetrahedron</li>
 *   <li>tetrahedron() correctly reconstructs the path from index</li>
 *   <li>Round-trip: tet.index() → tetrahedron(index) → index() produces same result</li>
 *   <li>Level inference from indices works correctly</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class TetreeSFCRoundTripTest {

    /**
     * Test SFC round-trip for basic indices.
     * Validates that index ↔ tetrahedron conversion is bijective.
     */
    @Test
    void testBasicSFCRoundTrip() {
        // Test root tetrahedron
        assertEquals(0L, Tet.tetrahedron(0L).index(), "Root tetrahedron round-trip failed");

        // Test level 1 tetrahedra (indices 1-7)
        for (long index = 1; index <= 7; index++) {
            Tet tet = Tet.tetrahedron(index);
            long reconstructed = tet.index();
            assertEquals(index, reconstructed, "Level 1 round-trip failed for index " + index);
            assertEquals((byte) 1, tet.l(), "Level 1 tetrahedron should have level 1");
        }

        // Test some level 2 tetrahedra (indices 8-63)
        for (long index = 8; index <= 63; index += 7) { // Sample every 7th index
            Tet tet = Tet.tetrahedron(index);
            long reconstructed = tet.index();
            assertEquals(index, reconstructed, "Level 2 round-trip failed for index " + index);
            assertEquals((byte) 2, tet.l(), "Level 2 tetrahedron should have level 2");
        }
    }

    /**
     * Test SFC round-trip for a comprehensive range of indices.
     */
    @Test
    void testComprehensiveSFCRoundTrip() {
        // Test first 1000 indices for comprehensive coverage
        for (long index = 0; index < 1000; index++) {
            Tet tet = Tet.tetrahedron(index);
            long reconstructed = tet.index();
            assertEquals(index, reconstructed, "SFC round-trip failed for index " + index);
            
            // Also verify level calculation is correct
            byte expectedLevel = Tet.tetLevelFromIndex(index);
            assertEquals(expectedLevel, tet.l(), "Level mismatch for index " + index);
        }
    }

    /**
     * Test SFC round-trip for specific level boundaries.
     * Validates correct behavior at level transitions.
     */
    @Test
    void testLevelBoundarySFCRoundTrip() {
        // Level 0: index 0
        testRoundTripAtIndex(0L, (byte) 0);

        // Level 1: indices 1-7
        testRoundTripAtIndex(1L, (byte) 1);
        testRoundTripAtIndex(7L, (byte) 1);

        // Level 2: indices 8-63
        testRoundTripAtIndex(8L, (byte) 2);
        testRoundTripAtIndex(63L, (byte) 2);

        // Level 3: indices 64-511
        testRoundTripAtIndex(64L, (byte) 3);
        testRoundTripAtIndex(511L, (byte) 3);

        // Level 4: indices 512-4095
        testRoundTripAtIndex(512L, (byte) 4);
        testRoundTripAtIndex(4095L, (byte) 4);
    }

    /**
     * Test SFC round-trip for parent-child relationships.
     * Validates that child generation and parent calculation are consistent.
     */
    @Test
    void testParentChildSFCConsistency() {
        // Test at multiple levels
        for (byte level = 0; level < 5; level++) {
            // Test various parent types
            for (byte parentType = 0; parentType < 6; parentType++) {
                Tet parent = new Tet(0, 0, 0, level, parentType);
                
                // Skip if at max level
                if (level >= Constants.getMaxRefinementLevel()) {
                    continue;
                }

                // Test all 8 children
                for (int childIndex = 0; childIndex < 8; childIndex++) {
                    Tet child = parent.child(childIndex);
                    
                    // Verify child has correct parent
                    Tet reconstructedParent = child.parent();
                    assertEquals(parent, reconstructedParent, 
                                "Parent-child cycle failed for parent type " + parentType + 
                                " level " + level + " child " + childIndex);
                    
                    // Verify SFC indices are consistent
                    long parentIndex = parent.index();
                    long childSFCIndex = child.index();
                    
                    // Child index should be unique at its level (SFC property)
                    // Note: Child 0 can have same index as parent (interior child)
                    if (childIndex == 0) {
                        // Interior child (child 0) can have same index as parent
                        assertTrue(childSFCIndex >= parentIndex, 
                                  "Child 0 index should be >= parent index");
                    } else {
                        // Other children should have indices > parent index  
                        assertTrue(childSFCIndex > parentIndex, 
                                  "Child " + childIndex + " index should be > parent index for SFC ordering");
                    }
                    
                    // Round-trip test for both parent and child
                    assertEquals(parent.index(), Tet.tetrahedron(parentIndex).index(),
                                "Parent round-trip failed");
                    assertEquals(child.index(), Tet.tetrahedron(childSFCIndex).index(),
                                "Child round-trip failed");
                }
            }
        }
    }

    /**
     * Test SFC round-trip for randomly sampled indices across different levels.
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 7, 8, 15, 31, 63, 64, 127, 255, 511, 512, 1023, 2047, 4095})
    void testSpecificIndexRoundTrip(long index) {
        Tet tet = Tet.tetrahedron(index);
        long reconstructed = tet.index();
        assertEquals(index, reconstructed, "Round-trip failed for index " + index);
        
        // Verify level calculation
        byte expectedLevel = Tet.tetLevelFromIndex(index);
        assertEquals(expectedLevel, tet.l(), "Level calculation failed for index " + index);
    }

    /**
     * Test that SFC ordering is monotonic within levels.
     * Children should have indices in correct order.
     */
    @Test
    void testSFCOrderingMonotonicity() {
        // Test at level 1 (children of root)
        Tet root = Tet.tetrahedron(0L);
        long[] childIndices = new long[8];
        
        for (int i = 0; i < 8; i++) {
            Tet child = root.child(i);
            childIndices[i] = child.index();
        }
        
        // Verify children are in SFC order (0, 1, 2, 3, 4, 5, 6, 7)
        // Note: Child 0 has index 0 (same as root), which is correct for tetrahedral SFC
        for (int i = 0; i < 8; i++) {
            assertEquals(i, childIndices[i], "Child " + i + " should have index " + i);
        }
        
        // Test ordering at level 2 for a sample parent
        Tet parentL2 = Tet.tetrahedron(8L); // First tetrahedron at level 2
        long[] grandchildIndices = new long[8];
        
        for (int i = 0; i < 8; i++) {
            Tet child = parentL2.child(i);
            grandchildIndices[i] = child.index();
        }
        
        // Verify grandchildren indices are in ascending order
        for (int i = 1; i < 8; i++) {
            assertTrue(grandchildIndices[i] > grandchildIndices[i-1], 
                      "SFC ordering violated: child " + i + " index " + grandchildIndices[i] + 
                      " should be > child " + (i-1) + " index " + grandchildIndices[i-1]);
        }
    }

    /**
     * Test level inference from indices matches expected pattern.
     */
    @Test
    void testLevelInferenceFromIndices() {
        // Level 0: index 0
        assertEquals((byte) 0, Tet.tetLevelFromIndex(0L));
        
        // Level 1: indices 1-7 (3 bits)
        for (long i = 1; i <= 7; i++) {
            assertEquals((byte) 1, Tet.tetLevelFromIndex(i), "Level 1 inference failed for index " + i);
        }
        
        // Level 2: indices 8-63 (6 bits)
        for (long i = 8; i <= 63; i += 7) {
            assertEquals((byte) 2, Tet.tetLevelFromIndex(i), "Level 2 inference failed for index " + i);
        }
        
        // Level 3: indices 64-511 (9 bits)
        for (long i = 64; i <= 511; i += 63) {
            assertEquals((byte) 3, Tet.tetLevelFromIndex(i), "Level 3 inference failed for index " + i);
        }
    }

    /**
     * Test error handling for invalid indices.
     */
    @Test
    void testInvalidIndexHandling() {
        // Negative indices should throw exception
        assertThrows(IllegalArgumentException.class, () -> Tet.tetrahedron(-1L),
                    "Negative index should throw exception");
        
        assertThrows(IllegalArgumentException.class, () -> Tet.tetLevelFromIndex(-1L),
                    "Negative index for level calculation should throw exception");
    }

    /**
     * Helper method to test round-trip at a specific index and verify level.
     */
    private void testRoundTripAtIndex(long index, byte expectedLevel) {
        Tet tet = Tet.tetrahedron(index);
        assertEquals(expectedLevel, tet.l(), "Level mismatch for index " + index);
        assertEquals(index, tet.index(), "Round-trip failed for index " + index);
        assertEquals(expectedLevel, Tet.tetLevelFromIndex(index), "Level inference failed for index " + index);
    }
}