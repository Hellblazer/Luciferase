package com.dyada.core;

import com.dyada.core.coordinates.LevelIndex;

import java.util.Arrays;

/**
 * Represents a multiscale spatial index with level-dependent indices.
 * Used for adaptive mesh refinement and hierarchical spatial indexing.
 */
public record MultiscaleIndex(
    byte[] dLevel,
    int[] indices
) {
    
    public MultiscaleIndex {
        if (dLevel == null || indices == null) {
            throw new IllegalArgumentException("Level and indices arrays cannot be null");
        }
        if (dLevel.length != indices.length) {
            throw new IllegalArgumentException("Level and indices arrays must have same length");
        }
        if (dLevel.length == 0) {
            throw new IllegalArgumentException("Arrays cannot be empty");
        }
        
        // Defensive copies
        dLevel = Arrays.copyOf(dLevel, dLevel.length);
        indices = Arrays.copyOf(indices, indices.length);
        
        // Validate levels are non-negative
        for (byte level : dLevel) {
            if (level < 0) {
                throw new IllegalArgumentException("Levels must be non-negative");
            }
        }
    }
    
    /**
     * Creates a uniform multiscale index with the same level in all dimensions.
     */
    public static MultiscaleIndex uniform(int dimensions, byte level, int baseIndex) {
        var levels = new byte[dimensions];
        var indices = new int[dimensions];
        Arrays.fill(levels, level);
        Arrays.fill(indices, baseIndex);
        return new MultiscaleIndex(levels, indices);
    }
    
    /**
     * Creates a multiscale index from a LevelIndex.
     */
    public static MultiscaleIndex fromLevelIndex(LevelIndex levelIndex) {
        var coordinates = levelIndex.coordinates();
        var levels = new byte[coordinates.length];
        var indices = new int[coordinates.length];
        
        for (int i = 0; i < coordinates.length; i++) {
            levels[i] = levelIndex.level();
            // Safe conversion from long to int, assuming coordinates fit in int range
            indices[i] = (int) coordinates[i];
        }
        
        return new MultiscaleIndex(levels, indices);
    }
    
    /**
     * Creates a 2D multiscale index.
     */
    public static MultiscaleIndex create2D(byte level, int x, int y) {
        return new MultiscaleIndex(
            new byte[]{level, level}, 
            new int[]{x, y}
        );
    }
    
    /**
     * Creates a 3D multiscale index.
     */
    public static MultiscaleIndex create3D(byte level, int x, int y, int z) {
        return new MultiscaleIndex(
            new byte[]{level, level, level}, 
            new int[]{x, y, z}
        );
    }
    
    /**
     * Creates a multiscale index with different levels per dimension.
     */
    public static MultiscaleIndex create(byte[] levels, int[] indices) {
        return new MultiscaleIndex(levels, indices);
    }
    
    /**
     * Gets the number of dimensions.
     */
    public int dimensions() {
        return dLevel.length;
    }
    
    /**
     * Gets the maximum level across all dimensions.
     */
    public byte getMaxLevel() {
        byte maxLevel = 0;
        for (byte level : dLevel) {
            if (level > maxLevel) {
                maxLevel = level;
            }
        }
        return maxLevel;
    }
    
    /**
     * Gets the minimum level across all dimensions.
     */
    public byte getMinLevel() {
        byte minLevel = Byte.MAX_VALUE;
        for (byte level : dLevel) {
            if (level < minLevel) {
                minLevel = level;
            }
        }
        return minLevel == Byte.MAX_VALUE ? 0 : minLevel;
    }
    
    /**
     * Gets the level for a specific dimension.
     */
    public byte getLevel(int dimension) {
        if (dimension < 0 || dimension >= dLevel.length) {
            throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
        return dLevel[dimension];
    }
    
    /**
     * Gets the index for a specific dimension.
     */
    public int getIndex(int dimension) {
        if (dimension < 0 || dimension >= indices.length) {
            throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
        return indices[dimension];
    }
    
    /**
     * Creates a new multiscale index with updated level in one dimension.
     */
    public MultiscaleIndex withLevel(int dimension, byte newLevel) {
        if (dimension < 0 || dimension >= dLevel.length) {
            throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
        
        var newLevels = Arrays.copyOf(dLevel, dLevel.length);
        newLevels[dimension] = newLevel;
        return new MultiscaleIndex(newLevels, indices);
    }
    
    /**
     * Creates a new multiscale index with updated index in one dimension.
     */
    public MultiscaleIndex withIndex(int dimension, int newIndex) {
        if (dimension < 0 || dimension >= indices.length) {
            throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
        
        var newIndices = Arrays.copyOf(indices, indices.length);
        newIndices[dimension] = newIndex;
        return new MultiscaleIndex(dLevel, newIndices);
    }
    
    /**
     * Converts to a simple LevelIndex using the maximum level.
     */
    public LevelIndex toLevelIndex() {
        byte maxLevel = getMaxLevel();
        var levels = new byte[indices.length];
        var longIndices = new long[indices.length];
        
        Arrays.fill(levels, maxLevel);
        for (int i = 0; i < indices.length; i++) {
            longIndices[i] = indices[i];
        }
        
        return new LevelIndex(levels, longIndices);
    }
    
    /**
     * Checks if this index has uniform levels across all dimensions.
     */
    public boolean isUniform() {
        if (dLevel.length <= 1) {
            return true;
        }
        
        byte firstLevel = dLevel[0];
        for (int i = 1; i < dLevel.length; i++) {
            if (dLevel[i] != firstLevel) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MultiscaleIndex other)) return false;
        return Arrays.equals(dLevel, other.dLevel) && Arrays.equals(indices, other.indices);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(dLevel) * 31 + Arrays.hashCode(indices);
    }
    
    @Override
    public String toString() {
        return String.format("MultiscaleIndex{levels=%s, indices=%s}", 
            Arrays.toString(dLevel), Arrays.toString(indices));
    }
}