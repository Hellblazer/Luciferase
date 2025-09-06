package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;

import java.util.Arrays;
import java.util.Optional;

/**
 * A specialized transformation for translation operations.
 * Represents pure translation without rotation or scaling.
 */
public final class TranslationTransformation implements CoordinateTransformation {
    
    private final double[] translation;
    private final int dimension;
    
    /**
     * Creates a translation transformation with the given translation vector.
     * 
     * @param translation the translation vector
     * @throws IllegalArgumentException if translation is null or empty
     */
    public TranslationTransformation(double... translation) {
        if (translation == null || translation.length == 0) {
            throw new IllegalArgumentException("Translation vector cannot be null or empty");
        }
        
        this.translation = translation.clone();
        this.dimension = translation.length;
    }
    
    /**
     * Creates a translation transformation from a coordinate.
     * 
     * @param translationVector the translation vector as a coordinate
     */
    public TranslationTransformation(Coordinate translationVector) {
        this(translationVector.values());
    }
    
    /**
     * Creates a 2D translation transformation.
     * 
     * @param deltaX the X-axis translation
     * @param deltaY the Y-axis translation
     * @return the translation transformation
     */
    public static TranslationTransformation translate2D(double deltaX, double deltaY) {
        return new TranslationTransformation(deltaX, deltaY);
    }
    
    /**
     * Creates a 3D translation transformation.
     * 
     * @param deltaX the X-axis translation
     * @param deltaY the Y-axis translation
     * @param deltaZ the Z-axis translation
     * @return the translation transformation
     */
    public static TranslationTransformation translate3D(double deltaX, double deltaY, double deltaZ) {
        return new TranslationTransformation(deltaX, deltaY, deltaZ);
    }
    
    /**
     * Creates a translation along a single axis.
     * 
     * @param dimension the total number of dimensions
     * @param axis the axis to translate along (0-based index)
     * @param distance the translation distance
     * @return the translation transformation
     */
    public static TranslationTransformation translateAxis(int dimension, int axis, double distance) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Dimension must be positive");
        }
        if (axis < 0 || axis >= dimension) {
            throw new IllegalArgumentException("Axis index out of bounds");
        }
        
        var translation = new double[dimension];
        translation[axis] = distance;
        return new TranslationTransformation(translation);
    }
    
    /**
     * Creates a translation that moves from one point to another.
     * 
     * @param from the source point
     * @param to the target point
     * @return the translation transformation
     */
    public static TranslationTransformation fromTo(Coordinate from, Coordinate to) {
        var fromValues = from.values();
        var toValues = to.values();
        
        if (fromValues.length != toValues.length) {
            throw new IllegalArgumentException("Source and target coordinates must have same dimension");
        }
        
        var translation = new double[fromValues.length];
        for (int i = 0; i < fromValues.length; i++) {
            translation[i] = toValues[i] - fromValues[i];
        }
        
        return new TranslationTransformation(translation);
    }
    
    /**
     * Creates the identity translation (zero translation).
     * 
     * @param dimension the number of dimensions
     * @return the identity translation transformation
     */
    public static TranslationTransformation identity(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Dimension must be positive");
        }
        
        return new TranslationTransformation(new double[dimension]);
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
            result[i] = sourceValues[i] + translation[i];
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
            result[i] = targetValues[i] - translation[i];
        }
        
        return new Coordinate(result);
    }
    
    @Override
    public Optional<CoordinateTransformation> inverse() {
        var inverseTranslation = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            inverseTranslation[i] = -translation[i];
        }
        return Optional.of(new TranslationTransformation(inverseTranslation));
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
        var translatedMin = new double[dimension];
        var translatedMax = new double[dimension];
        
        for (int i = 0; i < dimension; i++) {
            translatedMin[i] = min[i] + translation[i];
            translatedMax[i] = max[i] + translation[i];
        }
        
        return new Bounds(translatedMin, translatedMax);
    }
    
    @Override
    public CoordinateTransformation compose(CoordinateTransformation other) {
        if (other instanceof TranslationTransformation otherTranslation && 
            otherTranslation.dimension == this.dimension) {
            // Optimize composition of two translation transformations
            var combinedTranslation = new double[dimension];
            for (int i = 0; i < dimension; i++) {
                combinedTranslation[i] = this.translation[i] + otherTranslation.translation[i];
            }
            return new TranslationTransformation(combinedTranslation);
        } else {
            return new CompositeTransformation(this, other);
        }
    }
    
    @Override
    public double determinant() {
        return 1.0; // Translation preserves volume
    }
    
    @Override
    public boolean isIsometric() {
        return true; // Translation preserves distances
    }
    
    @Override
    public boolean isConformal() {
        return true; // Translation preserves angles
    }
    
    @Override
    public boolean isInvertible() {
        return true;
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
        sb.append("TranslationTransformation(").append(dimension).append("D): [");
        for (int i = 0; i < translation.length; i++) {
            sb.append(String.format("%.4f", translation[i]));
            if (i < translation.length - 1) sb.append(", ");
        }
        sb.append("]\n");
        sb.append("Magnitude: ").append(String.format("%.4f", getMagnitude()));
        return sb.toString();
    }
    
    /**
     * Gets the translation vector.
     * 
     * @return a copy of the translation vector
     */
    public double[] getTranslation() {
        return translation.clone();
    }
    
    /**
     * Gets the translation component for a specific dimension.
     * 
     * @param dimension the dimension index (0-based)
     * @return the translation component
     * @throws IndexOutOfBoundsException if dimension is invalid
     */
    public double getTranslation(int dimension) {
        return translation[dimension];
    }
    
    /**
     * Gets the translation vector as a coordinate.
     * 
     * @return the translation vector
     */
    public Coordinate getTranslationAsCoordinate() {
        return new Coordinate(translation.clone());
    }
    
    /**
     * Checks if this is the identity translation (zero translation).
     * 
     * @return true if all translation components are zero
     */
    public boolean isIdentity() {
        for (var component : translation) {
            if (Math.abs(component) > 1e-10) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Gets the magnitude of the translation vector.
     * 
     * @return the Euclidean magnitude of the translation
     */
    public double getMagnitude() {
        var sumSquares = 0.0;
        for (var component : translation) {
            sumSquares += component * component;
        }
        return Math.sqrt(sumSquares);
    }
    
    /**
     * Gets the Manhattan distance of the translation.
     * 
     * @return the sum of absolute values of all components
     */
    public double getManhattanDistance() {
        var sum = 0.0;
        for (var component : translation) {
            sum += Math.abs(component);
        }
        return sum;
    }
    
    /**
     * Gets the maximum component of the translation (Chebyshev distance).
     * 
     * @return the maximum absolute value among all components
     */
    public double getChebyshevDistance() {
        var max = 0.0;
        for (var component : translation) {
            max = Math.max(max, Math.abs(component));
        }
        return max;
    }
    
    /**
     * Creates a unit translation in the same direction.
     * 
     * @return a translation with magnitude 1 in the same direction
     * @throws IllegalStateException if this is the zero translation
     */
    public TranslationTransformation normalize() {
        var magnitude = getMagnitude();
        if (magnitude < 1e-10) {
            throw new IllegalStateException("Cannot normalize zero translation");
        }
        
        var normalized = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            normalized[i] = translation[i] / magnitude;
        }
        
        return new TranslationTransformation(normalized);
    }
    
    /**
     * Scales the translation by a factor.
     * 
     * @param factor the scaling factor
     * @return a new translation transformation scaled by the factor
     */
    public TranslationTransformation scale(double factor) {
        var scaled = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            scaled[i] = translation[i] * factor;
        }
        return new TranslationTransformation(scaled);
    }
    
    @Override
    public String toString() {
        return "TranslationTransformation{" +
               "translation=" + Arrays.toString(translation) +
               ", magnitude=" + String.format("%.3f", getMagnitude()) +
               '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        var that = (TranslationTransformation) obj;
        return Arrays.equals(translation, that.translation);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(translation);
    }
}