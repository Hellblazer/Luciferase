package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Linear coordinate transformation using matrix multiplication.
 * 
 * Represents transformations of the form: y = Mx
 * where M is the transformation matrix and x is the input coordinate vector.
 * 
 * Linear transformations include scaling, rotation, reflection, and shearing,
 * but do not include translation (use AffineTransformation for that).
 */
public class LinearTransformation implements CoordinateTransformation {
    
    private final double[][] matrix;
    private final int sourceDimension;
    private final int targetDimension;
    private final Double cachedDeterminant;
    private final LinearTransformation cachedInverse;
    
    /**
     * Creates a linear transformation with the given matrix.
     * 
     * @param matrix The transformation matrix (targetDim x sourceDim)
     * @throws IllegalArgumentException if the matrix is null or invalid
     */
    public LinearTransformation(double[][] matrix) {
        this(matrix, true);
    }
    
    /**
     * Private constructor that allows control over pre-computation.
     * Used internally to prevent infinite recursion during inverse computation.
     */
    private LinearTransformation(double[][] matrix, boolean precompute) {
        if (matrix == null) {
            throw new IllegalArgumentException("Transformation matrix cannot be null");
        }
        
        if (matrix.length == 0) {
            throw new IllegalArgumentException("Matrix must have at least one row");
        }
        
        this.targetDimension = matrix.length;
        this.sourceDimension = matrix[0].length;
        
        if (sourceDimension == 0) {
            throw new IllegalArgumentException("Matrix must have at least one column");
        }
        
        // Validate matrix dimensions and create defensive copy
        this.matrix = new double[targetDimension][sourceDimension];
        for (int i = 0; i < targetDimension; i++) {
            if (matrix[i].length != sourceDimension) {
                throw new IllegalArgumentException("Matrix rows must have consistent dimensions");
            }
            System.arraycopy(matrix[i], 0, this.matrix[i], 0, sourceDimension);
        }
        
        if (precompute) {
            // Pre-compute determinant for square matrices
            this.cachedDeterminant = (sourceDimension == targetDimension) ? computeDeterminant() : null;
            
            // Pre-compute inverse for invertible square matrices
            this.cachedInverse = (isInvertible()) ? computeInverse() : null;
        } else {
            this.cachedDeterminant = null;
            this.cachedInverse = null;
        }
    }
    
    @Override
    public Coordinate transform(Coordinate source) throws TransformationException {
        if (source.values().length != sourceDimension) {
            throw new TransformationException(
                "Source coordinate dimension (" + source.values().length + 
                ") does not match transformation source dimension (" + sourceDimension + ")"
            );
        }
        
        var sourceValues = source.values();
        var result = new double[targetDimension];
        
        for (int i = 0; i < targetDimension; i++) {
            result[i] = 0.0;
            for (int j = 0; j < sourceDimension; j++) {
                result[i] += matrix[i][j] * sourceValues[j];
            }
        }
        
        return new Coordinate(result);
    }
    
    @Override
    public Bounds transformBounds(Bounds bounds) throws TransformationException {
        if (bounds.dimensions() != sourceDimension) {
            throw new TransformationException(
                "Bounds dimension (" + bounds.dimensions() + 
                ") does not match transformation source dimension (" + sourceDimension + ")"
            );
        }
        
        // For linear transformations, we need to transform all corner points
        // and find the axis-aligned bounding box of the result
        int numCorners = 1 << sourceDimension; // 2^n corners for n-dimensional box
        
        double[] minResult = new double[targetDimension];
        double[] maxResult = new double[targetDimension];
        Arrays.fill(minResult, Double.POSITIVE_INFINITY);
        Arrays.fill(maxResult, Double.NEGATIVE_INFINITY);
        
        // Transform each corner of the source bounds
        for (int corner = 0; corner < numCorners; corner++) {
            var cornerCoord = createCornerCoordinate(bounds, corner);
            var transformedCorner = transform(cornerCoord);
            var transformedValues = transformedCorner.values();
            
            for (int d = 0; d < targetDimension; d++) {
                minResult[d] = Math.min(minResult[d], transformedValues[d]);
                maxResult[d] = Math.max(maxResult[d], transformedValues[d]);
            }
        }
        
        return new Bounds(minResult, maxResult);
    }
    
    @Override
    public Coordinate inverseTransform(Coordinate target) throws TransformationException {
        if (!isInvertible()) {
            throw new TransformationException("Transformation is not invertible");
        }
        
        return cachedInverse.transform(target);
    }
    
    @Override
    public Optional<CoordinateTransformation> inverse() {
        return isInvertible() ? Optional.of(cachedInverse) : Optional.empty();
    }
    
    @Override
    public CoordinateTransformation compose(CoordinateTransformation other) {
        if (other instanceof LinearTransformation linear) {
            return composeWithLinear(linear);
        } else {
            return new CompositeTransformation(this, other);
        }
    }
    
    @Override
    public double determinant() {
        if (cachedDeterminant != null) {
            return cachedDeterminant;
        }
        throw new UnsupportedOperationException("Determinant is only defined for square matrices");
    }
    
    @Override
    public boolean isIsometric() {
        if (sourceDimension != targetDimension) {
            return false;
        }
        
        // Check if the matrix is orthogonal (M^T * M = I)
        return isOrthogonal() && Math.abs(Math.abs(determinant()) - 1.0) < 1e-10;
    }
    
    @Override
    public boolean isConformal() {
        if (sourceDimension != targetDimension) {
            return false;
        }
        
        // For linear transformations, conformal means isometric or uniform scaling
        return isIsometric() || isUniformScaling();
    }
    
    @Override
    public boolean isInvertible() {
        return sourceDimension == targetDimension && 
               cachedDeterminant != null && 
               Math.abs(cachedDeterminant) > 1e-12;
    }
    
    @Override
    public int getSourceDimension() {
        return sourceDimension;
    }
    
    @Override
    public int getTargetDimension() {
        return targetDimension;
    }
    
    @Override
    public String getDescription() {
        var sb = new StringBuilder();
        sb.append("LinearTransformation(").append(targetDimension).append("x").append(sourceDimension).append("):\n");
        
        for (int i = 0; i < targetDimension; i++) {
            sb.append("[");
            for (int j = 0; j < sourceDimension; j++) {
                sb.append(String.format("%8.4f", matrix[i][j]));
                if (j < sourceDimension - 1) sb.append(" ");
            }
            sb.append("]\n");
        }
        
        if (cachedDeterminant != null) {
            sb.append("Determinant: ").append(String.format("%.6f", cachedDeterminant));
        }
        
        return sb.toString();
    }
    
    /**
     * Returns a copy of the transformation matrix.
     */
    public double[][] getMatrix() {
        var copy = new double[targetDimension][sourceDimension];
        for (int i = 0; i < targetDimension; i++) {
            System.arraycopy(matrix[i], 0, copy[i], 0, sourceDimension);
        }
        return copy;
    }
    
    /**
     * Returns true since this is a linear transformation.
     */
    public boolean isLinear() {
        return true;
    }
    
    /**
     * Computes the Jacobian determinant at the given coordinate.
     * For linear transformations, this is constant and equals the determinant of the matrix.
     */
    public double computeJacobianDeterminant(Coordinate coordinate) {
        if (coordinate.dimensions() != sourceDimension) {
            throw new IllegalArgumentException(
                "Coordinate dimension (" + coordinate.dimensions() + 
                ") does not match transformation source dimension (" + sourceDimension + ")"
            );
        }
        
        if (cachedDeterminant == null) {
            throw new UnsupportedOperationException("Jacobian determinant is only defined for square matrices");
        }
        
        return cachedDeterminant;
    }
    
    /**
     * Transforms a batch of coordinates efficiently.
     */
    public java.util.List<Coordinate> transformBatch(java.util.List<Coordinate> coordinates) throws TransformationException {
        var result = new java.util.ArrayList<Coordinate>(coordinates.size());
        for (var coord : coordinates) {
            result.add(transform(coord));
        }
        return result;
    }
    
    // Static factory methods for common transformations
    
    /**
     * Creates an identity transformation for the given dimension.
     */
    public static LinearTransformation identity(int dimension) {
        var matrix = new double[dimension][dimension];
        for (int i = 0; i < dimension; i++) {
            matrix[i][i] = 1.0;
        }
        return new LinearTransformation(matrix);
    }
    
    /**
     * Creates a uniform scaling transformation.
     */
    public static LinearTransformation scale(int dimension, double factor) {
        var matrix = new double[dimension][dimension];
        for (int i = 0; i < dimension; i++) {
            matrix[i][i] = factor;
        }
        return new LinearTransformation(matrix);
    }
    
    /**
     * Creates a non-uniform scaling transformation.
     */
    public static LinearTransformation scale(double... factors) {
        int dimension = factors.length;
        var matrix = new double[dimension][dimension];
        for (int i = 0; i < dimension; i++) {
            matrix[i][i] = factors[i];
        }
        return new LinearTransformation(matrix);
    }
    
    /**
     * Creates a 2D rotation transformation.
     */
    public static LinearTransformation rotation2D(double angleRadians) {
        double cos = Math.cos(angleRadians);
        double sin = Math.sin(angleRadians);
        
        double[][] matrix = {
            {cos, -sin},
            {sin,  cos}
        };
        
        return new LinearTransformation(matrix);
    }
    
    /**
     * Creates a reflection transformation across a hyperplane.
     */
    public static LinearTransformation reflection(double[] normal) {
        int dimension = normal.length;
        
        // Normalize the normal vector
        double normLength = 0.0;
        for (double component : normal) {
            normLength += component * component;
        }
        normLength = Math.sqrt(normLength);
        
        if (normLength < 1e-12) {
            throw new IllegalArgumentException("Normal vector cannot be zero");
        }
        
        var normalizedNormal = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            normalizedNormal[i] = normal[i] / normLength;
        }
        
        // Create reflection matrix: I - 2 * n * n^T
        var matrix = new double[dimension][dimension];
        for (int i = 0; i < dimension; i++) {
            for (int j = 0; j < dimension; j++) {
                matrix[i][j] = (i == j ? 1.0 : 0.0) - 2.0 * normalizedNormal[i] * normalizedNormal[j];
            }
        }
        
        return new LinearTransformation(matrix);
    }
    
    // Private helper methods
    
    private double computeDeterminant() {
        if (sourceDimension != targetDimension) {
            throw new UnsupportedOperationException("Determinant is only defined for square matrices");
        }
        
        return calculateDeterminant(matrix);
    }
    
    private LinearTransformation computeInverse() {
        if (sourceDimension != targetDimension) {
            return null;
        }
        
        double det = determinant();
        if (Math.abs(det) < 1e-12) {
            return null;
        }
        
        var inverse = invertMatrix(matrix);
        if (inverse != null) {
            // Create the inverse transformation using a special constructor that sets up
            // a bidirectional relationship to avoid infinite recursion
            return createInverseTransformation(inverse);
        }
        return null;
    }
    
    private LinearTransformation createInverseTransformation(double[][] inverseMatrix) {
        // Create the inverse transformation with its own properly computed determinant and inverse
        var inverseTransformation = new LinearTransformation(inverseMatrix, false);
        
        // Manually set the cached values to avoid infinite recursion
        // The determinant of the inverse is 1/determinant of the original
        var inverseDeterminant = 1.0 / determinant();
        
        // Use reflection to set the final fields
        try {
            var detField = LinearTransformation.class.getDeclaredField("cachedDeterminant");
            detField.setAccessible(true);
            detField.set(inverseTransformation, inverseDeterminant);
            
            var invField = LinearTransformation.class.getDeclaredField("cachedInverse");
            invField.setAccessible(true);
            invField.set(inverseTransformation, this); // The inverse of the inverse is the original
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize inverse transformation", e);
        }
        
        return inverseTransformation;
    }

    private LinearTransformation composeWithLinear(LinearTransformation other) {
        if (sourceDimension != other.targetDimension) {
            throw new IllegalArgumentException(
                "Cannot compose transformations: source dimension mismatch"
            );
        }
        
        // Multiply matrices: result = other.matrix * this.matrix
        var resultMatrix = new double[other.targetDimension][targetDimension];
        
        for (int i = 0; i < other.targetDimension; i++) {
            for (int j = 0; j < targetDimension; j++) {
                resultMatrix[i][j] = 0.0;
                for (int k = 0; k < sourceDimension; k++) {
                    resultMatrix[i][j] += other.matrix[i][k] * matrix[k][j];
                }
            }
        }
        
        return new LinearTransformation(resultMatrix);
    }
    
    private Coordinate createCornerCoordinate(Bounds bounds, int cornerIndex) {
        var coordinates = new double[sourceDimension];
        
        for (int d = 0; d < sourceDimension; d++) {
            boolean useMax = (cornerIndex & (1 << d)) != 0;
            coordinates[d] = useMax ? bounds.max()[d] : bounds.min()[d];
        }
        
        return new Coordinate(coordinates);
    }
    
    private boolean isOrthogonal() {
        if (sourceDimension != targetDimension) {
            return false;
        }
        
        // Check if M^T * M = I
        for (int i = 0; i < sourceDimension; i++) {
            for (int j = 0; j < sourceDimension; j++) {
                double dotProduct = 0.0;
                for (int k = 0; k < targetDimension; k++) {
                    dotProduct += matrix[k][i] * matrix[k][j];
                }
                
                double expected = (i == j) ? 1.0 : 0.0;
                if (Math.abs(dotProduct - expected) > 1e-10) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    private boolean isUniformScaling() {
        if (sourceDimension != targetDimension) {
            return false;
        }
        
        // Check if matrix is a scalar multiple of identity
        double scale = matrix[0][0];
        
        for (int i = 0; i < sourceDimension; i++) {
            for (int j = 0; j < sourceDimension; j++) {
                double expected = (i == j) ? scale : 0.0;
                if (Math.abs(matrix[i][j] - expected) > 1e-10) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    // Matrix operations
    
    private static double calculateDeterminant(double[][] matrix) {
        int n = matrix.length;
        
        if (n == 1) {
            return matrix[0][0];
        }
        
        if (n == 2) {
            return matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0];
        }
        
        // Use LU decomposition for larger matrices
        return calculateDeterminantLU(matrix);
    }
    
    private static double calculateDeterminantLU(double[][] matrix) {
        int n = matrix.length;
        
        // Create a copy for LU decomposition
        var lu = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, lu[i], 0, n);
        }
        
        int swaps = 0;
        
        // Forward elimination
        for (int i = 0; i < n; i++) {
            // Find pivot
            int maxRow = i;
            for (int k = i + 1; k < n; k++) {
                if (Math.abs(lu[k][i]) > Math.abs(lu[maxRow][i])) {
                    maxRow = k;
                }
            }
            
            // Swap rows if needed
            if (maxRow != i) {
                var temp = lu[i];
                lu[i] = lu[maxRow];
                lu[maxRow] = temp;
                swaps++;
            }
            
            // Check for singular matrix
            if (Math.abs(lu[i][i]) < 1e-12) {
                return 0.0;
            }
            
            // Eliminate column
            for (int k = i + 1; k < n; k++) {
                double factor = lu[k][i] / lu[i][i];
                for (int j = i; j < n; j++) {
                    lu[k][j] -= factor * lu[i][j];
                }
            }
        }
        
        // Calculate determinant as product of diagonal elements
        double det = (swaps % 2 == 0) ? 1.0 : -1.0;
        for (int i = 0; i < n; i++) {
            det *= lu[i][i];
        }
        
        return det;
    }
    
    private static double[][] invertMatrix(double[][] matrix) {
        int n = matrix.length;
        
        // Create augmented matrix [A|I]
        var augmented = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, augmented[i], 0, n);
            augmented[i][n + i] = 1.0;
        }
        
        // Gauss-Jordan elimination
        for (int i = 0; i < n; i++) {
            // Find pivot
            int maxRow = i;
            for (int k = i + 1; k < n; k++) {
                if (Math.abs(augmented[k][i]) > Math.abs(augmented[maxRow][i])) {
                    maxRow = k;
                }
            }
            
            // Swap rows if needed
            if (maxRow != i) {
                var temp = augmented[i];
                augmented[i] = augmented[maxRow];
                augmented[maxRow] = temp;
            }
            
            // Check for singular matrix
            if (Math.abs(augmented[i][i]) < 1e-12) {
                return null;
            }
            
            // Scale pivot row
            double pivot = augmented[i][i];
            for (int j = 0; j < 2 * n; j++) {
                augmented[i][j] /= pivot;
            }
            
            // Eliminate column
            for (int k = 0; k < n; k++) {
                if (k != i) {
                    double factor = augmented[k][i];
                    for (int j = 0; j < 2 * n; j++) {
                        augmented[k][j] -= factor * augmented[i][j];
                    }
                }
            }
        }
        
        // Extract inverse matrix
        var inverse = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(augmented[i], n, inverse[i], 0, n);
        }
        
        return inverse;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LinearTransformation other)) return false;
        
        if (sourceDimension != other.sourceDimension || targetDimension != other.targetDimension) {
            return false;
        }
        
        for (int i = 0; i < targetDimension; i++) {
            for (int j = 0; j < sourceDimension; j++) {
                if (Math.abs(matrix[i][j] - other.matrix[i][j]) > 1e-12) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(Arrays.deepHashCode(matrix), sourceDimension, targetDimension);
    }
}