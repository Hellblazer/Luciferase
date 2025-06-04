package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for tetrahedral k-Nearest Neighbor search functionality
 * All test coordinates use positive values only as required by tetrahedral SFC
 * 
 * @author hal.hildebrand
 */
public class TetKNearestNeighborSearchTest {

    private Tetree<String> tetree;
    private final byte testLevel = 15; // Higher resolution for testing

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new TreeMap<>());
        
        // Insert test data points with positive coordinates
        // Use coordinates that will map to different tetrahedra
        int gridSize = Constants.lengthAtLevel(testLevel);
        
        tetree.insert(new Point3f(32.0f, 32.0f, 32.0f), testLevel, "Point1");
        tetree.insert(new Point3f(96.0f, 96.0f, 96.0f), testLevel, "Point2");
        tetree.insert(new Point3f(160.0f, 160.0f, 160.0f), testLevel, "Point3");
        tetree.insert(new Point3f(224.0f, 224.0f, 224.0f), testLevel, "Point4");
        tetree.insert(new Point3f(288.0f, 288.0f, 288.0f), testLevel, "Point5");
        tetree.insert(new Point3f(80.0f, 32.0f, 32.0f), testLevel, "Point6");
        tetree.insert(new Point3f(352.0f, 352.0f, 352.0f), testLevel, "Point7");
        tetree.insert(new Point3f(32.0f, 96.0f, 32.0f), testLevel, "Point8");
    }

    @Test
    void testFindSingleNearestNeighbor() {
        Point3f queryPoint = new Point3f(30.0f, 30.0f, 30.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 1, tetree);
        
        assertEquals(1, results.size());
        // Should be Point1 as it's closest to query point
        assertEquals("Point1", results.get(0).content);
        assertTrue(results.get(0).distance >= 0);
    }

    @Test
    void testFindMultipleNearestNeighbors() {
        Point3f queryPoint = new Point3f(30.0f, 30.0f, 30.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, tetree);
        
        assertEquals(3, results.size());
        
        // Results should be sorted by distance (closest first)
        assertTrue(results.get(0).distance <= results.get(1).distance);
        assertTrue(results.get(1).distance <= results.get(2).distance);
        
        // Point1 should be closest
        assertEquals("Point1", results.get(0).content);
    }

    @Test
    void testFindAllNeighbors() {
        Point3f queryPoint = new Point3f(50.0f, 50.0f, 50.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 10, tetree);
        
        // Should return all 8 points since we asked for 10
        assertEquals(8, results.size());
        
        // Results should be sorted by distance
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distance <= results.get(i + 1).distance);
        }
    }

    @Test
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new TreeMap<>());
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, emptyTetree);
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testZeroK() {
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 0, tetree);
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testNegativeK() {
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, -1, tetree);
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testNegativeCoordinatesThrowsException() {
        Point3f invalidQueryPoint = new Point3f(-10.0f, 10.0f, 10.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetKNearestNeighborSearch.findKNearestNeighbors(invalidQueryPoint, 1, tetree);
        });
    }

    @Test
    void testFindNearestNeighbor() {
        Point3f queryPoint = new Point3f(30.0f, 30.0f, 30.0f);
        
        var result = TetKNearestNeighborSearch.findNearestNeighbor(queryPoint, tetree);
        
        assertNotNull(result);
        assertEquals("Point1", result.content);
        assertTrue(result.distance >= 0);
    }

    @Test
    void testFindNearestNeighborEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new TreeMap<>());
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        var result = TetKNearestNeighborSearch.findNearestNeighbor(queryPoint, emptyTetree);
        
        assertNull(result);
    }

    @Test
    void testFindNeighborsWithinRadius() {
        Point3f queryPoint = new Point3f(30.0f, 30.0f, 30.0f);
        float radius = 100.0f;
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findNeighborsWithinRadius(queryPoint, radius, tetree);
        
        assertFalse(results.isEmpty());
        
        // All results should be within radius
        for (var result : results) {
            assertTrue(result.distance <= radius);
        }
        
        // Results should be sorted by distance
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distance <= results.get(i + 1).distance);
        }
    }

    @Test
    void testFindNeighborsWithinSmallRadius() {
        Point3f queryPoint = new Point3f(30.0f, 30.0f, 30.0f);
        float radius = 1.0f; // Very small radius
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findNeighborsWithinRadius(queryPoint, radius, tetree);
        
        // All results should be within radius
        for (var result : results) {
            assertTrue(result.distance <= radius);
        }
    }

    @Test
    void testFindNeighborsWithinZeroRadius() {
        Point3f queryPoint = new Point3f(30.0f, 30.0f, 30.0f);
        float radius = 0.0f;
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findNeighborsWithinRadius(queryPoint, radius, tetree);
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testConfigurationOptions() {
        Point3f queryPoint = new Point3f(30.0f, 30.0f, 30.0f);
        
        // Test default config
        var defaultConfig = TetKNearestNeighborSearch.KNNConfig.defaultConfig();
        var defaultResults = TetKNearestNeighborSearch.findKNearestNeighbors(
            queryPoint, 3, tetree, defaultConfig);
        assertEquals(3, defaultResults.size());
        
        // Test fast config
        var fastConfig = TetKNearestNeighborSearch.KNNConfig.fastConfig();
        var fastResults = TetKNearestNeighborSearch.findKNearestNeighbors(
            queryPoint, 3, tetree, fastConfig);
        assertEquals(3, fastResults.size());
        
        // Test precise config
        var preciseConfig = TetKNearestNeighborSearch.KNNConfig.preciseConfig();
        var preciseResults = TetKNearestNeighborSearch.findKNearestNeighbors(
            queryPoint, 3, tetree, preciseConfig);
        assertEquals(3, preciseResults.size());
    }

    @Test
    void testLargeCoordinates() {
        Point3f queryPoint = new Point3f(1000.0f, 1000.0f, 1000.0f);
        
        Tetree<String> largeTetree = new Tetree<>(new TreeMap<>());
        largeTetree.insert(new Point3f(999.0f, 999.0f, 999.0f), testLevel, "Large1");
        largeTetree.insert(new Point3f(1001.0f, 1001.0f, 1001.0f), testLevel, "Large2");
        largeTetree.insert(new Point3f(500.0f, 500.0f, 500.0f), testLevel, "Large3");
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 2, largeTetree);
        
        assertEquals(2, results.size());
        assertTrue(results.get(0).distance <= results.get(1).distance);
    }

    @Test
    void testResultConsistency() {
        Point3f queryPoint = new Point3f(50.0f, 50.0f, 50.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results1 = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, tetree);
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results2 = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, tetree);
        
        assertEquals(results1.size(), results2.size());
        for (int i = 0; i < results1.size(); i++) {
            assertEquals(results1.get(i).content, results2.get(i).content);
            assertEquals(results1.get(i).distance, results2.get(i).distance, 0.001f);
        }
    }

    @Test
    void testEdgeCaseQueryAtBoundary() {
        Point3f boundaryPoint = new Point3f(0.1f, 0.1f, 0.1f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(boundaryPoint, 2, tetree);
        
        assertEquals(2, results.size());
        assertTrue(results.get(0).distance <= results.get(1).distance);
    }

    @Test
    void testDistanceCalculation() {
        Point3f queryPoint = new Point3f(0.1f, 0.1f, 0.1f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 1, tetree);
        
        assertEquals(1, results.size());
        assertTrue(results.get(0).distance >= 0);
    }

    @Test
    void testInstrumentedSearch() {
        Point3f queryPoint = new Point3f(30.0f, 30.0f, 30.0f);
        var config = TetKNearestNeighborSearch.KNNConfig.defaultConfig();
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighborsWithStats(queryPoint, 3, tetree, config);
        
        assertEquals(3, results.size());
        
        // Results should be sorted by distance
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distance <= results.get(i + 1).distance);
        }
    }
}