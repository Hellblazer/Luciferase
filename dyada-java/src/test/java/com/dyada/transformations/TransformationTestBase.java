package com.dyada.transformations;

import com.dyada.TestBase;
import com.dyada.core.coordinates.Coordinate;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for common transformation tests.
 * Eliminates duplicate testing patterns across all transformation types.
 */
@DisplayName("Common Transformation Tests")
abstract class TransformationTestBase extends TestBase {

    /**
     * Create a sample transformation for testing common properties.
     * Must be non-identity and invertible for meaningful tests.
     */
    protected abstract CoordinateTransformation createSampleTransformation();

    /**
     * Create a different transformation for inequality testing.
     */
    protected abstract CoordinateTransformation createDifferentTransformation();

    /**
     * Create an invalid transformation that should throw during construction.
     * Return null if the transformation type doesn't have invalid states.
     * Default implementation returns null (no invalid states).
     */
    protected CoordinateTransformation createInvalidTransformation() {
        return null;
    }

    /**
     * Get a valid input coordinate for the sample transformation.
     */
    protected abstract Coordinate getValidInputCoordinate();

    /**
     * Test basic properties that all transformations should have.
     */
    @Test
    @DisplayName("Basic transformation properties")
    void testBasicProperties() {
        var transformation = createSampleTransformation();
        
        // Dimension consistency
        assertTrue(transformation.getSourceDimension() > 0);
        assertTrue(transformation.getTargetDimension() > 0);
        
        // String representation exists
        assertNotNull(transformation.toString());
        assertFalse(transformation.toString().trim().isEmpty());
    }

    /**
     * Test dimension handling and validation.
     */
    @Test
    @DisplayName("Dimension validation")
    void testDimensionValidation() throws TransformationException {
        var transformation = createSampleTransformation();
        var validInput = getValidInputCoordinate();
        
        // Valid input should work
        assertDoesNotThrow(() -> transformation.transform(validInput));
        
        // Wrong dimension should fail
        if (transformation.getSourceDimension() != 1) {
            var wrongDimensionInput = new Coordinate(new double[]{1.0});
            assertThrows(TransformationException.class, 
                () -> transformation.transform(wrongDimensionInput));
        }
        
        if (transformation.getSourceDimension() != 5) {
            var wrongDimensionInput = new Coordinate(new double[]{1.0, 2.0, 3.0, 4.0, 5.0});
            assertThrows(TransformationException.class, 
                () -> transformation.transform(wrongDimensionInput));
        }
    }

    /**
     * Test equals and hashCode contract.
     */
    @Test
    @DisplayName("Equals and hashCode contract")
    void testEqualsAndHashCode() {
        var transformation1 = createSampleTransformation();
        var transformation2 = createSampleTransformation();
        var different = createDifferentTransformation();
        
        // Reflexivity
        assertEquals(transformation1, transformation1);
        assertEquals(transformation1.hashCode(), transformation1.hashCode());
        
        // Symmetry and transitivity
        assertEquals(transformation1, transformation2);
        assertEquals(transformation2, transformation1);
        assertEquals(transformation1.hashCode(), transformation2.hashCode());
        
        // Inequality
        assertNotEquals(transformation1, different);
        assertNotEquals(transformation1, null);
        assertNotEquals(transformation1, "not a transformation");
    }

    /**
     * Test exception handling for invalid inputs.
     */
    @Test
    @DisplayName("Exception handling")
    void testExceptionHandling() {
        var transformation = createSampleTransformation();
        
        // Null input should throw
        assertThrows(Exception.class, () -> transformation.transform(null));
        
        // Invalid transformation construction
        var invalidTransformation = createInvalidTransformation();
        if (invalidTransformation != null) {
            // If an invalid transformation was provided, test it fails appropriately
            var validInput = getValidInputCoordinate();
            assertThrows(Exception.class, () -> invalidTransformation.transform(validInput));
        }
    }

    /**
     * Test transformation composition behavior.
     */
    @Test
    @DisplayName("Transformation composition")
    void testComposition() {
        var t1 = createSampleTransformation();
        var t2 = createDifferentTransformation();
        
        // Composition should succeed if dimensions match
        if (t1.getTargetDimension() == t2.getSourceDimension()) {
            assertDoesNotThrow(() -> t1.compose(t2));
            var composed = t1.compose(t2);
            
            assertEquals(t2.getSourceDimension(), composed.getSourceDimension());
            assertEquals(t1.getTargetDimension(), composed.getTargetDimension());
        }
        
        // Composition with mismatched dimensions should fail
        if (t1.getTargetDimension() != t2.getSourceDimension()) {
            assertThrows(Exception.class, () -> t1.compose(t2));
        }
    }

    /**
     * Test mathematical properties for all transformations.
     */
    @Test
    @DisplayName("Mathematical properties")
    void testMathematicalProperties() throws TransformationException {
        var transformation = createSampleTransformation();
        var input = getValidInputCoordinate();
        
        // Transform should produce valid output
        var output = transformation.transform(input);
        assertNotNull(output);
        assertEquals(transformation.getTargetDimension(), output.dimensions());
        
        // Determinant should be finite
        var determinant = transformation.determinant();
        assertTrue(Double.isFinite(determinant));
    }

    /**
     * Test parameterized dimensions for transformations that support multiple dimensions.
     */
    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    @DisplayName("Multi-dimensional support")
    void testMultiDimensionalSupport(int dimension) {
        // This test should be overridden by subclasses that support parameterized dimensions
        // Default implementation does nothing
    }
}