package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Plane3D class All test coordinates use positive values only
 *
 * @author hal.hildebrand
 */
public class Plane3DTest {

    @Test
    void testAABBWithNegativeCoordinatesThrowsException() {
        Plane3D plane = Plane3D.parallelToXY(100.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            plane.intersectsAABB(-10.0f, 50.0f, 50.0f, 100.0f, 100.0f, 100.0f);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            plane.intersectsAABB(50.0f, -10.0f, 50.0f, 100.0f, 100.0f, 100.0f);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            plane.intersectsAABB(50.0f, 50.0f, -10.0f, 100.0f, 100.0f, 100.0f);
        });
    }

    @Test
    void testCollinearPointsThrowException() {
        // Three collinear points - all on same line
        Point3f p1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f p2 = new Point3f(200.0f, 200.0f, 200.0f);
        Point3f p3 = new Point3f(300.0f, 300.0f, 300.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            Plane3D.fromThreePoints(p1, p2, p3);
        });
    }

    @Test
    void testContainsPoint() {
        Point3f p1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f p2 = new Point3f(200.0f, 100.0f, 100.0f);
        Point3f p3 = new Point3f(150.0f, 200.0f, 100.0f);

        Plane3D plane = Plane3D.fromThreePoints(p1, p2, p3);

        assertTrue(plane.containsPoint(p1));
        assertTrue(plane.containsPoint(p2));
        assertTrue(plane.containsPoint(p3));

        // Point not on plane
        Point3f outsidePoint = new Point3f(100.0f, 100.0f, 200.0f);
        assertFalse(plane.containsPoint(outsidePoint));

        // Point near plane within tolerance
        Point3f nearPoint = new Point3f(100.0f, 100.0f, 100.001f);
        assertTrue(plane.containsPoint(nearPoint, 0.01f));
        assertFalse(plane.containsPoint(nearPoint, 0.0001f));
    }

    @Test
    void testDistanceToPoint() {
        // Create a plane parallel to XY plane at z = 100
        Plane3D plane = Plane3D.parallelToXY(100.0f);

        Point3f pointOnPlane = new Point3f(50.0f, 75.0f, 100.0f);
        Point3f pointAbove = new Point3f(50.0f, 75.0f, 150.0f);
        Point3f pointBelow = new Point3f(50.0f, 75.0f, 50.0f);

        assertEquals(0.0f, plane.distanceToPoint(pointOnPlane), 0.001f);
        assertEquals(50.0f, plane.distanceToPoint(pointAbove), 0.001f);
        assertEquals(-50.0f, plane.distanceToPoint(pointBelow), 0.001f);

        assertEquals(0.0f, plane.absoluteDistanceToPoint(pointOnPlane), 0.001f);
        assertEquals(50.0f, plane.absoluteDistanceToPoint(pointAbove), 0.001f);
        assertEquals(50.0f, plane.absoluteDistanceToPoint(pointBelow), 0.001f);
    }

    @Test
    void testFromPointAndNormal() {
        Point3f point = new Point3f(50.0f, 75.0f, 100.0f);
        Vector3f normal = new Vector3f(1.0f, 1.0f, 1.0f);

        Plane3D plane = Plane3D.fromPointAndNormal(point, normal);

        // The original point should lie on the plane
        assertTrue(plane.containsPoint(point));

        // Normal should be normalized
        Vector3f planeNormal = plane.getNormal();
        assertEquals(1.0f, planeNormal.length(), 0.001f);
    }

    @Test
    void testFromThreePoints() {
        // Define three points in positive space that form a triangle
        Point3f p1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f p2 = new Point3f(200.0f, 100.0f, 100.0f);
        Point3f p3 = new Point3f(150.0f, 200.0f, 100.0f);

        Plane3D plane = Plane3D.fromThreePoints(p1, p2, p3);

        // All three points should lie on the plane
        assertTrue(plane.containsPoint(p1));
        assertTrue(plane.containsPoint(p2));
        assertTrue(plane.containsPoint(p3));
    }

    @Test
    void testIntersectsAABB() {
        // Create a diagonal plane
        Point3f p1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f p2 = new Point3f(200.0f, 100.0f, 200.0f);
        Point3f p3 = new Point3f(100.0f, 200.0f, 200.0f);

        Plane3D plane = Plane3D.fromThreePoints(p1, p2, p3);

        // Box that intersects the plane
        assertTrue(plane.intersectsAABB(50.0f, 50.0f, 50.0f, 250.0f, 250.0f, 250.0f));

        // Box completely on one side
        assertFalse(plane.intersectsAABB(300.0f, 300.0f, 50.0f, 400.0f, 400.0f, 60.0f));

        // Box completely on other side  
        assertFalse(plane.intersectsAABB(50.0f, 50.0f, 300.0f, 60.0f, 60.0f, 400.0f));
    }

    @Test
    void testIntersectsCube() {
        Plane3D plane = Plane3D.parallelToXY(150.0f);

        // Cube that intersects plane
        Spatial.Cube intersectingCube = new Spatial.Cube(100.0f, 100.0f, 120.0f, 50.0f);
        assertTrue(plane.intersectsCube(intersectingCube));

        // Cube completely above plane
        Spatial.Cube aboveCube = new Spatial.Cube(100.0f, 100.0f, 200.0f, 30.0f);
        assertFalse(plane.intersectsCube(aboveCube));

        // Cube completely below plane
        Spatial.Cube belowCube = new Spatial.Cube(100.0f, 100.0f, 50.0f, 30.0f);
        assertFalse(plane.intersectsCube(belowCube));
    }

    @Test
    void testNegativeCoordinatesInDistanceCalculation() {
        Plane3D plane = Plane3D.parallelToXY(100.0f);
        Point3f negativePoint = new Point3f(-50.0f, 75.0f, 100.0f);

        // Negative coordinates are now allowed, so these should work
        float distance = plane.distanceToPoint(negativePoint);
        assertEquals(0.0f, distance, 1e-6f);

        float absDistance = plane.absoluteDistanceToPoint(negativePoint);
        assertEquals(0.0f, absDistance, 1e-6f);

        boolean contains = plane.containsPoint(negativePoint);
        assertTrue(contains);
    }

    @Test
    void testNegativeCoordinatesInParallelPlanesThrowException() {
        assertThrows(IllegalArgumentException.class, () -> Plane3D.parallelToXY(-10.0f));
        assertThrows(IllegalArgumentException.class, () -> Plane3D.parallelToXZ(-5.0f));
        assertThrows(IllegalArgumentException.class, () -> Plane3D.parallelToYZ(-15.0f));
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativePoint = new Point3f(-10.0f, 50.0f, 75.0f);
        Point3f positivePoint1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f positivePoint2 = new Point3f(200.0f, 100.0f, 100.0f);

        // Negative coordinates are now allowed, so these should work
        Plane3D plane1 = Plane3D.fromThreePoints(negativePoint, positivePoint1, positivePoint2);
        assertNotNull(plane1);

        Vector3f normal = new Vector3f(1.0f, 0.0f, 0.0f);
        Plane3D plane2 = Plane3D.fromPointAndNormal(negativePoint, normal);
        assertNotNull(plane2);
    }

    @Test
    void testParallelPlanes() {
        // Test axis-aligned planes
        Plane3D xyPlane = Plane3D.parallelToXY(100.0f);
        Plane3D xzPlane = Plane3D.parallelToXZ(75.0f);
        Plane3D yzPlane = Plane3D.parallelToYZ(50.0f);

        // Test points on each plane
        assertTrue(xyPlane.containsPoint(new Point3f(200.0f, 300.0f, 100.0f)));
        assertTrue(xzPlane.containsPoint(new Point3f(200.0f, 75.0f, 300.0f)));
        assertTrue(yzPlane.containsPoint(new Point3f(50.0f, 200.0f, 300.0f)));

        // Test normal vectors
        Vector3f xyNormal = xyPlane.getNormal();
        assertEquals(0.0f, xyNormal.x, 0.001f);
        assertEquals(0.0f, xyNormal.y, 0.001f);
        assertEquals(1.0f, xyNormal.z, 0.001f);

        Vector3f xzNormal = xzPlane.getNormal();
        assertEquals(0.0f, xzNormal.x, 0.001f);
        assertEquals(1.0f, xzNormal.y, 0.001f);
        assertEquals(0.0f, xzNormal.z, 0.001f);

        Vector3f yzNormal = yzPlane.getNormal();
        assertEquals(1.0f, yzNormal.x, 0.001f);
        assertEquals(0.0f, yzNormal.y, 0.001f);
        assertEquals(0.0f, yzNormal.z, 0.001f);
    }

    @Test
    void testToString() {
        Plane3D plane = Plane3D.parallelToXY(100.0f);
        String str = plane.toString();

        assertTrue(str.contains("Plane3D"));
        assertTrue(str.contains("0.000x"));
        assertTrue(str.contains("0.000y"));
        assertTrue(str.contains("1.000z"));
        assertTrue(str.contains("-100.000"));
    }

    @Test
    void testZeroNormalThrowsException() {
        Point3f point = new Point3f(50.0f, 75.0f, 100.0f);
        Vector3f zeroNormal = new Vector3f(0.0f, 0.0f, 0.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            Plane3D.fromPointAndNormal(point, zeroNormal);
        });
    }
}
