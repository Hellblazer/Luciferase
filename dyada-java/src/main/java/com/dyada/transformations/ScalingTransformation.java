package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;

import java.util.Arrays;
import java.util.Optional;

/**
 * A specialized transformation for scaling operations.
 * Supports uniform and non-uniform scaling in n-dimensional space.
 */
public final class ScalingTransformation implements CoordinateTransformation {
    
    private final double[] scaleFactors;
    private final int dimension;
    
    /**
     * Creates a scaling transformation with the given scale factors.
     * 
     * @param scaleFactors the scale factors for each dimension
     * @throws IllegalArgumentException if any scale factor is zero or if array is empty
     */
    public ScalingTransformation(double... scaleFactors) {
        if (scaleFactors == null || scaleFactors.length == 0) {
            throw new IllegalArgumentException("Scale factors cannot be null or empty");
        }
        
        for (var factor : scaleFactors) {
            if (Math.abs(factor) < 1e-10) {
                throw new IllegalArgumentException("Scale factors cannot be zero");
            }
        }
        
        this.scaleFactors = scaleFactors.clone();
        this.dimension = scaleFactors.length;
    }
    
    /**
     * Creates a uniform scaling transformation.
     * 
     * @param dimension the number of dimensions
     * @param scaleFactor the uniform scale factor
     * @return the scaling transformation
     */
    public static ScalingTransformation uniform(int dimension, double scaleFactor) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Dimension must be positive");
        }
        
        var factors = new double[dimension];
        Arrays.fill(factors, scaleFactor);
        return new ScalingTransformation(factors);
    }
    
    /**
     * Creates a 2D scaling transformation.
     * 
     * @param scaleX the X-axis scale factor
     * @param scaleY the Y-axis scale factor
     * @return the scaling transformation
     */
    public static ScalingTransformation scale2D(double scaleX, double scaleY) {
        return new ScalingTransformation(scaleX, scaleY);
    }
    
    /**
     * Creates a 3D scaling transformation.
     * 
     * @param scaleX the X-axis scale factor
     * @param scaleY the Y-axis scale factor
     * @param scaleZ the Z-axis scale factor
     * @return the scaling transformation
     */
    public static ScalingTransformation scale3D(double scaleX, double scaleY, double scaleZ) {
        return new ScalingTransformation(scaleX, scaleY, scaleZ);
    }
    
    /**
     * Creates a scaling transformation that stretches along a specific axis.
     * 
     * @param dimension the total number of dimensions
     * @param axis the axis to stretch (0-based index)
     * @param scaleFactor the scale factor for the specified axis
     * @return the scaling transformation
     */
    public static ScalingTransformation stretchAxis(int dimension, int axis, double scaleFactor) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Dimension must be positive");
        }
        if (axis < 0 || axis >= dimension) {
            throw new IllegalArgumentException("Axis index out of bounds");
        }
        
        var factors = new double[dimension];
        Arrays.fill(factors, 1.0);
        factors[axis] = scaleFactor;
        return new ScalingTransformation(factors);
    }
    
    /**
     * Creates a scaling transformation that mirrors across specified axes.
     * 
     * @param dimension the total number of dimensions
     * @param mirrorAxes the axes to mirror (0-based indices)
     * @return the scaling transformation
     */
    public static ScalingTransformation mirror(int dimension, int... mirrorAxes) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Dimension must be positive");
        }
        
        var factors = new double[dimension];
        Arrays.fill(factors, 1.0);
        
        for (var axis : mirrorAxes) {
            if (axis < 0 || axis >= dimension) {
                throw new IllegalArgumentException("Mirror axis index out of bounds: " + axis);
            }
            factors[axis] = -1.0;
        }
        
        return new ScalingTransformation(factors);
    }
    
    @Override
    public Coordinate transform(Coordinate source) throws TransformationException {
        var sourceValues = source.values();
        if (sourceValues.length != dimension) {
            throw new TransformationException(
                "Coordinate dimension (" + sourceValues.length + 
                ") does not match transformation dimension (" + dimension + ")"
            );
        }
        
        var result = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            result[i] = sourceValues[i] * scaleFactors[i];
        }
        
        return new Coordinate(result);
    }
    
    @Override
    public Coordinate inverseTransform(Coordinate target) throws TransformationException {
        var targetValues = target.values();
        if (targetValues.length != dimension) {
            throw new TransformationException(
                "Coordinate dimension (" + targetValues.length + 
                ") does not match transformation dimension (" + dimension + ")"
            );
        }
        
        var result = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            result[i] = targetValues[i] / scaleFactors[i];
        }
        
        return new Coordinate(result);
    }
    
    @Override
    public Optional<CoordinateTransformation> inverse() {
        var inverseFactors = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            inverseFactors[i] = 1.0 / scaleFactors[i];
        }
        return Optional.of(new ScalingTransformation(inverseFactors));
    }
    
    @Override
    public Bounds transformBounds(Bounds bounds) throws TransformationException {
        if (bounds.dimensions() != dimension) {
            throw new TransformationException(
                "Bounds dimension (" + bounds.dimensions() + 
                ") does not match transformation dimension (" + dimension + ")"
            );
        }
        
        var min = bounds.min();
        var max = bounds.max();
        var scaledMin = new double[dimension];
        var scaledMax = new double[dimension];
        
        for (int i = 0; i < dimension; i++) {
            if (scaleFactors[i] > 0) {
                scaledMin[i] = min[i] * scaleFactors[i];
                scaledMax[i] = max[i] * scaleFactors[i];
            } else {
                // Negative scale factor flips the bounds
                scaledMin[i] = max[i] * scaleFactors[i];
                scaledMax[i] = min[i] * scaleFactors[i];
            }
        }
        
        return new Bounds(scaledMin, scaledMax);
    }
    
    @Override
    public CoordinateTransformation compose(CoordinateTransformation other) {
        if (other instanceof ScalingTransformation scaling && scaling.dimension == this.dimension) {
            // Optimize composition of two scaling transformations
            var combinedFactors = new double[dimension];
            for (int i = 0; i < dimension; i++) {
                combinedFactors[i] = this.scaleFactors[i] * scaling.scaleFactors[i];
            }
            return new ScalingTransformation(combinedFactors);
        } else {
            return new CompositeTransformation(this, other);
        }
    }
    
    @Override
    public double determinant() {
        var determinant = 1.0;
        for (var factor : scaleFactors) {
            determinant *= factor;
        }
        return determinant;
    }
    
    @Override
    public boolean isIsometric() {
        // Scaling is isometric only if all scale factors are ±1
        for (var factor : scaleFactors) {
            if (Math.abs(Math.abs(factor) - 1.0) > 1e-10) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public boolean isConformal() {
        // Scaling is conformal if it's uniform scaling
        return isUniform();
    }
    
    @Override
    public boolean isInvertible() {
        return true; // All scale factors are non-zero by construction
    }
    
    @Override
    public int getSourceDimension() {
        return dimension;
    }
    
    @Override
    public int getTargetDimension() {
        return dimension;
    }
    
    @Override
    public String getDescription() {
        var sb = new StringBuilder();
        sb.append("ScalingTransformation(").append(dimension).append("D): [");
        for (int i = 0; i < scaleFactors.length; i++) {
            sb.append(String.format("%.4f", scaleFactors[i]));
            if (i < scaleFactors.length - 1) sb.append(", ");
        }
        sb.append("]\n");
        sb.append("Uniform: ").append(isUniform());
        if (isUniform()) {
            sb.append(", Factor: ").append(String.format("%.4f", getUniformScaleFactor()));
        }
        sb.append(", Determinant: ").append(String.format("%.4f", determinant()));
        return sb.toString();
    }
    
    /**
     * Gets the scale factors.
     * 
     * @return a copy of the scale factors array
     */
    public double[] getScaleFactors() {
        return scaleFactors.clone();
    }
    
    /**
     * Gets the scale factor for a specific dimension.
     * 
     * @param dimension the dimension index (0-based)
     * @return the scale factor
     * @throws IndexOutOfBoundsException if dimension is invalid
     */
    public double getScaleFactor(int dimension) {
        return scaleFactors[dimension];
    }
    
    /**
     * Checks if this is a uniform scaling transformation.
     * 
     * @return true if all scale factors are equal
     */
    public boolean isUniform() {
        if (dimension <= 1) return true;
        
        var first = scaleFactors[0];
        for (int i = 1; i < dimension; i++) {
            if (Math.abs(scaleFactors[i] - first) > 1e-10) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Gets the uniform scale factor if this is a uniform scaling.
     * 
     * @return the uniform scale factor
     * @throws IllegalStateException if this is not a uniform scaling
     */
    public double getUniformScaleFactor() {
        if (!isUniform()) {
            throw new IllegalStateException("This is not a uniform scaling transformation");
        }
        return scaleFactors[0];
    }
    
    /**
     * Checks if this transformation includes mirroring (negative scale factors).
     * 
     * @return true if any scale factor is negative
     */
    public boolean hasMirroring() {
        for (var factor : scaleFactors) {
            if (factor < 0) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Gets the volume scaling factor (absolute value of determinant).
     * 
     * @return the volume scaling factor
     */
    public double getVolumeScaleFactor() {
        return Math.abs(determinant());
    }
    
    @Override
    public String toString() {
        return "ScalingTransformation{" +
               "scaleFactors=" + Arrays.toString(scaleFactors) +
               ", uniform=" + isUniform() +
               '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        var that = (ScalingTransformation) obj;
        return Arrays.equals(scaleFactors, that.scaleFactors);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(scaleFactors);
    }
}