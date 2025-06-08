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

import com.hellblazer.luciferase.lucien.entity.Entity;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
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
public class MultiEntityRayTracingSearchTest {

    private OctreeWithEntities<LongEntityID, String> octree;

    @BeforeEach
    void setUp() {
        octree = new OctreeWithEntities<>(new SequentialLongIDGenerator(), 10, (byte) 16);
    }

    @Test
    void testBasicRayIntersection() {
        // Add entities along a ray path
        octree.insert(new Point3f(10, 0, 0), (byte) 4, "Entity1");
        octree.insert(new Point3f(20, 0, 0), (byte) 4, "Entity2");
        octree.insert(new Point3f(30, 0, 0), (byte) 4, "Entity3");
        octree.insert(new Point3f(0, 10, 0), (byte) 4, "OffRay");

        // Ray along positive X axis
        Ray3D ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        
        var results = MultiEntityRayTracingSearch.traceRay(octree, ray);

        assertEquals(3, results.size());
        // Results should be sorted by distance
        assertEquals("Entity1", results.get(0).content);
        assertEquals("Entity2", results.get(1).content);
        assertEquals("Entity3", results.get(2).content);
        assertEquals(10f, results.get(0).distanceAlongRay, 0.001f);
    }

    @Test
    void testRayWithEntityBounds() {
        // Add entities with bounds
        Entity<LongEntityID, String> sphere1 = new Entity<>(
            new LongEntityID(1L), new Point3f(10, 0, 0), "Sphere1",
            new EntityBounds.Sphere(3f)
        );
        Entity<LongEntityID, String> sphere2 = new Entity<>(
            new LongEntityID(2L), new Point3f(20, 0, 0), "Sphere2",
            new EntityBounds.Sphere(5f)
        );

        octree.insertWithBounds(sphere1.position(), (byte) 4, sphere1.id(), 
                               sphere1.content(), sphere1.bounds());
        octree.insertWithBounds(sphere2.position(), (byte) 4, sphere2.id(), 
                               sphere2.content(), sphere2.bounds());

        // Ray that intersects both spheres
        Ray3D ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        
        var results = MultiEntityRayTracingSearch.traceRay(octree, ray);

        assertEquals(2, results.size());
        // First intersection should be with sphere1's near surface
        assertTrue(results.get(0).entryDistance < 10f); // Before center
        assertTrue(results.get(0).exitDistance > 10f);  // After center
    }

    @Test
    void testRayWithMaxDistance() {
        // Add entities at various distances
        for (int i = 1; i <= 10; i++) {
            octree.insert(new Point3f(i * 10, 0, 0), (byte) 4, "Entity" + i);
        }

        Ray3D ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        
        // Trace with max distance of 55
        var results = MultiEntityRayTracingSearch.traceRayWithMaxDistance(octree, ray, 55f);

        assertEquals(5, results.size()); // Should only find first 5 entities
        assertTrue(results.stream().allMatch(r -> r.distanceAlongRay <= 55f));
    }

    @Test
    void testMultipleEntitiesAtSameLocation() {
        // Multiple entities at same position
        Point3f position = new Point3f(10, 0, 0);
        for (int i = 0; i < 5; i++) {
            octree.insert(position, (byte) 4, "Entity" + i);
        }

        Ray3D ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        
        var results = MultiEntityRayTracingSearch.traceRay(octree, ray);

        assertEquals(5, results.size());
        // All should have same distance
        assertTrue(results.stream().allMatch(r -> r.distanceAlongRay == 10f));
    }

    @Test
    void testFindFirstIntersection() {
        // Add entities with different bounds
        Entity<LongEntityID, String> nearSmall = new Entity<>(
            new LongEntityID(1L), new Point3f(20, 0, 0), "NearSmall",
            new EntityBounds.Sphere(1f)
        );
        Entity<LongEntityID, String> farLarge = new Entity<>(
            new LongEntityID(2L), new Point3f(30, 0, 0), "FarLarge",
            new EntityBounds.Sphere(15f) // Large enough to be hit first
        );

        octree.insertWithBounds(nearSmall.position(), (byte) 4, nearSmall.id(), 
                               nearSmall.content(), nearSmall.bounds());
        octree.insertWithBounds(farLarge.position(), (byte) 4, farLarge.id(), 
                               farLarge.content(), farLarge.bounds());

        Ray3D ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        
        var first = MultiEntityRayTracingSearch.findFirstIntersection(octree, ray);

        assertNotNull(first);
        assertEquals("FarLarge", first.content); // Large sphere is hit first
        assertTrue(first.entryDistance < 20f); // Before the small sphere
    }

    @Test
    void testDiagonalRay() {
        // Entities in 3D grid
        octree.insert(new Point3f(10, 10, 10), (byte) 4, "Corner1");
        octree.insert(new Point3f(20, 20, 20), (byte) 4, "Corner2");
        octree.insert(new Point3f(30, 30, 30), (byte) 4, "Corner3");
        octree.insert(new Point3f(10, 20, 30), (byte) 4, "OffDiagonal");

        // Diagonal ray
        Vector3f direction = new Vector3f(1, 1, 1);
        direction.normalize();
        Ray3D ray = new Ray3D(new Point3f(0, 0, 0), direction);
        
        var results = MultiEntityRayTracingSearch.traceRay(octree, ray);

        assertEquals(3, results.size());
        // Should hit the three corners on the diagonal
        assertTrue(results.stream().anyMatch(r -> r.content.equals("Corner1")));
        assertTrue(results.stream().anyMatch(r -> r.content.equals("Corner2")));
        assertTrue(results.stream().anyMatch(r -> r.content.equals("Corner3")));
    }

    @Test
    void testRayOcclusion() {
        // Test occlusion with opaque entities
        Entity<LongEntityID, String> opaque = new Entity<>(
            new LongEntityID(1L), new Point3f(10, 0, 0), "Opaque",
            new EntityBounds.Sphere(5f)
        );
        Entity<LongEntityID, String> behind = new Entity<>(
            new LongEntityID(2L), new Point3f(20, 0, 0), "Behind",
            new EntityBounds.Sphere(2f)
        );

        octree.insertWithBounds(opaque.position(), (byte) 4, opaque.id(), 
                               opaque.content(), opaque.bounds());
        octree.insertWithBounds(behind.position(), (byte) 4, behind.id(), 
                               behind.content(), behind.bounds());

        Ray3D ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        
        // Find all intersections
        var allResults = MultiEntityRayTracingSearch.traceRay(octree, ray);
        assertEquals(2, allResults.size());

        // Find visible intersections (assuming first entity is opaque)
        var visibleResults = MultiEntityRayTracingSearch.traceRayWithOcclusion(
            octree, ray, id -> id.equals(opaque.id())
        );
        
        assertEquals(1, visibleResults.size());
        assertEquals("Opaque", visibleResults.get(0).content);
    }

    @Test
    void testRayMisses() {
        // Add entities that ray won't hit
        octree.insert(new Point3f(10, 10, 0), (byte) 4, "Above");
        octree.insert(new Point3f(10, -10, 0), (byte) 4, "Below");

        // Ray along X axis at y=0
        Ray3D ray = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        
        var results = MultiEntityRayTracingSearch.traceRay(octree, ray);

        assertTrue(results.isEmpty());
    }

    @Test
    void testComplexEntityBounds() {
        // Test with box bounds
        Entity<LongEntityID, String> box = new Entity<>(
            new LongEntityID(1L), new Point3f(10, 0, 0), "Box",
            new EntityBounds.Box(new Point3f(4, 2, 3))
        );

        octree.insertWithBounds(box.position(), (byte) 4, box.id(), 
                               box.content(), box.bounds());

        // Ray that grazes the edge of the box
        Ray3D ray = new Ray3D(new Point3f(0, 1.9f, 0), new Vector3f(1, 0, 0));
        
        var results = MultiEntityRayTracingSearch.traceRay(octree, ray);

        assertEquals(1, results.size());
        assertEquals("Box", results.get(0).content);
        assertTrue(results.get(0).grazing); // Should be marked as grazing
    }
}