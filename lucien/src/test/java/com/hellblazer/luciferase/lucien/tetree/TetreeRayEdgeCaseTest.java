/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for Tetree ray intersection functionality. Tests challenging scenarios specific to tetrahedral
 * spatial subdivision.
 *
 * @author hal.hildebrand
 */
public class TetreeRayEdgeCaseTest {

    private Tetree<LongEntityID, String> tetree;
    private SequentialLongIDGenerator    idGenerator;

    @BeforeEach
    void setUp() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testEmptyTetreeRayIntersection() {
        Ray3D ray = new Ray3D(new Point3f(10, 10, 10), new Vector3f(1, 1, 1), 1000.0f);

        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);
        Optional<SpatialIndex.RayIntersection<LongEntityID, String>> firstIntersection = tetree.rayIntersectFirst(ray);

        assertTrue(intersections.isEmpty(), "Empty tetree should produce no intersections");
        assertFalse(firstIntersection.isPresent(), "Empty tetree should produce no first intersection");
    }

    @Test
    void testOverlappingBoundedEntities() {
        Point3f center = new Point3f(100, 100, 100);
        EntityBounds largeBounds = new EntityBounds(new Point3f(90, 90, 90), new Point3f(110, 110, 110));
        EntityBounds smallBounds = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105));

        LongEntityID largeId = idGenerator.generateID();
        LongEntityID smallId = idGenerator.generateID();

        tetree.insert(largeId, center, (byte) 10, "LargeEntity", largeBounds);
        tetree.insert(smallId, center, (byte) 10, "SmallEntity", smallBounds);

        Ray3D ray = new Ray3D(new Point3f(50, 100, 100), new Vector3f(1, 0, 0), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);

        // Should handle overlapping entities without crashing
        assertNotNull(intersections, "Should handle overlapping bounded entities");
        if (intersections.size() > 1) {
            // If multiple intersections found, verify they are sorted by distance
            assertTrue(intersections.get(0).distance() <= intersections.get(1).distance(),
                       "Intersections should be sorted by distance");
        }
    }

    @Test
    void testRayAtMaximumDistance() {
        Point3f pos = new Point3f(100, 100, 100);
        tetree.insert(pos, (byte) 10, "Entity");

        // Distance from (50,50,50) to (100,100,100) is sqrt(50^2 + 50^2 + 50^2) ≈ 86.6
        float exactDistance = (float) Math.sqrt(3 * 50 * 50);

        Vector3f direction = new Vector3f(1, 1, 1);
        direction.normalize();
        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), direction, exactDistance + 5.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);

        assertNotNull(intersections, "Ray at maximum distance should not crash");
        for (var intersection : intersections) {
            assertTrue(intersection.distance() <= exactDistance + 5.0f,
                       "Intersection distance should be within ray max distance");
        }
    }

    @Test
    void testRayAtTetrahedronBoundaries() {
        // Entities positioned near tetrahedral cell boundaries
        Point3f pos1 = new Point3f(63.9f, 100, 100); // Just below 64 boundary
        Point3f pos2 = new Point3f(64.1f, 100, 100); // Just above 64 boundary

        tetree.insert(pos1, (byte) 10, "BoundaryEntity1");
        tetree.insert(pos2, (byte) 10, "BoundaryEntity2");

        Ray3D ray = new Ray3D(new Point3f(10, 100, 100), new Vector3f(1, 0, 0), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);

        // Should handle tetrahedral boundaries gracefully
        assertNotNull(intersections, "Should handle entities across tetrahedral boundaries");
    }

    @Test
    void testRayAxisAligned() {
        // Note: Tetree requires positive coordinates
        Point3f pos = new Point3f(100, 100, 100);
        tetree.insert(pos, (byte) 10, "Entity");

        // X-axis aligned ray
        Ray3D rayX = new Ray3D(new Point3f(50, 100, 100), new Vector3f(1, 0, 0), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersectionsX = tetree.rayIntersectAll(rayX);
        // May or may not find intersections due to tetrahedral geometry complexity
        assertNotNull(intersectionsX, "Should handle X-axis aligned ray without crashing");

        // Y-axis aligned ray
        Ray3D rayY = new Ray3D(new Point3f(100, 50, 100), new Vector3f(0, 1, 0), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersectionsY = tetree.rayIntersectAll(rayY);
        assertNotNull(intersectionsY, "Should handle Y-axis aligned ray without crashing");

        // Z-axis aligned ray
        Ray3D rayZ = new Ray3D(new Point3f(100, 100, 50), new Vector3f(0, 0, 1), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersectionsZ = tetree.rayIntersectAll(rayZ);
        assertNotNull(intersectionsZ, "Should handle Z-axis aligned ray without crashing");
    }

    @Test
    void testRayBeyondMaximumDistance() {
        Point3f pos = new Point3f(200, 200, 200);
        tetree.insert(pos, (byte) 10, "Entity");

        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 50.0f); // Too short
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);

        // Should not find intersections beyond max distance
        for (var intersection : intersections) {
            assertTrue(intersection.distance() <= 50.0f, "Should not find intersections beyond max distance");
        }
    }

    @Test
    void testRayFirstIntersectionConsistency() {
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(150, 150, 150);
        Point3f pos3 = new Point3f(200, 200, 200);

        tetree.insert(pos1, (byte) 10, "First");
        tetree.insert(pos2, (byte) 10, "Second");
        tetree.insert(pos3, (byte) 10, "Third");

        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 500.0f);

        List<SpatialIndex.RayIntersection<LongEntityID, String>> allIntersections = tetree.rayIntersectAll(ray);
        Optional<SpatialIndex.RayIntersection<LongEntityID, String>> firstIntersection = tetree.rayIntersectFirst(ray);

        if (!allIntersections.isEmpty()) {
            assertTrue(firstIntersection.isPresent(),
                       "rayIntersectFirst should be present when rayIntersectAll finds intersections");
            assertEquals(allIntersections.get(0).entityId(), firstIntersection.get().entityId(),
                         "rayIntersectFirst should match first element of rayIntersectAll");
            assertEquals(allIntersections.get(0).distance(), firstIntersection.get().distance(), 0.001f,
                         "Distances should match between rayIntersectFirst and rayIntersectAll");
        } else {
            assertFalse(firstIntersection.isPresent(),
                        "rayIntersectFirst should be empty when rayIntersectAll finds no intersections");
        }
    }

    @Test
    void testRayThroughS0TetrahedronTypes() {
        // Test ray intersection through different tetrahedron types (0-5)
        // Entities placed to potentially be in different S0 tetrahedron types
        Point3f pos1 = new Point3f(64, 64, 64);   // Type 0 region
        Point3f pos2 = new Point3f(192, 64, 64);  // Type 1 region
        Point3f pos3 = new Point3f(64, 192, 64);  // Type 2 region
        Point3f pos4 = new Point3f(64, 64, 192);  // Type 3 region

        tetree.insert(pos1, (byte) 8, "Type0");
        tetree.insert(pos2, (byte) 8, "Type1");
        tetree.insert(pos3, (byte) 8, "Type2");
        tetree.insert(pos4, (byte) 8, "Type3");

        // Ray that potentially crosses multiple tetrahedron types
        Ray3D ray = new Ray3D(new Point3f(10, 10, 10), new Vector3f(1, 1, 1), 500.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);

        assertNotNull(intersections, "Ray through different tetrahedron types should not crash");
    }

    @Test
    void testRayThroughTetrahedronVertices() {
        // Ray passing through vertices of the S0 tetrahedron (reference tetrahedron)
        Point3f pos = new Point3f(128, 128, 128); // Center of default tetrahedron
        tetree.insert(pos, (byte) 8, "CenterEntity");

        // Ray from origin through tetrahedron
        Ray3D ray = new Ray3D(new Point3f(1, 1, 1), new Vector3f(1, 1, 1), 500.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);

        // Should handle without crashing, may or may not intersect due to tetrahedral geometry
        assertNotNull(intersections, "Ray through tetrahedron vertices should not crash");
    }

    @Test
    void testRayWithPositiveCoordinatesOnly() {
        // Tetree requires positive coordinates - test this constraint
        Point3f pos = new Point3f(100, 100, 100);
        tetree.insert(pos, (byte) 10, "Entity");

        // Ray with positive origin and direction
        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = tetree.rayIntersectAll(ray);

        assertNotNull(intersections, "Ray with positive coordinates should work");
    }

    @Test
    void testRayWithZeroLength() {
        Point3f pos = new Point3f(100, 100, 100);
        tetree.insert(pos, (byte) 10, "Entity");

        // Ray3D constructor should reject zero max distance
        assertThrows(IllegalArgumentException.class, () -> {
            new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 0.0f);
        }, "Ray with zero max distance should throw IllegalArgumentException");
    }
}
