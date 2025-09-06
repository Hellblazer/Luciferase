package com.dyada.core.coordinates;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents spatial boundaries in n-dimensional space.
 * Defines minimum and maximum extents for spatial discretization.
 */
public record Bounds(double[] min, double[] max) {
    
    public Bounds {
        Objects.requireNonNull(min, "min bounds cannot be null");
        Objects.requireNonNull(max, "max bounds cannot be null");
        
        if (min.length != max.length) {
            throw new IllegalArgumentException("min and max bounds must have same dimensions");
        }
        
        if (min.length == 0) {
            throw new IllegalArgumentException("bounds must have at least one dimension");
        }
        
        for (int i = 0; i < min.length; i++) {
            if (min[i] >= max[i]) {
                throw new IllegalArgumentException("min bounds must be less than max bounds in all dimensions");
            }
        }
        
        // Defensive copy
        min = min.clone();
        max = max.clone();
    }
    
    /**
     * Returns the number of dimensions
     */
    public int dimensions() {
        return min.length;
    }
    
    /**
     * Returns the extent (size) in the specified dimension
     */
    public double extent(int dimension) {
        if (dimension < 0 || dimension >= min.length) {
            throw new IndexOutOfBoundsException("dimension " + dimension + " out of bounds");
        }
        return max[dimension] - min[dimension];
    }
    
    /**
     * Returns the center point of the bounds
     */
    public Coordinate center() {
        var centerCoords = new double[min.length];
        for (int i = 0; i < min.length; i++) {
            centerCoords[i] = (min[i] + max[i]) / 2.0;
        }
        return new Coordinate(centerCoords);
    }
    
    /**
     * Checks if a coordinate is within these bounds (inclusive)
     */
    public boolean contains(Coordinate point) {
        Objects.requireNonNull(point, "point cannot be null");
        
        if (point.dimensions() != dimensions()) {
            return false;
        }
        
        var coords = point.values();
        for (int i = 0; i < min.length; i++) {
            if (coords[i] < min[i] || coords[i] > max[i]) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Creates new bounds expanded by the specified margin in all dimensions
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
    
    /**
     * Creates new bounds that is the intersection with another bounds
     */
    public Bounds intersect(Bounds other) {
        Objects.requireNonNull(other, "other bounds cannot be null");
        
        if (dimensions() != other.dimensions()) {
            throw new IllegalArgumentException("bounds must have same dimensions");
        }
        
        var newMin = new double[min.length];
        var newMax = new double[max.length];
        
        for (int i = 0; i < min.length; i++) {
            newMin[i] = Math.max(min[i], other.min[i]);
            newMax[i] = Math.min(max[i], other.max[i]);
            
            if (newMin[i] >= newMax[i]) {
                throw new IllegalArgumentException("bounds do not intersect");
            }
        }
        
        return new Bounds(newMin, newMax);
    }
    
    /**
     * Creates new bounds that is the union with another bounds
     */
    public Bounds union(Bounds other) {
        Objects.requireNonNull(other, "other bounds cannot be null");
        
        if (dimensions() != other.dimensions()) {
            throw new IllegalArgumentException("bounds must have same dimensions");
        }
        
        var newMin = new double[min.length];
        var newMax = new double[max.length];
        
        for (int i = 0; i < min.length; i++) {
            newMin[i] = Math.min(min[i], other.min[i]);
            newMax[i] = Math.max(max[i], other.max[i]);
        }
        
        return new Bounds(newMin, newMax);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Bounds other)) return false;
        return Arrays.equals(min, other.min) && Arrays.equals(max, other.max);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(min), Arrays.hashCode(max));
    }
    
    @Override
    public String toString() {
        return String.format("Bounds{min=%s, max=%s}", 
                            Arrays.toString(min), Arrays.toString(max));
    }
}