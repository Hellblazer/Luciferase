package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for k-Nearest Neighbor search functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class KNearestNeighborSearchTest {

    private Octree<String> octree;
    private final byte testLevel = 15; // Higher resolution for testing smaller coordinates

    @BeforeEach
    void setUp() {
        octree = new Octree<>();
        
        // Use coordinates that will map to different cubes
        // At level 15, grid size is 64, so use multiples and offsets of 64
        int gridSize = Constants.lengthAtLevel(testLevel);
        
        // Insert test data points - all with positive coordinates
        octree.insert(new Point3f(32.0f, 32.0f, 32.0f), testLevel, "Point1");           // Will go to (0,0,0)
        octree.insert(new Point3f(96.0f, 96.0f, 96.0f), testLevel, "Point2");           // Will go to (64,64,64)
        octree.insert(new Point3f(160.0f, 160.0f, 160.0f), testLevel, "Point3");        // Will go to (128,128,128)
        octree.insert(new Point3f(224.0f, 224.0f, 224.0f), testLevel, "Point4");        // Will go to (192,192,192)
        octree.insert(new Point3f(288.0f, 288.0f, 288.0f), testLevel, "Point5");        // Will go to (256,256,256)
        octree.insert(new Point3f(80.0f, 32.0f, 32.0f), testLevel, "Point6");           // Will go to (64,0,0)
        octree.insert(new Point3f(352.0f, 352.0f, 352.0f), testLevel, "Point7");        // Will go to (320,320,320)
        octree.insert(new Point3f(32.0f, 96.0f, 32.0f), testLevel, "Point8");           // Will go to (0,64,0)
    }

    @Test
    void testFindSingleNearestNeighbor() {
        Point3f queryPoint = new Point3f(11.0f, 11.0f, 11.0f);
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 1, octree);
        
        assertEquals(1, results.size());
        assertEquals("Point1", results.get(0).content);
        // Distance should be 0 since query point is inside Point1's cube
        assertEquals(0.0f, results.get(0).distance, 0.001f);
    }

    @Test
    void testFindMultipleNearestNeighbors() {
        Point3f queryPoint = new Point3f(12.0f, 12.0f, 12.0f);
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, octree);
        
        assertEquals(3, results.size());
        
        // Results should be sorted by distance (closest first)
        assertTrue(results.get(0).distance <= results.get(1).distance);
        assertTrue(results.get(1).distance <= results.get(2).distance);
        
        // The closest should be Point1 (cube contains 12,12,12)
        assertEquals("Point1", results.get(0).content);
        assertEquals(0.0f, results.get(0).distance, 0.001f);
    }

    @Test
    void testFindAllPoints() {
        Point3f queryPoint = new Point3f(20.0f, 20.0f, 20.0f);
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 10, octree);
        
        // Should return all 8 points since we asked for 10
        assertEquals(8, results.size());
        
        // Results should be sorted by distance
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distance <= results.get(i + 1).distance);
        }
        
        // Point1 should be closest (cube contains 20,20,20)
        assertEquals("Point1", results.get(0).content);
        assertEquals(0.0f, results.get(0).distance, 0.001f);
    }

    @Test
    void testEmptyOctree() {
        Octree<String> emptyOctree = new Octree<>();
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, emptyOctree);
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testZeroK() {
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 0, octree);
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testNegativeK() {
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, -1, octree);
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testNegativeCoordinatesThrowsException() {
        Point3f invalidQueryPoint = new Point3f(-10.0f, 10.0f, 10.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            KNearestNeighborSearch.findKNearestNeighbors(invalidQueryPoint, 1, octree);
        });
    }

    @Test
    void testDistanceCalculation() {
        // Test with known distances using points that map to different cubes
        Point3f queryPoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        // Insert a point that will map to a different cube
        Octree<String> testOctree = new Octree<>();
        int gridSize = Constants.lengthAtLevel(testLevel);
        testOctree.insert(new Point3f(gridSize + 32.0f, gridSize + 32.0f, gridSize + 32.0f), testLevel, "DistantPoint");
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 1, testOctree);
        
        assertEquals(1, results.size());
        // Distance should be > 0 since query point is outside the cube
        assertTrue(results.get(0).distance > 0);
    }

    @Test
    void testEdgeCaseQueryAtBoundary() {
        // Test query point at coordinate boundaries
        Point3f boundaryPoint = new Point3f(0.1f, 0.1f, 0.1f);
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results = 
            KNearestNeighborSearch.findKNearestNeighbors(boundaryPoint, 2, octree);
        
        assertEquals(2, results.size());
        assertTrue(results.get(0).distance <= results.get(1).distance);
    }

    @Test
    void testLargeCoordinates() {
        // Test with larger coordinate values
        Point3f queryPoint = new Point3f(1000.0f, 1000.0f, 1000.0f);
        
        Octree<String> largeOctree = new Octree<>();
        largeOctree.insert(new Point3f(999.0f, 999.0f, 999.0f), testLevel, "Large1");
        largeOctree.insert(new Point3f(1001.0f, 1001.0f, 1001.0f), testLevel, "Large2");
        largeOctree.insert(new Point3f(500.0f, 500.0f, 500.0f), testLevel, "Large3");
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 2, largeOctree);
        
        assertEquals(2, results.size());
        // Should find the two closest points
        assertTrue(results.get(0).distance < results.get(1).distance);
    }

    @Test
    void testResultConsistency() {
        // Test that multiple calls with same parameters return same results
        Point3f queryPoint = new Point3f(15.0f, 15.0f, 15.0f);
        
        List<KNearestNeighborSearch.KNNCandidate<String>> results1 = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, octree);
        List<KNearestNeighborSearch.KNNCandidate<String>> results2 = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, octree);
        
        assertEquals(results1.size(), results2.size());
        for (int i = 0; i < results1.size(); i++) {
            assertEquals(results1.get(i).content, results2.get(i).content);
            assertEquals(results1.get(i).distance, results2.get(i).distance, 0.001f);
        }
    }
}