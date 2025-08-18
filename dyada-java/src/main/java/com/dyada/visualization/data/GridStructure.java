package com.dyada.visualization.data;

import java.util.Arrays;

/**
 * Describes the structure and organization of a spatial grid.
 */
public record GridStructure(
    int[] resolution,
    double[] cellSize,
    double[] origin,
    int maxRefinementLevel,
    String indexingScheme
) {
    
    public GridStructure {
        if (resolution == null || cellSize == null || origin == null) {
            throw new IllegalArgumentException("Grid structure parameters cannot be null");
        }
        if (resolution.length != cellSize.length || cellSize.length != origin.length) {
            throw new IllegalArgumentException("All grid parameters must have same dimensions");
        }
        if (resolution.length < 2 || resolution.length > 3) {
            throw new IllegalArgumentException("Grid must be 2D or 3D");
        }
        if (maxRefinementLevel < 0) {
            throw new IllegalArgumentException("Max refinement level cannot be negative");
        }
        if (indexingScheme == null || indexingScheme.isBlank()) {
            indexingScheme = "morton";
        }
        
        // Defensive copies
        resolution = Arrays.copyOf(resolution, resolution.length);
        cellSize = Arrays.copyOf(cellSize, cellSize.length);
        origin = Arrays.copyOf(origin, origin.length);
        
        // Validate positive values
        for (int i = 0; i < resolution.length; i++) {
            if (resolution[i] <= 0) {
                throw new IllegalArgumentException("Resolution must be positive in dimension " + i);
            }
            if (cellSize[i] <= 0) {
                throw new IllegalArgumentException("Cell size must be positive in dimension " + i);
            }
        }
    }
    
    /**
     * Creates a uniform 2D grid structure.
     */
    public static GridStructure uniform2D(int resX, int resY, double cellSizeX, double cellSizeY) {
        return new GridStructure(
            new int[]{resX, resY},
            new double[]{cellSizeX, cellSizeY},
            new double[]{0.0, 0.0},
            0,
            "morton"
        );
    }
    
    /**
     * Creates a uniform 3D grid structure.
     */
    public static GridStructure uniform3D(
        int resX, int resY, int resZ,
        double cellSizeX, double cellSizeY, double cellSizeZ
    ) {
        return new GridStructure(
            new int[]{resX, resY, resZ},
            new double[]{cellSizeX, cellSizeY, cellSizeZ},
            new double[]{0.0, 0.0, 0.0},
            0,
            "morton"
        );
    }
    
    /**
     * Creates adaptive grid structure with refinement levels.
     */
    public static GridStructure adaptive(
        int[] baseResolution,
        double[] baseCellSize,
        double[] origin,
        int maxLevels,
        String indexing
    ) {
        return new GridStructure(baseResolution, baseCellSize, origin, maxLevels, indexing);
    }
    
    /**
     * Gets the number of dimensions.
     */
    public int dimensions() {
        return resolution.length;
    }
    
    /**
     * Gets the total number of cells at base resolution.
     */
    public long getTotalCells() {
        long total = 1;
        for (int res : resolution) {
            total *= res;
        }
        return total;
    }
    
    /**
     * Gets the total grid size in each dimension.
     */
    public double[] getTotalSize() {
        var totalSize = new double[resolution.length];
        for (int i = 0; i < resolution.length; i++) {
            totalSize[i] = resolution[i] * cellSize[i];
        }
        return totalSize;
    }
    
    /**
     * Gets the bounds of the entire grid.
     */
    public Bounds getGridBounds() {
        var totalSize = getTotalSize();
        var max = new double[origin.length];
        for (int i = 0; i < origin.length; i++) {
            max[i] = origin[i] + totalSize[i];
        }
        return new Bounds(Arrays.copyOf(origin, origin.length), max);
    }
}