package com.dyada.discretization;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.LevelIndex;
import com.dyada.core.coordinates.Bounds;
import com.dyada.core.coordinates.CoordinateInterval;
import com.dyada.core.coordinates.SpatialQueryException;
import com.dyada.core.descriptors.Grid;
import com.dyada.core.descriptors.RefinementDescriptor;
import com.dyada.core.bitarray.BitArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.jqwik.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialDiscretizerTest {
    
    private SpatialDiscretizer discretizer;
    private Bounds bounds;
    private Grid grid;
    
    @BeforeEach
    void setUp() {
        bounds = new Bounds(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
        
        // Create RefinementDescriptor for 2D with some basic refinement
        var refinement = RefinementDescriptor.create(2);
        
        // Create CoordinateInterval from bounds
        var lowerBound = new Coordinate(new double[]{0.0, 0.0});
        var upperBound = new Coordinate(new double[]{1.0, 1.0});
        var coordinateInterval = new CoordinateInterval(lowerBound, upperBound);
        
        discretizer = new SpatialDiscretizer(refinement, coordinateInterval);
    }
    
    @Nested
    class BasicDiscretization {
        
        @Test
        void discretizeOrigin() {
            var point = new Coordinate(new double[]{0.0, 0.0});
            var result = discretizer.discretize(point);
            
            assertNotNull(result);
            assertEquals(2, result.dimensions());
        }
        
        @Test
        void discretizeCenter() {
            var point = new Coordinate(new double[]{0.5, 0.5});
            var result = discretizer.discretize(point);
            
            assertNotNull(result);
            assertEquals(2, result.dimensions());
        }
        
        @Test
        void discretizeBoundary() {
            var point = new Coordinate(new double[]{1.0, 1.0});
            var result = discretizer.discretize(point);
            
            assertNotNull(result);
            assertEquals(2, result.dimensions());
        }
    }
    
    @Nested
    class BoundsValidation {
        
        @Test
        void pointOutsideBoundsThrows() {
            var point = new Coordinate(new double[]{-0.1, 0.5});
            
            assertThrows(SpatialQueryException.class, () -> {
                discretizer.discretize(point);
            });
        }
        
        @Test
        void pointAboveBoundsThrows() {
            var point = new Coordinate(new double[]{0.5, 1.1});
            
            assertThrows(SpatialQueryException.class, () -> {
                discretizer.discretize(point);
            });
        }
        
        @Test
        void nullPointThrows() {
            assertThrows(NullPointerException.class, () -> {
                discretizer.discretize(null);
            });
        }
        
        @Test
        void wrongDimensionPointThrows() {
            var point = new Coordinate(new double[]{0.5}); // 1D instead of 2D
            
            assertThrows(IllegalArgumentException.class, () -> {
                discretizer.discretize(point);
            });
        }
    }
    
    @Nested
    class AdaptiveRefinement {
        
        @Test
        void refinementCreatesConsistentResults() {
            var point = new Coordinate(new double[]{0.5, 0.5});
            var baseResult = discretizer.discretize(point);
            
            // Test that refinement maintains consistency
            var adaptiveRefinement = RefinementDescriptor.create(2);
            var adaptiveLower = new Coordinate(new double[]{0.4, 0.4});
            var adaptiveUpper = new Coordinate(new double[]{0.6, 0.6});
            var adaptiveInterval = new CoordinateInterval(adaptiveLower, adaptiveUpper);
            var adaptiveDiscretizer = new SpatialDiscretizer(adaptiveRefinement, adaptiveInterval);
            
            var refinedResult = adaptiveDiscretizer.discretize(point);
            
            assertNotNull(refinedResult);
            assertTrue(refinedResult.getLevel(0) >= baseResult.getLevel(0));
        }
    }
    
    @Nested
    class EdgeCases {
        
        @Test
        void singleCellGrid() {
            var singleRefinement = RefinementDescriptor.create(2);
            var singleLower = new Coordinate(new double[]{0.0, 0.0});
            var singleUpper = new Coordinate(new double[]{1.0, 1.0});
            var singleInterval = new CoordinateInterval(singleLower, singleUpper);
            var singleDiscretizer = new SpatialDiscretizer(singleRefinement, singleInterval);
            
            var point = new Coordinate(new double[]{0.5, 0.5});
            var result = singleDiscretizer.discretize(point);
            
            assertNotNull(result);
            assertEquals(0, result.getIndices()[0]);
            assertEquals(0, result.getIndices()[1]);
        }
        
        @Test
        void veryFineBounds() {
            var fineRefinement = RefinementDescriptor.create(2);
            var fineLower = new Coordinate(new double[]{0.0, 0.0});
            var fineUpper = new Coordinate(new double[]{0.001, 0.001});
            var fineInterval = new CoordinateInterval(fineLower, fineUpper);
            var fineDiscretizer = new SpatialDiscretizer(fineRefinement, fineInterval);
            
            var point = new Coordinate(new double[]{0.0005, 0.0005});
            var result = fineDiscretizer.discretize(point);
            
            assertNotNull(result);
        }
    }
    
    @ParameterizedTest
    @ValueSource(ints = {2, 4, 8, 16, 32})
    void differentGridSizes(int gridSize) {
        var testRefinement = RefinementDescriptor.create(2);
        var testLower = new Coordinate(new double[]{0.0, 0.0});
        var testUpper = new Coordinate(new double[]{1.0, 1.0});
        var testInterval = new CoordinateInterval(testLower, testUpper);
        var testDiscretizer = new SpatialDiscretizer(testRefinement, testInterval);
        
        var point = new Coordinate(new double[]{0.5, 0.5});
        var result = testDiscretizer.discretize(point);
        
        assertNotNull(result);
        assertEquals(2, result.dimensions());
    }
    
    @Property
    void allValidPointsProduceResults(@ForAll("validCoordinates") Coordinate point) {
        // Initialize discretizer for property-based test
        var bounds = new Bounds(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
        var refinement = RefinementDescriptor.create(2);
        var lowerBound = new Coordinate(new double[]{0.0, 0.0});
        var upperBound = new Coordinate(new double[]{1.0, 1.0});
        var coordinateInterval = new CoordinateInterval(lowerBound, upperBound);
        var testDiscretizer = new SpatialDiscretizer(refinement, coordinateInterval);
        
        var result = testDiscretizer.discretize(point);
        assertNotNull(result);
        assertEquals(2, result.dimensions());
        assertTrue(result.getIndices()[0] >= 0);
        assertTrue(result.getIndices()[1] >= 0);
    }
    
    @Property
    void consistentDiscretization(@ForAll("validCoordinates") Coordinate point) {
        // Initialize discretizer for property-based test
        var bounds = new Bounds(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
        var refinement = RefinementDescriptor.create(2);
        var lowerBound = new Coordinate(new double[]{0.0, 0.0});
        var upperBound = new Coordinate(new double[]{1.0, 1.0});
        var coordinateInterval = new CoordinateInterval(lowerBound, upperBound);
        var testDiscretizer = new SpatialDiscretizer(refinement, coordinateInterval);
        
        var result1 = testDiscretizer.discretize(point);
        var result2 = testDiscretizer.discretize(point);
        
        assertEquals(result1.dimensions(), result2.dimensions());
        assertArrayEquals(result1.getIndices(), result2.getIndices());
        assertArrayEquals(result1.getLevels(), result2.getLevels());
    }
    
    @Provide
    Arbitrary<Coordinate> validCoordinates() {
        return Arbitraries.doubles().between(0.0, 1.0).list().ofSize(2)
                .map(list -> new Coordinate(list.stream().mapToDouble(d -> d).toArray()));
    }
}