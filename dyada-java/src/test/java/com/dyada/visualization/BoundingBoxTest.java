package com.dyada.visualization;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.transformations.CoordinateTransformation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BoundingBox Tests")
class BoundingBoxTest {
    
    @Nested
    @DisplayName("Construction")
    class ConstructionTests {
        
        @Test
        @DisplayName("Create valid bounding box")
        void createValidBoundingBox() {
            var min = new Coordinate(new double[]{0.0, 0.0});
            var max = new Coordinate(new double[]{1.0, 1.0});
            var box = new BoundingBox(min, max);
            
            assertEquals(min, box.min());
            assertEquals(max, box.max());
        }
        
        @Test
        @DisplayName("Reject mismatched dimensions")
        void rejectMismatchedDimensions() {
            var min2D = new Coordinate(new double[]{0.0, 0.0});
            var max3D = new Coordinate(new double[]{1.0, 1.0, 1.0});
            
            assertThrows(IllegalArgumentException.class, 
                () -> new BoundingBox(min2D, max3D));
        }
        
        @Test
        @DisplayName("Reject invalid coordinate ordering")
        void rejectInvalidOrdering() {
            var min = new Coordinate(new double[]{2.0, 1.0});
            var max = new Coordinate(new double[]{1.0, 2.0});
            
            assertThrows(IllegalArgumentException.class, 
                () -> new BoundingBox(min, max));
        }
        
        @Test
        @DisplayName("Allow equal min and max coordinates")
        void allowEqualMinMax() {
            var coord = new Coordinate(new double[]{1.0, 1.0});
            assertDoesNotThrow(() -> new BoundingBox(coord, coord));
        }
    }
    
    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodsTests {
        
        @Test
        @DisplayName("Create from arrays")
        void createFromArrays() {
            var box = BoundingBox.of(new double[]{0.0, 1.0}, new double[]{2.0, 3.0});
            
            assertArrayEquals(new double[]{0.0, 1.0}, box.min().values());
            assertArrayEquals(new double[]{2.0, 3.0}, box.max().values());
        }
        
        @Test
        @DisplayName("Create from coordinates collection")
        void createFromCoordinates() {
            var coords = List.of(
                new Coordinate(new double[]{1.0, 3.0}),
                new Coordinate(new double[]{0.0, 2.0}),
                new Coordinate(new double[]{2.0, 1.0})
            );
            
            var box = BoundingBox.fromCoordinates(coords);
            
            assertArrayEquals(new double[]{0.0, 1.0}, box.min().values());
            assertArrayEquals(new double[]{2.0, 3.0}, box.max().values());
        }
        
        @Test
        @DisplayName("Reject empty coordinates collection")
        void rejectEmptyCoordinates() {
            assertThrows(IllegalArgumentException.class, 
                () -> BoundingBox.fromCoordinates(List.of()));
        }
        
        @Test
        @DisplayName("Reject mixed dimension coordinates")
        void rejectMixedDimensionCoordinates() {
            var coords = List.of(
                new Coordinate(new double[]{1.0, 2.0}),
                new Coordinate(new double[]{3.0, 4.0, 5.0})
            );
            
            assertThrows(IllegalArgumentException.class, 
                () -> BoundingBox.fromCoordinates(coords));
        }
        
        @Test
        @DisplayName("Create unit bounding box")
        void createUnitBoundingBox() {
            var box2D = BoundingBox.unit(2);
            var box3D = BoundingBox.unit(3);
            
            assertArrayEquals(new double[]{0.0, 0.0}, box2D.min().values());
            assertArrayEquals(new double[]{1.0, 1.0}, box2D.max().values());
            
            assertArrayEquals(new double[]{0.0, 0.0, 0.0}, box3D.min().values());
            assertArrayEquals(new double[]{1.0, 1.0, 1.0}, box3D.max().values());
        }
        
        @Test
        @DisplayName("Create centered bounding box")
        void createCenteredBoundingBox() {
            var center = new Coordinate(new double[]{5.0, 3.0});
            var extents = new double[]{2.0, 1.0};
            
            var box = BoundingBox.centered(center, extents);
            
            assertArrayEquals(new double[]{3.0, 2.0}, box.min().values());
            assertArrayEquals(new double[]{7.0, 4.0}, box.max().values());
        }
        
        @Test
        @DisplayName("Reject mismatched center and extents dimensions")
        void rejectMismatchedCenterExtents() {
            var center = new Coordinate(new double[]{1.0, 2.0});
            var extents = new double[]{1.0, 2.0, 3.0};
            
            assertThrows(IllegalArgumentException.class, 
                () -> BoundingBox.centered(center, extents));
        }
    }
    
    @Nested
    @DisplayName("Properties")
    class PropertiesTests {
        
        private final BoundingBox box2D = BoundingBox.of(new double[]{1.0, 2.0}, new double[]{4.0, 6.0});
        private final BoundingBox box3D = BoundingBox.of(new double[]{0.0, 1.0, 2.0}, new double[]{3.0, 4.0, 8.0});
        
        @Test
        @DisplayName("Get dimension")
        void getDimension() {
            assertEquals(2, box2D.getDimension());
            assertEquals(3, box3D.getDimension());
        }
        
        @Test
        @DisplayName("Get center")
        void getCenter() {
            var center2D = box2D.getCenter();
            var center3D = box3D.getCenter();
            
            assertArrayEquals(new double[]{2.5, 4.0}, center2D.values(), 1e-10);
            assertArrayEquals(new double[]{1.5, 2.5, 5.0}, center3D.values(), 1e-10);
        }
        
        @Test
        @DisplayName("Get extents")
        void getExtents() {
            var extents2D = box2D.getExtents();
            var extents3D = box3D.getExtents();
            
            assertArrayEquals(new double[]{1.5, 2.0}, extents2D, 1e-10);
            assertArrayEquals(new double[]{1.5, 1.5, 3.0}, extents3D, 1e-10);
        }
        
        @Test
        @DisplayName("Get size")
        void getSize() {
            var size2D = box2D.getSize();
            var size3D = box3D.getSize();
            
            assertArrayEquals(new double[]{3.0, 4.0}, size2D, 1e-10);
            assertArrayEquals(new double[]{3.0, 3.0, 6.0}, size3D, 1e-10);
        }
        
        @Test
        @DisplayName("Get volume")
        void getVolume() {
            assertEquals(12.0, box2D.getVolume(), 1e-10);
            assertEquals(54.0, box3D.getVolume(), 1e-10);
            
            // 1D case
            var box1D = BoundingBox.of(new double[]{2.0}, new double[]{7.0});
            assertEquals(5.0, box1D.getVolume(), 1e-10);
        }
        
        @Test
        @DisplayName("Get diagonal length")
        void getDiagonalLength() {
            // 2D: sqrt(3^2 + 4^2) = 5.0
            assertEquals(5.0, box2D.getDiagonalLength(), 1e-10);
            
            // 3D: sqrt(3^2 + 3^2 + 6^2) = sqrt(54) ≈ 7.35
            assertEquals(Math.sqrt(54), box3D.getDiagonalLength(), 1e-10);
        }
    }
    
    @Nested
    @DisplayName("Containment")
    class ContainmentTests {
        
        private final BoundingBox box = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{2.0, 2.0});
        
        @Test
        @DisplayName("Contains coordinate inside")
        void containsCoordinateInside() {
            assertTrue(box.contains(new Coordinate(new double[]{1.0, 1.0})));
            assertTrue(box.contains(new Coordinate(new double[]{0.0, 0.0}))); // boundary
            assertTrue(box.contains(new Coordinate(new double[]{2.0, 2.0}))); // boundary
        }
        
        @Test
        @DisplayName("Does not contain coordinate outside")
        void doesNotContainCoordinateOutside() {
            assertFalse(box.contains(new Coordinate(new double[]{-0.1, 1.0})));
            assertFalse(box.contains(new Coordinate(new double[]{1.0, 2.1})));
            assertFalse(box.contains(new Coordinate(new double[]{3.0, 3.0})));
        }
        
        @Test
        @DisplayName("Reject coordinate with wrong dimension")
        void rejectWrongDimensionCoordinate() {
            assertFalse(box.contains(new Coordinate(new double[]{1.0}))); // 1D
            assertFalse(box.contains(new Coordinate(new double[]{1.0, 1.0, 1.0}))); // 3D
        }
    }
    
    @Nested
    @DisplayName("Intersection")
    class IntersectionTests {
        
        private final BoundingBox box1 = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{2.0, 2.0});
        
        @Test
        @DisplayName("Intersects with overlapping box")
        void intersectsWithOverlapping() {
            var box2 = BoundingBox.of(new double[]{1.0, 1.0}, new double[]{3.0, 3.0});
            assertTrue(box1.intersects(box2));
            assertTrue(box2.intersects(box1)); // symmetric
        }
        
        @Test
        @DisplayName("Intersects at boundary")
        void intersectsAtBoundary() {
            var box2 = BoundingBox.of(new double[]{2.0, 0.0}, new double[]{3.0, 2.0});
            assertTrue(box1.intersects(box2));
        }
        
        @Test
        @DisplayName("Does not intersect with separate box")
        void doesNotIntersectSeparate() {
            var box2 = BoundingBox.of(new double[]{3.0, 3.0}, new double[]{4.0, 4.0});
            assertFalse(box1.intersects(box2));
            assertFalse(box2.intersects(box1)); // symmetric
        }
        
        @Test
        @DisplayName("Does not intersect different dimensions")
        void doesNotIntersectDifferentDimensions() {
            var box3D = BoundingBox.of(new double[]{0.0, 0.0, 0.0}, new double[]{1.0, 1.0, 1.0});
            assertFalse(box1.intersects(box3D));
        }
        
        @Test
        @DisplayName("Compute intersection")
        void computeIntersection() {
            var box2 = BoundingBox.of(new double[]{1.0, 1.0}, new double[]{3.0, 3.0});
            var intersection = box1.intersection(box2);
            
            assertNotNull(intersection);
            assertArrayEquals(new double[]{1.0, 1.0}, intersection.min().values());
            assertArrayEquals(new double[]{2.0, 2.0}, intersection.max().values());
        }
        
        @Test
        @DisplayName("Return null for non-intersecting boxes")
        void returnNullForNonIntersecting() {
            var box2 = BoundingBox.of(new double[]{3.0, 3.0}, new double[]{4.0, 4.0});
            assertNull(box1.intersection(box2));
        }
    }
    
    @Nested
    @DisplayName("Union")
    class UnionTests {
        
        @Test
        @DisplayName("Compute union of overlapping boxes")
        void computeUnionOverlapping() {
            var box1 = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{2.0, 2.0});
            var box2 = BoundingBox.of(new double[]{1.0, 1.0}, new double[]{3.0, 3.0});
            
            var union = box1.union(box2);
            
            assertArrayEquals(new double[]{0.0, 0.0}, union.min().values());
            assertArrayEquals(new double[]{3.0, 3.0}, union.max().values());
        }
        
        @Test
        @DisplayName("Compute union of separate boxes")
        void computeUnionSeparate() {
            var box1 = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
            var box2 = BoundingBox.of(new double[]{2.0, 2.0}, new double[]{3.0, 3.0});
            
            var union = box1.union(box2);
            
            assertArrayEquals(new double[]{0.0, 0.0}, union.min().values());
            assertArrayEquals(new double[]{3.0, 3.0}, union.max().values());
        }
        
        @Test
        @DisplayName("Reject union of different dimensions")
        void rejectUnionDifferentDimensions() {
            var box2D = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
            var box3D = BoundingBox.of(new double[]{0.0, 0.0, 0.0}, new double[]{1.0, 1.0, 1.0});
            
            assertThrows(IllegalArgumentException.class, 
                () -> box2D.union(box3D));
        }
    }
    
    @Nested
    @DisplayName("Expansion")
    class ExpansionTests {
        
        @Test
        @DisplayName("Expand by positive amount")
        void expandPositive() {
            var box = BoundingBox.of(new double[]{1.0, 2.0}, new double[]{3.0, 4.0});
            var expanded = box.expand(0.5);
            
            assertArrayEquals(new double[]{0.5, 1.5}, expanded.min().values());
            assertArrayEquals(new double[]{3.5, 4.5}, expanded.max().values());
        }
        
        @Test
        @DisplayName("Expand by zero")
        void expandZero() {
            var box = BoundingBox.of(new double[]{1.0, 2.0}, new double[]{3.0, 4.0});
            var expanded = box.expand(0.0);
            
            assertEquals(box, expanded);
        }
        
        @Test
        @DisplayName("Contract by negative amount")
        void contractNegative() {
            var box = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{2.0, 2.0});
            var contracted = box.expand(-0.25);
            
            assertArrayEquals(new double[]{0.25, 0.25}, contracted.min().values());
            assertArrayEquals(new double[]{1.75, 1.75}, contracted.max().values());
        }
    }
    
    @Nested
    @DisplayName("Corner Computation")
    class CornerComputationTests {
        
        @Test
        @DisplayName("Get corners of 2D box")
        void getCorners2D() {
            var box = BoundingBox.of(new double[]{1.0, 2.0}, new double[]{3.0, 4.0});
            var corners = box.getCorners();
            
            assertEquals(4, corners.length);
            
            // Check all corners are present (order may vary)
            var expectedCorners = Arrays.asList(
                new Coordinate(new double[]{1.0, 2.0}),
                new Coordinate(new double[]{3.0, 2.0}),
                new Coordinate(new double[]{1.0, 4.0}),
                new Coordinate(new double[]{3.0, 4.0})
            );
            
            for (var corner : corners) {
                assertTrue(expectedCorners.contains(corner), 
                    "Unexpected corner: " + Arrays.toString(corner.values()));
            }
        }
        
        @Test
        @DisplayName("Get corners of 3D box")
        void getCorners3D() {
            var box = BoundingBox.of(new double[]{0.0, 0.0, 0.0}, new double[]{1.0, 1.0, 1.0});
            var corners = box.getCorners();
            
            assertEquals(8, corners.length);
            
            // All corners should be within the bounding box
            for (var corner : corners) {
                assertTrue(box.contains(corner));
            }
        }
        
        @Test
        @DisplayName("Get corners of 1D box")
        void getCorners1D() {
            var box = BoundingBox.of(new double[]{2.0}, new double[]{5.0});
            var corners = box.getCorners();
            
            assertEquals(2, corners.length);
            
            var expectedCorners = Arrays.asList(
                new Coordinate(new double[]{2.0}),
                new Coordinate(new double[]{5.0})
            );
            
            for (var corner : corners) {
                assertTrue(expectedCorners.contains(corner));
            }
        }
    }
    
    @Nested
    @DisplayName("Transformation")
    class TransformationTests {
        
        @Test
        @DisplayName("Transform with identity transformation")
        void transformIdentity() {
            var box = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
            var transformation = new IdentityTransformation();
            
            var transformed = box.transform(transformation);
            
            assertEquals(box, transformed);
        }
        
        @Test
        @DisplayName("Transform with translation")
        void transformTranslation() {
            var box = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
            var transformation = new TranslationTransformation(2.0, 3.0);
            
            var transformed = box.transform(transformation);
            
            assertArrayEquals(new double[]{2.0, 3.0}, transformed.min().values(), 1e-10);
            assertArrayEquals(new double[]{3.0, 4.0}, transformed.max().values(), 1e-10);
        }
        
        @Test
        @DisplayName("Handle transformation exception")
        void handleTransformationException() {
            var box = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
            var transformation = new FailingTransformation();
            
            assertThrows(RuntimeException.class, 
                () -> box.transform(transformation));
        }
    }
    
    @Nested
    @DisplayName("Utility Methods")
    class UtilityMethodsTests {
        
        @Test
        @DisplayName("Detect degenerate box")
        void detectDegenerateBox() {
            // Zero volume
            var degenerateBox = BoundingBox.of(new double[]{1.0, 1.0}, new double[]{1.0, 1.0});
            assertTrue(degenerateBox.isDegenerate());
            
            // Very small volume
            var tinyBox = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{1e-12, 1e-12});
            assertTrue(tinyBox.isDegenerate());
            
            // Normal box
            var normalBox = BoundingBox.of(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
            assertFalse(normalBox.isDegenerate());
        }
        
        @Test
        @DisplayName("String representation")
        void stringRepresentation() {
            var box = BoundingBox.of(new double[]{1.0, 2.0}, new double[]{3.0, 4.0});
            var str = box.toString();
            
            assertTrue(str.contains("BoundingBox"));
            assertTrue(str.contains("min="));
            assertTrue(str.contains("max="));
            assertTrue(str.contains("center="));
            assertTrue(str.contains("volume="));
        }
    }
    
    // Helper transformation classes for testing
    private static class IdentityTransformation implements CoordinateTransformation {
        @Override
        public Coordinate transform(Coordinate input) {
            return input;
        }
        
        @Override
        public Coordinate inverseTransform(Coordinate transformed) {
            return transformed;
        }
        
        @Override
        public boolean isInvertible() {
            return true;
        }
        
        @Override
        public com.dyada.core.coordinates.Bounds transformBounds(com.dyada.core.coordinates.Bounds bounds) throws TransformationException {
            return bounds;
        }
        
        @Override
        public java.util.Optional<CoordinateTransformation> inverse() {
            return java.util.Optional.of(this);
        }
        
        @Override
        public CoordinateTransformation compose(CoordinateTransformation other) {
            return other;
        }
        
        @Override
        public double determinant() {
            return 1.0;
        }
        
        @Override
        public boolean isIsometric() {
            return true;
        }
        
        @Override
        public boolean isConformal() {
            return true;
        }
        
        @Override
        public int getSourceDimension() {
            return 2;
        }
        
        @Override
        public int getTargetDimension() {
            return 2;
        }
        
        @Override
        public String getDescription() {
            return "Identity Transformation";
        }
    }
    
    private static class TranslationTransformation implements CoordinateTransformation {
        private final double dx, dy;
        
        TranslationTransformation(double dx, double dy) {
            this.dx = dx;
            this.dy = dy;
        }
        
        @Override
        public Coordinate transform(Coordinate input) {
            var values = input.values();
            return new Coordinate(new double[]{values[0] + dx, values[1] + dy});
        }
        
        @Override
        public Coordinate inverseTransform(Coordinate transformed) {
            var values = transformed.values();
            return new Coordinate(new double[]{values[0] - dx, values[1] - dy});
        }
        
        @Override
        public boolean isInvertible() {
            return true;
        }
        
        @Override
        public com.dyada.core.coordinates.Bounds transformBounds(com.dyada.core.coordinates.Bounds bounds) throws TransformationException {
            throw new TransformationException("Not implemented");
        }
        
        @Override
        public java.util.Optional<CoordinateTransformation> inverse() {
            return java.util.Optional.of(new TranslationTransformation(-dx, -dy));
        }
        
        @Override
        public CoordinateTransformation compose(CoordinateTransformation other) {
            throw new UnsupportedOperationException();
        }
        
        @Override
        public double determinant() {
            return 1.0;
        }
        
        @Override
        public boolean isIsometric() {
            return true;
        }
        
        @Override
        public boolean isConformal() {
            return true;
        }
        
        @Override
        public int getSourceDimension() {
            return 2;
        }
        
        @Override
        public int getTargetDimension() {
            return 2;
        }
        
        @Override
        public String getDescription() {
            return String.format("Translation(%.1f, %.1f)", dx, dy);
        }
    }
    
    private static class FailingTransformation implements CoordinateTransformation {
        @Override
        public Coordinate transform(Coordinate input) throws TransformationException {
            throw new TransformationException("Transformation failed");
        }
        
        @Override
        public Coordinate inverseTransform(Coordinate transformed) throws TransformationException {
            throw new TransformationException("Inverse transformation failed");
        }
        
        @Override
        public boolean isInvertible() {
            return false;
        }
        
        @Override
        public com.dyada.core.coordinates.Bounds transformBounds(com.dyada.core.coordinates.Bounds bounds) throws TransformationException {
            throw new TransformationException("Bounds transformation failed");
        }
        
        @Override
        public java.util.Optional<CoordinateTransformation> inverse() {
            return java.util.Optional.empty();
        }
        
        @Override
        public CoordinateTransformation compose(CoordinateTransformation other) {
            throw new UnsupportedOperationException();
        }
        
        @Override
        public double determinant() {
            return 0.0;
        }
        
        @Override
        public boolean isIsometric() {
            return false;
        }
        
        @Override
        public boolean isConformal() {
            return false;
        }
        
        @Override
        public int getSourceDimension() {
            return 2;
        }
        
        @Override
        public int getTargetDimension() {
            return 2;
        }
        
        @Override
        public String getDescription() {
            return "Failing Transformation";
        }
    }
}