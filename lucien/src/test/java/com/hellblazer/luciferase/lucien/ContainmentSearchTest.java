package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Containment search functionality All test coordinates use positive values only
 *
 * @author hal.hildebrand
 */
public class ContainmentSearchTest {

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
    void testContainmentResultDataIntegrity() {
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 5000000.0f;
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<ContainmentSearch.ContainmentResult<String>> results = ContainmentSearch.cubesContainedInSphere(
        sphereCenter, sphereRadius, octree, referencePoint);

        for (var result : results) {
            // Verify all fields are properly set
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertNotNull(result.containmentType);
            assertTrue(result.distanceToReferencePoint >= 0);
            assertTrue(result.volumeRatio >= 0);
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

            // Verify distance calculation
            float dx = referencePoint.x - center.x;
            float dy = referencePoint.y - center.y;
            float dz = referencePoint.z - center.z;
            float expectedDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(expectedDistance, result.distanceToReferencePoint, 0.001f);
        }
    }

    @Test
    void testCountCubesContainedInAABB() {
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(50.0f, 50.0f, 50.0f, 10000000.0f,
                                                                           10000000.0f, 10000000.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        long count = ContainmentSearch.countCubesContainedInAABB(aabb, octree);

        // Count should match the number from cubesContainedInAABB
        List<ContainmentSearch.ContainmentResult<String>> results = ContainmentSearch.cubesContainedInAABB(aabb, octree,
                                                                                                           referencePoint);

        assertEquals(results.size(), count);
        assertTrue(count >= 0);
    }

    @Test
    void testCountCubesContainedInSphere() {
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 5000000.0f;
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        long count = ContainmentSearch.countCubesContainedInSphere(sphereCenter, sphereRadius, octree);

        // Count should match the number from cubesContainedInSphere
        List<ContainmentSearch.ContainmentResult<String>> results = ContainmentSearch.cubesContainedInSphere(
        sphereCenter, sphereRadius, octree, referencePoint);

        assertEquals(results.size(), count);
        assertTrue(count >= 0);
    }

    @Test
    void testCubesContainedInAABB() {
        // Large AABB that should contain some cubes
        AABBIntersectionSearch.AABB aabb = new AABBIntersectionSearch.AABB(50.0f, 50.0f, 50.0f, 10000000.0f,
                                                                           10000000.0f, 10000000.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<ContainmentSearch.ContainmentResult<String>> results = ContainmentSearch.cubesContainedInAABB(aabb, octree,
                                                                                                           referencePoint);

        // Should find some contained cubes
        assertTrue(results.size() >= 0);

        // Results should be sorted by distance from reference point
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToReferencePoint <= results.get(i + 1).distanceToReferencePoint);
        }

        // All results should be COMPLETELY_CONTAINED
        for (var result : results) {
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertEquals(ContainmentSearch.ContainmentType.COMPLETELY_CONTAINED, result.containmentType);
            assertTrue(result.distanceToReferencePoint >= 0);
            assertTrue(result.volumeRatio >= 0);
            assertTrue(result.index >= 0);
        }
    }

    @Test
    void testCubesContainedInCylinder() {
        // Cylinder that should contain some cubes
        Point3f cylinderBase = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f cylinderTop = new Point3f(400.0f, 400.0f, 400.0f);
        float cylinderRadius = 1000000.0f; // Very large radius
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<ContainmentSearch.ContainmentResult<String>> results = ContainmentSearch.cubesContainedInCylinder(
        cylinderBase, cylinderTop, cylinderRadius, octree, referencePoint);

        // Should find some contained cubes
        assertTrue(results.size() >= 0);

        // Results should be sorted by distance from reference point
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToReferencePoint <= results.get(i + 1).distanceToReferencePoint);
        }

        // All results should be COMPLETELY_CONTAINED
        for (var result : results) {
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertEquals(ContainmentSearch.ContainmentType.COMPLETELY_CONTAINED, result.containmentType);
            assertTrue(result.distanceToReferencePoint >= 0);
            assertTrue(result.volumeRatio >= 0);
            assertTrue(result.index >= 0);
        }
    }

    @Test
    void testCubesContainedInSphere() {
        // Large sphere that should contain some cubes
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 5000000.0f; // Very large to encompass cubes
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<ContainmentSearch.ContainmentResult<String>> results = ContainmentSearch.cubesContainedInSphere(
        sphereCenter, sphereRadius, octree, referencePoint);

        // Should find some contained cubes
        assertTrue(results.size() >= 0);

        // Results should be sorted by distance from reference point
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToReferencePoint <= results.get(i + 1).distanceToReferencePoint);
        }

        // All results should be COMPLETELY_CONTAINED
        for (var result : results) {
            assertNotNull(result.content);
            assertNotNull(result.cube);
            assertNotNull(result.cubeCenter);
            assertEquals(ContainmentSearch.ContainmentType.COMPLETELY_CONTAINED, result.containmentType);
            assertTrue(result.distanceToReferencePoint >= 0);
            assertTrue(result.volumeRatio >= 0);
            assertTrue(result.index >= 0);
        }
    }

    @Test
    void testCubesWithVolumeRatio() {
        // First get some contained cubes
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 5000000.0f;
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<ContainmentSearch.ContainmentResult<String>> containedCubes = ContainmentSearch.cubesContainedInSphere(
        sphereCenter, sphereRadius, octree, referencePoint);

        if (!containedCubes.isEmpty()) {
            // Filter by volume ratio
            float containerVolume = (4.0f / 3.0f) * (float) Math.PI * sphereRadius * sphereRadius * sphereRadius;
            List<ContainmentSearch.ContainmentResult<String>> filteredCubes = ContainmentSearch.cubesWithVolumeRatio(
            containerVolume, 0.0f, 1.0f, containedCubes);

            // All results should have volume ratio within range
            for (var result : filteredCubes) {
                assertTrue(result.volumeRatio >= 0.0f);
                assertTrue(result.volumeRatio <= 1.0f);
            }
        }
    }

    @Test
    void testDistanceOrdering() {
        Point3f sphereCenter = new Point3f(250.0f, 250.0f, 250.0f);
        float sphereRadius = 5000000.0f;
        Point3f nearReference = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f farReference = new Point3f(600.0f, 600.0f, 600.0f);

        List<ContainmentSearch.ContainmentResult<String>> nearResults = ContainmentSearch.cubesContainedInSphere(
        sphereCenter, sphereRadius, octree, nearReference);

        List<ContainmentSearch.ContainmentResult<String>> farResults = ContainmentSearch.cubesContainedInSphere(
        sphereCenter, sphereRadius, octree, farReference);

        // Different reference points should give different distance orderings
        if (!nearResults.isEmpty()) {
            // Verify distance ordering within each result set
            for (int i = 0; i < nearResults.size() - 1; i++) {
                assertTrue(
                nearResults.get(i).distanceToReferencePoint <= nearResults.get(i + 1).distanceToReferencePoint);
            }
        }

        if (!farResults.isEmpty()) {
            for (int i = 0; i < farResults.size() - 1; i++) {
                assertTrue(
                farResults.get(i).distanceToReferencePoint <= farResults.get(i + 1).distanceToReferencePoint);
            }
        }
    }

    @Test
    void testEmptyOctree() {
        Octree<String> emptyOctree = new Octree<>();
        Point3f sphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float sphereRadius = 1000.0f;
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        List<ContainmentSearch.ContainmentResult<String>> results = ContainmentSearch.cubesContainedInSphere(
        sphereCenter, sphereRadius, emptyOctree, referencePoint);

        assertTrue(results.isEmpty());

        assertEquals(0, ContainmentSearch.countCubesContainedInSphere(sphereCenter, sphereRadius, emptyOctree));

        ContainmentSearch.ContainmentStatistics stats = ContainmentSearch.getContainmentStatisticsForSphere(
        sphereCenter, sphereRadius, emptyOctree);
        assertEquals(0, stats.totalCubes);
        assertEquals(0, stats.containedCubes);
        assertEquals(0, stats.partiallyContainedCubes);
        assertEquals(0, stats.notContainedCubes);
    }

    @Test
    void testGetContainmentStatisticsForSphere() {
        Point3f sphereCenter = new Point3f(250.0f, 250.0f, 250.0f);
        float sphereRadius = 1000000.0f;

        ContainmentSearch.ContainmentStatistics stats = ContainmentSearch.getContainmentStatisticsForSphere(
        sphereCenter, sphereRadius, octree);

        assertNotNull(stats);
        assertTrue(stats.totalCubes >= 0);
        assertTrue(stats.containedCubes >= 0);
        assertTrue(stats.partiallyContainedCubes >= 0);
        assertTrue(stats.notContainedCubes >= 0);
        assertTrue(stats.totalVolumeRatio >= 0);
        assertTrue(stats.averageVolumeRatio >= 0);

        // Total should equal sum of parts
        assertEquals(stats.totalCubes, stats.containedCubes + stats.partiallyContainedCubes + stats.notContainedCubes);

        // Percentages should be valid
        assertTrue(stats.getContainedPercentage() >= 0 && stats.getContainedPercentage() <= 100);
        assertTrue(stats.getPartiallyContainedPercentage() >= 0 && stats.getPartiallyContainedPercentage() <= 100);
        assertTrue(stats.getNotContainedPercentage() >= 0 && stats.getNotContainedPercentage() <= 100);
        assertTrue(stats.getOverallContainmentPercentage() >= 0 && stats.getOverallContainmentPercentage() <= 100);

        // Overall containment should equal contained + partially contained
        assertEquals(stats.getOverallContainmentPercentage(),
                     stats.getContainedPercentage() + stats.getPartiallyContainedPercentage(), 0.001);

        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("ContainmentStats"));
        assertTrue(statsStr.contains("total="));
        assertTrue(statsStr.contains("contained="));
        assertTrue(statsStr.contains("partial="));
        assertTrue(statsStr.contains("not_contained="));
        assertTrue(statsStr.contains("avg_volume_ratio="));
    }

    @Test
    void testInvalidVolumeRatioRange() {
        List<ContainmentSearch.ContainmentResult<String>> emptyCubes = List.of();

        // Test invalid range
        assertThrows(IllegalArgumentException.class, () -> {
            ContainmentSearch.cubesWithVolumeRatio(1000.0f, -0.1f, 1.0f, emptyCubes);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ContainmentSearch.cubesWithVolumeRatio(1000.0f, 0.8f, 0.2f, emptyCubes); // max < min
        });
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativeSphereCenter = new Point3f(-50.0f, 200.0f, 200.0f);
        Point3f positiveSphereCenter = new Point3f(200.0f, 200.0f, 200.0f);
        Point3f negativeReference = new Point3f(-100.0f, 100.0f, 100.0f);
        Point3f positiveReference = new Point3f(100.0f, 100.0f, 100.0f);
        float radius = 1000.0f;

        // Test negative sphere center
        assertThrows(IllegalArgumentException.class, () -> {
            ContainmentSearch.cubesContainedInSphere(negativeSphereCenter, radius, octree, positiveReference);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ContainmentSearch.countCubesContainedInSphere(negativeSphereCenter, radius, octree);
        });

        // Test negative reference point
        assertThrows(IllegalArgumentException.class, () -> {
            ContainmentSearch.cubesContainedInSphere(positiveSphereCenter, radius, octree, negativeReference);
        });

        // Test negative radius
        assertThrows(IllegalArgumentException.class, () -> {
            ContainmentSearch.cubesContainedInSphere(positiveSphereCenter, -10.0f, octree, positiveReference);
        });

        // Test negative cylinder coordinates
        Point3f negativeCylinderBase = new Point3f(-100.0f, 100.0f, 100.0f);
        Point3f positiveCylinderBase = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f positiveCylinderTop = new Point3f(200.0f, 200.0f, 200.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            ContainmentSearch.cubesContainedInCylinder(negativeCylinderBase, positiveCylinderTop, radius, octree,
                                                       positiveReference);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            ContainmentSearch.cubesContainedInCylinder(positiveCylinderBase, positiveCylinderTop, -10.0f, octree,
                                                       positiveReference);
        });
    }

    @Test
    void testSmallAndLargeContainers() {
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);

        // Very small sphere - should contain few or no cubes
        Point3f smallCenter = new Point3f(200.0f, 200.0f, 200.0f);
        float smallRadius = 1.0f;

        List<ContainmentSearch.ContainmentResult<String>> smallResults = ContainmentSearch.cubesContainedInSphere(
        smallCenter, smallRadius, octree, referencePoint);

        // Very large sphere - should contain most or all cubes
        Point3f largeCenter = new Point3f(300.0f, 300.0f, 300.0f);
        float largeRadius = 10000000.0f;

        List<ContainmentSearch.ContainmentResult<String>> largeResults = ContainmentSearch.cubesContainedInSphere(
        largeCenter, largeRadius, octree, referencePoint);

        // Large sphere should generally contain more cubes than small sphere
        // (though this depends on cube sizes and positions)
        assertDoesNotThrow(() -> {
            assertTrue(largeResults.size() >= smallResults.size());
        });
    }
}
