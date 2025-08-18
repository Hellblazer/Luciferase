package com.dyada.visualization.data;

import java.util.Arrays;

/**
 * Represents spatial bounds for visualization data.
 * Supports 2D and 3D bounding regions.
 */
public record Bounds(
    double[] min,
    double[] max
) {
    
    public Bounds {
        if (min == null || max == null) {
            throw new IllegalArgumentException("Bounds coordinates cannot be null");
        }
        if (min.length != max.length) {
            throw new IllegalArgumentException("Min and max must have same dimensions");
        }
        if (min.length < 2 || min.length > 3) {
            throw new IllegalArgumentException("Bounds must be 2D or 3D");
        }
        
        // Defensive copy to ensure immutability
        min = Arrays.copyOf(min, min.length);
        max = Arrays.copyOf(max, max.length);
        
        // Validate that min <= max in all dimensions
        for (int i = 0; i < min.length; i++) {
            if (min[i] > max[i]) {
                throw new IllegalArgumentException(
                    "Min coordinate must be <= max coordinate in dimension " + i
                );
            }
        }
    }
    
    /**
     * Gets the number of dimensions.
     * 
     * @return 2 for 2D bounds, 3 for 3D bounds
     */
    public int dimensions() {
        return min.length;
    }
    
    /**
     * Calculates the size in each dimension.
     * 
     * @return array of sizes [width, height] or [width, height, depth]
     */
    public double[] size() {
        var size = new double[min.length];
        for (int i = 0; i < min.length; i++) {
            size[i] = max[i] - min[i];
        }
        return size;
    }
    
    /**
     * Calculates the center point.
     * 
     * @return center coordinates
     */
    public double[] center() {
        var center = new double[min.length];
        for (int i = 0; i < min.length; i++) {
            center[i] = (min[i] + max[i]) / 2.0;
        }
        return center;
    }
    
    /**
     * Checks if a point is contained within these bounds.
     * 
     * @param point coordinates to test
     * @return true if point is within bounds
     */
    public boolean contains(double[] point) {
        if (point.length != min.length) {
            return false;
        }
        
        for (int i = 0; i < min.length; i++) {
            if (point[i] < min[i] || point[i] > max[i]) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Creates expanded bounds with margin.
     * 
     * @param margin expansion amount in each dimension
     * @return new bounds expanded by margin
     */
    public Bounds expand(double margin) {
        var newMin = new double[min.length];
        var newMax = new double[max.length];
        
        for (int i = 0; i < min.length; i++) {
            newMin[i] = min[i] - margin;
            newMax[i] = max[i] + margin;
        }
        
        return new Bounds(newMin, newMax);
    }
}