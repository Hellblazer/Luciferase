package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for testing invertible transformations.
 * Extends common transformation tests with inverse operation testing.
 */
@DisplayName("Invertible Transformation Tests")
abstract class InvertibleTransformationTestBase extends TransformationTestBase {

    /**
     * Create a non-invertible transformation for testing.
     * Return null if the transformation type is always invertible.
     * Default implementation returns null (always invertible).
     */
    protected CoordinateTransformation createNonInvertibleTransformation() {
        return null;
    }

    /**
     * Test inverse operations and round-trip transformations.
     */
    @Test
    @DisplayName("Inverse operations")
    void testInverseOperations() throws TransformationException {
        var transformation = createSampleTransformation();
        var input = getValidInputCoordinate();
        
        // Forward transformation
        var transformed = transformation.transform(input);
        
        // Inverse transformation
        var inverse = transformation.inverse().orElseThrow();
        assertNotNull(inverse);
        
        // Round-trip should return to original
        var roundTrip = inverse.transform(transformed);
        assertArrayEquals(input.values(), roundTrip.values(), 1e-10);
        
        // Inverse properties
        assertEquals(transformation.getTargetDimension(), inverse.getSourceDimension());
        assertEquals(transformation.getSourceDimension(), inverse.getTargetDimension());
    }

    /**
     * Test round-trip transformation accuracy.
     */
    @Test
    @DisplayName("Round-trip transformation")
    void testRoundTripTransformation() throws TransformationException {
        var transformation = createSampleTransformation();
        var input = getValidInputCoordinate();
        
        // Forward then inverse should return original
        var forward = transformation.transform(input);
        var inverse = transformation.inverse().orElseThrow();
        var backToOriginal = inverse.transform(forward);
        
        for (int i = 0; i < input.dimensions(); i++) {
            assertEquals(input.get(i), backToOriginal.get(i), 1e-10,
                "Round-trip failed at dimension " + i);
        }
    }

    /**
     * Test invertibility properties.
     */
    @Test
    @DisplayName("Invertibility properties")
    void testInvertibilityProperties() throws TransformationException {
        var transformation = createSampleTransformation();
        
        // Should be marked as invertible
        assertTrue(transformation.isInvertible());
        
        // Determinant should be non-zero for invertible transformations
        var determinant = transformation.determinant();
        assertNotEquals(0.0, determinant, 1e-10);
    }

    /**
     * Test non-invertible transformation behavior.
     */
    @Test
    @DisplayName("Non-invertible transformation handling")
    void testNonInvertibleTransformation() {
        var nonInvertible = createNonInvertibleTransformation();
        
        if (nonInvertible != null) {
            // Should be marked as non-invertible
            assertFalse(nonInvertible.isInvertible());
            
            // Getting inverse should return empty
            assertTrue(nonInvertible.inverse().isEmpty());
        }
    }

    /**
     * Test inverse of inverse returns original transformation.
     */
    @Test
    @DisplayName("Inverse of inverse")
    void testInverseOfInverse() throws TransformationException {
        var transformation = createSampleTransformation();
        var inverse = transformation.inverse().orElseThrow();
        var inverseOfInverse = inverse.inverse().orElseThrow();
        
        var input = getValidInputCoordinate();
        var original = transformation.transform(input);
        var doubleInverse = inverseOfInverse.transform(input);
        
        assertArrayEquals(original.values(), doubleInverse.values(), 1e-10);
    }
}