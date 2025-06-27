package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the childStandard() method that produces valid children according to standard refinement
 */
public class ChildStandardTest {
    
    private static int computeExpectedType(Tet tet) {
        if (tet.l() == 0) {
            return 0;
        }
        
        int currentType = 0; // Start at root type 0
        
        // Walk through each level computing type transformations
        for (int i = 0; i < tet.l(); i++) {
            // Extract coordinate bits at this level
            int bitPos = Constants.getMaxRefinementLevel() - 1 - i;
            int xBit = (tet.x() >> bitPos) & 1;
            int yBit = (tet.y() >> bitPos) & 1;
            int zBit = (tet.z() >> bitPos) & 1;
            
            // Child index from coordinate bits
            int childIdx = (zBit << 2) | (yBit << 1) | xBit;
            
            // Transform type based on child position
            currentType = Constants.TYPE_TO_TYPE_OF_CHILD[currentType][childIdx];
        }
        
        return currentType;
    }
    
    @Test
    public void testChildStandardProducesValidChildren() {
        System.out.println("Testing childStandard() method:\n");
        
        // Start from root
        Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        System.out.println("Root: " + root);
        // Note: valid() method doesn't exist anymore, but we know root type 0 is valid
        
        System.out.println("\nAll children using childStandard():");
        for (int i = 0; i < 8; i++) {
            Tet child = root.childStandard(i);
            System.out.printf("childStandard(%d): %s\n", i, child);
            // Check that the child type matches expected from standard refinement
            byte expectedType = Constants.TYPE_TO_TYPE_OF_CHILD[0][i];
            assertEquals(expectedType, child.type(), "Child " + i + " should have correct type");
        }
    }
    
    @Test
    public void testCompareAllChildMethods() {
        System.out.println("\n\nComparing all child generation methods:");
        
        Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        
        System.out.println("Child | child() type | childTM() type | childStandard() type");
        System.out.println("------|--------------|----------------|---------------------");
        
        for (int i = 0; i < 8; i++) {
            Tet childRegular = root.child(i);
            Tet childTM = root.childTM((byte) i);
            Tet childStandard = root.childStandard(i);
            
            System.out.printf("%5d | %12d | %14d | %19d\n",
                i,
                childRegular.type(),
                childTM.type(), 
                childStandard.type());
        }
    }
    
    @Test
    public void testMultiLevelStandardRefinement() {
        System.out.println("\n\nTesting multi-level standard refinement:");
        
        // Create a path using childStandard - should always be valid
        Tet current = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        int[] path = {7, 0, 5, 3, 2}; // Arbitrary deep path
        
        System.out.println("Starting from root: " + current);
        assertEquals(0, current.type(), "Root should be type 0");
        
        for (int i = 0; i < path.length; i++) {
            current = current.childStandard(path[i]);
            System.out.printf("After childStandard(%d): %s\n", path[i], current);
            // Verify type matches what we'd compute from coordinates
            int expectedType = computeExpectedType(current);
            assertEquals(expectedType, current.type(),
                String.format("Child at level %d should have correct type", current.l()));
        }
        
        // Also verify the path matches what we'd compute from coordinates
        System.out.println("\nVerifying path reconstruction from final coordinates:");
        System.out.println("Final tet: " + current);
        
        // Reconstruct the path
        for (int level = 0; level < current.l(); level++) {
            int bitPos = 21 - 1 - level;
            int xBit = (current.x() >> bitPos) & 1;
            int yBit = (current.y() >> bitPos) & 1; 
            int zBit = (current.z() >> bitPos) & 1;
            int childIdx = (zBit << 2) | (yBit << 1) | xBit;
            
            System.out.printf("Level %d: bits (%d,%d,%d) -> child %d (expected: %d)\n",
                level, xBit, yBit, zBit, childIdx, 
                level < path.length ? path[level] : -1);
            
            if (level < path.length) {
                assertEquals(path[level], childIdx, 
                    "Reconstructed path should match original");
            }
        }
    }
    
    @Test
    public void testChildStandardCoordinates() {
        System.out.println("\n\nVerifying childStandard() coordinate calculation:");
        
        Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        
        // Expected coordinates for each child at level 1
        // Cell size at level 1 = 2^20 = 1048576
        int cellSize = 1048576;
        int[][] expectedCoords = {
            {0, 0, 0},                          // Child 0: (0,0,0)
            {cellSize, 0, 0},                   // Child 1: (1,0,0)
            {0, cellSize, 0},                   // Child 2: (0,1,0)
            {cellSize, cellSize, 0},            // Child 3: (1,1,0)
            {0, 0, cellSize},                   // Child 4: (0,0,1)
            {cellSize, 0, cellSize},            // Child 5: (1,0,1)
            {0, cellSize, cellSize},            // Child 6: (0,1,1)
            {cellSize, cellSize, cellSize}     // Child 7: (1,1,1)
        };
        
        for (int i = 0; i < 8; i++) {
            Tet child = root.childStandard(i);
            assertEquals(expectedCoords[i][0], child.x(), "X coordinate mismatch for child " + i);
            assertEquals(expectedCoords[i][1], child.y(), "Y coordinate mismatch for child " + i);
            assertEquals(expectedCoords[i][2], child.z(), "Z coordinate mismatch for child " + i);
            
            // Also check the type matches CHILD_TYPES table
            byte expectedType = Constants.TYPE_TO_TYPE_OF_CHILD[0][i];
            assertEquals(expectedType, child.type(), "Type mismatch for child " + i);
        }
    }
}