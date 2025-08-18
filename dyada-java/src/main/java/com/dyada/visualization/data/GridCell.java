package com.dyada.visualization.data;

import java.util.Arrays;
import java.util.Map;

/**
 * Represents a cell in a spatial grid visualization with coordinates and refinement level.
 */
public record GridCell(
    com.dyada.core.MultiscaleIndex index,
    double[] coordinates,
    double[] size,
    int refinementLevel,
    boolean isActive,
    Map<String, Object> attributes
) {
    
    public GridCell {
        if (index == null) {
            throw new IllegalArgumentException("Multiscale index cannot be null");
        }
        if (coordinates == null) {
            throw new IllegalArgumentException("Coordinates cannot be null");
        }
        if (size == null) {
            throw new IllegalArgumentException("Size cannot be null");
        }
        if (coordinates.length != size.length) {
            throw new IllegalArgumentException("Coordinates and size must have same dimensions");
        }
        if (coordinates.length < 2 || coordinates.length > 3) {
            throw new IllegalArgumentException("Grid cell must be 2D or 3D");
        }
        if (refinementLevel < 0) {
            throw new IllegalArgumentException("Refinement level cannot be negative");
        }
        
        // Defensive copies
        coordinates = Arrays.copyOf(coordinates, coordinates.length);
        size = Arrays.copyOf(size, size.length);
        
        if (attributes == null) {
            attributes = Map.of();
        }
    }
    
    /**
     * Creates a 2D grid cell.
     */
    public static GridCell create2D(
        com.dyada.core.MultiscaleIndex index,
        double x, double y,
        double width, double height,
        int level,
        boolean active
    ) {
        return new GridCell(
            index,
            new double[]{x, y},
            new double[]{width, height},
            level,
            active,
            Map.of()
        );
    }
    
    /**
     * Creates a 3D grid cell.
     */
    public static GridCell create3D(
        com.dyada.core.MultiscaleIndex index,
        double x, double y, double z,
        double width, double height, double depth,
        int level,
        boolean active
    ) {
        return new GridCell(
            index,
            new double[]{x, y, z},
            new double[]{width, height, depth},
            level,
            active,
            Map.of()
        );
    }
    
    /**
     * Gets the number of dimensions.
     */
    public int dimensions() {
        return coordinates.length;
    }
    
    /**
     * Gets the center coordinates of the cell.
     */
    public double[] getCenter() {
        var center = new double[coordinates.length];
        for (int i = 0; i < coordinates.length; i++) {
            center[i] = coordinates[i] + size[i] / 2.0;
        }
        return center;
    }
    
    /**
     * Gets the bounds of this cell.
     */
    public Bounds getBounds() {
        var min = Arrays.copyOf(coordinates, coordinates.length);
        var max = new double[coordinates.length];
        for (int i = 0; i < coordinates.length; i++) {
            max[i] = coordinates[i] + size[i];
        }
        return new Bounds(min, max);
    }
    
    /**
     * Checks if a point is contained within this cell.
     */
    public boolean contains(double[] point) {
        if (point.length != coordinates.length) {
            return false;
        }
        
        for (int i = 0; i < coordinates.length; i++) {
            if (point[i] < coordinates[i] || point[i] > coordinates[i] + size[i]) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Calculates the volume (area in 2D) of this cell.
     */
    public double getVolume() {
        double volume = 1.0;
        for (double s : size) {
            volume *= s;
        }
        return volume;
    }
}