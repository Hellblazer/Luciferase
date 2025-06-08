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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for multi-entity AABB intersection search functionality
 *
 * @author hal.hildebrand
 */
public class AABBIntersectionSearchTest {

    private final byte testLevel = 15;
    private OctreeWithEntities<LongEntityID, String> multiEntityOctree;

    @BeforeEach
    void setUp() {
        // Create test data with entities at various positions relative to an AABB
        List<EntityTestUtils.MultiEntityLocation<String>> locations = new ArrayList<>();
        
        // Entities at AABB center (300, 300, 300)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(300.0f, 300.0f, 300.0f),
            testLevel,
            "CenterEntity1", "CenterEntity2"
        ));
        
        // Entities inside AABB (200-400 range)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(250.0f, 250.0f, 250.0f),
            testLevel,
            "InsideEntity1", "InsideEntity2", "InsideEntity3"
        ));
        
        // Entities on AABB boundary
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(200.0f, 300.0f, 300.0f), // On min X face
            testLevel,
            "BoundaryEntity1", "BoundaryEntity2"
        ));
        
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(400.0f, 300.0f, 300.0f), // On max X face
            testLevel,
            "BoundaryEntity3"
        ));
        
        // Entities outside AABB
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(150.0f, 150.0f, 150.0f),
            testLevel,
            "OutsideEntity1", "OutsideEntity2"
        ));
        
        // Entities far outside
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(500.0f, 500.0f, 500.0f),
            testLevel,
            "FarEntity"
        ));
        
        multiEntityOctree = EntityTestUtils.createMultiEntityOctree(locations);
    }

    @Test
    void testFindEntitiesIntersectingAABB() {
        AABBIntersectionSearch.AABB aabb = 
            new AABBIntersectionSearch.AABB(200.0f, 200.0f, 200.0f, 400.0f, 400.0f, 400.0f);
        Point3f referencePoint = new Point3f(100.0f, 100.0f, 100.0f);
        float boundaryTolerance = 1.0f;
        
        List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>> results =
            AABBIntersectionSearch.findEntitiesIntersectingAABB(
                aabb, multiEntityOctree, referencePoint, boundaryTolerance
            );
        
        // Should find center (2), inside (3), and boundary (3) entities = 8 total
        assertEquals(8, results.size());
        
        // Verify center entities are included
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("CenterEntity")));
        
        // Verify inside entities are included
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("InsideEntity")));
        
        // Verify boundary entities are included
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("BoundaryEntity")));
        
        // Verify outside entities are NOT included
        assertFalse(results.stream().anyMatch(r -> r.content.startsWith("OutsideEntity")));
        assertFalse(results.stream().anyMatch(r -> r.content.equals("FarEntity")));
        
        // Verify sorting by distance from reference point
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToReferencePoint <= results.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testFindEntitiesInsideAABB() {
        AABBIntersectionSearch.AABB aabb = 
            new AABBIntersectionSearch.AABB(200.0f, 200.0f, 200.0f, 400.0f, 400.0f, 400.0f);
        
        List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>> results =
            AABBIntersectionSearch.findEntitiesInsideAABB(aabb, multiEntityOctree);
        
        // Should find center (2) and inside (3) entities = 5 total
        // Boundary entities should NOT be included (strict containment)
        assertEquals(5, results.size());
        
        // Verify all results are marked as INSIDE
        assertTrue(results.stream().allMatch(r -> 
            r.intersectionType == AABBIntersectionSearch.IntersectionType.INSIDE
        ));
        
        // Verify sorting by distance from AABB center
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToAABBCenter <= results.get(i + 1).distanceToAABBCenter);
        }
    }

    @Test
    void testIntersectionTypes() {
        AABBIntersectionSearch.AABB aabb = 
            new AABBIntersectionSearch.AABB(200.0f, 200.0f, 200.0f, 400.0f, 400.0f, 400.0f);
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        float boundaryTolerance = 1.0f;
        
        List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>> results =
            AABBIntersectionSearch.findEntitiesIntersectingAABB(
                aabb, multiEntityOctree, referencePoint, boundaryTolerance
            );
        
        // Check intersection types
        for (var result : results) {
            if (result.content.startsWith("CenterEntity") || result.content.startsWith("InsideEntity")) {
                assertEquals(AABBIntersectionSearch.IntersectionType.INSIDE, result.intersectionType);
            } else if (result.content.startsWith("BoundaryEntity")) {
                assertEquals(AABBIntersectionSearch.IntersectionType.ON_BOUNDARY, result.intersectionType);
            }
        }
    }

    @Test
    void testCountEntitiesByAABBIntersection() {
        AABBIntersectionSearch.AABB aabb = 
            new AABBIntersectionSearch.AABB(200.0f, 200.0f, 200.0f, 400.0f, 400.0f, 400.0f);
        float boundaryTolerance = 1.0f;
        
        int[] counts = AABBIntersectionSearch.countEntitiesByAABBIntersection(
            aabb, multiEntityOctree, boundaryTolerance
        );
        
        int inside = counts[0];
        int onBoundary = counts[1];
        int outside = counts[2];
        
        // Center (2) + Inside (3) = 5
        assertEquals(5, inside);
        
        // Boundary entities = 3
        assertEquals(3, onBoundary);
        
        // Outside (2) + Far (1) = 3
        assertEquals(3, outside);
        
        // Total should match entity count
        assertEquals(multiEntityOctree.getEntityStats().entityCount, inside + onBoundary + outside);
    }

    @Test
    void testFindEntitiesIntersectingMultipleAABBs() {
        // Create overlapping AABBs
        List<AABBIntersectionSearch.AABB> aabbs = new ArrayList<>();
        aabbs.add(new AABBIntersectionSearch.AABB(200.0f, 200.0f, 200.0f, 350.0f, 350.0f, 350.0f));
        aabbs.add(new AABBIntersectionSearch.AABB(250.0f, 250.0f, 250.0f, 400.0f, 400.0f, 400.0f));
        
        // Test requiring all AABBs (intersection)
        List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>> allResults =
            AABBIntersectionSearch.findEntitiesIntersectingMultipleAABBs(
                aabbs, multiEntityOctree, true
            );
        
        // Only entities in the intersection of both AABBs (250-350 range)
        assertTrue(allResults.size() > 0);
        
        // Test requiring any AABB (union)
        List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>> anyResults =
            AABBIntersectionSearch.findEntitiesIntersectingMultipleAABBs(
                aabbs, multiEntityOctree, false
            );
        
        // Should have more entities (union is larger than intersection)
        assertTrue(anyResults.size() >= allResults.size());
    }

    @Test
    void testFindEntitiesInAABBIntersection() {
        AABBIntersectionSearch.AABB aabb1 = 
            new AABBIntersectionSearch.AABB(200.0f, 200.0f, 200.0f, 350.0f, 350.0f, 350.0f);
        AABBIntersectionSearch.AABB aabb2 = 
            new AABBIntersectionSearch.AABB(250.0f, 250.0f, 250.0f, 400.0f, 400.0f, 400.0f);
        
        List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>> results =
            AABBIntersectionSearch.findEntitiesInAABBIntersection(aabb1, aabb2, multiEntityOctree);
        
        // Should find entities in the intersection region (250-350)
        assertTrue(results.size() > 0);
        
        // All entities should be in the intersection region
        for (var result : results) {
            assertTrue(result.position.x >= 250.0f && result.position.x <= 350.0f);
            assertTrue(result.position.y >= 250.0f && result.position.y <= 350.0f);
            assertTrue(result.position.z >= 250.0f && result.position.z <= 350.0f);
        }
    }

    @Test
    void testBatchAABBIntersections() {
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        List<AABBIntersectionSearch.AABB> queries = new ArrayList<>();
        queries.add(new AABBIntersectionSearch.AABB(200.0f, 200.0f, 200.0f, 300.0f, 300.0f, 300.0f)); // Small
        queries.add(new AABBIntersectionSearch.AABB(200.0f, 200.0f, 200.0f, 400.0f, 400.0f, 400.0f)); // Large
        
        Map<AABBIntersectionSearch.AABB, 
            List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>>> results =
            AABBIntersectionSearch.batchAABBIntersections(queries, multiEntityOctree, referencePoint);
        
        assertEquals(2, results.size());
        
        // Small AABB should have fewer intersections
        List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>> smallResults = 
            results.get(queries.get(0));
        List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>> largeResults = 
            results.get(queries.get(1));
        
        assertTrue(smallResults.size() <= largeResults.size());
    }

    @Test
    void testAABBFromCenterAndHalfExtents() {
        Point3f center = new Point3f(300.0f, 300.0f, 300.0f);
        float halfWidth = 100.0f;
        float halfHeight = 100.0f;
        float halfDepth = 100.0f;
        
        AABBIntersectionSearch.AABB aabb = 
            AABBIntersectionSearch.AABB.fromCenterAndHalfExtents(
                center, halfWidth, halfHeight, halfDepth
            );
        
        assertEquals(200.0f, aabb.minX);
        assertEquals(200.0f, aabb.minY);
        assertEquals(200.0f, aabb.minZ);
        assertEquals(400.0f, aabb.maxX);
        assertEquals(400.0f, aabb.maxY);
        assertEquals(400.0f, aabb.maxZ);
        
        // Test center calculation
        Point3f calculatedCenter = aabb.getCenter();
        assertEquals(center.x, calculatedCenter.x, 0.001f);
        assertEquals(center.y, calculatedCenter.y, 0.001f);
        assertEquals(center.z, calculatedCenter.z, 0.001f);
    }

    @Test
    void testAABBFromCorners() {
        Point3f corner1 = new Point3f(200.0f, 200.0f, 200.0f);
        Point3f corner2 = new Point3f(400.0f, 400.0f, 400.0f);
        
        AABBIntersectionSearch.AABB aabb = 
            AABBIntersectionSearch.AABB.fromCorners(corner1, corner2);
        
        assertEquals(200.0f, aabb.minX);
        assertEquals(200.0f, aabb.minY);
        assertEquals(200.0f, aabb.minZ);
        assertEquals(400.0f, aabb.maxX);
        assertEquals(400.0f, aabb.maxY);
        assertEquals(400.0f, aabb.maxZ);
        
        // Test with corners in opposite order
        AABBIntersectionSearch.AABB aabb2 = 
            AABBIntersectionSearch.AABB.fromCorners(corner2, corner1);
        
        assertEquals(aabb, aabb2);
    }

    @Test
    void testEmptyOctree() {
        OctreeWithEntities<LongEntityID, String> emptyOctree = 
            EntityTestUtils.createMultiEntityOctree(new ArrayList<>());
        
        AABBIntersectionSearch.AABB aabb = 
            new AABBIntersectionSearch.AABB(100.0f, 100.0f, 100.0f, 200.0f, 200.0f, 200.0f);
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        List<AABBIntersectionSearch.EntityAABBIntersection<LongEntityID, String>> results =
            AABBIntersectionSearch.findEntitiesIntersectingAABB(
                aabb, emptyOctree, referencePoint, 1.0f
            );
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testNegativeCoordinatesThrowsException() {
        // Test negative AABB coordinates
        assertThrows(IllegalArgumentException.class, () -> {
            new AABBIntersectionSearch.AABB(-10.0f, 10.0f, 10.0f, 100.0f, 100.0f, 100.0f);
        });
        
        // Test negative reference point
        AABBIntersectionSearch.AABB validAABB = 
            new AABBIntersectionSearch.AABB(10.0f, 10.0f, 10.0f, 100.0f, 100.0f, 100.0f);
        Point3f invalidRef = new Point3f(-10.0f, 10.0f, 10.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            AABBIntersectionSearch.findEntitiesIntersectingAABB(
                validAABB, multiEntityOctree, invalidRef, 1.0f
            );
        });
    }

    @Test
    void testInvalidAABBThrowsException() {
        // Test max <= min
        assertThrows(IllegalArgumentException.class, () -> {
            new AABBIntersectionSearch.AABB(100.0f, 100.0f, 100.0f, 50.0f, 150.0f, 150.0f);
        });
    }

    @Test
    void testAABBProperties() {
        AABBIntersectionSearch.AABB aabb = 
            new AABBIntersectionSearch.AABB(100.0f, 100.0f, 100.0f, 300.0f, 400.0f, 500.0f);
        
        assertEquals(200.0f, aabb.getWidth());
        assertEquals(300.0f, aabb.getHeight());
        assertEquals(400.0f, aabb.getDepth());
        assertEquals(200.0f * 300.0f * 400.0f, aabb.getVolume());
        
        Point3f center = aabb.getCenter();
        assertEquals(200.0f, center.x);
        assertEquals(250.0f, center.y);
        assertEquals(300.0f, center.z);
    }
}