package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for TetContainmentSearch
 * Tests tetrahedral containment algorithms within various spatial volumes
 * 
 * @author hal.hildebrand
 */
class TetContainmentSearchTest {

    private Tetree<String> testTetree;

    @BeforeEach
    void setUp() {
        testTetree = new Tetree<>(new java.util.TreeMap<>());
        
        // Add test tetrahedra in valid tetrahedral domain
        float scale = Constants.MAX_EXTENT / 4.0f;
        byte testLevel = 15;
        
        // Create a 3D grid of test points
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                for (int k = 0; k < 5; k++) {
                    float x = scale * 0.1f + i * scale * 0.05f;
                    float y = scale * 0.1f + j * scale * 0.05f;
                    float z = scale * 0.1f + k * scale * 0.05f;
                    testTetree.insert(new Point3f(x, y, z), testLevel, 
                        String.format("tet_%d_%d_%d", i, j, k));
                }
            }
        }
    }

    @Test
    @DisplayName("Test tetrahedra contained in sphere")
    void testTetrahedraContainedInSphere() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        float sphereRadius = scale * 0.1f;
        Point3f referencePoint = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        List<TetContainmentSearch.TetContainmentResult<String>> results = 
            TetContainmentSearch.tetrahedraContainedInSphere(sphereCenter, sphereRadius, testTetree, referencePoint);

        assertNotNull(results);
        assertFalse(results.isEmpty());
        
        // Verify all results are marked as completely contained
        for (var result : results) {
            assertEquals(TetContainmentSearch.ContainmentType.COMPLETELY_CONTAINED, result.containmentType);
            assertNotNull(result.content);
            assertNotNull(result.tetrahedron);
            assertTrue(result.distanceToReferencePoint >= 0);
            assertTrue(result.volumeRatio > 0);
            
            // Verify tetrahedron is actually within sphere
            Point3f tetCenter = result.tetrahedronCenter;
            float distance = calculateDistance(sphereCenter, tetCenter);
            assertTrue(distance <= sphereRadius + scale * 0.01f, // Allow small tolerance
                      "Tetrahedron center should be within sphere");
        }
        
        // Verify results are sorted by distance
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i-1).distanceToReferencePoint <= results.get(i).distanceToReferencePoint,
                      "Results should be sorted by distance from reference point");
        }
    }

    @Test
    @DisplayName("Test tetrahedra contained in AABB")
    void testTetrahedraContainedInAABB() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Spatial.aabb aabb = new Spatial.aabb(
            scale * 0.15f, scale * 0.15f, scale * 0.15f,
            scale * 0.25f, scale * 0.25f, scale * 0.25f
        );
        Point3f referencePoint = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        List<TetContainmentSearch.TetContainmentResult<String>> results = 
            TetContainmentSearch.tetrahedraContainedInAABB(aabb, testTetree, referencePoint);

        assertNotNull(results);
        
        // Verify all results are within AABB bounds
        for (var result : results) {
            assertEquals(TetContainmentSearch.ContainmentType.COMPLETELY_CONTAINED, result.containmentType);
            
            Point3f center = result.tetrahedronCenter;
            assertTrue(center.x >= aabb.originX() && center.x <= aabb.extentX(),
                      "Tetrahedron center X should be within AABB");
            assertTrue(center.y >= aabb.originY() && center.y <= aabb.extentY(),
                      "Tetrahedron center Y should be within AABB");
            assertTrue(center.z >= aabb.originZ() && center.z <= aabb.extentZ(),
                      "Tetrahedron center Z should be within AABB");
        }
    }

    @Test
    @DisplayName("Test tetrahedra contained in cylinder")
    void testTetrahedraContainedInCylinder() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f cylinderBase = new Point3f(scale * 0.2f, scale * 0.1f, scale * 0.2f);
        Point3f cylinderTop = new Point3f(scale * 0.2f, scale * 0.3f, scale * 0.2f);
        float cylinderRadius = scale * 0.08f;
        Point3f referencePoint = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        List<TetContainmentSearch.TetContainmentResult<String>> results = 
            TetContainmentSearch.tetrahedraContainedInCylinder(cylinderBase, cylinderTop, cylinderRadius, 
                                                              testTetree, referencePoint);

        assertNotNull(results);
        
        // Verify all results are properly contained
        for (var result : results) {
            assertEquals(TetContainmentSearch.ContainmentType.COMPLETELY_CONTAINED, result.containmentType);
            assertTrue(result.volumeRatio > 0);
        }
    }

    @Test
    @DisplayName("Test volume ratio filtering")
    void testVolumeRatioFiltering() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        float sphereRadius = scale * 0.15f;
        Point3f referencePoint = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        List<TetContainmentSearch.TetContainmentResult<String>> allResults = 
            TetContainmentSearch.tetrahedraContainedInSphere(sphereCenter, sphereRadius, testTetree, referencePoint);

        float sphereVolume = (4.0f / 3.0f) * (float) Math.PI * sphereRadius * sphereRadius * sphereRadius;
        
        // Filter for specific volume ratio range
        float minRatio = 0.0001f;
        float maxRatio = 0.001f;
        List<TetContainmentSearch.TetContainmentResult<String>> filteredResults = 
            TetContainmentSearch.tetrahedraWithVolumeRatio(sphereVolume, minRatio, maxRatio, allResults);

        assertNotNull(filteredResults);
        
        // Verify all filtered results are within the specified ratio range
        for (var result : filteredResults) {
            assertTrue(result.volumeRatio >= minRatio && result.volumeRatio <= maxRatio,
                      "Volume ratio should be within specified range");
        }
        
        // Verify filtering actually reduced the result set (if applicable)
        assertTrue(filteredResults.size() <= allResults.size());
    }

    @Test
    @DisplayName("Test containment counting")
    void testContainmentCounting() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        float sphereRadius = scale * 0.1f;
        Point3f referencePoint = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        // Get full results
        List<TetContainmentSearch.TetContainmentResult<String>> results = 
            TetContainmentSearch.tetrahedraContainedInSphere(sphereCenter, sphereRadius, testTetree, referencePoint);

        // Get count
        long count = TetContainmentSearch.countTetrahedraContainedInSphere(sphereCenter, sphereRadius, testTetree);

        assertEquals(results.size(), count, "Count should match number of results");

        // Test AABB counting
        Spatial.aabb aabb = new Spatial.aabb(
            scale * 0.15f, scale * 0.15f, scale * 0.15f,
            scale * 0.25f, scale * 0.25f, scale * 0.25f
        );
        
        List<TetContainmentSearch.TetContainmentResult<String>> aabbResults = 
            TetContainmentSearch.tetrahedraContainedInAABB(aabb, testTetree, referencePoint);
        
        long aabbCount = TetContainmentSearch.countTetrahedraContainedInAABB(aabb, testTetree);
        
        assertEquals(aabbResults.size(), aabbCount, "AABB count should match number of results");
    }

    @Test
    @DisplayName("Test containment statistics")
    void testContainmentStatistics() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        float sphereRadius = scale * 0.12f;

        TetContainmentSearch.TetContainmentStatistics stats = 
            TetContainmentSearch.getContainmentStatisticsForSphere(sphereCenter, sphereRadius, testTetree);

        assertNotNull(stats);
        assertTrue(stats.totalTetrahedra > 0);
        assertTrue(stats.containedTetrahedra >= 0);
        assertTrue(stats.partiallyContainedTetrahedra >= 0);
        assertTrue(stats.notContainedTetrahedra >= 0);
        
        // Verify totals add up
        assertEquals(stats.totalTetrahedra, 
                    stats.containedTetrahedra + stats.partiallyContainedTetrahedra + stats.notContainedTetrahedra);
        
        // Verify percentages
        assertTrue(stats.getContainedPercentage() >= 0 && stats.getContainedPercentage() <= 100);
        assertTrue(stats.getPartiallyContainedPercentage() >= 0 && stats.getPartiallyContainedPercentage() <= 100);
        assertTrue(stats.getNotContainedPercentage() >= 0 && stats.getNotContainedPercentage() <= 100);
        
        // Verify average volume ratio
        if (stats.containedTetrahedra > 0) {
            assertTrue(stats.averageVolumeRatio > 0);
        }
        
        // Test string representation
        String statsString = stats.toString();
        assertNotNull(statsString);
        assertTrue(statsString.contains("TetContainmentStats"));
    }

    @Test
    @DisplayName("Test input validation")
    void testInputValidation() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f validPoint = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        Point3f negativePoint = new Point3f(-scale * 0.2f, scale * 0.2f, scale * 0.2f);
        float validRadius = scale * 0.1f;
        float invalidRadius = -scale * 0.1f;

        // Test negative coordinates in sphere center
        assertThrows(IllegalArgumentException.class, () -> 
            TetContainmentSearch.tetrahedraContainedInSphere(negativePoint, validRadius, testTetree, validPoint));

        // Test negative coordinates in reference point
        assertThrows(IllegalArgumentException.class, () -> 
            TetContainmentSearch.tetrahedraContainedInSphere(validPoint, validRadius, testTetree, negativePoint));

        // Test negative radius
        assertThrows(IllegalArgumentException.class, () -> 
            TetContainmentSearch.tetrahedraContainedInSphere(validPoint, invalidRadius, testTetree, validPoint));

        // Test zero radius
        assertThrows(IllegalArgumentException.class, () -> 
            TetContainmentSearch.tetrahedraContainedInSphere(validPoint, 0.0f, testTetree, validPoint));

        // Test cylinder with negative coordinates
        assertThrows(IllegalArgumentException.class, () -> 
            TetContainmentSearch.tetrahedraContainedInCylinder(negativePoint, validPoint, validRadius, 
                                                              testTetree, validPoint));

        assertThrows(IllegalArgumentException.class, () -> 
            TetContainmentSearch.tetrahedraContainedInCylinder(validPoint, negativePoint, validRadius, 
                                                              testTetree, validPoint));

        // Test invalid volume ratio range
        List<TetContainmentSearch.TetContainmentResult<String>> emptyList = List.of();
        assertThrows(IllegalArgumentException.class, () -> 
            TetContainmentSearch.tetrahedraWithVolumeRatio(1000.0f, 0.5f, 0.1f, emptyList));

        assertThrows(IllegalArgumentException.class, () -> 
            TetContainmentSearch.tetrahedraWithVolumeRatio(1000.0f, -0.1f, 0.5f, emptyList));
    }

    @Test
    @DisplayName("Test empty tetree")
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new java.util.TreeMap<>());
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        float sphereRadius = scale * 0.1f;
        Point3f referencePoint = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        List<TetContainmentSearch.TetContainmentResult<String>> results = 
            TetContainmentSearch.tetrahedraContainedInSphere(sphereCenter, sphereRadius, emptyTetree, referencePoint);

        assertNotNull(results);
        assertTrue(results.isEmpty());

        long count = TetContainmentSearch.countTetrahedraContainedInSphere(sphereCenter, sphereRadius, emptyTetree);
        assertEquals(0, count);

        TetContainmentSearch.TetContainmentStatistics stats = 
            TetContainmentSearch.getContainmentStatisticsForSphere(sphereCenter, sphereRadius, emptyTetree);

        assertNotNull(stats);
        assertEquals(0, stats.totalTetrahedra);
        assertEquals(0, stats.containedTetrahedra);
        assertEquals(0.0, stats.averageVolumeRatio, 0.001);
    }

    @Test
    @DisplayName("Test small sphere containment")
    void testSmallSphereContainment() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.01f; // Very small sphere
        Point3f referencePoint = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        List<TetContainmentSearch.TetContainmentResult<String>> results = 
            TetContainmentSearch.tetrahedraContainedInSphere(sphereCenter, sphereRadius, testTetree, referencePoint);

        assertNotNull(results);
        // Small sphere might not contain any complete tetrahedra
        // This is expected behavior
    }

    @Test
    @DisplayName("Test large sphere containment")
    void testLargeSphereContainment() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        float sphereRadius = scale * 0.5f; // Very large sphere
        Point3f referencePoint = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        List<TetContainmentSearch.TetContainmentResult<String>> results = 
            TetContainmentSearch.tetrahedraContainedInSphere(sphereCenter, sphereRadius, testTetree, referencePoint);

        assertNotNull(results);
        assertTrue(results.size() > 0, "Large sphere should contain some tetrahedra");
        
        // Verify volume ratios are reasonable
        for (var result : results) {
            assertTrue(result.volumeRatio < 1.0f, "Individual tetrahedron should be smaller than containing sphere");
        }
    }

    private float calculateDistance(Point3f p1, Point3f p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        float dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}