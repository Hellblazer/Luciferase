package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;

import java.util.Optional;

/**
 * Interface for coordinate transformations in DyAda spatial operations.
 * 
 * Coordinate transformations enable mapping between different coordinate systems,
 * scaling operations, rotations, translations, and complex geometric operations.
 * All transformations preserve the dimensional consistency of coordinates.
 */
public interface CoordinateTransformation {
    
    /**
     * Transforms a coordinate from the source space to the target space.
     * 
     * @param source The coordinate in the source coordinate system
     * @return The transformed coordinate in the target coordinate system
     * @throws TransformationException if the transformation cannot be applied
     */
    Coordinate transform(Coordinate source) throws TransformationException;
    
    /**
     * Transforms a bounds from the source space to the target space.
     * 
     * @param bounds The bounds in the source coordinate system
     * @return The transformed bounds in the target coordinate system
     * @throws TransformationException if the transformation cannot be applied
     */
    Bounds transformBounds(Bounds bounds) throws TransformationException;
    
    /**
     * Applies the inverse transformation to map from target space back to source space.
     * 
     * @param target The coordinate in the target coordinate system
     * @return The coordinate in the source coordinate system
     * @throws TransformationException if the inverse transformation cannot be applied
     */
    Coordinate inverseTransform(Coordinate target) throws TransformationException;
    
    /**
     * Returns the inverse transformation if it exists.
     * 
     * @return Optional containing the inverse transformation, or empty if not invertible
     */
    Optional<CoordinateTransformation> inverse();
    
    /**
     * Composes this transformation with another transformation.
     * The resulting transformation applies this transformation first, then the other.
     * 
     * @param other The transformation to compose with
     * @return A new transformation representing the composition
     */
    CoordinateTransformation compose(CoordinateTransformation other);
    
    /**
     * Returns the determinant of the transformation matrix.
     * For affine transformations, this indicates scaling and orientation changes.
     * 
     * @return The determinant value
     */
    double determinant();
    
    /**
     * Checks if this transformation preserves distances (is isometric).
     * 
     * @return True if the transformation preserves distances
     */
    boolean isIsometric();
    
    /**
     * Checks if this transformation preserves angles (is conformal).
     * 
     * @return True if the transformation preserves angles
     */
    boolean isConformal();
    
    /**
     * Checks if this transformation is invertible.
     * 
     * @return True if the transformation has an inverse
     */
    boolean isInvertible();
    
    /**
     * Returns the source dimension of this transformation.
     * 
     * @return The number of dimensions in the source coordinate system
     */
    int getSourceDimension();
    
    /**
     * Returns the target dimension of this transformation.
     * 
     * @return The number of dimensions in the target coordinate system
     */
    int getTargetDimension();
    
    /**
     * Returns a string representation of the transformation matrix or parameters.
     * 
     * @return A human-readable description of the transformation
     */
    String getDescription();
    
    /**
     * Exception thrown when a coordinate transformation fails.
     */
    class TransformationException extends Exception {
        public TransformationException(String message) {
            super(message);
        }
        
        public TransformationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}