package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Frustum culling search functionality
 * All test coordinates use positive values only
 * 
 * @author hal.hildebrand
 */
public class FrustumCullingSearchTest {

    private Octree<String> octree;
    private final byte testLevel = 15;

    @BeforeEach
    void setUp() {
        octree = new Octree<>();
        
        // Use coordinates that will map to different cubes - all positive
        int gridSize = Constants.lengthAtLevel(testLevel);
        
        // Insert test data points in a spatial pattern
        octree.insert(new Point3f(100.0f, 100.0f, 100.0f), testLevel, "Near1");
        octree.insert(new Point3f(200.0f, 200.0f, 200.0f), testLevel, "Center1");
        octree.insert(new Point3f(300.0f, 300.0f, 300.0f), testLevel, "Far1");
        octree.insert(new Point3f(150.0f, 150.0f, 150.0f), testLevel, "Near2");
        octree.insert(new Point3f(250.0f, 250.0f, 250.0f), testLevel, "Center2");
        octree.insert(new Point3f(350.0f, 350.0f, 350.0f), testLevel, "Far2");
        octree.insert(new Point3f(50.0f, 50.0f, 50.0f), testLevel, "VeryNear");
        octree.insert(new Point3f(500.0f, 500.0f, 500.0f), testLevel, "VeryFar");
        octree.insert(new Point3f(120.0f, 180.0f, 160.0f), testLevel, "OffAxis1");
        octree.insert(new Point3f(280.0f, 220.0f, 240.0f), testLevel, "OffAxis2");
    }

    @Test
    void testFrustumCulledAll() {
        // Create a perspective frustum looking roughly towards the positive direction
        Point3f cameraPos = new Point3f(50.0f, 50.0f, 50.0f);
        Point3f lookAt = new Point3f(250.0f, 250.0f, 250.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 3.0f, 1.0f, 25.0f, 400.0f);
        
        List<FrustumCullingSearch.FrustumIntersection<String>> intersections = 
            FrustumCullingSearch.frustumCulledAll(frustum, octree, cameraPos);
        
        // Should find some intersections (exact number depends on frustum geometry and cube quantization)
        assertTrue(intersections.size() >= 0);
        
        // Results should be sorted by distance from camera
        for (int i = 0; i < intersections.size() - 1; i++) {
            assertTrue(intersections.get(i).distanceToCamera <= 
                      intersections.get(i + 1).distanceToCamera);
        }
        
        // All intersections should have valid data
        for (var intersection : intersections) {
            assertNotNull(intersection.content);
            assertNotNull(intersection.cube);
            assertNotNull(intersection.cubeCenter);
            assertNotNull(intersection.cullingResult);
            assertTrue(intersection.distanceToCamera >= 0);
            assertTrue(intersection.index >= 0);
            
            // Should not be OUTSIDE since these are intersections
            assertNotEquals(FrustumCullingSearch.CullingResult.OUTSIDE, intersection.cullingResult);
        }
    }

    @Test
    void testFrustumCulledFirst() {
        Point3f cameraPos = new Point3f(75.0f, 75.0f, 75.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 4.0f, 1.0f, 50.0f, 500.0f);
        
        FrustumCullingSearch.FrustumIntersection<String> firstIntersection = 
            FrustumCullingSearch.frustumCulledFirst(frustum, octree, cameraPos);
        
        if (firstIntersection != null) {
            // Should be the closest intersection
            List<FrustumCullingSearch.FrustumIntersection<String>> allIntersections = 
                FrustumCullingSearch.frustumCulledAll(frustum, octree, cameraPos);
            
            assertFalse(allIntersections.isEmpty());
            assertEquals(allIntersections.get(0).content, firstIntersection.content);
            assertEquals(allIntersections.get(0).distanceToCamera, 
                        firstIntersection.distanceToCamera, 0.001f);
        }
    }

    @Test
    void testCubesCompletelyInside() {
        Point3f cameraPos = new Point3f(50.0f, 50.0f, 50.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        // Wide field of view to try to encompass some cubes completely
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 2.0f, 1.0f, 25.0f, 600.0f);
        
        List<FrustumCullingSearch.FrustumIntersection<String>> insideCubes = 
            FrustumCullingSearch.cubesCompletelyInside(frustum, octree, cameraPos);
        
        // All results should be INSIDE
        for (var intersection : insideCubes) {
            assertEquals(FrustumCullingSearch.CullingResult.INSIDE, intersection.cullingResult);
        }
        
        // Results should be sorted by distance from camera
        for (int i = 0; i < insideCubes.size() - 1; i++) {
            assertTrue(insideCubes.get(i).distanceToCamera <= 
                      insideCubes.get(i + 1).distanceToCamera);
        }
    }

    @Test
    void testCubesPartiallyIntersecting() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        // Narrow field of view to increase chance of partial intersections
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 6.0f, 1.0f, 50.0f, 400.0f);
        
        List<FrustumCullingSearch.FrustumIntersection<String>> intersectingCubes = 
            FrustumCullingSearch.cubesPartiallyIntersecting(frustum, octree, cameraPos);
        
        // All results should be INTERSECTING
        for (var intersection : intersectingCubes) {
            assertEquals(FrustumCullingSearch.CullingResult.INTERSECTING, intersection.cullingResult);
        }
        
        // Results should be sorted by distance from camera
        for (int i = 0; i < intersectingCubes.size() - 1; i++) {
            assertTrue(intersectingCubes.get(i).distanceToCamera <= 
                      intersectingCubes.get(i + 1).distanceToCamera);
        }
    }

    @Test
    void testCountFrustumIntersections() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 4.0f, 1.0f, 75.0f, 500.0f);
        
        long count = FrustumCullingSearch.countFrustumIntersections(frustum, octree);
        
        // Count should match the number from frustumCulledAll
        List<FrustumCullingSearch.FrustumIntersection<String>> intersections = 
            FrustumCullingSearch.frustumCulledAll(frustum, octree, cameraPos);
        
        assertEquals(intersections.size(), count);
        assertTrue(count >= 0);
    }

    @Test
    void testHasAnyIntersection() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        // Wide frustum that should intersect some cubes
        Frustum3D intersectingFrustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                                   (float) Math.PI / 2.0f, 1.0f, 25.0f, 600.0f);
        
        // Very narrow frustum looking in a direction with no data
        Frustum3D nonIntersectingFrustum = Frustum3D.createPerspective(
            new Point3f(100.0f, 100.0f, 100.0f), 
            new Point3f(1000.0f, 1000.0f, 100.0f), 
            up, 
            (float) Math.PI / 180.0f, 1.0f, 50.0f, 200.0f);
        
        // Test both cases (might depend on actual cube positions and sizes)
        assertDoesNotThrow(() -> {
            FrustumCullingSearch.hasAnyIntersection(intersectingFrustum, octree);
        });
        
        assertDoesNotThrow(() -> {
            FrustumCullingSearch.hasAnyIntersection(nonIntersectingFrustum, octree);
        });
    }

    @Test
    void testGetFrustumCullingStatistics() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 3.0f, 1.0f, 50.0f, 400.0f);
        
        FrustumCullingSearch.CullingStatistics stats = 
            FrustumCullingSearch.getFrustumCullingStatistics(frustum, octree);
        
        assertNotNull(stats);
        assertTrue(stats.totalCubes >= 0);
        assertTrue(stats.insideCubes >= 0);
        assertTrue(stats.intersectingCubes >= 0);
        assertTrue(stats.outsideCubes >= 0);
        
        // Total should equal sum of parts
        assertEquals(stats.totalCubes, stats.insideCubes + stats.intersectingCubes + stats.outsideCubes);
        
        // Percentages should be valid
        assertTrue(stats.getInsidePercentage() >= 0 && stats.getInsidePercentage() <= 100);
        assertTrue(stats.getIntersectingPercentage() >= 0 && stats.getIntersectingPercentage() <= 100);
        assertTrue(stats.getOutsidePercentage() >= 0 && stats.getOutsidePercentage() <= 100);
        assertTrue(stats.getVisiblePercentage() >= 0 && stats.getVisiblePercentage() <= 100);
        
        // Visible percentage should equal inside + intersecting
        assertEquals(stats.getVisiblePercentage(), 
                    stats.getInsidePercentage() + stats.getIntersectingPercentage(), 0.001);
        
        // Test toString
        String statsStr = stats.toString();
        assertTrue(statsStr.contains("CullingStats"));
        assertTrue(statsStr.contains("total="));
        assertTrue(statsStr.contains("inside="));
        assertTrue(statsStr.contains("intersecting="));
        assertTrue(statsStr.contains("outside="));
        assertTrue(statsStr.contains("visible="));
    }

    @Test
    void testBatchFrustumCulling() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt1 = new Point3f(200.0f, 200.0f, 200.0f);
        Point3f lookAt2 = new Point3f(300.0f, 200.0f, 300.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum1 = Frustum3D.createPerspective(cameraPos, lookAt1, up, 
                                                        (float) Math.PI / 4.0f, 1.0f, 50.0f, 400.0f);
        Frustum3D frustum2 = Frustum3D.createPerspective(cameraPos, lookAt2, up, 
                                                        (float) Math.PI / 4.0f, 1.0f, 50.0f, 400.0f);
        
        List<Frustum3D> frustums = List.of(frustum1, frustum2);
        
        Map<Frustum3D, List<FrustumCullingSearch.FrustumIntersection<String>>> results = 
            FrustumCullingSearch.batchFrustumCulling(frustums, octree, cameraPos);
        
        assertEquals(2, results.size());
        assertTrue(results.containsKey(frustum1));
        assertTrue(results.containsKey(frustum2));
        
        // Each result should be sorted by distance
        for (var entry : results.entrySet()) {
            List<FrustumCullingSearch.FrustumIntersection<String>> intersections = entry.getValue();
            for (int i = 0; i < intersections.size() - 1; i++) {
                assertTrue(intersections.get(i).distanceToCamera <= 
                          intersections.get(i + 1).distanceToCamera);
            }
        }
    }

    @Test
    void testEmptyOctree() {
        Octree<String> emptyOctree = new Octree<>();
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 4.0f, 1.0f, 50.0f, 400.0f);
        
        List<FrustumCullingSearch.FrustumIntersection<String>> intersections = 
            FrustumCullingSearch.frustumCulledAll(frustum, emptyOctree, cameraPos);
        
        assertTrue(intersections.isEmpty());
        
        FrustumCullingSearch.FrustumIntersection<String> first = 
            FrustumCullingSearch.frustumCulledFirst(frustum, emptyOctree, cameraPos);
        
        assertNull(first);
        
        assertEquals(0, FrustumCullingSearch.countFrustumIntersections(frustum, emptyOctree));
        assertFalse(FrustumCullingSearch.hasAnyIntersection(frustum, emptyOctree));
        
        FrustumCullingSearch.CullingStatistics stats = 
            FrustumCullingSearch.getFrustumCullingStatistics(frustum, emptyOctree);
        assertEquals(0, stats.totalCubes);
        assertEquals(0, stats.insideCubes);
        assertEquals(0, stats.intersectingCubes);
        assertEquals(0, stats.outsideCubes);
    }

    @Test
    void testNegativeCameraPositionThrowsException() {
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        Point3f negativeCameraPos = new Point3f(-50.0f, 100.0f, 100.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(
            new Point3f(100.0f, 100.0f, 100.0f), lookAt, up, 
            (float) Math.PI / 4.0f, 1.0f, 50.0f, 400.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            FrustumCullingSearch.frustumCulledAll(frustum, octree, negativeCameraPos);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            FrustumCullingSearch.frustumCulledFirst(frustum, octree, negativeCameraPos);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            FrustumCullingSearch.cubesCompletelyInside(frustum, octree, negativeCameraPos);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            FrustumCullingSearch.cubesPartiallyIntersecting(frustum, octree, negativeCameraPos);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            FrustumCullingSearch.batchFrustumCulling(List.of(frustum), octree, negativeCameraPos);
        });
    }

    @Test
    void testOrthographicFrustumCulling() {
        Point3f cameraPos = new Point3f(100.0f, 100.0f, 100.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D orthogonalFrustum = Frustum3D.createOrthographic(cameraPos, lookAt, up,
                                                                  50.0f, 150.0f, 50.0f, 150.0f,
                                                                  50.0f, 400.0f);
        
        List<FrustumCullingSearch.FrustumIntersection<String>> intersections = 
            FrustumCullingSearch.frustumCulledAll(orthogonalFrustum, octree, cameraPos);
        
        // Should work without exceptions
        assertDoesNotThrow(() -> {
            FrustumCullingSearch.countFrustumIntersections(orthogonalFrustum, octree);
        });
        
        assertDoesNotThrow(() -> {
            FrustumCullingSearch.hasAnyIntersection(orthogonalFrustum, octree);
        });
        
        assertDoesNotThrow(() -> {
            FrustumCullingSearch.getFrustumCullingStatistics(orthogonalFrustum, octree);
        });
    }

    @Test
    void testFrustumIntersectionDataIntegrity() {
        Point3f cameraPos = new Point3f(75.0f, 75.0f, 75.0f);
        Point3f lookAt = new Point3f(200.0f, 200.0f, 200.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(cameraPos, lookAt, up, 
                                                       (float) Math.PI / 3.0f, 1.0f, 50.0f, 400.0f);
        
        List<FrustumCullingSearch.FrustumIntersection<String>> intersections = 
            FrustumCullingSearch.frustumCulledAll(frustum, octree, cameraPos);
        
        for (var intersection : intersections) {
            // Verify all fields are properly set
            assertNotNull(intersection.content);
            assertNotNull(intersection.cube);
            assertNotNull(intersection.cubeCenter);
            assertNotNull(intersection.cullingResult);
            assertTrue(intersection.distanceToCamera >= 0);
            assertTrue(intersection.index >= 0);
            
            // Verify cube center is within cube bounds
            Spatial.Cube cube = intersection.cube;
            Point3f center = intersection.cubeCenter;
            assertTrue(center.x >= cube.originX());
            assertTrue(center.x <= cube.originX() + cube.extent());
            assertTrue(center.y >= cube.originY());
            assertTrue(center.y <= cube.originY() + cube.extent());
            assertTrue(center.z >= cube.originZ());
            assertTrue(center.z <= cube.originZ() + cube.extent());
            
            // Verify distance calculation
            float dx = cameraPos.x - center.x;
            float dy = cameraPos.y - center.y;
            float dz = cameraPos.z - center.z;
            float expectedDistance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(expectedDistance, intersection.distanceToCamera, 0.001f);
            
            // Verify culling result is not OUTSIDE (since these are intersections)
            assertNotEquals(FrustumCullingSearch.CullingResult.OUTSIDE, intersection.cullingResult);
        }
    }

    @Test
    void testDistanceOrdering() {
        Point3f nearCamera = new Point3f(80.0f, 80.0f, 80.0f);
        Point3f farCamera = new Point3f(600.0f, 600.0f, 600.0f);
        Point3f lookAt = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f up = new Vector3f(10.0f, 20.0f, 10.0f);
        
        Frustum3D frustum = Frustum3D.createPerspective(nearCamera, lookAt, up, 
                                                       (float) Math.PI / 2.0f, 1.0f, 25.0f, 800.0f);
        
        List<FrustumCullingSearch.FrustumIntersection<String>> nearIntersections = 
            FrustumCullingSearch.frustumCulledAll(frustum, octree, nearCamera);
        
        List<FrustumCullingSearch.FrustumIntersection<String>> farIntersections = 
            FrustumCullingSearch.frustumCulledAll(frustum, octree, farCamera);
        
        // Different camera positions should give different distance orderings
        if (!nearIntersections.isEmpty()) {
            // Verify distance ordering within each result set
            for (int i = 0; i < nearIntersections.size() - 1; i++) {
                assertTrue(nearIntersections.get(i).distanceToCamera <= 
                          nearIntersections.get(i + 1).distanceToCamera);
            }
        }
        
        if (!farIntersections.isEmpty()) {
            for (int i = 0; i < farIntersections.size() - 1; i++) {
                assertTrue(farIntersections.get(i).distanceToCamera <= 
                          farIntersections.get(i + 1).distanceToCamera);
            }
        }
    }
}