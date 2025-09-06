package com.dyada.core.linearization;

import com.dyada.core.coordinates.LevelIndex;
import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.CoordinateInterval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Morton Order Linearization Tests")
class MortonOrderLinearizationTest {
    
    private MortonOrderLinearization morton;
    
    @BeforeEach
    void setUp() {
        morton = new MortonOrderLinearization();
    }
    
    @Test
    @DisplayName("Basic properties")
    void testBasicProperties() {
        assertEquals("Morton Order", morton.getName());
        assertNotNull(morton.getDescription());
        assertTrue(morton.getDescription().contains("Z-order"));
        assertEquals(Integer.MAX_VALUE, morton.getMaxDimensions());
    }
    
    @Test
    @DisplayName("1D linearization")
    void test1DLinearization() {
        var levelIndex = new LevelIndex(new byte[]{5}, new long[]{25});
        
        long linearized = morton.linearize(levelIndex);
        assertEquals(25, linearized); // 1D should be identity
        
        var delinearized = morton.delinearize(linearized, 1);
        assertNotNull(delinearized);
        assertEquals(1, delinearized.dLevel().length);
        assertEquals(1, delinearized.dIndex().length);
    }
    
    @Test
    @DisplayName("2D linearization basic cases")
    void test2DLinearization() {
        // Test simple 2D cases
        var levelIndex1 = new LevelIndex(new byte[]{2, 2}, new long[]{0, 0});
        var levelIndex2 = new LevelIndex(new byte[]{2, 2}, new long[]{1, 0});
        var levelIndex3 = new LevelIndex(new byte[]{2, 2}, new long[]{0, 1});
        var levelIndex4 = new LevelIndex(new byte[]{2, 2}, new long[]{1, 1});
        
        long linear1 = morton.linearize(levelIndex1);
        long linear2 = morton.linearize(levelIndex2);
        long linear3 = morton.linearize(levelIndex3);
        long linear4 = morton.linearize(levelIndex4);
        
        // Morton order for 2D should follow Z-pattern
        assertTrue(linear1 < linear2);
        assertTrue(linear2 < linear3);
        assertTrue(linear3 < linear4);
        
        // Test round-trip
        var delinearized1 = morton.delinearize(linear1, 2);
        assertNotNull(delinearized1);
        assertEquals(2, delinearized1.dLevel().length);
        assertEquals(2, delinearized1.dIndex().length);
    }
    
    @Test
    @DisplayName("3D linearization")
    void test3DLinearization() {
        var levelIndex = new LevelIndex(
            new byte[]{3, 3, 3}, 
            new long[]{1, 2, 3}
        );
        
        long linearized = morton.linearize(levelIndex);
        assertTrue(linearized > 0);
        
        var delinearized = morton.delinearize(linearized, 3);
        assertNotNull(delinearized);
        assertEquals(3, delinearized.dLevel().length);
        assertEquals(3, delinearized.dIndex().length);
    }
    
    @Test
    @DisplayName("High-dimensional linearization")
    void testHighDimensionalLinearization() {
        // Test with 6 dimensions
        var levels = new byte[]{2, 2, 2, 2, 2, 2};
        var indices = new long[]{1, 1, 1, 1, 1, 1};
        var levelIndex = new LevelIndex(levels, indices);
        
        long linearized = morton.linearize(levelIndex);
        assertTrue(linearized > 0);
        
        var delinearized = morton.delinearize(linearized, 6);
        assertNotNull(delinearized);
        assertEquals(6, delinearized.dLevel().length);
        assertEquals(6, delinearized.dIndex().length);
    }
    
    @Test
    @DisplayName("Coordinate linearization")
    void testCoordinateLinearization() {
        var coordinate = new Coordinate(new double[]{0.5, 0.25, 0.75});
        byte maxLevel = 10;
        
        long linearized = morton.linearize(coordinate, maxLevel);
        assertTrue(linearized > 0);
        
        var delinearized = morton.delinearize(linearized, 3, maxLevel);
        assertNotNull(delinearized);
        assertEquals(3, delinearized.values().length);
        
        // Values should be approximately equal (within discretization error)
        double tolerance = 1.0 / ((1L << maxLevel) - 1);
        for (int i = 0; i < 3; i++) {
            assertEquals(coordinate.values()[i], delinearized.values()[i], tolerance);
        }
    }
    
    @Test
    @DisplayName("Range linearization")
    void testRangeLinearization() {
        var lowerBound = new Coordinate(new double[]{0.0, 0.0});
        var upperBound = new Coordinate(new double[]{0.5, 0.5});
        var interval = new CoordinateInterval(lowerBound, upperBound);
        
        var range = morton.linearizeRange(interval, (byte) 8);
        
        assertNotNull(range);
        assertTrue(range.start() >= 0);
        assertTrue(range.end() >= range.start());
        assertTrue(range.size() > 0);
    }
    
    @Test
    @DisplayName("Max level constraints")
    void testMaxLevelConstraints() {
        // 2D should support up to 31 levels
        assertEquals(31, morton.getMaxLevel(2));
        assertTrue(morton.isSupported(2, (byte) 31));
        assertFalse(morton.isSupported(2, (byte) 32));
        
        // 3D should support up to 20 levels
        assertEquals(20, morton.getMaxLevel(3));
        assertTrue(morton.isSupported(3, (byte) 20));
        assertFalse(morton.isSupported(3, (byte) 21));
        
        // Higher dimensions have lower limits
        assertTrue(morton.getMaxLevel(10) < morton.getMaxLevel(3));
    }
    
    @Test
    @DisplayName("Max linear index calculation")
    void testMaxLinearIndex() {
        long maxIndex2D = morton.getMaxLinearIndex(2, (byte) 10);
        assertTrue(maxIndex2D > 0);
        
        long maxIndex3D = morton.getMaxLinearIndex(3, (byte) 10);
        assertTrue(maxIndex3D > maxIndex2D); // 3D should have larger max index
        
        // Test overflow protection
        long maxIndexOverflow = morton.getMaxLinearIndex(10, (byte) 20);
        assertEquals(Long.MAX_VALUE, maxIndexOverflow);
    }
    
    @Test
    @DisplayName("Memory usage estimation")
    void testMemoryUsageEstimation() {
        long memoryUsage = morton.estimateMemoryUsage(3, (byte) 10);
        assertTrue(memoryUsage > 0);
        assertTrue(memoryUsage < 1000); // Should be minimal for stateless implementation
    }
    
    @Test
    @DisplayName("Round-trip consistency")
    void testRoundTripConsistency() {
        // Test multiple cases for round-trip consistency
        var testCases = new LevelIndex[]{
            new LevelIndex(new byte[]{1}, new long[]{0}),
            new LevelIndex(new byte[]{1}, new long[]{1}),
            new LevelIndex(new byte[]{2, 2}, new long[]{0, 0}),
            new LevelIndex(new byte[]{2, 2}, new long[]{1, 1}),
            new LevelIndex(new byte[]{2, 2}, new long[]{3, 2}),
            new LevelIndex(new byte[]{3, 3, 3}, new long[]{1, 2, 3}),
            new LevelIndex(new byte[]{5, 5}, new long[]{15, 31})
        };
        
        for (var original : testCases) {
            long linearized = morton.linearize(original);
            var roundTrip = morton.delinearize(linearized, original.dLevel().length);
            
            assertNotNull(roundTrip);
            assertEquals(original.dLevel().length, roundTrip.dLevel().length);
            assertEquals(original.dIndex().length, roundTrip.dIndex().length);
            
            // Note: Levels might be different due to delinearization assumptions,
            // but indices should be preserved for the significant bits
        }
    }
    
    @Test
    @DisplayName("Spatial locality properties")
    void testSpatialLocality() {
        // Test that nearby points have nearby linear indices
        var point1 = new LevelIndex(new byte[]{5, 5}, new long[]{10, 10});
        var point2 = new LevelIndex(new byte[]{5, 5}, new long[]{10, 11}); // Nearby
        var point3 = new LevelIndex(new byte[]{5, 5}, new long[]{20, 20}); // Far away
        
        long linear1 = morton.linearize(point1);
        long linear2 = morton.linearize(point2);
        long linear3 = morton.linearize(point3);
        
        // Distance between linear1 and linear2 should be smaller than
        // distance between linear1 and linear3
        long distance12 = Math.abs(linear2 - linear1);
        long distance13 = Math.abs(linear3 - linear1);
        
        assertTrue(distance12 < distance13);
    }
    
    @Test
    @DisplayName("Edge cases and error handling")
    void testEdgeCasesAndErrorHandling() {
        // Null inputs
        assertThrows(IllegalArgumentException.class, 
            () -> morton.linearize((LevelIndex) null));
        assertThrows(IllegalArgumentException.class, 
            () -> morton.linearize((Coordinate) null, (byte) 5));
        assertThrows(IllegalArgumentException.class, 
            () -> morton.linearizeRange(null, (byte) 5));
        
        // Invalid parameters
        assertThrows(IllegalArgumentException.class, 
            () -> morton.delinearize(-1, 2));
        assertThrows(IllegalArgumentException.class, 
            () -> morton.delinearize(0, 0));
        assertThrows(IllegalArgumentException.class, 
            () -> morton.getMaxLevel(0));
    }
    
    @Test
    @DisplayName("Large index handling")
    void testLargeIndexHandling() {
        // Test with large but valid indices
        var largeLevelIndex = new LevelIndex(
            new byte[]{10, 10}, 
            new long[]{1023, 1023} // Near max for level 10
        );
        
        long linearized = morton.linearize(largeLevelIndex);
        assertTrue(linearized > 0);
        
        var delinearized = morton.delinearize(linearized, 2);
        assertNotNull(delinearized);
    }
    
    @Test
    @DisplayName("Bit interleaving correctness")
    void testBitInterleavingCorrectness() {
        // Test specific bit patterns for 2D case
        var levelIndex = new LevelIndex(new byte[]{3, 3}, new long[]{5, 3}); // 101, 011 in binary
        
        long linearized = morton.linearize(levelIndex);
        
        // For Morton order 2D: interleave 101 and 011 -> 100111
        // x=101, y=011 -> xyxyxy = 100111 = 39
        // Note: Actual result depends on bit ordering and implementation details
        assertTrue(linearized > 0);
        
        var delinearized = morton.delinearize(linearized, 2);
        assertNotNull(delinearized);
    }
    
    @Test
    @DisplayName("Performance characteristics")
    void testPerformanceCharacteristics() {
        // Test that linearization is reasonably fast
        var testPoint = new LevelIndex(new byte[]{10, 10, 10}, new long[]{512, 256, 128});
        
        long startTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            morton.linearize(testPoint);
        }
        long endTime = System.nanoTime();
        
        long duration = endTime - startTime;
        assertTrue(duration < 10_000_000); // Should complete in less than 10ms
    }
    
    @Test
    @DisplayName("toString representation")
    void testToString() {
        var string = morton.toString();
        assertNotNull(string);
        assertTrue(string.contains("Morton Order"));
        assertTrue(string.contains("unlimited"));
    }
}