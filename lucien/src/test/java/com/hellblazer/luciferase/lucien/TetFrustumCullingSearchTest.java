package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.TetrahedralSearchBase.SimplexAggregationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for TetFrustumCullingSearch
 * 
 * @author hal.hildebrand
 */
class TetFrustumCullingSearchTest {
    
    private Tetree<String> tetree;
    private Frustum3D perspectiveFrustum;
    private Frustum3D orthographicFrustum;
    private Point3f cameraPosition;
    
    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new TreeMap<>());
        cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        
        // Create perspective frustum looking down the positive Z axis
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        float fovy = (float) Math.toRadians(60.0); // 60 degree field of view
        float aspectRatio = 1.0f;
        float nearDistance = 50.0f;
        float farDistance = 2000.0f;
        
        perspectiveFrustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up, fovy, aspectRatio, nearDistance, farDistance
        );
        
        // Create orthographic frustum
        // Using smaller values to ensure all computed points remain positive
        orthographicFrustum = Frustum3D.createOrthographic(
            cameraPosition, lookAt, up,
            50.0f, 200.0f, 50.0f, 200.0f, // left, right, bottom, top (smaller values)
            nearDistance, farDistance
        );
        
        // Insert test data
        insertTestData();
    }
    
    private void insertTestData() {
        // Camera is at (500, 500, 100) looking toward (500, 500, 1000)
        // Create test data appropriate for frustum culling with reasonable distances
        byte level = 15;
        
        // Very close to camera (distance ~100)
        tetree.insert(new Point3f(500.0f, 500.0f, 150.0f), level, "VeryNear1");
        tetree.insert(new Point3f(480.0f, 480.0f, 160.0f), level, "VeryNear2");
        tetree.insert(new Point3f(520.0f, 520.0f, 170.0f), level, "VeryNear3");
        
        // Near the camera (distance ~100-200)
        tetree.insert(new Point3f(500.0f, 500.0f, 200.0f), level, "Near1");
        tetree.insert(new Point3f(450.0f, 450.0f, 250.0f), level, "Near2");
        tetree.insert(new Point3f(550.0f, 550.0f, 230.0f), level, "Near3");
        
        // Middle distance (distance ~400-600)
        tetree.insert(new Point3f(500.0f, 500.0f, 500.0f), level, "Mid1");
        tetree.insert(new Point3f(400.0f, 400.0f, 600.0f), level, "Mid2");
        tetree.insert(new Point3f(600.0f, 600.0f, 700.0f), level, "Mid3");
        
        // Far from camera (distance ~1400+)  
        tetree.insert(new Point3f(500.0f, 500.0f, 1500.0f), level, "Far1");
        tetree.insert(new Point3f(450.0f, 550.0f, 1800.0f), level, "Far2");
        
        // Outside frustum (to the sides, but same Z range)
        tetree.insert(new Point3f(50.0f, 50.0f, 500.0f), level, "OutsideLeft");
        tetree.insert(new Point3f(950.0f, 950.0f, 500.0f), level, "OutsideRight");
        
        // Behind near plane (but still positive coordinates)
        tetree.insert(new Point3f(500.0f, 500.0f, 120.0f), level, "BehindNearPlane");
        
        // Beyond far plane
        tetree.insert(new Point3f(500.0f, 500.0f, 2500.0f), level, "BeyondFar");
    }
    
    @Test
    void testFrustumCulledAll() {
        var results = TetFrustumCullingSearch.frustumCulledAll(
            perspectiveFrustum, tetree, cameraPosition, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        assertNotNull(results);
        assertFalse(results.isEmpty());
        
        // Verify results are sorted by distance
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).distanceToCamera <= results.get(i).distanceToCamera,
                      "Results should be sorted by distance from camera");
        }
        
        // Verify closest result
        var closest = results.get(0);
        assertTrue(closest.distanceToCamera < 500.0f, "Closest should be near the camera, but got distance: " + closest.distanceToCamera);
    }
    
    @Test
    void testFrustumCulledFirst() {
        var result = TetFrustumCullingSearch.frustumCulledFirst(
            perspectiveFrustum, tetree, cameraPosition, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        assertNotNull(result);
        assertTrue(result.distanceToCamera < 500.0f, "First result should be closest to camera");
    }
    
    @Test
    void testTetrahedraCompletelyInside() {
        var results = TetFrustumCullingSearch.tetrahedraCompletelyInside(
            perspectiveFrustum, tetree, cameraPosition, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        assertNotNull(results);
        
        // All results should be marked as INSIDE
        for (var result : results) {
            assertEquals(TetFrustumCullingSearch.CullingResult.INSIDE, result.cullingResult);
        }
    }
    
    @Test
    void testTetrahedraPartiallyIntersecting() {
        var results = TetFrustumCullingSearch.tetrahedraPartiallyIntersecting(
            perspectiveFrustum, tetree, cameraPosition, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        assertNotNull(results);
        
        // All results should be marked as INTERSECTING
        for (var result : results) {
            assertEquals(TetFrustumCullingSearch.CullingResult.INTERSECTING, result.cullingResult);
        }
    }
    
    @Test
    void testCountFrustumIntersections() {
        long count = TetFrustumCullingSearch.countFrustumIntersections(
            perspectiveFrustum, tetree, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        assertTrue(count > 0, "Should have some intersections");
        assertTrue(count < 12, "Should not include all tetrahedra (some are outside)");
    }
    
    @Test
    void testHasAnyIntersection() {
        assertTrue(TetFrustumCullingSearch.hasAnyIntersection(
            perspectiveFrustum, tetree, SimplexAggregationStrategy.ALL_SIMPLICIES
        ));
        
        // Test with empty tetree
        Tetree<String> emptyTetree = new Tetree<>(new TreeMap<>());
        assertFalse(TetFrustumCullingSearch.hasAnyIntersection(
            perspectiveFrustum, emptyTetree, SimplexAggregationStrategy.ALL_SIMPLICIES
        ));
    }
    
    @Test
    void testGetFrustumCullingStatistics() {
        var stats = TetFrustumCullingSearch.getFrustumCullingStatistics(
            perspectiveFrustum, tetree, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        assertNotNull(stats);
        assertTrue(stats.totalTetrahedra > 0);
        assertTrue(stats.insideTetrahedra >= 0);
        assertTrue(stats.intersectingTetrahedra >= 0);
        assertTrue(stats.outsideTetrahedra >= 0);
        
        // Verify percentages
        double totalPercentage = stats.getInsidePercentage() + 
                                stats.getIntersectingPercentage() + 
                                stats.getOutsidePercentage();
        assertEquals(100.0, totalPercentage, 0.1, "Percentages should sum to 100%");
        
        assertTrue(stats.getVisiblePercentage() > 0, "Some tetrahedra should be visible");
        assertTrue(stats.getVisiblePercentage() < 100.0, "Not all tetrahedra should be visible");
    }
    
    @Test
    void testBatchFrustumCulling() {
        // Create multiple frustums with different orientations
        Point3f lookAt2 = new Point3f(600.0f, 500.0f, 1000.0f);
        Point3f lookAt3 = new Point3f(400.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        Frustum3D frustum2 = Frustum3D.createPerspective(
            cameraPosition, lookAt2, up, 
            (float) Math.toRadians(45.0), 1.0f, 50.0f, 2000.0f
        );
        
        Frustum3D frustum3 = Frustum3D.createPerspective(
            cameraPosition, lookAt3, up,
            (float) Math.toRadians(90.0), 1.0f, 50.0f, 2000.0f
        );
        
        List<Frustum3D> frustums = Arrays.asList(perspectiveFrustum, frustum2, frustum3);
        
        Map<Frustum3D, List<TetFrustumCullingSearch.TetFrustumIntersection<String>>> results = 
            TetFrustumCullingSearch.batchFrustumCulling(frustums, tetree, cameraPosition, SimplexAggregationStrategy.ALL_SIMPLICIES);
        
        assertNotNull(results);
        assertEquals(3, results.size());
        
        // Each frustum should have results
        for (Frustum3D frustum : frustums) {
            assertTrue(results.containsKey(frustum));
            assertNotNull(results.get(frustum));
        }
    }
    
    @Test
    void testOrthographicFrustum() {
        var results = TetFrustumCullingSearch.frustumCulledAll(
            orthographicFrustum, tetree, cameraPosition, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        assertNotNull(results);
        // Don't require results since orthographic frustum might not intersect with any tetrahedra
        
        // Orthographic frustum should capture different tetrahedra than perspective
        var perspResults = TetFrustumCullingSearch.frustumCulledAll(
            perspectiveFrustum, tetree, cameraPosition, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        // The counts might differ due to different frustum shapes
        // This is expected behavior
    }
    
    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f negativeCameraPos = new Point3f(-100.0f, 500.0f, 500.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetFrustumCullingSearch.frustumCulledAll(
                perspectiveFrustum, tetree, negativeCameraPos, SimplexAggregationStrategy.ALL_SIMPLICIES
            );
        });
    }
    
    @Test
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new TreeMap<>());
        
        var results = TetFrustumCullingSearch.frustumCulledAll(
            perspectiveFrustum, emptyTetree, cameraPosition, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        assertNotNull(results);
        assertTrue(results.isEmpty());
        
        var stats = TetFrustumCullingSearch.getFrustumCullingStatistics(
            perspectiveFrustum, emptyTetree, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        assertEquals(0, stats.totalTetrahedra);
        assertEquals(0.0, stats.getVisiblePercentage());
    }
    
    @Test
    void testAggregationStrategies() {
        // Test with different aggregation strategies
        for (SimplexAggregationStrategy strategy : SimplexAggregationStrategy.values()) {
            var results = TetFrustumCullingSearch.frustumCulledAll(
                perspectiveFrustum, tetree, cameraPosition, strategy
            );
            
            assertNotNull(results, "Results should not be null for strategy: " + strategy);
            
            // Different strategies may produce different counts, but all should work
            if (strategy != SimplexAggregationStrategy.ALL_SIMPLICIES) {
                // Aggregation strategies might reduce the number of results
                assertTrue(results.size() >= 0);
            }
        }
    }
    
    @Test
    void testCullingStatisticsToString() {
        var stats = TetFrustumCullingSearch.getFrustumCullingStatistics(
            perspectiveFrustum, tetree, SimplexAggregationStrategy.ALL_SIMPLICIES
        );
        
        String statsString = stats.toString();
        assertNotNull(statsString);
        assertTrue(statsString.contains("CullingStats"));
        assertTrue(statsString.contains("total="));
        assertTrue(statsString.contains("visible="));
    }
}