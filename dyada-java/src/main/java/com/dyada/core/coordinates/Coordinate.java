package com.dyada.core.coordinates;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable record representing a point in multi-dimensional space.
 * 
 * Coordinates are represented as double values, typically within the unit hypercube [0,1]^d
 * for DyAda's spatial operations, but can represent arbitrary real coordinates.
 */
public record Coordinate(double[] values) {
    
    /**
     * Compact constructor with validation and defensive copying.
     */
    public Coordinate {
        if (values == null) {
            throw new IllegalArgumentException("Coordinate values cannot be null");
        }
        if (values.length == 0) {
            throw new IllegalArgumentException("Coordinate must have at least one dimension");
        }
        
        // Defensive copy
        values = values.clone();
    }
    
    /**
     * Creates a coordinate from the given values.
     */
    public static Coordinate of(double... values) {
        return new Coordinate(values);
    }
    
    /**
     * Creates a coordinate at the origin (all zeros) with the specified dimensions.
     */
    public static Coordinate origin(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive: " + dimensions);
        }
        return new Coordinate(new double[dimensions]);
    }
    
    /**
     * Creates a coordinate with all values set to the specified value.
     */
    public static Coordinate uniform(int dimensions, double value) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive: " + dimensions);
        }
        
        var coords = new double[dimensions];
        Arrays.fill(coords, value);
        return new Coordinate(coords);
    }
    
    /**
     * Creates a coordinate representing the center of the unit hypercube.
     */
    public static Coordinate unitCenter(int dimensions) {
        return uniform(dimensions, 0.5);
    }
    
    /**
     * Returns the number of dimensions.
     */
    public int dimensions() {
        return values.length;
    }
    
    /**
     * Returns the coordinate value for the specified dimension.
     */
    public double get(int dimension) {
        checkDimension(dimension);
        return values[dimension];
    }
    
    /**
     * Returns a new Coordinate with the specified dimension set to the given value.
     */
    public Coordinate with(int dimension, double value) {
        checkDimension(dimension);
        
        var newValues = values.clone();
        newValues[dimension] = value;
        return new Coordinate(newValues);
    }
    
    /**
     * Returns the sum of this coordinate and the other coordinate.
     */
    public Coordinate add(Coordinate other) {
        checkSameDimensions(other);
        var result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] + other.values[i];
        }
        return new Coordinate(result);
    }
    
    /**
     * Returns the difference of this coordinate and the other coordinate.
     */
    public Coordinate subtract(Coordinate other) {
        checkSameDimensions(other);
        var result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] - other.values[i];
        }
        return new Coordinate(result);
    }
    
    /**
     * Returns this coordinate multiplied by a scalar.
     */
    public Coordinate multiply(double scalar) {
        if (!Double.isFinite(scalar)) {
            throw new IllegalArgumentException("Scalar must be finite: " + scalar);
        }
        
        var result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] * scalar;
        }
        return new Coordinate(result);
    }
    
    /**
     * Returns this coordinate divided by a scalar.
     */
    public Coordinate divide(double scalar) {
        if (!Double.isFinite(scalar) || scalar == 0.0) {
            throw new IllegalArgumentException("Scalar must be finite and non-zero: " + scalar);
        }
        
        return multiply(1.0 / scalar);
    }
    
    /**
     * Returns the Euclidean distance between this coordinate and the other coordinate.
     */
    public double distance(Coordinate other) {
        checkSameDimensions(other);
        double sumSquares = 0.0;
        for (int i = 0; i < values.length; i++) {
            double diff = values[i] - other.values[i];
            sumSquares += diff * diff;
        }
        return Math.sqrt(sumSquares);
    }
    
    /**
     * Returns the squared Euclidean distance (avoids square root computation).
     */
    public double distanceSquared(Coordinate other) {
        checkSameDimensions(other);
        double sumSquares = 0.0;
        for (int i = 0; i < values.length; i++) {
            double diff = values[i] - other.values[i];
            sumSquares += diff * diff;
        }
        return sumSquares;
    }
    
    /**
     * Returns the Manhattan (L1) distance between this coordinate and the other coordinate.
     */
    public double manhattanDistance(Coordinate other) {
        checkSameDimensions(other);
        double sum = 0.0;
        for (int i = 0; i < values.length; i++) {
            sum += Math.abs(values[i] - other.values[i]);
        }
        return sum;
    }
    
    /**
     * Returns the magnitude (Euclidean norm) of this coordinate.
     */
    public double magnitude() {
        double sumSquares = 0.0;
        for (double value : values) {
            if (Double.isInfinite(value)) {
                return Double.POSITIVE_INFINITY;
            }
            if (Double.isNaN(value)) {
                return Double.NaN;
            }
            sumSquares += value * value;
        }
        return Math.sqrt(sumSquares);
    }
    
    /**
     * Returns a normalized version of this coordinate (unit vector in same direction).
     */
    public Coordinate normalize() {
        double mag = magnitude();
        if (mag == 0.0) {
            throw new ArithmeticException("Cannot normalize zero vector");
        }
        return divide(mag);
    }
    
    /**
     * Returns the dot product of this coordinate and the other coordinate.
     */
    public double dot(Coordinate other) {
        checkSameDimensions(other);
        double sum = 0.0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i] * other.values[i];
        }
        return sum;
    }
    
    /**
     * Returns the dot product of this coordinate and the other coordinate.
     * Alias for dot() method for compatibility.
     */
    public double dotProduct(Coordinate other) {
        return dot(other);
    }
    
    /**
     * Returns the cross product of this coordinate and the other coordinate.
     * Only valid for 3D coordinates.
     */
    public Coordinate crossProduct(Coordinate other) {
        if (values.length != 3 || other.values.length != 3) {
            throw new IllegalArgumentException("Cross product is only defined for 3D coordinates");
        }
        
        double x = values[1] * other.values[2] - values[2] * other.values[1];
        double y = values[2] * other.values[0] - values[0] * other.values[2];
        double z = values[0] * other.values[1] - values[1] * other.values[0];
        
        return new Coordinate(new double[]{x, y, z});
    }
    
    /**
     * Returns linear interpolation between this coordinate and the other coordinate.
     * @param other the target coordinate
     * @param t interpolation parameter (0.0 = this, 1.0 = other)
     */
    public Coordinate lerp(Coordinate other, double t) {
        checkSameDimensions(other);
        if (!Double.isFinite(t)) {
            throw new IllegalArgumentException("Interpolation parameter must be finite: " + t);
        }
        
        var result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] + t * (other.values[i] - values[i]);
        }
        return new Coordinate(result);
    }
    
    /**
     * Returns true if all coordinate values are within the unit hypercube [0,1]^d.
     */
    public boolean isInUnitCube() {
        for (double value : values) {
            if (value < 0.0 || value > 1.0) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Returns true if this coordinate is approximately equal to the other coordinate
     * within the specified tolerance.
     */
    public boolean isApproximatelyEqual(Coordinate other, double tolerance) {
        if (tolerance < 0.0) {
            throw new IllegalArgumentException("Tolerance must be non-negative: " + tolerance);
        }
        checkSameDimensions(other);
        
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - other.values[i]) > tolerance) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Returns a copy of the coordinate values array.
     */
    public double[] getValues() {
        return values.clone();
    }
    
    /**
     * Projects this coordinate onto a lower-dimensional space by selecting specific dimensions.
     */
    public Coordinate project(int... dimensions) {
        if (dimensions.length == 0) {
            throw new IllegalArgumentException("Must specify at least one dimension for projection");
        }
        
        var result = new double[dimensions.length];
        for (int i = 0; i < dimensions.length; i++) {
            checkDimension(dimensions[i]);
            result[i] = values[dimensions[i]];
        }
        return new Coordinate(result);
    }
    
    @Override
    public String toString() {
        return "Coordinate" + Arrays.toString(values);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Coordinate other)) return false;
        return Arrays.equals(values, other.values);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
    
    private void checkDimension(int dimension) {
        if (dimension < 0 || dimension >= values.length) {
            throw new IllegalArgumentException(
                String.format("Dimension %d out of range [0, %d)", dimension, values.length));
        }
    }
    
    private void checkSameDimensions(Coordinate other) {
        if (values.length != other.values.length) {
            throw new IllegalArgumentException(
                String.format("Coordinates must have same dimensions: %d vs %d", 
                    values.length, other.values.length));
        }
    }
}