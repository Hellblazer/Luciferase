package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for TetPlaneIntersectionSearch
 * Tests plane-tetrahedron intersection search functionality in tetrahedral space
 * 
 * @author hal.hildebrand
 */
public class TetPlaneIntersectionSearchTest {

    private Tetree<String> tetree;
    private static final float TOLERANCE = 1e-4f;
    private final byte testLevel = 15; // Higher resolution for testing
    
    // Test coordinates within S0 tetrahedron domain (positive, properly scaled)
    private static final float SCALE = Constants.MAX_EXTENT * 0.1f; // Use 10% of max extent as base scale
    private static final Point3f VALID_POINT_1 = new Point3f(SCALE * 0.1f, SCALE * 0.05f, SCALE * 0.02f);
    private static final Point3f VALID_POINT_2 = new Point3f(SCALE * 0.3f, SCALE * 0.15f, SCALE * 0.1f);
    private static final Point3f VALID_POINT_3 = new Point3f(SCALE * 0.5f, SCALE * 0.25f, SCALE * 0.2f);
    private static final Point3f REFERENCE_POINT = new Point3f(SCALE * 0.2f, SCALE * 0.1f, SCALE * 0.05f);

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new java.util.TreeMap<>());
        
        // Add test data to tetree within S0 tetrahedron domain
        tetree.insert(VALID_POINT_1, testLevel, "content1");
        tetree.insert(VALID_POINT_2, testLevel, "content2");
        tetree.insert(VALID_POINT_3, testLevel, "content3");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testPlaneIntersectedAllBasic() {
        // Create a plane that should intersect some tetrahedra
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(1.0f, 0.0f, 0.0f)
        );
        
        var intersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            plane, tetree, REFERENCE_POINT);
        
        assertNotNull(intersections);
        // Debug output to understand what's happening
        System.out.println("Found " + intersections.size() + " plane intersections");
        
        // Verify intersections are sorted by distance from reference point
        for (int i = 1; i < intersections.size(); i++) {
            assertTrue(intersections.get(i-1).distanceToReferencePoint <= 
                      intersections.get(i).distanceToReferencePoint,
                      "Intersections should be sorted by distance from reference point");
        }
        
        // Verify all results have valid properties
        for (var intersection : intersections) {
            assertNotNull(intersection.content);
            assertNotNull(intersection.tetrahedronCenter);
            assertTrue(intersection.index >= 0);
            assertTrue(intersection.distanceToReferencePoint >= 0);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testPlaneIntersectedFirst() {
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(0.0f, 1.0f, 0.0f)
        );
        
        var firstIntersection = TetPlaneIntersectionSearch.planeIntersectedFirst(
            plane, tetree, REFERENCE_POINT);
        
        if (firstIntersection != null) {
            assertNotNull(firstIntersection.content);
            assertNotNull(firstIntersection.tetrahedronCenter);
            assertTrue(firstIntersection.index >= 0);
            assertTrue(firstIntersection.distanceToReferencePoint >= 0);
            
            // Verify this is indeed the closest intersection
            var allIntersections = TetPlaneIntersectionSearch.planeIntersectedAll(
                plane, tetree, REFERENCE_POINT);
            if (!allIntersections.isEmpty()) {
                assertEquals(firstIntersection.index, allIntersections.get(0).index,
                           "First intersection should match closest from all intersections");
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testPlaneIntersectedAllByPlaneDistance() {
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(0.0f, 0.0f, 1.0f)
        );
        
        var intersections = TetPlaneIntersectionSearch.planeIntersectedAllByPlaneDistance(
            plane, tetree);
        
        assertNotNull(intersections);
        
        // Verify intersections are sorted by distance from plane
        for (int i = 1; i < intersections.size(); i++) {
            assertTrue(intersections.get(i-1).distanceToReferencePoint <= 
                      intersections.get(i).distanceToReferencePoint,
                      "Intersections should be sorted by distance from plane");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testTetrahedraOnPositiveSide() {
        // Create a plane that divides the space
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(1.0f, 0.0f, 0.0f)
        );
        
        var positiveSide = TetPlaneIntersectionSearch.tetrahedraOnPositiveSide(
            plane, tetree, REFERENCE_POINT);
        
        assertNotNull(positiveSide);
        
        // Verify all tetrahedra are indeed on positive side
        for (var result : positiveSide) {
            assertTrue(result.signedDistanceToPlane > 0,
                      "All results should be on positive side of plane");
        }
        
        // Verify sorting by distance from reference point
        for (int i = 1; i < positiveSide.size(); i++) {
            assertTrue(positiveSide.get(i-1).distanceToReferencePoint <= 
                      positiveSide.get(i).distanceToReferencePoint,
                      "Results should be sorted by distance from reference point");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testTetrahedraOnNegativeSide() {
        // Create a plane that divides the space
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(1.0f, 0.0f, 0.0f)
        );
        
        var negativeSide = TetPlaneIntersectionSearch.tetrahedraOnNegativeSide(
            plane, tetree, REFERENCE_POINT);
        
        assertNotNull(negativeSide);
        
        // Verify all tetrahedra are indeed on negative side
        for (var result : negativeSide) {
            assertTrue(result.signedDistanceToPlane < 0,
                      "All results should be on negative side of plane");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testCountPlaneIntersections() {
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(1.0f, 1.0f, 1.0f)
        );
        
        long count = TetPlaneIntersectionSearch.countPlaneIntersections(plane, tetree);
        
        assertTrue(count >= 0, "Count should be non-negative");
        
        // Verify count matches actual intersections
        var intersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            plane, tetree, REFERENCE_POINT);
        assertEquals(intersections.size(), count,
                    "Count should match number of actual intersections");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testHasAnyIntersection() {
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(0.0f, 1.0f, 1.0f)
        );
        
        boolean hasIntersection = TetPlaneIntersectionSearch.hasAnyIntersection(plane, tetree);
        
        // Verify consistency with actual intersections
        var intersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            plane, tetree, REFERENCE_POINT);
        assertEquals(!intersections.isEmpty(), hasIntersection,
                    "hasAnyIntersection should match whether intersections exist");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSimplexAggregationStrategies() {
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(1.0f, 0.0f, 0.0f)
        );
        
        // Test different aggregation strategies
        for (var strategy : TetrahedralSearchBase.SimplexAggregationStrategy.values()) {
            var intersections = TetPlaneIntersectionSearch.planeIntersectedAll(
                plane, tetree, REFERENCE_POINT, strategy);
            
            assertNotNull(intersections, "Should handle " + strategy + " aggregation");
            
            // Verify all results are valid
            for (var intersection : intersections) {
                assertNotNull(intersection.content);
                assertNotNull(intersection.tetrahedronCenter);
                assertTrue(intersection.index >= 0);
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testPlaneParallelToAxes() {
        // Test plane parallel to XY plane
        Plane3D xyPlane = Plane3D.parallelToXY(SCALE * 0.3f);
        var xyIntersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            xyPlane, tetree, REFERENCE_POINT);
        assertNotNull(xyIntersections);
        
        // Test plane parallel to XZ plane
        Plane3D xzPlane = Plane3D.parallelToXZ(SCALE * 0.3f);
        var xzIntersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            xzPlane, tetree, REFERENCE_POINT);
        assertNotNull(xzIntersections);
        
        // Test plane parallel to YZ plane
        Plane3D yzPlane = Plane3D.parallelToYZ(SCALE * 0.3f);
        var yzIntersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            yzPlane, tetree, REFERENCE_POINT);
        assertNotNull(yzIntersections);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testPlaneFromThreePoints() {
        // Create plane from three points within S0 domain
        Point3f p1 = new Point3f(SCALE * 0.1f, SCALE * 0.1f, SCALE * 0.1f);
        Point3f p2 = new Point3f(SCALE * 0.4f, SCALE * 0.1f, SCALE * 0.1f);
        Point3f p3 = new Point3f(SCALE * 0.2f, SCALE * 0.4f, SCALE * 0.1f);
        
        Plane3D plane = Plane3D.fromThreePoints(p1, p2, p3);
        
        var intersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            plane, tetree, REFERENCE_POINT);
        
        assertNotNull(intersections);
        
        // Verify the three points are indeed on the plane (within tolerance)
        assertTrue(plane.containsPoint(p1, TOLERANCE));
        assertTrue(plane.containsPoint(p2, TOLERANCE));
        assertTrue(plane.containsPoint(p3, TOLERANCE));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new java.util.TreeMap<>());
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(1.0f, 0.0f, 0.0f)
        );
        
        var intersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            plane, emptyTetree, REFERENCE_POINT);
        
        assertNotNull(intersections);
        assertTrue(intersections.isEmpty(), "Empty tetree should have no intersections");
        
        var firstIntersection = TetPlaneIntersectionSearch.planeIntersectedFirst(
            plane, emptyTetree, REFERENCE_POINT);
        assertNull(firstIntersection, "Empty tetree should have no first intersection");
        
        long count = TetPlaneIntersectionSearch.countPlaneIntersections(plane, emptyTetree);
        assertEquals(0, count, "Empty tetree should have zero intersections");
        
        boolean hasAny = TetPlaneIntersectionSearch.hasAnyIntersection(plane, emptyTetree);
        assertFalse(hasAny, "Empty tetree should have no intersections");
    }

    @Test
    void testInvalidCoordinates() {
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(1.0f, 0.0f, 0.0f)
        );
        
        // Test negative coordinates for reference point
        Point3f negativePoint = new Point3f(-100.0f, 100.0f, 100.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetPlaneIntersectionSearch.planeIntersectedAll(plane, tetree, negativePoint);
        }, "Should reject negative coordinates");
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetPlaneIntersectionSearch.tetrahedraOnPositiveSide(plane, tetree, negativePoint);
        }, "Should reject negative coordinates");
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetPlaneIntersectionSearch.tetrahedraOnNegativeSide(plane, tetree, negativePoint);
        }, "Should reject negative coordinates");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testLargeTetree() {
        // Create larger tetree for performance testing
        Tetree<String> largeTetree = new Tetree<>(new java.util.TreeMap<>());
        
        // Add many points within S0 domain
        for (int i = 0; i < 10; i++) {
            float x = SCALE * (0.1f + i * 0.05f);
            float y = SCALE * (0.1f + i * 0.03f);
            float z = SCALE * (0.1f + i * 0.02f);
            largeTetree.insert(new Point3f(x, y, z), testLevel, "content" + i);
        }
        
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(1.0f, 1.0f, 0.0f)
        );
        
        var intersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            plane, largeTetree, REFERENCE_POINT);
        
        assertNotNull(intersections);
        // Debug output
        System.out.println("Large tetree found " + intersections.size() + " plane intersections");
        
        // Verify sorting is maintained
        for (int i = 1; i < intersections.size(); i++) {
            assertTrue(intersections.get(i-1).distanceToReferencePoint <= 
                      intersections.get(i).distanceToReferencePoint,
                      "Large result set should maintain sorting");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConsistencyBetweenMethods() {
        Plane3D plane = Plane3D.fromPointAndNormal(
            REFERENCE_POINT, 
            new Vector3f(1.0f, 1.0f, 1.0f)
        );
        
        var allIntersections = TetPlaneIntersectionSearch.planeIntersectedAll(
            plane, tetree, REFERENCE_POINT);
        var firstIntersection = TetPlaneIntersectionSearch.planeIntersectedFirst(
            plane, tetree, REFERENCE_POINT);
        long count = TetPlaneIntersectionSearch.countPlaneIntersections(plane, tetree);
        boolean hasAny = TetPlaneIntersectionSearch.hasAnyIntersection(plane, tetree);
        
        // Verify consistency between methods
        assertEquals(allIntersections.size(), count,
                    "Count should match all intersections size");
        assertEquals(!allIntersections.isEmpty(), hasAny,
                    "hasAnyIntersection should match whether intersections exist");
        
        if (!allIntersections.isEmpty()) {
            assertNotNull(firstIntersection, "First intersection should exist if any intersections exist");
            assertEquals(allIntersections.get(0).index, firstIntersection.index,
                        "First intersection should match first from all intersections");
        } else {
            assertNull(firstIntersection, "First intersection should be null if no intersections exist");
        }
    }
}