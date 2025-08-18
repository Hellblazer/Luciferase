package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Linear Transformation Tests")
class LinearTransformationTest {
    
    @Test
    @DisplayName("2D identity transformation")
    void testIdentity2D() throws TransformationException {
        var transformation = LinearTransformation.identity(2);
        var point = new Coordinate(new double[]{3.0, 4.0});
        var result = transformation.transform(point);
        
        assertArrayEquals(new double[]{3.0, 4.0}, result.values(), 1e-10);
        assertTrue(transformation.isLinear());
        assertTrue(transformation.isInvertible());
        assertEquals(1.0, transformation.computeJacobianDeterminant(point), 1e-10);
    }
    
    @Test
    @DisplayName("3D identity transformation")
    void testIdentity3D() throws TransformationException {
        var transformation = LinearTransformation.identity(3);
        var point = new Coordinate(new double[]{1.0, 2.0, 3.0});
        var result = transformation.transform(point);
        
        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, result.values(), 1e-10);
    }
    
    @Test
    @DisplayName("2D scaling transformation")
    void testScaling2D() throws TransformationException {
        var matrix = new double[][]{
            {2.0, 0.0},
            {0.0, 3.0}
        };
        var transformation = new LinearTransformation(matrix);
        var point = new Coordinate(new double[]{1.0, 1.0});
        var result = transformation.transform(point);
        
        assertArrayEquals(new double[]{2.0, 3.0}, result.values(), 1e-10);
        assertEquals(6.0, transformation.computeJacobianDeterminant(point), 1e-10);
    }
    
    @Test
    @DisplayName("2D rotation transformation")
    void testRotation2D() throws TransformationException {
        // 90-degree rotation
        var matrix = new double[][]{
            {0.0, -1.0},
            {1.0, 0.0}
        };
        var transformation = new LinearTransformation(matrix);
        var point = new Coordinate(new double[]{1.0, 0.0});
        var result = transformation.transform(point);
        
        assertArrayEquals(new double[]{0.0, 1.0}, result.values(), 1e-10);
        assertEquals(1.0, transformation.computeJacobianDeterminant(point), 1e-10);
    }
    
    @Test
    @DisplayName("Matrix inverse computation")
    void testMatrixInverse() throws TransformationException {
        var matrix = new double[][]{
            {2.0, 1.0},
            {1.0, 1.0}
        };
        var transformation = new LinearTransformation(matrix);
        var inverse = transformation.inverse();
        
        var point = new Coordinate(new double[]{3.0, 2.0});
        var transformed = transformation.transform(point);
        var restored = inverse.orElseThrow().transform(transformed);
        
        assertArrayEquals(point.values(), restored.values(), 1e-10);
    }
    
    @Test
    @DisplayName("3D matrix operations")
    void test3DMatrix() throws TransformationException {
        var matrix = new double[][]{
            {1.0, 2.0, 0.0},
            {0.0, 1.0, 1.0},
            {1.0, 0.0, 1.0}
        };
        var transformation = new LinearTransformation(matrix);
        var point = new Coordinate(new double[]{1.0, 2.0, 3.0});
        var result = transformation.transform(point);
        
        // Expected: [1*1 + 2*2 + 0*3, 0*1 + 1*2 + 1*3, 1*1 + 0*2 + 1*3] = [5, 5, 4]
        assertArrayEquals(new double[]{5.0, 5.0, 4.0}, result.values(), 1e-10);
    }
    
    @Test
    @DisplayName("Dimension mismatch error")
    void testDimensionMismatch() {
        var matrix = new double[][]{
            {1.0, 0.0},
            {0.0, 1.0}
        };
        var transformation = new LinearTransformation(matrix);
        var point = new Coordinate(new double[]{1.0, 2.0, 3.0}); // 3D point, 2D transformation
        
        assertThrows(TransformationException.class, () -> transformation.transform(point));
    }
    
    @Test
    @DisplayName("Singular matrix detection")
    void testSingularMatrix() {
        var singularMatrix = new double[][]{
            {1.0, 2.0},
            {2.0, 4.0} // Rows are linearly dependent
        };
        var transformation = new LinearTransformation(singularMatrix);
        
        assertFalse(transformation.isInvertible());
        assertTrue(transformation.inverse().isEmpty());
    }
    
    @Test
    @DisplayName("Determinant calculation")
    void testDeterminantCalculation() throws TransformationException {
        var matrix = new double[][]{
            {3.0, 1.0},
            {2.0, 4.0}
        };
        var transformation = new LinearTransformation(matrix);
        var point = new Coordinate(new double[]{0.0, 0.0});
        
        // Determinant should be 3*4 - 1*2 = 10
        assertEquals(10.0, transformation.computeJacobianDeterminant(point), 1e-10);
    }
    
    @Test
    @DisplayName("Batch transformation")
    void testBatchTransformation() throws TransformationException {
        var transformation = LinearTransformation.identity(2);
        var points = java.util.List.of(
            new Coordinate(new double[]{1.0, 2.0}),
            new Coordinate(new double[]{3.0, 4.0}),
            new Coordinate(new double[]{5.0, 6.0})
        );
        
        var results = transformation.transformBatch(points);
        
        assertEquals(3, results.size());
        for (int i = 0; i < points.size(); i++) {
            assertArrayEquals(points.get(i).values(), results.get(i).values(), 1e-10);
        }
    }
    
    @Test
    @DisplayName("Matrix copying and immutability")
    void testMatrixImmutability() throws TransformationException {
        var matrix = new double[][]{
            {1.0, 2.0},
            {3.0, 4.0}
        };
        var transformation = new LinearTransformation(matrix);
        
        // Modify original matrix
        matrix[0][0] = 999.0;
        
        // Transformation should be unaffected
        var retrievedMatrix = transformation.getMatrix();
        assertEquals(1.0, retrievedMatrix[0][0], 1e-10);
        
        // Modify retrieved matrix
        retrievedMatrix[0][0] = 888.0;
        
        // Transformation should still be unaffected
        var retrievedAgain = transformation.getMatrix();
        assertEquals(1.0, retrievedAgain[0][0], 1e-10);
    }
    
    @Test
    @DisplayName("Rectangular matrix dimensions")
    void testRectangularMatrixDimensions() {
        // Non-square matrix (2x3) - should be valid
        var matrix = new double[][]{
            {1.0, 2.0, 3.0},
            {4.0, 5.0, 6.0}
        };
        
        var transformation = new LinearTransformation(matrix);
        assertEquals(3, transformation.getSourceDimension());
        assertEquals(2, transformation.getTargetDimension());
    }
    
    @Test
    @DisplayName("Empty matrix")
    void testEmptyMatrix() {
        var matrix = new double[0][0];
        
        assertThrows(IllegalArgumentException.class, () -> new LinearTransformation(matrix));
    }
    
    @Test
    @DisplayName("Null matrix")
    void testNullMatrix() {
        assertThrows(IllegalArgumentException.class, () -> new LinearTransformation(null));
    }
}