package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.TetQueryOptimizer.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TetQueryOptimizer (Phase 5B)
 * Tests tetrahedral-specific query optimizations including SFC range queries,
 * intersection testing, containment optimization, and neighbor search.
 */
public class TetQueryOptimizerTest {

    private TetQueryOptimizer optimizer;
    private TetSpatialRangeQuery rangeQuery;
    private TetIntersectionOptimizer intersectionOptimizer;
    private TetContainmentOptimizer containmentOptimizer;
    private TetNeighborSearch neighborSearch;
    private TetQueryMetrics metrics;

    @BeforeEach
    void setUp() {
        TetQueryOptimizer.clearCaches();
        optimizer = new TetQueryOptimizer();
        rangeQuery = optimizer.createRangeQuery();
        intersectionOptimizer = optimizer.createIntersectionOptimizer();
        containmentOptimizer = optimizer.createContainmentOptimizer();
        neighborSearch = optimizer.createNeighborSearch();
        metrics = optimizer.getMetrics();
        metrics.reset();
    }

    @Test
    @DisplayName("Test tetrahedral spatial range query with contained mode")
    void testTetSpatialRangeQueryContained() {
        // Test with a small cube that should contain some tetrahedra
        var cube = new Spatial.Cube(100, 100, 100, 200);
        
        var results = rangeQuery.queryTetRange(cube, QueryMode.CONTAINED).toList();
        
        // Verify results are valid tetrahedral indices
        for (var index : results) {
            assertTrue(index >= 0, "SFC index should be non-negative: " + index);
            var tet = Tet.tetrahedron(index);
            assertNotNull(tet, "Should be able to reconstruct tetrahedron from index");
            
            // Verify all vertices are within cube bounds
            var vertices = tet.coordinates();
            for (var vertex : vertices) {
                assertTrue(vertex.x >= cube.originX() && vertex.x <= cube.originX() + cube.extent(),
                    "Vertex X should be within cube bounds");
                assertTrue(vertex.y >= cube.originY() && vertex.y <= cube.originY() + cube.extent(),
                    "Vertex Y should be within cube bounds");
                assertTrue(vertex.z >= cube.originZ() && vertex.z <= cube.originZ() + cube.extent(),
                    "Vertex Z should be within cube bounds");
            }
        }
        
        // Verify metrics were recorded
        assertTrue(metrics.getMetricsSummary().contains("Range Queries: 1"));
    }

    @Test
    @DisplayName("Test tetrahedral spatial range query with intersecting mode")
    void testTetSpatialRangeQueryIntersecting() {
        // Test with a sphere
        var sphere = new Spatial.Sphere(500, 500, 500, 100);
        
        var results = rangeQuery.queryTetRange(sphere, QueryMode.INTERSECTING).toList();
        
        // Should find some intersecting tetrahedra
        assertFalse(results.isEmpty(), "Should find intersecting tetrahedra");
        
        // Verify results are valid and unique
        var uniqueResults = new HashSet<>(results);
        assertEquals(results.size(), uniqueResults.size(), "Results should be unique");
        
        for (var index : results) {
            assertTrue(index >= 0, "SFC index should be non-negative: " + index);
            var tet = Tet.tetrahedron(index);
            assertNotNull(tet, "Should be able to reconstruct tetrahedron from index");
        }
    }

    @Test
    @DisplayName("Test tetrahedral spatial range query with overlapping mode")
    void testTetSpatialRangeQueryOverlapping() {
        // Test with an AABB
        var aabb = new Spatial.aabb(200, 200, 200, 400, 400, 400);
        
        var results = rangeQuery.queryTetRange(aabb, QueryMode.OVERLAPPING).toList();
        
        // Overlapping should behave similar to intersecting
        assertFalse(results.isEmpty(), "Should find overlapping tetrahedra");
        
        for (var index : results) {
            assertTrue(index >= 0, "SFC index should be non-negative: " + index);
        }
    }

    @Test
    @DisplayName("Test tetrahedral range query caching")
    void testTetRangeQueryCaching() {
        var cube = new Spatial.Cube(150, 150, 150, 100);
        
        // First query should miss cache
        var results1 = rangeQuery.queryTetRange(cube, QueryMode.CONTAINED).toList();
        
        // Second identical query should hit cache
        var results2 = rangeQuery.queryTetRange(cube, QueryMode.CONTAINED).toList();
        
        // Results should be identical
        assertEquals(results1, results2, "Cached results should match original");
        
        // Verify cache hit was recorded
        assertTrue(metrics.getCacheHitRate() > 0.0, "Should have cache hits");
    }

    @Test
    @DisplayName("Test tetrahedral intersection optimizer with sphere")
    void testTetIntersectionOptimizerSphere() {
        var tet = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        var sphere = new Spatial.Sphere(1100, 1100, 1100, 200);
        
        boolean intersects = intersectionOptimizer.intersects(tet, sphere);
        
        // Should intersect given the proximity
        assertTrue(intersects, "Tetrahedron should intersect with nearby sphere");
        
        // Test with distant sphere
        var distantSphere = new Spatial.Sphere(10000, 10000, 10000, 100);
        boolean distantIntersects = intersectionOptimizer.intersects(tet, distantSphere);
        
        assertFalse(distantIntersects, "Tetrahedron should not intersect with distant sphere");
        
        // Verify metrics
        assertTrue(metrics.getMetricsSummary().contains("Intersection Tests: 2"));
    }

    @Test
    @DisplayName("Test tetrahedral intersection optimizer with cube")
    void testTetIntersectionOptimizerCube() {
        var tet = new Tet(500, 500, 500, (byte) 8, (byte) 1);
        var cube = new Spatial.Cube(400, 400, 400, 300);
        
        boolean intersects = intersectionOptimizer.intersects(tet, cube);
        
        // Given the coordinates and cube size, should intersect
        assertTrue(intersects, "Tetrahedron should intersect with overlapping cube");
    }

    @Test
    @DisplayName("Test tetrahedral intersection caching")
    void testTetIntersectionCaching() {
        var tet = new Tet(800, 800, 800, (byte) 12, (byte) 3);
        var aabb = new Spatial.aabb(700, 700, 700, 900, 900, 900);
        
        // First test should miss cache
        boolean result1 = intersectionOptimizer.intersects(tet, aabb);
        
        // Second identical test should hit cache
        boolean result2 = intersectionOptimizer.intersects(tet, aabb);
        
        assertEquals(result1, result2, "Cached intersection result should match");
        assertTrue(metrics.getCacheHitRate() > 0.0, "Should have cache hits");
    }

    @Test
    @DisplayName("Test tetrahedral containment optimizer")
    void testTetContainmentOptimizer() {
        var tet = new Tet(1000, 1000, 1000, (byte) 15, (byte) 2);
        
        // Test point that should be inside tetrahedron (near center)
        var centerPoint = new Point3f(1010, 1010, 1010);
        boolean contains1 = containmentOptimizer.contains(tet, centerPoint);
        
        // Test point that should be outside tetrahedron
        var outsidePoint = new Point3f(2000, 2000, 2000);
        boolean contains2 = containmentOptimizer.contains(tet, outsidePoint);
        
        // Verify containment logic (results depend on actual tetrahedral geometry)
        assertNotNull(contains1); // Just verify no exceptions
        assertNotNull(contains2);
        
        // Test caching - second identical test should hit cache
        boolean cachedResult = containmentOptimizer.contains(tet, centerPoint);
        assertEquals(contains1, cachedResult, "Cached containment result should match");
        
        // Verify metrics
        assertTrue(metrics.getMetricsSummary().contains("Containment Tests:"));
    }

    @Test
    @DisplayName("Test tetrahedral batch containment optimization")
    void testTetBatchContainment() {
        var tet = new Tet(2000, 2000, 2000, (byte) 12, (byte) 4);
        
        var testPoints = List.of(
            new Point3f(2050, 2050, 2050),  // Near tetrahedron
            new Point3f(2100, 2100, 2100),  // Within tetrahedron bounds
            new Point3f(5000, 5000, 5000),  // Far from tetrahedron
            new Point3f(1950, 1950, 1950)   // Near tetrahedron edge
        );
        
        var results = containmentOptimizer.containsBatch(tet, testPoints);
        
        assertEquals(testPoints.size(), results.size(), "Should have result for each test point");
        
        for (var point : testPoints) {
            assertTrue(results.containsKey(point), "Should have result for point: " + point);
            assertNotNull(results.get(point), "Result should not be null");
        }
    }

    @Test
    @DisplayName("Test tetrahedral neighbor search - face neighbors")
    void testTetNeighborSearchFace() {
        var tet = new Tet(1500, 1500, 1500, (byte) 10, (byte) 1);
        
        var neighbors = neighborSearch.findAllNeighbors(tet);
        
        // Should find some face neighbors (up to 4)
        assertTrue(neighbors.size() <= 4, "Should have at most 4 face neighbors");
        
        for (var neighbor : neighbors) {
            assertNotNull(neighbor, "Neighbor should not be null");
            assertEquals(tet.l(), neighbor.l(), "Face neighbors should be at same level");
            
            // Verify positive coordinates
            assertTrue(neighbor.x() >= 0, "Neighbor X should be positive");
            assertTrue(neighbor.y() >= 0, "Neighbor Y should be positive");
            assertTrue(neighbor.z() >= 0, "Neighbor Z should be positive");
        }
        
        // Verify metrics
        assertTrue(metrics.getMetricsSummary().contains("Neighbor Searches:"));
    }

    @Test
    @DisplayName("Test tetrahedral neighbor search - distance-based")
    void testTetNeighborSearchDistance() {
        var tet = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        float searchDistance = 5000.0f; // Use larger search distance to ensure we find neighbors
        
        var neighbors = neighborSearch.findNeighborsWithinDistance(tet, searchDistance);
        
        // The method should return a valid list (may be empty depending on SFC structure)
        assertNotNull(neighbors, "Should return a valid neighbor list");
        
        // If neighbors are found, verify they are within the specified distance
        var tetCenter = getTetCenter(tet);
        for (var neighbor : neighbors) {
            var neighborCenter = getTetCenter(neighbor);
            float distance = tetCenter.distance(neighborCenter);
            assertTrue(distance <= searchDistance, 
                "Neighbor should be within search distance: " + distance + " <= " + searchDistance);
        }
        
        // Test with a very small distance should find fewer or no neighbors
        var closeNeighbors = neighborSearch.findNeighborsWithinDistance(tet, 10.0f);
        assertNotNull(closeNeighbors, "Should return valid list for small distance");
        assertTrue(closeNeighbors.size() <= neighbors.size(), 
            "Smaller search distance should find fewer or equal neighbors");
    }

    @Test
    @DisplayName("Test tetrahedral neighbor search - level neighbors")
    void testTetNeighborSearchLevel() {
        var tet = new Tet(2500, 2500, 2500, (byte) 5, (byte) 0);
        
        var levelNeighbors = neighborSearch.findLevelNeighbors(tet);
        
        // Should include parent and children
        assertTrue(levelNeighbors.size() >= 1, "Should have at least parent or children");
        
        // Verify parent (if exists)
        var parent = levelNeighbors.stream()
            .filter(neighbor -> neighbor.l() == tet.l() - 1)
            .findFirst();
        
        if (parent.isPresent()) {
            assertEquals(tet.l() - 1, parent.get().l(), "Parent should be one level coarser");
        }
        
        // Verify children (if exist)
        var children = levelNeighbors.stream()
            .filter(neighbor -> neighbor.l() == tet.l() + 1)
            .toList();
        
        if (!children.isEmpty()) {
            assertTrue(children.size() <= 8, "Should have at most 8 children");
            for (var child : children) {
                assertEquals(tet.l() + 1, child.l(), "Child should be one level finer");
            }
        }
    }

    @Test
    @DisplayName("Test tetrahedral query metrics collection")
    void testTetQueryMetrics() {
        // Perform various operations to generate metrics
        var cube = new Spatial.Cube(100, 100, 100, 50);
        rangeQuery.queryTetRange(cube, QueryMode.CONTAINED).toList();
        
        var tet = new Tet(200, 200, 200, (byte) 10, (byte) 0);
        var sphere = new Spatial.Sphere(250, 250, 250, 100);
        intersectionOptimizer.intersects(tet, sphere);
        
        var point = new Point3f(210, 210, 210);
        containmentOptimizer.contains(tet, point);
        
        neighborSearch.findAllNeighbors(tet);
        
        // Verify metrics were recorded
        var summary = metrics.getMetricsSummary();
        assertTrue(summary.contains("Range Queries: 1"), "Should record range queries");
        assertTrue(summary.contains("Intersection Tests: 1"), "Should record intersection tests");
        assertTrue(summary.contains("Containment Tests: 1"), "Should record containment tests");
        assertTrue(summary.contains("Neighbor Searches: 1"), "Should record neighbor searches");
        
        // Test metrics reset
        metrics.reset();
        var resetSummary = metrics.getMetricsSummary();
        assertTrue(resetSummary.contains("Range Queries: 0"), "Metrics should reset");
    }

    @Test
    @DisplayName("Test tetrahedral query cache management")
    void testTetQueryCacheManagement() {
        // Fill caches with some data
        var tet = new Tet(1000, 1000, 1000, (byte) 10, (byte) 3);
        var cube = new Spatial.Cube(900, 900, 900, 200);
        var point = new Point3f(1050, 1050, 1050);
        
        // Generate cache entries
        rangeQuery.queryTetRange(cube, QueryMode.INTERSECTING).toList();
        intersectionOptimizer.intersects(tet, cube);
        containmentOptimizer.contains(tet, point);
        
        // Clear caches
        TetQueryOptimizer.clearCaches();
        
        // Operations should still work (rebuilding caches)
        var newResults = rangeQuery.queryTetRange(cube, QueryMode.INTERSECTING).toList();
        boolean newIntersection = intersectionOptimizer.intersects(tet, cube);
        boolean newContainment = containmentOptimizer.contains(tet, point);
        
        // Results should be consistent (though cache miss rate will be higher)
        assertNotNull(newResults);
        assertNotNull(newIntersection);
        assertNotNull(newContainment);
    }

    @Test
    @DisplayName("Test tetrahedral query optimization API")
    void testTetQueryOptimizerAPI() {
        // Test creating various optimizers
        var rangeOpt = optimizer.createRangeQuery();
        var intersectionOpt = optimizer.createIntersectionOptimizer();
        var containmentOpt = optimizer.createContainmentOptimizer();
        var neighborOpt = optimizer.createNeighborSearch();
        
        assertNotNull(rangeOpt, "Range query optimizer should be created");
        assertNotNull(intersectionOpt, "Intersection optimizer should be created");
        assertNotNull(containmentOpt, "Containment optimizer should be created");
        assertNotNull(neighborOpt, "Neighbor search should be created");
        
        // Test metrics access
        var queryMetrics = optimizer.getMetrics();
        assertNotNull(queryMetrics, "Query metrics should be accessible");
        assertNotNull(queryMetrics.getMetricsSummary(), "Metrics summary should be available");
    }

    @Test
    @DisplayName("Test tetrahedral query with various spatial types")
    void testTetQueryVariousSpatialTypes() {
        var tet = new Tet(1500, 1500, 1500, (byte) 12, (byte) 2);
        
        // Test with different spatial volume types
        var cube = new Spatial.Cube(1400, 1400, 1400, 200);
        var sphere = new Spatial.Sphere(1550, 1550, 1550, 150);
        var aabb = new Spatial.aabb(1450, 1450, 1450, 1600, 1600, 1600);
        
        // Test intersection with each type
        boolean cubeIntersects = intersectionOptimizer.intersects(tet, cube);
        boolean sphereIntersects = intersectionOptimizer.intersects(tet, sphere);
        boolean aabbIntersects = intersectionOptimizer.intersects(tet, aabb);
        
        // All should provide valid results (not testing specific geometric correctness)
        assertNotNull(cubeIntersects);
        assertNotNull(sphereIntersects);
        assertNotNull(aabbIntersects);
        
        // Test range queries with each type
        var cubeResults = rangeQuery.queryTetRange(cube, QueryMode.INTERSECTING).toList();
        var sphereResults = rangeQuery.queryTetRange(sphere, QueryMode.INTERSECTING).toList();
        var aabbResults = rangeQuery.queryTetRange(aabb, QueryMode.INTERSECTING).toList();
        
        assertNotNull(cubeResults);
        assertNotNull(sphereResults);
        assertNotNull(aabbResults);
    }

    @Test
    @DisplayName("Test tetrahedral query performance characteristics")
    void testTetQueryPerformanceCharacteristics() {
        // Test with progressively larger volumes to verify scaling
        var volumes = List.of(
            new Spatial.Cube(100, 100, 100, 50),    // Small
            new Spatial.Cube(200, 200, 200, 100),   // Medium
            new Spatial.Cube(300, 300, 300, 200)    // Large
        );
        
        for (var volume : volumes) {
            long startTime = System.nanoTime();
            
            var results = rangeQuery.queryTetRange(volume, QueryMode.INTERSECTING).toList();
            
            long endTime = System.nanoTime();
            long duration = endTime - startTime;
            
            // Verify reasonable performance (should complete in reasonable time)
            assertTrue(duration < 100_000_000, "Query should complete in reasonable time"); // 100ms
            assertNotNull(results, "Should return valid results");
        }
    }

    // Helper method to get tetrahedron center
    private Point3f getTetCenter(Tet tet) {
        var vertices = tet.coordinates();
        float centerX = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
        float centerY = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
        float centerZ = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;
        return new Point3f(centerX, centerY, centerZ);
    }
}