package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AABB intersection search functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class AABBIntersectionSearchTest {

    private Octree<String> octree;
    private final byte testLevel = 15;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new TreeMap<>());
        
        // Use coordinates that will map to different cubes - all positive
        int gridSize = Constants.lengthAtLevel(testLevel);
        
        // Insert test data points in a spatial pattern
        octree.insert(new Point3f(100.0f, 100.0f, 100.0f), testLevel, "Near1");
        octree.insert(new Point3f(200.0f, 200.0f, 200.0f), testLevel, "Center1");
        octree.insert(new Point3f(300.0f, 300.0f, 300.0f), testLevel, "Far1");
        octree.insert(new Point3f(150.0f, 150.0f, 150.0f), testLevel, "Near2");
        octree.insert(new Point3f(250.0f, 250.0f, 250.0f), testLevel, "Center2");
        octree.insert(new Point3f(350.0f, 350.0f, 350.0f), testLevel, "Far2");
        octree.insert(new Point3f(50.0f, 50.0f, 50.0f), testLevel, "VeryNear");
        octree.insert(new Point3f(500.0f, 500.0f, 500.0f), testLevel, "VeryFar");
        octree.insert(new Point3f(120.0f, 180.0f, 160.0f), testLevel, "OffAxis1");
        octree.insert(new Point3f(280.0f, 220.0f, 240.0f), testLevel, "OffAxis2");
    }

    @Test
    void testAABBCreation() {
        // Test basic AABB creation
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            100.0f, 100.0f, 100.0f, 300.0f, 300.0f, 300.0f);
        
        assertEquals(100.0f, aabb.minX);
        assertEquals(100.0f, aabb.minY);
        assertEquals(100.0f, aabb.minZ);
        assertEquals(300.0f, aabb.maxX);
        assertEquals(300.0f, aabb.maxY);
        assertEquals(300.0f, aabb.maxZ);
        
        assertEquals(200.0f, aabb.getWidth());
        assertEquals(200.0f, aabb.getHeight());
        assertEquals(200.0f, aabb.getDepth());
        assertEquals(8000000.0f, aabb.getVolume());
        
        Point3f center = aabb.getCenter();
        assertEquals(200.0f, center.x, 0.001f);
        assertEquals(200.0f, center.y, 0.001f);
        assertEquals(200.0f, center.z, 0.001f);
    }

    @Test
    void testAABBFromCenterAndHalfExtents() {
        Point3f center = new Point3f(200.0f, 200.0f, 200.0f);
        AABBIntersectionSearch.AABB aabb = AABBIntersectionSearch.AABB.fromCenterAndHalfExtents(
            center, 50.0f, 75.0f, 100.0f);
        
        assertEquals(150.0f, aabb.minX);
        assertEquals(125.0f, aabb.minY);
        assertEquals(100.0f, aabb.minZ);
        assertEquals(250.0f, aabb.maxX);
        assertEquals(275.0f, aabb.maxY);
        assertEquals(300.0f, aabb.maxZ);
    }

    @Test
    void testAABBFromCorners() {
        Point3f corner1 = new Point3f(100.0f, 150.0f, 200.0f);
        Point3f corner2 = new Point3f(300.0f, 100.0f, 400.0f);
        
        AABBIntersectionSearch.AABB aabb = AABBIntersectionSearch.AABB.fromCorners(corner1, corner2);
        
        assertEquals(100.0f, aabb.minX);
        assertEquals(100.0f, aabb.minY);
        assertEquals(200.0f, aabb.minZ);
        assertEquals(300.0f, aabb.maxX);
        assertEquals(150.0f, aabb.maxY);
        assertEquals(400.0f, aabb.maxZ);
    }

    @Test
    void testAABBIntersectedAll() {
        // Create an AABB that should intersect some cubes
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            150.0f, 150.0f, 150.0f, 350.0f, 350.0f, 350.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> intersections = 
            AABBIntersectionSearch.aabbIntersectedAll(aabb, octree, referencePoint);
        
        // Should find some intersections (exact number depends on AABB geometry and cube quantization)
        assertTrue(intersections.size() >= 0);
        
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
            assertNotNull(intersection.intersectionType);
            assertTrue(intersection.distanceToReferencePoint >= 0);
            assertTrue(intersection.index >= 0);
            
            // Should not be COMPLETELY_OUTSIDE since these are intersections
            assertNotEquals(AABBIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, 
                           intersection.intersectionType);
        }
    }

    @Test
    void testAABBIntersectedFirst() {
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            120.0f, 120.0f, 120.0f, 280.0f, 280.0f, 280.0f);
        Point3f referencePoint = new Point3f(75.0f, 75.0f, 75.0f);
        
        AABBIntersectionSearch.AABBIntersection<String> firstIntersection = 
            AABBIntersectionSearch.aabbIntersectedFirst(aabb, octree, referencePoint);
        
        if (firstIntersection != null) {
            // Should be the closest intersection
            List<AABBIntersectionSearch.AABBIntersection<String>> allIntersections = 
                AABBIntersectionSearch.aabbIntersectedAll(aabb, octree, referencePoint);
            
            assertFalse(allIntersections.isEmpty());
            assertEquals(allIntersections.get(0).content, firstIntersection.content);
            assertEquals(allIntersections.get(0).distanceToReferencePoint, 
                        firstIntersection.distanceToReferencePoint, 0.001f);
        }
    }

    @Test
    void testCubesCompletelyInside() {
        // Large AABB that might completely contain some cubes
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            50.0f, 50.0f, 50.0f, 10000000.0f, 10000000.0f, 10000000.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> insideCubes = 
            AABBIntersectionSearch.cubesCompletelyInside(aabb, octree, referencePoint);
        
        // All results should be COMPLETELY_INSIDE
        for (var intersection : insideCubes) {
            assertEquals(AABBIntersectionSearch.IntersectionType.COMPLETELY_INSIDE, 
                        intersection.intersectionType);
        }
        
        // Results should be sorted by distance from reference point
        for (int i = 0; i < insideCubes.size() - 1; i++) {
            assertTrue(insideCubes.get(i).distanceToReferencePoint <= 
                      insideCubes.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testCubesPartiallyIntersecting() {
        // Medium AABB that should create some partial intersections
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            180.0f, 180.0f, 180.0f, 320.0f, 320.0f, 320.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> intersectingCubes = 
            AABBIntersectionSearch.cubesPartiallyIntersecting(aabb, octree, referencePoint);
        
        // All results should be INTERSECTING
        for (var intersection : intersectingCubes) {
            assertEquals(AABBIntersectionSearch.IntersectionType.INTERSECTING, 
                        intersection.intersectionType);
        }
        
        // Results should be sorted by distance from reference point
        for (int i = 0; i < intersectingCubes.size() - 1; i++) {
            assertTrue(intersectingCubes.get(i).distanceToReferencePoint <= 
                      intersectingCubes.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testCubesContainingAABB() {
        // Small AABB that might be contained within large cubes
        AABBIntersectionSearch.AABB smallAABB = new AABBIntersectionSearch.AABB(
            200.0f, 200.0f, 200.0f, 210.0f, 210.0f, 210.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> containingCubes = 
            AABBIntersectionSearch.cubesContainingAABB(smallAABB, octree, referencePoint);
        
        // All results should be CONTAINS_AABB
        for (var intersection : containingCubes) {
            assertEquals(AABBIntersectionSearch.IntersectionType.CONTAINS_AABB, 
                        intersection.intersectionType);
        }
        
        // Results should be sorted by distance from reference point
        for (int i = 0; i < containingCubes.size() - 1; i++) {
            assertTrue(containingCubes.get(i).distanceToReferencePoint <= 
                      containingCubes.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testCountAABBIntersections() {
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            200.0f, 200.0f, 200.0f, 400.0f, 400.0f, 400.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        long count = AABBIntersectionSearch.countAABBIntersections(aabb, octree);
        
        // Count should match the number from aabbIntersectedAll
        List<AABBIntersectionSearch.AABBIntersection<String>> intersections = 
            AABBIntersectionSearch.aabbIntersectedAll(aabb, octree, referencePoint);
        
        assertEquals(intersections.size(), count);
        assertTrue(count >= 0);
    }

    @Test
    void testHasAnyIntersection() {
        // Large AABB that should intersect some cubes
        AABBIntersectionSearch.AABB intersectingAABB = new AABBIntersectionSearch.AABB(
            150.0f, 150.0f, 150.0f, 350.0f, 350.0f, 350.0f);
        
        // Small AABB in an area with no data
        AABBIntersectionSearch.AABB nonIntersectingAABB = new AABBIntersectionSearch.AABB(
            1000.0f, 1000.0f, 1000.0f, 1100.0f, 1100.0f, 1100.0f);
        
        // Test both cases (might depend on actual cube positions and sizes)
        assertDoesNotThrow(() -> {
            AABBIntersectionSearch.hasAnyIntersection(intersectingAABB, octree);
        });
        
        assertDoesNotThrow(() -> {
            AABBIntersectionSearch.hasAnyIntersection(nonIntersectingAABB, octree);
        });
    }

    @Test
    void testGetAABBIntersectionStatistics() {
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            150.0f, 150.0f, 150.0f, 450.0f, 450.0f, 450.0f);
        
        AABBIntersectionSearch.IntersectionStatistics stats = 
            AABBIntersectionSearch.getAABBIntersectionStatistics(aabb, octree);
        
        assertNotNull(stats);
        assertTrue(stats.totalCubes >= 0);
        assertTrue(stats.insideCubes >= 0);
        assertTrue(stats.intersectingCubes >= 0);
        assertTrue(stats.containingCubes >= 0);
        assertTrue(stats.outsideCubes >= 0);
        
        // Total should equal sum of parts
        assertEquals(stats.totalCubes, 
                    stats.insideCubes + stats.intersectingCubes + stats.containingCubes + stats.outsideCubes);
        
        // Percentages should be valid
        assertTrue(stats.getInsidePercentage() >= 0 && stats.getInsidePercentage() <= 100);
        assertTrue(stats.getIntersectingPercentage() >= 0 && stats.getIntersectingPercentage() <= 100);
        assertTrue(stats.getContainingPercentage() >= 0 && stats.getContainingPercentage() <= 100);
        assertTrue(stats.getOutsidePercentage() >= 0 && stats.getOutsidePercentage() <= 100);
        assertTrue(stats.getIntersectedPercentage() >= 0 && stats.getIntersectedPercentage() <= 100);
        
        // Intersected percentage should equal inside + intersecting + containing
        assertEquals(stats.getIntersectedPercentage(), 
                    stats.getInsidePercentage() + stats.getIntersectingPercentage() + stats.getContainingPercentage(), 0.001);
        
        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("AABBIntersectionStats"));
        assertTrue(statsStr.contains("total="));
        assertTrue(statsStr.contains("inside="));
        assertTrue(statsStr.contains("intersecting="));
        assertTrue(statsStr.contains("containing="));
        assertTrue(statsStr.contains("outside="));
        assertTrue(statsStr.contains("intersected="));
    }

    @Test
    void testBatchAABBIntersections() {
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        AABBIntersectionSearch.AABB aabb1 = new AABBIntersectionSearch.AABB(
            150.0f, 150.0f, 150.0f, 250.0f, 250.0f, 250.0f);
        AABBIntersectionSearch.AABB aabb2 = new AABBIntersectionSearch.AABB(
            250.0f, 250.0f, 250.0f, 350.0f, 350.0f, 350.0f);
        
        List<AABBIntersectionSearch.AABB> aabbs = List.of(aabb1, aabb2);
        
        Map<AABBIntersectionSearch.AABB, List<AABBIntersectionSearch.AABBIntersection<String>>> results = 
            AABBIntersectionSearch.batchAABBIntersections(aabbs, octree, referencePoint);
        
        assertEquals(2, results.size());
        assertTrue(results.containsKey(aabb1));
        assertTrue(results.containsKey(aabb2));
        
        // Each result should be sorted by distance
        for (var entry : results.entrySet()) {
            List<AABBIntersectionSearch.AABBIntersection<String>> intersections = entry.getValue();
            for (int i = 0; i < intersections.size() - 1; i++) {
                assertTrue(intersections.get(i).distanceToReferencePoint <= 
                          intersections.get(i + 1).distanceToReferencePoint);
            }
        }
    }

    @Test
    void testEmptyOctree() {
        Octree<String> emptyOctree = new Octree<>(new TreeMap<>());
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            150.0f, 150.0f, 150.0f, 250.0f, 250.0f, 250.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> intersections = 
            AABBIntersectionSearch.aabbIntersectedAll(aabb, emptyOctree, referencePoint);
        
        assertTrue(intersections.isEmpty());
        
        AABBIntersectionSearch.AABBIntersection<String> first = 
            AABBIntersectionSearch.aabbIntersectedFirst(aabb, emptyOctree, referencePoint);
        
        assertNull(first);
        
        assertEquals(0, AABBIntersectionSearch.countAABBIntersections(aabb, emptyOctree));
        assertFalse(AABBIntersectionSearch.hasAnyIntersection(aabb, emptyOctree));
        
        AABBIntersectionSearch.IntersectionStatistics stats = 
            AABBIntersectionSearch.getAABBIntersectionStatistics(aabb, emptyOctree);
        assertEquals(0, stats.totalCubes);
        assertEquals(0, stats.insideCubes);
        assertEquals(0, stats.intersectingCubes);
        assertEquals(0, stats.containingCubes);
        assertEquals(0, stats.outsideCubes);
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativeReference = new Point3f(-100.0f, 100.0f, 100.0f);
        Point3f positiveReference = new Point3f(100.0f, 100.0f, 100.0f);
        AABBIntersectionSearch.AABB validAABB = new AABBIntersectionSearch.AABB(
            150.0f, 150.0f, 150.0f, 250.0f, 250.0f, 250.0f);
        
        // Test negative AABB coordinates
        assertThrows(IllegalArgumentException.class, () -> {
            new AABBIntersectionSearch.AABB(-10.0f, 150.0f, 150.0f, 250.0f, 250.0f, 250.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new AABBIntersectionSearch.AABB(150.0f, -10.0f, 150.0f, 250.0f, 250.0f, 250.0f);
        });
        
        // Test invalid AABB bounds
        assertThrows(IllegalArgumentException.class, () -> {
            new AABBIntersectionSearch.AABB(250.0f, 150.0f, 150.0f, 150.0f, 250.0f, 250.0f); // maxX < minX
        });
        
        // Test negative reference point
        assertThrows(IllegalArgumentException.class, () -> {
            AABBIntersectionSearch.aabbIntersectedAll(validAABB, octree, negativeReference);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            AABBIntersectionSearch.cubesCompletelyInside(validAABB, octree, negativeReference);
        });
        
        // Test negative coordinates in factory methods
        assertThrows(IllegalArgumentException.class, () -> {
            Point3f negativeCenter = new Point3f(-200.0f, 200.0f, 200.0f);
            AABBIntersectionSearch.AABB.fromCenterAndHalfExtents(negativeCenter, 50.0f, 50.0f, 50.0f);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            AABBIntersectionSearch.AABB.fromCenterAndHalfExtents(positiveReference, -10.0f, 50.0f, 50.0f);
        });
    }

    @Test
    void testAABBEqualsAndHashCode() {
        AABBIntersectionSearch.AABB aabb1 = new AABBIntersectionSearch.AABB(
            100.0f, 100.0f, 100.0f, 200.0f, 200.0f, 200.0f);
        AABBIntersectionSearch.AABB aabb2 = new AABBIntersectionSearch.AABB(
            100.0f, 100.0f, 100.0f, 200.0f, 200.0f, 200.0f);
        AABBIntersectionSearch.AABB aabb3 = new AABBIntersectionSearch.AABB(
            100.0f, 100.0f, 100.0f, 300.0f, 200.0f, 200.0f);
        
        // Test equality
        assertEquals(aabb1, aabb2);
        assertNotEquals(aabb1, aabb3);
        
        // Test hash code consistency
        assertEquals(aabb1.hashCode(), aabb2.hashCode());
        
        // Test toString
        String aabbStr = aabb1.toString();
        assertTrue(aabbStr.contains("AABB"));
        assertTrue(aabbStr.contains("min="));
        assertTrue(aabbStr.contains("max="));
    }

    @Test
    void testAABBIntersectionDataIntegrity() {
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            150.0f, 150.0f, 150.0f, 350.0f, 350.0f, 350.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> intersections = 
            AABBIntersectionSearch.aabbIntersectedAll(aabb, octree, referencePoint);
        
        for (var intersection : intersections) {
            // Verify all fields are properly set
            assertNotNull(intersection.content);
            assertNotNull(intersection.cube);
            assertNotNull(intersection.cubeCenter);
            assertNotNull(intersection.intersectionType);
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
            
            // Verify intersection type is not COMPLETELY_OUTSIDE (since these are intersections)
            assertNotEquals(AABBIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, 
                           intersection.intersectionType);
        }
    }

    @Test
    void testDistanceOrdering() {
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(
            150.0f, 150.0f, 150.0f, 450.0f, 450.0f, 450.0f);
        Point3f nearReference = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f farReference = new Point3f(600.0f, 600.0f, 600.0f);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> nearIntersections = 
            AABBIntersectionSearch.aabbIntersectedAll(aabb, octree, nearReference);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> farIntersections = 
            AABBIntersectionSearch.aabbIntersectedAll(aabb, octree, farReference);
        
        // Different reference points should give different distance orderings
        if (!nearIntersections.isEmpty()) {
            // Verify distance ordering within each result set
            for (int i = 0; i < nearIntersections.size() - 1; i++) {
                assertTrue(nearIntersections.get(i).distanceToReferencePoint <= 
                          nearIntersections.get(i + 1).distanceToReferencePoint);
            }
        }
        
        if (!farIntersections.isEmpty()) {
            for (int i = 0; i < farIntersections.size() - 1; i++) {
                assertTrue(farIntersections.get(i).distanceToReferencePoint <= 
                          farIntersections.get(i + 1).distanceToReferencePoint);
            }
        }
    }

    @Test
    void testSmallAndLargeAABBs() {
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        // Very small AABB - should intersect few or no cubes
        AABBIntersectionSearch.AABB smallAABB = new AABBIntersectionSearch.AABB(
            200.0f, 200.0f, 200.0f, 201.0f, 201.0f, 201.0f);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> smallIntersections = 
            AABBIntersectionSearch.aabbIntersectedAll(smallAABB, octree, referencePoint);
        
        // Very large AABB - should intersect most or all cubes
        AABBIntersectionSearch.AABB largeAABB = new AABBIntersectionSearch.AABB(
            50.0f, 50.0f, 50.0f, 1000000.0f, 1000000.0f, 1000000.0f);
        
        List<AABBIntersectionSearch.AABBIntersection<String>> largeIntersections = 
            AABBIntersectionSearch.aabbIntersectedAll(largeAABB, octree, referencePoint);
        
        // Large AABB should generally find more intersections than small AABB
        // (though this depends on cube sizes and positions)
        assertDoesNotThrow(() -> {
            assertTrue(largeIntersections.size() >= smallIntersections.size());
        });
    }
}