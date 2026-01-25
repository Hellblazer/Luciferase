/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SplitPlaneStrategy interface and implementations.
 * <p>
 * Validates:
 * - LongestAxisStrategy picks correct axis based on bubble dimensions
 * - Axis-specific strategies (X, Y, Z) always return expected planes
 * - CyclicAxisStrategy cycles through axes in order
 * - Factory methods return correct strategy instances
 * - forAxis() factory maps SplitAxis to correct strategy
 * <p>
 * NOTE: Tests work directly with BubbleBounds to avoid RDGCS transformation artifacts.
 */
class SplitPlaneStrategyTest {

    /**
     * Test LongestAxisStrategy: X-dominant bounds.
     */
    @Test
    void testLongestAxisStrategy_XDominant() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 60.0f, 60.0f)  // X is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.longestAxis();
        var plane = strategy.calculate(bounds, List.of());

        assertNotNull(plane);
        assertEquals(SplitPlane.SplitAxis.X, plane.axis());
        // Normal should be X-axis aligned
        assertEquals(1.0f, Math.abs(plane.normal().x), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().y), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().z), 0.001f);
    }

    /**
     * Test LongestAxisStrategy: Y-dominant bounds.
     */
    @Test
    void testLongestAxisStrategy_YDominant() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(60.0f, 150.0f, 60.0f)  // Y is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.longestAxis();
        var plane = strategy.calculate(bounds, List.of());

        assertNotNull(plane);
        assertEquals(SplitPlane.SplitAxis.Y, plane.axis());
        // Normal should be Y-axis aligned
        assertEquals(0.0f, Math.abs(plane.normal().x), 0.001f);
        assertEquals(1.0f, Math.abs(plane.normal().y), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().z), 0.001f);
    }

    /**
     * Test LongestAxisStrategy: Z-dominant bounds.
     */
    @Test
    void testLongestAxisStrategy_ZDominant() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(60.0f, 60.0f, 150.0f)  // Z is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.longestAxis();
        var plane = strategy.calculate(bounds, List.of());

        assertNotNull(plane);
        assertEquals(SplitPlane.SplitAxis.Z, plane.axis());
        // Normal should be Z-axis aligned
        assertEquals(0.0f, Math.abs(plane.normal().x), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().y), 0.001f);
        assertEquals(1.0f, Math.abs(plane.normal().z), 0.001f);
    }

    /**
     * Test XAxisStrategy always returns X plane.
     */
    @Test
    void testXAxisStrategy_AlwaysReturnsX() {
        // Create Y-dominant bounds, but strategy should still return X
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(60.0f, 150.0f, 60.0f)  // Y is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.xAxis();
        var plane = strategy.calculate(bounds, List.of());

        assertNotNull(plane);
        assertEquals(SplitPlane.SplitAxis.X, plane.axis());
        assertEquals(1.0f, Math.abs(plane.normal().x), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().y), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().z), 0.001f);
    }

    /**
     * Test YAxisStrategy always returns Y plane.
     */
    @Test
    void testYAxisStrategy_AlwaysReturnsY() {
        // Create X-dominant bounds, but strategy should still return Y
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 60.0f, 60.0f)  // X is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.yAxis();
        var plane = strategy.calculate(bounds, List.of());

        assertNotNull(plane);
        assertEquals(SplitPlane.SplitAxis.Y, plane.axis());
        assertEquals(0.0f, Math.abs(plane.normal().x), 0.001f);
        assertEquals(1.0f, Math.abs(plane.normal().y), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().z), 0.001f);
    }

    /**
     * Test ZAxisStrategy always returns Z plane.
     */
    @Test
    void testZAxisStrategy_AlwaysReturnsZ() {
        // Create X-dominant bounds, but strategy should still return Z
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 60.0f, 60.0f)  // X is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.zAxis();
        var plane = strategy.calculate(bounds, List.of());

        assertNotNull(plane);
        assertEquals(SplitPlane.SplitAxis.Z, plane.axis());
        assertEquals(0.0f, Math.abs(plane.normal().x), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().y), 0.001f);
        assertEquals(1.0f, Math.abs(plane.normal().z), 0.001f);
    }

    /**
     * Test CyclicAxisStrategy cycles through X -> Y -> Z -> X.
     */
    @Test
    void testCyclicAxisStrategy_CyclesCorrectly() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(60.0f, 60.0f, 60.0f)
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.cyclic();

        // First call should return X
        var plane1 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.X, plane1.axis());

        // Second call should return Y
        var plane2 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.Y, plane2.axis());

        // Third call should return Z
        var plane3 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.Z, plane3.axis());

        // Fourth call should cycle back to X
        var plane4 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.X, plane4.axis());

        // Fifth call should be Y again
        var plane5 = strategy.calculate(bounds, List.of());
        assertEquals(SplitPlane.SplitAxis.Y, plane5.axis());
    }

    /**
     * Test forAxis() factory maps SplitAxis.X correctly.
     */
    @Test
    void testForAxis_MapsXCorrectly() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(60.0f, 150.0f, 60.0f)  // Y is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.forAxis(SplitPlane.SplitAxis.X);
        var plane = strategy.calculate(bounds, List.of());

        assertEquals(SplitPlane.SplitAxis.X, plane.axis());
    }

    /**
     * Test forAxis() factory maps SplitAxis.Y correctly.
     */
    @Test
    void testForAxis_MapsYCorrectly() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 60.0f, 60.0f)  // X is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.forAxis(SplitPlane.SplitAxis.Y);
        var plane = strategy.calculate(bounds, List.of());

        assertEquals(SplitPlane.SplitAxis.Y, plane.axis());
    }

    /**
     * Test forAxis() factory maps SplitAxis.Z correctly.
     */
    @Test
    void testForAxis_MapsZCorrectly() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 60.0f, 60.0f)  // X is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.forAxis(SplitPlane.SplitAxis.Z);
        var plane = strategy.calculate(bounds, List.of());

        assertEquals(SplitPlane.SplitAxis.Z, plane.axis());
    }

    /**
     * Test forAxis() factory maps SplitAxis.LONGEST correctly.
     */
    @Test
    void testForAxis_MapsLongestCorrectly() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(150.0f, 60.0f, 60.0f)  // X is longest
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);

        var strategy = SplitPlaneStrategies.forAxis(SplitPlane.SplitAxis.LONGEST);
        var plane = strategy.calculate(bounds, List.of());

        // Should delegate to LongestAxisStrategy, so X should be chosen
        assertEquals(SplitPlane.SplitAxis.X, plane.axis());
    }

    /**
     * Test factory methods return non-null strategies.
     */
    @Test
    void testFactoryMethods_ReturnNonNull() {
        assertNotNull(SplitPlaneStrategies.longestAxis());
        assertNotNull(SplitPlaneStrategies.xAxis());
        assertNotNull(SplitPlaneStrategies.yAxis());
        assertNotNull(SplitPlaneStrategies.zAxis());
        assertNotNull(SplitPlaneStrategies.cyclic());
    }

    /**
     * Test that strategies place plane through centroid.
     */
    @Test
    void testStrategies_PlacePlaneAtCentroid() {
        var positions = List.of(
            new Point3f(50.0f, 50.0f, 50.0f),
            new Point3f(100.0f, 60.0f, 60.0f)
        );
        var bounds = BubbleBounds.fromEntityPositions(positions);
        var centroid = bounds.centroid();

        // Test each strategy
        for (var strategy : List.of(
            SplitPlaneStrategies.longestAxis(),
            SplitPlaneStrategies.xAxis(),
            SplitPlaneStrategies.yAxis(),
            SplitPlaneStrategies.zAxis()
        )) {
            var plane = strategy.calculate(bounds, List.of());

            // Calculate distance from centroid to plane
            float distanceFromCentroid = Math.abs(
                plane.normal().x * (float)centroid.getX() +
                plane.normal().y * (float)centroid.getY() +
                plane.normal().z * (float)centroid.getZ() -
                plane.distance()
            );

            // Distance should be very small (plane passes through centroid)
            assertTrue(distanceFromCentroid < 5.0f,
                "Plane should pass through centroid, distance: " + distanceFromCentroid);
        }
    }
}
