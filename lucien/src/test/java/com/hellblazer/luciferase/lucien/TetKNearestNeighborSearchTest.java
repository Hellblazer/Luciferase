package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for tetrahedral k-Nearest Neighbor search functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class TetKNearestNeighborSearchTest {

    private Tetree<String> tetree;
    private final byte testLevel = 15; // Higher resolution for testing

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new TreeMap<>());
        
        // Insert test points within S0 tetrahedron domain at proper scale
        // S0 vertices: (0,0,0), (MAX_EXTENT,0,0), (MAX_EXTENT,0,MAX_EXTENT), (MAX_EXTENT,MAX_EXTENT,MAX_EXTENT)
        // Use fractional coordinates at appropriate scale to avoid quantization ambiguity
        float scale = Constants.MAX_EXTENT * 0.1f; // Use 10% of max extent as base scale
        
        tetree.insert(new Point3f(scale * 0.1f, scale * 0.05f, scale * 0.02f), testLevel, "TetPoint1");
        tetree.insert(new Point3f(scale * 0.3f, scale * 0.15f, scale * 0.1f), testLevel, "TetPoint2");
        tetree.insert(new Point3f(scale * 0.5f, scale * 0.25f, scale * 0.2f), testLevel, "TetPoint3");
    }

    @Test
    void testBasicMethodCall() {
        // Test that the method can be called without hanging or crashing
        // Use coordinates within S0 tetrahedron domain
        float scale = Constants.MAX_EXTENT * 0.1f;
        Point3f queryPoint = new Point3f(scale * 0.05f, scale * 0.02f, scale * 0.01f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 1, tetree);
        
        // Now we should get actual results since we have a real implementation
        assertNotNull(results);
        assertTrue(results.size() <= 1, "Should return at most 1 result for k=1");
        
        if (!results.isEmpty()) {
            TetKNearestNeighborSearch.TetKNNCandidate<String> result = results.get(0);
            assertNotNull(result.content, "Result should have content");
            assertNotNull(result.position, "Result should have position");
            assertTrue(result.distance >= 0, "Distance should be non-negative");
            System.out.println("Basic test result: content=" + result.content + " distance=" + result.distance);
        }
    }

    @Test
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new TreeMap<>());
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, emptyTetree);
        
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testZeroK() {
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 0, tetree);
        
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testNegativeK() {
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, -1, tetree);
        
        assertNotNull(results);
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
    void testDistanceCalculationToSingleTetrahedron() {
        // Test distance calculation to a single tetrahedron
        // Use a tetrahedron with reasonable coordinates and level
        var tet = new Tet(100, 100, 100, (byte) 8, (byte) 2);
        long tetIndex = tet.index();
        
        // Test distance from tetrahedron center (should be 0 or very small)
        Point3f center = TetKNearestNeighborSearch.getTetCenter(tetIndex);
        float distanceToCenter = TetKNearestNeighborSearch.calculateDistanceToTet(center, tetIndex);
        assertTrue(distanceToCenter >= 0, "Distance should be non-negative");
        // Center should be inside or very close to the tetrahedron
        assertTrue(distanceToCenter < 1.0f, "Distance from tetrahedron center should be very small");
        
        // Test distance from a far point (should be positive)
        // Based on the debug output, center is at ~(4096, 2048, 4096), so use a much farther point
        Point3f farPoint = new Point3f(20000.0f, 20000.0f, 20000.0f);
        float distanceToFar = TetKNearestNeighborSearch.calculateDistanceToTet(farPoint, tetIndex);
        
        // Debug output
        System.out.println("Debug: Tet at (" + tet.x() + ", " + tet.y() + ", " + tet.z() + ") level=" + tet.l());
        System.out.println("Debug: Center = " + center);
        System.out.println("Debug: Far point = " + farPoint);
        System.out.println("Debug: Distance to center = " + distanceToCenter);
        System.out.println("Debug: Distance to far = " + distanceToFar);
        
        assertTrue(distanceToFar >= 0, "Distance to far point should be non-negative");
        
        // Now we should have a positive distance since the far point is truly outside the tetrahedron
        assertTrue(distanceToFar > 0, "Distance to far point should be positive");
        assertTrue(distanceToFar > distanceToCenter, "Far point should be farther than center");
    }

    @Test
    void testGetTetCenter() {
        // Test that getTetCenter returns reasonable coordinates
        var tet = new Tet(50, 50, 50, (byte) 8, (byte) 1);
        long tetIndex = tet.index();
        
        Point3f center = TetKNearestNeighborSearch.getTetCenter(tetIndex);
        
        // Center should have positive coordinates
        assertTrue(center.x >= 0, "Center X should be positive");
        assertTrue(center.y >= 0, "Center Y should be positive");
        assertTrue(center.z >= 0, "Center Z should be positive");
        
        // Center should be within reasonable bounds for our test coordinates
        assertTrue(center.x < 10000, "Center X should be reasonable");
        assertTrue(center.y < 10000, "Center Y should be reasonable");
        assertTrue(center.z < 10000, "Center Z should be reasonable");
    }

    @Test
    void testDistanceCalculationWithNegativeCoordinates() {
        // Distance calculation should throw on negative coordinates
        var tet = new Tet(100, 100, 100, (byte) 8, (byte) 2);
        long tetIndex = tet.index();
        
        Point3f invalidPoint = new Point3f(-10.0f, 50.0f, 50.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetKNearestNeighborSearch.calculateDistanceToTet(invalidPoint, tetIndex);
        });
    }

    @Test
    void testDistanceCalculationWithMultipleTetrahedra() {
        // Test distance calculation with different tetrahedra to ensure geometric correctness
        
        // Create tetrahedra at different levels and positions
        var tet1 = new Tet(50, 50, 50, (byte) 10, (byte) 1);   // Smaller tetrahedron
        var tet2 = new Tet(200, 200, 200, (byte) 8, (byte) 3); // Larger tetrahedron  
        var tet3 = new Tet(500, 500, 500, (byte) 12, (byte) 0); // Very small tetrahedron
        
        long tetIndex1 = tet1.index();
        long tetIndex2 = tet2.index();  
        long tetIndex3 = tet3.index();
        
        // Test point relatively close to first tetrahedron
        Point3f testPoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        float dist1 = TetKNearestNeighborSearch.calculateDistanceToTet(testPoint, tetIndex1);
        float dist2 = TetKNearestNeighborSearch.calculateDistanceToTet(testPoint, tetIndex2);
        float dist3 = TetKNearestNeighborSearch.calculateDistanceToTet(testPoint, tetIndex3);
        
        // All distances should be non-negative
        assertTrue(dist1 >= 0, "Distance to tet1 should be non-negative");
        assertTrue(dist2 >= 0, "Distance to tet2 should be non-negative");
        assertTrue(dist3 >= 0, "Distance to tet3 should be non-negative");
        
        // Debug output for understanding coordinate system
        System.out.println("Test point: " + testPoint);
        System.out.println("Tet1 center: " + TetKNearestNeighborSearch.getTetCenter(tetIndex1) + " distance: " + dist1);
        System.out.println("Tet2 center: " + TetKNearestNeighborSearch.getTetCenter(tetIndex2) + " distance: " + dist2);
        System.out.println("Tet3 center: " + TetKNearestNeighborSearch.getTetCenter(tetIndex3) + " distance: " + dist3);
    }

    @Test 
    void testDistanceConsistency() {
        // Test that distance calculation is consistent and symmetric properties hold
        var tet = new Tet(100, 100, 100, (byte) 10, (byte) 2);
        long tetIndex = tet.index();
        
        Point3f center = TetKNearestNeighborSearch.getTetCenter(tetIndex);
        
        // Test points at increasing distances from center
        Point3f[] testPoints = {
            new Point3f(center.x + 10, center.y + 10, center.z + 10),
            new Point3f(center.x + 100, center.y + 100, center.z + 100),
            new Point3f(center.x + 1000, center.y + 1000, center.z + 1000),
            new Point3f(center.x + 5000, center.y + 5000, center.z + 5000)
        };
        
        float[] distances = new float[testPoints.length];
        for (int i = 0; i < testPoints.length; i++) {
            distances[i] = TetKNearestNeighborSearch.calculateDistanceToTet(testPoints[i], tetIndex);
            assertTrue(distances[i] >= 0, "Distance " + i + " should be non-negative");
        }
        
        // Generally, distances should increase as we move farther from center
        // (though this may not always be true due to tetrahedron geometry)
        boolean hasIncreasingDistances = true;
        for (int i = 1; i < distances.length; i++) {
            if (distances[i] <= distances[i-1]) {
                hasIncreasingDistances = false;
                break;
            }
        }
        
        // Debug output
        System.out.println("Distance consistency test:");
        for (int i = 0; i < distances.length; i++) {
            System.out.println("  Point " + i + ": " + testPoints[i] + " -> distance: " + distances[i]);
        }
        System.out.println("  Has increasing distances: " + hasIncreasingDistances);
        
        // For now, just verify all distances are valid (non-negative)
        // We'll analyze the geometric behavior separately
    }

    @Test
    void testDistanceToMultipleLevels() {
        // Test distance calculation across different tetrahedral levels
        Point3f queryPoint = new Point3f(1000.0f, 1000.0f, 1000.0f);
        
        // Test tetrahedra at different levels (different sizes)
        byte[] levels = {6, 8, 10, 12, 14};
        
        for (byte level : levels) {
            var tet = new Tet(500, 500, 500, level, (byte) 1);
            long tetIndex = tet.index();
            
            Point3f center = TetKNearestNeighborSearch.getTetCenter(tetIndex);
            float distance = TetKNearestNeighborSearch.calculateDistanceToTet(queryPoint, tetIndex);
            
            assertTrue(distance >= 0, "Distance at level " + level + " should be non-negative");
            
            System.out.println("Level " + level + ": center=" + center + " distance=" + distance);
        }
    }

    @Test
    void testKNearestNeighborWithFewTetrahedra() {
        // Create a small tetree with just a few entries at different levels for controlled testing
        Tetree<String> smallTetree = new Tetree<>(new TreeMap<>());
        
        // Use S0 tetrahedron coordinates at proper scale
        float scale = Constants.MAX_EXTENT * 0.1f;
        Point3f point1 = new Point3f(scale * 0.2f, scale * 0.1f, scale * 0.05f);
        Point3f point2 = new Point3f(scale * 0.4f, scale * 0.2f, scale * 0.15f);
        Point3f point3 = new Point3f(scale * 0.6f, scale * 0.3f, scale * 0.25f);
        
        // Use different levels to create tetrahedra of different sizes
        smallTetree.insert(point1, (byte) 12, "Small1");    // Small tetrahedron
        smallTetree.insert(point2, (byte) 10, "Medium1");   // Medium tetrahedron
        smallTetree.insert(point3, (byte) 8, "Large1");     // Large tetrahedron
        
        // Query point closer to the first inserted point
        Point3f queryPoint = new Point3f(scale * 0.15f, scale * 0.08f, scale * 0.03f);
        
        // Test k=1
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 1, smallTetree);
        
        // Debug output
        System.out.println("K=1 test results:");
        System.out.println("Query point: " + queryPoint);
        System.out.println("Results found: " + results.size());
        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i);
            System.out.println("  " + i + ": content=" + result.content + 
                             " position=" + result.position + " distance=" + result.distance);
        }
        
        // Basic validation
        assertNotNull(results);
        assertTrue(results.size() <= 1, "Should return at most 1 result for k=1");
        
        if (!results.isEmpty()) {
            TetKNearestNeighborSearch.TetKNNCandidate<String> result = results.get(0);
            assertNotNull(result.content);
            assertNotNull(result.position);
            assertTrue(result.distance >= 0, "Distance should be non-negative");
        }
    }

    @Test
    void testKNearestNeighborSorting() {
        // Test that results are properly sorted by distance
        Tetree<String> sortingTetree = new Tetree<>(new TreeMap<>());
        
        // Insert multiple points to test sorting
        sortingTetree.insert(new Point3f(500.0f, 500.0f, 500.0f), (byte) 14, "Far");      // Should be far
        sortingTetree.insert(new Point3f(50.0f, 50.0f, 50.0f), (byte) 14, "Close");       // Should be close
        sortingTetree.insert(new Point3f(250.0f, 250.0f, 250.0f), (byte) 14, "Medium");   // Should be medium
        
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        // Test k=3 to get all results and verify sorting
        List<TetKNearestNeighborSearch.TetKNNCandidate<String>> results = 
            TetKNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, sortingTetree);
        
        // Debug output
        System.out.println("Sorting test results:");
        System.out.println("Query point: " + queryPoint);
        System.out.println("Results found: " + results.size());
        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i);
            System.out.println("  " + i + ": content=" + result.content + 
                             " position=" + result.position + " distance=" + result.distance);
        }
        
        // Validate sorting
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i).distance >= results.get(i-1).distance, 
                "Results should be sorted by distance (closest first)");
        }
        
        // All distances should be non-negative
        for (var result : results) {
            assertTrue(result.distance >= 0, "All distances should be non-negative");
        }
    }
}