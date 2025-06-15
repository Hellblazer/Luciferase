/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.octree;

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
 * Edge case tests for Octree ray intersection functionality.
 * Tests challenging scenarios that could break the ray intersection implementation.
 *
 * @author hal.hildebrand
 */
public class OctreeRayEdgeCaseTest {

    private Octree<LongEntityID, String> octree;
    private SequentialLongIDGenerator idGenerator;

    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
        idGenerator = new SequentialLongIDGenerator();
    }

    @Test
    void testRayWithZeroLength() {
        Point3f pos = new Point3f(100, 100, 100);
        octree.insert(pos, (byte) 10, "Entity");

        // Ray3D constructor should reject zero max distance
        assertThrows(IllegalArgumentException.class, () -> {
            new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 0.0f);
        }, "Ray with zero max distance should throw IllegalArgumentException");
    }

    @Test
    void testRayAxisAligned() {
        Point3f pos = new Point3f(100, 100, 100);
        octree.insert(pos, (byte) 10, "Entity");

        // X-axis aligned ray
        Ray3D rayX = new Ray3D(new Point3f(50, 100, 100), new Vector3f(1, 0, 0), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersectionsX = octree.rayIntersectAll(rayX);
        assertFalse(intersectionsX.isEmpty(), "X-axis aligned ray should intersect");

        // Y-axis aligned ray
        Ray3D rayY = new Ray3D(new Point3f(100, 50, 100), new Vector3f(0, 1, 0), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersectionsY = octree.rayIntersectAll(rayY);
        assertFalse(intersectionsY.isEmpty(), "Y-axis aligned ray should intersect");

        // Z-axis aligned ray
        Ray3D rayZ = new Ray3D(new Point3f(100, 100, 50), new Vector3f(0, 0, 1), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersectionsZ = octree.rayIntersectAll(rayZ);
        assertFalse(intersectionsZ.isEmpty(), "Z-axis aligned ray should intersect");
    }

    @Test
    void testOverlappingBoundedEntities() {
        Point3f center = new Point3f(100, 100, 100);
        EntityBounds largeBounds = new EntityBounds(new Point3f(90, 90, 90), new Point3f(110, 110, 110));
        EntityBounds smallBounds = new EntityBounds(new Point3f(95, 95, 95), new Point3f(105, 105, 105));

        LongEntityID largeId = idGenerator.generateID();
        LongEntityID smallId = idGenerator.generateID();

        octree.insert(largeId, center, (byte) 10, "LargeEntity", largeBounds);
        octree.insert(smallId, center, (byte) 10, "SmallEntity", smallBounds);

        Ray3D ray = new Ray3D(new Point3f(50, 100, 100), new Vector3f(1, 0, 0), 200.0f);
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);
        
        assertEquals(2, intersections.size(), "Should find both overlapping entities");
        
        // Verify intersections are sorted by distance
        assertTrue(intersections.get(0).distance() <= intersections.get(1).distance(), 
                  "Intersections should be sorted by distance");
    }

    @Test
    void testRayFirstIntersectionConsistency() {
        Point3f pos1 = new Point3f(100, 100, 100);
        Point3f pos2 = new Point3f(150, 150, 150);
        Point3f pos3 = new Point3f(200, 200, 200);
        
        octree.insert(pos1, (byte) 10, "First");
        octree.insert(pos2, (byte) 10, "Second");
        octree.insert(pos3, (byte) 10, "Third");

        Ray3D ray = new Ray3D(new Point3f(50, 50, 50), new Vector3f(1, 1, 1), 500.0f);
        
        List<SpatialIndex.RayIntersection<LongEntityID, String>> allIntersections = octree.rayIntersectAll(ray);
        Optional<SpatialIndex.RayIntersection<LongEntityID, String>> firstIntersection = octree.rayIntersectFirst(ray);
        
        if (!allIntersections.isEmpty()) {
            assertTrue(firstIntersection.isPresent(), "rayIntersectFirst should be present when rayIntersectAll finds intersections");
            assertEquals(allIntersections.get(0).entityId(), firstIntersection.get().entityId(),
                        "rayIntersectFirst should match first element of rayIntersectAll");
            assertEquals(allIntersections.get(0).distance(), firstIntersection.get().distance(), 0.001f,
                        "Distances should match between rayIntersectFirst and rayIntersectAll");
        } else {
            assertFalse(firstIntersection.isPresent(), "rayIntersectFirst should be empty when rayIntersectAll finds no intersections");
        }
    }

    @Test
    void testEmptyOctreeRayIntersection() {
        Ray3D ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 1, 1), 1000.0f);
        
        List<SpatialIndex.RayIntersection<LongEntityID, String>> intersections = octree.rayIntersectAll(ray);
        Optional<SpatialIndex.RayIntersection<LongEntityID, String>> firstIntersection = octree.rayIntersectFirst(ray);
        
        assertTrue(intersections.isEmpty(), "Empty octree should produce no intersections");
        assertFalse(firstIntersection.isPresent(), "Empty octree should produce no first intersection");
    }
}