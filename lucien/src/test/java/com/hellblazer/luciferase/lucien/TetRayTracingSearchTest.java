package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for TetRayTracingSearch Tests ray-tetrahedron intersection search functionality using
 * Möller-Trumbore algorithm
 *
 * @author hal.hildebrand
 */
public class TetRayTracingSearchTest {

    // Test coordinates within S0 tetrahedron domain (positive, properly scaled)
    private static final float          SCALE         = Constants.MAX_EXTENT
    * 0.1f; // Use 10% of max extent as base scale
    private static final Point3f        VALID_POINT_1 = new Point3f(SCALE * 0.1f, SCALE * 0.05f, SCALE * 0.02f);
    private static final Point3f        VALID_POINT_2 = new Point3f(SCALE * 0.3f, SCALE * 0.15f, SCALE * 0.1f);
    private static final Point3f        VALID_POINT_3 = new Point3f(SCALE * 0.5f, SCALE * 0.25f, SCALE * 0.2f);
    private static final Point3f        RAY_ORIGIN    = new Point3f(SCALE * 0.01f, SCALE * 0.01f, SCALE * 0.01f);
    private final        byte           testLevel     = 15; // Higher resolution for testing
    private              Tetree<String> tetree;

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
    void testConsistencyBetweenMethods() {
        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 0.7f, 0.9f));

        var allIntersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);
        var firstIntersection = TetRayTracingSearch.rayIntersectedFirst(ray, tetree);
        long count = TetRayTracingSearch.countRayIntersections(ray, tetree);
        boolean hasAny = TetRayTracingSearch.hasAnyIntersection(ray, tetree);

        // Verify consistency between methods
        assertEquals(allIntersections.size(), count, "Count should match all intersections size");
        assertEquals(!allIntersections.isEmpty(), hasAny,
                     "hasAnyIntersection should match whether intersections exist");

        if (!allIntersections.isEmpty()) {
            assertNotNull(firstIntersection, "First intersection should exist if any intersections exist");
            assertEquals(allIntersections.get(0).index, firstIntersection.index,
                         "First intersection should match first from all intersections");
            assertEquals(allIntersections.get(0).distance, firstIntersection.distance, 1e-6f, "Distance should match");
        } else {
            assertNull(firstIntersection, "First intersection should be null if no intersections exist");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testCountRayIntersections() {
        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 1.0f, 0.5f));

        long count = TetRayTracingSearch.countRayIntersections(ray, tetree);

        assertTrue(count >= 0, "Count should be non-negative");

        // Verify count matches actual intersections
        var intersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);
        assertEquals(intersections.size(), count, "Count should match number of actual intersections");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testEmptyTetree() {
        Tetree<String> emptyTetree = new Tetree<>(new java.util.TreeMap<>());
        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 1.0f, 1.0f));

        var intersections = TetRayTracingSearch.rayIntersectedAll(ray, emptyTetree);

        assertNotNull(intersections);
        assertTrue(intersections.isEmpty(), "Empty tetree should have no intersections");

        var firstIntersection = TetRayTracingSearch.rayIntersectedFirst(ray, emptyTetree);
        assertNull(firstIntersection, "Empty tetree should have no first intersection");

        long count = TetRayTracingSearch.countRayIntersections(ray, emptyTetree);
        assertEquals(0, count, "Empty tetree should have zero intersections");

        boolean hasAny = TetRayTracingSearch.hasAnyIntersection(ray, emptyTetree);
        assertFalse(hasAny, "Empty tetree should have no intersections");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testFaceIndexValidity() {
        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 1.0f, 1.0f));

        var intersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);

        for (var intersection : intersections) {
            assertTrue(intersection.intersectedFace >= 0 && intersection.intersectedFace <= 3,
                       "Intersected face index should be between 0 and 3, got: " + intersection.intersectedFace);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testHasAnyIntersection() {
        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(0.5f, 1.0f, 1.0f));

        boolean hasIntersection = TetRayTracingSearch.hasAnyIntersection(ray, tetree);

        // Verify consistency with actual intersections
        var intersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);
        assertEquals(!intersections.isEmpty(), hasIntersection,
                     "hasAnyIntersection should match whether intersections exist");
    }

    @Test
    void testInvalidRayOrigin() {
        // Test negative coordinates for ray origin
        Point3f negativeOrigin = new Point3f(-100.0f, 100.0f, 100.0f);
        Vector3f direction = new Vector3f(1.0f, 0.0f, 0.0f);

        assertThrows(IllegalArgumentException.class, () -> {
            new Ray3D(negativeOrigin, direction);
        }, "Should reject negative ray origin coordinates");
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

        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 0.8f, 0.6f));

        var intersections = TetRayTracingSearch.rayIntersectedAll(ray, largeTetree);

        assertNotNull(intersections);
        // Debug output
        System.out.println("Large tetree found " + intersections.size() + " ray intersections");

        // Verify sorting is maintained
        for (int i = 1; i < intersections.size(); i++) {
            assertTrue(intersections.get(i - 1).distance <= intersections.get(i).distance,
                       "Large result set should maintain sorting");
        }
    }

    @Test
    @Disabled("Performance optimization needed - Tetree is currently ~1951x slower than Octree")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testPerformanceComparison() {
        // Create equivalent data structures for performance comparison
        // Octree performance comparison removed - SingleContentAdapter doesn't support ray tracing
        Tetree<String> perfTetree = new Tetree<>(new java.util.TreeMap<>());

        // Add data to Tetree
        perfTetree.insert(VALID_POINT_1, testLevel, "content1");
        perfTetree.insert(VALID_POINT_2, testLevel, "content2");
        perfTetree.insert(VALID_POINT_3, testLevel, "content3");

        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 1.0f, 1.0f));

        // Warm up JVM
        for (int i = 0; i < 100; i++) {
            TetRayTracingSearch.rayIntersectedAll(ray, perfTetree);
        }

        // Octree benchmark removed - not supported by OctreeWithEntitiesSpatialIndexAdapter

        // Benchmark Tetree ray tracing
        long tetreeStart = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            TetRayTracingSearch.rayIntersectedAll(ray, perfTetree);
        }
        long tetreeEnd = System.nanoTime();
        double tetreeTime = (tetreeEnd - tetreeStart) / 1_000_000.0; // Convert to milliseconds

        // Calculate performance metrics
        double tetreeAvg = tetreeTime / 1000.0;

        System.out.printf("Performance Results (1000 iterations):%n");
        System.out.printf("Tetree ray tracing: %.3f ms total, %.6f ms avg%n", tetreeTime, tetreeAvg);
        
        // Note: Octree comparison removed as OctreeWithEntitiesSpatialIndexAdapter doesn't support ray tracing
        System.out.println("Note: Direct Octree comparison not available with current architecture");

        // Log performance for future optimization
        System.out.printf("TetRayTracingSearch performance: %.6f ms per operation%n", tetreeAvg);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRayDirections() {
        // Test rays in different directions
        Vector3f[] directions = { new Vector3f(1.0f, 0.0f, 0.0f),  // X-axis
                                  new Vector3f(0.0f, 1.0f, 0.0f),  // Y-axis
                                  new Vector3f(0.0f, 0.0f, 1.0f),  // Z-axis
                                  new Vector3f(1.0f, 1.0f, 0.0f),  // XY diagonal
                                  new Vector3f(1.0f, 0.0f, 1.0f),  // XZ diagonal
                                  new Vector3f(0.0f, 1.0f, 1.0f),  // YZ diagonal
                                  new Vector3f(1.0f, 1.0f, 1.0f),  // XYZ diagonal
        };

        for (Vector3f direction : directions) {
            Ray3D ray = new Ray3D(RAY_ORIGIN, direction);
            var intersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);

            assertNotNull(intersections, "Should handle direction " + direction);

            // Verify sorting
            for (int i = 1; i < intersections.size(); i++) {
                assertTrue(intersections.get(i - 1).distance <= intersections.get(i).distance,
                           "Results should be sorted by distance for direction " + direction);
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRayFromPointToPoint() {
        // Test ray from one point to another
        Point3f start = new Point3f(SCALE * 0.05f, SCALE * 0.05f, SCALE * 0.05f);
        Point3f end = new Point3f(SCALE * 0.4f, SCALE * 0.2f, SCALE * 0.15f);

        Ray3D ray = Ray3D.fromPoints(start, end);

        var intersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);
        assertNotNull(intersections);

        // Verify ray properties
        assertEquals(start.x, ray.origin().x, 1e-6f);
        assertEquals(start.y, ray.origin().y, 1e-6f);
        assertEquals(start.z, ray.origin().z, 1e-6f);

        // Direction should be normalized
        assertEquals(1.0f, ray.direction().length(), 1e-6f);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRayIntersectedAllBasic() {
        // Create a ray that should intersect some tetrahedra
        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 1.0f, 1.0f));

        var intersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);

        assertNotNull(intersections);
        // Debug output
        System.out.println("Found " + intersections.size() + " ray intersections");

        // Verify intersections are sorted by distance from ray origin
        for (int i = 1; i < intersections.size(); i++) {
            assertTrue(intersections.get(i - 1).distance <= intersections.get(i).distance,
                       "Intersections should be sorted by distance from ray origin");
        }

        // Verify all results have valid properties
        for (var intersection : intersections) {
            assertNotNull(intersection.content);
            assertNotNull(intersection.tetrahedronCenter);
            assertNotNull(intersection.intersectionPoint);
            assertTrue(intersection.index >= 0);
            assertTrue(intersection.distance >= 0);
            assertTrue(intersection.intersectedFace >= 0 && intersection.intersectedFace <= 3);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRayIntersectedFirst() {
        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 0.5f, 0.2f));

        var firstIntersection = TetRayTracingSearch.rayIntersectedFirst(ray, tetree);

        if (firstIntersection != null) {
            assertNotNull(firstIntersection.content);
            assertNotNull(firstIntersection.tetrahedronCenter);
            assertNotNull(firstIntersection.intersectionPoint);
            assertTrue(firstIntersection.index >= 0);
            assertTrue(firstIntersection.distance >= 0);
            assertTrue(firstIntersection.intersectedFace >= 0 && firstIntersection.intersectedFace <= 3);

            // Verify this is indeed the closest intersection
            var allIntersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);
            if (!allIntersections.isEmpty()) {
                assertEquals(firstIntersection.index, allIntersections.get(0).index,
                             "First intersection should match closest from all intersections");
                assertEquals(firstIntersection.distance, allIntersections.get(0).distance, 1e-6f,
                             "Distance should match");
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRayIntersectionPoints() {
        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 1.0f, 1.0f));

        var intersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);

        for (var intersection : intersections) {
            assertNotNull(intersection.intersectionPoint, "Intersection point should not be null");

            // Intersection point should be along the ray
            Vector3f toIntersection = new Vector3f();
            toIntersection.sub(intersection.intersectionPoint, ray.origin());

            // Check if intersection point is in the ray direction
            float dot = toIntersection.dot(ray.direction());
            assertTrue(dot >= -1e-6f, "Intersection point should be in ray direction or at origin");

            // Distance should match
            float calculatedDistance = toIntersection.length();
            assertEquals(intersection.distance, calculatedDistance, 1e-4f,
                         "Distance should match actual distance to intersection point");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testSimplexAggregationStrategies() {
        Ray3D ray = new Ray3D(RAY_ORIGIN, new Vector3f(1.0f, 0.8f, 0.6f));

        // Test different aggregation strategies
        for (var strategy : TetrahedralSearchBase.SimplexAggregationStrategy.values()) {
            var intersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree, strategy);

            assertNotNull(intersections, "Should handle " + strategy + " aggregation");

            // Verify all results are valid
            for (var intersection : intersections) {
                assertNotNull(intersection.content);
                assertNotNull(intersection.tetrahedronCenter);
                assertNotNull(intersection.intersectionPoint);
                assertTrue(intersection.index >= 0);
                assertTrue(intersection.intersectedFace >= 0 && intersection.intersectedFace <= 3);
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testZeroLengthRay() {
        // Test edge case with very short ray direction
        Vector3f shortDirection = new Vector3f(1e-10f, 1e-10f, 1e-10f);

        // Ray constructor should normalize the direction
        Ray3D ray = new Ray3D(RAY_ORIGIN, shortDirection);

        // Direction should be normalized to unit length
        assertEquals(1.0f, ray.direction().length(), 1e-6f, "Ray direction should be normalized to unit length");

        var intersections = TetRayTracingSearch.rayIntersectedAll(ray, tetree);
        assertNotNull(intersections);
    }
}
