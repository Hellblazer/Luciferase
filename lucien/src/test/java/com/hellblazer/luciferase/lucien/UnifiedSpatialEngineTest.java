package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic integration test for the unified spatial search engine interface.
 * Verifies that both Octree and Tetree engines work through the unified interface.
 * 
 * @author hal.hildebrand
 */
public class UnifiedSpatialEngineTest {
    
    @Test
    void testOctreeEngineCreation() {
        // Create an Octree engine via factory
        var engine = SpatialEngineFactory.createOptimal(SpatialEngineType.OCTREE, null);
        
        assertNotNull(engine);
        assertEquals(SpatialEngineType.OCTREE, engine.getEngineType());
        
        // Verify basic operations work
        var volume = new Spatial.Sphere(500.0f, 500.0f, 500.0f, 100.0f);
        var results = engine.boundedBy(volume);
        assertNotNull(results);
        assertTrue(results.isEmpty()); // Empty engine should return empty results
    }
    
    @Test
    void testTetreeEngineCreation() {
        // Create a Tetree engine via factory
        var engine = SpatialEngineFactory.createOptimal(SpatialEngineType.TETREE, null);
        
        assertNotNull(engine);
        assertEquals(SpatialEngineType.TETREE, engine.getEngineType());
        
        // Verify basic operations work with positive coordinates
        var volume = new Spatial.Sphere(500.0f, 500.0f, 500.0f, 100.0f);
        var results = engine.boundedBy(volume);
        assertNotNull(results);
        assertTrue(results.isEmpty()); // Empty engine should return empty results
    }
    
    @Test
    void testEngineWithInitialData() {
        // Create test data
        Map<Long, String> testData = new TreeMap<>();
        testData.put(1000L, "Test Content 1");
        testData.put(2000L, "Test Content 2");
        testData.put(3000L, "Test Content 3");
        
        // Test with Octree
        var octreeEngine = SpatialEngineFactory.createOptimal(SpatialEngineType.OCTREE, testData);
        assertNotNull(octreeEngine);
        assertEquals(SpatialEngineType.OCTREE, octreeEngine.getEngineType());
        
        // Test with Tetree
        var tetreeEngine = SpatialEngineFactory.createOptimal(SpatialEngineType.TETREE, testData);
        assertNotNull(tetreeEngine);
        assertEquals(SpatialEngineType.TETREE, tetreeEngine.getEngineType());
    }
    
    @Test
    void testKNearestNeighbors() {
        // Create test data with arbitrary spatial keys
        Map<Long, String> testData = new TreeMap<>();
        // Use simple spatial keys for testing
        testData.put(1000L, "Near");
        testData.put(2000L, "Middle");
        testData.put(3000L, "Far");
        
        // Test with Octree
        var octreeEngine = SpatialEngineFactory.createOptimal(SpatialEngineType.OCTREE, testData);
        var queryPoint = new Point3f(150.0f, 150.0f, 150.0f);
        
        // For empty or simple test data, k-NN might return less than k results
        var knnResults = octreeEngine.kNearestNeighbors(queryPoint, 2);
        
        assertNotNull(knnResults);
        // Verify results are sorted by distance
        for (int i = 1; i < knnResults.size(); i++) {
            assertTrue(knnResults.get(i-1).getDistance() <= knnResults.get(i).getDistance());
        }
    }
    
    @Test
    void testPerformanceMetrics() {
        var engine = SpatialEngineFactory.createOptimal(SpatialEngineType.OCTREE, null);
        
        // Execute some queries to generate metrics
        var volume = new Spatial.Sphere(500.0f, 500.0f, 500.0f, 100.0f);
        engine.boundedBy(volume);
        engine.boundedBy(volume);
        engine.boundedBy(volume);
        
        var metrics = engine.getPerformanceMetrics();
        assertNotNull(metrics);
        assertEquals(3, metrics.getTotalQueries());
        assertTrue(metrics.getAverageQueryTime() >= 0);
    }
    
    @Test
    void testMemoryUsage() {
        var engine = SpatialEngineFactory.createOptimal(SpatialEngineType.OCTREE, null);
        
        var memoryUsage = engine.getMemoryUsage();
        assertNotNull(memoryUsage);
        assertEquals(0, memoryUsage.getEntryCount()); // Empty engine
        assertEquals(0.0, memoryUsage.getTotalMemoryUsage(), 0.001);
        assertEquals(0.10, memoryUsage.getMemoryPerEntry(), 0.001); // Expected KB per entry
    }
    
    @Test
    void testQueryProfiles() {
        // Test AABB-heavy profile (favors Octree)
        var aabbProfile = SpatialEngineFactory.SpatialQueryProfile.createAABBHeavy(10000);
        assertFalse(aabbProfile.favorsTetrahedral());
        
        // Test geometric-heavy profile (favors Tetree)
        var geometricProfile = SpatialEngineFactory.SpatialQueryProfile.createGeometricHeavy(10000);
        assertTrue(geometricProfile.favorsTetrahedral());
        
        // Test balanced profile
        var balancedProfile = SpatialEngineFactory.SpatialQueryProfile.createBalanced(10000);
        assertTrue(balancedProfile.favorsTetrahedral()); // Balanced still favors geometric queries
    }
    
    @Test
    void testCoordinateSystemProfiles() {
        // Test positive-only coordinates (optimal for Tetree)
        var positiveProfile = SpatialEngineFactory.CoordinateSystemProfile.createPositiveOnly(1000.0f);
        assertFalse(positiveProfile.hasNegativeCoordinates());
        
        var positiveEngine = SpatialEngineFactory.createForCoordinateSystem(positiveProfile);
        assertEquals(SpatialEngineType.TETREE, positiveEngine.getEngineType());
        
        // Test full range coordinates (requires Octree)
        var fullRangeProfile = SpatialEngineFactory.CoordinateSystemProfile.createFullRange(1000.0f);
        assertTrue(fullRangeProfile.hasNegativeCoordinates());
        
        var fullRangeEngine = SpatialEngineFactory.createForCoordinateSystem(fullRangeProfile);
        assertEquals(SpatialEngineType.OCTREE, fullRangeEngine.getEngineType());
    }
    
    @Test
    void testTetreeWithAggregationStrategy() {
        var engine = SpatialEngineFactory.createTetreeWithStrategy(
            null, 
            TetrahedralSearchBase.SimplexAggregationStrategy.WEIGHTED_AVERAGE
        );
        
        assertNotNull(engine);
        assertEquals(SpatialEngineType.TETREE, engine.getEngineType());
        assertEquals(TetrahedralSearchBase.SimplexAggregationStrategy.WEIGHTED_AVERAGE, 
                    engine.getAggregationStrategy());
    }
    
    @Test
    void testDualEngines() {
        Map<Long, String> testData = new TreeMap<>();
        testData.put(1000L, "Test Content");
        
        var engines = SpatialEngineFactory.createDualEngines(testData);
        
        assertNotNull(engines);
        assertEquals(2, engines.length);
        assertEquals(SpatialEngineType.OCTREE, engines[0].getEngineType());
        assertEquals(SpatialEngineType.TETREE, engines[1].getEngineType());
    }
}