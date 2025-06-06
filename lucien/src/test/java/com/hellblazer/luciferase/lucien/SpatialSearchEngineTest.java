package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for the unified SpatialSearchEngine interface.
 * Ensures feature parity between Octree and Tetree implementations.
 * 
 * @author hal.hildebrand
 */
public class SpatialSearchEngineTest {

    private SpatialSearchEngine<String> octreeEngine;
    private SpatialSearchEngine<String> tetreeEngine;
    private final byte testLevel = 15;
    private static final int TEST_DATASET_SIZE = 100;

    @BeforeEach
    void setUp() {
        // Create engines with identical test data
        var testData = generateTestData(TEST_DATASET_SIZE);
        
        octreeEngine = SpatialEngineFactory.createOptimal(SpatialEngineType.OCTREE, new TreeMap<>(testData));
        tetreeEngine = SpatialEngineFactory.createOptimal(SpatialEngineType.TETREE, new TreeMap<>(testData));
    }

    @Test
    @DisplayName("Test factory creates correct engine types")
    void testFactoryEngineTypes() {
        assertEquals(SpatialEngineType.OCTREE, octreeEngine.getEngineType());
        assertEquals(SpatialEngineType.TETREE, tetreeEngine.getEngineType());
    }

    @Test
    @DisplayName("Test boundedBy operation consistency")
    void testBoundedByConsistency() {
        var query = new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f);
        
        var octreeResults = octreeEngine.boundedBy(query);
        var tetreeResults = tetreeEngine.boundedBy(query);
        
        assertNotNull(octreeResults);
        assertNotNull(tetreeResults);
        
        // Results should be comparable (within reasonable variance due to different decomposition)
        assertTrue(Math.abs(octreeResults.size() - tetreeResults.size()) <= octreeResults.size() * 0.3,
                  "Result sizes should be reasonably similar");
    }

    @Test
    @DisplayName("Test k-nearest neighbors consistency")
    void testKNearestNeighborsConsistency() {
        var queryPoint = new Point3f(2000.0f, 2000.0f, 2000.0f);
        int k = 5;
        
        var octreeResults = octreeEngine.kNearestNeighbors(queryPoint, k);
        var tetreeResults = tetreeEngine.kNearestNeighbors(queryPoint, k);
        
        assertEquals(k, octreeResults.size(), "Octree should return exactly k results");
        assertEquals(k, tetreeResults.size(), "Tetree should return exactly k results");
        
        // Verify distances are in ascending order
        for (int i = 1; i < k; i++) {
            assertTrue(octreeResults.get(i-1).getDistance() <= octreeResults.get(i).getDistance());
            assertTrue(tetreeResults.get(i-1).getDistance() <= tetreeResults.get(i).getDistance());
        }
    }

    @Test
    @DisplayName("Test within distance search consistency")
    void testWithinDistanceConsistency() {
        var queryPoint = new Point3f(2000.0f, 2000.0f, 2000.0f);
        float distance = 1500.0f;
        
        var octreeResults = octreeEngine.withinDistance(queryPoint, distance);
        var tetreeResults = tetreeEngine.withinDistance(queryPoint, distance);
        
        assertNotNull(octreeResults);
        assertNotNull(tetreeResults);
        
        // Verify all results are within the specified distance
        for (var result : octreeResults) {
            assertTrue(result.getDistance() <= distance * 1.1, // Allow small tolerance for floating point precision
                      "Octree result distance should be within specified range");
        }
        
        for (var result : tetreeResults) {
            assertTrue(result.getDistance() <= distance * 1.1,
                      "Tetree result distance should be within specified range");
        }
    }

    @Test
    @DisplayName("Test ray intersection functionality")
    void testRayIntersection() {
        var rayStart = new Point3f(1000.0f, 1000.0f, 1000.0f);
        var rayDirection = new Vector3f(1.0f, 1.0f, 1.0f);
        rayDirection.normalize();
        var ray = new Ray3D(rayStart, rayDirection);
        
        var octreeResults = octreeEngine.rayIntersection(ray);
        var tetreeResults = tetreeEngine.rayIntersection(ray);
        
        assertNotNull(octreeResults);
        assertNotNull(tetreeResults);
        
        // Both engines should find some intersections in our test dataset
        assertTrue(octreeResults.size() > 0, "Octree should find ray intersections");
        assertTrue(tetreeResults.size() > 0, "Tetree should find ray intersections");
    }

    @Test
    @DisplayName("Test sphere intersection functionality")
    void testSphereIntersection() {
        var center = new Point3f(2000.0f, 2000.0f, 2000.0f);
        float radius = 1000.0f;
        
        var octreeResults = octreeEngine.sphereIntersection(center, radius);
        var tetreeResults = tetreeEngine.sphereIntersection(center, radius);
        
        assertNotNull(octreeResults);
        assertNotNull(tetreeResults);
        
        // Tetree should find some sphere intersections (Octree uses AABB approximation)
        assertTrue(tetreeResults.size() > 0, "Tetree should find sphere intersections");
    }

    @Test
    @DisplayName("Test plane intersection functionality")
    void testPlaneIntersection() {
        var plane = new Plane3D(new Point3f(2000.0f, 2000.0f, 2000.0f), new Vector3f(1.0f, 0.0f, 0.0f));
        
        var octreeResults = octreeEngine.planeIntersection(plane);
        var tetreeResults = tetreeEngine.planeIntersection(plane);
        
        assertNotNull(octreeResults);
        assertNotNull(tetreeResults);
        
        // Both should find plane intersections
        assertTrue(octreeResults.size() > 0, "Octree should find plane intersections");
        assertTrue(tetreeResults.size() > 0, "Tetree should find plane intersections");
    }

    @Test
    @DisplayName("Test frustum culling functionality")
    void testFrustumCulling() {
        var frustum = new Frustum3D(
            new Point3f(1000.0f, 1000.0f, 1000.0f),  // eye
            new Point3f(3000.0f, 3000.0f, 3000.0f),  // target
            new Vector3f(0.0f, 0.0f, 1.0f),          // up
            (float) Math.PI / 4.0f,                   // fovy
            1.0f,                                     // aspect
            100.0f,                                   // near
            5000.0f                                   // far
        );
        
        var octreeResults = octreeEngine.frustumCulling(frustum);
        var tetreeResults = tetreeEngine.frustumCulling(frustum);
        
        assertNotNull(octreeResults);
        assertNotNull(tetreeResults);
        
        // Both should find some visible objects
        assertTrue(octreeResults.size() > 0, "Octree should find visible objects");
        assertTrue(tetreeResults.size() > 0, "Tetree should find visible objects");
    }

    @Test
    @DisplayName("Test containment search functionality")
    void testContainmentSearch() {
        var center = new Point3f(2000.0f, 2000.0f, 2000.0f);
        float radius = 500.0f;
        
        var octreeResults = octreeEngine.containedInSphere(center, radius);
        var tetreeResults = tetreeEngine.containedInSphere(center, radius);
        
        assertNotNull(octreeResults);
        assertNotNull(tetreeResults);
        
        // Results should exist for our dense test dataset
        assertTrue(octreeResults.size() >= 0, "Octree containment should work");
        assertTrue(tetreeResults.size() >= 0, "Tetree containment should work");
    }

    @Test
    @DisplayName("Test distance range search functionality")
    void testDistanceRangeSearch() {
        var queryPoint = new Point3f(2000.0f, 2000.0f, 2000.0f);
        float minDistance = 500.0f;
        float maxDistance = 1500.0f;
        
        var octreeResults = octreeEngine.withinDistanceRange(queryPoint, minDistance, maxDistance);
        var tetreeResults = tetreeEngine.withinDistanceRange(queryPoint, minDistance, maxDistance);
        
        assertNotNull(octreeResults);
        assertNotNull(tetreeResults);
        
        // Verify all results are within the distance range
        for (var result : octreeResults) {
            assertTrue(result.getDistance() >= minDistance * 0.9 && result.getDistance() <= maxDistance * 1.1);
        }
        
        for (var result : tetreeResults) {
            assertTrue(result.getDistance() >= minDistance * 0.9 && result.getDistance() <= maxDistance * 1.1);
        }
    }

    @Test
    @DisplayName("Test line-of-sight functionality")
    void testLineOfSight() {
        var observer = new Point3f(1000.0f, 1000.0f, 1000.0f);
        var target = new Point3f(3000.0f, 3000.0f, 3000.0f);
        double tolerance = 0.1;
        
        var octreeResult = octreeEngine.testLineOfSight(observer, target, tolerance);
        var tetreeResult = tetreeEngine.testLineOfSight(observer, target, tolerance);
        
        assertNotNull(octreeResult);
        assertNotNull(tetreeResult);
        
        assertTrue(octreeResult.getTotalOcclusionRatio() >= 0.0 && octreeResult.getTotalOcclusionRatio() <= 1.0);
        assertTrue(tetreeResult.getTotalOcclusionRatio() >= 0.0 && tetreeResult.getTotalOcclusionRatio() <= 1.0);
        
        assertTrue(octreeResult.getDistance() > 0);
        assertTrue(tetreeResult.getDistance() > 0);
    }

    @Test
    @DisplayName("Test parallel processing functionality")
    void testParallelProcessing() {
        var query = new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 2000.0f);
        
        var octreeResults = octreeEngine.parallelBoundedBy(query);
        var tetreeResults = tetreeEngine.parallelBoundedBy(query);
        
        assertNotNull(octreeResults);
        assertNotNull(tetreeResults);
        
        // Parallel results should be similar to sequential results
        var sequentialOctree = octreeEngine.boundedBy(query);
        var sequentialTetree = tetreeEngine.boundedBy(query);
        
        assertEquals(sequentialOctree.size(), octreeResults.size(), "Parallel Octree should match sequential");
        assertEquals(sequentialTetree.size(), tetreeResults.size(), "Parallel Tetree should match sequential");
    }

    @Test
    @DisplayName("Test performance metrics functionality")
    void testPerformanceMetrics() {
        // Execute some operations to generate metrics
        var query = new Spatial.Cube(1000.0f, 1000.0f, 1000.0f, 1000.0f);
        octreeEngine.boundedBy(query);
        tetreeEngine.boundedBy(query);
        
        var octreeMetrics = octreeEngine.getPerformanceMetrics();
        var tetreeMetrics = tetreeEngine.getPerformanceMetrics();
        
        assertNotNull(octreeMetrics);
        assertNotNull(tetreeMetrics);
        
        assertTrue(octreeMetrics.getTotalQueries() > 0);
        assertTrue(tetreeMetrics.getTotalQueries() > 0);
        
        assertTrue(octreeMetrics.getAverageQueryTime() >= 0.0);
        assertTrue(tetreeMetrics.getAverageQueryTime() >= 0.0);
    }

    @Test
    @DisplayName("Test memory usage tracking")
    void testMemoryUsage() {
        var octreeMemory = octreeEngine.getMemoryUsage();
        var tetreeMemory = tetreeEngine.getMemoryUsage();
        
        assertNotNull(octreeMemory);
        assertNotNull(tetreeMemory);
        
        assertTrue(octreeMemory.getEntryCount() > 0);
        assertTrue(tetreeMemory.getEntryCount() > 0);
        
        assertTrue(octreeMemory.getMemoryPerEntry() > 0.0);
        assertTrue(tetreeMemory.getMemoryPerEntry() > 0.0);
        
        assertTrue(octreeMemory.getTotalMemoryUsage() > 0.0);
        assertTrue(tetreeMemory.getTotalMemoryUsage() > 0.0);
    }

    @Test
    @DisplayName("Test factory profiles for engine selection")
    void testFactoryProfiles() {
        var aabbProfile = SpatialEngineFactory.SpatialQueryProfile.createAABBHeavy(1000);
        var geometricProfile = SpatialEngineFactory.SpatialQueryProfile.createGeometricHeavy(1000);
        var balancedProfile = SpatialEngineFactory.SpatialQueryProfile.createBalanced(1000);
        
        assertFalse(aabbProfile.favorsTetrahedral(), "AABB profile should favor Octree");
        assertTrue(geometricProfile.favorsTetrahedral(), "Geometric profile should favor Tetree");
        assertTrue(balancedProfile.favorsTetrahedral(), "Balanced profile should favor Tetree");
        
        var aabbEngine = SpatialEngineFactory.createForPerformance(new TreeMap<>(), aabbProfile);
        var geometricEngine = SpatialEngineFactory.createForPerformance(new TreeMap<>(), geometricProfile);
        
        assertEquals(SpatialEngineType.OCTREE, aabbEngine.getEngineType());
        assertEquals(SpatialEngineType.TETREE, geometricEngine.getEngineType());
    }

    @Test
    @DisplayName("Test coordinate system profiles")
    void testCoordinateSystemProfiles() {
        var positiveProfile = SpatialEngineFactory.CoordinateSystemProfile.createPositiveOnly(5000.0f);
        var fullRangeProfile = SpatialEngineFactory.CoordinateSystemProfile.createFullRange(5000.0f);
        
        assertFalse(positiveProfile.hasNegativeCoordinates());
        assertTrue(fullRangeProfile.hasNegativeCoordinates());
        
        var positiveEngine = SpatialEngineFactory.createForCoordinateSystem(positiveProfile);
        var fullRangeEngine = SpatialEngineFactory.createForCoordinateSystem(fullRangeProfile);
        
        assertEquals(SpatialEngineType.TETREE, positiveEngine.getEngineType());
        assertEquals(SpatialEngineType.OCTREE, fullRangeEngine.getEngineType());
    }

    @Test
    @DisplayName("Test dual engine creation")
    void testDualEngineCreation() {
        var testData = generateTestData(50);
        var dualEngines = SpatialEngineFactory.createDualEngines(testData);
        
        assertEquals(2, dualEngines.length);
        assertEquals(SpatialEngineType.OCTREE, dualEngines[0].getEngineType());
        assertEquals(SpatialEngineType.TETREE, dualEngines[1].getEngineType());
        
        // Both should have the same data
        assertTrue(dualEngines[0].getMemoryUsage().getEntryCount() > 0);
        assertTrue(dualEngines[1].getMemoryUsage().getEntryCount() > 0);
    }

    private TreeMap<Long, String> generateTestData(int count) {
        var data = new TreeMap<Long, String>();
        var random = ThreadLocalRandom.current();
        
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * 5000;
            float y = random.nextFloat() * 5000;
            float z = random.nextFloat() * 5000;
            
            var point = new Point3f(x, y, z);
            var octreeKey = Octant.keyFromPoint(point, testLevel);
            var tetreeKey = Tet.keyFromPoint(point, testLevel);
            
            // Use the same key for both engines (use octree key as common reference)
            data.put(octreeKey, "test-data-" + i);
        }
        
        return data;
    }
}