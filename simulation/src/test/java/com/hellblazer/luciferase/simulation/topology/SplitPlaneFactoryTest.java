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

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SplitPlane factory methods and axis support.
 * <p>
 * Validates:
 * - Backward compatibility with 2-arg constructor
 * - Factory methods for X, Y, Z axes
 * - Axis inference from normal vectors
 * - Longest axis selection from bounds
 */
class SplitPlaneFactoryTest {

    /**
     * Test backward compatibility: existing 2-arg constructor still works.
     * CRITICAL: All 25 existing call sites depend on this.
     */
    @Test
    void testBackwardCompatibility_TwoArgConstructor() {
        var normal = new Point3f(1.0f, 0.0f, 0.0f);
        var plane = new SplitPlane(normal, 5.0f);

        assertNotNull(plane);
        assertEquals(normal, plane.normal());
        assertEquals(5.0f, plane.distance());
        // Axis should be inferred as X
        assertEquals(SplitPlane.SplitAxis.X, plane.axis());
    }

    /**
     * Test factory method for X-axis aligned plane.
     */
    @Test
    void testXAxisFactory() {
        var plane = SplitPlane.xAxis(10.0f);

        assertNotNull(plane);
        assertEquals(1.0f, plane.normal().x, 0.001f);
        assertEquals(0.0f, plane.normal().y, 0.001f);
        assertEquals(0.0f, plane.normal().z, 0.001f);
        assertEquals(10.0f, plane.distance());
        assertEquals(SplitPlane.SplitAxis.X, plane.axis());
    }

    /**
     * Test factory method for Y-axis aligned plane.
     */
    @Test
    void testYAxisFactory() {
        var plane = SplitPlane.yAxis(15.0f);

        assertNotNull(plane);
        assertEquals(0.0f, plane.normal().x, 0.001f);
        assertEquals(1.0f, plane.normal().y, 0.001f);
        assertEquals(0.0f, plane.normal().z, 0.001f);
        assertEquals(15.0f, plane.distance());
        assertEquals(SplitPlane.SplitAxis.Y, plane.axis());
    }

    /**
     * Test factory method for Z-axis aligned plane.
     */
    @Test
    void testZAxisFactory() {
        var plane = SplitPlane.zAxis(20.0f);

        assertNotNull(plane);
        assertEquals(0.0f, plane.normal().x, 0.001f);
        assertEquals(0.0f, plane.normal().y, 0.001f);
        assertEquals(1.0f, plane.normal().z, 0.001f);
        assertEquals(20.0f, plane.distance());
        assertEquals(SplitPlane.SplitAxis.Z, plane.axis());
    }

    /**
     * Test axis inference from normal vector - X axis.
     */
    @Test
    void testInferAxis_X() {
        var normal = new Point3f(1.0f, 0.0f, 0.0f);
        var plane = new SplitPlane(normal, 0.0f);

        assertEquals(SplitPlane.SplitAxis.X, plane.axis());
    }

    /**
     * Test axis inference from normal vector - Y axis.
     */
    @Test
    void testInferAxis_Y() {
        var normal = new Point3f(0.0f, 1.0f, 0.0f);
        var plane = new SplitPlane(normal, 0.0f);

        assertEquals(SplitPlane.SplitAxis.Y, plane.axis());
    }

    /**
     * Test axis inference from normal vector - Z axis.
     */
    @Test
    void testInferAxis_Z() {
        var normal = new Point3f(0.0f, 0.0f, 1.0f);
        var plane = new SplitPlane(normal, 0.0f);

        assertEquals(SplitPlane.SplitAxis.Z, plane.axis());
    }

    /**
     * Test axis inference with near-axis normals (dominant component).
     */
    @Test
    void testInferAxis_NearX() {
        // Normal vector mostly in X direction
        var normal = new Point3f(0.95f, 0.1f, 0.1f);
        var plane = new SplitPlane(normal, 0.0f);

        assertEquals(SplitPlane.SplitAxis.X, plane.axis());
    }

    /**
     * Test axis inference defaults to Z for diagonal normals.
     */
    @Test
    void testInferAxis_Diagonal() {
        // Diagonal normal (no dominant axis)
        var normal = new Point3f(0.577f, 0.577f, 0.577f);
        var plane = new SplitPlane(normal, 0.0f);

        // Should default to Z when no axis dominates
        assertEquals(SplitPlane.SplitAxis.Z, plane.axis());
    }

    /**
     * Test that factory methods create unit normal vectors.
     */
    @Test
    void testFactoryCreatesUnitNormals() {
        var planeX = SplitPlane.xAxis(0.0f);
        var planeY = SplitPlane.yAxis(0.0f);
        var planeZ = SplitPlane.zAxis(0.0f);

        // All normals should be unit length
        assertEquals(1.0f, vectorLength(planeX.normal()), 0.001f);
        assertEquals(1.0f, vectorLength(planeY.normal()), 0.001f);
        assertEquals(1.0f, vectorLength(planeZ.normal()), 0.001f);
    }

    /**
     * Helper: compute vector length.
     */
    private float vectorLength(Point3f v) {
        return (float) Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
    }

    /**
     * Test three-arg constructor explicitly.
     */
    @Test
    void testThreeArgConstructor() {
        var normal = new Point3f(0.0f, 1.0f, 0.0f);
        var plane = new SplitPlane(normal, 7.5f, SplitPlane.SplitAxis.Y);

        assertNotNull(plane);
        assertEquals(normal, plane.normal());
        assertEquals(7.5f, plane.distance());
        assertEquals(SplitPlane.SplitAxis.Y, plane.axis());
    }

    /**
     * Test that axis field is accessible.
     */
    @Test
    void testAxisAccessor() {
        var plane = SplitPlane.xAxis(0.0f);

        // Verify axis() accessor works
        var axis = plane.axis();
        assertNotNull(axis);
        assertTrue(axis instanceof SplitPlane.SplitAxis);
    }

    /**
     * Test alongLongestAxis() factory method - X dominant.
     */
    @Test
    void testAlongLongestAxis_XDominant() {
        // Create bounds that are longer in X direction
        var positions = List.of(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(100.0f, 10.0f, 10.0f)  // X is longest
        );
        var bounds = com.hellblazer.luciferase.simulation.bubble.BubbleBounds.fromEntityPositions(positions);

        var plane = SplitPlane.alongLongestAxis(bounds);

        assertNotNull(plane);
        assertEquals(SplitPlane.SplitAxis.X, plane.axis());
        // Normal should be X-axis aligned
        assertEquals(1.0f, Math.abs(plane.normal().x), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().y), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().z), 0.001f);
    }

    /**
     * Test alongLongestAxis() factory method - Y dominant.
     */
    @Test
    void testAlongLongestAxis_YDominant() {
        // Create bounds that are longer in Y direction
        var positions = List.of(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(10.0f, 100.0f, 10.0f)  // Y is longest
        );
        var bounds = com.hellblazer.luciferase.simulation.bubble.BubbleBounds.fromEntityPositions(positions);

        var plane = SplitPlane.alongLongestAxis(bounds);

        assertNotNull(plane);
        assertEquals(SplitPlane.SplitAxis.Y, plane.axis());
        // Normal should be Y-axis aligned
        assertEquals(0.0f, Math.abs(plane.normal().x), 0.001f);
        assertEquals(1.0f, Math.abs(plane.normal().y), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().z), 0.001f);
    }

    /**
     * Test alongLongestAxis() factory method - Z dominant.
     */
    @Test
    void testAlongLongestAxis_ZDominant() {
        // Create bounds that are longer in Z direction
        var positions = List.of(
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(10.0f, 10.0f, 100.0f)  // Z is longest
        );
        var bounds = com.hellblazer.luciferase.simulation.bubble.BubbleBounds.fromEntityPositions(positions);

        var plane = SplitPlane.alongLongestAxis(bounds);

        assertNotNull(plane);
        assertEquals(SplitPlane.SplitAxis.Z, plane.axis());
        // Normal should be Z-axis aligned
        assertEquals(0.0f, Math.abs(plane.normal().x), 0.001f);
        assertEquals(0.0f, Math.abs(plane.normal().y), 0.001f);
        assertEquals(1.0f, Math.abs(plane.normal().z), 0.001f);
    }

    /**
     * Test alongLongestAxis() places plane through centroid.
     */
    @Test
    void testAlongLongestAxis_PlaneAtCentroid() {
        // Create symmetric bounds
        var positions = List.of(
            new Point3f(-50.0f, -10.0f, -10.0f),
            new Point3f(50.0f, 10.0f, 10.0f)
        );
        var bounds = com.hellblazer.luciferase.simulation.bubble.BubbleBounds.fromEntityPositions(positions);

        var plane = SplitPlane.alongLongestAxis(bounds);

        // Plane should pass through centroid (approximately)
        var centroid = bounds.centroid();
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
