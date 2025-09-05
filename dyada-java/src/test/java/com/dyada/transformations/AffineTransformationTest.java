package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class AffineTransformationTest {
    
    private LinearTransformation identity2D;
    private LinearTransformation scaling2D;
    private LinearTransformation rotation2D;
    private double[] translation2D;
    private double[] translation3D;
    
    @BeforeEach
    void setUp() {
        identity2D = LinearTransformation.identity(2);
        scaling2D = LinearTransformation.scale(2, 2.0);
        rotation2D = LinearTransformation.rotation2D(Math.PI / 4); // 45 degrees
        translation2D = new double[]{1.0, 2.0};
        translation3D = new double[]{1.0, 2.0, 3.0};
    }
    
    @Test
    void testConstructorWithLinearAndTranslation() {
        var affine = new AffineTransformation(identity2D, translation2D);
        
        assertEquals(2, affine.getSourceDimension());
        assertEquals(2, affine.getTargetDimension());
        assertEquals(identity2D, affine.getLinearPart());
        assertArrayEquals(translation2D, affine.getTranslation());
    }
    
    @Test
    void testConstructorWithMatrixAndTranslation() {
        double[][] matrix = {{2.0, 0.0}, {0.0, 2.0}};
        var affine = new AffineTransformation(matrix, translation2D);
        
        assertEquals(2, affine.getSourceDimension());
        assertEquals(2, affine.getTargetDimension());
        assertEquals(scaling2D, affine.getLinearPart());
        assertArrayEquals(translation2D, affine.getTranslation());
    }
    
    @Test
    void testConstructorDimensionMismatch() {
        assertThrows(IllegalArgumentException.class, () -> 
            new AffineTransformation(identity2D, translation3D));
    }
    
    @Test
    void testStaticTranslation() {
        var affine = AffineTransformation.translation(1.0, 2.0, 3.0);
        
        assertEquals(3, affine.getSourceDimension());
        assertEquals(3, affine.getTargetDimension());
        assertTrue(affine.isPureTranslation());
        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, affine.getTranslation());
    }
    
    @Test
    void testStaticRotation2D() {
        var angle = Math.PI / 6; // 30 degrees
        var affine = AffineTransformation.rotation2D(angle, translation2D);
        
        assertEquals(2, affine.getSourceDimension());
        assertEquals(2, affine.getTargetDimension());
        assertArrayEquals(translation2D, affine.getTranslation());
        
        // Verify rotation matrix properties
        assertTrue(affine.getLinearPart().isIsometric());
    }
    
    @Test
    void testRotation2DInvalidTranslation() {
        assertThrows(IllegalArgumentException.class, () -> 
            AffineTransformation.rotation2D(Math.PI / 4, translation3D));
    }
    
    @Test
    void testTransformCoordinate() throws Exception {
        var affine = new AffineTransformation(scaling2D, translation2D);
        var source = new Coordinate(new double[]{3.0, 4.0});
        
        var result = affine.transform(source);
        
        // Expected: scale by 2, then translate by (1, 2)
        // (3, 4) * 2 + (1, 2) = (6, 8) + (1, 2) = (7, 10)
        assertArrayEquals(new double[]{7.0, 10.0}, result.values(), 1e-10);
    }
    
    @Test
    void testTransformBounds() throws Exception {
        var affine = new AffineTransformation(scaling2D, translation2D);
        var bounds = new Bounds(new double[]{0.0, 0.0}, new double[]{2.0, 3.0});
        
        var result = affine.transformBounds(bounds);
        
        // Scale by 2, then translate by (1, 2)
        // Min: (0, 0) * 2 + (1, 2) = (1, 2)
        // Max: (2, 3) * 2 + (1, 2) = (5, 8)
        assertArrayEquals(new double[]{1.0, 2.0}, result.min(), 1e-10);
        assertArrayEquals(new double[]{5.0, 8.0}, result.max(), 1e-10);
    }
    
    @Test
    void testInverseTransform() throws Exception {
        var affine = new AffineTransformation(scaling2D, translation2D);
        var target = new Coordinate(new double[]{7.0, 10.0});
        
        var result = affine.inverseTransform(target);
        
        // Expected: translate by (-1, -2), then scale by 0.5
        // (7, 10) - (1, 2) = (6, 8), then (6, 8) * 0.5 = (3, 4)
        assertArrayEquals(new double[]{3.0, 4.0}, result.values(), 1e-10);
    }
    
    @Test
    void testInverseTransformNotInvertible() {
        // Create singular matrix (determinant = 0)
        double[][] singularMatrix = {{1.0, 2.0}, {2.0, 4.0}};
        var singular = new LinearTransformation(singularMatrix);
        var affine = new AffineTransformation(singular, translation2D);
        
        assertThrows(TransformationException.class, () -> 
            affine.inverseTransform(new Coordinate(new double[]{1.0, 1.0})));
    }
    
    @Test
    void testInverse() {
        var affine = new AffineTransformation(scaling2D, translation2D);
        var inverseOpt = affine.inverse();
        
        assertTrue(inverseOpt.isPresent());
        var inverse = (AffineTransformation) inverseOpt.get();
        
        // Test round-trip
        var original = new Coordinate(new double[]{3.0, 4.0});
        try {
            var transformed = affine.transform(original);
            var backTransformed = inverse.transform(transformed);
            
            assertArrayEquals(original.values(), backTransformed.values(), 1e-10);
        } catch (TransformationException e) {
            fail("Transformation should not fail", e);
        }
    }
    
    @Test
    void testInverseNotInvertible() {
        double[][] singularMatrix = {{1.0, 2.0}, {2.0, 4.0}};
        var singular = new LinearTransformation(singularMatrix);
        var affine = new AffineTransformation(singular, translation2D);
        
        var inverseOpt = affine.inverse();
        assertTrue(inverseOpt.isEmpty());
    }
    
    @Test
    void testComposeWithAffineTransformation() {
        var affine1 = new AffineTransformation(scaling2D, translation2D);
        var affine2 = AffineTransformation.translation(2.0, 3.0);
        
        var composed = affine1.compose(affine2);
        assertTrue(composed instanceof AffineTransformation);
        
        // Test composition
        var source = new Coordinate(new double[]{1.0, 2.0});
        try {
            var step1 = affine1.transform(source);
            var expected = affine2.transform(step1);
            var actual = composed.transform(source);
            
            assertArrayEquals(expected.values(), actual.values(), 1e-10);
        } catch (TransformationException e) {
            fail("Transformation should not fail", e);
        }
    }
    
    @Test
    void testComposeWithOtherTransformation() {
        var affine = new AffineTransformation(scaling2D, translation2D);
        var other = new ScalingTransformation(3.0, 4.0);
        
        var composed = affine.compose(other);
        assertTrue(composed instanceof CompositeTransformation);
    }
    
    @Test
    void testDeterminant() {
        var affine = new AffineTransformation(scaling2D, translation2D);
        assertEquals(4.0, affine.determinant(), 1e-10); // 2 * 2 = 4
    }
    
    @Test
    void testIsIsometric() {
        var affine1 = new AffineTransformation(identity2D, translation2D);
        assertTrue(affine1.isIsometric());
        
        var affine2 = new AffineTransformation(scaling2D, translation2D);
        assertFalse(affine2.isIsometric());
        
        var affine3 = new AffineTransformation(rotation2D, translation2D);
        assertTrue(affine3.isIsometric());
    }
    
    @Test
    void testIsConformal() {
        var affine1 = new AffineTransformation(identity2D, translation2D);
        assertTrue(affine1.isConformal());
        
        var affine2 = new AffineTransformation(scaling2D, translation2D);
        assertTrue(affine2.isConformal()); // Uniform scaling is conformal
        
        var affine3 = new AffineTransformation(rotation2D, translation2D);
        assertTrue(affine3.isConformal());
    }
    
    @Test
    void testIsInvertible() {
        var affine1 = new AffineTransformation(scaling2D, translation2D);
        assertTrue(affine1.isInvertible());
        
        double[][] singularMatrix = {{1.0, 2.0}, {2.0, 4.0}};
        var singular = new LinearTransformation(singularMatrix);
        var affine2 = new AffineTransformation(singular, translation2D);
        assertFalse(affine2.isInvertible());
    }
    
    @Test
    void testGetDescription() {
        var affine = new AffineTransformation(scaling2D, translation2D);
        var description = affine.getDescription();
        
        assertNotNull(description);
        assertTrue(description.contains("AffineTransformation"));
        assertTrue(description.contains("Translation"));
    }
    
    @Test
    void testIsPureTranslation() {
        var affine1 = AffineTransformation.translation(1.0, 2.0);
        assertTrue(affine1.isPureTranslation());
        
        var affine2 = new AffineTransformation(scaling2D, translation2D);
        assertFalse(affine2.isPureTranslation());
    }
    
    @Test
    void testIsPureLinear() {
        var affine1 = new AffineTransformation(scaling2D, new double[]{0.0, 0.0});
        assertTrue(affine1.isPureLinear());
        
        var affine2 = new AffineTransformation(scaling2D, translation2D);
        assertFalse(affine2.isPureLinear());
    }
    
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void testDifferentDimensions(int dimension) {
        var identity = LinearTransformation.identity(dimension);
        var translation = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            translation[i] = i + 1.0;
        }
        
        var affine = new AffineTransformation(identity, translation);
        
        assertEquals(dimension, affine.getSourceDimension());
        assertEquals(dimension, affine.getTargetDimension());
        assertTrue(affine.isPureTranslation());
    }
    
    @ParameterizedTest
    @CsvSource({
        "0.0, 0.0, 0.0",
        "1.0, 1.0, 1.414213562373095",
        "3.0, 4.0, 5.0",
        "-1.0, 0.0, 1.0"
    })
    void testTranslationMagnitudes(double x, double y, double expectedMagnitude) {
        var affine = AffineTransformation.translation(x, y);
        var translation = affine.getTranslation();
        
        var magnitude = Math.sqrt(translation[0] * translation[0] + translation[1] * translation[1]);
        assertEquals(expectedMagnitude, magnitude, 1e-10);
    }
    
    @Test
    void testGetTranslationDefensiveCopy() {
        var affine = new AffineTransformation(identity2D, translation2D);
        var translation = affine.getTranslation();
        
        // Modify the returned array
        translation[0] = 999.0;
        
        // Original should be unchanged
        assertArrayEquals(new double[]{1.0, 2.0}, affine.getTranslation());
    }
    
    @Test
    void testToString() {
        var affine = new AffineTransformation(scaling2D, translation2D);
        var string = affine.toString();
        
        assertNotNull(string);
        assertTrue(string.contains("AffineTransformation"));
        assertTrue(string.contains("dimension=2"));
    }
    
    @Test
    void testEquals() {
        var affine1 = new AffineTransformation(scaling2D, translation2D);
        var affine2 = new AffineTransformation(scaling2D, translation2D);
        var affine3 = new AffineTransformation(identity2D, translation2D);
        
        assertEquals(affine1, affine2);
        assertEquals(affine1.hashCode(), affine2.hashCode());
        assertNotEquals(affine1, affine3);
        assertNotEquals(affine1, null);
        assertNotEquals(affine1, "not an affine transformation");
    }
    
    @Test
    void testHashCode() {
        var affine1 = new AffineTransformation(scaling2D, translation2D);
        var affine2 = new AffineTransformation(scaling2D, translation2D);
        
        assertEquals(affine1.hashCode(), affine2.hashCode());
    }
    
    @Test
    void testTransformationException() {
        var affine = new AffineTransformation(identity2D, translation2D);
        var wrongDimensionCoord = new Coordinate(new double[]{1.0, 2.0, 3.0});
        
        assertThrows(TransformationException.class, () -> 
            affine.transform(wrongDimensionCoord));
    }
    
    @Test
    void testBoundsTransformationException() {
        var affine = new AffineTransformation(identity2D, translation2D);
        var wrongDimensionBounds = new Bounds(new double[]{1.0, 2.0, 3.0}, new double[]{4.0, 5.0, 6.0});
        
        assertThrows(TransformationException.class, () -> 
            affine.transformBounds(wrongDimensionBounds));
    }
    
    @Test
    void testSelfComposition() {
        var affine = new AffineTransformation(scaling2D, translation2D);
        var composed = affine.compose(affine);
        
        assertTrue(composed instanceof AffineTransformation);
        
        // Test that double transformation gives expected result
        var source = new Coordinate(new double[]{1.0, 1.0});
        try {
            var doubleTransformed = composed.transform(source);
            var stepByStep = affine.transform(affine.transform(source));
            
            assertArrayEquals(stepByStep.values(), doubleTransformed.values(), 1e-10);
        } catch (TransformationException e) {
            fail("Transformation should not fail", e);
        }
    }
}