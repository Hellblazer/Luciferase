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
 * Tests for multi-entity plane intersection search functionality
 *
 * @author hal.hildebrand
 */
public class MultiEntityPlaneIntersectionSearchTest {

    private OctreeWithEntities<LongEntityID, String> octree;

    @BeforeEach
    void setUp() {
        octree = new OctreeWithEntities<>(new SequentialLongIDGenerator(), 10, (byte) 16);
    }

    @Test
    void testBasicPlaneIntersection() {
        // Add entities on both sides of a plane
        LongEntityID id1 = octree.insert(new Point3f(5, 5, 5), (byte) 4, "Above");
        LongEntityID id2 = octree.insert(new Point3f(5, -5, 5), (byte) 4, "Below");
        LongEntityID id3 = octree.insert(new Point3f(5, 0, 5), (byte) 4, "OnPlane");

        // XZ plane at y=0
        Plane3D plane = new Plane3D(new Point3f(0, 0, 0), new Vector3f(0, 1, 0));
        
        var results = MultiEntityPlaneIntersectionSearch.findIntersectingEntities(octree, plane);

        // Should find entity on the plane
        assertTrue(results.stream().anyMatch(r -> r.content.equals("OnPlane")));
        assertTrue(results.stream().anyMatch(r -> Math.abs(r.distanceToPlane) < 0.001f));
    }

    @Test
    void testEntityBoundsIntersection() {
        // Add entities with bounds that intersect plane
        Entity<LongEntityID, String> sphere = new Entity<>(
            new LongEntityID(1L), new Point3f(10, 2, 10), "Sphere",
            new EntityBounds.Sphere(3f) // Radius 3, so intersects y=0 plane
        );
        Entity<LongEntityID, String> box = new Entity<>(
            new LongEntityID(2L), new Point3f(20, 5, 20), "Box",
            new EntityBounds.Box(new Point3f(2, 6, 2)) // Height 6, so extends below y=0
        );

        octree.insertWithBounds(sphere.position(), (byte) 4, sphere.id(), 
                               sphere.content(), sphere.bounds());
        octree.insertWithBounds(box.position(), (byte) 4, box.id(), 
                               box.content(), box.bounds());

        // XZ plane at y=0
        Plane3D plane = new Plane3D(new Point3f(0, 0, 0), new Vector3f(0, 1, 0));
        
        var results = MultiEntityPlaneIntersectionSearch.findIntersectingEntities(octree, plane);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> 
            r.intersectionType == MultiEntityPlaneIntersectionSearch.IntersectionType.BOUNDS_INTERSECT));
    }

    @Test
    void testMultipleEntitiesAtSameLocation() {
        // Multiple entities at same position
        Point3f position = new Point3f(15, 15, 15);
        for (int i = 0; i < 5; i++) {
            octree.insert(position, (byte) 4, "Entity" + i);
        }

        // Diagonal plane passing through the position
        Plane3D plane = new Plane3D(position, new Vector3f(1, 1, 1).normalize());
        
        var results = MultiEntityPlaneIntersectionSearch.findIntersectingEntities(octree, plane);

        assertEquals(5, results.size());
        assertTrue(results.stream().allMatch(r -> r.distanceToPlane == 0));
    }

    @Test
    void testFindEntitiesOnSide() {
        // Add entities on different sides of plane
        octree.insert(new Point3f(5, 10, 5), (byte) 4, "Positive1");
        octree.insert(new Point3f(10, 20, 10), (byte) 4, "Positive2");
        octree.insert(new Point3f(15, -5, 15), (byte) 4, "Negative1");
        octree.insert(new Point3f(20, -10, 20), (byte) 4, "Negative2");

        // XZ plane at y=0
        Plane3D plane = new Plane3D(new Point3f(0, 0, 0), new Vector3f(0, 1, 0));
        
        // Find entities on positive side
        var positiveSide = MultiEntityPlaneIntersectionSearch.findEntitiesOnSide(
            octree, plane, true
        );
        assertEquals(2, positiveSide.size());
        assertTrue(positiveSide.stream().allMatch(r -> r.content.startsWith("Positive")));

        // Find entities on negative side
        var negativeSide = MultiEntityPlaneIntersectionSearch.findEntitiesOnSide(
            octree, plane, false
        );
        assertEquals(2, negativeSide.size());
        assertTrue(negativeSide.stream().allMatch(r -> r.content.startsWith("Negative")));
    }

    @Test
    void testThickPlaneIntersection() {
        // Test plane with thickness
        octree.insert(new Point3f(0, 0.5f, 0), (byte) 4, "NearPlane1");
        octree.insert(new Point3f(5, -0.5f, 5), (byte) 4, "NearPlane2");
        octree.insert(new Point3f(10, 5, 10), (byte) 4, "FarFromPlane");

        Plane3D plane = new Plane3D(new Point3f(0, 0, 0), new Vector3f(0, 1, 0));
        
        // Find entities within thickness of 1.0
        var results = MultiEntityPlaneIntersectionSearch.findIntersectingEntitiesWithThickness(
            octree, plane, 1.0f
        );

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.content.equals("NearPlane1")));
        assertTrue(results.stream().anyMatch(r -> r.content.equals("NearPlane2")));
    }

    @Test
    void testArbitraryPlaneOrientation() {
        // Add entities in 3D space
        List<Point3f> positions = List.of(
            new Point3f(10, 10, 10),
            new Point3f(20, 20, 20),
            new Point3f(30, 30, 30),
            new Point3f(15, 25, 5),
            new Point3f(25, 15, 35)
        );

        for (int i = 0; i < positions.size(); i++) {
            octree.insert(positions.get(i), (byte) 4, "Entity" + i);
        }

        // Plane with arbitrary orientation
        Vector3f normal = new Vector3f(1, 2, -1);
        normal.normalize();
        Plane3D plane = new Plane3D(new Point3f(20, 20, 20), normal);
        
        var results = MultiEntityPlaneIntersectionSearch.findIntersectingEntities(octree, plane);

        // Verify plane equation for results
        for (var result : results) {
            float distance = plane.distanceToPoint(result.position);
            assertEquals(distance, result.distanceToPlane, 0.001f);
        }
    }

    @Test
    void testClipEntitiesByPlane() {
        // Add entities with bounds that are clipped by plane
        Entity<LongEntityID, String> largeSphere = new Entity<>(
            new LongEntityID(1L), new Point3f(0, 0, 0), "LargeSphere",
            new EntityBounds.Sphere(10f)
        );
        
        octree.insertWithBounds(largeSphere.position(), (byte) 4, largeSphere.id(), 
                               largeSphere.content(), largeSphere.bounds());

        // Vertical plane that cuts through the sphere
        Plane3D plane = new Plane3D(new Point3f(5, 0, 0), new Vector3f(1, 0, 0));
        
        var results = MultiEntityPlaneIntersectionSearch.clipEntitiesByPlane(
            octree, plane
        );

        assertEquals(1, results.size());
        var clipped = results.get(0);
        assertTrue(clipped.volumeOnPositiveSide > 0);
        assertTrue(clipped.volumeOnNegativeSide > 0);
        assertEquals(1.0f, clipped.volumeOnPositiveSide + clipped.volumeOnNegativeSide, 0.01f);
    }
}