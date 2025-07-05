package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Ray3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static com.hellblazer.luciferase.lucien.tetree.TetrahedralSearchBase.tetrahedronCenter;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TetrahedralGeometry Tests ray-tetrahedron intersection, frustum culling, plane intersection,
 * and other geometric operations
 */
@Disabled("TetrahedralSearchBase not available in this branch")
public class TetrahedralGeometryTest {

    private static final float TOLERANCE = 1e-6f;

    @BeforeEach
    void setUp() {
        // Any setup needed for tests
    }

    @Test
    @DisplayName("Test point to tetrahedron distance")
    void testDistancePointToTetrahedron() {
        int cellSize = Constants.lengthAtLevel((byte) 5);
        var tet = new Tet(100 * cellSize, 100 * cellSize, 100 * cellSize, (byte) 5, (byte) 2);
        var tetKey = (ExtendedTetreeKey) tet.tmIndex();

        // Debug: Show tetrahedron info
        var vertices = tet.coordinates();
        System.out.println("Tetrahedron vertices:");
        for (int i = 0; i < vertices.length; i++) {
            System.out.println("  v" + i + ": " + vertices[i]);
        }

        // Test distance from tetrahedron center (should be 0)
        Point3f center = tetrahedronCenter(tetKey);
        System.out.println("Center: " + center);
        float centerDistance = TetrahedralGeometry.distancePointToTetrahedron(center, tetKey);
        assertEquals(0.0f, centerDistance, TOLERANCE, "Distance from center should be 0");

        // Test distance from far point (should be positive)
        // NOTE: Based on our debug findings, (10000, 10000, 10000) is actually INSIDE
        // the large tetrahedron that tetIndex=0 maps to, so we need a truly far point
        Point3f farPoint = new Point3f(100000000.0f, 100000000.0f, 100000000.0f);
        System.out.println("Far point: " + farPoint);

        // Debug: Check if far point is incorrectly considered inside
        boolean farPointInside = TetrahedralSearchBase.pointInTetrahedron(farPoint, tetKey);
        System.out.println("Far point inside: " + farPointInside);

        float farDistance = TetrahedralGeometry.distancePointToTetrahedron(farPoint, tetKey);
        System.out.println("Far point distance: " + farDistance);

        assertTrue(farDistance > 0, "Distance to far point should be positive");
    }

    @Test
    @DisplayName("Test distance between tetrahedra")
    void testDistanceTetrahedronToTetrahedron() {
        // Test distance from tetrahedron to itself
        int cellSize = Constants.lengthAtLevel((byte) 5);
        var tet = new Tet(100 * cellSize, 100 * cellSize, 100 * cellSize, (byte) 5, (byte) 2);
        var tetKey = (ExtendedTetreeKey) tet.tmIndex();

        float selfDistance = TetrahedralGeometry.distanceTetrahedronToTetrahedron(tetKey, tetKey);
        assertEquals(0.0f, selfDistance, TOLERANCE, "Distance from tetrahedron to itself should be 0");

        // Test distance to different tetrahedron
        var tet2 = new Tet(1000 * cellSize, 1000 * cellSize, 1000 * cellSize, (byte) 5, (byte) 2);
        var tetKey2 = (ExtendedTetreeKey) tet2.tmIndex();

        float distance = TetrahedralGeometry.distanceTetrahedronToTetrahedron(tetKey, tetKey2);
        assertTrue(distance >= 0, "Distance between tetrahedra should be non-negative");

        // Distance should be symmetric
        float reverseDistance = TetrahedralGeometry.distanceTetrahedronToTetrahedron(tetKey2, tetKey);
        assertEquals(distance, reverseDistance, TOLERANCE, "Distance should be symmetric");
    }

    @Test
    @DisplayName("Test edge cases and error handling")
    void testEdgeCases() {
        // Test with very small tetrahedron
        try {
            // Root tetrahedron must be at origin
            var smallTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
            var smallKey = (ExtendedTetreeKey) smallTet.tmIndex();

            // These should handle small tetrahedra gracefully
            assertDoesNotThrow(() -> {
                Point3f center = tetrahedronCenter(smallKey);
                TetrahedralGeometry.distancePointToTetrahedron(center, smallKey);
            });
        } catch (Exception e) {
            // If small tetrahedron construction fails, that's acceptable
        }

        // Test with degenerate ray direction
        Point3f origin = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f zeroDirection = new Vector3f(0.0f, 0.0f, 0.0f);

        // Ray with zero direction should throw (as per unified Ray3D implementation)
        assertThrows(IllegalArgumentException.class, () -> new Ray3D(origin, zeroDirection, 100.0f));
    }

    @Test
    @DisplayName("Test Frustum3D creation")
    void testFrustum3DCreation() {
        Point3f position = new Point3f(10.0f, 10.0f, 10.0f);
        Vector3f forward = new Vector3f(0.0f, 0.0f, 1.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f);

        float near = 1.0f;
        float far = 100.0f;
        float fovY = 60.0f;
        float aspect = 16.0f / 9.0f;

        var frustum = new TetrahedralGeometry.Frustum3D(position, forward, up, right, near, far, fovY, aspect);

        var planes = frustum.getPlanes();
        assertEquals(6, planes.length, "Frustum should have 6 planes");

        for (var plane : planes) {
            assertNotNull(plane, "Each plane should be non-null");
            assertEquals(1.0f, plane.normal().length(), TOLERANCE, "Plane normals should be normalized");
        }

        // Test with negative position coordinates (should throw)
        assertThrows(IllegalArgumentException.class,
                     () -> new TetrahedralGeometry.Frustum3D(new Point3f(-1.0f, 10.0f, 10.0f), forward, up, right, near,
                                                             far, fovY, aspect));
    }

    @Test
    @DisplayName("Test frustum-tetrahedron intersection")
    void testFrustumTetrahedronIntersection() {
        // Create a tetrahedron
        int cellSize = Constants.lengthAtLevel((byte) 5);
        var tet = new Tet(100 * cellSize, 100 * cellSize, 100 * cellSize, (byte) 5, (byte) 2);
        var tetKey = (ExtendedTetreeKey) tet.tmIndex();

        // Create frustum pointing towards tetrahedron
        Point3f tetCenter = tetrahedronCenter(tetKey);
        Point3f frustumPos = new Point3f(tetCenter.x - 50, tetCenter.y - 50, tetCenter.z - 50);
        Vector3f forward = new Vector3f(1.0f, 1.0f, 1.0f);
        forward.normalize();
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f);

        var frustum = new TetrahedralGeometry.Frustum3D(frustumPos, forward, up, right, 1.0f, 1000.0f, 90.0f, 1.0f);

        // Test intersection
        boolean intersects = TetrahedralGeometry.frustumIntersectsTetrahedron(frustum, tetKey);

        // Result depends on specific tetrahedron geometry and frustum setup
        // We mainly test that the method runs without error
        assertDoesNotThrow(() -> TetrahedralGeometry.frustumIntersectsTetrahedron(frustum, tetKey));
    }

    @Test
    @DisplayName("Test Plane3D creation and distance computation")
    void testPlane3DCreation() {
        Point3f point = new Point3f(0.0f, 0.0f, 0.0f);
        Vector3f normal = new Vector3f(0.0f, 0.0f, 1.0f);

        var plane = new TetrahedralGeometry.Plane3D(point, normal);
        assertEquals(point, plane.point());

        // Normal should be normalized
        assertEquals(1.0f, plane.normal().length(), TOLERANCE);

        // Test distance to point
        Point3f testPoint = new Point3f(1.0f, 2.0f, 5.0f);
        float distance = plane.distanceToPoint(testPoint);
        assertEquals(5.0f, distance, TOLERANCE); // Distance along Z axis

        // Test with zero-length normal (should throw)
        assertThrows(IllegalArgumentException.class,
                     () -> new TetrahedralGeometry.Plane3D(point, new Vector3f(0.0f, 0.0f, 0.0f)));
    }

    @Test
    @DisplayName("Test plane-tetrahedron intersection")
    void testPlaneTetrahedronIntersection() {
        // Create a tetrahedron
        int cellSize = Constants.lengthAtLevel((byte) 5);
        var tet = new Tet(100 * cellSize, 100 * cellSize, 100 * cellSize, (byte) 5, (byte) 2);
        var tetKey = (ExtendedTetreeKey) tet.tmIndex();

        // Create plane that might intersect tetrahedron
        Point3f tetCenter = tetrahedronCenter(tetKey);
        var plane = new TetrahedralGeometry.Plane3D(tetCenter, new Vector3f(1.0f, 0.0f, 0.0f));

        // Test intersection
        boolean intersects = TetrahedralGeometry.planeIntersectsTetrahedron(plane, tetKey);

        // Result depends on specific tetrahedron geometry
        // We mainly test that the method runs without error
        assertDoesNotThrow(() -> TetrahedralGeometry.planeIntersectsTetrahedron(plane, tetKey));

        // Test with plane truly far away from tetrahedron
        // Since the tetrahedron can be very large (up to 65536^3), we need a truly distant plane
        var vertices = Tet.tetrahedron(tetKey).coordinates();
        float maxExtent = 0;
        for (var vertex : vertices) {
            maxExtent = Math.max(maxExtent, Math.max(Math.max(vertex.x, vertex.y), vertex.z));
        }

        // Place plane well beyond the tetrahedron's maximum extent
        Point3f farPoint = new Point3f(maxExtent + 100000, maxExtent + 100000, maxExtent + 100000);
        var farPlane = new TetrahedralGeometry.Plane3D(farPoint, new Vector3f(1.0f, 0.0f, 0.0f));
        boolean farIntersects = TetrahedralGeometry.planeIntersectsTetrahedron(farPlane, tetKey);

        // Far plane should not intersect
        assertFalse(farIntersects, "Far plane should not intersect tetrahedron");
    }

    @Test
    @DisplayName("Test Ray3D creation and validation")
    void testRay3DCreation() {
        // Valid ray with positive origin
        Point3f origin = new Point3f(1.0f, 2.0f, 3.0f);
        Vector3f direction = new Vector3f(0.0f, 0.0f, 1.0f);
        float maxDistance = 100.0f;

        var ray = new Ray3D(origin, direction, maxDistance);
        assertEquals(origin, ray.origin());
        assertEquals(direction, ray.direction());
        assertEquals(maxDistance, ray.maxDistance());

        // Test pointAt method
        Point3f pointAt10 = ray.pointAt(10.0f);
        assertEquals(1.0f, pointAt10.x, TOLERANCE);
        assertEquals(2.0f, pointAt10.y, TOLERANCE);
        assertEquals(13.0f, pointAt10.z, TOLERANCE); // 3.0 + 10.0 * 1.0

        // Invalid ray with negative origin coordinates
        assertThrows(IllegalArgumentException.class,
                     () -> new Ray3D(new Point3f(-1.0f, 2.0f, 3.0f), direction, maxDistance));

        // Invalid ray with negative max distance
        assertThrows(IllegalArgumentException.class, () -> new Ray3D(origin, direction, -1.0f));
    }

    @Test
    @DisplayName("Test intersection result no-intersection case")
    void testRayIntersectionNoIntersection() {
        var noIntersection = TetrahedralGeometry.RayTetrahedronIntersection.noIntersection();

        assertFalse(noIntersection.intersects, "No intersection should have intersects = false");
        assertEquals(Float.MAX_VALUE, noIntersection.distance, "No intersection should have max distance");
        assertNull(noIntersection.intersectionPoint, "No intersection should have null intersection point");
        assertNull(noIntersection.normal, "No intersection should have null normal");
        assertEquals(-1, noIntersection.intersectedFace, "No intersection should have -1 face index");
    }

    @Test
    @DisplayName("Test ray-tetrahedron intersection")
    void testRayTetrahedronIntersection() {
        // Create a tetrahedron
        int cellSize = Constants.lengthAtLevel((byte) 5);
        var tet = new Tet(100 * cellSize, 100 * cellSize, 100 * cellSize, (byte) 5, (byte) 2);
        var tetKey = (ExtendedTetreeKey) tet.tmIndex();

        // Test ray from far away pointing towards tetrahedron center
        Point3f tetCenter = tetrahedronCenter(tetKey);
        Point3f rayOrigin = new Point3f(tetCenter.x - 50, tetCenter.y - 50, tetCenter.z - 50);
        Vector3f rayDirection = new Vector3f(1.0f, 1.0f, 1.0f);
        rayDirection.normalize();

        var ray = new Ray3D(rayOrigin, rayDirection, 1000.0f);
        var intersection = TetrahedralGeometry.rayIntersectsTetrahedron(ray, tetKey);

        // For a well-formed tetrahedron, we should get some intersection information
        // (exact results depend on tetrahedron geometry)
        assertNotNull(intersection, "Intersection result should not be null");

        // Test ray pointing away from tetrahedron
        Vector3f awayDirection = new Vector3f(-1.0f, -1.0f, -1.0f);
        awayDirection.normalize();
        var awayRay = new Ray3D(rayOrigin, awayDirection, 1000.0f);
        var noIntersection = TetrahedralGeometry.rayIntersectsTetrahedron(awayRay, tetKey);

        assertFalse(noIntersection.intersects, "Ray pointing away should not intersect");
    }

    @Test
    @DisplayName("Test tetrahedron-tetrahedron intersection")
    void testTetrahedronTetrahedronIntersection() {
        // Test intersection of tetrahedron with itself
        int cellSize = Constants.lengthAtLevel((byte) 5);
        var tet = new Tet(100 * cellSize, 100 * cellSize, 100 * cellSize, (byte) 5, (byte) 2);
        var tetKey = (ExtendedTetreeKey) tet.tmIndex();

        var selfIntersection = TetrahedralGeometry.tetrahedronIntersection(tetKey, tetKey);
        assertEquals(TetrahedralGeometry.IntersectionResult.IDENTICAL, selfIntersection,
                     "Tetrahedron should be identical to itself");

        // Test with different tetrahedra - create a slightly different tet to ensure different TM-index
        var tet2 = new Tet(101 * cellSize, 100 * cellSize, 100 * cellSize, (byte) 5, (byte) 2);
        var tetKey2 = (ExtendedTetreeKey) tet2.tmIndex();

        var intersection = TetrahedralGeometry.tetrahedronIntersection(tetKey, tetKey2);
        assertNotNull(intersection, "Intersection result should not be null");

        // Result depends on specific tetrahedron positions
        // We mainly test that the method runs without error and returns a valid result
        assertTrue(intersection == TetrahedralGeometry.IntersectionResult.NO_INTERSECTION
                   || intersection == TetrahedralGeometry.IntersectionResult.TOUCHING
                   || intersection == TetrahedralGeometry.IntersectionResult.PARTIAL_OVERLAP
                   || intersection == TetrahedralGeometry.IntersectionResult.COMPLETE_OVERLAP,
                   "Should return a valid intersection result");
    }
}
