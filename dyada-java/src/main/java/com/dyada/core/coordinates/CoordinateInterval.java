package com.dyada.core.coordinates;

import java.util.Objects;

/**
 * Immutable record representing a multi-dimensional interval (hyperrectangle) in space.
 * 
 * Defined by lower and upper bound coordinates, representing a closed interval [lower, upper]
 * in each dimension. This is used to represent spatial boxes in DyAda's discretization.
 */
public record CoordinateInterval(
    Coordinate lowerBound,
    Coordinate upperBound
) {
    
    /**
     * Compact constructor with validation.
     */
    public CoordinateInterval {
        Objects.requireNonNull(lowerBound, "Lower bound cannot be null");
        Objects.requireNonNull(upperBound, "Upper bound cannot be null");
        
        if (lowerBound.dimensions() != upperBound.dimensions()) {
            throw new IllegalArgumentException(
                String.format("Bounds must have same dimensions: %d vs %d", 
                    lowerBound.dimensions(), upperBound.dimensions()));
        }
        
        // Validate that lower bound <= upper bound in each dimension
        for (int i = 0; i < lowerBound.dimensions(); i++) {
            if (lowerBound.get(i) > upperBound.get(i)) {
                throw new IllegalArgumentException(
                    String.format("Lower bound %f > upper bound %f at dimension %d", 
                        lowerBound.get(i), upperBound.get(i), i));
            }
        }
    }
    
    /**
     * Creates an interval from coordinate arrays.
     */
    public static CoordinateInterval of(double[] lower, double[] upper) {
        return new CoordinateInterval(Coordinate.of(lower), Coordinate.of(upper));
    }
    
    /**
     * Creates an interval from individual coordinates.
     */
    public static CoordinateInterval of(Coordinate lower, Coordinate upper) {
        return new CoordinateInterval(lower, upper);
    }
    
    /**
     * Creates a unit hypercube [0,1]^d.
     */
    public static CoordinateInterval unitCube(int dimensions) {
        return new CoordinateInterval(
            Coordinate.origin(dimensions),
            Coordinate.uniform(dimensions, 1.0)
        );
    }
    
    /**
     * Creates a centered interval around a point with specified half-widths.
     */
    public static CoordinateInterval centered(Coordinate center, double... halfWidths) {
        if (halfWidths.length != center.dimensions()) {
            throw new IllegalArgumentException("Half-widths must match center dimensions");
        }
        
        var lower = new double[center.dimensions()];
        var upper = new double[center.dimensions()];
        
        for (int i = 0; i < center.dimensions(); i++) {
            if (halfWidths[i] < 0.0) {
                throw new IllegalArgumentException("Half-width cannot be negative: " + halfWidths[i]);
            }
            lower[i] = center.get(i) - halfWidths[i];
            upper[i] = center.get(i) + halfWidths[i];
        }
        
        return new CoordinateInterval(Coordinate.of(lower), Coordinate.of(upper));
    }
    
    /**
     * Creates a square/cubic interval centered at a point with uniform half-width.
     */
    public static CoordinateInterval centeredUniform(Coordinate center, double halfWidth) {
        if (halfWidth < 0.0) {
            throw new IllegalArgumentException("Half-width cannot be negative: " + halfWidth);
        }
        
        var halfWidths = new double[center.dimensions()];
        for (int i = 0; i < halfWidths.length; i++) {
            halfWidths[i] = halfWidth;
        }
        
        return centered(center, halfWidths);
    }
    
    /**
     * Returns the number of dimensions.
     */
    public int dimensions() {
        return lowerBound.dimensions();
    }
    
    /**
     * Returns the center point of the interval.
     */
    public Coordinate center() {
        return lowerBound.add(upperBound).divide(2.0);
    }
    
    /**
     * Returns the size (width) of the interval in each dimension.
     */
    public Coordinate size() {
        return upperBound.subtract(lowerBound);
    }
    
    /**
     * Returns the half-size (radius) of the interval in each dimension.
     */
    public Coordinate halfSize() {
        return size().divide(2.0);
    }
    
    /**
     * Returns the volume (hypervolume) of the interval.
     */
    public double volume() {
        double volume = 1.0;
        for (int i = 0; i < dimensions(); i++) {
            double width = upperBound.get(i) - lowerBound.get(i);
            volume *= width;
        }
        return volume;
    }
    
    /**
     * Returns true if the interval contains the specified point.
     * Uses closed interval semantics: [lower, upper].
     */
    public boolean contains(Coordinate point) {
        if (point.dimensions() != dimensions()) {
            throw new IllegalArgumentException(
                String.format("Point dimensions %d != interval dimensions %d", 
                    point.dimensions(), dimensions()));
        }
        
        for (int i = 0; i < dimensions(); i++) {
            double coord = point.get(i);
            if (coord < lowerBound.get(i) || coord > upperBound.get(i)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Returns true if the interval strictly contains the point (open interval semantics).
     */
    public boolean strictlyContains(Coordinate point) {
        if (point.dimensions() != dimensions()) {
            throw new IllegalArgumentException(
                String.format("Point dimensions %d != interval dimensions %d", 
                    point.dimensions(), dimensions()));
        }
        
        for (int i = 0; i < dimensions(); i++) {
            double coord = point.get(i);
            if (coord <= lowerBound.get(i) || coord >= upperBound.get(i)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Returns true if this interval overlaps with the other interval.
     */
    public boolean overlaps(CoordinateInterval other) {
        if (other.dimensions() != dimensions()) {
            throw new IllegalArgumentException(
                String.format("Interval dimensions %d != %d", other.dimensions(), dimensions()));
        }
        
        for (int i = 0; i < dimensions(); i++) {
            // No overlap if separated in any dimension
            if (upperBound.get(i) < other.lowerBound.get(i) || 
                lowerBound.get(i) > other.upperBound.get(i)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Returns the intersection of this interval with the other interval.
     * Returns null if the intervals don't overlap.
     */
    public CoordinateInterval intersect(CoordinateInterval other) {
        if (other.dimensions() != dimensions()) {
            throw new IllegalArgumentException(
                String.format("Interval dimensions %d != %d", other.dimensions(), dimensions()));
        }
        
        if (!overlaps(other)) {
            return null; // No intersection
        }
        
        var newLower = new double[dimensions()];
        var newUpper = new double[dimensions()];
        
        for (int i = 0; i < dimensions(); i++) {
            newLower[i] = Math.max(lowerBound.get(i), other.lowerBound.get(i));
            newUpper[i] = Math.min(upperBound.get(i), other.upperBound.get(i));
        }
        
        return new CoordinateInterval(Coordinate.of(newLower), Coordinate.of(newUpper));
    }
    
    /**
     * Returns the union bounding box of this interval and the other interval.
     */
    public CoordinateInterval union(CoordinateInterval other) {
        if (other.dimensions() != dimensions()) {
            throw new IllegalArgumentException(
                String.format("Interval dimensions %d != %d", other.dimensions(), dimensions()));
        }
        
        var newLower = new double[dimensions()];
        var newUpper = new double[dimensions()];
        
        for (int i = 0; i < dimensions(); i++) {
            newLower[i] = Math.min(lowerBound.get(i), other.lowerBound.get(i));
            newUpper[i] = Math.max(upperBound.get(i), other.upperBound.get(i));
        }
        
        return new CoordinateInterval(Coordinate.of(newLower), Coordinate.of(newUpper));
    }
    
    /**
     * Returns an expanded interval with the specified margin added in all dimensions.
     */
    public CoordinateInterval expand(double margin) {
        if (margin < 0.0) {
            throw new IllegalArgumentException("Margin cannot be negative: " + margin);
        }
        
        return new CoordinateInterval(
            lowerBound.subtract(Coordinate.uniform(dimensions(), margin)),
            upperBound.add(Coordinate.uniform(dimensions(), margin))
        );
    }
    
    /**
     * Returns a contracted interval with the specified margin removed from all dimensions.
     */
    public CoordinateInterval contract(double margin) {
        if (margin < 0.0) {
            throw new IllegalArgumentException("Margin cannot be negative: " + margin);
        }
        
        var newLower = lowerBound.add(Coordinate.uniform(dimensions(), margin));
        var newUpper = upperBound.subtract(Coordinate.uniform(dimensions(), margin));
        
        // Check if contraction would create invalid interval
        for (int i = 0; i < dimensions(); i++) {
            if (newLower.get(i) > newUpper.get(i)) {
                throw new IllegalArgumentException(
                    String.format("Contraction by %f would make interval invalid at dimension %d", 
                        margin, i));
            }
        }
        
        return new CoordinateInterval(newLower, newUpper);
    }
    
    /**
     * Returns a scaled version of the interval around its center.
     */
    public CoordinateInterval scale(double factor) {
        if (factor <= 0.0) {
            throw new IllegalArgumentException("Scale factor must be positive: " + factor);
        }
        
        var center = center();
        var halfSize = halfSize().multiply(factor);
        
        return new CoordinateInterval(
            center.subtract(halfSize),
            center.add(halfSize)
        );
    }
    
    /**
     * Projects the interval onto the specified dimensions.
     */
    public CoordinateInterval project(int... dimensions) {
        return new CoordinateInterval(
            lowerBound.project(dimensions),
            upperBound.project(dimensions)
        );
    }
    
    /**
     * Returns true if this interval is entirely within the unit hypercube [0,1]^d.
     */
    public boolean isInUnitCube() {
        return lowerBound.isInUnitCube() && upperBound.isInUnitCube();
    }
    
    /**
     * Returns the distance from a point to this interval (0 if point is inside).
     */
    public double distanceToPoint(Coordinate point) {
        if (point.dimensions() != dimensions()) {
            throw new IllegalArgumentException(
                String.format("Point dimensions %d != interval dimensions %d", 
                    point.dimensions(), dimensions()));
        }
        
        double distanceSquared = 0.0;
        for (int i = 0; i < dimensions(); i++) {
            double coord = point.get(i);
            double lower = lowerBound.get(i);
            double upper = upperBound.get(i);
            
            if (coord < lower) {
                double diff = lower - coord;
                distanceSquared += diff * diff;
            } else if (coord > upper) {
                double diff = coord - upper;
                distanceSquared += diff * diff;
            }
            // If coord is within [lower, upper], contribute 0 to distance
        }
        
        return Math.sqrt(distanceSquared);
    }
    
    @Override
    public String toString() {
        return String.format("CoordinateInterval{%s, %s}", lowerBound, upperBound);
    }
}