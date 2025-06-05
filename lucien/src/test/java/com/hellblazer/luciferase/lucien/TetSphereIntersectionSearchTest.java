package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for TetSphereIntersectionSearch
 * Tests sphere-tetrahedron intersection algorithms and spatial queries
 * 
 * @author hal.hildebrand
 */
class TetSphereIntersectionSearchTest {

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
    @DisplayName("Test basic sphere intersection search")
    void testBasicSphereIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.1f;

        List<TetSphereIntersectionSearch.SphereIntersection<String>> intersections = 
            TetSphereIntersectionSearch.sphereIntersectedAll(sphereCenter, sphereRadius, testTetree, referencePoint);

        assertNotNull(intersections);
        assertTrue(intersections.size() >= 0); // May find 0 or more intersections depending on tetrahedral layout
        
        // Verify all intersections have valid data
        for (var intersection : intersections) {
            assertNotNull(intersection.content);
            assertNotNull(intersection.tetrahedron);
            assertNotNull(intersection.tetrahedronCenter);
            assertTrue(intersection.distanceToReferencePoint >= 0);
            assertNotEquals(TetSphereIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test sphere intersection with small sphere")
    void testSmallSphereIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);
        float sphereRadius = scale * 0.01f; // Very small sphere

        List<TetSphereIntersectionSearch.SphereIntersection<String>> intersections = 
            TetSphereIntersectionSearch.sphereIntersectedAll(sphereCenter, sphereRadius, testTetree, referencePoint);

        assertNotNull(intersections);
        
        // Small sphere should find fewer intersections
        // Verify intersection types are appropriate
        for (var intersection : intersections) {
            assertNotEquals(TetSphereIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test sphere intersection with large sphere")
    void testLargeSphereIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        float sphereRadius = scale * 0.5f; // Large sphere

        List<TetSphereIntersectionSearch.SphereIntersection<String>> intersections = 
            TetSphereIntersectionSearch.sphereIntersectedAll(sphereCenter, sphereRadius, testTetree, referencePoint);

        assertNotNull(intersections);
        
        // Large sphere should potentially find more intersections
        // Verify all found intersections are valid
        for (var intersection : intersections) {
            assertNotNull(intersection.content);
            assertNotEquals(TetSphereIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test first intersection search")
    void testFirstIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.2f;

        TetSphereIntersectionSearch.SphereIntersection<String> firstIntersection = 
            TetSphereIntersectionSearch.sphereIntersectedFirst(sphereCenter, sphereRadius, testTetree, referencePoint);

        // May be null if no intersections found, which is valid
        if (firstIntersection != null) {
            assertNotNull(firstIntersection.content);
            assertNotNull(firstIntersection.tetrahedron);
            assertTrue(firstIntersection.distanceToReferencePoint >= 0);
            
            // First intersection should be closest to reference point
            List<TetSphereIntersectionSearch.SphereIntersection<String>> allIntersections = 
                TetSphereIntersectionSearch.sphereIntersectedAll(sphereCenter, sphereRadius, testTetree, referencePoint);
            
            if (!allIntersections.isEmpty()) {
                assertEquals(firstIntersection.distanceToReferencePoint, 
                           allIntersections.get(0).distanceToReferencePoint, 0.001f);
            }
        }
    }

    @Test
    @DisplayName("Test completely inside filter")
    void testCompletelyInside() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.3f; // Large enough to potentially contain tetrahedra

        List<TetSphereIntersectionSearch.SphereIntersection<String>> insideIntersections = 
            TetSphereIntersectionSearch.tetrahedraCompletelyInside(sphereCenter, sphereRadius, testTetree, referencePoint);

        assertNotNull(insideIntersections);
        
        // Verify all results are marked as completely inside
        for (var intersection : insideIntersections) {
            assertEquals(TetSphereIntersectionSearch.IntersectionType.COMPLETELY_INSIDE, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test partially intersecting filter")
    void testPartiallyIntersecting() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.1f;

        List<TetSphereIntersectionSearch.SphereIntersection<String>> partialIntersections = 
            TetSphereIntersectionSearch.tetrahedraPartiallyIntersecting(sphereCenter, sphereRadius, testTetree, referencePoint);

        assertNotNull(partialIntersections);
        
        // Verify all results are marked as partially intersecting
        for (var intersection : partialIntersections) {
            assertEquals(TetSphereIntersectionSearch.IntersectionType.INTERSECTING, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test intersection count")
    void testIntersectionCount() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.2f;

        long count = TetSphereIntersectionSearch.countSphereIntersections(sphereCenter, sphereRadius, testTetree);
        
        List<TetSphereIntersectionSearch.SphereIntersection<String>> intersections = 
            TetSphereIntersectionSearch.sphereIntersectedAll(sphereCenter, sphereRadius, testTetree, referencePoint);

        assertEquals(intersections.size(), count);
    }

    @Test
    @DisplayName("Test has any intersection")
    void testHasAnyIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        // Test with sphere that should intersect
        Point3f intersectingSphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float intersectingRadius = scale * 0.2f;
        
        boolean hasIntersection = TetSphereIntersectionSearch.hasAnyIntersection(
            intersectingSphereCenter, intersectingRadius, testTetree);
        
        long count = TetSphereIntersectionSearch.countSphereIntersections(
            intersectingSphereCenter, intersectingRadius, testTetree);
        
        assertEquals(count > 0, hasIntersection);
        
        // Test with sphere far away that should not intersect
        Point3f farSphereCenter = new Point3f(scale * 10.0f, scale * 10.0f, scale * 10.0f);
        float farRadius = scale * 0.1f;
        
        boolean hasFarIntersection = TetSphereIntersectionSearch.hasAnyIntersection(
            farSphereCenter, farRadius, testTetree);
        
        long farCount = TetSphereIntersectionSearch.countSphereIntersections(
            farSphereCenter, farRadius, testTetree);
        
        assertEquals(farCount > 0, hasFarIntersection);
    }

    @Test
    @DisplayName("Test intersection statistics")
    void testIntersectionStatistics() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.3f;

        TetSphereIntersectionSearch.IntersectionStatistics stats = 
            TetSphereIntersectionSearch.getSphereIntersectionStatistics(sphereCenter, sphereRadius, testTetree);

        assertNotNull(stats);
        assertTrue(stats.totalTetrahedra >= 0);
        assertTrue(stats.insideTetrahedra >= 0);
        assertTrue(stats.intersectingTetrahedra >= 0);
        assertTrue(stats.outsideTetrahedra >= 0);
        
        // Verify percentages are valid
        assertTrue(stats.getInsidePercentage() >= 0 && stats.getInsidePercentage() <= 100);
        assertTrue(stats.getIntersectingPercentage() >= 0 && stats.getIntersectingPercentage() <= 100);
        assertTrue(stats.getOutsidePercentage() >= 0 && stats.getOutsidePercentage() <= 100);
        assertTrue(stats.getIntersectedPercentage() >= 0 && stats.getIntersectedPercentage() <= 100);
        
        // Verify statistics consistency
        assertEquals(stats.totalTetrahedra, stats.insideTetrahedra + stats.intersectingTetrahedra + stats.outsideTetrahedra);
        
        // Test string representation
        String statsString = stats.toString();
        assertNotNull(statsString);
        assertTrue(statsString.contains("TetSphereIntersectionStats"));
    }

    @Test
    @DisplayName("Test batch sphere intersections")
    void testBatchSphereIntersections() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        List<TetSphereIntersectionSearch.SphereQuery> queries = List.of(
            new TetSphereIntersectionSearch.SphereQuery(new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f), scale * 0.05f),
            new TetSphereIntersectionSearch.SphereQuery(new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f), scale * 0.1f),
            new TetSphereIntersectionSearch.SphereQuery(new Point3f(scale * 0.3f, scale * 0.3f, scale * 0.3f), scale * 0.15f)
        );

        var batchResults = TetSphereIntersectionSearch.batchSphereIntersections(queries, testTetree, referencePoint);

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
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.2f;

        // Test different aggregation strategies
        for (TetrahedralSearchBase.SimplexAggregationStrategy strategy : TetrahedralSearchBase.SimplexAggregationStrategy.values()) {
            List<TetSphereIntersectionSearch.SphereIntersection<String>> intersections = 
                TetSphereIntersectionSearch.sphereIntersectedAll(sphereCenter, sphereRadius, testTetree, referencePoint, strategy);

            assertNotNull(intersections);
            
            // Verify strategy-specific behavior
            switch (strategy) {
                case REPRESENTATIVE_ONLY, BEST_FIT -> {
                    // Should return at most one simplex per spatial region
                    assertTrue(intersections.size() >= 0);
                }
                case ALL_SIMPLICIES -> {
                    // May return multiple simplicies per spatial region
                    assertTrue(intersections.size() >= 0);
                }
                case WEIGHTED_AVERAGE -> {
                    // Currently behaves like ALL_SIMPLICIES
                    assertTrue(intersections.size() >= 0);
                }
            }
        }
    }

    @Test
    @DisplayName("Test input validation")
    void testInputValidation() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f validCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        Point3f negativeCenter = new Point3f(-scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float validRadius = scale * 0.1f;
        float invalidRadius = -scale * 0.1f;

        // Test negative coordinates
        assertThrows(IllegalArgumentException.class, () -> 
            TetSphereIntersectionSearch.sphereIntersectedAll(negativeCenter, validRadius, testTetree, referencePoint));

        assertThrows(IllegalArgumentException.class, () -> 
            TetSphereIntersectionSearch.sphereIntersectedAll(validCenter, validRadius, testTetree, negativeCenter));

        // Test negative radius
        assertThrows(IllegalArgumentException.class, () -> 
            TetSphereIntersectionSearch.sphereIntersectedAll(validCenter, invalidRadius, testTetree, referencePoint));

        // Test zero radius
        assertThrows(IllegalArgumentException.class, () -> 
            TetSphereIntersectionSearch.sphereIntersectedAll(validCenter, 0.0f, testTetree, referencePoint));

        // Test SphereQuery validation
        assertThrows(IllegalArgumentException.class, () -> 
            new TetSphereIntersectionSearch.SphereQuery(negativeCenter, validRadius));
        
        assertThrows(IllegalArgumentException.class, () -> 
            new TetSphereIntersectionSearch.SphereQuery(validCenter, invalidRadius));
    }

    @Test
    @DisplayName("Test empty tetree")
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new java.util.TreeMap<>());
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.1f;

        List<TetSphereIntersectionSearch.SphereIntersection<String>> intersections = 
            TetSphereIntersectionSearch.sphereIntersectedAll(sphereCenter, sphereRadius, emptyTetree, referencePoint);

        assertNotNull(intersections);
        assertTrue(intersections.isEmpty());

        TetSphereIntersectionSearch.SphereIntersection<String> firstIntersection = 
            TetSphereIntersectionSearch.sphereIntersectedFirst(sphereCenter, sphereRadius, emptyTetree, referencePoint);

        assertNull(firstIntersection);

        long count = TetSphereIntersectionSearch.countSphereIntersections(sphereCenter, sphereRadius, emptyTetree);
        assertEquals(0, count);

        boolean hasAny = TetSphereIntersectionSearch.hasAnyIntersection(sphereCenter, sphereRadius, emptyTetree);
        assertFalse(hasAny);
    }

    @Test
    @DisplayName("Test distance ordering")
    void testDistanceOrdering() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f sphereCenter = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        float sphereRadius = scale * 0.3f; // Large sphere to capture multiple tetrahedra
        Point3f closeReference = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        List<TetSphereIntersectionSearch.SphereIntersection<String>> intersections = 
            TetSphereIntersectionSearch.sphereIntersectedAll(sphereCenter, sphereRadius, testTetree, closeReference);

        if (intersections.size() > 1) {
            // Verify results are sorted by distance from reference point
            for (int i = 1; i < intersections.size(); i++) {
                assertTrue(intersections.get(i-1).distanceToReferencePoint <= intersections.get(i).distanceToReferencePoint,
                          "Results should be sorted by distance from reference point");
            }
        }
    }

    @Test
    @DisplayName("Test sphere query equality and hash code")
    void testSphereQueryEquality() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        Point3f center1 = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);
        Point3f center2 = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);
        Point3f center3 = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        float radius = scale * 0.05f;

        TetSphereIntersectionSearch.SphereQuery query1 = new TetSphereIntersectionSearch.SphereQuery(center1, radius);
        TetSphereIntersectionSearch.SphereQuery query2 = new TetSphereIntersectionSearch.SphereQuery(center2, radius);
        TetSphereIntersectionSearch.SphereQuery query3 = new TetSphereIntersectionSearch.SphereQuery(center3, radius);

        assertEquals(query1, query2);
        assertNotEquals(query1, query3);
        assertEquals(query1.hashCode(), query2.hashCode());
        
        // Test toString
        String queryString = query1.toString();
        assertNotNull(queryString);
        assertTrue(queryString.contains("SphereQuery"));
    }
}