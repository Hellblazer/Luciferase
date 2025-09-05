package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A composite transformation that chains multiple transformations together.
 * Transformations are applied in the order they were added.
 * 
 * For transformations T1, T2, ..., Tn, the composite applies:
 * result = Tn(...T2(T1(input))...)
 */
public final class CompositeTransformation implements CoordinateTransformation {
    
    private final List<CoordinateTransformation> transformations;
    private final int dimension;
    
    /**
     * Creates a composite transformation from a list of transformations.
     * 
     * @param transformations the transformations to chain (applied in order)
     * @throws IllegalArgumentException if transformations is empty or contains null elements
     */
    public CompositeTransformation(List<CoordinateTransformation> transformations) {
        if (transformations == null || transformations.isEmpty()) {
            throw new IllegalArgumentException("Transformations list cannot be null or empty");
        }
        
        if (transformations.contains(null)) {
            throw new IllegalArgumentException("Transformations list cannot contain null elements");
        }
        
        this.transformations = new ArrayList<>(transformations);
        this.dimension = transformations.get(0).getTargetDimension();
        
        // Verify dimension compatibility for chaining
        for (int i = 0; i < transformations.size() - 1; i++) {
            var current = transformations.get(i);
            var next = transformations.get(i + 1);
            if (current.getTargetDimension() != next.getSourceDimension()) {
                throw new IllegalArgumentException(
                    "Transformation dimension mismatch: " + current.getTargetDimension() + 
                    " -> " + next.getSourceDimension()
                );
            }
        }
    }
    
    /**
     * Creates a composite transformation from individual transformations.
     * 
     * @param transformations the transformations to chain (applied in order)
     */
    public CompositeTransformation(CoordinateTransformation... transformations) {
        this(Arrays.asList(transformations));
    }
    
    /**
     * Creates a composite of two transformations.
     * 
     * @param first the first transformation to apply
     * @param second the second transformation to apply
     */
    public static CompositeTransformation of(CoordinateTransformation first, CoordinateTransformation second) {
        return new CompositeTransformation(first, second);
    }
    
    /**
     * Creates a composite of three transformations.
     * 
     * @param first the first transformation to apply
     * @param second the second transformation to apply
     * @param third the third transformation to apply
     */
    public static CompositeTransformation of(CoordinateTransformation first, 
                                           CoordinateTransformation second, 
                                           CoordinateTransformation third) {
        return new CompositeTransformation(first, second, third);
    }
    
    /**
     * Builds a composite transformation incrementally.
     */
    public static class Builder {
        private final List<CoordinateTransformation> transformations = new ArrayList<>();
        
        /**
         * Adds a transformation to the chain.
         * 
         * @param transformation the transformation to add
         * @return this builder for method chaining
         */
        public Builder then(CoordinateTransformation transformation) {
            if (transformation == null) {
                throw new IllegalArgumentException("Transformation cannot be null");
            }
            transformations.add(transformation);
            return this;
        }
        
        /**
         * Builds the composite transformation.
         * 
         * @return the composite transformation
         * @throws IllegalStateException if no transformations were added
         */
        public CompositeTransformation build() {
            if (transformations.isEmpty()) {
                throw new IllegalStateException("At least one transformation must be added");
            }
            return new CompositeTransformation(transformations);
        }
    }
    
    /**
     * Creates a new builder for composite transformations.
     * 
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public Coordinate transform(Coordinate source) throws TransformationException {
        var current = source;
        for (var transformation : transformations) {
            current = transformation.transform(current);
        }
        return current;
    }
    
    @Override
    public Bounds transformBounds(Bounds bounds) throws TransformationException {
        var current = bounds;
        for (var transformation : transformations) {
            current = transformation.transformBounds(current);
        }
        return current;
    }
    
    @Override
    public Coordinate inverseTransform(Coordinate target) throws TransformationException {
        var current = target;
        // Apply inverse transformations in reverse order
        for (int i = transformations.size() - 1; i >= 0; i--) {
            current = transformations.get(i).inverseTransform(current);
        }
        return current;
    }
    
    @Override
    public Optional<CoordinateTransformation> inverse() {
        // For composite transformation T1 ∘ T2 ∘ ... ∘ Tn,
        // the inverse is Tn^-1 ∘ ... ∘ T2^-1 ∘ T1^-1
        var inverseTransformations = new ArrayList<CoordinateTransformation>(transformations.size());
        
        for (int i = transformations.size() - 1; i >= 0; i--) {
            var inverseOpt = transformations.get(i).inverse();
            if (inverseOpt.isEmpty()) {
                return Optional.empty();
            }
            inverseTransformations.add(inverseOpt.get());
        }
        
        return Optional.of(new CompositeTransformation(inverseTransformations));
    }
    
    @Override
    public CoordinateTransformation compose(CoordinateTransformation other) {
        if (other instanceof CompositeTransformation composite) {
            // Flatten nested composites
            var combined = new ArrayList<CoordinateTransformation>(transformations.size() + composite.transformations.size());
            combined.addAll(transformations);
            combined.addAll(composite.transformations);
            return new CompositeTransformation(combined);
        } else {
            var combined = new ArrayList<>(transformations);
            combined.add(other);
            return new CompositeTransformation(combined);
        }
    }
    
    @Override
    public double determinant() {
        // For composite transformation, the determinant is the product
        // of individual determinants
        var determinant = 1.0;
        for (var transformation : transformations) {
            determinant *= transformation.determinant();
        }
        return determinant;
    }
    
    @Override
    public boolean isIsometric() {
        return transformations.stream().allMatch(CoordinateTransformation::isIsometric);
    }
    
    @Override
    public boolean isConformal() {
        return transformations.stream().allMatch(CoordinateTransformation::isConformal);
    }
    
    @Override
    public boolean isInvertible() {
        return transformations.stream().allMatch(CoordinateTransformation::isInvertible);
    }
    
    @Override
    public int getSourceDimension() {
        return transformations.isEmpty() ? 0 : transformations.get(0).getSourceDimension();
    }
    
    @Override
    public int getTargetDimension() {
        return dimension;
    }
    
    /**
     * Gets the dimension of this transformation (same as getTargetDimension for compatibility).
     * 
     * @return the target dimension
     */
    public int getDimension() {
        return getTargetDimension();
    }
    
    /**
     * Computes the Jacobian determinant at a specific coordinate.
     * For composite transformations, this is the product of individual Jacobian determinants.
     * 
     * @param coordinate the coordinate at which to compute the determinant
     * @return the Jacobian determinant
     */
    public double computeJacobianDeterminant(Coordinate coordinate) {
        try {
            var current = coordinate;
            double totalDeterminant = 1.0;
            
            for (var transformation : transformations) {
                // Check if transformation has computeJacobianDeterminant method
                if (transformation instanceof LinearTransformation linear) {
                    totalDeterminant *= linear.computeJacobianDeterminant(current);
                } else if (transformation instanceof RotationTransformation rotation) {
                    totalDeterminant *= rotation.computeJacobianDeterminant(current);
                } else {
                    // Fallback to determinant() method
                    totalDeterminant *= transformation.determinant();
                }
                
                // Transform coordinate for next iteration
                current = transformation.transform(current);
            }
            
            return totalDeterminant;
        } catch (TransformationException e) {
            // If transformation fails, return the determinant() fallback
            return determinant();
        }
    }
    
    /**
     * Transforms a batch of coordinates efficiently.
     * 
     * @param coordinates the list of coordinates to transform
     * @return the list of transformed coordinates
     * @throws TransformationException if any transformation fails
     */
    public java.util.List<Coordinate> transformBatch(java.util.List<Coordinate> coordinates) throws TransformationException {
        var result = new java.util.ArrayList<Coordinate>(coordinates.size());
        
        for (var coordinate : coordinates) {
            result.add(transform(coordinate));
        }
        
        return result;
    }
    
    /**
     * Checks if this composite transformation is linear.
     * A composite is linear only if all its component transformations are linear.
     * 
     * @return true if all component transformations are linear
     */
    public boolean isLinear() {
        return transformations.stream().allMatch(this::isLinearTransformation);
    }
    
    /**
     * Helper method to check if a specific transformation is linear.
     * Since not all transformations implement isLinear() in the interface,
     * we need to check specific types.
     */
    private boolean isLinearTransformation(CoordinateTransformation transformation) {
        // Check known linear transformation types
        if (transformation instanceof LinearTransformation linear) {
            return linear.isLinear();
        }
        if (transformation instanceof RotationTransformation rotation) {
            return rotation.isLinear();
        }
        if (transformation instanceof ScalingTransformation scaling) {
            return true; // Scaling is always linear
        }
        if (transformation instanceof CompositeTransformation composite) {
            return composite.isLinear(); // Recursive check
        }
        // Translation and Affine transformations are not linear (they have translation component)
        if (transformation instanceof TranslationTransformation) {
            return false;
        }
        if (transformation instanceof AffineTransformation) {
            return false;
        }
        // Default assumption for unknown types
        return false;
    }
    
    @Override
    public String getDescription() {
        var sb = new StringBuilder();
        sb.append("CompositeTransformation(").append(transformations.size()).append(" transformations):\n");
        for (int i = 0; i < transformations.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(transformations.get(i).getDescription()).append("\n");
        }
        return sb.toString();
    }
    
    /**
     * Gets the number of transformations in this composite.
     * 
     * @return the number of transformations
     */
    public int getTransformationCount() {
        return transformations.size();
    }
    
    /**
     * Gets an unmodifiable view of the transformations.
     * 
     * @return the transformations
     */
    public List<CoordinateTransformation> getTransformations() {
        return Collections.unmodifiableList(transformations);
    }
    
    /**
     * Gets the transformation at the specified index.
     * 
     * @param index the index
     * @return the transformation
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public CoordinateTransformation getTransformation(int index) {
        return transformations.get(index);
    }
    
    @Override
    public String toString() {
        return "CompositeTransformation{" +
               "transformations=" + transformations.size() +
               ", dimension=" + dimension +
               '}';
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        var that = (CompositeTransformation) obj;
        return dimension == that.dimension && 
               transformations.equals(that.transformations);
    }
    
    @Override
    public int hashCode() {
        return transformations.hashCode() * 31 + dimension;
    }
}