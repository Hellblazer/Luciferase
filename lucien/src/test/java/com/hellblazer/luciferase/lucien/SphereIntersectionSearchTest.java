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
 * Unit tests for multi-entity sphere intersection search functionality
 *
 * @author hal.hildebrand
 */
public class SphereIntersectionSearchTest {

    private final byte testLevel = 15;
    private OctreeWithEntities<LongEntityID, String> multiEntityOctree;

    @BeforeEach
    void setUp() {
        // Create test data with entities at various distances from a sphere center
        List<EntityTestUtils.MultiEntityLocation<String>> locations = new ArrayList<>();
        
        // Entities at sphere center (distance = 0)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(500.0f, 500.0f, 500.0f),
            testLevel,
            "CenterEntity1", "CenterEntity2"
        ));
        
        // Entities inside sphere (distance < radius)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(550.0f, 500.0f, 500.0f), // 50 units from center
            testLevel,
            "InsideEntity1", "InsideEntity2", "InsideEntity3"
        ));
        
        // Entities on sphere surface (distance ≈ radius)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(600.0f, 500.0f, 500.0f), // 100 units from center
            testLevel,
            "SurfaceEntity1", "SurfaceEntity2"
        ));
        
        // Entities outside sphere (distance > radius)
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(650.0f, 500.0f, 500.0f), // 150 units from center
            testLevel,
            "OutsideEntity1", "OutsideEntity2"
        ));
        
        // Entities far outside
        locations.add(new EntityTestUtils.MultiEntityLocation<>(
            new Point3f(800.0f, 500.0f, 500.0f), // 300 units from center
            testLevel,
            "FarEntity"
        ));
        
        multiEntityOctree = EntityTestUtils.createMultiEntityOctree(locations);
    }

    @Test
    void testFindEntitiesIntersectingSphere() {
        Point3f sphereCenter = new Point3f(500.0f, 500.0f, 500.0f);
        float sphereRadius = 100.0f;
        Point3f referencePoint = new Point3f(400.0f, 400.0f, 400.0f);
        float surfaceTolerance = 1.0f;
        
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> results =
            SphereIntersectionSearch.findEntitiesIntersectingSphere(
                sphereCenter, sphereRadius, multiEntityOctree, referencePoint, surfaceTolerance
            );
        
        // Should find center (2), inside (3), and surface (2) entities = 7 total
        assertEquals(7, results.size());
        
        // Verify center entities are included
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("CenterEntity")));
        
        // Verify inside entities are included
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("InsideEntity")));
        
        // Verify surface entities are included
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("SurfaceEntity")));
        
        // Verify outside entities are NOT included
        assertFalse(results.stream().anyMatch(r -> r.content.startsWith("OutsideEntity")));
        assertFalse(results.stream().anyMatch(r -> r.content.equals("FarEntity")));
        
        // Verify sorting by distance from reference point
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToReferencePoint <= results.get(i + 1).distanceToReferencePoint);
        }
    }

    @Test
    void testFindEntitiesInsideSphere() {
        Point3f sphereCenter = new Point3f(500.0f, 500.0f, 500.0f);
        float sphereRadius = 100.0f;
        
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> results =
            SphereIntersectionSearch.findEntitiesInsideSphere(sphereCenter, sphereRadius, multiEntityOctree);
        
        // Should find center (2) and inside (3) entities = 5 total
        // Surface entities should NOT be included (distance = radius)
        assertEquals(5, results.size());
        
        // Verify all results are marked as INSIDE
        assertTrue(results.stream().allMatch(r -> r.intersectionType == SphereIntersectionSearch.IntersectionType.INSIDE));
        
        // Verify sorting by distance from sphere center
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToCenter <= results.get(i + 1).distanceToCenter);
        }
    }

    @Test
    void testFindEntitiesInSphericalShell() {
        Point3f center = new Point3f(500.0f, 500.0f, 500.0f);
        float innerRadius = 40.0f;
        float outerRadius = 110.0f;
        
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> results =
            SphereIntersectionSearch.findEntitiesInSphericalShell(
                center, innerRadius, outerRadius, multiEntityOctree
            );
        
        // Should find inside entities (distance=50) and surface entities (distance=100)
        // Center entities (distance=0) are outside inner radius
        // Far entities (distance>110) are outside outer radius
        assertTrue(results.size() >= 5); // InsideEntity1-3 and SurfaceEntity1-2
        
        // Verify entities are in the shell
        assertTrue(results.stream().allMatch(r -> 
            r.distanceToCenter >= innerRadius && r.distanceToCenter <= outerRadius
        ));
    }

    @Test
    void testCountEntitiesBySphereIntersection() {
        Point3f sphereCenter = new Point3f(500.0f, 500.0f, 500.0f);
        float sphereRadius = 100.0f;
        float surfaceTolerance = 1.0f;
        
        int[] counts = SphereIntersectionSearch.countEntitiesBySphereIntersection(
            sphereCenter, sphereRadius, multiEntityOctree, surfaceTolerance
        );
        
        int inside = counts[0];
        int onSurface = counts[1];
        int outside = counts[2];
        
        // Center (2) + Inside (3) = 5
        assertEquals(5, inside);
        
        // Surface entities = 2
        assertEquals(2, onSurface);
        
        // Outside (2) + Far (1) = 3
        assertEquals(3, outside);
        
        // Total should match entity count
        assertEquals(multiEntityOctree.getEntityStats().entityCount, inside + onSurface + outside);
    }

    @Test
    void testIntersectionTypes() {
        Point3f sphereCenter = new Point3f(500.0f, 500.0f, 500.0f);
        float sphereRadius = 100.0f;
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        float surfaceTolerance = 1.0f;
        
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> results =
            SphereIntersectionSearch.findEntitiesIntersectingSphere(
                sphereCenter, sphereRadius, multiEntityOctree, referencePoint, surfaceTolerance
            );
        
        // Check intersection types
        for (var result : results) {
            if (result.content.startsWith("CenterEntity") || result.content.startsWith("InsideEntity")) {
                assertEquals(SphereIntersectionSearch.IntersectionType.INSIDE, result.intersectionType);
            } else if (result.content.startsWith("SurfaceEntity")) {
                assertEquals(SphereIntersectionSearch.IntersectionType.ON_SURFACE, result.intersectionType);
            }
        }
    }

    @Test
    void testFindEntitiesIntersectingMultipleSpheres() {
        // Create overlapping spheres
        List<SphereIntersectionSearch.SphereQuery> spheres = new ArrayList<>();
        spheres.add(new SphereIntersectionSearch.SphereQuery(
            new Point3f(500.0f, 500.0f, 500.0f), 100.0f
        ));
        spheres.add(new SphereIntersectionSearch.SphereQuery(
            new Point3f(550.0f, 500.0f, 500.0f), 100.0f
        ));
        
        // Test requiring all spheres (intersection)
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> allResults =
            SphereIntersectionSearch.findEntitiesIntersectingMultipleSpheres(
                spheres, multiEntityOctree, true
            );
        
        // Only entities in the intersection of both spheres
        assertTrue(allResults.size() > 0);
        
        // Test requiring any sphere (union)
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> anyResults =
            SphereIntersectionSearch.findEntitiesIntersectingMultipleSpheres(
                spheres, multiEntityOctree, false
            );
        
        // Should have more entities (union is larger than intersection)
        assertTrue(anyResults.size() >= allResults.size());
    }

    @Test
    void testBatchSphereIntersections() {
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        List<SphereIntersectionSearch.SphereQuery> queries = new ArrayList<>();
        queries.add(new SphereIntersectionSearch.SphereQuery(
            new Point3f(500.0f, 500.0f, 500.0f), 50.0f  // Small sphere
        ));
        queries.add(new SphereIntersectionSearch.SphereQuery(
            new Point3f(500.0f, 500.0f, 500.0f), 150.0f // Large sphere
        ));
        
        Map<SphereIntersectionSearch.SphereQuery, 
            List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>>> results =
            SphereIntersectionSearch.batchSphereIntersections(queries, multiEntityOctree, referencePoint);
        
        assertEquals(2, results.size());
        
        // Small sphere should have fewer intersections
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> smallResults = 
            results.get(queries.get(0));
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> largeResults = 
            results.get(queries.get(1));
        
        assertTrue(smallResults.size() < largeResults.size());
    }

    @Test
    void testFindKNearestToSphereSurface() {
        Point3f sphereCenter = new Point3f(500.0f, 500.0f, 500.0f);
        float sphereRadius = 75.0f; // Between inside and surface entities
        int k = 5;
        
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> results =
            SphereIntersectionSearch.findKNearestToSphereSurface(
                sphereCenter, sphereRadius, multiEntityOctree, k
            );
        
        assertEquals(k, results.size());
        
        // Results should be sorted by distance to surface
        for (int i = 0; i < results.size() - 1; i++) {
            // distanceToReferencePoint is used to store distance to surface in this method
            assertTrue(results.get(i).distanceToReferencePoint <= results.get(i + 1).distanceToReferencePoint);
        }
        
        // Inside entities at distance 50 should be closest to surface (|50-75| = 25)
        assertTrue(results.get(0).content.startsWith("InsideEntity"));
    }

    @Test
    void testEmptyOctree() {
        OctreeWithEntities<LongEntityID, String> emptyOctree = 
            EntityTestUtils.createMultiEntityOctree(new ArrayList<>());
        
        Point3f sphereCenter = new Point3f(100.0f, 100.0f, 100.0f);
        float sphereRadius = 50.0f;
        Point3f referencePoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        List<SphereIntersectionSearch.EntitySphereIntersection<LongEntityID, String>> results =
            SphereIntersectionSearch.findEntitiesIntersectingSphere(
                sphereCenter, sphereRadius, emptyOctree, referencePoint, 1.0f
            );
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testNegativeCoordinatesThrowsException() {
        Point3f invalidCenter = new Point3f(-10.0f, 10.0f, 10.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.findEntitiesInsideSphere(
                invalidCenter, 50.0f, multiEntityOctree
            );
        });
    }

    @Test
    void testInvalidRadiusThrowsException() {
        Point3f validCenter = new Point3f(100.0f, 100.0f, 100.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.findEntitiesInsideSphere(
                validCenter, -10.0f, multiEntityOctree
            );
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.findEntitiesInsideSphere(
                validCenter, 0.0f, multiEntityOctree
            );
        });
    }

    @Test
    void testSphericalShellValidation() {
        Point3f center = new Point3f(100.0f, 100.0f, 100.0f);
        
        // Invalid: outer radius <= inner radius
        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.findEntitiesInSphericalShell(
                center, 100.0f, 50.0f, multiEntityOctree
            );
        });
        
        // Invalid: negative radius
        assertThrows(IllegalArgumentException.class, () -> {
            SphereIntersectionSearch.findEntitiesInSphericalShell(
                center, -10.0f, 50.0f, multiEntityOctree
            );
        });
    }
}