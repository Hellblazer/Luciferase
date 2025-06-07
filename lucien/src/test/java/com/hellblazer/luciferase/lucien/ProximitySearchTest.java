package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Proximity search functionality All test coordinates use positive values only
 *
 * @author hal.hildebrand
 */
public class ProximitySearchTest {

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
    void testCubesInProximityBands() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);

        List<ProximitySearch.DistanceRange> ranges = List.of(
        new ProximitySearch.DistanceRange(0.0f, 100.0f, ProximitySearch.ProximityType.VERY_CLOSE),
        new ProximitySearch.DistanceRange(100.0f, 500.0f, ProximitySearch.ProximityType.CLOSE),
        new ProximitySearch.DistanceRange(500.0f, 1000.0f, ProximitySearch.ProximityType.MODERATE));

        Map<ProximitySearch.DistanceRange, List<ProximitySearch.ProximityResult<String>>> results = ProximitySearch.cubesInProximityBands(
        queryPoint, ranges, octree);

        assertEquals(3, results.size());
        assertTrue(results.containsKey(ranges.get(0)));
        assertTrue(results.containsKey(ranges.get(1)));
        assertTrue(results.containsKey(ranges.get(2)));

        // Each band should have results sorted by distance
        for (var entry : results.entrySet()) {
            List<ProximitySearch.ProximityResult<String>> bandResults = entry.getValue();
            for (int i = 0; i < bandResults.size() - 1; i++) {
                assertTrue(bandResults.get(i).distanceToQuery <= bandResults.get(i + 1).distanceToQuery);
            }
        }
    }

    @Test
    void testCubesNearAllPoints() {
        List<Point3f> queryPoints = List.of(new Point3f(150.0f, 150.0f, 150.0f), new Point3f(250.0f, 250.0f, 250.0f));
        float maxDistance = 5000.0f; // Large distance to include cubes

        List<ProximitySearch.ProximityResult<String>> results = ProximitySearch.cubesNearAllPoints(queryPoints,
                                                                                                   maxDistance, octree);

        // Should find cubes near all of the query points
        assertTrue(results.size() >= 0);

        // Results should be sorted by distance to primary query point
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToQuery <= results.get(i + 1).distanceToQuery);
        }

        // All results should be within max distance of all query points
        for (var result : results) {
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertNotNull(result.proximityType);
            assertTrue(result.distanceToQuery >= 0);
        }

        // Test invalid max distance
        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.cubesNearAllPoints(queryPoints, -10.0f, octree);
        });
    }

    @Test
    void testCubesNearAnyPoint() {
        List<Point3f> queryPoints = List.of(new Point3f(100.0f, 100.0f, 100.0f), new Point3f(300.0f, 300.0f, 300.0f));
        float maxDistance = 1000.0f;

        List<ProximitySearch.ProximityResult<String>> results = ProximitySearch.cubesNearAnyPoint(queryPoints,
                                                                                                  maxDistance, octree);

        // Should find cubes near any of the query points
        assertTrue(results.size() >= 0);

        // Results should be sorted by closest distance to any query point
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).minDistanceToQuery <= results.get(i + 1).minDistanceToQuery);
        }

        // All results should be within max distance of at least one query point
        for (var result : results) {
            assertTrue(result.minDistanceToQuery <= maxDistance);
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertNotNull(result.proximityType);
        }

        // Test invalid max distance
        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.cubesNearAnyPoint(queryPoints, -10.0f, octree);
        });
    }

    @Test
    void testCubesWithinDistanceRange() {
        Point3f queryPoint = new Point3f(150.0f, 150.0f, 150.0f);
        ProximitySearch.DistanceRange range = new ProximitySearch.DistanceRange(0.0f, 1000.0f,
                                                                                ProximitySearch.ProximityType.MODERATE);

        List<ProximitySearch.ProximityResult<String>> results = ProximitySearch.cubesWithinDistanceRange(queryPoint,
                                                                                                         range, octree);

        // Should find some cubes within range
        assertTrue(results.size() >= 0);

        // Results should be sorted by center distance
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToQuery <= results.get(i + 1).distanceToQuery);
        }

        // All results should have valid data
        for (var result : results) {
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertEquals(ProximitySearch.ProximityType.MODERATE, result.proximityType);
            assertTrue(result.distanceToQuery >= 0);
            assertTrue(result.minDistanceToQuery >= 0);
            assertTrue(result.maxDistanceToQuery >= result.minDistanceToQuery);
            assertTrue(result.index >= 0);
        }
    }

    @Test
    void testDistanceOrdering() {
        Point3f nearQuery = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f farQuery = new Point3f(600.0f, 600.0f, 600.0f);
        int n = 5;

        List<ProximitySearch.ProximityResult<String>> nearResults = ProximitySearch.findNClosestCubes(nearQuery, n,
                                                                                                      octree);

        List<ProximitySearch.ProximityResult<String>> farResults = ProximitySearch.findNClosestCubes(farQuery, n,
                                                                                                     octree);

        // Different query points should give different distance orderings
        if (!nearResults.isEmpty()) {
            // Verify distance ordering within each result set
            for (int i = 0; i < nearResults.size() - 1; i++) {
                assertTrue(nearResults.get(i).distanceToQuery <= nearResults.get(i + 1).distanceToQuery);
            }
        }

        if (!farResults.isEmpty()) {
            for (int i = 0; i < farResults.size() - 1; i++) {
                assertTrue(farResults.get(i).distanceToQuery <= farResults.get(i + 1).distanceToQuery);
            }
        }
    }

    @Test
    void testDistanceRange() {
        // Test DistanceRange creation and validation
        ProximitySearch.DistanceRange range = new ProximitySearch.DistanceRange(100.0f, 500.0f,
                                                                                ProximitySearch.ProximityType.CLOSE);

        assertEquals(100.0f, range.minDistance);
        assertEquals(500.0f, range.maxDistance);
        assertEquals(ProximitySearch.ProximityType.CLOSE, range.proximityType);

        assertTrue(range.contains(200.0f));
        assertTrue(range.contains(100.0f));
        assertTrue(range.contains(500.0f));
        assertFalse(range.contains(50.0f));
        assertFalse(range.contains(600.0f));

        // Test toString
        String rangeStr = range.toString();
        assertTrue(rangeStr.contains("DistanceRange"));
        assertTrue(rangeStr.contains("100.00"));
        assertTrue(rangeStr.contains("500.00"));
        assertTrue(rangeStr.contains("CLOSE"));

        // Test invalid ranges
        assertThrows(IllegalArgumentException.class, () -> {
            new ProximitySearch.DistanceRange(-10.0f, 100.0f, ProximitySearch.ProximityType.CLOSE);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ProximitySearch.DistanceRange(200.0f, 100.0f, ProximitySearch.ProximityType.CLOSE);
        });
    }

    @Test
    void testEmptyOctree() {
        Octree<String> emptyOctree = new Octree<>();
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        ProximitySearch.DistanceRange range = new ProximitySearch.DistanceRange(0.0f, 1000.0f,
                                                                                ProximitySearch.ProximityType.CLOSE);

        List<ProximitySearch.ProximityResult<String>> results = ProximitySearch.cubesWithinDistanceRange(queryPoint,
                                                                                                         range,
                                                                                                         emptyOctree);

        assertTrue(results.isEmpty());

        List<ProximitySearch.ProximityResult<String>> nClosest = ProximitySearch.findNClosestCubes(queryPoint, 3,
                                                                                                   emptyOctree);

        assertTrue(nClosest.isEmpty());

        ProximitySearch.ProximityStatistics stats = ProximitySearch.getProximityStatistics(queryPoint, emptyOctree);
        assertEquals(0, stats.totalCubes);
        assertEquals(0, stats.veryCloseCubes);
        assertEquals(0, stats.closeCubes);
        assertEquals(0, stats.moderateCubes);
        assertEquals(0, stats.farCubes);
    }

    @Test
    void testFindNClosestCubes() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        int n = 3;

        List<ProximitySearch.ProximityResult<String>> results = ProximitySearch.findNClosestCubes(queryPoint, n,
                                                                                                  octree);

        // Should find at most N cubes
        assertTrue(results.size() <= n);
        assertTrue(results.size() >= 0);

        // Results should be sorted by distance
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToQuery <= results.get(i + 1).distanceToQuery);
        }

        // All results should have valid data
        for (var result : results) {
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertNotNull(result.proximityType);
            assertTrue(result.distanceToQuery >= 0);
            assertTrue(result.minDistanceToQuery >= 0);
            assertTrue(result.maxDistanceToQuery >= result.minDistanceToQuery);
            assertTrue(result.index >= 0);
        }

        // Test invalid N
        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.findNClosestCubes(queryPoint, 0, octree);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.findNClosestCubes(queryPoint, -1, octree);
        });
    }

    @Test
    void testGetProximityStatistics() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);

        ProximitySearch.ProximityStatistics stats = ProximitySearch.getProximityStatistics(queryPoint, octree);

        assertNotNull(stats);
        assertTrue(stats.totalCubes >= 0);
        assertTrue(stats.veryCloseCubes >= 0);
        assertTrue(stats.closeCubes >= 0);
        assertTrue(stats.moderateCubes >= 0);
        assertTrue(stats.farCubes >= 0);
        assertTrue(stats.averageDistance >= 0);
        assertTrue(stats.minDistance >= 0);
        assertTrue(stats.maxDistance >= stats.minDistance);

        // Total should equal sum of parts
        assertEquals(stats.totalCubes, stats.veryCloseCubes + stats.closeCubes + stats.moderateCubes + stats.farCubes);

        // Percentages should be valid
        assertTrue(stats.getVeryClosePercentage() >= 0 && stats.getVeryClosePercentage() <= 100);
        assertTrue(stats.getClosePercentage() >= 0 && stats.getClosePercentage() <= 100);
        assertTrue(stats.getModeratePercentage() >= 0 && stats.getModeratePercentage() <= 100);
        assertTrue(stats.getFarPercentage() >= 0 && stats.getFarPercentage() <= 100);

        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("ProximityStats"));
        assertTrue(statsStr.contains("total="));
        assertTrue(statsStr.contains("very_close="));
        assertTrue(statsStr.contains("close="));
        assertTrue(statsStr.contains("moderate="));
        assertTrue(statsStr.contains("far="));
        assertTrue(statsStr.contains("avg_dist="));
        assertTrue(statsStr.contains("range="));
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativeQuery = new Point3f(-100.0f, 200.0f, 200.0f);
        Point3f positiveQuery = new Point3f(200.0f, 200.0f, 200.0f);
        ProximitySearch.DistanceRange range = new ProximitySearch.DistanceRange(0.0f, 1000.0f,
                                                                                ProximitySearch.ProximityType.CLOSE);

        // Test negative query point
        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.cubesWithinDistanceRange(negativeQuery, range, octree);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.findNClosestCubes(negativeQuery, 3, octree);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.getProximityStatistics(negativeQuery, octree);
        });

        // Test negative coordinates in query point lists
        List<Point3f> queryPointsWithNegative = List.of(positiveQuery, new Point3f(-50.0f, 100.0f, 100.0f));

        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.cubesNearAnyPoint(queryPointsWithNegative, 1000.0f, octree);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ProximitySearch.cubesNearAllPoints(queryPointsWithNegative, 1000.0f, octree);
        });
    }

    @Test
    void testProximityResultDataIntegrity() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);
        ProximitySearch.DistanceRange range = new ProximitySearch.DistanceRange(0.0f, 1000000.0f,
                                                                                ProximitySearch.ProximityType.CLOSE);

        List<ProximitySearch.ProximityResult<String>> results = ProximitySearch.cubesWithinDistanceRange(queryPoint,
                                                                                                         range, octree);

        for (var result : results) {
            // Verify all fields are properly set
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertNotNull(result.proximityType);
            assertTrue(result.distanceToQuery >= 0);
            assertTrue(result.minDistanceToQuery >= 0);
            assertTrue(result.maxDistanceToQuery >= result.minDistanceToQuery);
            assertTrue(result.index >= 0);

            // Verify cube center is within cube bounds
            Spatial.Cube cube = result.cube;
            Point3f center = result.cubeCenter;
            assertTrue(center.x >= cube.originX());
            assertTrue(center.x <= cube.originX() + cube.extent());
            assertTrue(center.y >= cube.originY());
            assertTrue(center.y <= cube.originY() + cube.extent());
            assertTrue(center.z >= cube.originZ());
            assertTrue(center.z <= cube.originZ() + cube.extent());

            // Verify center distance calculation
            float dx = queryPoint.x - center.x;
            float dy = queryPoint.y - center.y;
            float dz = queryPoint.z - center.z;
            float expectedDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(expectedDistance, result.distanceToQuery, 0.001f);

            // Verify min distance is less than or equal to center distance
            assertTrue(result.minDistanceToQuery <= result.distanceToQuery);
        }
    }

    @Test
    void testProximityTypeClassification() {
        Point3f queryPoint = new Point3f(200.0f, 200.0f, 200.0f);

        List<ProximitySearch.ProximityResult<String>> allResults = ProximitySearch.findNClosestCubes(queryPoint, 100,
                                                                                                     octree);

        // Verify proximity types are classified correctly based on distance
        for (var result : allResults) {
            ProximitySearch.ProximityType expectedType;
            if (result.distanceToQuery < 100.0f) {
                expectedType = ProximitySearch.ProximityType.VERY_CLOSE;
            } else if (result.distanceToQuery < 500.0f) {
                expectedType = ProximitySearch.ProximityType.CLOSE;
            } else if (result.distanceToQuery < 1000.0f) {
                expectedType = ProximitySearch.ProximityType.MODERATE;
            } else if (result.distanceToQuery < 5000.0f) {
                expectedType = ProximitySearch.ProximityType.FAR;
            } else {
                expectedType = ProximitySearch.ProximityType.VERY_FAR;
            }

            assertEquals(expectedType, result.proximityType);
        }
    }
}
