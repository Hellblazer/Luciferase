package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Plane intersection search functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class PlaneIntersectionSearchTest {

    private Octree<String> octree;
    private final byte testLevel = 15;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new TreeMap<>());
        
        // Use coordinates that will map to different cubes - all positive
        int gridSize = Constants.lengthAtLevel(testLevel);
        
        // Insert test data points in a grid pattern
        octree.insert(new Point3f(32.0f, 32.0f, 32.0f), testLevel, "Point1");           // Low corner
        octree.insert(new Point3f(96.0f, 96.0f, 96.0f), testLevel, "Point2");           // Mid area
        octree.insert(new Point3f(160.0f, 160.0f, 160.0f), testLevel, "Point3");        // Higher area
        octree.insert(new Point3f(224.0f, 224.0f, 224.0f), testLevel, "Point4");        // Even higher
        octree.insert(new Point3f(288.0f, 288.0f, 288.0f), testLevel, "Point5");        // Top area
        octree.insert(new Point3f(80.0f, 32.0f, 32.0f), testLevel, "Point6");           // X-axis variation
        octree.insert(new Point3f(32.0f, 80.0f, 32.0f), testLevel, "Point7");           // Y-axis variation
        octree.insert(new Point3f(32.0f, 32.0f, 80.0f), testLevel, "Point8");           // Z-axis variation
    }

    @Test
    void testPlaneIntersectedAllWithXYPlane() {
        // Create a plane parallel to XY at z = 100 (should intersect some cubes)
        Plane3D plane = Plane3D.parallelToXY(100.0f);
        Point3f referencePoint = new Point3f(50.0f, 50.0f, 50.0f);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> intersections = 
            PlaneIntersectionSearch.planeIntersectedAll(plane, octree, referencePoint);
        
        // Should find some intersections
        assertTrue(intersections.size() > 0);
        
        // Results should be sorted by distance from reference point
        for (int i = 0; i < intersections.size() - 1; i++) {
            assertTrue(intersections.get(i).distanceToReferencePoint <= 
                      intersections.get(i + 1).distanceToReferencePoint);
        }
        
        // All intersections should have valid data
        for (var intersection : intersections) {
            assertNotNull(intersection.content);
            assertNotNull(intersection.cube);
            assertNotNull(intersection.cubeCenter);
            assertTrue(intersection.distanceToReferencePoint >= 0);
            assertTrue(intersection.index >= 0);
            
            // Verify the plane actually intersects this cube
            assertTrue(plane.intersectsCube(intersection.cube));
        }
    }

    @Test
    void testPlaneIntersectedFirst() {
        Plane3D plane = Plane3D.parallelToXZ(150.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        PlaneIntersectionSearch.PlaneIntersection<String> firstIntersection = 
            PlaneIntersectionSearch.planeIntersectedFirst(plane, octree, referencePoint);
        
        if (firstIntersection != null) {
            // Should be the closest intersection
            List<PlaneIntersectionSearch.PlaneIntersection<String>> allIntersections = 
                PlaneIntersectionSearch.planeIntersectedAll(plane, octree, referencePoint);
            
            assertFalse(allIntersections.isEmpty());
            assertEquals(allIntersections.get(0).content, firstIntersection.content);
            assertEquals(allIntersections.get(0).distanceToReferencePoint, 
                        firstIntersection.distanceToReferencePoint, 0.001f);
        }
    }

    @Test
    void testPlaneIntersectedAllByPlaneDistance() {
        // Create a diagonal plane
        Point3f p1 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f p2 = new Point3f(200.0f, 100.0f, 200.0f);
        Point3f p3 = new Point3f(100.0f, 200.0f, 200.0f);
        Plane3D plane = Plane3D.fromThreePoints(p1, p2, p3);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> intersections = 
            PlaneIntersectionSearch.planeIntersectedAllByPlaneDistance(plane, octree);
        
        // Results should be sorted by distance from plane
        for (int i = 0; i < intersections.size() - 1; i++) {
            assertTrue(intersections.get(i).distanceToReferencePoint <= 
                      intersections.get(i + 1).distanceToReferencePoint);
        }
        
        // Distance should represent plane distance, not reference point distance
        for (var intersection : intersections) {
            float expectedPlaneDistance = Math.abs(plane.distanceToPoint(intersection.cubeCenter));
            assertEquals(expectedPlaneDistance, intersection.distanceToReferencePoint, 0.001f);
        }
    }

    @Test
    void testCubesOnPositiveSide() {
        Plane3D plane = Plane3D.parallelToXY(120.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> positiveSide = 
            PlaneIntersectionSearch.cubesOnPositiveSide(plane, octree, referencePoint);
        
        // All cubes should be on positive side (z > 120)
        for (var intersection : positiveSide) {
            float distanceToPlane = plane.distanceToPoint(intersection.cubeCenter);
            assertTrue(distanceToPlane > 0, "Cube should be on positive side of plane");
        }
        
        // Results should be sorted by distance from reference point
        for (int i = 0; i < positiveSide.size() - 1; i++) {
            assertTrue(positiveSide.get(i).distanceToReferencePoint <= 
                      positiveSide.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testCubesOnNegativeSide() {
        Plane3D plane = Plane3D.parallelToXY(120.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> negativeSide = 
            PlaneIntersectionSearch.cubesOnNegativeSide(plane, octree, referencePoint);
        
        // All cubes should be on negative side (z < 120)
        for (var intersection : negativeSide) {
            float distanceToPlane = plane.distanceToPoint(intersection.cubeCenter);
            assertTrue(distanceToPlane < 0, "Cube should be on negative side of plane");
        }
        
        // Results should be sorted by distance from reference point
        for (int i = 0; i < negativeSide.size() - 1; i++) {
            assertTrue(negativeSide.get(i).distanceToReferencePoint <= 
                      negativeSide.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testCountPlaneIntersections() {
        Plane3D plane = Plane3D.parallelToXY(150.0f);
        
        long count = PlaneIntersectionSearch.countPlaneIntersections(plane, octree);
        
        // Count should match the number from planeIntersectedAll
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        List<PlaneIntersectionSearch.PlaneIntersection<String>> intersections = 
            PlaneIntersectionSearch.planeIntersectedAll(plane, octree, referencePoint);
        
        assertEquals(intersections.size(), count);
        assertTrue(count >= 0);
    }

    @Test
    void testHasAnyIntersection() {
        // Plane that should intersect some cubes
        Plane3D intersectingPlane = Plane3D.parallelToXY(100.0f);
        assertTrue(PlaneIntersectionSearch.hasAnyIntersection(intersectingPlane, octree));
        
        // Plane that should not intersect any cubes (far away)
        Plane3D nonIntersectingPlane = Plane3D.parallelToXY(100000000.0f);
        assertFalse(PlaneIntersectionSearch.hasAnyIntersection(nonIntersectingPlane, octree));
    }

    @Test
    void testEmptyOctree() {
        Octree<String> emptyOctree = new Octree<>(new TreeMap<>());
        Plane3D plane = Plane3D.parallelToXY(100.0f);
        Point3f referencePoint = new Point3f(50.0f, 50.0f, 50.0f);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> intersections = 
            PlaneIntersectionSearch.planeIntersectedAll(plane, emptyOctree, referencePoint);
        
        assertTrue(intersections.isEmpty());
        
        PlaneIntersectionSearch.PlaneIntersection<String> first = 
            PlaneIntersectionSearch.planeIntersectedFirst(plane, emptyOctree, referencePoint);
        
        assertNull(first);
        
        assertEquals(0, PlaneIntersectionSearch.countPlaneIntersections(plane, emptyOctree));
        assertFalse(PlaneIntersectionSearch.hasAnyIntersection(plane, emptyOctree));
    }

    @Test
    void testNegativeReferencePointThrowsException() {
        Plane3D plane = Plane3D.parallelToXY(100.0f);
        Point3f negativeReferencePoint = new Point3f(-50.0f, 100.0f, 100.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            PlaneIntersectionSearch.planeIntersectedAll(plane, octree, negativeReferencePoint);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            PlaneIntersectionSearch.planeIntersectedFirst(plane, octree, negativeReferencePoint);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            PlaneIntersectionSearch.cubesOnPositiveSide(plane, octree, negativeReferencePoint);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            PlaneIntersectionSearch.cubesOnNegativeSide(plane, octree, negativeReferencePoint);
        });
    }

    @Test
    void testComplexPlaneIntersection() {
        // Create a plane that cuts diagonally through the octree
        Point3f p1 = new Point3f(50.0f, 50.0f, 50.0f);
        Point3f p2 = new Point3f(150.0f, 50.0f, 150.0f);
        Point3f p3 = new Point3f(50.0f, 150.0f, 150.0f);
        
        Plane3D diagonalPlane = Plane3D.fromThreePoints(p1, p2, p3);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> intersections = 
            PlaneIntersectionSearch.planeIntersectedAll(diagonalPlane, octree, referencePoint);
        
        // Should find some intersections
        assertTrue(intersections.size() > 0);
        
        // Verify each intersection
        for (var intersection : intersections) {
            assertTrue(diagonalPlane.intersectsCube(intersection.cube));
            assertTrue(intersection.distanceToReferencePoint >= 0);
        }
        
        // Test positive and negative sides
        List<PlaneIntersectionSearch.PlaneIntersection<String>> positiveSide = 
            PlaneIntersectionSearch.cubesOnPositiveSide(diagonalPlane, octree, referencePoint);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> negativeSide = 
            PlaneIntersectionSearch.cubesOnNegativeSide(diagonalPlane, octree, referencePoint);
        
        // Total cubes should equal positive + negative + intersecting
        // (intersecting cubes might be on either side depending on their center)
        long totalCubes = octree.getMap().size();
        long accountedCubes = positiveSide.size() + negativeSide.size();
        
        assertTrue(accountedCubes <= totalCubes);
    }

    @Test
    void testDistanceOrdering() {
        Plane3D plane = Plane3D.parallelToYZ(100.0f);
        Point3f nearReference = new Point3f(50.0f, 50.0f, 50.0f);
        Point3f farReference = new Point3f(500.0f, 500.0f, 500.0f);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> nearIntersections = 
            PlaneIntersectionSearch.planeIntersectedAll(plane, octree, nearReference);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> farIntersections = 
            PlaneIntersectionSearch.planeIntersectedAll(plane, octree, farReference);
        
        // Same intersecting cubes but different ordering due to different reference points
        if (!nearIntersections.isEmpty() && !farIntersections.isEmpty()) {
            // Verify distance ordering within each result set
            for (int i = 0; i < nearIntersections.size() - 1; i++) {
                assertTrue(nearIntersections.get(i).distanceToReferencePoint <= 
                          nearIntersections.get(i + 1).distanceToReferencePoint);
            }
            
            for (int i = 0; i < farIntersections.size() - 1; i++) {
                assertTrue(farIntersections.get(i).distanceToReferencePoint <= 
                          farIntersections.get(i + 1).distanceToReferencePoint);
            }
        }
    }

    @Test
    void testPlaneIntersectionDataIntegrity() {
        Plane3D plane = Plane3D.parallelToXY(100.0f);
        Point3f referencePoint = new Point3f(75.0f, 75.0f, 75.0f);
        
        List<PlaneIntersectionSearch.PlaneIntersection<String>> intersections = 
            PlaneIntersectionSearch.planeIntersectedAll(plane, octree, referencePoint);
        
        for (var intersection : intersections) {
            // Verify all fields are properly set
            assertNotNull(intersection.content);
            assertNotNull(intersection.cube);
            assertNotNull(intersection.cubeCenter);
            assertTrue(intersection.distanceToReferencePoint >= 0);
            assertTrue(intersection.index >= 0);
            
            // Verify cube center is within cube bounds
            Spatial.Cube cube = intersection.cube;
            Point3f center = intersection.cubeCenter;
            assertTrue(center.x >= cube.originX());
            assertTrue(center.x <= cube.originX() + cube.extent());
            assertTrue(center.y >= cube.originY());
            assertTrue(center.y <= cube.originY() + cube.extent());
            assertTrue(center.z >= cube.originZ());
            assertTrue(center.z <= cube.originZ() + cube.extent());
            
            // Verify distance calculation
            float dx = referencePoint.x - center.x;
            float dy = referencePoint.y - center.y;
            float dz = referencePoint.z - center.z;
            float expectedDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(expectedDistance, intersection.distanceToReferencePoint, 0.001f);
        }
    }
}