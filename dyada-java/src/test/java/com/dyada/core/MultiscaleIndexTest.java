package com.dyada.core;

import com.dyada.TestBase;
import com.dyada.core.coordinates.LevelIndex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MultiscaleIndex Tests")
class MultiscaleIndexTest extends TestBase {
    
    @Test
    @DisplayName("Create 2D multiscale index")
    void testCreate2D() {
        var index = MultiscaleIndex.create2D((byte) 3, 5, 7);
        
        assertEquals(2, index.dimensions());
        assertEquals(3, index.getLevel(0));
        assertEquals(3, index.getLevel(1));
        assertEquals(5, index.getIndex(0));
        assertEquals(7, index.getIndex(1));
        assertTrue(index.isUniform());
    }
    
    @Test
    @DisplayName("Create 3D multiscale index")
    void testCreate3D() {
        var index = MultiscaleIndex.create3D((byte) 2, 1, 3, 5);
        
        assertEquals(3, index.dimensions());
        assertEquals(2, index.getLevel(0));
        assertEquals(2, index.getLevel(1));
        assertEquals(2, index.getLevel(2));
        assertEquals(1, index.getIndex(0));
        assertEquals(3, index.getIndex(1));
        assertEquals(5, index.getIndex(2));
        assertTrue(index.isUniform());
    }
    
    @Test
    @DisplayName("Create non-uniform multiscale index")
    void testCreateNonUniform() {
        var levels = new byte[]{1, 2, 3};
        var indices = new int[]{10, 20, 30};
        var index = MultiscaleIndex.create(levels, indices);
        
        assertEquals(3, index.dimensions());
        assertEquals(1, index.getLevel(0));
        assertEquals(2, index.getLevel(1));
        assertEquals(3, index.getLevel(2));
        assertEquals(10, index.getIndex(0));
        assertEquals(20, index.getIndex(1));
        assertEquals(30, index.getIndex(2));
        assertFalse(index.isUniform());
    }
    
    @Test
    @DisplayName("Create from LevelIndex")
    void testFromLevelIndex() {
        var levelIndex = levelIndex2D((byte) 4, 8, 12);
        var multiscaleIndex = MultiscaleIndex.fromLevelIndex(levelIndex);
        
        assertEquals(2, multiscaleIndex.dimensions());
        assertEquals(4, multiscaleIndex.getLevel(0));
        assertEquals(4, multiscaleIndex.getLevel(1));
        assertEquals(8, multiscaleIndex.getIndex(0));
        assertEquals(12, multiscaleIndex.getIndex(1));
        assertTrue(multiscaleIndex.isUniform());
    }
    
    @Test
    @DisplayName("Convert to LevelIndex")
    void testToLevelIndex() {
        var multiscaleIndex = MultiscaleIndex.create2D((byte) 5, 15, 25);
        var levelIndex = multiscaleIndex.toLevelIndex();
        
        assertEquals(5, levelIndex.level());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new long[]{15, 25}, levelIndex.coordinates());
    }
    
    @Test
    @DisplayName("Get max and min levels")
    void testMaxMinLevels() {
        var levels = new byte[]{1, 5, 3, 2};
        var indices = new int[]{10, 20, 30, 40};
        var index = MultiscaleIndex.create(levels, indices);
        
        assertEquals(5, index.getMaxLevel());
        assertEquals(1, index.getMinLevel());
    }
    
    @Test
    @DisplayName("Update level in dimension")
    void testWithLevel() {
        var original = MultiscaleIndex.create2D((byte) 2, 10, 20);
        var updated = original.withLevel(1, (byte) 5);
        
        // Original unchanged
        assertEquals(2, original.getLevel(0));
        assertEquals(2, original.getLevel(1));
        
        // Updated has new level
        assertEquals(2, updated.getLevel(0));
        assertEquals(5, updated.getLevel(1));
        assertEquals(10, updated.getIndex(0));
        assertEquals(20, updated.getIndex(1));
    }
    
    @Test
    @DisplayName("Update index in dimension")
    void testWithIndex() {
        var original = MultiscaleIndex.create2D((byte) 3, 5, 10);
        var updated = original.withIndex(0, 15);
        
        // Original unchanged
        assertEquals(5, original.getIndex(0));
        assertEquals(10, original.getIndex(1));
        
        // Updated has new index
        assertEquals(15, updated.getIndex(0));
        assertEquals(10, updated.getIndex(1));
        assertEquals(3, updated.getLevel(0));
        assertEquals(3, updated.getLevel(1));
    }
    
    @Test
    @DisplayName("Uniform detection")
    void testUniformDetection() {
        // Uniform cases
        assertTrue(MultiscaleIndex.create2D((byte) 3, 1, 2).isUniform());
        assertTrue(MultiscaleIndex.uniform(4, (byte) 2, 5).isUniform());
        
        // Non-uniform cases
        var levels = new byte[]{1, 2};
        var indices = new int[]{5, 10};
        assertFalse(MultiscaleIndex.create(levels, indices).isUniform());
    }
    
    @Test
    @DisplayName("Invalid dimension access throws exception")
    void testInvalidDimensionAccess() {
        var index = MultiscaleIndex.create2D((byte) 1, 5, 10);
        
        assertThrows(IllegalArgumentException.class, () -> index.getLevel(-1));
        assertThrows(IllegalArgumentException.class, () -> index.getLevel(2));
        assertThrows(IllegalArgumentException.class, () -> index.getIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> index.getIndex(2));
        assertThrows(IllegalArgumentException.class, () -> index.withLevel(-1, (byte) 2));
        assertThrows(IllegalArgumentException.class, () -> index.withLevel(2, (byte) 2));
        assertThrows(IllegalArgumentException.class, () -> index.withIndex(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> index.withIndex(2, 5));
    }
    
    @ParameterizedTest
    @ValueSource(ints = {-1, -5, -10})
    @DisplayName("Negative levels throw exception")
    void testNegativeLevels(int negativeLevel) {
        var levels = new byte[]{1, (byte) negativeLevel};
        var indices = new int[]{5, 10};
        
        assertThrows(IllegalArgumentException.class, 
            () -> MultiscaleIndex.create(levels, indices));
    }
    
    @Test
    @DisplayName("Null arrays throw exception")
    void testNullArrays() {
        assertThrows(IllegalArgumentException.class, 
            () -> MultiscaleIndex.create(null, new int[]{1, 2}));
        assertThrows(IllegalArgumentException.class, 
            () -> MultiscaleIndex.create(new byte[]{1, 2}, null));
        assertThrows(IllegalArgumentException.class, 
            () -> MultiscaleIndex.create(null, null));
    }
    
    @Test
    @DisplayName("Mismatched array lengths throw exception")
    void testMismatchedArrayLengths() {
        var levels = new byte[]{1, 2, 3};
        var indices = new int[]{5, 10}; // Different length
        
        assertThrows(IllegalArgumentException.class, 
            () -> MultiscaleIndex.create(levels, indices));
    }
    
    @Test
    @DisplayName("Empty arrays throw exception")
    void testEmptyArrays() {
        assertThrows(IllegalArgumentException.class, 
            () -> MultiscaleIndex.create(new byte[0], new int[0]));
    }
    
    @Test
    @DisplayName("Equality and hashCode")
    void testEqualityAndHashCode() {
        var index1 = MultiscaleIndex.create2D((byte) 3, 5, 10);
        var index2 = MultiscaleIndex.create2D((byte) 3, 5, 10);
        var index3 = MultiscaleIndex.create2D((byte) 3, 5, 11);
        
        assertEquals(index1, index2);
        assertEquals(index1.hashCode(), index2.hashCode());
        assertNotEquals(index1, index3);
        assertNotEquals(index1.hashCode(), index3.hashCode());
    }
}