package com.dyada.core.coordinates;

import com.dyada.TestBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Coordinate Tests")
class CoordinateTest extends TestBase {
    
    @Test
    @DisplayName("Create 2D coordinate")
    void testCreate2D() {
        var coord = coordinate2D(3.5, 7.2);
        
        assertEquals(2, coord.dimensions());
        assertArrayEquals(new double[]{3.5, 7.2}, coord.values(), 1e-10);
        assertEquals(3.5, coord.get(0), 1e-10);
        assertEquals(7.2, coord.get(1), 1e-10);
    }
    
    @Test
    @DisplayName("Create 3D coordinate")
    void testCreate3D() {
        var coord = coordinate3D(1.0, 2.5, -3.7);
        
        assertEquals(3, coord.dimensions());
        assertArrayEquals(new double[]{1.0, 2.5, -3.7}, coord.values(), 1e-10);
        assertEquals(1.0, coord.get(0), 1e-10);
        assertEquals(2.5, coord.get(1), 1e-10);
        assertEquals(-3.7, coord.get(2), 1e-10);
    }
    
    @Test
    @DisplayName("Create from array")
    void testCreateFromArray() {
        var values = new double[]{1.1, 2.2, 3.3, 4.4};
        var coord = new Coordinate(values);
        
        assertEquals(4, coord.dimensions());
        assertArrayEquals(values, coord.values(), 1e-10);
        
        // Verify defensive copy
        values[0] = 999.0;
        assertEquals(1.1, coord.get(0), 1e-10);
    }
    
    @Test
    @DisplayName("Coordinate addition")
    void testAdd() {
        var coord1 = coordinate2D(1.0, 2.0);
        var coord2 = coordinate2D(3.0, 4.0);
        
        var result = coord1.add(coord2);
        
        assertArrayEquals(new double[]{4.0, 6.0}, result.values(), 1e-10);
        
        // Original coordinates unchanged
        assertArrayEquals(new double[]{1.0, 2.0}, coord1.values(), 1e-10);
        assertArrayEquals(new double[]{3.0, 4.0}, coord2.values(), 1e-10);
    }
    
    @Test
    @DisplayName("Coordinate subtraction")
    void testSubtract() {
        var coord1 = coordinate2D(5.0, 8.0);
        var coord2 = coordinate2D(2.0, 3.0);
        
        var result = coord1.subtract(coord2);
        
        assertArrayEquals(new double[]{3.0, 5.0}, result.values(), 1e-10);
    }
    
    @Test
    @DisplayName("Scalar multiplication")
    void testMultiply() {
        var coord = coordinate3D(2.0, -3.0, 4.0);
        
        var result = coord.multiply(2.5);
        
        assertArrayEquals(new double[]{5.0, -7.5, 10.0}, result.values(), 1e-10);
    }
    
    @Test
    @DisplayName("Distance calculation")
    void testDistance() {
        var coord1 = coordinate2D(0.0, 0.0);
        var coord2 = coordinate2D(3.0, 4.0);
        
        double distance = coord1.distance(coord2);
        
        assertEquals(5.0, distance, 1e-10); // 3-4-5 triangle
    }
    
    @Test
    @DisplayName("Magnitude calculation")
    void testMagnitude() {
        var coord = coordinate2D(3.0, 4.0);
        
        assertEquals(5.0, coord.magnitude(), 1e-10);
    }
    
    @Test
    @DisplayName("Normalization")
    void testNormalize() {
        var coord = coordinate2D(3.0, 4.0);
        var normalized = coord.normalize();
        
        assertEquals(1.0, normalized.magnitude(), 1e-10);
        assertArrayEquals(new double[]{0.6, 0.8}, normalized.values(), 1e-10);
    }
    
    @Test
    @DisplayName("Zero vector normalization")
    void testNormalizeZeroVector() {
        var zero = coordinate2D(0.0, 0.0);
        
        assertThrows(ArithmeticException.class, zero::normalize);
    }
    
    @Test
    @DisplayName("Dot product")
    void testDotProduct() {
        var coord1 = coordinate3D(1.0, 2.0, 3.0);
        var coord2 = coordinate3D(4.0, 5.0, 6.0);
        
        double dotProduct = coord1.dotProduct(coord2);
        
        assertEquals(32.0, dotProduct, 1e-10); // 1*4 + 2*5 + 3*6 = 32
    }
    
    @Test
    @DisplayName("Cross product 3D")
    void testCrossProduct3D() {
        var coord1 = coordinate3D(1.0, 0.0, 0.0);
        var coord2 = coordinate3D(0.0, 1.0, 0.0);
        
        var crossProduct = coord1.crossProduct(coord2);
        
        assertArrayEquals(new double[]{0.0, 0.0, 1.0}, crossProduct.values(), 1e-10);
    }
    
    @Test
    @DisplayName("Cross product non-3D throws exception")
    void testCrossProductNon3D() {
        var coord2D = coordinate2D(1.0, 2.0);
        var other2D = coordinate2D(3.0, 4.0);
        
        assertThrows(IllegalArgumentException.class, () -> coord2D.crossProduct(other2D));
    }
    
    @Test
    @DisplayName("Linear interpolation")
    void testLerp() {
        var start = coordinate2D(0.0, 0.0);
        var end = coordinate2D(10.0, 20.0);
        
        var midpoint = start.lerp(end, 0.5);
        var quarter = start.lerp(end, 0.25);
        
        assertArrayEquals(new double[]{5.0, 10.0}, midpoint.values(), 1e-10);
        assertArrayEquals(new double[]{2.5, 5.0}, quarter.values(), 1e-10);
    }
    
    @Test
    @DisplayName("Linear interpolation at extremes")
    void testLerpExtremes() {
        var start = coordinate2D(1.0, 2.0);
        var end = coordinate2D(3.0, 4.0);
        
        var atStart = start.lerp(end, 0.0);
        var atEnd = start.lerp(end, 1.0);
        
        assertCoordinateEquals(start, atStart, 1e-10);
        assertCoordinateEquals(end, atEnd, 1e-10);
    }
    
    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, 2.0, -1.0})
    @DisplayName("Linear interpolation outside [0,1] range")
    void testLerpOutsideRange(double t) {
        var start = coordinate2D(0.0, 0.0);
        var end = coordinate2D(10.0, 10.0);
        
        // Should work but extrapolate beyond the range
        var result = start.lerp(end, t);
        
        assertArrayEquals(new double[]{10.0 * t, 10.0 * t}, result.values(), 1e-10);
    }
    
    @Test
    @DisplayName("Operations on different dimensions throw exception")
    void testDifferentDimensionOperations() {
        var coord2D = coordinate2D(1.0, 2.0);
        var coord3D = coordinate3D(1.0, 2.0, 3.0);
        
        assertThrows(IllegalArgumentException.class, () -> coord2D.add(coord3D));
        assertThrows(IllegalArgumentException.class, () -> coord2D.subtract(coord3D));
        assertThrows(IllegalArgumentException.class, () -> coord2D.distance(coord3D));
        assertThrows(IllegalArgumentException.class, () -> coord2D.dotProduct(coord3D));
        assertThrows(IllegalArgumentException.class, () -> coord2D.lerp(coord3D, 0.5));
    }
    
    @Test
    @DisplayName("Invalid index access throws exception")
    void testInvalidIndexAccess() {
        var coord = coordinate2D(1.0, 2.0);
        
        assertThrows(IllegalArgumentException.class, () -> coord.get(-1));
        assertThrows(IllegalArgumentException.class, () -> coord.get(2));
    }
    
    @Test
    @DisplayName("Null array throws exception")
    void testNullArray() {
        assertThrows(IllegalArgumentException.class, () -> new Coordinate(null));
    }
    
    @Test
    @DisplayName("Empty array throws exception")
    void testEmptyArray() {
        assertThrows(IllegalArgumentException.class, () -> new Coordinate(new double[0]));
    }
    
    @Test
    @DisplayName("High-dimension coordinates")
    void testHighDimensionCoordinates() {
        var values = randomDoubleArray(10, -100.0, 100.0);
        var coord = new Coordinate(values);
        
        assertEquals(10, coord.dimensions());
        
        for (int i = 0; i < 10; i++) {
            assertEquals(values[i], coord.get(i), 1e-10);
        }
    }
    
    @Test
    @DisplayName("Equality and hashCode")
    void testEqualityAndHashCode() {
        var coord1 = coordinate2D(1.0, 2.0);
        var coord2 = coordinate2D(1.0, 2.0);
        var coord3 = coordinate2D(1.0, 2.1);
        
        assertEquals(coord1, coord2);
        assertEquals(coord1.hashCode(), coord2.hashCode());
        assertNotEquals(coord1, coord3);
        assertNotEquals(coord1.hashCode(), coord3.hashCode());
    }
    
    @Test
    @DisplayName("Immutability")
    void testImmutability() {
        var original = coordinate2D(1.0, 2.0);
        var modified = original.add(coordinate2D(1.0, 1.0));
        
        // Original should be unchanged
        assertArrayEquals(new double[]{1.0, 2.0}, original.values(), 1e-10);
        assertArrayEquals(new double[]{2.0, 3.0}, modified.values(), 1e-10);
    }
    
    @Test
    @DisplayName("Special values handling")
    void testSpecialValues() {
        var coord = new Coordinate(new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN});
        
        assertEquals(3, coord.dimensions());
        assertEquals(Double.POSITIVE_INFINITY, coord.get(0));
        assertEquals(Double.NEGATIVE_INFINITY, coord.get(1));
        assertTrue(Double.isNaN(coord.get(2)));
        
        // Magnitude should be infinity
        assertEquals(Double.POSITIVE_INFINITY, coord.magnitude());
    }
}