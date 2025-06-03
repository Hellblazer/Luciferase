package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Ray tracing intersection search functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class RayTracingSearchTest {

    private Octree<String> octree;
    private final byte testLevel = 15; // Higher resolution for testing

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new TreeMap<>());
        
        // Use coordinates that will map to different cubes
        // At level 15, grid size is 64, so use multiples and offsets of 64
        int gridSize = Constants.lengthAtLevel(testLevel);
        
        // Insert test data points - all with positive coordinates
        octree.insert(new Point3f(32.0f, 32.0f, 32.0f), testLevel, "Point1");           // Will go to (0,0,0)
        octree.insert(new Point3f(96.0f, 96.0f, 96.0f), testLevel, "Point2");           // Will go to (64,64,64)
        octree.insert(new Point3f(160.0f, 160.0f, 160.0f), testLevel, "Point3");        // Will go to (128,128,128)
        octree.insert(new Point3f(224.0f, 224.0f, 224.0f), testLevel, "Point4");        // Will go to (192,192,192)
        octree.insert(new Point3f(288.0f, 288.0f, 288.0f), testLevel, "Point5");        // Will go to (256,256,256)
        octree.insert(new Point3f(80.0f, 32.0f, 32.0f), testLevel, "Point6");           // Will go to (64,0,0)
        octree.insert(new Point3f(352.0f, 352.0f, 352.0f), testLevel, "Point7");        // Will go to (320,320,320)
        octree.insert(new Point3f(32.0f, 96.0f, 32.0f), testLevel, "Point8");           // Will go to (0,64,0)
    }

    @Test
    void testRayBasicCreation() {
        Point3f origin = new Point3f(10.0f, 10.0f, 10.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        Ray3D ray = new Ray3D(origin, direction);
        
        assertEquals(origin, ray.origin());
        assertEquals(1.0f, ray.direction().length(), 0.001f); // Should be normalized
    }

    @Test
    void testRayFromPoints() {
        Point3f origin = new Point3f(10.0f, 10.0f, 10.0f);
        Point3f target = new Point3f(20.0f, 10.0f, 10.0f);
        
        Ray3D ray = Ray3D.fromPoints(origin, target);
        
        assertEquals(origin, ray.origin());
        assertEquals(1.0f, ray.direction().length(), 0.001f); // Should be normalized
        assertTrue(ray.direction().x > 0); // Should point in positive X direction
    }

    @Test
    void testNegativeOriginThrowsException() {
        Point3f invalidOrigin = new Point3f(-10.0f, 10.0f, 10.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            new Ray3D(invalidOrigin, direction);
        });
    }

    @Test
    void testRayGetPointAt() {
        Point3f origin = new Point3f(10.0f, 10.0f, 10.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        Point3f pointAt5 = ray.getPointAt(5.0f);
        assertEquals(15.0f, pointAt5.x, 0.001f);
        assertEquals(10.0f, pointAt5.y, 0.001f);
        assertEquals(10.0f, pointAt5.z, 0.001f);
    }

    @Test
    void testRayIntersectingDiagonally() {
        // Ray going diagonally through space from origin
        Point3f origin = new Point3f(1.0f, 1.0f, 1.0f);
        Vector3f direction = new Vector3f(1.0f, 1.0f, 1.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        List<RayTracingSearch.RayIntersection<String>> intersections = 
            RayTracingSearch.rayIntersectedAll(ray, octree);
        
        // Should find multiple intersections along the diagonal
        assertTrue(intersections.size() > 0);
        
        // Results should be sorted by distance
        for (int i = 0; i < intersections.size() - 1; i++) {
            assertTrue(intersections.get(i).distance <= intersections.get(i + 1).distance);
        }
    }

    @Test
    void testRayIntersectingAlongAxis() {
        // Ray going along X axis
        Point3f origin = new Point3f(1.0f, 50.0f, 50.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        List<RayTracingSearch.RayIntersection<String>> intersections = 
            RayTracingSearch.rayIntersectedAll(ray, octree);
        
        // Should find intersections along the X axis
        assertTrue(intersections.size() > 0);
        
        // All intersection points should have similar Y and Z coordinates
        for (RayTracingSearch.RayIntersection<String> intersection : intersections) {
            assertTrue(intersection.intersectionPoint.y >= 50.0f - 1.0f); // Within cube bounds
            assertTrue(intersection.intersectionPoint.z >= 50.0f - 1.0f); // Within cube bounds
        }
    }

    @Test
    void testRayMissingAllCubes() {
        // Ray that doesn't intersect any cubes - need to be far outside the large cubes
        Point3f origin = new Point3f(3000000.0f, 3000000.0f, 3000000.0f);
        Vector3f direction = new Vector3f(0.0f, 0.0f, 1.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        List<RayTracingSearch.RayIntersection<String>> intersections = 
            RayTracingSearch.rayIntersectedAll(ray, octree);
        
        // Should find no intersections
        assertTrue(intersections.isEmpty());
    }

    @Test
    void testRayIntersectedFirst() {
        // Ray that should hit Point1's cube first
        Point3f origin = new Point3f(1.0f, 32.0f, 32.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        RayTracingSearch.RayIntersection<String> firstIntersection = 
            RayTracingSearch.rayIntersectedFirst(ray, octree);
        
        assertNotNull(firstIntersection);
        assertEquals("Point1", firstIntersection.content);
        assertTrue(firstIntersection.distance >= 0);
    }

    @Test
    void testRayIntersectedFirstNoHit() {
        // Ray that doesn't hit anything - use same coordinates as testRayMissingAllCubes
        Point3f origin = new Point3f(3000000.0f, 3000000.0f, 3000000.0f);
        Vector3f direction = new Vector3f(0.0f, 0.0f, 1.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        RayTracingSearch.RayIntersection<String> firstIntersection = 
            RayTracingSearch.rayIntersectedFirst(ray, octree);
        
        assertNull(firstIntersection);
    }

    @Test
    void testEmptyOctree() {
        Octree<String> emptyOctree = new Octree<>(new TreeMap<>());
        Point3f origin = new Point3f(10.0f, 10.0f, 10.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        List<RayTracingSearch.RayIntersection<String>> intersections = 
            RayTracingSearch.rayIntersectedAll(ray, emptyOctree);
        
        assertTrue(intersections.isEmpty());
        
        RayTracingSearch.RayIntersection<String> firstIntersection = 
            RayTracingSearch.rayIntersectedFirst(ray, emptyOctree);
        
        assertNull(firstIntersection);
    }

    @Test
    void testRayOriginInsideCube() {
        // Ray starting inside Point1's cube
        Point3f origin = new Point3f(32.0f, 32.0f, 32.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        List<RayTracingSearch.RayIntersection<String>> intersections = 
            RayTracingSearch.rayIntersectedAll(ray, octree);
        
        // Should still find intersections (ray starts inside one and may hit others)
        assertTrue(intersections.size() > 0);
        
        // First intersection should have distance 0 or very small (starting inside)
        assertTrue(intersections.get(0).distance >= 0);
    }

    @Test
    void testRayParallelToAxis() {
        // Ray parallel to Y axis
        Point3f origin = new Point3f(50.0f, 1.0f, 50.0f);
        Vector3f direction = new Vector3f(0.0f, 1.0f, 0.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        List<RayTracingSearch.RayIntersection<String>> intersections = 
            RayTracingSearch.rayIntersectedAll(ray, octree);
        
        // Should find intersections
        assertTrue(intersections.size() > 0);
        
        // Verify intersection points maintain X and Z coordinates
        for (RayTracingSearch.RayIntersection<String> intersection : intersections) {
            assertTrue(intersection.intersectionPoint.x >= 50.0f - 1.0f);
            assertTrue(intersection.intersectionPoint.z >= 50.0f - 1.0f);
        }
    }

    @Test
    void testDistanceOrderingCorrectness() {
        // Ray that should hit multiple cubes in a predictable order
        Point3f origin = new Point3f(1.0f, 50.0f, 50.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        List<RayTracingSearch.RayIntersection<String>> intersections = 
            RayTracingSearch.rayIntersectedAll(ray, octree);
        
        if (intersections.size() > 1) {
            // Verify strict ordering by distance
            for (int i = 0; i < intersections.size() - 1; i++) {
                assertTrue(intersections.get(i).distance <= intersections.get(i + 1).distance,
                    "Intersections not properly ordered by distance");
            }
            
            // Verify intersection points are also ordered along the ray
            for (int i = 0; i < intersections.size() - 1; i++) {
                assertTrue(intersections.get(i).intersectionPoint.x <= intersections.get(i + 1).intersectionPoint.x,
                    "Intersection points not ordered along X axis");
            }
        }
    }

    @Test
    void testRayIntersectionDataIntegrity() {
        Point3f origin = new Point3f(1.0f, 32.0f, 32.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);
        Ray3D ray = new Ray3D(origin, direction);
        
        List<RayTracingSearch.RayIntersection<String>> intersections = 
            RayTracingSearch.rayIntersectedAll(ray, octree);
        
        for (RayTracingSearch.RayIntersection<String> intersection : intersections) {
            // Verify all fields are properly set
            assertNotNull(intersection.content);
            assertNotNull(intersection.cube);
            assertNotNull(intersection.intersectionPoint);
            assertTrue(intersection.distance >= 0);
            assertTrue(intersection.index >= 0);
            
            // Verify intersection point is within the cube bounds
            Spatial.Cube cube = intersection.cube;
            Point3f point = intersection.intersectionPoint;
            assertTrue(point.x >= cube.originX() - 0.001f);
            assertTrue(point.x <= cube.originX() + cube.extent() + 0.001f);
            assertTrue(point.y >= cube.originY() - 0.001f);
            assertTrue(point.y <= cube.originY() + cube.extent() + 0.001f);
            assertTrue(point.z >= cube.originZ() - 0.001f);
            assertTrue(point.z <= cube.originZ() + cube.extent() + 0.001f);
        }
    }
}