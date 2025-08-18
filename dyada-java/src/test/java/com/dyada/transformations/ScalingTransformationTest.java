package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ScalingTransformationTest {
    
    @Test
    void testConstructorWithScaleFactors() {
        var scaling = new ScalingTransformation(2.0, 3.0, 4.0);
        
        assertEquals(3, scaling.getSourceDimension());
        assertEquals(3, scaling.getTargetDimension());
        assertArrayEquals(new double[]{2.0, 3.0, 4.0}, scaling.getScaleFactors());
        assertFalse(scaling.isUniform());
    }
    
    @Test
    void testConstructorNullScaleFactors() {
        assertThrows(IllegalArgumentException.class, () -> 
            new ScalingTransformation((double[]) null));
    }
    
    @Test
    void testConstructorEmptyScaleFactors() {
        assertThrows(IllegalArgumentException.class, () -> 
            new ScalingTransformation());
    }
    
    @Test
    void testConstructorZeroScaleFactor() {
        assertThrows(IllegalArgumentException.class, () -> 
            new ScalingTransformation(1.0, 0.0, 2.0));
    }
    
    @Test
    void testConstructorNearZeroScaleFactor() {
        assertThrows(IllegalArgumentException.class, () -> 
            new ScalingTransformation(1.0, 1e-12, 2.0));
    }
    
    @Test
    void testUniformScaling() {
        var scaling = ScalingTransformation.uniform(3, 2.5);
        
        assertEquals(3, scaling.getSourceDimension());
        assertEquals(3, scaling.getTargetDimension());
        assertTrue(scaling.isUniform());
        assertEquals(2.5, scaling.getUniformScaleFactor());
        assertArrayEquals(new double[]{2.5, 2.5, 2.5}, scaling.getScaleFactors());
    }
    
    @Test
    void testUniformScalingInvalidDimension() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScalingTransformation.uniform(0, 2.0));
        assertThrows(IllegalArgumentException.class, () -> 
            ScalingTransformation.uniform(-1, 2.0));
    }
    
    @Test
    void testScale2D() {
        var scaling = ScalingTransformation.scale2D(2.0, 3.0);
        
        assertEquals(2, scaling.getSourceDimension());
        assertEquals(2, scaling.getTargetDimension());
        assertEquals(2.0, scaling.getScaleFactor(0));
        assertEquals(3.0, scaling.getScaleFactor(1));
        assertFalse(scaling.isUniform());
    }
    
    @Test
    void testScale3D() {
        var scaling = ScalingTransformation.scale3D(2.0, 3.0, 4.0);
        
        assertEquals(3, scaling.getSourceDimension());
        assertEquals(3, scaling.getTargetDimension());
        assertEquals(2.0, scaling.getScaleFactor(0));
        assertEquals(3.0, scaling.getScaleFactor(1));
        assertEquals(4.0, scaling.getScaleFactor(2));
        assertFalse(scaling.isUniform());
    }
    
    @Test
    void testStretchAxis() {
        var scaling = ScalingTransformation.stretchAxis(4, 2, 3.0);
        
        assertEquals(4, scaling.getSourceDimension());
        assertEquals(1.0, scaling.getScaleFactor(0));
        assertEquals(1.0, scaling.getScaleFactor(1));
        assertEquals(3.0, scaling.getScaleFactor(2));
        assertEquals(1.0, scaling.getScaleFactor(3));
        assertFalse(scaling.isUniform());
    }
    
    @Test
    void testStretchAxisInvalidDimension() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScalingTransformation.stretchAxis(0, 0, 2.0));
    }
    
    @Test
    void testStretchAxisInvalidAxis() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScalingTransformation.stretchAxis(3, 3, 2.0));
        assertThrows(IllegalArgumentException.class, () -> 
            ScalingTransformation.stretchAxis(3, -1, 2.0));
    }
    
    @Test
    void testMirror() {
        var scaling = ScalingTransformation.mirror(3, 0, 2);
        
        assertEquals(3, scaling.getSourceDimension());
        assertEquals(-1.0, scaling.getScaleFactor(0));
        assertEquals(1.0, scaling.getScaleFactor(1));
        assertEquals(-1.0, scaling.getScaleFactor(2));
        assertTrue(scaling.hasMirroring());
    }
    
    @Test
    void testMirrorInvalidDimension() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScalingTransformation.mirror(0, 0));
    }
    
    @Test
    void testMirrorInvalidAxis() {
        assertThrows(IllegalArgumentException.class, () -> 
            ScalingTransformation.mirror(3, 3));
        assertThrows(IllegalArgumentException.class, () -> 
            ScalingTransformation.mirror(3, -1));
    }
    
    @Test
    void testTransformCoordinate() throws Exception {
        var scaling = new ScalingTransformation(2.0, 3.0, 4.0);
        var source = new Coordinate(new double[]{1.0, 2.0, 3.0});
        
        var result = scaling.transform(source);
        
        assertArrayEquals(new double[]{2.0, 6.0, 12.0}, result.values(), 1e-10);
    }
    
    @Test
    void testTransformCoordinateWrongDimension() {
        var scaling = new ScalingTransformation(2.0, 3.0);
        var source = new Coordinate(new double[]{1.0, 2.0, 3.0});
        
        assertThrows(TransformationException.class, () -> 
            scaling.transform(source));
    }
    
    @Test
    void testInverseTransform() throws Exception {
        var scaling = new ScalingTransformation(2.0, 4.0, 0.5);
        var target = new Coordinate(new double[]{4.0, 8.0, 1.0});
        
        var result = scaling.inverseTransform(target);
        
        assertArrayEquals(new double[]{2.0, 2.0, 2.0}, result.values(), 1e-10);
    }
    
    @Test
    void testInverseTransformWrongDimension() {
        var scaling = new ScalingTransformation(2.0, 3.0);
        var target = new Coordinate(new double[]{1.0, 2.0, 3.0});
        
        assertThrows(TransformationException.class, () -> 
            scaling.inverseTransform(target));
    }
    
    @Test
    void testInverse() {
        var scaling = new ScalingTransformation(2.0, 4.0, 0.5);
        var inverseOpt = scaling.inverse();
        
        assertTrue(inverseOpt.isPresent());
        var inverse = (ScalingTransformation) inverseOpt.get();
        
        assertArrayEquals(new double[]{0.5, 0.25, 2.0}, inverse.getScaleFactors(), 1e-10);
    }
    
    @Test
    void testTransformBounds() throws Exception {
        var scaling = new ScalingTransformation(2.0, 3.0);
        var bounds = new Bounds(new double[]{1.0, 2.0}, new double[]{3.0, 4.0});
        
        var result = scaling.transformBounds(bounds);
        
        assertArrayEquals(new double[]{2.0, 6.0}, result.min(), 1e-10);
        assertArrayEquals(new double[]{6.0, 12.0}, result.max(), 1e-10);
    }
    
    @Test
    void testTransformBoundsWithNegativeScaling() throws Exception {
        var scaling = new ScalingTransformation(-2.0, 3.0);
        var bounds = new Bounds(new double[]{1.0, 2.0}, new double[]{3.0, 4.0});
        
        var result = scaling.transformBounds(bounds);
        
        // Negative scaling flips the bounds for that dimension
        assertArrayEquals(new double[]{-6.0, 6.0}, result.min(), 1e-10);
        assertArrayEquals(new double[]{-2.0, 12.0}, result.max(), 1e-10);
    }
    
    @Test
    void testTransformBoundsWrongDimension() {
        var scaling = new ScalingTransformation(2.0, 3.0);
        var bounds = new Bounds(new double[]{1.0, 2.0, 3.0}, new double[]{4.0, 5.0, 6.0});
        
        assertThrows(TransformationException.class, () -> 
            scaling.transformBounds(bounds));
    }
    
    @Test
    void testComposeWithScaling() {
        var scaling1 = new ScalingTransformation(2.0, 3.0);
        var scaling2 = new ScalingTransformation(4.0, 5.0);
        
        var composed = scaling1.compose(scaling2);
        assertTrue(composed instanceof ScalingTransformation);
        
        var result = (ScalingTransformation) composed;
        assertArrayEquals(new double[]{8.0, 15.0}, result.getScaleFactors(), 1e-10);
    }
    
    @Test
    void testComposeWithOtherTransformation() {
        var scaling = new ScalingTransformation(2.0, 3.0);
        var translation = new TranslationTransformation(1.0, 2.0);
        
        var composed = scaling.compose(translation);
        assertTrue(composed instanceof CompositeTransformation);
    }
    
    @Test
    void testDeterminant() {
        var scaling = new ScalingTransformation(2.0, 3.0, 4.0);
        assertEquals(24.0, scaling.determinant(), 1e-10); // 2 * 3 * 4 = 24
    }
    
    @Test
    void testDeterminantWithNegative() {
        var scaling = new ScalingTransformation(-2.0, 3.0);
        assertEquals(-6.0, scaling.determinant(), 1e-10);
    }
    
    @Test
    void testIsIsometric() {
        var scaling1 = new ScalingTransformation(1.0, 1.0, 1.0);
        assertTrue(scaling1.isIsometric());
        
        var scaling2 = new ScalingTransformation(-1.0, 1.0, 1.0);
        assertTrue(scaling2.isIsometric());
        
        var scaling3 = new ScalingTransformation(2.0, 1.0, 1.0);
        assertFalse(scaling3.isIsometric());
    }
    
    @Test
    void testIsConformal() {
        var scaling1 = ScalingTransformation.uniform(3, 2.0);
        assertTrue(scaling1.isConformal());
        
        var scaling2 = new ScalingTransformation(2.0, 3.0);
        assertFalse(scaling2.isConformal());
    }
    
    @Test
    void testIsInvertible() {
        var scaling = new ScalingTransformation(2.0, 3.0, 4.0);
        assertTrue(scaling.isInvertible());
    }
    
    @Test
    void testGetDescription() {
        var scaling = new ScalingTransformation(2.0, 3.0);
        var description = scaling.getDescription();
        
        assertNotNull(description);
        assertTrue(description.contains("ScalingTransformation"));
        assertTrue(description.contains("Uniform"));
        assertTrue(description.contains("Determinant"));
    }
    
    @Test
    void testGetScaleFactorByIndex() {
        var scaling = new ScalingTransformation(2.0, 3.0, 4.0);
        
        assertEquals(2.0, scaling.getScaleFactor(0));
        assertEquals(3.0, scaling.getScaleFactor(1));
        assertEquals(4.0, scaling.getScaleFactor(2));
        
        assertThrows(IndexOutOfBoundsException.class, () -> 
            scaling.getScaleFactor(3));
    }
    
    @Test
    void testIsUniform() {
        var uniform = ScalingTransformation.uniform(3, 2.5);
        assertTrue(uniform.isUniform());
        
        var nonUniform = new ScalingTransformation(2.0, 3.0, 4.0);
        assertFalse(nonUniform.isUniform());
        
        var singleDimension = new ScalingTransformation(5.0);
        assertTrue(singleDimension.isUniform());
    }
    
    @Test
    void testGetUniformScaleFactor() {
        var uniform = ScalingTransformation.uniform(3, 2.5);
        assertEquals(2.5, uniform.getUniformScaleFactor());
        
        var nonUniform = new ScalingTransformation(2.0, 3.0);
        assertThrows(IllegalStateException.class, nonUniform::getUniformScaleFactor);
    }
    
    @Test
    void testHasMirroring() {
        var positive = new ScalingTransformation(2.0, 3.0);
        assertFalse(positive.hasMirroring());
        
        var negative = new ScalingTransformation(-2.0, 3.0);
        assertTrue(negative.hasMirroring());
        
        var mirror = ScalingTransformation.mirror(3, 0, 2);
        assertTrue(mirror.hasMirroring());
    }
    
    @Test
    void testGetVolumeScaleFactor() {
        var scaling = new ScalingTransformation(2.0, 3.0, 4.0);
        assertEquals(24.0, scaling.getVolumeScaleFactor(), 1e-10);
        
        var withNegative = new ScalingTransformation(-2.0, 3.0);
        assertEquals(6.0, withNegative.getVolumeScaleFactor(), 1e-10);
    }
    
    @Test
    void testGetScaleFactorsDefensiveCopy() {
        var scaling = new ScalingTransformation(2.0, 3.0);
        var factors = scaling.getScaleFactors();
        
        // Modify the returned array
        factors[0] = 999.0;
        
        // Original should be unchanged
        assertEquals(2.0, scaling.getScaleFactor(0));
    }
    
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void testDifferentDimensions(int dimension) {
        var scaling = ScalingTransformation.uniform(dimension, 2.0);
        
        assertEquals(dimension, scaling.getSourceDimension());
        assertEquals(dimension, scaling.getTargetDimension());
        assertTrue(scaling.isUniform());
        assertEquals(2.0, scaling.getUniformScaleFactor());
    }
    
    @ParameterizedTest
    @CsvSource({
        "1.0, 1.0, true",
        "2.0, 2.0, true", 
        "1.0, 2.0, false",
        "-1.0, -1.0, true",
        "1.0, -1.0, false"
    })
    void testUniformScalingDetection(double factor1, double factor2, boolean expectedUniform) {
        var scaling = new ScalingTransformation(factor1, factor2);
        assertEquals(expectedUniform, scaling.isUniform());
    }
    
    @ParameterizedTest
    @CsvSource({
        "2.0, 3.0, 6.0",
        "-2.0, 3.0, -6.0",
        "0.5, 0.5, 0.25",
        "-1.0, -1.0, 1.0"
    })
    void testDeterminantCalculation(double factor1, double factor2, double expectedDeterminant) {
        var scaling = new ScalingTransformation(factor1, factor2);
        assertEquals(expectedDeterminant, scaling.determinant(), 1e-10);
    }
    
    @Test
    void testToString() {
        var scaling = new ScalingTransformation(2.0, 3.0);
        var string = scaling.toString();
        
        assertNotNull(string);
        assertTrue(string.contains("ScalingTransformation"));
        assertTrue(string.contains("uniform=false"));
    }
    
    @Test
    void testEquals() {
        var scaling1 = new ScalingTransformation(2.0, 3.0);
        var scaling2 = new ScalingTransformation(2.0, 3.0);
        var scaling3 = new ScalingTransformation(2.0, 4.0);
        
        assertEquals(scaling1, scaling2);
        assertEquals(scaling1.hashCode(), scaling2.hashCode());
        assertNotEquals(scaling1, scaling3);
        assertNotEquals(scaling1, null);
        assertNotEquals(scaling1, "not a scaling transformation");
    }
    
    @Test
    void testHashCode() {
        var scaling1 = new ScalingTransformation(2.0, 3.0);
        var scaling2 = new ScalingTransformation(2.0, 3.0);
        
        assertEquals(scaling1.hashCode(), scaling2.hashCode());
    }
    
    @Test
    void testRoundTripTransformation() throws Exception {
        var scaling = new ScalingTransformation(2.0, 3.0, 0.5);
        var original = new Coordinate(new double[]{4.0, 6.0, 8.0});
        
        var transformed = scaling.transform(original);
        var backTransformed = scaling.inverseTransform(transformed);
        
        assertArrayEquals(original.values(), backTransformed.values(), 1e-10);
    }
    
    @Test
    void testMultipleComposition() {
        var scaling1 = new ScalingTransformation(2.0, 3.0);
        var scaling2 = new ScalingTransformation(4.0, 5.0);
        var scaling3 = new ScalingTransformation(6.0, 7.0);
        
        var composed = scaling1.compose(scaling2).compose(scaling3);
        assertTrue(composed instanceof ScalingTransformation);
        
        var result = (ScalingTransformation) composed;
        // 2*4*6 = 48, 3*5*7 = 105
        assertArrayEquals(new double[]{48.0, 105.0}, result.getScaleFactors(), 1e-10);
    }
    
    @Test
    void testNegativeScalingBehavior() throws Exception {
        var scaling = new ScalingTransformation(-1.0, -1.0);
        var source = new Coordinate(new double[]{2.0, 3.0});
        
        var result = scaling.transform(source);
        
        assertArrayEquals(new double[]{-2.0, -3.0}, result.values(), 1e-10);
        assertTrue(scaling.hasMirroring());
        assertTrue(scaling.isIsometric());
        assertEquals(1.0, scaling.determinant(), 1e-10);
    }
}