package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Rotation Transformation Tests")
class RotationTransformationTest {
    
    private static final double EPSILON = 1e-10;
    
    @Test
    @DisplayName("2D rotation - 90 degrees")
    void test2DRotation90Degrees() throws TransformationException {
        var rotation = RotationTransformation.rotation2D(Math.PI / 2);
        var point = new Coordinate(new double[]{1.0, 0.0});
        var result = rotation.transform(point);
        
        assertArrayEquals(new double[]{0.0, 1.0}, result.values(), EPSILON);
        assertTrue(rotation.isLinear());
        assertTrue(rotation.isInvertible());
        assertEquals(1.0, rotation.computeJacobianDeterminant(point), EPSILON);
    }
    
    @Test
    @DisplayName("2D rotation - 180 degrees")
    void test2DRotation180Degrees() throws TransformationException {
        var rotation = RotationTransformation.rotation2D(Math.PI);
        var point = new Coordinate(new double[]{1.0, 0.0});
        var result = rotation.transform(point);
        
        assertArrayEquals(new double[]{-1.0, 0.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("2D rotation - 270 degrees")
    void test2DRotation270Degrees() throws TransformationException {
        var rotation = RotationTransformation.rotation2D(3 * Math.PI / 2);
        var point = new Coordinate(new double[]{1.0, 0.0});
        var result = rotation.transform(point);
        
        assertArrayEquals(new double[]{0.0, -1.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("2D rotation - 360 degrees (identity)")
    void test2DRotation360Degrees() throws TransformationException {
        var rotation = RotationTransformation.rotation2D(2 * Math.PI);
        var point = new Coordinate(new double[]{3.0, 4.0});
        var result = rotation.transform(point);
        
        assertArrayEquals(new double[]{3.0, 4.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("3D rotation around X-axis")
    void test3DRotationX() throws TransformationException {
        var rotation = RotationTransformation.rotationX(Math.PI / 2);
        var point = new Coordinate(new double[]{1.0, 1.0, 0.0});
        var result = rotation.transform(point);
        
        assertArrayEquals(new double[]{1.0, 0.0, 1.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("3D rotation around Y-axis")
    void test3DRotationY() throws TransformationException {
        var rotation = RotationTransformation.rotationY(Math.PI / 2);
        var point = new Coordinate(new double[]{1.0, 1.0, 0.0});
        var result = rotation.transform(point);
        
        assertArrayEquals(new double[]{0.0, 1.0, -1.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("3D rotation around Z-axis")
    void test3DRotationZ() throws TransformationException {
        var rotation = RotationTransformation.rotationZ(Math.PI / 2);
        var point = new Coordinate(new double[]{1.0, 0.0, 1.0});
        var result = rotation.transform(point);
        
        assertArrayEquals(new double[]{0.0, 1.0, 1.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("3D rotation around arbitrary axis")
    void test3DRotationArbitraryAxis() throws TransformationException {
        var axis = new double[]{1.0, 1.0, 1.0}; // Will be normalized
        var rotation = RotationTransformation.rotation3D(axis, Math.PI / 3);
        var point = new Coordinate(new double[]{1.0, 0.0, 0.0});
        var result = rotation.transform(point);
        
        // Verify that rotation preserves magnitude
        var originalMagnitude = Math.sqrt(1.0);
        var resultMagnitude = Math.sqrt(
            result.values()[0] * result.values()[0] +
            result.values()[1] * result.values()[1] +
            result.values()[2] * result.values()[2]
        );
        assertEquals(originalMagnitude, resultMagnitude, EPSILON);
    }
    
    @Test
    @DisplayName("Euler angles rotation")
    void testEulerAngles() throws TransformationException {
        var rotation = RotationTransformation.eulerAngles(Math.PI / 4, Math.PI / 6, Math.PI / 3);
        var point = new Coordinate(new double[]{1.0, 0.0, 0.0});
        var result = rotation.transform(point);
        
        // Verify rotation preserves magnitude
        var originalMagnitude = 1.0;
        var resultMagnitude = Math.sqrt(
            result.values()[0] * result.values()[0] +
            result.values()[1] * result.values()[1] +
            result.values()[2] * result.values()[2]
        );
        assertEquals(originalMagnitude, resultMagnitude, EPSILON);
    }
    
    @Test
    @DisplayName("Vector alignment - 2D")
    void testVectorAlignment2D() throws TransformationException {
        var from = new double[]{1.0, 0.0};
        var to = new double[]{0.0, 1.0};
        var rotation = RotationTransformation.alignVectors(from, to);
        
        var point = new Coordinate(from);
        var result = rotation.transform(point);
        
        // Result should be aligned with 'to' vector (after normalization)
        assertArrayEquals(new double[]{0.0, 1.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("Vector alignment - 3D")
    void testVectorAlignment3D() throws TransformationException {
        var from = new double[]{1.0, 0.0, 0.0};
        var to = new double[]{0.0, 0.0, 1.0};
        var rotation = RotationTransformation.alignVectors(from, to);
        
        var point = new Coordinate(from);
        var result = rotation.transform(point);
        
        // Result should be aligned with 'to' vector (after normalization)
        assertArrayEquals(new double[]{0.0, 0.0, 1.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("Vector alignment - already aligned")
    void testVectorAlignmentAlreadyAligned() throws TransformationException {
        var from = new double[]{1.0, 0.0, 0.0};
        var to = new double[]{2.0, 0.0, 0.0}; // Same direction, different magnitude
        var rotation = RotationTransformation.alignVectors(from, to);
        
        var point = new Coordinate(from);
        var result = rotation.transform(point);
        
        // Should remain the same direction
        assertArrayEquals(new double[]{1.0, 0.0, 0.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("Vector alignment - opposite vectors")
    void testVectorAlignmentOpposite() throws TransformationException {
        var from = new double[]{1.0, 0.0, 0.0};
        var to = new double[]{-1.0, 0.0, 0.0};
        var rotation = RotationTransformation.alignVectors(from, to);
        
        var point = new Coordinate(from);
        var result = rotation.transform(point);
        
        // Should be rotated 180 degrees
        assertArrayEquals(new double[]{-1.0, 0.0, 0.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("Rotation inverse")
    void testRotationInverse() throws TransformationException {
        var rotation = RotationTransformation.rotation2D(Math.PI / 4);
        var inverse = rotation.inverse();
        
        assertTrue(inverse.isPresent());
        
        var point = new Coordinate(new double[]{1.0, 1.0});
        var rotated = rotation.transform(point);
        var restored = inverse.get().transform(rotated);
        
        assertArrayEquals(point.values(), restored.values(), EPSILON);
    }
    
    @Test
    @DisplayName("Rotation determinant is always 1")
    void testRotationDeterminant() throws TransformationException {
        var rotation2D = RotationTransformation.rotation2D(Math.PI / 3);
        var rotation3D = RotationTransformation.rotationX(Math.PI / 4);
        
        var point2D = new Coordinate(new double[]{1.0, 1.0});
        var point3D = new Coordinate(new double[]{1.0, 1.0, 1.0});
        
        assertEquals(1.0, rotation2D.computeJacobianDeterminant(point2D), EPSILON);
        assertEquals(1.0, rotation3D.computeJacobianDeterminant(point3D), EPSILON);
    }
    
    @Test
    @DisplayName("Invalid axis vector")
    void testInvalidAxis() {
        var zeroAxis = new double[]{0.0, 0.0, 0.0};
        
        assertThrows(IllegalArgumentException.class, 
                    () -> RotationTransformation.rotation3D(zeroAxis, Math.PI / 2));
    }
    
    @Test
    @DisplayName("Invalid dimension for vector alignment")
    void testInvalidDimensionVectorAlignment() {
        var from2D = new double[]{1.0, 0.0};
        var to3D = new double[]{1.0, 0.0, 0.0};
        
        assertThrows(IllegalArgumentException.class, 
                    () -> RotationTransformation.alignVectors(from2D, to3D));
    }
    
    @Test
    @DisplayName("Unsupported dimension for vector alignment")
    void testUnsupportedDimensionVectorAlignment() {
        var from4D = new double[]{1.0, 0.0, 0.0, 0.0};
        var to4D = new double[]{0.0, 1.0, 0.0, 0.0};
        
        assertThrows(IllegalArgumentException.class, 
                    () -> RotationTransformation.alignVectors(from4D, to4D));
    }
    
    @Test
    @DisplayName("Rotation composition")
    void testRotationComposition() throws TransformationException {
        var rotation1 = RotationTransformation.rotation2D(Math.PI / 4);
        var rotation2 = RotationTransformation.rotation2D(Math.PI / 4);
        var composed = rotation1.compose(rotation2);
        
        var point = new Coordinate(new double[]{1.0, 0.0});
        var result1 = rotation2.transform(rotation1.transform(point));
        var result2 = composed.transform(point);
        
        assertArrayEquals(result1.values(), result2.values(), EPSILON);
    }
}