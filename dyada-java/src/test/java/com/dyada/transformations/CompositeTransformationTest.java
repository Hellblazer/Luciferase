package com.dyada.transformations;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.transformations.CoordinateTransformation.TransformationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Composite Transformation Tests")
class CompositeTransformationTest {
    
    private static final double EPSILON = 1e-10;
    
    @Test
    @DisplayName("Simple two-transformation composition")
    void testSimpleComposition() throws TransformationException {
        var scaling = ScalingTransformation.scale2D(2.0, 3.0);
        var translation = TranslationTransformation.translate2D(1.0, 2.0);
        var composite = new CompositeTransformation(scaling, translation);
        
        var point = new Coordinate(new double[]{1.0, 1.0});
        
        // Apply manually: scale first, then translate
        var scaled = scaling.transform(point);
        var expected = translation.transform(scaled);
        
        // Apply through composite
        var result = composite.transform(point);
        
        assertArrayEquals(expected.values(), result.values(), EPSILON);
        assertEquals(2, composite.getDimension());
    }
    
    @Test
    @DisplayName("Three-transformation composition")
    void testThreeTransformationComposition() throws TransformationException {
        var rotation = RotationTransformation.rotation2D(Math.PI / 4);
        var scaling = ScalingTransformation.uniform(2, 2.0);
        var translation = TranslationTransformation.translate2D(5.0, 10.0);
        var composite = new CompositeTransformation(rotation, scaling, translation);
        
        var point = new Coordinate(new double[]{1.0, 0.0});
        
        // Apply manually
        var rotated = rotation.transform(point);
        var scaled = scaling.transform(rotated);
        var expected = translation.transform(scaled);
        
        // Apply through composite
        var result = composite.transform(point);
        
        assertArrayEquals(expected.values(), result.values(), EPSILON);
        assertEquals(3, composite.getTransformationCount());
    }
    
    @Test
    @DisplayName("Builder pattern")
    void testBuilderPattern() throws TransformationException {
        var composite = CompositeTransformation.builder()
            .then(ScalingTransformation.scale2D(2.0, 2.0))
            .then(RotationTransformation.rotation2D(Math.PI / 2))
            .then(TranslationTransformation.translate2D(1.0, 1.0))
            .build();
        
        var point = new Coordinate(new double[]{1.0, 0.0});
        var result = composite.transform(point);
        
        // Expected: scale (1,0) -> (2,0), rotate 90° -> (0,2), translate -> (1,3)
        assertArrayEquals(new double[]{1.0, 3.0}, result.values(), EPSILON);
    }
    
    @Test
    @DisplayName("Static factory methods")
    void testStaticFactoryMethods() throws TransformationException {
        var transform1 = ScalingTransformation.uniform(2, 2.0);
        var transform2 = TranslationTransformation.translate2D(1.0, 1.0);
        var transform3 = RotationTransformation.rotation2D(Math.PI);
        
        var composite2 = CompositeTransformation.of(transform1, transform2);
        var composite3 = CompositeTransformation.of(transform1, transform2, transform3);
        
        assertEquals(2, composite2.getTransformationCount());
        assertEquals(3, composite3.getTransformationCount());
        
        var point = new Coordinate(new double[]{1.0, 1.0});
        assertDoesNotThrow(() -> composite2.transform(point));
        assertDoesNotThrow(() -> composite3.transform(point));
    }
    
    @Test
    @DisplayName("Composite inverse")
    void testCompositeInverse() throws TransformationException {
        var rotation = RotationTransformation.rotation2D(Math.PI / 3);
        var scaling = ScalingTransformation.scale2D(2.0, 3.0);
        var translation = TranslationTransformation.translate2D(5.0, 7.0);
        var composite = new CompositeTransformation(rotation, scaling, translation);
        
        var inverse = composite.inverse();
        
        var point = new Coordinate(new double[]{2.0, 3.0});
        var transformed = composite.transform(point);
        var restored = inverse.get().transform(transformed);
        
        assertArrayEquals(point.values(), restored.values(), EPSILON);
    }
    
    @Test
    @DisplayName("Composite composition with another composite")
    void testCompositeComposition() throws TransformationException {
        var composite1 = new CompositeTransformation(
            ScalingTransformation.scale2D(2.0, 2.0),
            RotationTransformation.rotation2D(Math.PI / 4)
        );
        
        var composite2 = new CompositeTransformation(
            TranslationTransformation.translate2D(1.0, 1.0),
            ScalingTransformation.scale2D(0.5, 0.5)
        );
        
        var combined = composite1.compose(composite2);
        
        // Should have 4 transformations total (flattened)
        assertTrue(combined instanceof CompositeTransformation);
        var compositeResult = (CompositeTransformation) combined;
        assertEquals(4, compositeResult.getTransformationCount());
    }
    
    @Test
    @DisplayName("Composite composition with single transformation")
    void testCompositeWithSingleComposition() throws TransformationException {
        var composite = new CompositeTransformation(
            ScalingTransformation.scale2D(2.0, 2.0),
            RotationTransformation.rotation2D(Math.PI / 4)
        );
        
        var translation = TranslationTransformation.translate2D(1.0, 1.0);
        var combined = composite.compose(translation);
        
        assertTrue(combined instanceof CompositeTransformation);
        var compositeResult = (CompositeTransformation) combined;
        assertEquals(3, compositeResult.getTransformationCount());
    }
    
    @Test
    @DisplayName("Linearity check")
    void testLinearityCheck() {
        var linearComposite = new CompositeTransformation(
            LinearTransformation.identity(2),
            RotationTransformation.rotation2D(Math.PI / 4),
            ScalingTransformation.scale2D(2.0, 3.0)
        );
        
        var nonLinearComposite = new CompositeTransformation(
            ScalingTransformation.scale2D(2.0, 2.0),
            TranslationTransformation.translate2D(1.0, 1.0)
        );
        
        assertTrue(linearComposite.isLinear());
        assertFalse(nonLinearComposite.isLinear());
    }
    
    @Test
    @DisplayName("Invertibility check")
    void testInvertibilityCheck() {
        var invertibleComposite = new CompositeTransformation(
            RotationTransformation.rotation2D(Math.PI / 4),
            ScalingTransformation.scale2D(2.0, 3.0),
            TranslationTransformation.translate2D(1.0, 1.0)
        );
        
        assertTrue(invertibleComposite.isInvertible());
    }
    
    @Test
    @DisplayName("Jacobian determinant calculation")
    void testJacobianDeterminant() throws TransformationException {
        var scaling = ScalingTransformation.scale2D(2.0, 3.0); // determinant = 6
        var rotation = RotationTransformation.rotation2D(Math.PI / 4); // determinant = 1
        var composite = new CompositeTransformation(scaling, rotation);
        
        var point = new Coordinate(new double[]{1.0, 1.0});
        var determinant = composite.computeJacobianDeterminant(point);
        
        // Should be product of individual determinants
        assertEquals(6.0, determinant, EPSILON);
    }
    
    @Test
    @DisplayName("Batch transformation")
    void testBatchTransformation() throws TransformationException {
        var composite = new CompositeTransformation(
            ScalingTransformation.scale2D(2.0, 2.0),
            TranslationTransformation.translate2D(1.0, 1.0)
        );
        
        var points = java.util.List.of(
            new Coordinate(new double[]{1.0, 1.0}),
            new Coordinate(new double[]{2.0, 2.0}),
            new Coordinate(new double[]{3.0, 3.0})
        );
        
        var results = composite.transformBatch(points);
        
        assertEquals(3, results.size());
        // First point: scale (1,1) -> (2,2), translate -> (3,3)
        assertArrayEquals(new double[]{3.0, 3.0}, results.get(0).values(), EPSILON);
        // Second point: scale (2,2) -> (4,4), translate -> (5,5)
        assertArrayEquals(new double[]{5.0, 5.0}, results.get(1).values(), EPSILON);
    }
    
    @Test
    @DisplayName("Getter methods")
    void testGetterMethods() {
        var transform1 = ScalingTransformation.scale2D(2.0, 2.0);
        var transform2 = RotationTransformation.rotation2D(Math.PI / 4);
        var transform3 = TranslationTransformation.translate2D(1.0, 1.0);
        var composite = new CompositeTransformation(transform1, transform2, transform3);
        
        assertEquals(3, composite.getTransformationCount());
        assertEquals(2, composite.getDimension());
        
        var transformations = composite.getTransformations();
        assertEquals(3, transformations.size());
        assertEquals(transform1, transformations.get(0));
        assertEquals(transform2, transformations.get(1));
        assertEquals(transform3, transformations.get(2));
        
        assertEquals(transform2, composite.getTransformation(1));
    }
    
    @Test
    @DisplayName("Error cases")
    void testErrorCases() {
        // Empty transformations list
        assertThrows(IllegalArgumentException.class, 
                    () -> new CompositeTransformation(java.util.List.of()));
        
        // Null transformations list
        assertThrows(IllegalArgumentException.class, 
                    () -> new CompositeTransformation((java.util.List<CoordinateTransformation>) null));
        
        // Null transformation in list
        var transform = ScalingTransformation.scale2D(2.0, 2.0);
        assertThrows(IllegalArgumentException.class, 
                    () -> new CompositeTransformation(transform, null));
        
        // Mismatched dimensions
        var transform2D = ScalingTransformation.scale2D(2.0, 2.0);
        var transform3D = ScalingTransformation.scale3D(2.0, 2.0, 2.0);
        assertThrows(IllegalArgumentException.class, 
                    () -> new CompositeTransformation(transform2D, transform3D));
        
        // Builder with no transformations
        var builder = CompositeTransformation.builder();
        assertThrows(IllegalStateException.class, builder::build);
        
        // Null transformation in builder
        assertThrows(IllegalArgumentException.class, 
                    () -> CompositeTransformation.builder().then(null));
    }
    
    @Test
    @DisplayName("Equals and hashCode")
    void testEqualsAndHashCode() {
        var transform1 = ScalingTransformation.scale2D(2.0, 2.0);
        var transform2 = RotationTransformation.rotation2D(Math.PI / 4);
        
        var composite1 = new CompositeTransformation(transform1, transform2);
        var composite2 = new CompositeTransformation(transform1, transform2);
        var composite3 = new CompositeTransformation(transform2, transform1); // Different order
        
        assertEquals(composite1, composite2);
        assertNotEquals(composite1, composite3);
        assertEquals(composite1.hashCode(), composite2.hashCode());
    }
}