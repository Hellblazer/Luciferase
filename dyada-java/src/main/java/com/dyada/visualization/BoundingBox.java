package com.dyada.visualization;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.transformations.CoordinateTransformation;

import java.util.Arrays;

/**
 * Represents an axis-aligned bounding box in n-dimensional space.
 * Used for spatial culling and level-of-detail calculations.
 */
public record BoundingBox(Coordinate min, Coordinate max) {
    
    /**
     * Creates a bounding box with validation.
     * 
     * @param min minimum coordinate
     * @param max maximum coordinate
     * @throws IllegalArgumentException if dimensions don't match or min > max
     */
    public BoundingBox {
        if (min.values().length != max.values().length) {
            throw new IllegalArgumentException("Min and max coordinates must have same dimension");
        }
        
        var minValues = min.values();
        var maxValues = max.values();
        for (int i = 0; i < minValues.length; i++) {
            if (minValues[i] > maxValues[i]) {
                throw new IllegalArgumentException("Min coordinate cannot be greater than max coordinate");
            }
        }
    }
    
    /**
     * Creates a bounding box from arrays of coordinates.
     * 
     * @param minValues minimum values for each dimension
     * @param maxValues maximum values for each dimension
     * @return new bounding box
     */
    public static BoundingBox of(double[] minValues, double[] maxValues) {
        return new BoundingBox(new Coordinate(minValues), new Coordinate(maxValues));
    }
    
    /**
     * Creates a bounding box that encompasses all given coordinates.
     * 
     * @param coordinates the coordinates to bound
     * @return bounding box containing all coordinates
     * @throws IllegalArgumentException if coordinates list is empty
     */
    public static BoundingBox fromCoordinates(Iterable<Coordinate> coordinates) {
        var iterator = coordinates.iterator();
        if (!iterator.hasNext()) {
            throw new IllegalArgumentException("Cannot create bounding box from empty coordinates");
        }
        
        var first = iterator.next();
        var dimension = first.values().length;
        var minValues = first.values().clone();
        var maxValues = first.values().clone();
        
        while (iterator.hasNext()) {
            var coord = iterator.next();
            var values = coord.values();
            
            if (values.length != dimension) {
                throw new IllegalArgumentException("All coordinates must have same dimension");
            }
            
            for (int i = 0; i < dimension; i++) {
                minValues[i] = Math.min(minValues[i], values[i]);
                maxValues[i] = Math.max(maxValues[i], values[i]);
            }
        }
        
        return new BoundingBox(new Coordinate(minValues), new Coordinate(maxValues));
    }
    
    /**
     * Creates a unit bounding box (0 to 1 in all dimensions).
     * 
     * @param dimension the number of dimensions
     * @return unit bounding box
     */
    public static BoundingBox unit(int dimension) {
        var minValues = new double[dimension];
        var maxValues = new double[dimension];
        Arrays.fill(maxValues, 1.0);
        return new BoundingBox(new Coordinate(minValues), new Coordinate(maxValues));
    }
    
    /**
     * Creates a centered bounding box with given extents.
     * 
     * @param center center coordinate
     * @param extents half-extents in each dimension
     * @return centered bounding box
     */
    public static BoundingBox centered(Coordinate center, double[] extents) {
        var centerValues = center.values();
        if (centerValues.length != extents.length) {
            throw new IllegalArgumentException("Center and extents must have same dimension");
        }
        
        var minValues = new double[centerValues.length];
        var maxValues = new double[centerValues.length];
        
        for (int i = 0; i < centerValues.length; i++) {
            minValues[i] = centerValues[i] - extents[i];
            maxValues[i] = centerValues[i] + extents[i];
        }
        
        return new BoundingBox(new Coordinate(minValues), new Coordinate(maxValues));
    }
    
    /**
     * Gets the dimension of this bounding box.
     * 
     * @return number of dimensions
     */
    public int getDimension() {
        return min.values().length;
    }
    
    /**
     * Gets the center coordinate of this bounding box.
     * 
     * @return center coordinate
     */
    public Coordinate getCenter() {
        var minValues = min.values();
        var maxValues = max.values();
        var centerValues = new double[minValues.length];
        
        for (int i = 0; i < minValues.length; i++) {
            centerValues[i] = (minValues[i] + maxValues[i]) / 2.0;
        }
        
        return new Coordinate(centerValues);
    }
    
    /**
     * Gets the extents (half-sizes) of this bounding box.
     * 
     * @return extents in each dimension
     */
    public double[] getExtents() {
        var minValues = min.values();
        var maxValues = max.values();
        var extents = new double[minValues.length];
        
        for (int i = 0; i < minValues.length; i++) {
            extents[i] = (maxValues[i] - minValues[i]) / 2.0;
        }
        
        return extents;
    }
    
    /**
     * Gets the size (full extent) in each dimension.
     * 
     * @return sizes in each dimension
     */
    public double[] getSize() {
        var minValues = min.values();
        var maxValues = max.values();
        var sizes = new double[minValues.length];
        
        for (int i = 0; i < minValues.length; i++) {
            sizes[i] = maxValues[i] - minValues[i];
        }
        
        return sizes;
    }
    
    /**
     * Gets the volume (or area in 2D, length in 1D) of this bounding box.
     * 
     * @return volume
     */
    public double getVolume() {
        var sizes = getSize();
        var volume = 1.0;
        for (var size : sizes) {
            volume *= size;
        }
        return volume;
    }
    
    /**
     * Gets the diagonal length of this bounding box.
     * 
     * @return diagonal length
     */
    public double getDiagonalLength() {
        var sizes = getSize();
        var sumSquares = 0.0;
        for (var size : sizes) {
            sumSquares += size * size;
        }
        return Math.sqrt(sumSquares);
    }
    
    /**
     * Checks if this bounding box contains a coordinate.
     * 
     * @param coordinate the coordinate to test
     * @return true if contained
     */
    public boolean contains(Coordinate coordinate) {
        var values = coordinate.values();
        var minValues = min.values();
        var maxValues = max.values();
        
        if (values.length != minValues.length) {
            return false;
        }
        
        for (int i = 0; i < values.length; i++) {
            if (values[i] < minValues[i] || values[i] > maxValues[i]) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Checks if this bounding box intersects with another.
     * 
     * @param other the other bounding box
     * @return true if they intersect
     */
    public boolean intersects(BoundingBox other) {
        if (getDimension() != other.getDimension()) {
            return false;
        }
        
        var minValues = min.values();
        var maxValues = max.values();
        var otherMinValues = other.min.values();
        var otherMaxValues = other.max.values();
        
        for (int i = 0; i < minValues.length; i++) {
            if (maxValues[i] < otherMinValues[i] || minValues[i] > otherMaxValues[i]) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Computes the union of this bounding box with another.
     * 
     * @param other the other bounding box
     * @return union bounding box
     */
    public BoundingBox union(BoundingBox other) {
        if (getDimension() != other.getDimension()) {
            throw new IllegalArgumentException("Cannot union bounding boxes of different dimensions");
        }
        
        var minValues = min.values();
        var maxValues = max.values();
        var otherMinValues = other.min.values();
        var otherMaxValues = other.max.values();
        
        var unionMinValues = new double[minValues.length];
        var unionMaxValues = new double[minValues.length];
        
        for (int i = 0; i < minValues.length; i++) {
            unionMinValues[i] = Math.min(minValues[i], otherMinValues[i]);
            unionMaxValues[i] = Math.max(maxValues[i], otherMaxValues[i]);
        }
        
        return new BoundingBox(new Coordinate(unionMinValues), new Coordinate(unionMaxValues));
    }
    
    /**
     * Computes the intersection of this bounding box with another.
     * 
     * @param other the other bounding box
     * @return intersection bounding box, or null if no intersection
     */
    public BoundingBox intersection(BoundingBox other) {
        if (!intersects(other)) {
            return null;
        }
        
        var minValues = min.values();
        var maxValues = max.values();
        var otherMinValues = other.min.values();
        var otherMaxValues = other.max.values();
        
        var intersectionMinValues = new double[minValues.length];
        var intersectionMaxValues = new double[minValues.length];
        
        for (int i = 0; i < minValues.length; i++) {
            intersectionMinValues[i] = Math.max(minValues[i], otherMinValues[i]);
            intersectionMaxValues[i] = Math.min(maxValues[i], otherMaxValues[i]);
        }
        
        return new BoundingBox(new Coordinate(intersectionMinValues), new Coordinate(intersectionMaxValues));
    }
    
    /**
     * Expands this bounding box by a given amount in all directions.
     * 
     * @param expansion the amount to expand
     * @return expanded bounding box
     */
    public BoundingBox expand(double expansion) {
        var minValues = min.values();
        var maxValues = max.values();
        var expandedMinValues = new double[minValues.length];
        var expandedMaxValues = new double[minValues.length];
        
        for (int i = 0; i < minValues.length; i++) {
            expandedMinValues[i] = minValues[i] - expansion;
            expandedMaxValues[i] = maxValues[i] + expansion;
        }
        
        return new BoundingBox(new Coordinate(expandedMinValues), new Coordinate(expandedMaxValues));
    }
    
    /**
     * Transforms this bounding box using a coordinate transformation.
     * Note: This may not preserve axis-alignment for non-axis-aligned transformations.
     * 
     * @param transformation the transformation to apply
     * @return transformed bounding box
     */
    public BoundingBox transform(CoordinateTransformation transformation) {
        // For general transformations, we need to transform all corners and recompute bounds
        var corners = getCorners();
        var transformedCorners = java.util.List.of(corners).stream()
            .map(corner -> {
                try {
                    return transformation.transform(corner);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to transform bounding box corner", e);
                }
            })
            .toList();
        
        return fromCoordinates(transformedCorners);
    }
    
    /**
     * Gets all corner coordinates of this bounding box.
     * 
     * @return array of corner coordinates
     */
    public Coordinate[] getCorners() {
        var dimension = getDimension();
        var numCorners = 1 << dimension; // 2^dimension
        var corners = new Coordinate[numCorners];
        
        var minValues = min.values();
        var maxValues = max.values();
        
        for (int i = 0; i < numCorners; i++) {
            var cornerValues = new double[dimension];
            for (int d = 0; d < dimension; d++) {
                cornerValues[d] = ((i >> d) & 1) == 0 ? minValues[d] : maxValues[d];
            }
            corners[i] = new Coordinate(cornerValues);
        }
        
        return corners;
    }
    
    /**
     * Checks if this bounding box is degenerate (has zero volume).
     * 
     * @return true if degenerate
     */
    public boolean isDegenerate() {
        return getVolume() < 1e-10;
    }
    
    @Override
    public String toString() {
        return "BoundingBox{" +
               "min=" + min +
               ", max=" + max +
               ", center=" + getCenter() +
               ", volume=" + String.format("%.3f", getVolume()) +
               '}';
    }
}