package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Linear Transformation Tests")
class LinearTransformationTest extends InvertibleTransformationTestBase {

    @Override
    protected CoordinateTransformation createSampleTransformation() {
        // 2D scaling transformation
        var matrix = new double[][]{
            {2.0, 0.0},
            {0.0, 3.0}
        };
        return new LinearTransformation(matrix);
    }

    @Override
    protected CoordinateTransformation createDifferentTransformation() {
        // 2D rotation transformation (90 degrees)
        var matrix = new double[][]{
            {0.0, -1.0},
            {1.0, 0.0}
        };
        return new LinearTransformation(matrix);
    }

    @Override
    protected CoordinateTransformation createInvalidTransformation() {
        // LinearTransformation doesn't have invalid construction states
        // Invalid matrices are still valid transformations, just non-invertible
        return null;
    }

    @Override
    protected CoordinateTransformation createNonInvertibleTransformation() {
        // Singular matrix (non-invertible)
        var matrix = new double[][]{
            {1.0, 2.0},
            {2.0, 4.0} // Linearly dependent rows
        };
        return new LinearTransformation(matrix);
    }

    @Override
    protected Coordinate getValidInputCoordinate() {
        return new Coordinate(new double[]{1.0, 1.0});
    }
    
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
    
    // Dimension mismatch testing now handled by base class
    
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

    // --- Bead Luciferase-7wzml.59: no-reflection inverse constructor tests ---

    @Test
    @DisplayName("Inverse-of-inverse round-trip returns original transform result")
    void testInverseOfInverseRoundTrip() throws TransformationException {
        var matrix = new double[][]{
            {2.0, 1.0},
            {1.0, 1.0}
        };
        var t = new LinearTransformation(matrix);
        var inv = t.inverse().orElseThrow();
        var invInv = inv.inverse().orElseThrow();

        // inv.inverse() should be equal to the original
        var point = new Coordinate(new double[]{3.0, 5.0});
        assertArrayEquals(t.transform(point).values(), invInv.transform(point).values(), 1e-10);
    }

    @Test
    @DisplayName("Cached determinant of inverse equals 1/det(original)")
    void testInverseDeterminantIsReciprocal() throws TransformationException {
        var matrix = new double[][]{
            {3.0, 1.0},
            {2.0, 4.0}  // det = 3*4 - 1*2 = 10
        };
        var t = new LinearTransformation(matrix);
        var inv = (LinearTransformation) t.inverse().orElseThrow();

        var anyPoint = new Coordinate(new double[]{0.0, 0.0});
        double detOrig = t.computeJacobianDeterminant(anyPoint);          // 10.0
        double detInv  = inv.computeJacobianDeterminant(anyPoint);        // 0.1

        assertEquals(1.0 / detOrig, detInv, 1e-12);
    }

    @Test
    @DisplayName("No reflection API is used — inverse constructor assigns final fields directly")
    void testNoReflectionInInverseConstruction() {
        // If the old reflection path were used it would throw under strong JPMS encapsulation
        // (--illegal-access=deny). Simply exercising the path without an exception is the gate.
        var matrix = new double[][]{
            {4.0, 7.0},
            {2.0, 6.0}
        };
        var t = new LinearTransformation(matrix);
        // Must not throw InaccessibleObjectException / any reflection error
        var inv = assertDoesNotThrow(() -> t.inverse().orElseThrow());
        assertNotNull(inv);
    }

    // --- Bead Luciferase-7wzml.113: composeWithLinear wrong column count for non-square ---

    @Test
    @DisplayName("compose A(3x2).compose(B=2x3): result is B*A = 2x2")
    void testComposeNonSquare_3x2_with_2x3() throws TransformationException {
        // this = A: 3x2  (targetDim=3, sourceDim=2)
        // other = B: 2x3 (targetDim=2, sourceDim=3)
        // result = B * A: 2x2
        // A:
        // [1 2]
        // [3 4]
        // [5 6]
        var matrixA = new double[][]{
            {1.0, 2.0},
            {3.0, 4.0},
            {5.0, 6.0}
        };
        // B:
        // [7  8  9 ]
        // [10 11 12]
        var matrixB = new double[][]{
            {7.0,  8.0,  9.0},
            {10.0, 11.0, 12.0}
        };
        var A = new LinearTransformation(matrixA);  // 3x2
        var B = new LinearTransformation(matrixB);  // 2x3

        // compose: A.compose(B) computes B*A (other=B applied first, then this=A)
        // B*A: (2x3)*(3x2) = 2x2
        // row0: [7*1+8*3+9*5, 7*2+8*4+9*6] = [7+24+45, 14+32+54] = [76, 100]
        // row1: [10*1+11*3+12*5, 10*2+11*4+12*6] = [10+33+60, 20+44+72] = [103, 136]
        var composed = (LinearTransformation) A.compose(B);

        double[][] m = composed.getMatrix();
        assertEquals(2, m.length,    "result must have 2 rows (B.targetDimension)");
        assertEquals(2, m[0].length, "result must have 2 columns (A.sourceDimension)");
        assertArrayEquals(new double[]{76.0, 100.0},  m[0], 1e-10);
        assertArrayEquals(new double[]{103.0, 136.0}, m[1], 1e-10);
    }

    @Test
    @DisplayName("compose A(2x3).compose(B=3x2): result is B*A = 3x3")
    void testComposeNonSquare_2x3_with_3x2() throws TransformationException {
        // this = A: 2x3  (targetDim=2, sourceDim=3)
        // other = B: 3x2 (targetDim=3, sourceDim=2)
        // result = B * A: 3x3
        // A:
        // [1 2 3]
        // [4 5 6]
        var matrixA = new double[][]{
            {1.0, 2.0, 3.0},
            {4.0, 5.0, 6.0}
        };
        // B:
        // [7  8 ]
        // [9  10]
        // [11 12]
        var matrixB = new double[][]{
            {7.0,  8.0},
            {9.0,  10.0},
            {11.0, 12.0}
        };
        var A = new LinearTransformation(matrixA);  // 2x3
        var B = new LinearTransformation(matrixB);  // 3x2

        // B*A: (3x2)*(2x3) = 3x3
        // row0: [7*1+8*4, 7*2+8*5, 7*3+8*6] = [7+32, 14+40, 21+48] = [39, 54, 69]
        // row1: [9*1+10*4, 9*2+10*5, 9*3+10*6] = [9+40, 18+50, 27+60] = [49, 68, 87]
        // row2: [11*1+12*4, 11*2+12*5, 11*3+12*6] = [11+48, 22+60, 33+72] = [59, 82, 105]
        var composed = (LinearTransformation) A.compose(B);

        double[][] m = composed.getMatrix();
        assertEquals(3, m.length,    "result must have 3 rows (B.targetDimension)");
        assertEquals(3, m[0].length, "result must have 3 columns (A.sourceDimension)");
        assertArrayEquals(new double[]{39.0,  54.0,  69.0},  m[0], 1e-10);
        assertArrayEquals(new double[]{49.0,  68.0,  87.0},  m[1], 1e-10);
        assertArrayEquals(new double[]{59.0,  82.0, 105.0},  m[2], 1e-10);
    }

    @Test
    @DisplayName("compose square matrices (regression guard — existing behavior preserved)")
    void testComposeSquare_2x2() throws TransformationException {
        // A.compose(B) computes result = B*A (other=B applied first)
        // A = [[1,2],[3,4]], B = [[5,6],[7,8]]
        // B*A: row0=[5*1+6*3, 5*2+6*4]=[23,34], row1=[7*1+8*3, 7*2+8*4]=[31,46]
        var matrixA = new double[][]{{1.0, 2.0}, {3.0, 4.0}};
        var matrixB = new double[][]{{5.0, 6.0}, {7.0, 8.0}};
        var A = new LinearTransformation(matrixA);
        var B = new LinearTransformation(matrixB);
        var composed = (LinearTransformation) A.compose(B);
        double[][] m = composed.getMatrix();
        assertEquals(2, m.length);
        assertEquals(2, m[0].length);
        assertArrayEquals(new double[]{23.0, 34.0}, m[0], 1e-10);
        assertArrayEquals(new double[]{31.0, 46.0}, m[1], 1e-10);
    }

    @Test
    @DisplayName("compose guard regression: this.source==other.target but this.target!=other.source must throw")
    void testComposeIncompatibleDimensions_regressionGuard() {
        // this = A: 3x2 (targetDim=3, sourceDim=2)
        // other = B: 4x2 (targetDim=2, sourceDim=4... wait, see below)
        // We need: this.sourceDim(2) == other.targetDim(2)  [passes BROKEN guard]
        //      AND this.targetDim(3) != other.sourceDim(4)  [thrown by FIXED guard]
        // B is 2x4: targetDim=2, sourceDim=4 — a 2-row 4-column matrix
        var matrixA = new double[][]{
            {1.0, 2.0},
            {3.0, 4.0},
            {5.0, 6.0}
        };  // A: 3 rows x 2 cols → targetDim=3, sourceDim=2
        var matrixB = new double[][]{
            {1.0, 2.0, 3.0, 4.0},
            {5.0, 6.0, 7.0, 8.0}
        };  // B: 2 rows x 4 cols → targetDim=2, sourceDim=4
        var A = new LinearTransformation(matrixA);
        var B = new LinearTransformation(matrixB);
        // Broken guard would NOT throw (A.sourceDim=2 == B.targetDim=2), then AIOOBE in k-loop.
        // Fixed guard MUST throw (A.targetDim=3 != B.sourceDim=4).
        assertThrows(IllegalArgumentException.class, () -> A.compose(B),
            "Fixed guard must throw when this.targetDimension != other.sourceDimension");
    }
}