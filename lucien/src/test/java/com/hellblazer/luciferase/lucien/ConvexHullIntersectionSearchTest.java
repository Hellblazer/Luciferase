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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for multi-entity convex hull intersection search functionality
 *
 * @author hal.hildebrand
 */
public class ConvexHullIntersectionSearchTest {

    private final byte testLevel = 15;
    private OctreeWithEntities<LongEntityID, String> multiEntityOctree;

    @BeforeEach
    void setUp() {
        // Create test data with entities positioned for convex hull testing
        List<EntityTestUtils.MultiEntityLocation<String>> locations = new ArrayList<>();
        
        // Entities inside a test convex hull (200-400 cube)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(300.0f, 300.0f, 300.0f), // Center
            testLevel,
            "InsideEntity1", "InsideEntity2"
        ));
        
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(250.0f, 250.0f, 250.0f), // Inside corner
            testLevel,
            "InsideEntity3", "InsideEntity4"
        ));
        
        // Entities on the boundary
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(200.0f, 300.0f, 300.0f), // On face
            testLevel,
            "BoundaryEntity1", "BoundaryEntity2"
        ));
        
        // Entities partially intersecting
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(195.0f, 300.0f, 300.0f), // Slightly outside
            testLevel,
            "IntersectingEntity1", "IntersectingEntity2"
        ));
        
        // Entities outside
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(100.0f, 100.0f, 100.0f),
            testLevel,
            "OutsideEntity1", "OutsideEntity2"
        ));
        
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(500.0f, 500.0f, 500.0f),
            testLevel,
            "FarEntity"
        ));
        
        multiEntityOctree = EntityTestUtils.createMultiEntityOctree(locations);
    }

    @Test
    void testConvexHullFromVertices() {
        // Create a tetrahedron convex hull
        List<Point3f> vertices = new ArrayList<>();
        vertices.add(new Point3f(200.0f, 200.0f, 200.0f));
        vertices.add(new Point3f(400.0f, 200.0f, 200.0f));
        vertices.add(new Point3f(300.0f, 400.0f, 200.0f));
        vertices.add(new Point3f(300.0f, 300.0f, 400.0f));
        
        ConvexHullIntersectionSearch.ConvexHull hull = 
            ConvexHullIntersectionSearch.ConvexHull.fromVertices(vertices);
        
        assertNotNull(hull);
        assertNotNull(hull.planes);
        assertTrue(hull.planes.size() > 0);
        assertNotNull(hull.centroid);
        assertTrue(hull.boundingRadius > 0);
        
        // Test point containment
        Point3f insidePoint = new Point3f(300.0f, 300.0f, 300.0f);
        assertTrue(hull.containsPoint(insidePoint));
        
        Point3f outsidePoint = new Point3f(100.0f, 100.0f, 100.0f);
        assertFalse(hull.containsPoint(outsidePoint));
    }

    @Test
    void testOrientedBoundingBox() {
        Point3f center = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f[] axes = {
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f)
        };
        float[] extents = { 100.0f, 100.0f, 100.0f };
        
        ConvexHullIntersectionSearch.ConvexHull hull = 
            ConvexHullIntersectionSearch.ConvexHull.createOrientedBoundingBox(center, axes, extents);
        
        assertNotNull(hull);
        assertEquals(6, hull.planes.size()); // 6 planes for a box
        
        // Test containment
        assertTrue(hull.containsPoint(center));
        assertTrue(hull.containsPoint(new Point3f(250.0f, 250.0f, 250.0f)));
        assertFalse(hull.containsPoint(new Point3f(100.0f, 100.0f, 100.0f)));
    }

    @Test
    void testFindEntitiesIntersectingConvexHull() {
        // Create a box convex hull from 200 to 400
        List<Point3f> vertices = new ArrayList<>();
        vertices.add(new Point3f(200.0f, 200.0f, 200.0f));
        vertices.add(new Point3f(400.0f, 200.0f, 200.0f));
        vertices.add(new Point3f(200.0f, 400.0f, 200.0f));
        vertices.add(new Point3f(200.0f, 200.0f, 400.0f));
        vertices.add(new Point3f(400.0f, 400.0f, 200.0f));
        vertices.add(new Point3f(400.0f, 200.0f, 400.0f));
        vertices.add(new Point3f(200.0f, 400.0f, 400.0f));
        vertices.add(new Point3f(400.0f, 400.0f, 400.0f));
        
        ConvexHullIntersectionSearch.ConvexHull hull = 
            ConvexHullIntersectionSearch.ConvexHull.fromVertices(vertices);
        
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        List<ConvexHullIntersectionSearch.EntityConvexHullIntersection<LongEntityID, String>> results =
            ConvexHullIntersectionSearch.findEntitiesIntersectingConvexHull(
                hull, multiEntityOctree, referencePoint
            );
        
        // Should find entities inside and on boundary
        assertTrue(results.size() > 0);
        
        // Should include inside entities
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("InsideEntity")));
        
        // Should include boundary entities
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("BoundaryEntity")));
        
        // Should NOT include outside entities
        assertFalse(results.stream().anyMatch(r -> r.content.startsWith("OutsideEntity")));
        assertFalse(results.stream().anyMatch(r -> r.content.equals("FarEntity")));
        
        // Should be sorted by distance from reference
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToReferencePoint <= results.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testFindEntitiesInsideConvexHull() {
        // Create a simple convex hull
        Point3f center = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f[] axes = {
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f)
        };
        float[] extents = { 100.0f, 100.0f, 100.0f };
        
        ConvexHullIntersectionSearch.ConvexHull hull = 
            ConvexHullIntersectionSearch.ConvexHull.createOrientedBoundingBox(center, axes, extents);
        
        List<ConvexHullIntersectionSearch.EntityConvexHullIntersection<LongEntityID, String>> results =
            ConvexHullIntersectionSearch.findEntitiesInsideConvexHull(hull, multiEntityOctree);
        
        // Should only find entities completely inside
        assertTrue(results.size() > 0);
        assertTrue(results.stream().allMatch(r -> 
            r.intersectionType == ConvexHullIntersectionSearch.IntersectionType.COMPLETELY_INSIDE
        ));
        
        // Should include inside entities
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("InsideEntity")));
        
        // Should NOT include boundary or outside entities
        assertFalse(results.stream().anyMatch(r -> r.content.startsWith("BoundaryEntity")));
        assertFalse(results.stream().anyMatch(r -> r.content.startsWith("OutsideEntity")));
    }

    @Test
    void testCountEntitiesByConvexHullIntersection() {
        // Create a convex hull
        List<Point3f> vertices = new ArrayList<>();
        vertices.add(new Point3f(200.0f, 200.0f, 200.0f));
        vertices.add(new Point3f(400.0f, 200.0f, 200.0f));
        vertices.add(new Point3f(200.0f, 400.0f, 200.0f));
        vertices.add(new Point3f(200.0f, 200.0f, 400.0f));
        
        ConvexHullIntersectionSearch.ConvexHull hull = 
            ConvexHullIntersectionSearch.ConvexHull.fromVertices(vertices);
        
        int[] counts = ConvexHullIntersectionSearch.countEntitiesByConvexHullIntersection(
            hull, multiEntityOctree
        );
        
        int inside = counts[0];
        int intersecting = counts[1];
        int outside = counts[2];
        
        // Should have entities in each category
        assertTrue(inside > 0);
        assertTrue(outside > 0);
        
        // Total should match entity count
        assertEquals(multiEntityOctree.getStats().entityCount, inside + intersecting + outside);
    }

    @Test
    void testFindEntitiesIntersectingMultipleConvexHulls() {
        // Create two overlapping convex hulls
        List<ConvexHullIntersectionSearch.ConvexHull> hulls = new ArrayList<>();
        
        // First hull: 200-350
        List<Point3f> vertices1 = new ArrayList<>();
        vertices1.add(new Point3f(200.0f, 200.0f, 200.0f));
        vertices1.add(new Point3f(350.0f, 200.0f, 200.0f));
        vertices1.add(new Point3f(200.0f, 350.0f, 200.0f));
        vertices1.add(new Point3f(200.0f, 200.0f, 350.0f));
        hulls.add(ConvexHullIntersectionSearch.ConvexHull.fromVertices(vertices1));
        
        // Second hull: 250-400
        List<Point3f> vertices2 = new ArrayList<>();
        vertices2.add(new Point3f(250.0f, 250.0f, 250.0f));
        vertices2.add(new Point3f(400.0f, 250.0f, 250.0f));
        vertices2.add(new Point3f(250.0f, 400.0f, 250.0f));
        vertices2.add(new Point3f(250.0f, 250.0f, 400.0f));
        hulls.add(ConvexHullIntersectionSearch.ConvexHull.fromVertices(vertices2));
        
        // Test requiring all hulls (intersection)
        List<ConvexHullIntersectionSearch.EntityConvexHullIntersection<LongEntityID, String>> allResults =
            ConvexHullIntersectionSearch.findEntitiesIntersectingMultipleConvexHulls(
                hulls, multiEntityOctree, true
            );
        
        // Only entities in the intersection of both hulls (250-350 range)
        assertTrue(allResults.size() > 0);
        
        // Test requiring any hull (union)
        List<ConvexHullIntersectionSearch.EntityConvexHullIntersection<LongEntityID, String>> anyResults =
            ConvexHullIntersectionSearch.findEntitiesIntersectingMultipleConvexHulls(
                hulls, multiEntityOctree, false
            );
        
        // Should have more entities (union is larger than intersection)
        assertTrue(anyResults.size() >= allResults.size());
    }

    @Test
    void testConvexHullIntersectionStatistics() {
        // Create a convex hull
        Point3f center = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f[] axes = {
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f)
        };
        float[] extents = { 100.0f, 100.0f, 100.0f };
        
        ConvexHullIntersectionSearch.ConvexHull hull = 
            ConvexHullIntersectionSearch.ConvexHull.createOrientedBoundingBox(center, axes, extents);
        
        ConvexHullIntersectionSearch.ConvexHullIntersectionStatistics stats =
            ConvexHullIntersectionSearch.getConvexHullIntersectionStatistics(hull, multiEntityOctree);
        
        // Verify total entity count
        assertEquals(multiEntityOctree.getStats().entityCount, stats.totalEntities);
        
        // Should have entities in different categories
        assertTrue(stats.insideEntities > 0);
        assertTrue(stats.outsideEntities > 0);
        
        // Verify percentages
        assertTrue(stats.getInsidePercentage() >= 0 && stats.getInsidePercentage() <= 100);
        assertTrue(stats.getIntersectingPercentage() >= 0 && stats.getIntersectingPercentage() <= 100);
        assertTrue(stats.getOutsidePercentage() >= 0 && stats.getOutsidePercentage() <= 100);
        
        // Average penetration depth should be non-negative
        assertTrue(stats.averagePenetrationDepth >= 0);
    }

    @Test
    void testBatchConvexHullIntersections() {
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        List<ConvexHullIntersectionSearch.ConvexHull> hulls = new ArrayList<>();
        
        // Small hull
        List<Point3f> vertices1 = new ArrayList<>();
        vertices1.add(new Point3f(250.0f, 250.0f, 250.0f));
        vertices1.add(new Point3f(350.0f, 250.0f, 250.0f));
        vertices1.add(new Point3f(250.0f, 350.0f, 250.0f));
        vertices1.add(new Point3f(250.0f, 250.0f, 350.0f));
        hulls.add(ConvexHullIntersectionSearch.ConvexHull.fromVertices(vertices1));
        
        // Large hull
        List<Point3f> vertices2 = new ArrayList<>();
        vertices2.add(new Point3f(100.0f, 100.0f, 100.0f));
        vertices2.add(new Point3f(500.0f, 100.0f, 100.0f));
        vertices2.add(new Point3f(100.0f, 500.0f, 100.0f));
        vertices2.add(new Point3f(100.0f, 100.0f, 500.0f));
        hulls.add(ConvexHullIntersectionSearch.ConvexHull.fromVertices(vertices2));
        
        Map<ConvexHullIntersectionSearch.ConvexHull, 
            List<ConvexHullIntersectionSearch.EntityConvexHullIntersection<LongEntityID, String>>> results =
            ConvexHullIntersectionSearch.batchConvexHullIntersections(hulls, multiEntityOctree, referencePoint);
        
        assertEquals(2, results.size());
        
        // Small hull should have fewer intersections
        List<ConvexHullIntersectionSearch.EntityConvexHullIntersection<LongEntityID, String>> smallResults = 
            results.get(hulls.get(0));
        List<ConvexHullIntersectionSearch.EntityConvexHullIntersection<LongEntityID, String>> largeResults = 
            results.get(hulls.get(1));
        
        assertTrue(smallResults.size() <= largeResults.size());
    }

    @Test
    void testPenetrationDepth() {
        // Create a convex hull
        Point3f center = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f[] axes = {
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f)
        };
        float[] extents = { 100.0f, 100.0f, 100.0f };
        
        ConvexHullIntersectionSearch.ConvexHull hull = 
            ConvexHullIntersectionSearch.ConvexHull.createOrientedBoundingBox(center, axes, extents);
        
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        List<ConvexHullIntersectionSearch.EntityConvexHullIntersection<LongEntityID, String>> results =
            ConvexHullIntersectionSearch.findEntitiesIntersectingConvexHull(
                hull, multiEntityOctree, referencePoint
            );
        
        // Entities inside should have positive penetration depth
        for (var result : results) {
            if (result.intersectionType == ConvexHullIntersectionSearch.IntersectionType.COMPLETELY_INSIDE) {
                assertTrue(result.penetrationDepth > 0);
            }
        }
    }

    @Test
    void testNegativeCoordinatesThrowsException() {
        // Test negative vertices
        List<Point3f> invalidVertices = new ArrayList<>();
        invalidVertices.add(new Point3f(-10.0f, 10.0f, 10.0f));
        invalidVertices.add(new Point3f(10.0f, 10.0f, 10.0f));
        invalidVertices.add(new Point3f(10.0f, 20.0f, 10.0f));
        invalidVertices.add(new Point3f(10.0f, 10.0f, 20.0f));
        
        assertThrows(IllegalArgumentException.class, () -> {
            ConvexHullIntersectionSearch.ConvexHull.fromVertices(invalidVertices);
        });
        
        // Test negative center for oriented bounding box
        Point3f invalidCenter = new Point3f(-10.0f, 10.0f, 10.0f);
        Vector3f[] axes = {
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f)
        };
        float[] extents = { 10.0f, 10.0f, 10.0f };
        
        assertThrows(IllegalArgumentException.class, () -> {
            ConvexHullIntersectionSearch.ConvexHull.createOrientedBoundingBox(invalidCenter, axes, extents);
        });
        
        // Test negative reference point
        List<Point3f> validVertices = new ArrayList<>();
        validVertices.add(new Point3f(10.0f, 10.0f, 10.0f));
        validVertices.add(new Point3f(20.0f, 10.0f, 10.0f));
        validVertices.add(new Point3f(10.0f, 20.0f, 10.0f));
        validVertices.add(new Point3f(10.0f, 10.0f, 20.0f));
        
        ConvexHullIntersectionSearch.ConvexHull validHull = 
            ConvexHullIntersectionSearch.ConvexHull.fromVertices(validVertices);
        Point3f invalidRef = new Point3f(-10.0f, 10.0f, 10.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            ConvexHullIntersectionSearch.findEntitiesIntersectingConvexHull(
                validHull, multiEntityOctree, invalidRef
            );
        });
    }

    @Test
    void testEmptyOctree() {
        OctreeWithEntities<LongEntityID, String> emptyOctree = 
            EntityTestUtils.createMultiEntityOctree(new ArrayList<>());
        
        List<Point3f> vertices = new ArrayList<>();
        vertices.add(new Point3f(10.0f, 10.0f, 10.0f));
        vertices.add(new Point3f(20.0f, 10.0f, 10.0f));
        vertices.add(new Point3f(10.0f, 20.0f, 10.0f));
        vertices.add(new Point3f(10.0f, 10.0f, 20.0f));
        
        ConvexHullIntersectionSearch.ConvexHull hull = 
            ConvexHullIntersectionSearch.ConvexHull.fromVertices(vertices);
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        List<ConvexHullIntersectionSearch.EntityConvexHullIntersection<LongEntityID, String>> results =
            ConvexHullIntersectionSearch.findEntitiesIntersectingConvexHull(
                hull, emptyOctree, referencePoint
            );
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testInvalidConvexHullCreation() {
        // Test with insufficient vertices
        List<Point3f> insufficientVertices = new ArrayList<>();
        insufficientVertices.add(new Point3f(10.0f, 10.0f, 10.0f));
        insufficientVertices.add(new Point3f(20.0f, 10.0f, 10.0f));
        
        assertThrows(IllegalArgumentException.class, () -> {
            ConvexHullIntersectionSearch.ConvexHull.fromVertices(insufficientVertices);
        });
        
        // Test with null vertices
        assertThrows(IllegalArgumentException.class, () -> {
            ConvexHullIntersectionSearch.ConvexHull.fromVertices(null);
        });
        
        // Test with invalid extents
        Point3f center = new Point3f(100.0f, 100.0f, 100.0f);
        Vector3f[] axes = {
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f)
        };
        float[] invalidExtents = { -10.0f, 10.0f, 10.0f };
        
        assertThrows(IllegalArgumentException.class, () -> {
            ConvexHullIntersectionSearch.ConvexHull.createOrientedBoundingBox(center, axes, invalidExtents);
        });
    }

    @Test
    void testDistanceToConvexHull() {
        // Create a simple convex hull
        Point3f center = new Point3f(300.0f, 300.0f, 300.0f);
        Vector3f[] axes = {
            new Vector3f(1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f)
        };
        float[] extents = { 100.0f, 100.0f, 100.0f };
        
        ConvexHullIntersectionSearch.ConvexHull hull = 
            ConvexHullIntersectionSearch.ConvexHull.createOrientedBoundingBox(center, axes, extents);
        
        // Test point inside
        Point3f insidePoint = new Point3f(300.0f, 300.0f, 300.0f);
        float insideDistance = hull.distanceToPoint(insidePoint);
        assertTrue(insideDistance < 0); // Negative for inside
        
        // Test point outside
        Point3f outsidePoint = new Point3f(500.0f, 300.0f, 300.0f);
        float outsideDistance = hull.distanceToPoint(outsidePoint);
        assertTrue(outsideDistance > 0); // Positive for outside
    }
}