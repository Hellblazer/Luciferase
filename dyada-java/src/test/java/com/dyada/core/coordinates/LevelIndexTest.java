package com.dyada.core.coordinates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for LevelIndex functionality.
 */
@DisplayName("LevelIndex Tests")
class LevelIndexTest {

    @Test
    @DisplayName("Create 2D level index")
    void create2DLevelIndex() {
        var levels = new byte[]{3, 4};
        var indices = new long[]{2, 7};
        var levelIndex = new LevelIndex(levels, indices);
        
        assertEquals(2, levelIndex.dimensions());
        assertEquals(3, levelIndex.getLevel(0));
        assertEquals(4, levelIndex.getLevel(1));
        assertEquals(2, levelIndex.getIndex(0));
        assertEquals(7, levelIndex.getIndex(1));
    }

    @Test
    @DisplayName("Create using static factory method")
    void createUsingStaticFactory() {
        var levels = new byte[]{2, 3, 4};
        var indices = new long[]{1, 3, 5};
        var levelIndex = LevelIndex.of(levels, indices);
        
        assertEquals(3, levelIndex.dimensions());
        assertArrayEquals(levels, levelIndex.getLevels());
        assertArrayEquals(indices, levelIndex.getIndices());
    }

    @Test
    @DisplayName("Create uniform level index")
    void createUniformLevelIndex() {
        var levelIndex = LevelIndex.uniform(3, (byte) 5);
        
        assertEquals(3, levelIndex.dimensions());
        for (int i = 0; i < 3; i++) {
            assertEquals(5, levelIndex.getLevel(i));
            assertEquals(0, levelIndex.getIndex(i));
        }
    }

    @Test
    @DisplayName("Create origin level index")
    void createOriginLevelIndex() {
        var levelIndex = LevelIndex.origin(4);
        
        assertEquals(4, levelIndex.dimensions());
        for (int i = 0; i < 4; i++) {
            assertEquals(0, levelIndex.getLevel(i));
            assertEquals(0, levelIndex.getIndex(i));
        }
    }

    @Test
    @DisplayName("Null arrays throw exception")
    void nullArraysThrowException() {
        var levels = new byte[]{1, 2};
        var indices = new long[]{0, 1};
        
        assertThrows(NullPointerException.class, 
            () -> new LevelIndex(null, indices));
        assertThrows(NullPointerException.class, 
            () -> new LevelIndex(levels, null));
    }

    @Test
    @DisplayName("Mismatched array lengths throw exception")
    void mismatchedArrayLengthsThrowException() {
        var levels = new byte[]{1, 2, 3};
        var indices = new long[]{0, 1}; // Different length
        
        assertThrows(IllegalArgumentException.class,
            () -> new LevelIndex(levels, indices));
    }

    @Test
    @DisplayName("Empty arrays throw exception")
    void emptyArraysThrowException() {
        assertThrows(IllegalArgumentException.class,
            () -> new LevelIndex(new byte[0], new long[0]));
    }

    @Test
    @DisplayName("Negative levels throw exception")
    void negativeLevelsThrowException() {
        var levels = new byte[]{2, -1}; // Negative level
        var indices = new long[]{1, 0};
        
        assertThrows(IllegalArgumentException.class,
            () -> new LevelIndex(levels, indices));
    }

    @Test
    @DisplayName("Levels exceeding maximum throw exception")
    void levelsExceedingMaximumThrowException() {
        var levels = new byte[]{2, (byte) (LevelIndex.MAX_LEVEL + 1)};
        var indices = new long[]{1, 0};
        
        assertThrows(IllegalArgumentException.class,
            () -> new LevelIndex(levels, indices));
    }

    @Test
    @DisplayName("Negative indices throw exception")
    void negativeIndicesThrowException() {
        var levels = new byte[]{2, 3};
        var indices = new long[]{1, -1}; // Negative index
        
        assertThrows(IllegalArgumentException.class,
            () -> new LevelIndex(levels, indices));
    }

    @Test
    @DisplayName("Indices exceeding level bounds throw exception")
    void indicesExceedingLevelBoundsThrowException() {
        var levels = new byte[]{2, 3}; // Level 2 allows indices 0-3, level 3 allows 0-7
        var indices = new long[]{1, 8}; // Index 8 exceeds maximum 7 for level 3
        
        assertThrows(IllegalArgumentException.class,
            () -> new LevelIndex(levels, indices));
    }

    @Test
    @DisplayName("Maximum valid indices for levels")
    void maximumValidIndicesForLevels() {
        // Level 2 -> max index 3, level 3 -> max index 7
        var levels = new byte[]{2, 3};
        var indices = new long[]{3, 7}; // Maximum valid indices
        
        assertDoesNotThrow(() -> new LevelIndex(levels, indices));
        
        var levelIndex = new LevelIndex(levels, indices);
        assertEquals(3, levelIndex.getIndex(0));
        assertEquals(7, levelIndex.getIndex(1));
    }

    @Test
    @DisplayName("Boundary dimension access")
    void boundaryDimensionAccess() {
        var levelIndex = LevelIndex.uniform(3, (byte) 2);
        
        // Valid accesses
        assertEquals(2, levelIndex.getLevel(0));
        assertEquals(2, levelIndex.getLevel(2));
        assertEquals(0, levelIndex.getIndex(0));
        assertEquals(0, levelIndex.getIndex(2));
        
        // Invalid accesses
        assertThrows(IndexOutOfBoundsException.class, () -> levelIndex.getLevel(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> levelIndex.getLevel(3));
        assertThrows(IndexOutOfBoundsException.class, () -> levelIndex.getIndex(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> levelIndex.getIndex(3));
    }

    @Test
    @DisplayName("Update level in dimension")
    void updateLevelInDimension() {
        var levelIndex = LevelIndex.uniform(2, (byte) 2);
        var updated = levelIndex.withLevel(0, (byte) 4);
        
        assertEquals(4, updated.getLevel(0));
        assertEquals(2, updated.getLevel(1)); // Other dimension unchanged
        assertEquals(0, updated.getIndex(0)); // Index preserved if valid
        assertEquals(0, updated.getIndex(1));
        
        // Original unchanged
        assertEquals(2, levelIndex.getLevel(0));
    }

    @Test
    @DisplayName("Update level resets index if too large")
    void updateLevelResetsIndexIfTooLarge() {
        var levels = new byte[]{3}; // Level 3 allows indices 0-7
        var indices = new long[]{5}; // Valid for level 3
        var levelIndex = new LevelIndex(levels, indices);
        
        // Reduce level to 2 (allows indices 0-3), index 5 should be reset to 0
        var updated = levelIndex.withLevel(0, (byte) 2);
        assertEquals(2, updated.getLevel(0));
        assertEquals(0, updated.getIndex(0)); // Reset because 5 > 3
    }

    @Test
    @DisplayName("Update index in dimension")
    void updateIndexInDimension() {
        var levelIndex = LevelIndex.uniform(2, (byte) 3); // Level 3 allows indices 0-7
        var updated = levelIndex.withIndex(1, 5);
        
        assertEquals(3, updated.getLevel(0)); // Levels unchanged
        assertEquals(3, updated.getLevel(1));
        assertEquals(0, updated.getIndex(0)); // Other index unchanged
        assertEquals(5, updated.getIndex(1)); // Updated index
        
        // Original unchanged
        assertEquals(0, levelIndex.getIndex(1));
    }

    @Test
    @DisplayName("Update index with invalid value throws exception")
    void updateIndexWithInvalidValueThrowsException() {
        var levelIndex = LevelIndex.uniform(2, (byte) 2); // Level 2 allows indices 0-3
        
        assertThrows(IllegalArgumentException.class, 
            () -> levelIndex.withIndex(0, -1));
        assertThrows(IllegalArgumentException.class, 
            () -> levelIndex.withIndex(0, 4)); // Exceeds maximum 3
    }

    @Test
    @DisplayName("Refine levels")
    void refineLevels() {
        var levelIndex = LevelIndex.of(new byte[]{2, 3}, new long[]{1, 4});
        var refined = levelIndex.refine((byte) 2);
        
        // Levels increased by 2
        assertEquals(4, refined.getLevel(0));
        assertEquals(5, refined.getLevel(1));
        
        // Indices scaled up by 2^2 = 4
        assertEquals(4, refined.getIndex(0)); // 1 << 2 = 4
        assertEquals(16, refined.getIndex(1)); // 4 << 2 = 16
    }

    @Test
    @DisplayName("Refine with negative delta throws exception")
    void refineWithNegativeDeltaThrowsException() {
        var levelIndex = LevelIndex.uniform(2, (byte) 2);
        
        assertThrows(IllegalArgumentException.class,
            () -> levelIndex.refine((byte) -1));
    }

    @Test
    @DisplayName("Refine exceeding maximum level throws exception")
    void refineExceedingMaximumLevelThrowsException() {
        var levelIndex = LevelIndex.uniform(2, (byte) 61); // Close to maximum
        
        assertThrows(IllegalArgumentException.class,
            () -> levelIndex.refine((byte) 2)); // Would exceed MAX_LEVEL
    }

    @Test
    @DisplayName("Calculate total cells")
    void calculateTotalCells() {
        // Level 2 has 4 cells, level 3 has 8 cells -> total 32 cells
        var levelIndex = LevelIndex.of(new byte[]{2, 3}, new long[]{0, 0});
        assertEquals(32, levelIndex.totalCells());
        
        // Single dimension
        var singleDim = LevelIndex.of(new byte[]{4}, new long[]{0});
        assertEquals(16, singleDim.totalCells()); // 2^4 = 16
    }

    @Test
    @DisplayName("Total cells overflow throws exception")
    void totalCellsOverflowThrowsException() {
        // Create level index that would cause overflow
        var levelIndex = LevelIndex.uniform(2, (byte) 62); // 2^62 * 2^62 > Long.MAX_VALUE
        
        assertThrows(ArithmeticException.class, levelIndex::totalCells);
    }

    @Test
    @DisplayName("Get arrays returns defensive copies")
    void getArraysReturnsDefensiveCopies() {
        var originalLevels = new byte[]{2, 3};
        var originalIndices = new long[]{1, 4};
        var levelIndex = new LevelIndex(originalLevels, originalIndices);
        
        var levelsArray = levelIndex.getLevels();
        var indicesArray = levelIndex.getIndices();
        var coordinatesArray = levelIndex.coordinates();
        
        // Modify returned arrays
        levelsArray[0] = 99;
        indicesArray[0] = 99;
        coordinatesArray[1] = 99;
        
        // Original should be unchanged
        assertEquals(2, levelIndex.getLevel(0));
        assertEquals(1, levelIndex.getIndex(0));
        assertEquals(4, levelIndex.getIndex(1));
        
        // Arrays should be independent
        assertArrayEquals(new byte[]{2, 3}, levelIndex.getLevels());
        assertArrayEquals(new long[]{1, 4}, levelIndex.getIndices());
        assertArrayEquals(new long[]{1, 4}, levelIndex.coordinates());
    }

    @Test
    @DisplayName("Level method returns first level")
    void levelMethodReturnsFirstLevel() {
        var levelIndex = LevelIndex.of(new byte[]{5, 3, 7}, new long[]{0, 0, 0});
        assertEquals(5, levelIndex.level());
        
        // Empty should return 0 (though this shouldn't be possible due to validation)
        // This tests the defensive implementation
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 10})
    @DisplayName("High-dimensional level indices")
    void highDimensionalLevelIndices(int dimensions) {
        var levelIndex = LevelIndex.uniform(dimensions, (byte) 3);
        
        assertEquals(dimensions, levelIndex.dimensions());
        for (int i = 0; i < dimensions; i++) {
            assertEquals(3, levelIndex.getLevel(i));
            assertEquals(0, levelIndex.getIndex(i));
        }
        
        // Total cells should be 8^dimensions
        long expectedCells = 1;
        for (int i = 0; i < dimensions; i++) {
            expectedCells *= 8; // 2^3 = 8
        }
        if (expectedCells <= Long.MAX_VALUE) { // Avoid overflow for test
            assertEquals(expectedCells, levelIndex.totalCells());
        }
    }

    @Test
    @DisplayName("Constructor makes defensive copies")
    void constructorMakesDefensiveCopies() {
        var levels = new byte[]{2, 3};
        var indices = new long[]{1, 4};
        var levelIndex = new LevelIndex(levels, indices);
        
        // Modify original arrays
        levels[0] = 99;
        indices[0] = 99;
        
        // LevelIndex should be unchanged
        assertEquals(2, levelIndex.getLevel(0));
        assertEquals(1, levelIndex.getIndex(0));
    }

    @Test
    @DisplayName("Equality and hashCode")
    void equalityAndHashCode() {
        var levelIndex1 = LevelIndex.of(new byte[]{2, 3}, new long[]{1, 4});
        var levelIndex2 = LevelIndex.of(new byte[]{2, 3}, new long[]{1, 4});
        var levelIndex3 = LevelIndex.of(new byte[]{2, 3}, new long[]{1, 5});
        var levelIndex4 = LevelIndex.of(new byte[]{3, 3}, new long[]{1, 4});
        
        // Equal objects
        assertEquals(levelIndex1, levelIndex2);
        assertEquals(levelIndex1.hashCode(), levelIndex2.hashCode());
        
        // Different indices
        assertNotEquals(levelIndex1, levelIndex3);
        
        // Different levels
        assertNotEquals(levelIndex1, levelIndex4);
        
        // Self-equality
        assertEquals(levelIndex1, levelIndex1);
        
        // Null and different type
        assertNotEquals(levelIndex1, null);
        assertNotEquals(levelIndex1, "not a LevelIndex");
    }

    @Test
    @DisplayName("toString format")
    void toStringFormat() {
        var levelIndex = LevelIndex.of(new byte[]{2, 3}, new long[]{1, 4});
        var str = levelIndex.toString();
        
        assertTrue(str.contains("LevelIndex"));
        assertTrue(str.contains("levels"));
        assertTrue(str.contains("indices"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("3"));
        assertTrue(str.contains("1"));
        assertTrue(str.contains("4"));
    }

    @Test
    @DisplayName("Maximum level boundary")
    void maximumLevelBoundary() {
        // Test at the boundary of MAX_LEVEL
        var levelIndex = LevelIndex.uniform(1, LevelIndex.MAX_LEVEL);
        assertEquals(LevelIndex.MAX_LEVEL, levelIndex.getLevel(0));
        
        // Should be able to set to MAX_LEVEL
        var updated = levelIndex.withLevel(0, LevelIndex.MAX_LEVEL);
        assertEquals(LevelIndex.MAX_LEVEL, updated.getLevel(0));
        
        // Should not be able to exceed MAX_LEVEL
        assertThrows(IllegalArgumentException.class,
            () -> levelIndex.withLevel(0, (byte) (LevelIndex.MAX_LEVEL + 1)));
    }

    @Test
    @DisplayName("Index bounds for various levels")
    void indexBoundsForVariousLevels() {
        // Level 0: index 0 only
        var level0 = LevelIndex.of(new byte[]{0}, new long[]{0});
        assertEquals(0, level0.getIndex(0));
        assertThrows(IllegalArgumentException.class,
            () -> level0.withIndex(0, 1));
        
        // Level 1: indices 0-1
        var level1 = LevelIndex.uniform(1, (byte) 1);
        assertDoesNotThrow(() -> level1.withIndex(0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> level1.withIndex(0, 2));
        
        // Level 5: indices 0-31
        var level5 = LevelIndex.uniform(1, (byte) 5);
        assertDoesNotThrow(() -> level5.withIndex(0, 31));
        assertThrows(IllegalArgumentException.class,
            () -> level5.withIndex(0, 32));
    }
}