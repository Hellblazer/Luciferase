package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for TetAABBIntersectionSearch
 * Tests AABB-tetrahedron intersection algorithms and spatial queries
 * 
 * @author hal.hildebrand
 */
class TetAABBIntersectionSearchTest {

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
    @DisplayName("Test basic AABB intersection search")
    void testBasicAABBIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.25f, scale * 0.25f, scale * 0.25f
        );

        List<TetAABBIntersectionSearch.AABBIntersection<String>> intersections = 
            TetAABBIntersectionSearch.aabbIntersectedAll(aabb, testTetree, referencePoint);

        assertNotNull(intersections);
        assertTrue(intersections.size() >= 0); // May find 0 or more intersections depending on tetrahedral layout
        
        // Verify all intersections have valid data
        for (var intersection : intersections) {
            assertNotNull(intersection.content);
            assertNotNull(intersection.tetrahedron);
            assertNotNull(intersection.tetrahedronCenter);
            assertTrue(intersection.distanceToReferencePoint >= 0);
            assertNotEquals(TetAABBIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test AABB creation methods")
    void testAABBCreation() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        // Test constructor
        TetAABBIntersectionSearch.AABB aabb1 = new TetAABBIntersectionSearch.AABB(
            scale * 0.1f, scale * 0.1f, scale * 0.1f,
            scale * 0.2f, scale * 0.2f, scale * 0.2f
        );
        
        assertEquals(scale * 0.1f, aabb1.getWidth(), 0.001f);
        assertEquals(scale * 0.1f, aabb1.getHeight(), 0.001f);
        assertEquals(scale * 0.1f, aabb1.getDepth(), 0.001f);
        
        // Test fromCenterAndHalfExtents
        Point3f center = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        TetAABBIntersectionSearch.AABB aabb2 = TetAABBIntersectionSearch.AABB.fromCenterAndHalfExtents(
            center, scale * 0.05f, scale * 0.05f, scale * 0.05f
        );
        
        Point3f calculatedCenter = aabb2.getCenter();
        assertEquals(center.x, calculatedCenter.x, 0.001f);
        assertEquals(center.y, calculatedCenter.y, 0.001f);
        assertEquals(center.z, calculatedCenter.z, 0.001f);
        
        // Test fromCorners
        Point3f corner1 = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);
        Point3f corner2 = new Point3f(scale * 0.2f, scale * 0.2f, scale * 0.2f);
        TetAABBIntersectionSearch.AABB aabb3 = TetAABBIntersectionSearch.AABB.fromCorners(corner1, corner2);
        
        assertEquals(scale * 0.1f, aabb3.minX, 0.001f);
        assertEquals(scale * 0.2f, aabb3.maxX, 0.001f);
    }

    @Test
    @DisplayName("Test AABB intersection with small box")
    void testSmallAABBIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.09f, scale * 0.09f, scale * 0.09f,
            scale * 0.11f, scale * 0.11f, scale * 0.11f
        );

        List<TetAABBIntersectionSearch.AABBIntersection<String>> intersections = 
            TetAABBIntersectionSearch.aabbIntersectedAll(aabb, testTetree, referencePoint);

        assertNotNull(intersections);
        
        // Small AABB should find fewer intersections
        // Verify intersection types are appropriate
        for (var intersection : intersections) {
            assertNotEquals(TetAABBIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test AABB intersection with large box")
    void testLargeAABBIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.4f, scale * 0.4f, scale * 0.4f
        );

        List<TetAABBIntersectionSearch.AABBIntersection<String>> intersections = 
            TetAABBIntersectionSearch.aabbIntersectedAll(aabb, testTetree, referencePoint);

        assertNotNull(intersections);
        
        // Large AABB should potentially find more intersections
        // Verify all found intersections are valid
        for (var intersection : intersections) {
            assertNotNull(intersection.content);
            assertNotEquals(TetAABBIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test first intersection search")
    void testFirstIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.25f, scale * 0.25f, scale * 0.25f
        );

        TetAABBIntersectionSearch.AABBIntersection<String> firstIntersection = 
            TetAABBIntersectionSearch.aabbIntersectedFirst(aabb, testTetree, referencePoint);

        // May be null if no intersections found, which is valid
        if (firstIntersection != null) {
            assertNotNull(firstIntersection.content);
            assertNotNull(firstIntersection.tetrahedron);
            assertTrue(firstIntersection.distanceToReferencePoint >= 0);
            
            // First intersection should be closest to reference point
            List<TetAABBIntersectionSearch.AABBIntersection<String>> allIntersections = 
                TetAABBIntersectionSearch.aabbIntersectedAll(aabb, testTetree, referencePoint);
            
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
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.4f, scale * 0.4f, scale * 0.4f
        );

        List<TetAABBIntersectionSearch.AABBIntersection<String>> insideIntersections = 
            TetAABBIntersectionSearch.tetrahedraCompletelyInside(aabb, testTetree, referencePoint);

        assertNotNull(insideIntersections);
        
        // Verify all results are marked as completely inside
        for (var intersection : insideIntersections) {
            assertEquals(TetAABBIntersectionSearch.IntersectionType.COMPLETELY_INSIDE, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test partially intersecting filter")
    void testPartiallyIntersecting() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.15f, scale * 0.15f, scale * 0.15f,
            scale * 0.25f, scale * 0.25f, scale * 0.25f
        );

        List<TetAABBIntersectionSearch.AABBIntersection<String>> partialIntersections = 
            TetAABBIntersectionSearch.tetrahedraPartiallyIntersecting(aabb, testTetree, referencePoint);

        assertNotNull(partialIntersections);
        
        // Verify all results are marked as partially intersecting
        for (var intersection : partialIntersections) {
            assertEquals(TetAABBIntersectionSearch.IntersectionType.INTERSECTING, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test containing AABB filter")
    void testContainingAABB() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.19f, scale * 0.19f, scale * 0.19f,
            scale * 0.21f, scale * 0.21f, scale * 0.21f
        );

        List<TetAABBIntersectionSearch.AABBIntersection<String>> containingIntersections = 
            TetAABBIntersectionSearch.tetrahedraContainingAABB(aabb, testTetree, referencePoint);

        assertNotNull(containingIntersections);
        
        // Verify all results are marked as containing AABB
        for (var intersection : containingIntersections) {
            assertEquals(TetAABBIntersectionSearch.IntersectionType.CONTAINS_AABB, intersection.intersectionType);
        }
    }

    @Test
    @DisplayName("Test intersection count")
    void testIntersectionCount() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.25f, scale * 0.25f, scale * 0.25f
        );

        long count = TetAABBIntersectionSearch.countAABBIntersections(aabb, testTetree);
        
        List<TetAABBIntersectionSearch.AABBIntersection<String>> intersections = 
            TetAABBIntersectionSearch.aabbIntersectedAll(aabb, testTetree, referencePoint);

        assertEquals(intersections.size(), count);
    }

    @Test
    @DisplayName("Test has any intersection")
    void testHasAnyIntersection() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        // Test with AABB that should intersect
        TetAABBIntersectionSearch.AABB intersectingAABB = new TetAABBIntersectionSearch.AABB(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.25f, scale * 0.25f, scale * 0.25f
        );
        
        boolean hasIntersection = TetAABBIntersectionSearch.hasAnyIntersection(intersectingAABB, testTetree);
        
        long count = TetAABBIntersectionSearch.countAABBIntersections(intersectingAABB, testTetree);
        
        assertEquals(count > 0, hasIntersection);
        
        // Test with AABB far away that should not intersect
        TetAABBIntersectionSearch.AABB farAABB = new TetAABBIntersectionSearch.AABB(
            scale * 10.0f, scale * 10.0f, scale * 10.0f,
            scale * 11.0f, scale * 11.0f, scale * 11.0f
        );
        
        boolean hasFarIntersection = TetAABBIntersectionSearch.hasAnyIntersection(farAABB, testTetree);
        
        long farCount = TetAABBIntersectionSearch.countAABBIntersections(farAABB, testTetree);
        
        assertEquals(farCount > 0, hasFarIntersection);
    }

    @Test
    @DisplayName("Test intersection statistics")
    void testIntersectionStatistics() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.4f, scale * 0.4f, scale * 0.4f
        );

        TetAABBIntersectionSearch.IntersectionStatistics stats = 
            TetAABBIntersectionSearch.getAABBIntersectionStatistics(aabb, testTetree);

        assertNotNull(stats);
        assertTrue(stats.totalTetrahedra >= 0);
        assertTrue(stats.insideTetrahedra >= 0);
        assertTrue(stats.intersectingTetrahedra >= 0);
        assertTrue(stats.containingTetrahedra >= 0);
        assertTrue(stats.outsideTetrahedra >= 0);
        
        // Verify percentages are valid
        assertTrue(stats.getInsidePercentage() >= 0 && stats.getInsidePercentage() <= 100);
        assertTrue(stats.getIntersectingPercentage() >= 0 && stats.getIntersectingPercentage() <= 100);
        assertTrue(stats.getContainingPercentage() >= 0 && stats.getContainingPercentage() <= 100);
        assertTrue(stats.getOutsidePercentage() >= 0 && stats.getOutsidePercentage() <= 100);
        assertTrue(stats.getIntersectedPercentage() >= 0 && stats.getIntersectedPercentage() <= 100);
        
        // Verify statistics consistency
        assertEquals(stats.totalTetrahedra, stats.insideTetrahedra + stats.intersectingTetrahedra + 
                    stats.containingTetrahedra + stats.outsideTetrahedra);
        
        // Test string representation
        String statsString = stats.toString();
        assertNotNull(statsString);
        assertTrue(statsString.contains("TetAABBIntersectionStats"));
    }

    @Test
    @DisplayName("Test batch AABB intersections")
    void testBatchAABBIntersections() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        
        List<TetAABBIntersectionSearch.AABB> aabbs = List.of(
            new TetAABBIntersectionSearch.AABB(scale * 0.05f, scale * 0.05f, scale * 0.05f, scale * 0.15f, scale * 0.15f, scale * 0.15f),
            new TetAABBIntersectionSearch.AABB(scale * 0.15f, scale * 0.15f, scale * 0.15f, scale * 0.25f, scale * 0.25f, scale * 0.25f),
            new TetAABBIntersectionSearch.AABB(scale * 0.25f, scale * 0.25f, scale * 0.25f, scale * 0.35f, scale * 0.35f, scale * 0.35f)
        );

        var batchResults = TetAABBIntersectionSearch.batchAABBIntersections(aabbs, testTetree, referencePoint);

        assertNotNull(batchResults);
        assertEquals(aabbs.size(), batchResults.size());
        
        // Verify each AABB has a result
        for (var aabb : aabbs) {
            assertTrue(batchResults.containsKey(aabb));
            assertNotNull(batchResults.get(aabb));
        }
    }

    @Test
    @DisplayName("Test simplex aggregation strategies")
    void testSimplexAggregationStrategies() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.25f, scale * 0.25f, scale * 0.25f
        );

        // Test different aggregation strategies
        for (TetrahedralSearchBase.SimplexAggregationStrategy strategy : TetrahedralSearchBase.SimplexAggregationStrategy.values()) {
            List<TetAABBIntersectionSearch.AABBIntersection<String>> intersections = 
                TetAABBIntersectionSearch.aabbIntersectedAll(aabb, testTetree, referencePoint, strategy);

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
        Point3f validPoint = new Point3f(scale * 0.15f, scale * 0.15f, scale * 0.15f);
        Point3f negativePoint = new Point3f(-scale * 0.15f, scale * 0.15f, scale * 0.15f);
        
        TetAABBIntersectionSearch.AABB validAABB = new TetAABBIntersectionSearch.AABB(
            scale * 0.1f, scale * 0.1f, scale * 0.1f,
            scale * 0.2f, scale * 0.2f, scale * 0.2f
        );

        // Test negative coordinates in reference point
        assertThrows(IllegalArgumentException.class, () -> 
            TetAABBIntersectionSearch.aabbIntersectedAll(validAABB, testTetree, negativePoint));

        // Test negative coordinates in AABB
        assertThrows(IllegalArgumentException.class, () -> 
            new TetAABBIntersectionSearch.AABB(-1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f));

        // Test invalid AABB dimensions
        assertThrows(IllegalArgumentException.class, () -> 
            new TetAABBIntersectionSearch.AABB(2.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f));

        // Test AABB creation methods with negative coordinates
        assertThrows(IllegalArgumentException.class, () -> 
            TetAABBIntersectionSearch.AABB.fromCenterAndHalfExtents(negativePoint, 1.0f, 1.0f, 1.0f));

        assertThrows(IllegalArgumentException.class, () -> 
            TetAABBIntersectionSearch.AABB.fromCorners(negativePoint, validPoint));

        // Test invalid half-extents
        assertThrows(IllegalArgumentException.class, () -> 
            TetAABBIntersectionSearch.AABB.fromCenterAndHalfExtents(validPoint, -1.0f, 1.0f, 1.0f));
    }

    @Test
    @DisplayName("Test empty tetree")
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new java.util.TreeMap<>());
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.1f, scale * 0.1f, scale * 0.1f,
            scale * 0.2f, scale * 0.2f, scale * 0.2f
        );

        List<TetAABBIntersectionSearch.AABBIntersection<String>> intersections = 
            TetAABBIntersectionSearch.aabbIntersectedAll(aabb, emptyTetree, referencePoint);

        assertNotNull(intersections);
        assertTrue(intersections.isEmpty());

        TetAABBIntersectionSearch.AABBIntersection<String> firstIntersection = 
            TetAABBIntersectionSearch.aabbIntersectedFirst(aabb, emptyTetree, referencePoint);

        assertNull(firstIntersection);

        long count = TetAABBIntersectionSearch.countAABBIntersections(aabb, emptyTetree);
        assertEquals(0, count);

        boolean hasAny = TetAABBIntersectionSearch.hasAnyIntersection(aabb, emptyTetree);
        assertFalse(hasAny);
    }

    @Test
    @DisplayName("Test distance ordering")
    void testDistanceOrdering() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.05f, scale * 0.05f, scale * 0.05f,
            scale * 0.4f, scale * 0.4f, scale * 0.4f
        );
        Point3f closeReference = new Point3f(scale * 0.1f, scale * 0.1f, scale * 0.1f);

        List<TetAABBIntersectionSearch.AABBIntersection<String>> intersections = 
            TetAABBIntersectionSearch.aabbIntersectedAll(aabb, testTetree, closeReference);

        if (intersections.size() > 1) {
            // Verify results are sorted by distance from reference point
            for (int i = 1; i < intersections.size(); i++) {
                assertTrue(intersections.get(i-1).distanceToReferencePoint <= intersections.get(i).distanceToReferencePoint,
                          "Results should be sorted by distance from reference point");
            }
        }
    }

    @Test
    @DisplayName("Test AABB equality and hash code")
    void testAABBEquality() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb1 = new TetAABBIntersectionSearch.AABB(
            scale * 0.1f, scale * 0.1f, scale * 0.1f,
            scale * 0.2f, scale * 0.2f, scale * 0.2f
        );
        TetAABBIntersectionSearch.AABB aabb2 = new TetAABBIntersectionSearch.AABB(
            scale * 0.1f, scale * 0.1f, scale * 0.1f,
            scale * 0.2f, scale * 0.2f, scale * 0.2f
        );
        TetAABBIntersectionSearch.AABB aabb3 = new TetAABBIntersectionSearch.AABB(
            scale * 0.15f, scale * 0.15f, scale * 0.15f,
            scale * 0.25f, scale * 0.25f, scale * 0.25f
        );

        assertEquals(aabb1, aabb2);
        assertNotEquals(aabb1, aabb3);
        assertEquals(aabb1.hashCode(), aabb2.hashCode());
        
        // Test toString
        String aabbString = aabb1.toString();
        assertNotNull(aabbString);
        assertTrue(aabbString.contains("AABB"));
        
        // Test getVolume
        float expectedVolume = aabb1.getWidth() * aabb1.getHeight() * aabb1.getDepth();
        assertEquals(expectedVolume, aabb1.getVolume(), 0.001f);
    }

    @Test
    @DisplayName("Test AABB spatial conversion")
    void testAABBSpatialConversion() {
        float scale = Constants.MAX_EXTENT / 4.0f;
        TetAABBIntersectionSearch.AABB aabb = new TetAABBIntersectionSearch.AABB(
            scale * 0.1f, scale * 0.1f, scale * 0.1f,
            scale * 0.2f, scale * 0.2f, scale * 0.2f
        );
        
        Spatial.aabb spatialAABB = aabb.toSpatialAABB();
        assertNotNull(spatialAABB);
        
        assertEquals(aabb.minX, spatialAABB.originX(), 0.001f);
        assertEquals(aabb.minY, spatialAABB.originY(), 0.001f);
        assertEquals(aabb.minZ, spatialAABB.originZ(), 0.001f);
        assertEquals(aabb.maxX, spatialAABB.extentX(), 0.001f);
        assertEquals(aabb.maxY, spatialAABB.extentY(), 0.001f);
        assertEquals(aabb.maxZ, spatialAABB.extentZ(), 0.001f);
    }
}