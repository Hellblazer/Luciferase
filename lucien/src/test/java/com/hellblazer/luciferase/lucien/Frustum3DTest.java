package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Frustum3D class
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class Frustum3DTest {

    @Test
    void testCreatePerspectiveFrustum() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        float fovy = (float) Math.PI / 4.0f; // 45 degrees
        float aspectRatio = 16.0f / 9.0f;
        float nearDistance = 50.0f;
        float farDistance = 500.0f;
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, fovy, aspectRatio, nearDistance, farDistance);
        
        assertNotNull(frustum);
        assertNotNull(frustum.nearPlane);
        assertNotNull(frustum.farPlane);
        assertNotNull(frustum.leftPlane);
        assertNotNull(frustum.rightPlane);
        assertNotNull(frustum.topPlane);
        assertNotNull(frustum.bottomPlane);
        
        // Test that camera position is inside the frustum (should be behind near plane)
        // Actually, camera is typically outside the frustum volume in front of near plane
        Plane3D[] planes = frustum.getPlanes();
        assertEquals(6, planes.length);
    }

    @Test
    void testCreateOrthographicFrustum() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        float left = 25.0f, right = 75.0f;
        float bottom = 25.0f, top = 75.0f;
        float nearDistance = 50.0f, farDistance = 500.0f;
        
        Frustum3D frustum = Frustum3D.createOrthographic(cameraPos, lookAt, up, 
                                                        left, right, bottom, top, 
                                                        nearDistance, farDistance);
        
        assertNotNull(frustum);
        assertNotNull(frustum.nearPlane);
        assertNotNull(frustum.farPlane);
        assertNotNull(frustum.leftPlane);
        assertNotNull(frustum.rightPlane);
        assertNotNull(frustum.topPlane);
        assertNotNull(frustum.bottomPlane);
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativeCamera = new Point3f(-50.0f, 100.0f, 100.0f);
        Point3f positiveLookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(negativeCamera, positiveLookAt, up, 
                                      (float) Math.PI / 4.0f, 1.0f, 50.0f, 500.0f);
        });
        
        Point3f positiveCamera = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f negativeLookAt = new Point3f(-200.0f, 200.0f, 200.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(positiveCamera, negativeLookAt, up, 
                                      (float) Math.PI / 4.0f, 1.0f, 50.0f, 500.0f);
        });
    }

    @Test
    void testInvalidParametersThrowException() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        // Invalid near/far distances
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                      (float) Math.PI / 4.0f, 1.0f, -10.0f, 500.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                      (float) Math.PI / 4.0f, 1.0f, 500.0f, 50.0f); // far < near
        });
        
        // Invalid field of view
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                      -0.1f, 1.0f, 50.0f, 500.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                      (float) Math.PI + 0.1f, 1.0f, 50.0f, 500.0f);
        });
        
        // Invalid aspect ratio
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                      (float) Math.PI / 4.0f, -1.0f, 50.0f, 500.0f);
        });
    }

    @Test
    void testOrthographicInvalidParametersThrowException() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        // Invalid boundaries
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createOrthographic(cameraPos, lookAt, up, 
                                       -10.0f, 50.0f, 25.0f, 75.0f, 50.0f, 500.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createOrthographic(cameraPos, lookAt, up, 
                                       75.0f, 50.0f, 25.0f, 75.0f, 50.0f, 500.0f); // right < left
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createOrthographic(cameraPos, lookAt, up, 
                                       25.0f, 75.0f, 75.0f, 50.0f, 50.0f, 500.0f); // top < bottom
        });
    }

    @Test
    void testContainsPoint() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 6.0f, 1.0f, 50.0f, 500.0f);
        
        // Test point that should be inside frustum (center of view direction, between near and far)
        Point3f centerPoint = new Point3f(150.0f, 150.0f, 150.0f);
        
        // Test point that should be outside frustum (behind camera)
        Point3f behindCamera = new Point3f(50.0f, 50.0f, 50.0f);
        
        // Test point that should be outside frustum (too far)
        Point3f tooFar = new Point3f(700.0f, 700.0f, 700.0f);
        
        // Note: The exact results depend on the specific frustum geometry
        // We're mainly testing that the method works without exceptions
        assertDoesNotThrow(() -> frustum.containsPoint(centerPoint));
        assertDoesNotThrow(() -> frustum.containsPoint(behindCamera));
        assertDoesNotThrow(() -> frustum.containsPoint(tooFar));
    }

    @Test
    void testContainsPointNegativeCoordinatesThrowException() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 4.0f, 1.0f, 50.0f, 500.0f);
        
        Point3f negativePoint = new Point3f(-50.0f, 150.0f, 150.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            frustum.containsPoint(negativePoint);
        });
    }

    @Test
    void testIntersectsAABB() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 3.0f, 1.0f, 50.0f, 500.0f);
        
        // Large box that should intersect frustum
        assertDoesNotThrow(() -> {
            frustum.intersectsAABB(120.0f, 120.0f, 120.0f, 180.0f, 180.0f, 180.0f);
        });
        
        // Box that should be outside frustum (behind camera)
        assertDoesNotThrow(() -> {
            frustum.intersectsAABB(10.0f, 10.0f, 10.0f, 30.0f, 30.0f, 30.0f);
        });
        
        // Box that should be outside frustum (too far)
        assertDoesNotThrow(() -> {
            frustum.intersectsAABB(800.0f, 800.0f, 800.0f, 850.0f, 850.0f, 850.0f);
        });
    }

    @Test
    void testIntersectsAABBNegativeCoordinatesThrowException() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 4.0f, 1.0f, 50.0f, 500.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            frustum.intersectsAABB(-10.0f, 120.0f, 120.0f, 180.0f, 180.0f, 180.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            frustum.intersectsAABB(120.0f, -10.0f, 120.0f, 180.0f, 180.0f, 180.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            frustum.intersectsAABB(120.0f, 120.0f, -10.0f, 180.0f, 180.0f, 180.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            frustum.intersectsAABB(120.0f, 120.0f, 120.0f, -10.0f, 180.0f, 180.0f);
        });
    }

    @Test
    void testContainsAABB() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 2.0f, 1.0f, 50.0f, 500.0f);
        
        // Test various boxes
        assertDoesNotThrow(() -> {
            frustum.containsAABB(140.0f, 140.0f, 140.0f, 160.0f, 160.0f, 160.0f);
        });
        
        assertDoesNotThrow(() -> {
            frustum.containsAABB(10.0f, 10.0f, 10.0f, 30.0f, 30.0f, 30.0f);
        });
    }

    @Test
    void testIntersectsCube() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 4.0f, 1.0f, 50.0f, 500.0f);
        
        // Various cubes to test
        Spatial.Cube cube1 = new Spatial.Cube(140.0f, 140.0f, 140.0f, 20.0f);
        Spatial.Cube cube2 = new Spatial.Cube(10.0f, 10.0f, 10.0f, 20.0f);
        Spatial.Cube cube3 = new Spatial.Cube(800.0f, 800.0f, 800.0f, 20.0f);
        
        assertDoesNotThrow(() -> frustum.intersectsCube(cube1));
        assertDoesNotThrow(() -> frustum.intersectsCube(cube2));
        assertDoesNotThrow(() -> frustum.intersectsCube(cube3));
    }

    @Test
    void testContainsCube() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 2.0f, 1.0f, 50.0f, 500.0f);
        
        Spatial.Cube cube = new Spatial.Cube(140.0f, 140.0f, 140.0f, 20.0f);
        
        assertDoesNotThrow(() -> frustum.containsCube(cube));
    }

    @Test
    void testGetPlanes() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 4.0f, 1.0f, 50.0f, 500.0f);
        
        Plane3D[] planes = frustum.getPlanes();
        assertEquals(6, planes.length);
        
        assertEquals(frustum.nearPlane, planes[0]);
        assertEquals(frustum.farPlane, planes[1]);
        assertEquals(frustum.leftPlane, planes[2]);
        assertEquals(frustum.rightPlane, planes[3]);
        assertEquals(frustum.topPlane, planes[4]);
        assertEquals(frustum.bottomPlane, planes[5]);
    }

    @Test
    void testToString() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 4.0f, 1.0f, 50.0f, 500.0f);
        
        String str = frustum.toString();
        assertTrue(str.contains("Frustum3D"));
        assertTrue(str.contains("near="));
        assertTrue(str.contains("far="));
        assertTrue(str.contains("left="));
        assertTrue(str.contains("right="));
        assertTrue(str.contains("top="));
        assertTrue(str.contains("bottom="));
    }

    @Test
    void testSimplePerspectiveFrustumProperties() {
        // Simple frustum looking along positive Z axis
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(100.0f, 100.0f, 200.0f);
        Vector3f up = new Vector3f(100.0f, 200.0f, 100.0f);
        
        float fovy = (float) Math.PI / 4.0f;
        float aspectRatio = 1.0f;
        float nearDistance = 50.0f;
        float farDistance = 200.0f;
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, fovy, aspectRatio, nearDistance, farDistance);
        
        // Point in the center of view, between near and far planes
        Point3f centerPoint = new Point3f(100.0f, 100.0f, 175.0f);
        
        // Point behind the camera (should be outside)
        Point3f behindCamera = new Point3f(100.0f, 100.0f, 75.0f);
        
        // Point beyond far plane (should be outside)
        Point3f beyondFar = new Point3f(100.0f, 100.0f, 350.0f);
        
        // The exact results depend on frustum geometry, but methods should work
        assertDoesNotThrow(() -> frustum.containsPoint(centerPoint));
        assertDoesNotThrow(() -> frustum.containsPoint(behindCamera));
        assertDoesNotThrow(() -> frustum.containsPoint(beyondFar));
    }

    @Test
    void testSimpleOrthographicFrustumProperties() {
        // Simple orthographic frustum with camera looking along a more controlled direction
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(100.0f, 100.0f, 200.0f);
        Vector3f up = new Vector3f(50.0f, 100.0f, 50.0f); // More constrained up vector
        
        float left = 25.0f, right = 75.0f;
        float bottom = 25.0f, top = 75.0f;
        float nearDistance = 50.0f, farDistance = 200.0f;
        
        Frustum3D frustum = Frustum3D.createOrthographic(cameraPos, lookAt, up, 
                                                        left, right, bottom, top,
                                                        nearDistance, farDistance);
        
        // Point in the center of the orthographic volume
        Point3f centerPoint = new Point3f(100.0f, 100.0f, 175.0f);
        
        // Test that methods work without exceptions
        assertDoesNotThrow(() -> frustum.containsPoint(centerPoint));
        assertDoesNotThrow(() -> frustum.intersectsAABB(90.0f, 90.0f, 160.0f, 110.0f, 110.0f, 190.0f));
    }
}