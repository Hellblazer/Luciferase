package com.dyada.core.coordinates;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable record representing level and index coordinates in DyAda's dyadic addressing system.
 * 
 * Each dimension has a refinement level (0-62) and an index within that level.
 * This is the core data structure for representing spatial addressing in the dyadic grid.
 * 
 * The level indicates how finely a dimension is subdivided (level n means 2^n subdivisions).
 * The index indicates which subdivision within that level (0 to 2^level - 1).
 */
public record LevelIndex(
    byte[] dLevel,      // Refinement level per dimension (0-62)
    long[] dIndex       // Index within level per dimension
) {
    
    /**
     * Maximum supported refinement level per dimension.
     * Limited by IEEE 754 double precision mantissa (52 bits + 1 implicit bit + safety margin).
     */
    public static final byte MAX_LEVEL = 62;
    
    /**
     * Compact constructor with validation.
     */
    public LevelIndex {
        Objects.requireNonNull(dLevel, "Level array cannot be null");
        Objects.requireNonNull(dIndex, "Index array cannot be null");
        
        if (dLevel.length != dIndex.length) {
            throw new IllegalArgumentException(
                String.format("Level and index arrays must have same length: %d vs %d", 
                    dLevel.length, dIndex.length));
        }
        
        if (dLevel.length == 0) {
            throw new IllegalArgumentException("Cannot create LevelIndex with zero dimensions");
        }
        
        // Validate levels are within bounds
        for (int i = 0; i < dLevel.length; i++) {
            if (dLevel[i] < 0) {
                throw new IllegalArgumentException(
                    String.format("Level cannot be negative: %d at dimension %d", dLevel[i], i));
            }
            if (dLevel[i] > MAX_LEVEL) {
                throw new IllegalArgumentException(
                    String.format("Level %d exceeds maximum %d at dimension %d", 
                        dLevel[i], MAX_LEVEL, i));
            }
        }
        
        // Validate indices are within bounds for their levels
        for (int i = 0; i < dIndex.length; i++) {
            if (dIndex[i] < 0) {
                throw new IllegalArgumentException(
                    String.format("Index cannot be negative: %d at dimension %d", dIndex[i], i));
            }
            
            long maxIndex = (1L << dLevel[i]) - 1;
            if (dIndex[i] > maxIndex) {
                throw new IllegalArgumentException(
                    String.format("Index %d exceeds maximum %d for level %d at dimension %d", 
                        dIndex[i], maxIndex, dLevel[i], i));
            }
        }
        
        // Defensive copies
        dLevel = dLevel.clone();
        dIndex = dIndex.clone();
    }
    
    /**
     * Creates a LevelIndex with the specified dimensions.
     */
    public static LevelIndex of(byte[] levels, long[] indices) {
        return new LevelIndex(levels, indices);
    }
    
    /**
     * Creates a LevelIndex where all dimensions have the same level and indices start at 0.
     */
    public static LevelIndex uniform(int dimensions, byte level) {
        var levels = new byte[dimensions];
        var indices = new long[dimensions];
        Arrays.fill(levels, level);
        // indices array is already initialized to zeros
        return new LevelIndex(levels, indices);
    }
    
    /**
     * Creates a LevelIndex at the origin (all levels 0, all indices 0).
     */
    public static LevelIndex origin(int dimensions) {
        return uniform(dimensions, (byte) 0);
    }
    
    /**
     * Returns the number of dimensions.
     */
    public int dimensions() {
        return dLevel.length;
    }
    
    /**
     * Returns the level for the specified dimension.
     */
    public byte getLevel(int dimension) {
        checkDimension(dimension);
        return dLevel[dimension];
    }
    
    /**
     * Returns the index for the specified dimension.
     */
    public long getIndex(int dimension) {
        checkDimension(dimension);
        return dIndex[dimension];
    }
    
    /**
     * Returns a new LevelIndex with the level changed for the specified dimension.
     */
    public LevelIndex withLevel(int dimension, byte level) {
        checkDimension(dimension);
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                String.format("Level %d out of range [0, %d]", level, MAX_LEVEL));
        }
        
        var newLevels = dLevel.clone();
        var newIndices = dIndex.clone();
        newLevels[dimension] = level;
        
        // Adjust index if it's too large for the new level
        long maxIndex = (1L << level) - 1;
        if (newIndices[dimension] > maxIndex) {
            newIndices[dimension] = 0; // Reset to origin if invalid
        }
        
        return new LevelIndex(newLevels, newIndices);
    }
    
    /**
     * Returns a new LevelIndex with the index changed for the specified dimension.
     */
    public LevelIndex withIndex(int dimension, long index) {
        checkDimension(dimension);
        if (index < 0) {
            throw new IllegalArgumentException("Index cannot be negative: " + index);
        }
        
        long maxIndex = (1L << dLevel[dimension]) - 1;
        if (index > maxIndex) {
            throw new IllegalArgumentException(
                String.format("Index %d exceeds maximum %d for level %d", 
                    index, maxIndex, dLevel[dimension]));
        }
        
        var newIndices = dIndex.clone();
        newIndices[dimension] = index;
        return new LevelIndex(dLevel.clone(), newIndices);
    }
    
    /**
     * Returns a new LevelIndex with all levels incremented by the specified amount.
     */
    public LevelIndex refine(byte deltaLevel) {
        if (deltaLevel < 0) {
            throw new IllegalArgumentException("Refinement delta cannot be negative: " + deltaLevel);
        }
        
        var newLevels = new byte[dLevel.length];
        for (int i = 0; i < dLevel.length; i++) {
            int newLevel = dLevel[i] + deltaLevel;
            if (newLevel > MAX_LEVEL) {
                throw new IllegalArgumentException(
                    String.format("Refinement would exceed maximum level: %d + %d > %d", 
                        dLevel[i], deltaLevel, MAX_LEVEL));
            }
            newLevels[i] = (byte) newLevel;
        }
        
        // Scale up indices for the new refinement level
        var newIndices = new long[dIndex.length];
        for (int i = 0; i < dIndex.length; i++) {
            newIndices[i] = dIndex[i] << deltaLevel;
        }
        
        return new LevelIndex(newLevels, newIndices);
    }
    
    /**
     * Returns the total number of cells represented by this level structure.
     */
    public long totalCells() {
        long total = 1;
        for (byte level : dLevel) {
            long cellsInDimension = 1L << level;
            // Check for overflow
            if (total > Long.MAX_VALUE / cellsInDimension) {
                throw new ArithmeticException("Total cells overflow");
            }
            total *= cellsInDimension;
        }
        return total;
    }
    
    /**
     * Returns a copy of the level array.
     */
    public byte[] getLevels() {
        return dLevel.clone();
    }
    
    /**
     * Returns a copy of the index array.
     */
    public long[] getIndices() {
        return dIndex.clone();
    }
    
    /**
     * Returns the coordinates (indices) array - alias for getIndices().
     */
    public long[] coordinates() {
        return getIndices();
    }
    
    /**
     * Returns the first level in the levels array.
     * Used when a single level value is expected.
     */
    public byte level() {
        return dLevel.length > 0 ? dLevel[0] : 0;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        LevelIndex that = (LevelIndex) obj;
        return Arrays.equals(dLevel, that.dLevel) && Arrays.equals(dIndex, that.dIndex);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(dLevel), Arrays.hashCode(dIndex));
    }
    
    @Override
    public String toString() {
        return String.format("LevelIndex{levels=%s, indices=%s}", 
            Arrays.toString(dLevel), Arrays.toString(dIndex));
    }
    
    private void checkDimension(int dimension) {
        if (dimension < 0 || dimension >= dLevel.length) {
            throw new IndexOutOfBoundsException(
                String.format("Dimension %d out of range [0, %d)", dimension, dLevel.length));
        }
    }
}