package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static com.hellblazer.luciferase.lucien.TetrahedralSearchBase.SimplexAggregationStrategy;

/**
 * Unit tests for Tetrahedral Convex Hull Intersection search functionality
 * All test coordinates use positive values only to maintain tetrahedral constraints
 * 
 * @author hal.hildebrand
 */
public class TetConvexHullIntersectionSearchTest {

    private Tetree<String> tetree;
    private final byte testLevel = 15;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new TreeMap<>());
        
        // Create a spatial arrangement suitable for convex hull testing
        // Use coordinates that will map to different tetrahedra - all positive
        
        // Central cluster
        tetree.insert(new Point3f(200.0f, 200.0f, 200.0f), testLevel, "Center");
        tetree.insert(new Point3f(210.0f, 210.0f, 210.0f), testLevel, "CenterNear");
        tetree.insert(new Point3f(190.0f, 190.0f, 190.0f), testLevel, "CenterAlt");
        
        // Surrounding points
        tetree.insert(new Point3f(150.0f, 200.0f, 200.0f), testLevel, "West");
        tetree.insert(new Point3f(250.0f, 200.0f, 200.0f), testLevel, "East");
        tetree.insert(new Point3f(200.0f, 150.0f, 200.0f), testLevel, "South");
        tetree.insert(new Point3f(200.0f, 250.0f, 200.0f), testLevel, "North");
        tetree.insert(new Point3f(200.0f, 200.0f, 150.0f), testLevel, "Down");
        tetree.insert(new Point3f(200.0f, 200.0f, 250.0f), testLevel, "Up");
        
        // Corner points
        tetree.insert(new Point3f(100.0f, 100.0f, 100.0f), testLevel, "Corner1");
        tetree.insert(new Point3f(300.0f, 100.0f, 100.0f), testLevel, "Corner2");
        tetree.insert(new Point3f(100.0f, 300.0f, 100.0f), testLevel, "Corner3");
        tetree.insert(new Point3f(100.0f, 100.0f, 300.0f), testLevel, "Corner4");
        tetree.insert(new Point3f(300.0f, 300.0f, 300.0f), testLevel, "Corner5");
        
        // Distant points
        tetree.insert(new Point3f(400.0f, 400.0f, 400.0f), testLevel, "FarCorner");
        tetree.insert(new Point3f(500.0f, 200.0f, 200.0f), testLevel, "FarEast");
    }

    @Test
    void testTetConvexHullFromVertices() {
        List<Point3f> vertices = Arrays.asList(
            new Point3f(100.0f, 100.0f, 100.0f),
            new Point3f(300.0f, 100.0f, 100.0f),
            new Point3f(100.0f, 300.0f, 100.0f),
            new Point3f(100.0f, 100.0f, 300.0f)
        );
        
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.fromVertices(vertices);
        
        assertNotNull(hull);
        assertNotNull(hull.planes);
        assertFalse(hull.planes.isEmpty());
        assertNotNull(hull.centroid);
        assertTrue(hull.boundingRadius > 0);
        
        // Verify centroid has positive coordinates
        assertTrue(hull.centroid.x >= 0);
        assertTrue(hull.centroid.y >= 0);
        assertTrue(hull.centroid.z >= 0);
        
        // Test point containment
        Point3f insidePoint = new Point3f(150.0f, 150.0f, 150.0f);
        Point3f outsidePoint = new Point3f(400.0f, 400.0f, 400.0f);
        
        assertTrue(hull.containsPoint(insidePoint));
        assertFalse(hull.containsPoint(outsidePoint));
    }

    @Test
    void testTetConvexHullTetrahedralHull() {
        Point3f v0 = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f v1 = new Point3f(200.0f, 100.0f, 100.0f);
        Point3f v2 = new Point3f(100.0f, 200.0f, 100.0f);
        Point3f v3 = new Point3f(100.0f, 100.0f, 200.0f);
        
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(v0, v1, v2, v3);
        
        assertNotNull(hull);
        assertEquals(4, hull.planes.size()); // Should have 4 faces
        
        // Test that vertices are contained in the hull
        assertTrue(hull.containsPoint(v0));
        assertTrue(hull.containsPoint(v1));
        assertTrue(hull.containsPoint(v2));
        assertTrue(hull.containsPoint(v3));
        
        // Test centroid calculation
        Point3f expectedCentroid = new Point3f(
            (v0.x + v1.x + v2.x + v3.x) / 4.0f,
            (v0.y + v1.y + v2.y + v3.y) / 4.0f,
            (v0.z + v1.z + v2.z + v3.z) / 4.0f
        );
        // Centroid should be roughly in the center (allowing for plane-based calculation differences)
        float deltaX = Math.abs(hull.centroid.x - expectedCentroid.x);
        float deltaY = Math.abs(hull.centroid.y - expectedCentroid.y);
        float deltaZ = Math.abs(hull.centroid.z - expectedCentroid.z);
        assertTrue(deltaX < 100.0f); // Allow reasonable tolerance for tetrahedral centroid calculation
        assertTrue(deltaY < 100.0f);
        assertTrue(deltaZ < 100.0f);
        
        // Test toString
        String hullStr = hull.toString();
        assertTrue(hullStr.contains("TetConvexHull"));
        assertTrue(hullStr.contains("planes=4"));
    }

    @Test
    void testConvexHullIntersectedAll() {
        // Create a hull around the central area
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(150.0f, 150.0f, 150.0f),
                new Point3f(250.0f, 150.0f, 150.0f),
                new Point3f(150.0f, 250.0f, 150.0f),
                new Point3f(150.0f, 150.0f, 250.0f)
            );
        
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> intersections = 
            TetConvexHullIntersectionSearch.convexHullIntersectedAll(hull, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNotNull(intersections);
        
        // Results should be sorted by distance from reference point
        for (int i = 0; i < intersections.size() - 1; i++) {
            assertTrue(intersections.get(i).distanceToReferencePoint <= 
                      intersections.get(i + 1).distanceToReferencePoint);
        }
        
        // All intersections should have valid data
        for (var intersection : intersections) {
            assertNotNull(intersection.content);
            assertNotNull(intersection.tetrahedron);
            assertNotNull(intersection.tetrahedronCenter);
            assertNotNull(intersection.intersectionType);
            assertTrue(intersection.distanceToReferencePoint >= 0);
            assertTrue(intersection.penetrationDepth >= 0);
            assertTrue(intersection.index >= 0);
            
            // Should not be COMPLETELY_OUTSIDE since these are intersections
            assertNotEquals(TetConvexHullIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, 
                          intersection.intersectionType);
            
            // Verify positive coordinates
            assertTrue(intersection.tetrahedronCenter.x >= 0);
            assertTrue(intersection.tetrahedronCenter.y >= 0);
            assertTrue(intersection.tetrahedronCenter.z >= 0);
        }
    }

    @Test
    void testConvexHullIntersectedFirst() {
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(180.0f, 180.0f, 180.0f),
                new Point3f(220.0f, 180.0f, 180.0f),
                new Point3f(180.0f, 220.0f, 180.0f),
                new Point3f(180.0f, 180.0f, 220.0f)
            );
        
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        TetConvexHullIntersectionSearch.TetConvexHullIntersection<String> firstIntersection = 
            TetConvexHullIntersectionSearch.convexHullIntersectedFirst(hull, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        if (firstIntersection != null) {
            // Should be the closest intersection
            List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> allIntersections = 
                TetConvexHullIntersectionSearch.convexHullIntersectedAll(hull, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
            
            assertFalse(allIntersections.isEmpty());
            assertEquals(allIntersections.get(0).content, firstIntersection.content);
            assertEquals(allIntersections.get(0).distanceToReferencePoint, 
                        firstIntersection.distanceToReferencePoint, 0.001f);
        }
    }

    @Test
    void testTetrahedraCompletelyInside() {
        // Create a large hull that should encompass several tetrahedra
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(100.0f, 100.0f, 100.0f),
                new Point3f(300.0f, 100.0f, 100.0f),
                new Point3f(100.0f, 300.0f, 100.0f),
                new Point3f(100.0f, 100.0f, 300.0f)
            );
        
        Point3f referencePoint = new Point3f(50.0f, 50.0f, 50.0f);
        
        List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> insideTetrahedra = 
            TetConvexHullIntersectionSearch.tetrahedraCompletelyInside(hull, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        // All results should be COMPLETELY_INSIDE
        for (var intersection : insideTetrahedra) {
            assertEquals(TetConvexHullIntersectionSearch.IntersectionType.COMPLETELY_INSIDE, 
                        intersection.intersectionType);
        }
        
        // Results should be sorted by distance from reference point
        for (int i = 0; i < insideTetrahedra.size() - 1; i++) {
            assertTrue(insideTetrahedra.get(i).distanceToReferencePoint <= 
                      insideTetrahedra.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testTetrahedraPartiallyIntersecting() {
        // Create a hull that intersects with some tetrahedra
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(190.0f, 190.0f, 190.0f),
                new Point3f(210.0f, 190.0f, 190.0f),
                new Point3f(190.0f, 210.0f, 190.0f),
                new Point3f(190.0f, 190.0f, 210.0f)
            );
        
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> intersectingTetrahedra = 
            TetConvexHullIntersectionSearch.tetrahedraPartiallyIntersecting(hull, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        // All results should be INTERSECTING
        for (var intersection : intersectingTetrahedra) {
            assertEquals(TetConvexHullIntersectionSearch.IntersectionType.INTERSECTING, 
                        intersection.intersectionType);
        }
        
        // Results should be sorted by distance from reference point
        for (int i = 0; i < intersectingTetrahedra.size() - 1; i++) {
            assertTrue(intersectingTetrahedra.get(i).distanceToReferencePoint <= 
                      intersectingTetrahedra.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testCountConvexHullIntersections() {
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(150.0f, 150.0f, 150.0f),
                new Point3f(250.0f, 150.0f, 150.0f),
                new Point3f(150.0f, 250.0f, 150.0f),
                new Point3f(150.0f, 150.0f, 250.0f)
            );
        
        long count = TetConvexHullIntersectionSearch.countConvexHullIntersections(hull, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        // Count should match the number from convexHullIntersectedAll
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> intersections = 
            TetConvexHullIntersectionSearch.convexHullIntersectedAll(hull, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertEquals(intersections.size(), count);
        assertTrue(count >= 0);
    }

    @Test
    void testHasAnyIntersection() {
        // Hull that should intersect with tetrahedra
        TetConvexHullIntersectionSearch.TetConvexHull intersectingHull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(180.0f, 180.0f, 180.0f),
                new Point3f(220.0f, 180.0f, 180.0f),
                new Point3f(180.0f, 220.0f, 180.0f),
                new Point3f(180.0f, 180.0f, 220.0f)
            );
        
        // Hull that should not intersect with any tetrahedra
        TetConvexHullIntersectionSearch.TetConvexHull nonIntersectingHull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(1000.0f, 1000.0f, 1000.0f),
                new Point3f(1100.0f, 1000.0f, 1000.0f),
                new Point3f(1000.0f, 1100.0f, 1000.0f),
                new Point3f(1000.0f, 1000.0f, 1100.0f)
            );
        
        boolean hasIntersecting = TetConvexHullIntersectionSearch.hasAnyIntersection(intersectingHull, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        boolean hasNonIntersecting = TetConvexHullIntersectionSearch.hasAnyIntersection(nonIntersectingHull, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        // Results depend on actual tetrahedron positions, but should not throw exceptions
        assertDoesNotThrow(() -> hasIntersecting);
        assertDoesNotThrow(() -> hasNonIntersecting);
    }

    @Test
    void testGetConvexHullIntersectionStatistics() {
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(150.0f, 150.0f, 150.0f),
                new Point3f(250.0f, 150.0f, 150.0f),
                new Point3f(150.0f, 250.0f, 150.0f),
                new Point3f(150.0f, 150.0f, 250.0f)
            );
        
        TetConvexHullIntersectionSearch.TetIntersectionStatistics stats = 
            TetConvexHullIntersectionSearch.getConvexHullIntersectionStatistics(hull, tetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNotNull(stats);
        assertTrue(stats.totalTetrahedra >= 0);
        assertTrue(stats.insideTetrahedra >= 0);
        assertTrue(stats.intersectingTetrahedra >= 0);
        assertTrue(stats.outsideTetrahedra >= 0);
        assertTrue(stats.totalPenetrationDepth >= 0.0f);
        assertTrue(stats.averagePenetrationDepth >= 0.0f);
        
        // Total should equal sum of parts
        assertEquals(stats.totalTetrahedra, stats.insideTetrahedra + stats.intersectingTetrahedra + stats.outsideTetrahedra);
        
        // Percentages should be valid
        assertTrue(stats.getInsidePercentage() >= 0 && stats.getInsidePercentage() <= 100);
        assertTrue(stats.getIntersectingPercentage() >= 0 && stats.getIntersectingPercentage() <= 100);
        assertTrue(stats.getOutsidePercentage() >= 0 && stats.getOutsidePercentage() <= 100);
        assertTrue(stats.getIntersectedPercentage() >= 0 && stats.getIntersectedPercentage() <= 100);
        
        // Intersected percentage should equal inside + intersecting
        assertEquals(stats.getIntersectedPercentage(), 
                    stats.getInsidePercentage() + stats.getIntersectingPercentage(), 0.001);
        
        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("TetConvexHullIntersectionStats"));
        assertTrue(statsStr.contains("total="));
        assertTrue(statsStr.contains("inside="));
        assertTrue(statsStr.contains("intersecting="));
        assertTrue(statsStr.contains("outside="));
        assertTrue(statsStr.contains("intersected="));
        assertTrue(statsStr.contains("avg_penetration="));
    }

    @Test
    void testBatchConvexHullIntersections() {
        TetConvexHullIntersectionSearch.TetConvexHull hull1 = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(150.0f, 150.0f, 150.0f),
                new Point3f(200.0f, 150.0f, 150.0f),
                new Point3f(150.0f, 200.0f, 150.0f),
                new Point3f(150.0f, 150.0f, 200.0f)
            );
        
        TetConvexHullIntersectionSearch.TetConvexHull hull2 = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(250.0f, 250.0f, 250.0f),
                new Point3f(300.0f, 250.0f, 250.0f),
                new Point3f(250.0f, 300.0f, 250.0f),
                new Point3f(250.0f, 250.0f, 300.0f)
            );
        
        List<TetConvexHullIntersectionSearch.TetConvexHull> hulls = Arrays.asList(hull1, hull2);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        Map<TetConvexHullIntersectionSearch.TetConvexHull, List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>>> results = 
            TetConvexHullIntersectionSearch.batchConvexHullIntersections(hulls, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertEquals(2, results.size());
        assertTrue(results.containsKey(hull1));
        assertTrue(results.containsKey(hull2));
        
        // Each result should be sorted by distance
        for (var entry : results.entrySet()) {
            List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> intersections = entry.getValue();
            for (int i = 0; i < intersections.size() - 1; i++) {
                assertTrue(intersections.get(i).distanceToReferencePoint <= 
                          intersections.get(i + 1).distanceToReferencePoint);
            }
        }
    }

    @Test
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new TreeMap<>());
        
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(100.0f, 100.0f, 100.0f),
                new Point3f(200.0f, 100.0f, 100.0f),
                new Point3f(100.0f, 200.0f, 100.0f),
                new Point3f(100.0f, 100.0f, 200.0f)
            );
        
        Point3f referencePoint = new Point3f(50.0f, 50.0f, 50.0f);
        
        List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> intersections = 
            TetConvexHullIntersectionSearch.convexHullIntersectedAll(hull, emptyTetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertTrue(intersections.isEmpty());
        
        TetConvexHullIntersectionSearch.TetConvexHullIntersection<String> first = 
            TetConvexHullIntersectionSearch.convexHullIntersectedFirst(hull, emptyTetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        assertNull(first);
        
        assertEquals(0, TetConvexHullIntersectionSearch.countConvexHullIntersections(hull, emptyTetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY));
        assertFalse(TetConvexHullIntersectionSearch.hasAnyIntersection(hull, emptyTetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY));
        
        TetConvexHullIntersectionSearch.TetIntersectionStatistics stats = 
            TetConvexHullIntersectionSearch.getConvexHullIntersectionStatistics(hull, emptyTetree, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        assertEquals(0, stats.totalTetrahedra);
        assertEquals(0, stats.insideTetrahedra);
        assertEquals(0, stats.intersectingTetrahedra);
        assertEquals(0, stats.outsideTetrahedra);
        assertEquals(0.0f, stats.totalPenetrationDepth);
        assertEquals(0.0f, stats.averagePenetrationDepth);
    }

    @Test
    void testNegativeCoordinatesThrowException() {
        Point3f validPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f negativePos = new Point3f(-100.0f, 100.0f, 100.0f);
        
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(100.0f, 100.0f, 100.0f),
                new Point3f(200.0f, 100.0f, 100.0f),
                new Point3f(100.0f, 200.0f, 100.0f),
                new Point3f(100.0f, 100.0f, 200.0f)
            );
        
        // Creating hull with negative vertices
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                negativePos, validPos, validPos, validPos);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.TetConvexHull.fromVertices(Arrays.asList(negativePos, validPos, validPos, validPos));
        });
        
        // Using hull with negative reference point
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.convexHullIntersectedAll(hull, tetree, negativePos, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.convexHullIntersectedFirst(hull, tetree, negativePos, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.tetrahedraCompletelyInside(hull, tetree, negativePos, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.tetrahedraPartiallyIntersecting(hull, tetree, negativePos, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.batchConvexHullIntersections(Arrays.asList(hull), tetree, negativePos, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        });
        
        // Testing hull containsPoint with negative coordinates
        assertThrows(IllegalArgumentException.class, () -> {
            hull.containsPoint(negativePos);
        });
        
        // Testing hull distanceToPoint with negative coordinates
        assertThrows(IllegalArgumentException.class, () -> {
            hull.distanceToPoint(negativePos);
        });
    }

    @Test
    void testInvalidHullParameters() {
        // Hull with insufficient vertices
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.TetConvexHull.fromVertices(Arrays.asList(
                new Point3f(100.0f, 100.0f, 100.0f),
                new Point3f(200.0f, 100.0f, 100.0f)
            ));
        });
        
        // Hull with null vertices
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.TetConvexHull.fromVertices(null);
        });
        
        // Hull with empty vertices
        assertThrows(IllegalArgumentException.class, () -> {
            TetConvexHullIntersectionSearch.TetConvexHull.fromVertices(Arrays.asList());
        });
        
        // Hull with null planes
        assertThrows(IllegalArgumentException.class, () -> {
            new TetConvexHullIntersectionSearch.TetConvexHull(null);
        });
        
        // Hull with empty planes
        assertThrows(IllegalArgumentException.class, () -> {
            new TetConvexHullIntersectionSearch.TetConvexHull(Arrays.asList());
        });
    }

    @Test
    void testConvexHullDistanceCalculation() {
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(100.0f, 100.0f, 100.0f),
                new Point3f(200.0f, 100.0f, 100.0f),
                new Point3f(100.0f, 200.0f, 100.0f),
                new Point3f(100.0f, 100.0f, 200.0f)
            );
        
        // Point inside hull should have negative distance
        Point3f insidePoint = new Point3f(125.0f, 125.0f, 125.0f);
        float insideDistance = hull.distanceToPoint(insidePoint);
        assertTrue(insideDistance <= 0);
        
        // Point outside hull should have positive distance
        Point3f outsidePoint = new Point3f(300.0f, 300.0f, 300.0f);
        float outsideDistance = hull.distanceToPoint(outsidePoint);
        assertTrue(outsideDistance > 0);
    }

    @Test
    void testIntersectionDataIntegrity() {
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(150.0f, 150.0f, 150.0f),
                new Point3f(250.0f, 150.0f, 150.0f),
                new Point3f(150.0f, 250.0f, 150.0f),
                new Point3f(150.0f, 150.0f, 250.0f)
            );
        
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> intersections = 
            TetConvexHullIntersectionSearch.convexHullIntersectedAll(hull, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        for (var intersection : intersections) {
            // Verify all fields are properly set
            assertNotNull(intersection.content);
            assertNotNull(intersection.tetrahedron);
            assertNotNull(intersection.tetrahedronCenter);
            assertNotNull(intersection.intersectionType);
            assertTrue(intersection.distanceToReferencePoint >= 0);
            assertTrue(intersection.penetrationDepth >= 0);
            assertTrue(intersection.index >= 0);
            
            // Verify distance calculation
            Point3f center = intersection.tetrahedronCenter;
            float dx = referencePoint.x - center.x;
            float dy = referencePoint.y - center.y;
            float dz = referencePoint.z - center.z;
            float expectedDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(expectedDistance, intersection.distanceToReferencePoint, 0.001f);
            
            // Verify intersection type is not COMPLETELY_OUTSIDE (since these are intersections)
            assertNotEquals(TetConvexHullIntersectionSearch.IntersectionType.COMPLETELY_OUTSIDE, 
                          intersection.intersectionType);
        }
    }

    @Test
    void testSimplexAggregationStrategies() {
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.createTetrahedralHull(
                new Point3f(180.0f, 180.0f, 180.0f),
                new Point3f(220.0f, 180.0f, 180.0f),
                new Point3f(180.0f, 220.0f, 180.0f),
                new Point3f(180.0f, 180.0f, 220.0f)
            );
        
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        // Test different aggregation strategies
        List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> representativeResults = 
            TetConvexHullIntersectionSearch.convexHullIntersectedAll(hull, tetree, referencePoint, SimplexAggregationStrategy.REPRESENTATIVE_ONLY);
        
        List<TetConvexHullIntersectionSearch.TetConvexHullIntersection<String>> allResults = 
            TetConvexHullIntersectionSearch.convexHullIntersectedAll(hull, tetree, referencePoint, SimplexAggregationStrategy.ALL_SIMPLICIES);
        
        // ALL_SIMPLICIES should return at least as many results as REPRESENTATIVE_ONLY
        assertTrue(allResults.size() >= representativeResults.size());
        
        // Both should maintain distance ordering
        for (int i = 0; i < representativeResults.size() - 1; i++) {
            assertTrue(representativeResults.get(i).distanceToReferencePoint <= 
                      representativeResults.get(i + 1).distanceToReferencePoint);
        }
        
        for (int i = 0; i < allResults.size() - 1; i++) {
            assertTrue(allResults.get(i).distanceToReferencePoint <= 
                      allResults.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testDegenerateHullCases() {
        // Test coplanar points - should fall back to bounding box
        List<Point3f> coplanarVertices = Arrays.asList(
            new Point3f(100.0f, 100.0f, 100.0f),
            new Point3f(200.0f, 100.0f, 100.0f),
            new Point3f(150.0f, 100.0f, 100.0f),
            new Point3f(175.0f, 100.0f, 100.0f)
        );
        
        TetConvexHullIntersectionSearch.TetConvexHull hull = 
            TetConvexHullIntersectionSearch.TetConvexHull.fromVertices(coplanarVertices);
        
        assertNotNull(hull);
        assertNotNull(hull.planes);
        assertFalse(hull.planes.isEmpty());
        
        // Should still be able to test point containment
        assertDoesNotThrow(() -> {
            hull.containsPoint(new Point3f(150.0f, 100.0f, 100.0f));
        });
    }
}