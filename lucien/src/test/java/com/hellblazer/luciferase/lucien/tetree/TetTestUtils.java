package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Utility methods for generating valid test data for Tetree tests.
 * Ensures all generated Tet instances have properly quantized coordinates
 * and correct types based on standard refinement.
 */
public class TetTestUtils {
    
    /**
     * Generate a random valid Tet at the specified level.
     * This creates a Tet that follows standard refinement from type 0 root.
     * 
     * @param level the target level
     * @param random the random number generator
     * @return a valid Tet with properly quantized coordinates and correct type
     */
    public static Tet randomValidTet(byte level, Random random) {
        if (level == 0) {
            return new Tet(0, 0, 0, (byte) 0, (byte) 0);
        }
        
        // Generate a random refinement path
        int[] childPath = new int[level];
        for (int i = 0; i < level; i++) {
            childPath[i] = random.nextInt(8);
        }
        
        // Create tet from refinement path
        return fromRefinementPath(childPath);
    }
    
    /**
     * Generate a list of valid Tets at the specified level.
     * 
     * @param count number of Tets to generate
     * @param level the target level
     * @param random the random number generator
     * @return list of valid Tets
     */
    public static List<Tet> randomValidTets(int count, byte level, Random random) {
        List<Tet> tets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tets.add(randomValidTet(level, random));
        }
        return tets;
    }
    
    /**
     * Generate a valid Tet at a specific grid position.
     * This creates a Tet with the correct type based on standard refinement.
     * 
     * @param gridX grid X coordinate (will be multiplied by cell size)
     * @param gridY grid Y coordinate (will be multiplied by cell size)
     * @param gridZ grid Z coordinate (will be multiplied by cell size)
     * @param level the target level
     * @return a valid Tet at the specified grid position
     */
    public static Tet validTetAtGrid(int gridX, int gridY, int gridZ, byte level) {
        int cellSize = Constants.lengthAtLevel(level);
        int x = gridX * cellSize;
        int y = gridY * cellSize;
        int z = gridZ * cellSize;
        
        // Compute the correct type based on standard refinement from type 0 root
        int currentType = 0;
        for (int i = 0; i < level; i++) {
            int bitPos = 21 - 1 - i;
            int xBit = (x >> bitPos) & 1;
            int yBit = (y >> bitPos) & 1;
            int zBit = (z >> bitPos) & 1;
            
            int childIdx = (zBit << 2) | (yBit << 1) | xBit;
            currentType = Constants.TYPE_TO_TYPE_OF_CHILD[currentType][childIdx];
        }
        
        return new Tet(x, y, z, level, (byte) currentType);
    }
    
    /**
     * Generate all 8 corner Tets at a given level.
     * These are the Tets at the corners of the unit cube scaled to the level,
     * with types computed from standard refinement.
     * 
     * @param level the target level
     * @return list of 8 valid corner Tets
     */
    public static List<Tet> cornerTets(byte level) {
        if (level == 0) {
            return List.of(new Tet(0, 0, 0, (byte) 0, (byte) 0));
        }
        
        List<Tet> corners = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            int gridX = (i & 1);
            int gridY = ((i >> 1) & 1);
            int gridZ = ((i >> 2) & 1);
            corners.add(validTetAtGrid(gridX, gridY, gridZ, level));
        }
        return corners;
    }
    
    /**
     * Generate valid test data for TM-index tests.
     * Returns an array of Object[] suitable for parameterized tests.
     * Each entry contains: x, y, z, level, type (all guaranteed to be valid).
     * 
     * @return test data array
     */
    public static Object[][] validTmIndexTestData() {
        List<Object[]> testData = new ArrayList<>();
        
        // Add root
        testData.add(new Object[]{0, 0, 0, (byte) 0, (byte) 0});
        
        // Add some specific test cases at various levels
        // Level 1 - all 8 children of root
        for (int i = 0; i < 8; i++) {
            Tet child = fromRefinementPath(i);
            testData.add(new Object[]{
                child.x(), child.y(), child.z(), child.l(), child.type()
            });
        }
        
        // Level 2 - some representative cases
        testData.add(fromRefinementPathToArray(0, 0));  // Child 0 of child 0
        testData.add(fromRefinementPathToArray(0, 7));  // Child 7 of child 0
        testData.add(fromRefinementPathToArray(7, 0));  // Child 0 of child 7
        testData.add(fromRefinementPathToArray(7, 7));  // Child 7 of child 7
        
        // Add some random valid tets
        Random random = new Random(42); // Fixed seed for reproducibility
        for (byte level = 3; level <= 10; level++) {
            for (int i = 0; i < 3; i++) {
                Tet tet = randomValidTet(level, random);
                testData.add(new Object[]{
                    tet.x(), tet.y(), tet.z(), tet.l(), tet.type()
                });
            }
        }
        
        return testData.toArray(new Object[0][]);
    }
    
    /**
     * Check if coordinates are properly aligned to the grid at the given level.
     * 
     * @param x the x coordinate
     * @param y the y coordinate
     * @param z the z coordinate
     * @param level the level to check against
     * @return true if coordinates are aligned to the grid
     */
    public static boolean isAlignedToGrid(int x, int y, int z, byte level) {
        int cellSize = Constants.lengthAtLevel(level);
        return x % cellSize == 0 && y % cellSize == 0 && z % cellSize == 0;
    }
    
    /**
     * Generate a Tet that would be produced by refining from root.
     * This traces the refinement path from root to ensure valid type.
     * 
     * @param childPath array of child indices (0-7) representing the path from root
     * @return the Tet at the end of the refinement path
     */
    public static Tet fromRefinementPath(int... childPath) {
        Tet current = new Tet(0, 0, 0, (byte) 0, (byte) 0); // Start at root
        
        for (int childIdx : childPath) {
            if (childIdx < 0 || childIdx > 7) {
                throw new IllegalArgumentException("Child index must be 0-7: " + childIdx);
            }
            current = current.child(childIdx);
        }
        
        return current;
    }
    
    /**
     * Helper method to convert refinement path to test data array.
     */
    private static Object[] fromRefinementPathToArray(int... childPath) {
        Tet tet = fromRefinementPath(childPath);
        return new Object[]{tet.x(), tet.y(), tet.z(), tet.l(), tet.type()};
    }
}