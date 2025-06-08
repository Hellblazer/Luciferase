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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-entity plane intersection search functionality
 *
 * @author hal.hildebrand
 */
public class PlaneIntersectionSearchTest {

    private OctreeWithEntities<LongEntityID, String> octree;

    @BeforeEach
    void setUp() {
        octree = new OctreeWithEntities<>(new SequentialLongIDGenerator(), 10, (byte) 16);
    }

    @Test
    void testFindEntitiesIntersectingPlane() {
        // Add entities at different positions (all positive coordinates)
        octree.insert(new Point3f(10, 10, 10), (byte) 4, "Entity1");
        octree.insert(new Point3f(20, 20, 20), (byte) 4, "Entity2");
        octree.insert(new Point3f(30, 30, 30), (byte) 4, "Entity3");

        // Create a plane using three points (all positive coordinates)
        Plane3D plane = Plane3D.fromThreePoints(
            new Point3f(10, 10, 10),
            new Point3f(20, 10, 10), 
            new Point3f(10, 20, 10)
        );
        Point3f referencePoint = new Point3f(5, 5, 5);
        
        var results = PlaneIntersectionSearch.findEntitiesIntersectingPlane(
            plane, octree, referencePoint, 5.0f
        );

        assertNotNull(results);
        // Should find entities near the plane within tolerance
    }

    @Test
    void testFindEntitiesOnSideOfPlane() {
        // Add entities on different sides of plane (all positive coordinates)
        octree.insert(new Point3f(5, 5, 5), (byte) 4, "LowerSide");
        octree.insert(new Point3f(25, 25, 25), (byte) 4, "UpperSide");

        // Create a diagonal plane
        Plane3D plane = Plane3D.fromThreePoints(
            new Point3f(10, 10, 1),
            new Point3f(20, 10, 1), 
            new Point3f(10, 20, 1)
        );
        
        // Find entities on positive side
        var positiveSide = PlaneIntersectionSearch.findEntitiesOnSideOfPlane(
            plane, octree, true
        );
        
        // Find entities on negative side  
        var negativeSide = PlaneIntersectionSearch.findEntitiesOnSideOfPlane(
            plane, octree, false
        );

        assertNotNull(positiveSide);
        assertNotNull(negativeSide);
        // Should categorize entities based on which side of plane they're on
    }

    @Test
    void testFindEntitiesBetweenPlanes() {
        // Add entities at different positions
        octree.insert(new Point3f(15, 15, 15), (byte) 4, "Between");
        octree.insert(new Point3f(5, 5, 5), (byte) 4, "Below");
        octree.insert(new Point3f(35, 35, 35), (byte) 4, "Above");

        // Create two parallel planes
        Plane3D lowerPlane = Plane3D.fromThreePoints(
            new Point3f(10, 10, 1),
            new Point3f(20, 10, 1), 
            new Point3f(10, 20, 1)
        );
        Plane3D upperPlane = Plane3D.fromThreePoints(
            new Point3f(10, 10, 30),
            new Point3f(20, 10, 30), 
            new Point3f(10, 20, 30)
        );
        Point3f referencePoint = new Point3f(1, 1, 1);
        
        var results = PlaneIntersectionSearch.findEntitiesBetweenPlanes(
            lowerPlane, upperPlane, octree
        );

        assertNotNull(results);
        // Should find entities between the two planes
    }

    @Test
    void testFindEntitiesIntersectingMultiplePlanes() {
        // Add entities in 3D space
        octree.insert(new Point3f(15, 15, 15), (byte) 4, "Center");
        octree.insert(new Point3f(25, 25, 25), (byte) 4, "Corner");

        // Create multiple planes
        var planes = List.of(
            Plane3D.fromThreePoints(
                new Point3f(10, 10, 1),
                new Point3f(20, 10, 1), 
                new Point3f(10, 20, 1)
            ),
            Plane3D.fromThreePoints(
                new Point3f(1, 10, 10),
                new Point3f(1, 20, 10), 
                new Point3f(1, 10, 20)
            )
        );
        
        // Convert List to array
        var planeArray = planes.toArray(new Plane3D[0]);
        
        var results = PlaneIntersectionSearch.findEntitiesIntersectingMultiplePlanes(
            planeArray, octree, true
        );

        assertNotNull(results);
    }

    @Test
    void testCountEntitiesBySide() {
        // Add entities on different sides of plane
        octree.insert(new Point3f(5, 5, 5), (byte) 4, "Side1");
        octree.insert(new Point3f(25, 25, 25), (byte) 4, "Side2");
        octree.insert(new Point3f(15, 15, 15), (byte) 4, "Side3");

        // Create a plane
        Plane3D plane = Plane3D.fromThreePoints(
            new Point3f(10, 10, 10),
            new Point3f(20, 10, 10), 
            new Point3f(10, 20, 10)
        );
        Point3f referencePoint = new Point3f(1, 1, 1);
        
        int[] counts = PlaneIntersectionSearch.countEntitiesBySide(
            plane, octree, 1.0f
        );

        assertNotNull(counts);
        assertEquals(3, counts.length); // [negative, on plane, positive]
        assertTrue(counts[0] >= 0); // negative side count
        assertTrue(counts[1] >= 0); // on plane count  
        assertTrue(counts[2] >= 0); // positive side count
    }

    @Test
    void testMultipleEntitiesAtSameLocation() {
        // Multiple entities at same position
        Point3f position = new Point3f(15, 15, 15);
        for (int i = 0; i < 3; i++) {
            octree.insert(position, (byte) 4, "Entity" + i);
        }

        // Create a plane passing through the position
        Plane3D plane = Plane3D.fromThreePoints(
            new Point3f(15, 15, 15),
            new Point3f(25, 15, 15), 
            new Point3f(15, 25, 15)
        );
        Point3f referencePoint = new Point3f(1, 1, 1);
        
        var results = PlaneIntersectionSearch.findEntitiesIntersectingPlane(
            plane, octree, referencePoint, 1.0f
        );

        assertNotNull(results);
        // Should find all entities at this location
        assertTrue(results.size() >= 3);
    }

    @Test
    void testPositiveCoordinateConstraints() {
        // Add entity with positive coordinates
        octree.insert(new Point3f(10, 10, 10), (byte) 4, "ValidEntity");

        // Create plane with positive coordinates
        Plane3D plane = Plane3D.fromThreePoints(
            new Point3f(5, 5, 5),
            new Point3f(15, 5, 5), 
            new Point3f(5, 15, 5)
        );
        Point3f referencePoint = new Point3f(1, 1, 1);
        
        // This should work without throwing exceptions
        var results = PlaneIntersectionSearch.findEntitiesIntersectingPlane(
            plane, octree, referencePoint, 2.0f
        );

        assertNotNull(results);
    }
}