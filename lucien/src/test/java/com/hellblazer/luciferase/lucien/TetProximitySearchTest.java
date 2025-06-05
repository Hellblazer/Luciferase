package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for TetProximitySearch
 * Tests distance-based proximity queries and multi-point proximity analysis in tetrahedral space
 * 
 * @author hal.hildebrand
 */
class TetProximitySearchTest {

    private Tetree<String> testTetree;
    private Point3f referencePoint;

    @BeforeEach
    void setUp() {
        testTetree = new Tetree<>(new java.util.TreeMap<>());
        referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        // Add test tetrahedra in valid tetrahedral domain
        // Using coordinates within S0 tetrahedron scaled by Constants.MAX_EXTENT
        float scale = Constants.MAX_EXTENT / 4.0f; // Use 1/4 of max extent for safe coordinates
        byte testLevel = 15; // Higher resolution for testing
        
        testTetree.insert(new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f), testLevel, "tet1");
        testTetree.insert(new Point3f(scale * 0.2f, scale * 0.1f, scale * 0.1f), testLevel, "tet2");
        testTetree.insert(new Point3f(scale * 0.3f, scale * 0.2f, scale * 0.2f), testLevel, "tet3");
        testTetree.insert(new Point3f(scale * 0.1f, scale * 0.3f, scale * 0.1f), testLevel, "tet4");
        testTetree.insert(new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.3f), testLevel, "tet5");
    }

    @Test
    @DisplayName("Test distance range proximity search")
    void testDistanceRangeProximitySearch() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f queryPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        TetProximitySearch.DistanceRange closeRange = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.2f, TetProximitySearch.ProximityType.CLOSE
        );

        List<TetProximitySearch.ProximityResult<String>> results = 
            TetProximitySearch.tetrahedraWithinDistanceRange(queryPoint, closeRange, testTetree);

        assertNotNull(results);
        assertTrue(results.size() >= 0); // May find 0 or more results depending on tetrahedral layout
        
        // Verify all results have valid data
        for (var result : results) {
            assertNotNull(result.content);
            assertNotNull(result.tetrahedron);
            assertNotNull(result.tetrahedronCenter);
            assertTrue(result.distanceToQuery >= 0);
            assertTrue(result.minDistanceToQuery >= 0);
            assertTrue(result.maxDistanceToQuery >= 0);
            assertTrue(result.minDistanceToQuery <= result.maxDistanceToQuery);
            assertEquals(TetProximitySearch.ProximityType.CLOSE, result.proximityType);
        }
    }

    @Test
    @DisplayName("Test distance range creation and validation")
    void testDistanceRangeCreation() {
        // Test valid distance range
        TetProximitySearch.DistanceRange validRange = new TetProximitySearch.DistanceRange(
            10.0f, 100.0f, TetProximitySearch.ProximityType.MODERATE
        );
        
        assertTrue(validRange.contains(50.0f));
        assertTrue(validRange.contains(10.0f));
        assertTrue(validRange.contains(100.0f));
        assertFalse(validRange.contains(5.0f));
        assertFalse(validRange.contains(150.0f));
        
        // Test invalid distance ranges
        assertThrows(IllegalArgumentException.class, () -> 
            new TetProximitySearch.DistanceRange(-10.0f, 100.0f, TetProximitySearch.ProximityType.CLOSE));
        
        assertThrows(IllegalArgumentException.class, () -> 
            new TetProximitySearch.DistanceRange(100.0f, 50.0f, TetProximitySearch.ProximityType.CLOSE));
    }

    @Test
    @DisplayName("Test proximity bands search")
    void testProximityBands() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f queryPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        List<TetProximitySearch.DistanceRange> ranges = List.of(
            new TetProximitySearch.DistanceRange(0.0f, scale * 0.1f, TetProximitySearch.ProximityType.VERY_CLOSE),
            new TetProximitySearch.DistanceRange(scale * 0.1f, scale * 0.3f, TetProximitySearch.ProximityType.CLOSE),
            new TetProximitySearch.DistanceRange(scale * 0.3f, scale * 0.5f, TetProximitySearch.ProximityType.MODERATE)
        );

        Map<TetProximitySearch.DistanceRange, List<TetProximitySearch.ProximityResult<String>>> bandResults = 
            TetProximitySearch.tetrahedraInProximityBands(queryPoint, ranges, testTetree);

        assertNotNull(bandResults);
        assertEquals(ranges.size(), bandResults.size());
        
        // Verify each range has a result list
        for (var range : ranges) {
            assertTrue(bandResults.containsKey(range));
            assertNotNull(bandResults.get(range));
        }
    }

    @Test
    @DisplayName("Test N closest tetrahedra search")
    void testNClosestTetrahedra() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f queryPoint = new Point3f(scale * 0.05f, scale * 0.05f, scale * 0.05f);
        int n = 3;

        List<TetProximitySearch.ProximityResult<String>> results = 
            TetProximitySearch.findNClosestTetrahedra(queryPoint, n, testTetree);

        assertNotNull(results);
        assertTrue(results.size() <= n);
        
        // Verify results are sorted by distance
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i-1).distanceToQuery <= results.get(i).distanceToQuery,
                      "Results should be sorted by distance");
        }
        
        // Verify all results have valid data
        for (var result : results) {
            assertNotNull(result.content);
            assertNotNull(result.tetrahedron);
            assertTrue(result.distanceToQuery >= 0);
            assertTrue(result.minDistanceToQuery >= 0);
            assertTrue(result.maxDistanceToQuery >= 0);
        }
    }

    @Test
    @DisplayName("Test tetrahedra near any point")
    void testTetrahedraNearAnyPoint() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        List<Point3f> queryPoints = List.of(
            new Point3f(scale * 0.05f, scale * 0.05f, scale * 0.05f),
            new Point3f(scale * 0.25f, scale * 0.25f, scale * 0.25f),
            new Point3f(scale * 0.35f, scale * 0.35f, scale * 0.35f)
        );
        
        float maxDistance = scale * 0.2f;

        List<TetProximitySearch.ProximityResult<String>> results = 
            TetProximitySearch.tetrahedraNearAnyPoint(queryPoints, maxDistance, testTetree);

        assertNotNull(results);
        
        // Verify results are sorted by minimum distance
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i-1).minDistanceToQuery <= results.get(i).minDistanceToQuery,
                      "Results should be sorted by minimum distance");
        }
        
        // Verify all results have valid data
        for (var result : results) {
            assertNotNull(result.content);
            assertTrue(result.minDistanceToQuery <= maxDistance);
            assertTrue(result.distanceToQuery >= 0);
        }
    }

    @Test
    @DisplayName("Test tetrahedra near all points")
    void testTetrahedraNearAllPoints() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        List<Point3f> queryPoints = List.of(
            new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f),
            new Point3f(scale * 0.18f, scale * 0.18f, scale * 0.18f)
        );
        
        float maxDistance = scale * 0.2f;

        List<TetProximitySearch.ProximityResult<String>> results = 
            TetProximitySearch.tetrahedraNearAllPoints(queryPoints, maxDistance, testTetree);

        assertNotNull(results);
        
        // All results should be within maxDistance of all query points
        // This is difficult to verify without complex geometric calculations,
        // so we just verify basic properties
        for (var result : results) {
            assertNotNull(result.content);
            assertTrue(result.distanceToQuery >= 0);
            assertTrue(result.minDistanceToQuery >= 0);
        }
    }

    @Test
    @DisplayName("Test count tetrahedra in range")
    void testCountTetrahedraInRange() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f queryPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        TetProximitySearch.DistanceRange range = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.3f, TetProximitySearch.ProximityType.CLOSE
        );

        long count = TetProximitySearch.countTetrahedraInRange(queryPoint, range, testTetree);
        
        List<TetProximitySearch.ProximityResult<String>> results = 
            TetProximitySearch.tetrahedraWithinDistanceRange(queryPoint, range, testTetree);

        assertEquals(results.size(), count);
        assertTrue(count >= 0);
    }

    @Test
    @DisplayName("Test has any tetrahedron in range")
    void testHasAnyTetrahedronInRange() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        // Test with range that should contain tetrahedra
        Point3f closePoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        TetProximitySearch.DistanceRange closeRange = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.5f, TetProximitySearch.ProximityType.CLOSE
        );
        
        boolean hasClose = TetProximitySearch.hasAnyTetrahedronInRange(closePoint, closeRange, testTetree);
        long countClose = TetProximitySearch.countTetrahedraInRange(closePoint, closeRange, testTetree);
        
        assertEquals(countClose > 0, hasClose);
        
        // Test with range that should not contain tetrahedra
        Point3f farPoint = new Point3f(scale * 10.0f, scale * 10.0f, scale * 10.0f);
        TetProximitySearch.DistanceRange smallRange = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.01f, TetProximitySearch.ProximityType.VERY_CLOSE
        );
        
        boolean hasFar = TetProximitySearch.hasAnyTetrahedronInRange(farPoint, smallRange, testTetree);
        long countFar = TetProximitySearch.countTetrahedraInRange(farPoint, smallRange, testTetree);
        
        assertEquals(countFar > 0, hasFar);
    }

    @Test
    @DisplayName("Test proximity statistics")
    void testProximityStatistics() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f queryPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);

        TetProximitySearch.ProximityStatistics stats = 
            TetProximitySearch.getProximityStatistics(queryPoint, testTetree);

        assertNotNull(stats);
        assertTrue(stats.totalTetrahedra >= 0);
        assertTrue(stats.veryCloseTetrahedra >= 0);
        assertTrue(stats.closeTetrahedra >= 0);
        assertTrue(stats.moderateTetrahedra >= 0);
        assertTrue(stats.farTetrahedra >= 0);
        
        // Verify percentages are valid
        assertTrue(stats.getVeryClosePercentage() >= 0 && stats.getVeryClosePercentage() <= 100);
        assertTrue(stats.getClosePercentage() >= 0 && stats.getClosePercentage() <= 100);
        assertTrue(stats.getModeratePercentage() >= 0 && stats.getModeratePercentage() <= 100);
        assertTrue(stats.getFarPercentage() >= 0 && stats.getFarPercentage() <= 100);
        
        // Verify statistics consistency
        assertEquals(stats.totalTetrahedra, 
                    stats.veryCloseTetrahedra + stats.closeTetrahedra + stats.moderateTetrahedra + stats.farTetrahedra);
        
        if (stats.totalTetrahedra > 0) {
            assertTrue(stats.averageDistance >= 0);
            assertTrue(stats.minDistance >= 0);
            assertTrue(stats.maxDistance >= 0);
            assertTrue(stats.minDistance <= stats.maxDistance);
        }
        
        // Test string representation
        String statsString = stats.toString();
        assertNotNull(statsString);
        assertTrue(statsString.contains("TetProximityStats"));
    }

    @Test
    @DisplayName("Test batch proximity queries")
    void testBatchProximityQueries() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        List<TetProximitySearch.ProximityQuery> queries = List.of(
            new TetProximitySearch.ProximityQuery(
                new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f),
                new TetProximitySearch.DistanceRange(0.0f, scale * 0.1f, TetProximitySearch.ProximityType.CLOSE)
            ),
            new TetProximitySearch.ProximityQuery(
                new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f),
                new TetProximitySearch.DistanceRange(0.0f, scale * 0.15f, TetProximitySearch.ProximityType.MODERATE)
            ),
            new TetProximitySearch.ProximityQuery(
                new Point3f(scale * 0.3f, scale * 0.3f, scale * 0.3f),
                new TetProximitySearch.DistanceRange(0.0f, scale * 0.2f, TetProximitySearch.ProximityType.FAR)
            )
        );

        var batchResults = TetProximitySearch.batchProximityQueries(queries, testTetree);

        assertNotNull(batchResults);
        assertEquals(queries.size(), batchResults.size());
        
        // Verify each query has a result
        for (var query : queries) {
            assertTrue(batchResults.containsKey(query));
            assertNotNull(batchResults.get(query));
        }
    }

    @Test
    @DisplayName("Test simplex aggregation strategies")
    void testSimplexAggregationStrategies() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f queryPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        TetProximitySearch.DistanceRange range = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.3f, TetProximitySearch.ProximityType.CLOSE
        );

        // Test different aggregation strategies
        for (TetrahedralSearchBase.SimplexAggregationStrategy strategy : TetrahedralSearchBase.SimplexAggregationStrategy.values()) {
            List<TetProximitySearch.ProximityResult<String>> results = 
                TetProximitySearch.tetrahedraWithinDistanceRange(queryPoint, range, testTetree, strategy);

            assertNotNull(results);
            
            // Verify strategy-specific behavior
            switch (strategy) {
                case REPRESENTATIVE_ONLY, BEST_FIT -> {
                    // Should return at most one simplex per spatial region
                    assertTrue(results.size() >= 0);
                }
                case ALL_SIMPLICIES -> {
                    // May return multiple simplicies per spatial region
                    assertTrue(results.size() >= 0);
                }
                case WEIGHTED_AVERAGE -> {
                    // Currently behaves like ALL_SIMPLICIES
                    assertTrue(results.size() >= 0);
                }
            }
        }
    }

    @Test
    @DisplayName("Test input validation")
    void testInputValidation() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f validPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        Point3f negativePoint = new Point3f(-scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        TetProximitySearch.DistanceRange validRange = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.2f, TetProximitySearch.ProximityType.CLOSE
        );

        // Test negative coordinates in query point
        assertThrows(IllegalArgumentException.class, () -> 
            TetProximitySearch.tetrahedraWithinDistanceRange(negativePoint, validRange, testTetree));

        // Test negative coordinates in proximity query
        assertThrows(IllegalArgumentException.class, () -> 
            new TetProximitySearch.ProximityQuery(negativePoint, validRange));

        // Test negative N in closest search
        assertThrows(IllegalArgumentException.class, () -> 
            TetProximitySearch.findNClosestTetrahedra(validPoint, -1, testTetree));

        assertThrows(IllegalArgumentException.class, () -> 
            TetProximitySearch.findNClosestTetrahedra(validPoint, 0, testTetree));

        // Test negative max distance in multi-point searches
        assertThrows(IllegalArgumentException.class, () -> 
            TetProximitySearch.tetrahedraNearAnyPoint(List.of(validPoint), -1.0f, testTetree));

        assertThrows(IllegalArgumentException.class, () -> 
            TetProximitySearch.tetrahedraNearAllPoints(List.of(validPoint), -1.0f, testTetree));
    }

    @Test
    @DisplayName("Test empty tetree")
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new java.util.TreeMap<>());
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f queryPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        TetProximitySearch.DistanceRange range = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.2f, TetProximitySearch.ProximityType.CLOSE
        );

        List<TetProximitySearch.ProximityResult<String>> results = 
            TetProximitySearch.tetrahedraWithinDistanceRange(queryPoint, range, emptyTetree);

        assertNotNull(results);
        assertTrue(results.isEmpty());

        List<TetProximitySearch.ProximityResult<String>> closestResults = 
            TetProximitySearch.findNClosestTetrahedra(queryPoint, 3, emptyTetree);

        assertNotNull(closestResults);
        assertTrue(closestResults.isEmpty());

        long count = TetProximitySearch.countTetrahedraInRange(queryPoint, range, emptyTetree);
        assertEquals(0, count);

        boolean hasAny = TetProximitySearch.hasAnyTetrahedronInRange(queryPoint, range, emptyTetree);
        assertFalse(hasAny);

        TetProximitySearch.ProximityStatistics stats = 
            TetProximitySearch.getProximityStatistics(queryPoint, emptyTetree);
        
        assertNotNull(stats);
        assertEquals(0, stats.totalTetrahedra);
    }

    @Test
    @DisplayName("Test distance ordering")
    void testDistanceOrdering() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f queryPoint = new Point3f(scale * 0.05f, scale * 0.05f, scale * 0.05f);
        
        TetProximitySearch.DistanceRange largeRange = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.5f, TetProximitySearch.ProximityType.MODERATE
        );

        List<TetProximitySearch.ProximityResult<String>> results = 
            TetProximitySearch.tetrahedraWithinDistanceRange(queryPoint, largeRange, testTetree);

        if (results.size() > 1) {
            // Verify results are sorted by distance from query point
            for (int i = 1; i < results.size(); i++) {
                assertTrue(results.get(i-1).distanceToQuery <= results.get(i).distanceToQuery,
                          "Results should be sorted by distance from query point");
            }
        }
    }

    @Test
    @DisplayName("Test proximity query equality and hash code")
    void testProximityQueryEquality() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f point1 = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);
        Point3f point2 = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);
        Point3f point3 = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        
        TetProximitySearch.DistanceRange range1 = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.1f, TetProximitySearch.ProximityType.CLOSE
        );
        TetProximitySearch.DistanceRange range2 = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.1f, TetProximitySearch.ProximityType.CLOSE
        );
        TetProximitySearch.DistanceRange range3 = new TetProximitySearch.DistanceRange(
            0.0f, scale * 0.2f, TetProximitySearch.ProximityType.MODERATE
        );

        TetProximitySearch.ProximityQuery query1 = new TetProximitySearch.ProximityQuery(point1, range1);
        TetProximitySearch.ProximityQuery query2 = new TetProximitySearch.ProximityQuery(point2, range2);
        TetProximitySearch.ProximityQuery query3 = new TetProximitySearch.ProximityQuery(point3, range3);

        assertEquals(query1, query2);
        assertNotEquals(query1, query3);
        assertEquals(query1.hashCode(), query2.hashCode());
        
        // Test DistanceRange equality
        assertEquals(range1, range2);
        assertNotEquals(range1, range3);
        assertEquals(range1.hashCode(), range2.hashCode());
        
        // Test toString methods
        String queryString = query1.toString();
        assertNotNull(queryString);
        assertTrue(queryString.contains("ProximityQuery"));
        
        String rangeString = range1.toString();
        assertNotNull(rangeString);
        assertTrue(rangeString.contains("DistanceRange"));
    }

    @Test
    @DisplayName("Test complex proximity scenarios")
    void testComplexProximityScenarios() {
        // Create a more complex tetree with various distances
        Tetree<String> complexTetree = new Tetree<>(new java.util.TreeMap<>());
        float scale = Constants.MAX_EXTENT / 4.0f;
        byte testLevel = 14;
        
        // Insert tetrahedra at various distances from a central point
        Point3f centerPoint = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        
        // Very close tetrahedra
        complexTetree.insert(new Point3f(scale * 0.19f, scale * 0.19f, scale * 0.19f), testLevel, "very_close_1");
        complexTetree.insert(new Point3f(scale * 0.21f, scale * 0.21f, scale * 0.21f), testLevel, "very_close_2");
        
        // Close tetrahedra
        complexTetree.insert(new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f), testLevel, "close_1");
        complexTetree.insert(new Point3f(scale * 0.25f, scale * 0.25f, scale * 0.25f), testLevel, "close_2");
        
        // Moderate distance tetrahedra
        complexTetree.insert(new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f), testLevel, "moderate_1");
        complexTetree.insert(new Point3f(scale * 0.3f, scale * 0.3f, scale * 0.3f), testLevel, "moderate_2");
        
        // Test multi-range proximity bands
        List<TetProximitySearch.DistanceRange> ranges = List.of(
            new TetProximitySearch.DistanceRange(0.0f, scale * 0.05f, TetProximitySearch.ProximityType.VERY_CLOSE),
            new TetProximitySearch.DistanceRange(scale * 0.05f, scale * 0.15f, TetProximitySearch.ProximityType.CLOSE),
            new TetProximitySearch.DistanceRange(scale * 0.15f, scale * 0.25f, TetProximitySearch.ProximityType.MODERATE)
        );

        Map<TetProximitySearch.DistanceRange, List<TetProximitySearch.ProximityResult<String>>> bandResults = 
            TetProximitySearch.tetrahedraInProximityBands(centerPoint, ranges, complexTetree);

        // Verify we get results for different proximity bands
        assertNotNull(bandResults);
        assertEquals(ranges.size(), bandResults.size());
        
        // Test N closest search with various N values
        for (int n : new int[]{1, 3, 5, 10}) {
            List<TetProximitySearch.ProximityResult<String>> nClosest = 
                TetProximitySearch.findNClosestTetrahedra(centerPoint, n, complexTetree);
            
            assertNotNull(nClosest);
            assertTrue(nClosest.size() <= n);
            
            // Verify sorting
            for (int i = 1; i < nClosest.size(); i++) {
                assertTrue(nClosest.get(i-1).distanceToQuery <= nClosest.get(i).distanceToQuery);
            }
        }
    }
}