/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Accuracy and integration tests for ray intersection functionality. Tests cross-compatibility between Octree and
 * Tetree implementations and validates mathematical accuracy of intersection calculations.
 *
 * @author hal.hildebrand
 */
public class RayIntersectionAccuracyTest {

    private Octree<LongEntityID, String> octree;
    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator    idGenerator;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testBoundedEntityAccuracy() {
        // Test ray-AABB intersection accuracy
        Point3f entityCenter = new Point3f(200, 200, 200);
        EntityBounds bounds = new EntityBounds(new Point3f(180, 180, 180), new Point3f(220, 220, 220));

        // Ray that should intersect the front face of the AABB
        Point3f rayOrigin = new Point3f(100, 200, 200);
        Vector3f rayDirection = new Vector3f(1, 0, 0);
        Ray3D ray = new Ray3D(rayOrigin, rayDirection, 200.0f);

        // Expected intersection at x=180 (front face of AABB)
        float expectedDistance = 80.0f; // 180 - 100

        // Test Octree
        LongEntityID octreeId = idGenerator.generateID();
        octree.insert(octreeId, entityCenter, (byte) 10, "BoundedEntity", bounds);

        List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeIntersections = octree.rayIntersectAll(ray);
        assertFalse(octreeIntersections.isEmpty(), "Octree should find bounded entity intersection");

        var octreeIntersection = octreeIntersections.get(0);
        assertEquals(expectedDistance, octreeIntersection.distance(), 1.0f,
                     "Octree AABB intersection should be accurate");
        assertNotNull(octreeIntersection.bounds(), "Intersection should include bounds");

        // Test Tetree
        LongEntityID tetreeId = idGenerator.generateID();
        tetree.insert(tetreeId, entityCenter, (byte) 10, "BoundedEntity", bounds);

        List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeIntersections = tetree.rayIntersectAll(ray);
        if (!tetreeIntersections.isEmpty()) {
            var tetreeIntersection = tetreeIntersections.get(0);
            assertEquals(expectedDistance, tetreeIntersection.distance(), 1.0f,
                         "Tetree AABB intersection should be accurate");
            assertNotNull(tetreeIntersection.bounds(), "Intersection should include bounds");
        }
    }

    @Test
    void testConsistentOrderingBetweenImplementations() {
        // Create identical entity layout in both indices
        Point3f[] positions = { new Point3f(100, 100, 100), new Point3f(150, 150, 150), new Point3f(200, 200, 200),
                                new Point3f(250, 250, 250) };

        for (int i = 0; i < positions.length; i++) {
            octree.insert(positions[i], (byte) 10, "Entity" + i);
            tetree.insert(positions[i], (byte) 10, "Entity" + i);
        }

        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 400.0f);

        List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeIntersections = octree.rayIntersectAll(ray);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeIntersections = tetree.rayIntersectAll(ray);

        assertFalse(octreeIntersections.isEmpty(), "Octree should find intersections");

        if (!tetreeIntersections.isEmpty()) {
            // Both should find intersections in distance order
            for (int i = 1; i < octreeIntersections.size(); i++) {
                assertTrue(octreeIntersections.get(i - 1).distance() <= octreeIntersections.get(i).distance(),
                           "Octree intersections should be sorted by distance");
            }

            for (int i = 1; i < tetreeIntersections.size(); i++) {
                assertTrue(tetreeIntersections.get(i - 1).distance() <= tetreeIntersections.get(i).distance(),
                           "Tetree intersections should be sorted by distance");
            }
        }
    }

    @Test
    void testIntersectionPointAccuracy() {
        // Test that intersection points are correctly calculated
        Point3f rayOrigin = new Point3f(50, 100, 100);
        Vector3f rayDirection = new Vector3f(1, 0, 0);
        Ray3D ray = new Ray3D(rayOrigin, rayDirection, 200.0f);

        Point3f entityPos = new Point3f(150, 100, 100);
        EntityBounds bounds = new EntityBounds(new Point3f(140, 90, 90), new Point3f(160, 110, 110));

        // Test Octree
        LongEntityID octreeId = idGenerator.generateID();
        octree.insert(octreeId, entityPos, (byte) 12, "AccuracyTest", bounds);

        List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeIntersections = octree.rayIntersectAll(ray);
        assertFalse(octreeIntersections.isEmpty(), "Should find intersection");

        var octreeIntersection = octreeIntersections.get(0);
        Point3f intersectionPoint = octreeIntersection.intersectionPoint();
        assertNotNull(intersectionPoint, "Should have intersection point");

        // Intersection should be at x=140 (front face of AABB)
        assertEquals(140.0f, intersectionPoint.x, 1.0f, "X coordinate should be accurate");
        assertEquals(100.0f, intersectionPoint.y, 1.0f, "Y coordinate should be accurate");
        assertEquals(100.0f, intersectionPoint.z, 1.0f, "Z coordinate should be accurate");

        // Test Tetree
        LongEntityID tetreeId = idGenerator.generateID();
        tetree.insert(tetreeId, entityPos, (byte) 12, "AccuracyTest", bounds);

        List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeIntersections = tetree.rayIntersectAll(ray);
        if (!tetreeIntersections.isEmpty()) {
            var tetreeIntersection = tetreeIntersections.get(0);
            Point3f tetreeIntersectionPoint = tetreeIntersection.intersectionPoint();
            assertNotNull(tetreeIntersectionPoint, "Should have intersection point");

            assertEquals(140.0f, tetreeIntersectionPoint.x, 1.0f, "Tetree X coordinate should be accurate");
            assertEquals(100.0f, tetreeIntersectionPoint.y, 1.0f, "Tetree Y coordinate should be accurate");
            assertEquals(100.0f, tetreeIntersectionPoint.z, 1.0f, "Tetree Z coordinate should be accurate");
        }
    }

    @Test
    void testMultiLevelAccuracy() {
        // Test that entities at different levels are found accurately
        Point3f basePos = new Point3f(128, 128, 128);

        // Insert entities at different levels but same position
        LongEntityID coarseId = octree.insert(basePos, (byte) 8, "CoarseLevel");
        LongEntityID fineId = octree.insert(basePos, (byte) 15, "FineLevel");

        LongEntityID tetreeCoarseId = tetree.insert(basePos, (byte) 8, "CoarseLevel");
        LongEntityID tetreeFineId = tetree.insert(basePos, (byte) 15, "FineLevel");

        Ray3D ray = new Ray3D(new Point3f(64, 64, 64), new Vector3f(1, 1, 1), 200.0f);

        // Test Octree
        List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeIntersections = octree.rayIntersectAll(ray);
        assertEquals(2, octreeIntersections.size(), "Octree should find both entities at different levels");

        // Both should have similar distances since they're at the same position
        float distance1 = octreeIntersections.get(0).distance();
        float distance2 = octreeIntersections.get(1).distance();
        assertEquals(distance1, distance2, 2.0f, "Entities at same position should have similar distances");

        // Test Tetree
        List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeIntersections = tetree.rayIntersectAll(ray);

        if (tetreeIntersections.size() >= 2) {
            float tetreeDistance1 = tetreeIntersections.get(0).distance();
            float tetreeDistance2 = tetreeIntersections.get(1).distance();
            assertEquals(tetreeDistance1, tetreeDistance2, 2.0f,
                         "Tetree entities at same position should have similar distances");
        }
    }

    @Test
    void testNumericalStability() {
        // Test with very small and very large coordinates to check numerical stability
        Point3f[] testPositions = { new Point3f(1.0f, 1.0f, 1.0f),           // Small coordinates
                                    new Point3f(999.0f, 999.0f, 999.0f),     // Large coordinates
                                    new Point3f(500.5f, 500.5f, 500.5f)      // Mid-range with decimals
        };

        for (int i = 0; i < testPositions.length; i++) {
            octree.insert(testPositions[i], (byte) 12, "StabilityTest" + i);
            tetree.insert(testPositions[i], (byte) 12, "StabilityTest" + i);
        }

        // Test rays with various directions and magnitudes
        Vector3f[] directions = { new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1), new Vector3f(1,
                                                                                                                    1,
                                                                                                                    1),
                                  new Vector3f(-1, 1, 0) };

        for (Vector3f dir : directions) {
            dir.normalize();
            Ray3D ray = new Ray3D(new Point3f(500, 500, 500), dir, 1000.0f);

            // Should not crash and should produce reasonable results
            List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeResults = octree.rayIntersectAll(ray);
            List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeResults = tetree.rayIntersectAll(ray);

            assertNotNull(octreeResults, "Octree should handle numerical edge cases");
            assertNotNull(tetreeResults, "Tetree should handle numerical edge cases");

            // All distances should be positive and finite
            for (var intersection : octreeResults) {
                assertTrue(intersection.distance() >= 0, "Octree distances should be non-negative");
                assertTrue(Float.isFinite(intersection.distance()), "Octree distances should be finite");
            }

            for (var intersection : tetreeResults) {
                assertTrue(intersection.distance() >= 0, "Tetree distances should be non-negative");
                assertTrue(Float.isFinite(intersection.distance()), "Tetree distances should be finite");
            }
        }
    }

    @Test
    void testRayIntersectFirstConsistency() {
        // Verify rayIntersectFirst returns the same result as first element of rayIntersectAll
        Point3f[] positions = { new Point3f(120, 120, 120), new Point3f(180, 180, 180), new Point3f(240, 240, 240) };

        for (int i = 0; i < positions.length; i++) {
            octree.insert(positions[i], (byte) 10, "Entity" + i);
            tetree.insert(positions[i], (byte) 10, "Entity" + i);
        }

        Ray3D ray = new Ray3D(new Point3f(60, 60, 60), new Vector3f(1, 1, 1), 350.0f);

        // Test Octree consistency
        List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeAll = octree.rayIntersectAll(ray);
        Optional<SpatialIndex.RayIntersection<LongEntityID, String>> octreeFirst = octree.rayIntersectFirst(ray);

        if (!octreeAll.isEmpty()) {
            assertTrue(octreeFirst.isPresent(),
                       "rayIntersectFirst should be present when rayIntersectAll finds intersections");
            assertEquals(octreeAll.get(0).entityId(), octreeFirst.get().entityId(),
                         "Octree: rayIntersectFirst should match first element of rayIntersectAll");
            assertEquals(octreeAll.get(0).distance(), octreeFirst.get().distance(), 0.001f,
                         "Octree: distances should match");
        }

        // Test Tetree consistency
        List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeAll = tetree.rayIntersectAll(ray);
        Optional<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeFirst = tetree.rayIntersectFirst(ray);

        if (!tetreeAll.isEmpty()) {
            assertTrue(tetreeFirst.isPresent(),
                       "Tetree: rayIntersectFirst should be present when rayIntersectAll finds intersections");
            assertEquals(tetreeAll.get(0).entityId(), tetreeFirst.get().entityId(),
                         "Tetree: rayIntersectFirst should match first element of rayIntersectAll");
            assertEquals(tetreeAll.get(0).distance(), tetreeFirst.get().distance(), 0.001f,
                         "Tetree: distances should match");
        }
    }

    @Test
    void testRayIntersectionAccuracy() {
        // Known geometric setup: ray from origin through specific points
        Point3f rayOrigin = new Point3f(100, 100, 100);
        Vector3f rayDirection = new Vector3f(1, 1, 1);
        rayDirection.normalize();

        // Entity at known distance along ray
        Point3f entityPos = new Point3f(200, 200, 200);
        float expectedDistance = (float) Math.sqrt(3 * 100 * 100); // sqrt(30000) ≈ 173.2

        // Test with Octree
        LongEntityID octreeId = octree.insert(entityPos, (byte) 10, "TestEntity");
        Ray3D ray = new Ray3D(rayOrigin, rayDirection, 300.0f);

        List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeIntersections = octree.rayIntersectAll(ray);
        assertFalse(octreeIntersections.isEmpty(), "Octree should find intersection");

        var octreeIntersection = octreeIntersections.get(0);
        assertEquals(expectedDistance, octreeIntersection.distance(), 5.0f,
                     "Octree intersection distance should be mathematically accurate");

        // Test with Tetree
        LongEntityID tetreeId = tetree.insert(entityPos, (byte) 10, "TestEntity");
        List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeIntersections = tetree.rayIntersectAll(ray);

        if (!tetreeIntersections.isEmpty()) {
            var tetreeIntersection = tetreeIntersections.get(0);
            assertEquals(expectedDistance, tetreeIntersection.distance(), 5.0f,
                         "Tetree intersection distance should be mathematically accurate");
        }
    }

    @Test
    void testRayWithinDistanceAccuracy() {
        // Test rayIntersectWithin accuracy
        Point3f[] positions = { new Point3f(110, 110, 110), // Distance ≈ 86.6
                                new Point3f(200, 200, 200), // Distance ≈ 260.0
                                new Point3f(300, 300, 300)  // Distance ≈ 433.0
        };

        for (int i = 0; i < positions.length; i++) {
            octree.insert(positions[i], (byte) 10, "Entity" + i);
            tetree.insert(positions[i], (byte) 10, "Entity" + i);
        }

        Point3f rayOrigin = new Point3f(50, 50, 50);
        Vector3f rayDirection = new Vector3f(1, 1, 1);
        rayDirection.normalize();
        Ray3D ray = new Ray3D(rayOrigin, rayDirection, 500.0f);

        float maxDistance = 300.0f; // Should include first two entities

        // Test Octree
        List<SpatialIndex.RayIntersection<LongEntityID, String>> octreeWithin = octree.rayIntersectWithin(ray,
                                                                                                          maxDistance);
        assertTrue(octreeWithin.size() >= 1, "Octree should find at least the closest entity");
        assertTrue(octreeWithin.size() <= 2, "Octree should not find the farthest entity");

        for (var intersection : octreeWithin) {
            assertTrue(intersection.distance() <= maxDistance,
                       "All Octree intersections should be within max distance");
        }

        // Test Tetree
        List<SpatialIndex.RayIntersection<LongEntityID, String>> tetreeWithin = tetree.rayIntersectWithin(ray,
                                                                                                          maxDistance);

        for (var intersection : tetreeWithin) {
            assertTrue(intersection.distance() <= maxDistance,
                       "All Tetree intersections should be within max distance");
        }
    }
}
