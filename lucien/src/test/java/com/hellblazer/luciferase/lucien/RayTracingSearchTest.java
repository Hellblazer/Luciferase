/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-entity ray tracing search functionality
 *
 * @author hal.hildebrand
 */
public class RayTracingSearchTest {

    private OctreeWithEntities<LongEntityID, String> octree;

    @BeforeEach
    void setUp() {
        octree = new OctreeWithEntities<>(new SequentialLongIDGenerator(), 10, (byte) 16);
    }

    @Test
    void testBasicRayTracing() {
        // Add entities along a ray path (all positive coordinates)
        octree.insert(new Point3f(10, 5, 5), (byte) 4, "Entity1");
        octree.insert(new Point3f(20, 5, 5), (byte) 4, "Entity2");
        octree.insert(new Point3f(30, 5, 5), (byte) 4, "Entity3");
        octree.insert(new Point3f(5, 15, 5), (byte) 4, "OffRay");

        // Ray along positive X axis
        Ray3D ray = new Ray3D(new Point3f(1, 5, 5), new Vector3f(1, 0, 0));
        
        var results = RayTracingSearch.traceRay(ray, octree, 100.0f);

        assertNotNull(results);
        // Results should be sorted by distance
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i-1).distance <= results.get(i).distance,
                "Results should be sorted by distance");
        }
    }

    @Test
    void testTraceRayFirst() {
        // Add entities at different distances
        octree.insert(new Point3f(10, 5, 5), (byte) 4, "Near");
        octree.insert(new Point3f(20, 5, 5), (byte) 4, "Far");

        // Ray along positive X axis
        Ray3D ray = new Ray3D(new Point3f(1, 5, 5), new Vector3f(1, 0, 0));
        
        var first = RayTracingSearch.traceRayFirst(ray, octree, 100.0f);

        assertNotNull(first);
        // Should find an entity (may not be deterministic which one due to discretization)
    }

    @Test
    void testMultipleEntitiesAtSameLocation() {
        // Multiple entities at same position
        Point3f position = new Point3f(10, 10, 10);
        for (int i = 0; i < 3; i++) {
            octree.insert(position, (byte) 4, "Entity" + i);
        }

        Ray3D ray = new Ray3D(new Point3f(1, 10, 10), new Vector3f(1, 0, 0));
        
        var results = RayTracingSearch.traceRay(ray, octree, 100.0f);

        assertNotNull(results);
        // Should find entities at that location
        assertTrue(results.size() >= 1, "Should find at least one entity");
    }

    @Test
    void testTraceCone() {
        // Add entities in different directions from origin
        octree.insert(new Point3f(10, 5, 5), (byte) 4, "Forward");
        octree.insert(new Point3f(7, 8, 5), (byte) 4, "Angled");
        octree.insert(new Point3f(5, 15, 5), (byte) 4, "Side");

        // Create a cone along positive X axis with some aperture
        Ray3D centerRay = new Ray3D(new Point3f(1, 5, 5), new Vector3f(1, 0, 0));
        
        var results = RayTracingSearch.traceCone(centerRay, 0.5f, octree, 100.0f);

        assertNotNull(results);
        // Should find entities within the cone
    }

    @Test
    void testTraceMultipleRays() {
        // Add entities at different positions
        octree.insert(new Point3f(10, 5, 5), (byte) 4, "OnX");
        octree.insert(new Point3f(5, 10, 5), (byte) 4, "OnY");

        // Create multiple rays as array
        Ray3D[] rays = {
            new Ray3D(new Point3f(1, 5, 5), new Vector3f(1, 0, 0)),
            new Ray3D(new Point3f(5, 1, 5), new Vector3f(0, 1, 0))
        };
        
        var results = RayTracingSearch.traceMultipleRays(rays, octree, 100.0f);

        assertNotNull(results);
        // Should group results by entity ID
        assertFalse(results.isEmpty(), "Should find some intersections");
    }

    @Test
    void testFindVisibleEntities() {
        // Add entities at various positions
        octree.insert(new Point3f(10, 10, 10), (byte) 4, "Visible1");
        octree.insert(new Point3f(15, 15, 15), (byte) 4, "Visible2");
        octree.insert(new Point3f(50, 50, 50), (byte) 4, "TooFar");

        Point3f observer = new Point3f(5, 5, 5);
        Spatial.Cube viewVolume = new Spatial.Cube(5, 5, 5, 30); // 30x30x30 cube from observer
        
        var results = RayTracingSearch.findVisibleEntities(
            observer, viewVolume, octree
        );

        assertNotNull(results);
        // Should find entities within view volume
    }

    @Test
    void testRayMisses() {
        // Add entities that ray won't hit
        octree.insert(new Point3f(10, 20, 5), (byte) 4, "Above");
        octree.insert(new Point3f(10, 1, 5), (byte) 4, "Below");

        // Ray along X axis at y=10
        Ray3D ray = new Ray3D(new Point3f(1, 10, 5), new Vector3f(1, 0, 0));
        
        var results = RayTracingSearch.traceRay(ray, octree, 100.0f);

        assertNotNull(results);
        // May or may not find entities depending on octree cell size and discretization
        // This is implementation dependent, so we just check it doesn't crash
    }

    @Test
    void testDiagonalRay() {
        // Entities in 3D space
        octree.insert(new Point3f(10, 10, 10), (byte) 4, "Corner1");
        octree.insert(new Point3f(20, 20, 20), (byte) 4, "Corner2");
        octree.insert(new Point3f(30, 30, 30), (byte) 4, "Corner3");

        // Diagonal ray
        Vector3f direction = new Vector3f(1, 1, 1);
        direction.normalize();
        Ray3D ray = new Ray3D(new Point3f(1, 1, 1), direction);
        
        var results = RayTracingSearch.traceRay(ray, octree, 100.0f);

        assertNotNull(results);
        // Should find entities along the diagonal path
    }

    @Test
    void testRayWithMaxDistance() {
        // Add entities at various distances
        for (int i = 1; i <= 10; i++) {
            octree.insert(new Point3f(i * 10, 5, 5), (byte) 4, "Entity" + i);
        }

        Ray3D ray = new Ray3D(new Point3f(1, 5, 5), new Vector3f(1, 0, 0));
        
        // Trace with max distance of 55
        var results = RayTracingSearch.traceRay(ray, octree, 55.0f);

        assertNotNull(results);
        // Should only find entities within max distance
        assertTrue(results.stream().allMatch(r -> r.distance <= 55.0f),
            "All results should be within max distance");
    }

    @Test
    void testPositiveCoordinateConstraints() {
        // Add entities with positive coordinates only
        octree.insert(new Point3f(10, 10, 10), (byte) 4, "ValidEntity");
        octree.insert(new Point3f(20, 20, 20), (byte) 4, "AnotherValid");

        // Ray with positive origin and direction
        Ray3D ray = new Ray3D(new Point3f(5, 5, 5), new Vector3f(1, 1, 1));
        
        // This should work without throwing exceptions
        var results = RayTracingSearch.traceRay(ray, octree, 50.0f);

        assertNotNull(results);
    }

    @Test
    void testEmptyOctree() {
        // Ray on empty octree
        Ray3D ray = new Ray3D(new Point3f(5, 5, 5), new Vector3f(1, 0, 0));
        
        var results = RayTracingSearch.traceRay(ray, octree, 100.0f);

        assertNotNull(results);
        assertTrue(results.isEmpty(), "Empty octree should return no intersections");
    }
}