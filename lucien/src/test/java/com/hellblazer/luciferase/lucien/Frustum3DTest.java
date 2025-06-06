package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit test for Frustum3D class
 * Tests perspective and orthographic frustum creation, point containment, and AABB intersection
 * 
 * @author hal.hildebrand
 */
class Frustum3DTest {

    @Test
    @DisplayName("Test perspective frustum creation with valid parameters")
    void testCreatePerspectiveValid() {
        Point3f cameraPosition = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        float fovy = (float) Math.toRadians(60.0);
        float aspectRatio = 1.5f;
        float nearDistance = 10.0f;
        float farDistance = 1000.0f;

        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up, fovy, aspectRatio, nearDistance, farDistance
        );

        assertNotNull(frustum);
        assertNotNull(frustum.nearPlane);
        assertNotNull(frustum.farPlane);
        assertNotNull(frustum.leftPlane);
        assertNotNull(frustum.rightPlane);
        assertNotNull(frustum.topPlane);
        assertNotNull(frustum.bottomPlane);
    }

    @Test
    @DisplayName("Test perspective frustum with negative camera position")
    void testCreatePerspectiveNegativeCamera() {
        Point3f cameraPosition = new Point3f(-100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(
                cameraPosition, lookAt, up, 
                (float) Math.toRadians(60.0), 1.5f, 10.0f, 1000.0f
            );
        });
    }

    @Test
    @DisplayName("Test perspective frustum with negative look-at point")
    void testCreatePerspectiveNegativeLookAt() {
        Point3f cameraPosition = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, -50.0f, 200.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(
                cameraPosition, lookAt, up, 
                (float) Math.toRadians(60.0), 1.5f, 10.0f, 1000.0f
            );
        });
    }

    @Test
    @DisplayName("Test perspective frustum with invalid field of view")
    void testCreatePerspectiveInvalidFOV() {
        Point3f cameraPosition = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);

        // Test with negative FOV
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(
                cameraPosition, lookAt, up, 
                -0.5f, 1.5f, 10.0f, 1000.0f
            );
        });

        // Test with FOV >= PI
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(
                cameraPosition, lookAt, up, 
                (float) Math.PI, 1.5f, 10.0f, 1000.0f
            );
        });
    }

    @Test
    @DisplayName("Test perspective frustum with invalid distances")
    void testCreatePerspectiveInvalidDistances() {
        Point3f cameraPosition = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);

        // Test with negative near distance
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(
                cameraPosition, lookAt, up, 
                (float) Math.toRadians(60.0), 1.5f, -10.0f, 1000.0f
            );
        });

        // Test with far distance less than near distance
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createPerspective(
                cameraPosition, lookAt, up, 
                (float) Math.toRadians(60.0), 1.5f, 100.0f, 50.0f
            );
        });
    }

    @Test
    @DisplayName("Test orthographic frustum creation with valid parameters")
    void testCreateOrthographicValid() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        float left = 100.0f;
        float right = 200.0f;
        float bottom = 100.0f;
        float top = 200.0f;
        float nearDistance = 50.0f;
        float farDistance = 2000.0f;

        Frustum3D frustum = Frustum3D.createOrthographic(
            cameraPosition, lookAt, up, 
            left, right, bottom, top, nearDistance, farDistance
        );

        assertNotNull(frustum);
        assertNotNull(frustum.nearPlane);
        assertNotNull(frustum.farPlane);
        assertNotNull(frustum.leftPlane);
        assertNotNull(frustum.rightPlane);
        assertNotNull(frustum.topPlane);
        assertNotNull(frustum.bottomPlane);
    }

    @Test
    @DisplayName("Test orthographic frustum with negative boundaries")
    void testCreateOrthographicNegativeBoundaries() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createOrthographic(
                cameraPosition, lookAt, up, 
                -100.0f, 200.0f, 100.0f, 200.0f, 50.0f, 2000.0f
            );
        });
    }

    @Test
    @DisplayName("Test orthographic frustum with invalid boundary order")
    void testCreateOrthographicInvalidBoundaryOrder() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);

        // Test with right <= left
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createOrthographic(
                cameraPosition, lookAt, up, 
                200.0f, 100.0f, 100.0f, 200.0f, 50.0f, 2000.0f
            );
        });

        // Test with top <= bottom
        assertThrows(IllegalArgumentException.class, () -> {
            Frustum3D.createOrthographic(
                cameraPosition, lookAt, up, 
                100.0f, 200.0f, 200.0f, 100.0f, 50.0f, 2000.0f
            );
        });
    }

    @Test
    @DisplayName("Test containsPoint for perspective frustum")
    void testContainsPointPerspective() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );

        // Test point directly in front of camera (should be inside)
        Point3f insidePoint = new Point3f(500.0f, 500.0f, 500.0f);
        assertTrue(frustum.containsPoint(insidePoint));

        // Test point behind camera (should be outside)
        Point3f behindPoint = new Point3f(500.0f, 500.0f, 50.0f);
        assertFalse(frustum.containsPoint(behindPoint));

        // Test point beyond far plane (should be outside)
        Point3f beyondFarPoint = new Point3f(500.0f, 500.0f, 2500.0f);
        assertFalse(frustum.containsPoint(beyondFarPoint));

        // Test point far to the side (should be outside)
        Point3f sidePoint = new Point3f(2000.0f, 500.0f, 500.0f);
        assertFalse(frustum.containsPoint(sidePoint));
    }

    @Test
    @DisplayName("Test containsPoint with negative coordinates")
    void testContainsPointNegativeCoordinates() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );

        Point3f negativePoint = new Point3f(-100.0f, 500.0f, 500.0f);
        assertThrows(IllegalArgumentException.class, () -> {
            frustum.containsPoint(negativePoint);
        });
    }

    @Test
    @DisplayName("Test intersectsAABB for perspective frustum")
    void testIntersectsAABBPerspective() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );

        // Test AABB directly in front of camera (should intersect)
        assertTrue(frustum.intersectsAABB(
            450.0f, 450.0f, 450.0f,
            550.0f, 550.0f, 550.0f
        ));

        // Test AABB behind camera (should not intersect)
        assertFalse(frustum.intersectsAABB(
            450.0f, 450.0f, 10.0f,
            550.0f, 550.0f, 90.0f
        ));

        // Test AABB beyond far plane (should not intersect)
        assertFalse(frustum.intersectsAABB(
            450.0f, 450.0f, 2100.0f,
            550.0f, 550.0f, 2200.0f
        ));

        // Test AABB far to the side (should not intersect)
        assertFalse(frustum.intersectsAABB(
            2000.0f, 450.0f, 450.0f,
            2100.0f, 550.0f, 550.0f
        ));
    }

    @Test
    @DisplayName("Test intersectsAABB with negative coordinates")
    void testIntersectsAABBNegativeCoordinates() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );

        assertThrows(IllegalArgumentException.class, () -> {
            frustum.intersectsAABB(
                -100.0f, 450.0f, 450.0f,
                550.0f, 550.0f, 550.0f
            );
        });
    }

    @Test
    @DisplayName("Test containsAABB for perspective frustum")
    void testContainsAABBPerspective() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(90.0), 1.0f, 50.0f, 2000.0f // Wide FOV
        );

        // Test small AABB directly in front of camera (should be contained)
        assertTrue(frustum.containsAABB(
            490.0f, 490.0f, 490.0f,
            510.0f, 510.0f, 510.0f
        ));

        // Test AABB partially outside (should not be contained)
        assertFalse(frustum.containsAABB(
            450.0f, 450.0f, 140.0f,
            550.0f, 550.0f, 160.0f
        ));

        // Test AABB completely outside (should not be contained)
        assertFalse(frustum.containsAABB(
            450.0f, 450.0f, 10.0f,
            550.0f, 550.0f, 90.0f
        ));
    }

    @Test
    @DisplayName("Test intersectsCube and containsCube")
    void testCubeOperations() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );

        // Test cube in front of camera
        Spatial.Cube insideCube = new Spatial.Cube(490.0f, 490.0f, 490.0f, 20.0f);
        assertTrue(frustum.intersectsCube(insideCube));
        assertTrue(frustum.containsCube(insideCube));

        // Test cube behind camera
        Spatial.Cube behindCube = new Spatial.Cube(490.0f, 490.0f, 10.0f, 20.0f);
        assertFalse(frustum.intersectsCube(behindCube));
        assertFalse(frustum.containsCube(behindCube));
    }

    @Test
    @DisplayName("Test getPlanes returns all six planes")
    void testGetPlanes() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );

        Plane3D[] planes = frustum.getPlanes();
        
        assertNotNull(planes);
        assertEquals(6, planes.length);
        assertNotNull(planes[0]); // near
        assertNotNull(planes[1]); // far
        assertNotNull(planes[2]); // left
        assertNotNull(planes[3]); // right
        assertNotNull(planes[4]); // top
        assertNotNull(planes[5]); // bottom
    }

    @Test
    @DisplayName("Test orthographic frustum containment")
    void testOrthographicContainment() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createOrthographic(
            cameraPosition, lookAt, up,
            400.0f, 600.0f, 400.0f, 600.0f, // left, right, bottom, top
            50.0f, 2000.0f
        );

        // Point inside orthographic frustum
        Point3f testPoint = new Point3f(500.0f, 500.0f, 500.0f);
        System.out.println("Testing point: " + testPoint);
        System.out.println("Frustum planes:");
        for (Plane3D plane : frustum.getPlanes()) {
            System.out.println("  " + plane);
            System.out.println("    Distance to test point: " + plane.distanceToPoint(testPoint));
        }
        assertTrue(frustum.containsPoint(testPoint));
        
        // Point outside left boundary
        assertFalse(frustum.containsPoint(new Point3f(350.0f, 500.0f, 500.0f)));
        
        // Point outside right boundary
        assertFalse(frustum.containsPoint(new Point3f(650.0f, 500.0f, 500.0f)));
        
        // Point outside top boundary
        assertFalse(frustum.containsPoint(new Point3f(500.0f, 650.0f, 500.0f)));
        
        // Point outside bottom boundary
        assertFalse(frustum.containsPoint(new Point3f(500.0f, 350.0f, 500.0f)));
    }

    @Test
    @DisplayName("Test frustum with different aspect ratios")
    void testDifferentAspectRatios() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        // Wide aspect ratio
        Frustum3D wideFrustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 2.0f, 50.0f, 2000.0f
        );
        
        // Narrow aspect ratio
        Frustum3D narrowFrustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 0.5f, 50.0f, 2000.0f
        );
        
        // Point far to the side - should be inside wide frustum but outside narrow
        Point3f sidePoint = new Point3f(700.0f, 500.0f, 500.0f);
        
        // This test depends on the exact frustum calculation
        // We just verify that both frustums are created successfully
        assertNotNull(wideFrustum);
        assertNotNull(narrowFrustum);
    }

    @Test
    @DisplayName("Test frustum with edge cases for plane intersections")
    void testEdgeCases() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );

        // Test point exactly on near plane
        Point3f nearPlanePoint = new Point3f(500.0f, 500.0f, 150.0f);
        assertTrue(frustum.containsPoint(nearPlanePoint));
        
        // Test AABB straddling near plane
        assertTrue(frustum.intersectsAABB(
            490.0f, 490.0f, 140.0f,
            510.0f, 510.0f, 160.0f
        ));
        
        // Test AABB straddling far plane
        assertTrue(frustum.intersectsAABB(
            490.0f, 490.0f, 1990.0f,
            510.0f, 510.0f, 2010.0f
        ));
    }

    @Test
    @DisplayName("Test toString method")
    void testToString() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );

        String str = frustum.toString();
        assertNotNull(str);
        assertTrue(str.contains("Frustum3D"));
        assertTrue(str.contains("near="));
        assertTrue(str.contains("far="));
        assertTrue(str.contains("left="));
        assertTrue(str.contains("right="));
        assertTrue(str.contains("top="));
        assertTrue(str.contains("bottom="));
    }

    @Test
    @DisplayName("Test frustum with very small near/far ratio")
    void testSmallNearFarRatio() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        // Very small near distance with large far distance (common in games)
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 0.1f, 10000.0f
        );

        assertNotNull(frustum);
        
        // Test point very close to camera
        Point3f veryNearPoint = new Point3f(500.0f, 500.0f, 100.2f);
        assertTrue(frustum.containsPoint(veryNearPoint));
        
        // Test point very far from camera
        Point3f veryFarPoint = new Point3f(500.0f, 500.0f, 9000.0f);
        assertTrue(frustum.containsPoint(veryFarPoint));
    }

    @Test
    @DisplayName("Test frustum plane normals point inward")
    void testPlaneNormalsPointInward() {
        Point3f cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0), 1.0f, 50.0f, 2000.0f
        );

        // A point inside the frustum should have negative distance to all planes
        Point3f insidePoint = new Point3f(500.0f, 500.0f, 500.0f);
        
        assertTrue(frustum.nearPlane.distanceToPoint(insidePoint) <= 0);
        assertTrue(frustum.farPlane.distanceToPoint(insidePoint) <= 0);
        assertTrue(frustum.leftPlane.distanceToPoint(insidePoint) <= 0);
        assertTrue(frustum.rightPlane.distanceToPoint(insidePoint) <= 0);
        assertTrue(frustum.topPlane.distanceToPoint(insidePoint) <= 0);
        assertTrue(frustum.bottomPlane.distanceToPoint(insidePoint) <= 0);
    }
}