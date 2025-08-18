package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Affine coordinate transformation combining linear transformation and translation.
 * 
 * Represents transformations of the form: y = Mx + b
 * where M is the linear transformation matrix, x is the input coordinate vector,
 * and b is the translation vector.
 * 
 * Affine transformations preserve parallel lines and ratios of distances along
 * parallel lines, but may change angles and shapes.
 */
public final class AffineTransformation implements CoordinateTransformation {
    
    private final LinearTransformation linearPart;
    private final double[] translation;
    private final int dimension;
    
    /**
     * Creates an affine transformation with the given linear part and translation.
     * 
     * @param linearPart the linear transformation component
     * @param translation the translation vector
     * @throws IllegalArgumentException if dimensions don't match
     */
    public AffineTransformation(LinearTransformation linearPart, double[] translation) {
        if (linearPart.getTargetDimension() != translation.length) {
            throw new IllegalArgumentException("Linear part target dimension and translation dimensions must match");
        }
        
        this.linearPart = linearPart;
        this.translation = translation.clone();
        this.dimension = translation.length;
    }
    
    /**
     * Creates an affine transformation from a matrix and translation vector.
     * 
     * @param matrix the transformation matrix
     * @param translation the translation vector
     */
    public AffineTransformation(double[][] matrix, double[] translation) {
        this(new LinearTransformation(matrix), translation);
    }
    
    /**
     * Creates a pure translation transformation.
     * 
     * @param translation the translation vector
     * @return affine transformation with identity linear part
     */
    public static AffineTransformation translation(double... translation) {
        return new AffineTransformation(
            LinearTransformation.identity(translation.length),
            translation
        );
    }
    
    /**
     * Creates a 2D rotation with translation.
     * 
     * @param angle rotation angle in radians
     * @param translation translation vector (must be 2D)
     * @return affine transformation
     */
    public static AffineTransformation rotation2D(double angle, double[] translation) {
        if (translation.length != 2) {
            throw new IllegalArgumentException("Translation must be 2D for 2D rotation");
        }
        
        var rotation = LinearTransformation.rotation2D(angle);
        return new AffineTransformation(rotation, translation);
    }
    
    @Override
    public Coordinate transform(Coordinate source) throws TransformationException {
        // Apply linear transformation first, then add translation
        var linearResult = linearPart.transform(source);
        var linearValues = linearResult.values();
        var result = new double[dimension];
        
        for (int i = 0; i < dimension; i++) {
            result[i] = linearValues[i] + translation[i];
        }
        
        return new Coordinate(result);
    }
    
    @Override
    public Bounds transformBounds(Bounds bounds) throws TransformationException {
        // Transform bounds using linear part, then translate
        var linearBounds = linearPart.transformBounds(bounds);
        
        var min = linearBounds.min();
        var max = linearBounds.max();
        var translatedMin = new double[dimension];
        var translatedMax = new double[dimension];
        
        for (int i = 0; i < dimension; i++) {
            translatedMin[i] = min[i] + translation[i];
            translatedMax[i] = max[i] + translation[i];
        }
        
        return new Bounds(translatedMin, translatedMax);
    }
    
    @Override
    public Coordinate inverseTransform(Coordinate target) throws TransformationException {
        if (!isInvertible()) {
            throw new TransformationException("Transformation is not invertible");
        }
        
        // For affine inverse: y = Mx + b  =>  x = M^-1(y - b)
        var translatedTarget = new double[dimension];
        var targetValues = target.values();
        
        for (int i = 0; i < dimension; i++) {
            translatedTarget[i] = targetValues[i] - translation[i];
        }
        
        return linearPart.inverseTransform(new Coordinate(translatedTarget));
    }
    
    @Override
    public Optional<CoordinateTransformation> inverse() {
        var linearInverseOpt = linearPart.inverse();
        if (linearInverseOpt.isEmpty()) {
            return Optional.empty();
        }
        
        var linearInverse = (LinearTransformation) linearInverseOpt.get();
        
        try {
            // For affine inverse: y = Mx + b  =>  x = M^-1(y - b) = M^-1*y - M^-1*b
            var negativeTranslation = new Coordinate(
                Arrays.stream(translation).map(x -> -x).toArray()
            );
            var inverseTranslation = linearInverse.transform(negativeTranslation);
            
            return Optional.of(new AffineTransformation(linearInverse, inverseTranslation.values()));
        } catch (TransformationException e) {
            return Optional.empty();
        }
    }
    
    @Override
    public CoordinateTransformation compose(CoordinateTransformation other) {
        if (other instanceof AffineTransformation affine) {
            // Compose: (other ∘ this)(x) = other(this(x)) = other(Mx + b) = M'(Mx + b) + b' = M'Mx + M'b + b'
            var composedLinear = (LinearTransformation) affine.linearPart.compose(linearPart);
            
            try {
                var transformedTranslation = affine.linearPart.transform(new Coordinate(translation));
                var composedTranslation = new double[dimension];
                
                for (int i = 0; i < dimension; i++) {
                    composedTranslation[i] = transformedTranslation.values()[i] + affine.translation[i];
                }
                
                return new AffineTransformation(composedLinear, composedTranslation);
            } catch (TransformationException e) {
                throw new RuntimeException("Unexpected error in affine composition", e);
            }
        } else {
            return new CompositeTransformation(this, other);
        }
    }
    
    @Override
    public double determinant() {
        return linearPart.determinant();
    }
    
    @Override
    public boolean isIsometric() {
        return linearPart.isIsometric();
    }
    
    @Override
    public boolean isConformal() {
        return linearPart.isConformal();
    }
    
    @Override
    public boolean isInvertible() {
        return linearPart.isInvertible();
    }
    
    @Override
    public int getSourceDimension() {
        return linearPart.getSourceDimension();
    }
    
    @Override
    public int getTargetDimension() {
        return dimension;
    }
    
    @Override
    public String getDescription() {
        var sb = new StringBuilder();
        sb.append("AffineTransformation(").append(dimension).append("D):\\n");
        sb.append("Linear part:\\n").append(linearPart.getDescription());
        sb.append("Translation: [");
        for (int i = 0; i < translation.length; i++) {
            sb.append(String.format("%.4f", translation[i]));
            if (i < translation.length - 1) sb.append(", ");
        }
        sb.append("]\\n");
        return sb.toString();
    }
    
    /**
     * Gets the linear transformation component.
     * 
     * @return the linear part
     */
    public LinearTransformation getLinearPart() {
        return linearPart;
    }
    
    /**
     * Gets a copy of the translation vector.
     * 
     * @return translation vector
     */
    public double[] getTranslation() {
        return translation.clone();
    }
    
    /**
     * Checks if this is a pure translation (identity linear part).
     * 
     * @return true if only translation
     */
    public boolean isPureTranslation() {
        return linearPart.equals(LinearTransformation.identity(dimension));
    }
    
    /**
     * Checks if this is a pure linear transformation (zero translation).
     * 
     * @return true if only linear transformation
     */
    public boolean isPureLinear() {
        for (double component : translation) {
            if (Math.abs(component) > 1e-10) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public String toString() {
        return "AffineTransformation{" +
               "dimension=" + dimension +
               ", linear=" + linearPart +
               ", translation=" + Arrays.toString(translation) +
               '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AffineTransformation other)) return false;
        
        return dimension == other.dimension &&
               linearPart.equals(other.linearPart) &&
               Arrays.equals(translation, other.translation);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(linearPart, Arrays.hashCode(translation), dimension);
    }
}