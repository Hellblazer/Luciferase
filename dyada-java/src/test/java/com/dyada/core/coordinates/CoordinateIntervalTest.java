package com.dyada.core.coordinates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for CoordinateInterval functionality.
 */
@DisplayName("CoordinateInterval Tests")
class CoordinateIntervalTest {

    @Test
    @DisplayName("Create 2D coordinate interval")
    void create2DInterval() {
        var lower = Coordinate.of(1.0, 2.0);
        var upper = Coordinate.of(3.0, 4.0);
        var interval = new CoordinateInterval(lower, upper);
        
        assertEquals(lower, interval.lowerBound());
        assertEquals(upper, interval.upperBound());
        assertEquals(2, interval.dimensions());
    }

    @Test
    @DisplayName("Create interval from arrays")
    void createFromArrays() {
        var interval = CoordinateInterval.of(
            new double[]{0.0, 0.0, 0.0},
            new double[]{1.0, 2.0, 3.0}
        );
        
        assertEquals(3, interval.dimensions());
        assertEquals(Coordinate.of(0.0, 0.0, 0.0), interval.lowerBound());
        assertEquals(Coordinate.of(1.0, 2.0, 3.0), interval.upperBound());
    }

    @Test
    @DisplayName("Create unit cube")
    void createUnitCube() {
        var unitCube2D = CoordinateInterval.unitCube(2);
        var unitCube3D = CoordinateInterval.unitCube(3);
        
        assertEquals(Coordinate.origin(2), unitCube2D.lowerBound());
        assertEquals(Coordinate.uniform(2, 1.0), unitCube2D.upperBound());
        
        assertEquals(Coordinate.origin(3), unitCube3D.lowerBound());
        assertEquals(Coordinate.uniform(3, 1.0), unitCube3D.upperBound());
    }

    @Test
    @DisplayName("Create centered interval")
    void createCenteredInterval() {
        var center = Coordinate.of(5.0, 5.0);
        var interval = CoordinateInterval.centered(center, 1.0, 2.0);
        
        assertEquals(Coordinate.of(4.0, 3.0), interval.lowerBound());
        assertEquals(Coordinate.of(6.0, 7.0), interval.upperBound());
        assertEquals(center, interval.center());
    }

    @Test
    @DisplayName("Create centered uniform interval")
    void createCenteredUniformInterval() {
        var center = Coordinate.of(10.0, 20.0);
        var interval = CoordinateInterval.centeredUniform(center, 5.0);
        
        assertEquals(Coordinate.of(5.0, 15.0), interval.lowerBound());
        assertEquals(Coordinate.of(15.0, 25.0), interval.upperBound());
        assertEquals(center, interval.center());
    }

    @Test
    @DisplayName("Null bounds throw exception")
    void nullBoundsThrowException() {
        var coord = Coordinate.origin(2);
        
        assertThrows(NullPointerException.class, 
            () -> new CoordinateInterval(null, coord));
        assertThrows(NullPointerException.class, 
            () -> new CoordinateInterval(coord, null));
    }

    @Test
    @DisplayName("Mismatched dimensions throw exception")
    void mismatchedDimensionsThrowException() {
        var coord2D = Coordinate.origin(2);
        var coord3D = Coordinate.origin(3);
        
        assertThrows(IllegalArgumentException.class,
            () -> new CoordinateInterval(coord2D, coord3D));
    }

    @Test
    @DisplayName("Invalid bounds throw exception")
    void invalidBoundsThrowException() {
        var lower = Coordinate.of(3.0, 2.0);
        var upper = Coordinate.of(1.0, 4.0); // lower.x > upper.x
        
        var exception = assertThrows(IllegalArgumentException.class,
            () -> new CoordinateInterval(lower, upper));
        assertTrue(exception.getMessage().contains("Lower bound"));
    }

    @Test
    @DisplayName("Negative half-width throws exception")
    void negativeHalfWidthThrowsException() {
        var center = Coordinate.origin(2);
        
        assertThrows(IllegalArgumentException.class,
            () -> CoordinateInterval.centered(center, -1.0, 2.0));
        assertThrows(IllegalArgumentException.class,
            () -> CoordinateInterval.centeredUniform(center, -1.0));
    }

    @Test
    @DisplayName("Calculate center point")
    void calculateCenter() {
        var interval = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{4.0, 6.0}
        );
        
        assertEquals(Coordinate.of(2.0, 3.0), interval.center());
    }

    @Test
    @DisplayName("Calculate size")
    void calculateSize() {
        var interval = CoordinateInterval.of(
            new double[]{1.0, 2.0},
            new double[]{4.0, 8.0}
        );
        
        assertEquals(Coordinate.of(3.0, 6.0), interval.size());
        assertEquals(Coordinate.of(1.5, 3.0), interval.halfSize());
    }

    @Test
    @DisplayName("Calculate volume")
    void calculateVolume() {
        var interval2D = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{2.0, 3.0}
        );
        assertEquals(6.0, interval2D.volume(), 1e-10);
        
        var interval3D = CoordinateInterval.of(
            new double[]{0.0, 0.0, 0.0},
            new double[]{2.0, 3.0, 4.0}
        );
        assertEquals(24.0, interval3D.volume(), 1e-10);
    }

    @Test
    @DisplayName("Point containment - closed interval")
    void pointContainmentClosed() {
        var interval = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{2.0, 3.0}
        );
        
        // Inside
        assertTrue(interval.contains(Coordinate.of(1.0, 1.5)));
        
        // On boundaries (closed interval)
        assertTrue(interval.contains(Coordinate.of(0.0, 0.0)));
        assertTrue(interval.contains(Coordinate.of(2.0, 3.0)));
        assertTrue(interval.contains(Coordinate.of(1.0, 0.0)));
        assertTrue(interval.contains(Coordinate.of(2.0, 1.5)));
        
        // Outside
        assertFalse(interval.contains(Coordinate.of(-0.1, 1.0)));
        assertFalse(interval.contains(Coordinate.of(2.1, 1.0)));
        assertFalse(interval.contains(Coordinate.of(1.0, -0.1)));
        assertFalse(interval.contains(Coordinate.of(1.0, 3.1)));
    }

    @Test
    @DisplayName("Point containment - strict (open interval)")
    void pointContainmentStrict() {
        var interval = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{2.0, 3.0}
        );
        
        // Inside
        assertTrue(interval.strictlyContains(Coordinate.of(1.0, 1.5)));
        
        // On boundaries (open interval - excluded)
        assertFalse(interval.strictlyContains(Coordinate.of(0.0, 0.0)));
        assertFalse(interval.strictlyContains(Coordinate.of(2.0, 3.0)));
        assertFalse(interval.strictlyContains(Coordinate.of(1.0, 0.0)));
        assertFalse(interval.strictlyContains(Coordinate.of(2.0, 1.5)));
        
        // Outside
        assertFalse(interval.strictlyContains(Coordinate.of(-0.1, 1.0)));
        assertFalse(interval.strictlyContains(Coordinate.of(2.1, 1.0)));
    }

    @Test
    @DisplayName("Interval overlap detection")
    void intervalOverlap() {
        var interval1 = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{2.0, 2.0}
        );
        var interval2 = CoordinateInterval.of(
            new double[]{1.0, 1.0},
            new double[]{3.0, 3.0}
        );
        var interval3 = CoordinateInterval.of(
            new double[]{3.0, 3.0},
            new double[]{4.0, 4.0}
        );
        
        // Overlapping
        assertTrue(interval1.overlaps(interval2));
        assertTrue(interval2.overlaps(interval1));
        
        // Non-overlapping
        assertFalse(interval1.overlaps(interval3));
        assertFalse(interval3.overlaps(interval1));
        
        // Self-overlap
        assertTrue(interval1.overlaps(interval1));
    }

    @Test
    @DisplayName("Interval intersection")
    void intervalIntersection() {
        var interval1 = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{3.0, 3.0}
        );
        var interval2 = CoordinateInterval.of(
            new double[]{1.0, 1.0},
            new double[]{4.0, 4.0}
        );
        var interval3 = CoordinateInterval.of(
            new double[]{5.0, 5.0},
            new double[]{6.0, 6.0}
        );
        
        // Overlapping intervals
        var intersection = interval1.intersect(interval2);
        assertNotNull(intersection);
        assertEquals(CoordinateInterval.of(new double[]{1.0, 1.0}, new double[]{3.0, 3.0}), intersection);
        
        // Non-overlapping intervals
        assertNull(interval1.intersect(interval3));
        
        // Self-intersection
        assertEquals(interval1, interval1.intersect(interval1));
    }

    @Test
    @DisplayName("Interval union")
    void intervalUnion() {
        var interval1 = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{2.0, 2.0}
        );
        var interval2 = CoordinateInterval.of(
            new double[]{1.0, 1.0},
            new double[]{3.0, 3.0}
        );
        
        var union = interval1.union(interval2);
        var expected = CoordinateInterval.of(new double[]{0.0, 0.0}, new double[]{3.0, 3.0});
        assertEquals(expected, union);
        
        // Self-union
        assertEquals(interval1, interval1.union(interval1));
    }

    @Test
    @DisplayName("Expand interval")
    void expandInterval() {
        var interval = CoordinateInterval.of(
            new double[]{1.0, 1.0},
            new double[]{3.0, 3.0}
        );
        
        var expanded = interval.expand(0.5);
        var expected = CoordinateInterval.of(new double[]{0.5, 0.5}, new double[]{3.5, 3.5});
        assertEquals(expected, expanded);
        
        // Zero expansion
        assertEquals(interval, interval.expand(0.0));
        
        // Negative margin throws exception
        assertThrows(IllegalArgumentException.class, () -> interval.expand(-1.0));
    }

    @Test
    @DisplayName("Contract interval")
    void contractInterval() {
        var interval = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{4.0, 4.0}
        );
        
        var contracted = interval.contract(1.0);
        var expected = CoordinateInterval.of(new double[]{1.0, 1.0}, new double[]{3.0, 3.0});
        assertEquals(expected, contracted);
        
        // Zero contraction
        assertEquals(interval, interval.contract(0.0));
        
        // Over-contraction throws exception
        assertThrows(IllegalArgumentException.class, () -> interval.contract(3.0));
        
        // Negative margin throws exception
        assertThrows(IllegalArgumentException.class, () -> interval.contract(-1.0));
    }

    @Test
    @DisplayName("Scale interval")
    void scaleInterval() {
        var interval = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{4.0, 4.0}
        );
        
        var scaled = interval.scale(2.0);
        var expected = CoordinateInterval.of(new double[]{-2.0, -2.0}, new double[]{6.0, 6.0});
        assertEquals(expected, scaled);
        
        // Unit scaling
        assertEquals(interval, interval.scale(1.0));
        
        // Invalid scale factors
        assertThrows(IllegalArgumentException.class, () -> interval.scale(0.0));
        assertThrows(IllegalArgumentException.class, () -> interval.scale(-1.0));
    }

    @Test
    @DisplayName("Project interval")
    void projectInterval() {
        var interval3D = CoordinateInterval.of(
            new double[]{1.0, 2.0, 3.0},
            new double[]{4.0, 5.0, 6.0}
        );
        
        var projected = interval3D.project(0, 2);
        var expected = CoordinateInterval.of(new double[]{1.0, 3.0}, new double[]{4.0, 6.0});
        assertEquals(expected, projected);
    }

    @Test
    @DisplayName("Unit cube detection")
    void unitCubeDetection() {
        var unitCube = CoordinateInterval.unitCube(2);
        assertTrue(unitCube.isInUnitCube());
        
        var notUnitCube = CoordinateInterval.of(
            new double[]{-0.1, 0.0},
            new double[]{1.0, 1.0}
        );
        assertFalse(notUnitCube.isInUnitCube());
        
        var alsoNotUnitCube = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{1.1, 1.0}
        );
        assertFalse(alsoNotUnitCube.isInUnitCube());
    }

    @Test
    @DisplayName("Distance to point")
    void distanceToPoint() {
        var interval = CoordinateInterval.of(
            new double[]{0.0, 0.0},
            new double[]{2.0, 2.0}
        );
        
        // Point inside interval
        assertEquals(0.0, interval.distanceToPoint(Coordinate.of(1.0, 1.0)), 1e-10);
        
        // Point on boundary
        assertEquals(0.0, interval.distanceToPoint(Coordinate.of(0.0, 1.0)), 1e-10);
        assertEquals(0.0, interval.distanceToPoint(Coordinate.of(2.0, 1.0)), 1e-10);
        
        // Point outside interval
        assertEquals(1.0, interval.distanceToPoint(Coordinate.of(-1.0, 1.0)), 1e-10);
        assertEquals(1.0, interval.distanceToPoint(Coordinate.of(3.0, 1.0)), 1e-10);
        assertEquals(Math.sqrt(2.0), interval.distanceToPoint(Coordinate.of(-1.0, -1.0)), 1e-10);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("High-dimensional intervals")
    void highDimensionalIntervals(int dimensions) {
        var lower = new double[dimensions];
        var upper = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            lower[i] = i;
            upper[i] = i + 1;
        }
        
        var interval = CoordinateInterval.of(lower, upper);
        assertEquals(dimensions, interval.dimensions());
        assertEquals(1.0, interval.volume(), 1e-10); // Unit hypervolume
        
        var center = interval.center();
        for (int i = 0; i < dimensions; i++) {
            assertEquals(i + 0.5, center.get(i), 1e-10);
        }
    }

    @Test
    @DisplayName("Dimension mismatch in operations throws exception")
    void dimensionMismatchThrowsException() {
        var interval2D = CoordinateInterval.unitCube(2);
        var interval3D = CoordinateInterval.unitCube(3);
        var point3D = Coordinate.origin(3);
        
        assertThrows(IllegalArgumentException.class, 
            () -> interval2D.contains(point3D));
        assertThrows(IllegalArgumentException.class, 
            () -> interval2D.strictlyContains(point3D));
        assertThrows(IllegalArgumentException.class, 
            () -> interval2D.overlaps(interval3D));
        assertThrows(IllegalArgumentException.class, 
            () -> interval2D.intersect(interval3D));
        assertThrows(IllegalArgumentException.class, 
            () -> interval2D.union(interval3D));
        assertThrows(IllegalArgumentException.class, 
            () -> interval2D.distanceToPoint(point3D));
    }

    @Test
    @DisplayName("Equality and hashCode")
    void equalityAndHashCode() {
        var interval1 = CoordinateInterval.of(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
        var interval2 = CoordinateInterval.of(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
        var interval3 = CoordinateInterval.of(new double[]{0.0, 0.0}, new double[]{2.0, 2.0});
        
        assertEquals(interval1, interval2);
        assertNotEquals(interval1, interval3);
        
        assertEquals(interval1.hashCode(), interval2.hashCode());
        // hashCodes may or may not be different, but objects are not equal
        assertNotEquals(interval1, interval3);
    }

    @Test
    @DisplayName("toString format")
    void toStringFormat() {
        var interval = CoordinateInterval.of(new double[]{1.0, 2.0}, new double[]{3.0, 4.0});
        var str = interval.toString();
        
        assertTrue(str.contains("CoordinateInterval"));
        assertTrue(str.contains("1.0"));
        assertTrue(str.contains("2.0"));
        assertTrue(str.contains("3.0"));
        assertTrue(str.contains("4.0"));
    }
}