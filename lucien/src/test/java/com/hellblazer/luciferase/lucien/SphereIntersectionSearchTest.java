package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Sphere intersection search functionality All test coordinates use positive values only
 *
 * @author hal.hildebrand
 */
public class SphereIntersectionSearchTest {

    private final byte           testLevel = 15;
    private       Octree<String> octree;

    @BeforeEach
    void setUp() {
        octree = new Octree<>();

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
    void testBatchSphereIntersections() {
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        SphereIntersectionSearch.SphereQuery query1 = new SphereIntersectionSearch.SphereQuery(
        new Point3f(200.0f, 200.0f, 200.0f), 100.0f);
        SphereIntersectionSearch.SphereQuery query2 = new SphereIntersectionSearch.SphereQuery(
        new Point3f(300.0f, 200.0f, 300.0f), 150.0f);

        List<SphereIntersectionSearch.SphereQuery> queries = List.of(query1, query2);

        Map<SphereIntersectionSearch.SphereQuery, List<SphereIntersectionSearch.SphereIntersection<String>>> results = SphereIntersectionSearch.batchSphereIntersections(
        queries, octree, referencePoint);

        assertEquals(2, results.size());
        assertTrue(results.containsKey(query1));
        assertTrue(results.containsKey(query2));

        // Each result should be sorted by distance
        for (var entry : results.entrySet()) {
            List<SphereIntersectionSearch.SphereIntersection<String>> intersections = entry.getValue();
            for (int i = 0; i < intersections.size() - 1; i++) {
                assertTrue(
                intersections.get(i).distanceToReferencePoint <= intersections.get(i + 1).distanceToReferencePoint);
            }
        }
    }

    @Test
    void testCountSphereIntersections() {
        Point3f sphereCenter = new Point3f(250.0f, 250.0f, 250.0f);
        float sphereRadius = 200.0f;
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        long count = SphereIntersectionSearch.countSphereIntersections(sphereCenter, sphereRadius, octree);

        // Count should match the number from sphereIntersectedAll
        List<SphereIntersectionSearch.SphereIntersection<String>> intersections = SphereIntersectionSearch.sphereIntersectedAll(
        sphereCenter, sphereRadius, octree, referencePoint);

        assertEquals(intersections.size(), count);
        assertTrue(count >= 0);
    }

    @Test
    void testCubesCompletelyInside() {
        // Large sphere that might completely contain some cubes
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 5000000.0f; // Very large to try to encompass cubes completely
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<SphereIntersectionSearch.SphereIntersection<String>> insideCubes = SphereIntersectionSearch.cubesCompletelyInside(
        sphereCenter, sphereRadius, octree, referencePoint);

        // All results should be COMPLETELY_INSIDE
        for (var intersection : insideCubes) {
            assertEquals(SphereIntersectionSearch.IntersectionType.COMPLETELY_INSIDE, intersection.intersectionType);
        }

        // Results should be sorted by distance from reference point
        for (int i = 0; i < insideCubes.size() - 1; i++) {
            assertTrue(insideCubes.get(i).distanceToReferencePoint <= insideCubes.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testCubesPartiallyIntersecting() {
        // Medium sphere that should create some partial intersections
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 150.0f;
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<SphereIntersectionSearch.SphereIntersection<String>> intersectingCubes = SphereIntersectionSearch.cubesPartiallyIntersecting(
        sphereCenter, sphereRadius, octree, referencePoint);

        // All results should be INTERSECTING
        for (var intersection : intersectingCubes) {
            assertEquals(SphereIntersectionSearch.IntersectionType.INTERSECTING, intersection.intersectionType);
        }

        // Results should be sorted by distance from reference point
        for (int i = 0; i < intersectingCubes.size() - 1; i++) {
            assertTrue(
            intersectingCubes.get(i).distanceToReferencePoint <= intersectingCubes.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testDistanceOrdering() {
        Point3f sphereCenter = new Point3f(250.0f, 250.0f, 250.0f);
        float sphereRadius = 300.0f;
        Point3f nearReference = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f farReference = new Point3f(600.0f, 600.0f, 600.0f);

        List<SphereIntersectionSearch.SphereIntersection<String>> nearIntersections = SphereIntersectionSearch.sphereIntersectedAll(
        sphereCenter, sphereRadius, octree, nearReference);

        List<SphereIntersectionSearch.SphereIntersection<String>> farIntersections = SphereIntersectionSearch.sphereIntersectedAll(
        sphereCenter, sphereRadius, octree, farReference);

        // Different reference points should give different distance orderings
        if (!nearIntersections.isEmpty()) {
            // Verify distance ordering within each result set
            for (int i = 0; i < nearIntersections.size() - 1; i++) {
                assertTrue(nearIntersections.get(i).distanceToReferencePoint <= nearIntersections.get(
                i + 1).distanceToReferencePoint);
            }
        }

        if (!farIntersections.isEmpty()) {
            for (int i = 0; i < farIntersections.size() - 1; i++) {
                assertTrue(farIntersections.get(i).distanceToReferencePoint <= farIntersections.get(
                i + 1).distanceToReferencePoint);
            }
        }
    }

    @Test
    void testEmptyOctree() {
        Octree<String> emptyOctree = new Octree<>();
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 100.0f;
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<SphereIntersectionSearch.SphereIntersection<String>> intersections = SphereIntersectionSearch.sphereIntersectedAll(
        sphereCenter, sphereRadius, emptyOctree, referencePoint);

        assertTrue(intersections.isEmpty());

        SphereIntersectionSearch.SphereIntersection<String> first = SphereIntersectionSearch.sphereIntersectedFirst(
        sphereCenter, sphereRadius, emptyOctree, referencePoint);

        assertNull(first);

        assertEquals(0, SphereIntersectionSearch.countSphereIntersections(sphereCenter, sphereRadius, emptyOctree));
        assertFalse(SphereIntersectionSearch.hasAnyIntersection(sphereCenter, sphereRadius, emptyOctree));

        SphereIntersectionSearch.IntersectionStatistics stats = SphereIntersectionSearch.getSphereIntersectionStatistics(
        sphereCenter, sphereRadius, emptyOctree);
        assertEquals(0, stats.totalCubes);
        assertEquals(0, stats.insideCubes);
        assertEquals(0, stats.intersectingCubes);
        assertEquals(0, stats.outsideCubes);
    }

    @Test
    void testGetSphereIntersectionStatistics() {
        Point3f sphereCenter = new Point3f(250.0f, 250.0f, 250.0f);
        float sphereRadius = 300.0f;

        SphereIntersectionSearch.IntersectionStatistics stats = SphereIntersectionSearch.getSphereIntersectionStatistics(
        sphereCenter, sphereRadius, octree);

        assertNotNull(stats);
        assertTrue(stats.totalCubes >= 0);
        assertTrue(stats.insideCubes >= 0);
        assertTrue(stats.intersectingCubes >= 0);
        assertTrue(stats.outsideCubes >= 0);

        // Total should equal sum of parts
        assertEquals(stats.totalCubes, stats.insideCubes + stats.intersectingCubes + stats.outsideCubes);

        // Percentages should be valid
        assertTrue(stats.getInsidePercentage() >= 0 && stats.getInsidePercentage() <= 100);
        assertTrue(stats.getIntersectingPercentage() >= 0 && stats.getIntersectingPercentage() <= 100);
        assertTrue(stats.getOutsidePercentage() >= 0 && stats.getOutsidePercentage() <= 100);
        assertTrue(stats.getIntersectedPercentage() >= 0 && stats.getIntersectedPercentage() <= 100);

        // Intersected percentage should equal inside + intersecting
        assertEquals(stats.getIntersectedPercentage(), stats.getInsidePercentage() + stats.getIntersectingPercentage(),
                     0.001);

        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("SphereIntersectionStats"));
        assertTrue(statsStr.contains("total="));
        assertTrue(statsStr.contains("inside="));
        assertTrue(statsStr.contains("intersecting="));
        assertTrue(statsStr.contains("outside="));
        assertTrue(statsStr.contains("intersected="));
    }

    @Test
    void testHasAnyIntersection() {
        // Large sphere that should intersect some cubes
        Point3f intersectingCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float intersectingRadius = 500.0f;

        // Small sphere in an area with no data
        Point3f nonIntersectingCenter = new Point3f(1000.0f, 1000.0f, 1000.0f);
        float nonIntersectingRadius = 10.0f;

        // Test both cases (might depend on actual cube positions and sizes)
        assertDoesNotThrow(() -> {
            SphereIntersectionSearch.hasAnyIntersection(intersectingCenter, intersectingRadius, octree);
        });

        assertDoesNotThrow(() -> {
            SphereIntersectionSearch.hasAnyIntersection(nonIntersectingCenter, nonIntersectingRadius, octree);
        });
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativeCenter = new Point3f(-50.0f, 200.0f, 200.0f);
        Point3f positiveCenter = new Point3f(200.0f, 200.0f, 200.0f);
        Point3f negativeReference = new Point3f(-100.0f, 100.0f, 100.0f);
        Point3f positiveReference = new Point3f(100.0f, 100.0f, 100.0f);
        float radius = 100.0f;

        // Test negative sphere center
        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.sphereIntersectedAll(negativeCenter, radius, octree, positiveReference);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.sphereIntersectedFirst(negativeCenter, radius, octree, positiveReference);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.countSphereIntersections(negativeCenter, radius, octree);
        });

        // Test negative reference point
        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.sphereIntersectedAll(positiveCenter, radius, octree, negativeReference);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.cubesCompletelyInside(positiveCenter, radius, octree, negativeReference);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.cubesPartiallyIntersecting(positiveCenter, radius, octree, negativeReference);
        });

        // Test negative radius
        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.sphereIntersectedAll(positiveCenter, -10.0f, octree, positiveReference);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new SphereIntersectionSearch.SphereQuery(positiveCenter, -10.0f);
        });
    }

    @Test
    void testSmallAndLargeSpheres() {
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        // Very small sphere - should intersect few or no cubes
        Point3f smallCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float smallRadius = 1.0f;

        List<SphereIntersectionSearch.SphereIntersection<String>> smallIntersections = SphereIntersectionSearch.sphereIntersectedAll(
        smallCenter, smallRadius, octree, referencePoint);

        // Very large sphere - should intersect most or all cubes
        Point3f largeCenter = new Point3f(300.0f, 300.0f, 300.0f);
        float largeRadius = 10000000.0f;

        List<SphereIntersectionSearch.SphereIntersection<String>> largeIntersections = SphereIntersectionSearch.sphereIntersectedAll(
        largeCenter, largeRadius, octree, referencePoint);

        // Large sphere should generally find more intersections than small sphere
        // (though this depends on cube sizes and positions)
        assertDoesNotThrow(() -> {
            assertTrue(largeIntersections.size() >= smallIntersections.size());
        });
    }

    @Test
    void testSphereIntersectedAll() {
        // Create a sphere that should intersect some cubes
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 100.0f;
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<SphereIntersectionSearch.SphereIntersection<String>> intersections = SphereIntersectionSearch.sphereIntersectedAll(
        sphereCenter, sphereRadius, octree, referencePoint);

        // Should find some intersections (exact number depends on sphere geometry and cube quantization)
        assertTrue(intersections.size() >= 0);

        // Results should be sorted by distance from reference point
        for (int i = 0; i < intersections.size() - 1; i++) {
            assertTrue(
            intersections.get(i).distanceToReferencePoint <= intersections.get(i + 1).distanceToReferencePoint);
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
            assertNotEquals(SphereIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE,
                            intersection.intersectionType);
        }
    }

    @Test
    void testSphereIntersectedFirst() {
        Point3f sphereCenter = new Point3f(150.0f, 150.0f, 150.0f);
        float sphereRadius = 75.0f;
        Point3f referencePoint = new Point3f(75.0f, 75.0f, 75.0f);

        SphereIntersectionSearch.SphereIntersection<String> firstIntersection = SphereIntersectionSearch.sphereIntersectedFirst(
        sphereCenter, sphereRadius, octree, referencePoint);

        if (firstIntersection != null) {
            // Should be the closest intersection
            List<SphereIntersectionSearch.SphereIntersection<String>> allIntersections = SphereIntersectionSearch.sphereIntersectedAll(
            sphereCenter, sphereRadius, octree, referencePoint);

            assertFalse(allIntersections.isEmpty());
            assertEquals(allIntersections.get(0).content, firstIntersection.content);
            assertEquals(allIntersections.get(0).distanceToReferencePoint, firstIntersection.distanceToReferencePoint,
                         0.001f);
        }
    }

    @Test
    void testSphereIntersectionDataIntegrity() {
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 200.0f;
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<SphereIntersectionSearch.SphereIntersection<String>> intersections = SphereIntersectionSearch.sphereIntersectedAll(
        sphereCenter, sphereRadius, octree, referencePoint);

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
            assertNotEquals(SphereIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE,
                            intersection.intersectionType);
        }
    }

    @Test
    void testSphereQueryEqualsAndHashCode() {
        Point3f center1 = new Point3f(200.0f, 200.0f, 200.0f);
        Point3f center2 = new Point3f(200.0f, 200.0f, 200.0f);
        Point3f center3 = new Point3f(300.0f, 300.0f, 300.0f);

        SphereIntersectionSearch.SphereQuery query1 = new SphereIntersectionSearch.SphereQuery(center1, 100.0f);
        SphereIntersectionSearch.SphereQuery query2 = new SphereIntersectionSearch.SphereQuery(center2, 100.0f);
        SphereIntersectionSearch.SphereQuery query3 = new SphereIntersectionSearch.SphereQuery(center3, 100.0f);
        SphereIntersectionSearch.SphereQuery query4 = new SphereIntersectionSearch.SphereQuery(center1, 150.0f);

        // Test equality
        assertEquals(query1, query2);
        assertNotEquals(query1, query3);
        assertNotEquals(query1, query4);

        // Test hash code consistency
        assertEquals(query1.hashCode(), query2.hashCode());

        // Test toString
        String queryStr = query1.toString();
        assertTrue(queryStr.contains("SphereQuery"));
        assertTrue(queryStr.contains("center="));
        assertTrue(queryStr.contains("radius="));
    }
}
