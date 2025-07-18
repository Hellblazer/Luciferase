package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the unified Ray3D class
 */
public class Ray3DTest {

    private static final float EPSILON = 1e-6f;

    @Test
    public void testDirectionNormalization() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(3, 4, 0); // Length = 5

        var ray = new Ray3D(origin, direction);

        assertEquals(0.6f, ray.direction().x, EPSILON);
        assertEquals(0.8f, ray.direction().y, EPSILON);
        assertEquals(0.0f, ray.direction().z, EPSILON);
        assertEquals(1.0f, ray.direction().length(), EPSILON);
    }

    @Test
    public void testFromPoints() {
        var origin = new Point3f(1, 2, 3);
        var target = new Point3f(4, 6, 3);

        var ray = Ray3D.fromPoints(origin, target);

        assertEquals(origin, ray.origin());
        assertEquals(5.0f, ray.maxDistance(), EPSILON); // Distance = sqrt(9 + 16) = 5

        // Direction should point from origin to target
        var expectedDir = new Vector3f(3, 4, 0);
        expectedDir.normalize();
        assertEquals(expectedDir.x, ray.direction().x, EPSILON);
        assertEquals(expectedDir.y, ray.direction().y, EPSILON);
        assertEquals(expectedDir.z, ray.direction().z, EPSILON);
    }

    @Test
    public void testFromPointsUnbounded() {
        var origin = new Point3f(1, 2, 3);
        var target = new Point3f(4, 6, 3);

        var ray = Ray3D.fromPointsUnbounded(origin, target);

        assertEquals(origin, ray.origin());
        assertTrue(ray.isUnbounded());

        // Direction should point from origin to target
        var expectedDir = new Vector3f(3, 4, 0);
        expectedDir.normalize();
        assertEquals(expectedDir.x, ray.direction().x, EPSILON);
        assertEquals(expectedDir.y, ray.direction().y, EPSILON);
        assertEquals(expectedDir.z, ray.direction().z, EPSILON);
    }

    @Test
    public void testGetPointAt() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(1, 0, 0);
        var ray = new Ray3D(origin, direction);

        var p0 = ray.getPointAt(0);
        assertEquals(origin, p0);

        var p5 = ray.getPointAt(5);
        assertEquals(6.0f, p5.x, EPSILON);
        assertEquals(2.0f, p5.y, EPSILON);
        assertEquals(3.0f, p5.z, EPSILON);
    }

    @Test
    public void testIsWithinDistance() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(1, 0, 0);
        var ray = new Ray3D(origin, direction, 10.0f);

        assertTrue(ray.isWithinDistance(5.0f));
        assertTrue(ray.isWithinDistance(10.0f));
        assertFalse(ray.isWithinDistance(15.0f));
    }

    @Test
    public void testNegativeMaxDistanceThrows() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(1, 0, 0);

        assertThrows(IllegalArgumentException.class, () -> {
            new Ray3D(origin, direction, -5.0f);
        });
    }

    @Test
    public void testNegativeOriginAllowed() {
        // Ray origins can be anywhere in 3D space, including negative coordinates
        // Only entities in the spatial index must have positive coordinates
        var negativeOrigin = new Point3f(-1, 2, 3);
        var direction = new Vector3f(1, 0, 0);

        // Should not throw - negative origins are allowed for rays
        var ray = new Ray3D(negativeOrigin, direction);
        assertEquals(negativeOrigin, ray.origin());
    }

    @Test
    public void testPointAtAlias() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(0, 1, 0);
        var ray = new Ray3D(origin, direction);

        var p1 = ray.pointAt(3);
        var p2 = ray.getPointAt(3);

        assertEquals(p1, p2);
    }

    @Test
    public void testRayCreation() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(1, 0, 0);

        var ray = new Ray3D(origin, direction);

        assertEquals(origin, ray.origin());
        assertEquals(1.0f, ray.direction().x, EPSILON);
        assertEquals(0.0f, ray.direction().y, EPSILON);
        assertEquals(0.0f, ray.direction().z, EPSILON);
        assertEquals(Ray3D.UNBOUNDED, ray.maxDistance());
    }

    @Test
    public void testRayWithMaxDistance() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(0, 1, 0);
        var maxDistance = 10.0f;

        var ray = new Ray3D(origin, direction, maxDistance);

        assertEquals(maxDistance, ray.maxDistance());
        assertFalse(ray.isUnbounded());
    }

    @Test
    public void testUnboundedMethod() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(1, 0, 0);
        var ray1 = new Ray3D(origin, direction, 10.0f);

        var ray2 = ray1.unbounded();

        assertEquals(ray1.origin(), ray2.origin());
        assertEquals(ray1.direction(), ray2.direction());
        assertFalse(ray1.isUnbounded());
        assertTrue(ray2.isUnbounded());
    }

    @Test
    public void testUnboundedRay() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(0, 0, 1);

        var ray = new Ray3D(origin, direction);

        assertTrue(ray.isUnbounded());
        assertEquals(Float.POSITIVE_INFINITY, ray.maxDistance());
    }

    @Test
    public void testWithMaxDistance() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(1, 0, 0);
        var ray1 = new Ray3D(origin, direction);

        var ray2 = ray1.withMaxDistance(20.0f);

        assertEquals(ray1.origin(), ray2.origin());
        assertEquals(ray1.direction(), ray2.direction());
        assertEquals(20.0f, ray2.maxDistance());
        assertTrue(ray1.isUnbounded());
        assertFalse(ray2.isUnbounded());
    }

    @Test
    public void testZeroDirectionThrows() {
        var origin = new Point3f(1, 2, 3);
        var zeroDirection = new Vector3f(0, 0, 0);

        assertThrows(IllegalArgumentException.class, () -> {
            new Ray3D(origin, zeroDirection);
        });
    }

    @Test
    public void testZeroMaxDistanceThrows() {
        var origin = new Point3f(1, 2, 3);
        var direction = new Vector3f(1, 0, 0);

        assertThrows(IllegalArgumentException.class, () -> {
            new Ray3D(origin, direction, 0.0f);
        });
    }
}
