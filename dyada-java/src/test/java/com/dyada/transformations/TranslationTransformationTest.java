package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.Bounds;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class TranslationTransformationTest extends InvertibleTransformationTestBase {
    
    @Override
    protected CoordinateTransformation createSampleTransformation() {
        return new TranslationTransformation(1.0, 2.0, 3.0);
    }
    
    @Override
    protected CoordinateTransformation createDifferentTransformation() {
        return new TranslationTransformation(2.0, 3.0, 4.0);
    }
    
    @Override
    protected Coordinate getValidInputCoordinate() {
        return new Coordinate(new double[]{1.0, 2.0, 3.0});
    }
    
    // TranslationTransformation-specific tests (not covered by base classes)
    
    @Test
    void testConstructorWithTranslationArray() {
        var translation = new TranslationTransformation(1.0, 2.0, 3.0);
        
        assertEquals(3, translation.getSourceDimension());
        assertEquals(3, translation.getTargetDimension());
        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, translation.getTranslation(), 1e-10);
    }
    
    @Test
    void testConstructorWithCoordinate() {
        var coord = new Coordinate(new double[]{1.0, 2.0, 3.0});
        var translation = new TranslationTransformation(coord);
        
        assertEquals(3, translation.getSourceDimension());
        assertEquals(3, translation.getTargetDimension());
        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, translation.getTranslation(), 1e-10);
    }
    
    @Test
    void testConstructorNullTranslation() {
        assertThrows(IllegalArgumentException.class, () -> 
            new TranslationTransformation((double[]) null));
    }
    
    @Test
    void testConstructorEmptyTranslation() {
        assertThrows(IllegalArgumentException.class, () -> 
            new TranslationTransformation());
    }
    
    @Test
    void testTranslate2D() {
        var translation = TranslationTransformation.translate2D(2.0, 3.0);
        
        assertEquals(2, translation.getSourceDimension());
        assertEquals(2, translation.getTargetDimension());
        assertEquals(2.0, translation.getTranslation(0));
        assertEquals(3.0, translation.getTranslation(1));
    }
    
    @Test
    void testTranslate3D() {
        var translation = TranslationTransformation.translate3D(1.0, 2.0, 3.0);
        
        assertEquals(3, translation.getSourceDimension());
        assertEquals(3, translation.getTargetDimension());
        assertEquals(1.0, translation.getTranslation(0));
        assertEquals(2.0, translation.getTranslation(1));
        assertEquals(3.0, translation.getTranslation(2));
    }
    
    @Test
    void testTranslateAxis() {
        var translation = TranslationTransformation.translateAxis(4, 2, 5.0);
        
        assertEquals(4, translation.getSourceDimension());
        assertEquals(0.0, translation.getTranslation(0));
        assertEquals(0.0, translation.getTranslation(1));
        assertEquals(5.0, translation.getTranslation(2));
        assertEquals(0.0, translation.getTranslation(3));
    }
    
    @Test
    void testTranslateAxisInvalidDimension() {
        assertThrows(IllegalArgumentException.class, () -> 
            TranslationTransformation.translateAxis(0, 0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> 
            TranslationTransformation.translateAxis(-1, 0, 1.0));
    }
    
    @Test
    void testTranslateAxisInvalidAxis() {
        assertThrows(IllegalArgumentException.class, () -> 
            TranslationTransformation.translateAxis(3, 3, 1.0));
        assertThrows(IllegalArgumentException.class, () -> 
            TranslationTransformation.translateAxis(3, -1, 1.0));
    }
    
    @Test
    void testFromTo() {
        var from = new Coordinate(new double[]{1.0, 2.0});
        var to = new Coordinate(new double[]{4.0, 6.0});
        var translation = TranslationTransformation.fromTo(from, to);
        
        assertEquals(2, translation.getSourceDimension());
        assertArrayEquals(new double[]{3.0, 4.0}, translation.getTranslation(), 1e-10);
    }
    
    @Test
    void testFromToDimensionMismatch() {
        var from = new Coordinate(new double[]{1.0, 2.0});
        var to = new Coordinate(new double[]{4.0, 6.0, 8.0});
        
        assertThrows(IllegalArgumentException.class, () -> 
            TranslationTransformation.fromTo(from, to));
    }
    
    @Test
    void testIdentity() {
        var translation = TranslationTransformation.identity(3);
        
        assertEquals(3, translation.getSourceDimension());
        assertEquals(3, translation.getTargetDimension());
        assertTrue(translation.isIdentity());
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, translation.getTranslation(), 1e-10);
    }
    
    @Test
    void testIdentityInvalidDimension() {
        assertThrows(IllegalArgumentException.class, () -> 
            TranslationTransformation.identity(0));
        assertThrows(IllegalArgumentException.class, () -> 
            TranslationTransformation.identity(-1));
    }
    
    @Test
    void testTransformCoordinate() throws Exception {
        var translation = new TranslationTransformation(2.0, 3.0, 4.0);
        var source = new Coordinate(new double[]{1.0, 2.0, 3.0});
        
        var result = translation.transform(source);
        
        assertArrayEquals(new double[]{3.0, 5.0, 7.0}, result.values(), 1e-10);
    }
    
    @Test
    void testTransformCoordinateWrongDimension() {
        var translation = new TranslationTransformation(1.0, 2.0);
        var source = new Coordinate(new double[]{1.0, 2.0, 3.0});
        
        assertThrows(TransformationException.class, () -> 
            translation.transform(source));
    }
    
    @Test
    void testInverseTransform() throws Exception {
        var translation = new TranslationTransformation(2.0, 3.0, 4.0);
        var target = new Coordinate(new double[]{5.0, 8.0, 11.0});
        
        var result = translation.inverseTransform(target);
        
        assertArrayEquals(new double[]{3.0, 5.0, 7.0}, result.values(), 1e-10);
    }
    
    @Test
    void testInverseTransformWrongDimension() {
        var translation = new TranslationTransformation(1.0, 2.0);
        var target = new Coordinate(new double[]{1.0, 2.0, 3.0});
        
        assertThrows(TransformationException.class, () -> 
            translation.inverseTransform(target));
    }
    
    @Test
    void testInverse() {
        var translation = new TranslationTransformation(2.0, 3.0, 4.0);
        var inverseOpt = translation.inverse();
        
        assertTrue(inverseOpt.isPresent());
        var inverse = (TranslationTransformation) inverseOpt.get();
        
        assertArrayEquals(new double[]{-2.0, -3.0, -4.0}, inverse.getTranslation(), 1e-10);
    }
    
    @Test
    void testTransformBounds() throws Exception {
        var translation = new TranslationTransformation(2.0, 3.0);
        var bounds = new Bounds(new double[]{1.0, 2.0}, new double[]{4.0, 5.0});
        
        var result = translation.transformBounds(bounds);
        
        assertArrayEquals(new double[]{3.0, 5.0}, result.min(), 1e-10);
        assertArrayEquals(new double[]{6.0, 8.0}, result.max(), 1e-10);
    }
    
    @Test
    void testTransformBoundsWrongDimension() {
        var translation = new TranslationTransformation(1.0, 2.0);
        var bounds = new Bounds(new double[]{1.0, 2.0, 3.0}, new double[]{4.0, 5.0, 6.0});
        
        assertThrows(TransformationException.class, () -> 
            translation.transformBounds(bounds));
    }
    
    @Test
    void testComposeWithTranslation() {
        var translation1 = new TranslationTransformation(1.0, 2.0);
        var translation2 = new TranslationTransformation(3.0, 4.0);
        
        var composed = translation1.compose(translation2);
        assertTrue(composed instanceof TranslationTransformation);
        
        var result = (TranslationTransformation) composed;
        assertArrayEquals(new double[]{4.0, 6.0}, result.getTranslation(), 1e-10);
    }
    
    @Test
    void testComposeWithOtherTransformation() {
        var translation = new TranslationTransformation(1.0, 2.0);
        var scaling = new ScalingTransformation(2.0, 3.0);
        
        var composed = translation.compose(scaling);
        assertTrue(composed instanceof CompositeTransformation);
    }
    
    @Test
    void testDeterminant() {
        var translation = new TranslationTransformation(1.0, 2.0, 3.0);
        assertEquals(1.0, translation.determinant(), 1e-10);
    }
    
    @Test
    void testIsIsometric() {
        var translation = new TranslationTransformation(1.0, 2.0, 3.0);
        assertTrue(translation.isIsometric());
    }
    
    @Test
    void testIsConformal() {
        var translation = new TranslationTransformation(1.0, 2.0, 3.0);
        assertTrue(translation.isConformal());
    }
    
    @Test
    void testIsInvertible() {
        var translation = new TranslationTransformation(1.0, 2.0, 3.0);
        assertTrue(translation.isInvertible());
    }
    
    @Test
    void testGetDescription() {
        var translation = new TranslationTransformation(1.0, 2.0);
        var description = translation.getDescription();
        
        assertNotNull(description);
        assertTrue(description.contains("TranslationTransformation"));
        assertTrue(description.contains("Magnitude"));
    }
    
    @Test
    void testGetTranslationByIndex() {
        var translation = new TranslationTransformation(1.0, 2.0, 3.0);
        
        assertEquals(1.0, translation.getTranslation(0));
        assertEquals(2.0, translation.getTranslation(1));
        assertEquals(3.0, translation.getTranslation(2));
        
        assertThrows(IndexOutOfBoundsException.class, () -> 
            translation.getTranslation(3));
    }
    
    @Test
    void testGetTranslationAsCoordinate() {
        var translation = new TranslationTransformation(1.0, 2.0, 3.0);
        var coord = translation.getTranslationAsCoordinate();
        
        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, coord.values(), 1e-10);
    }
    
    @Test
    void testIsIdentity() {
        var identity = TranslationTransformation.identity(3);
        assertTrue(identity.isIdentity());
        
        var nonIdentity = new TranslationTransformation(1.0, 0.0, 0.0);
        assertFalse(nonIdentity.isIdentity());
        
        var almostIdentity = new TranslationTransformation(1e-12, 0.0, 0.0);
        assertTrue(almostIdentity.isIdentity()); // Within tolerance
    }
    
    @Test
    void testGetMagnitude() {
        var translation = new TranslationTransformation(3.0, 4.0);
        assertEquals(5.0, translation.getMagnitude(), 1e-10);
        
        var zeroTranslation = TranslationTransformation.identity(2);
        assertEquals(0.0, zeroTranslation.getMagnitude(), 1e-10);
    }
    
    @Test
    void testGetManhattanDistance() {
        var translation = new TranslationTransformation(3.0, -4.0, 5.0);
        assertEquals(12.0, translation.getManhattanDistance(), 1e-10); // |3| + |-4| + |5| = 12
    }
    
    @Test
    void testGetChebyshevDistance() {
        var translation = new TranslationTransformation(3.0, -7.0, 5.0);
        assertEquals(7.0, translation.getChebyshevDistance(), 1e-10); // max(|3|, |-7|, |5|) = 7
    }
    
    @Test
    void testNormalize() {
        var translation = new TranslationTransformation(3.0, 4.0);
        var normalized = translation.normalize();
        
        assertEquals(1.0, normalized.getMagnitude(), 1e-10);
        assertArrayEquals(new double[]{0.6, 0.8}, normalized.getTranslation(), 1e-10);
    }
    
    @Test
    void testNormalizeZeroTranslation() {
        var zeroTranslation = TranslationTransformation.identity(2);
        
        assertThrows(IllegalStateException.class, zeroTranslation::normalize);
    }
    
    @Test
    void testScale() {
        var translation = new TranslationTransformation(2.0, 3.0);
        var scaled = translation.scale(2.5);
        
        assertArrayEquals(new double[]{5.0, 7.5}, scaled.getTranslation(), 1e-10);
    }
    
    @Test
    void testGetTranslationDefensiveCopy() {
        var translation = new TranslationTransformation(1.0, 2.0);
        var translationArray = translation.getTranslation();
        
        // Modify the returned array
        translationArray[0] = 999.0;
        
        // Original should be unchanged
        assertEquals(1.0, translation.getTranslation(0));
    }
    
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void testDifferentDimensions(int dimension) {
        var translationArray = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            translationArray[i] = i + 1.0;
        }
        
        var translation = new TranslationTransformation(translationArray);
        
        assertEquals(dimension, translation.getSourceDimension());
        assertEquals(dimension, translation.getTargetDimension());
        
        for (int i = 0; i < dimension; i++) {
            assertEquals(i + 1.0, translation.getTranslation(i));
        }
    }
    
    @ParameterizedTest
    @CsvSource({
        "0.0, 0.0, 0.0",
        "3.0, 4.0, 5.0",
        "-3.0, 4.0, 5.0",
        "1.0, 0.0, 1.0",
        "0.0, 1.0, 1.0"
    })
    void testMagnitudeCalculation(double x, double y, double expectedMagnitude) {
        var translation = new TranslationTransformation(x, y);
        assertEquals(expectedMagnitude, translation.getMagnitude(), 1e-10);
    }
    
    @ParameterizedTest
    @CsvSource({
        "3.0, -4.0, 5.0, 12.0",
        "0.0, 0.0, 0.0, 0.0",
        "1.0, 1.0, 1.0, 3.0",
        "-2.0, -3.0, -1.0, 6.0"
    })
    void testManhattanDistanceCalculation(double x, double y, double z, double expectedDistance) {
        var translation = new TranslationTransformation(x, y, z);
        assertEquals(expectedDistance, translation.getManhattanDistance(), 1e-10);
    }
    
    @ParameterizedTest
    @CsvSource({
        "3.0, -7.0, 5.0, 7.0",
        "0.0, 0.0, 0.0, 0.0",
        "2.0, 2.0, 2.0, 2.0",
        "-5.0, 3.0, -1.0, 5.0"
    })
    void testChebyshevDistanceCalculation(double x, double y, double z, double expectedDistance) {
        var translation = new TranslationTransformation(x, y, z);
        assertEquals(expectedDistance, translation.getChebyshevDistance(), 1e-10);
    }
    
    @Test
    void testToString() {
        var translation = new TranslationTransformation(1.0, 2.0);
        var string = translation.toString();
        
        assertNotNull(string);
        assertTrue(string.contains("TranslationTransformation"));
        assertTrue(string.contains("magnitude="));
    }
    
    @Test
    void testMultipleComposition() {
        var translation1 = new TranslationTransformation(1.0, 2.0);
        var translation2 = new TranslationTransformation(3.0, 4.0);
        var translation3 = new TranslationTransformation(5.0, 6.0);
        
        var composed = translation1.compose(translation2).compose(translation3);
        assertTrue(composed instanceof TranslationTransformation);
        
        var result = (TranslationTransformation) composed;
        // 1+3+5 = 9, 2+4+6 = 12
        assertArrayEquals(new double[]{9.0, 12.0}, result.getTranslation(), 1e-10);
    }
    
    @Test
    void testZeroTranslationBehavior() throws Exception {
        var identity = TranslationTransformation.identity(3);
        var source = new Coordinate(new double[]{2.0, 3.0, 4.0});
        
        var result = identity.transform(source);
        
        assertArrayEquals(source.values(), result.values(), 1e-10);
        assertTrue(identity.isIdentity());
        assertEquals(1.0, identity.determinant(), 1e-10);
        assertEquals(0.0, identity.getMagnitude(), 1e-10);
    }
    
    @Test
    void testNegativeTranslationBehavior() throws Exception {
        var translation = new TranslationTransformation(-1.0, -2.0);
        var source = new Coordinate(new double[]{5.0, 7.0});
        
        var result = translation.transform(source);
        
        assertArrayEquals(new double[]{4.0, 5.0}, result.values(), 1e-10);
        assertTrue(translation.isIsometric());
        assertTrue(translation.isConformal());
        assertEquals(1.0, translation.determinant(), 1e-10);
    }
    
    @Test
    void testInverseSymmetry() {
        var translation = new TranslationTransformation(3.0, 4.0, 5.0);
        var inverse = (TranslationTransformation) translation.inverse().get();
        var doubleInverse = (TranslationTransformation) inverse.inverse().get();
        
        assertArrayEquals(translation.getTranslation(), doubleInverse.getTranslation(), 1e-10);
    }
}