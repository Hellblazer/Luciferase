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
 * Unit tests for multi-entity frustum culling search functionality
 *
 * @author hal.hildebrand
 */
public class MultiEntityFrustumCullingSearchTest {

    private final byte testLevel = 15;
    private OctreeWithEntities<LongEntityID, String> multiEntityOctree;
    private Frustum3D testFrustum;
    private Point3f cameraPosition;

    @BeforeEach
    void setUp() {
        // Setup camera and frustum
        cameraPosition = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f lookAt = new Point3f(500.0f, 500.0f, 1000.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        
        // Create a perspective frustum
        testFrustum = Frustum3D.createPerspective(
            cameraPosition, lookAt, up,
            (float) Math.toRadians(60.0f), // 60 degree FOV
            1.333f, // 4:3 aspect ratio
            10.0f,  // near plane
            900.0f  // far plane
        );
        
        // Create test data
        List<MultiEntityTestUtils.MultiEntityLocation<String>> locations = new ArrayList<>();
        
        // Entities clearly inside frustum (near center of view)
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(500.0f, 500.0f, 500.0f),
            testLevel,
            "CenterEntity1", "CenterEntity2", "CenterEntity3"
        ));
        
        // Entities at edge of frustum
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(700.0f, 500.0f, 500.0f),
            testLevel,
            "EdgeEntityRight1", "EdgeEntityRight2"
        ));
        
        // Entities outside frustum (behind camera)
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(500.0f, 500.0f, 50.0f),
            testLevel,
            "BehindCamera1", "BehindCamera2"
        ));
        
        // Entities outside frustum (too far left)
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(100.0f, 500.0f, 500.0f),
            testLevel,
            "OutsideLeft"
        ));
        
        // Entities outside frustum (beyond far plane)
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(500.0f, 500.0f, 1100.0f),
            testLevel,
            "BeyondFar"
        ));
        
        multiEntityOctree = MultiEntityTestUtils.createMultiEntityOctree(locations);
    }

    @Test
    void testFindEntitiesInFrustum() {
        List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>> results =
            MultiEntityFrustumCullingSearch.findEntitiesInFrustum(testFrustum, multiEntityOctree, cameraPosition);
        
        // Should find center entities and edge entities
        assertTrue(results.size() >= 5); // At least CenterEntity1-3 and EdgeEntityRight1-2
        
        // Verify center entities are found
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("CenterEntity")));
        
        // Verify entities are sorted by distance
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToCamera <= results.get(i + 1).distanceToCamera);
        }
        
        // Verify entities outside frustum are not included
        assertFalse(results.stream().anyMatch(r -> r.content.startsWith("BehindCamera")));
        assertFalse(results.stream().anyMatch(r -> r.content.equals("OutsideLeft")));
        assertFalse(results.stream().anyMatch(r -> r.content.equals("BeyondFar")));
    }

    @Test
    void testMultipleEntitiesAtSameLocationInFrustum() {
        List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>> results =
            MultiEntityFrustumCullingSearch.findEntitiesInFrustum(testFrustum, multiEntityOctree, cameraPosition);
        
        // Count center entities
        long centerEntityCount = results.stream()
            .filter(r -> r.content.startsWith("CenterEntity"))
            .count();
        
        assertEquals(3, centerEntityCount); // Should find all 3 center entities
        
        // Verify they all have the same distance
        List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>> centerEntities =
            results.stream()
                .filter(r -> r.content.startsWith("CenterEntity"))
                .toList();
        
        float expectedDistance = centerEntities.get(0).distanceToCamera;
        assertTrue(centerEntities.stream().allMatch(e -> Math.abs(e.distanceToCamera - expectedDistance) < 0.001f));
    }

    @Test
    void testCountEntitiesByCullingResult() {
        int[] counts = MultiEntityFrustumCullingSearch.countEntitiesByCullingResult(testFrustum, multiEntityOctree);
        
        int inside = counts[0];
        int intersecting = counts[1];
        int outside = counts[2];
        
        // Should have some entities inside
        assertTrue(inside > 0);
        
        // Should have some entities outside
        assertTrue(outside > 0);
        
        // Total should match entity count
        assertEquals(multiEntityOctree.getStats().entityCount, inside + intersecting + outside);
    }

    @Test
    void testOrthographicFrustum() {
        // Create orthographic frustum
        Frustum3D orthoFrustum = Frustum3D.createOrthographic(
            cameraPosition, 
            new Point3f(500.0f, 500.0f, 1000.0f), // lookAt
            new Vector3f(0.0f, 1.0f, 0.0f), // up
            300.0f, 700.0f, // left, right
            300.0f, 700.0f, // bottom, top
            10.0f, 900.0f   // near, far
        );
        
        List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>> results =
            MultiEntityFrustumCullingSearch.findEntitiesInFrustum(orthoFrustum, multiEntityOctree, cameraPosition);
        
        // Should find entities within the orthographic bounds
        assertTrue(results.size() > 0);
        
        // Center entities should be found
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("CenterEntity")));
    }

    @Test
    void testVisibleEntities() {
        Vector3f lookDirection = new Vector3f(0.0f, 0.0f, 1.0f); // Looking along +Z
        
        List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>> results =
            MultiEntityFrustumCullingSearch.findVisibleEntities(
                testFrustum, multiEntityOctree, cameraPosition, lookDirection
            );
        
        // Should not include entities behind camera
        assertFalse(results.stream().anyMatch(r -> r.content.startsWith("BehindCamera")));
        
        // Should include entities in front
        assertTrue(results.stream().anyMatch(r -> r.content.startsWith("CenterEntity")));
    }

    @Test
    void testBatchFrustumCulling() {
        // Create multiple frustums for different camera angles
        List<Frustum3D> frustums = new ArrayList<>();
        List<Point3f> cameraPositions = new ArrayList<>();
        
        // Original frustum
        frustums.add(testFrustum);
        cameraPositions.add(cameraPosition);
        
        // Frustum looking right (to avoid negative coordinates)
        Point3f rightCameraPos = new Point3f(500.0f, 500.0f, 100.0f);
        Point3f rightLookAt = new Point3f(900.0f, 500.0f, 500.0f);
        Frustum3D rightFrustum = Frustum3D.createPerspective(
            rightCameraPos, rightLookAt, new Vector3f(0.0f, 1.0f, 0.0f),
            (float) Math.toRadians(60.0f), 1.333f, 10.0f, 900.0f
        );
        frustums.add(rightFrustum);
        cameraPositions.add(rightCameraPos);
        
        Map<Frustum3D, List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>>> results =
            MultiEntityFrustumCullingSearch.batchFrustumCulling(frustums, multiEntityOctree, cameraPositions);
        
        assertEquals(2, results.size());
        
        // Each frustum should see different entities
        List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>> originalResults = 
            results.get(testFrustum);
        List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>> rightResults = 
            results.get(rightFrustum);
        
        // Original should see center entities
        assertTrue(originalResults.stream().anyMatch(r -> r.content.startsWith("CenterEntity")));
        
        // Right frustum might see different entities
        assertNotNull(rightResults);
    }

    @Test
    void testFindEntitiesInAnyFrustum() {
        // Create LOD frustums
        List<Frustum3D> lodFrustums = new ArrayList<>();
        
        // Near LOD frustum (narrow, close)
        Frustum3D nearLod = Frustum3D.createPerspective(
            cameraPosition,
            new Point3f(500.0f, 500.0f, 1000.0f),
            new Vector3f(0.0f, 1.0f, 0.0f),
            (float) Math.toRadians(30.0f), // Narrower FOV
            1.333f,
            10.0f,
            300.0f // Closer far plane
        );
        lodFrustums.add(nearLod);
        
        // Far LOD frustum (wide, far)
        lodFrustums.add(testFrustum); // Use the main frustum as far LOD
        
        List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>> results =
            MultiEntityFrustumCullingSearch.findEntitiesInAnyFrustum(lodFrustums, multiEntityOctree, cameraPosition);
        
        // Should find entities from both frustums
        assertTrue(results.size() > 0);
        
        // Results should be sorted by distance
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distanceToCamera <= results.get(i + 1).distanceToCamera);
        }
    }

    @Test
    void testEmptyOctree() {
        OctreeWithEntities<LongEntityID, String> emptyOctree = 
            MultiEntityTestUtils.createMultiEntityOctree(new ArrayList<>());
            
        List<MultiEntityFrustumCullingSearch.EntityFrustumIntersection<LongEntityID, String>> results =
            MultiEntityFrustumCullingSearch.findEntitiesInFrustum(testFrustum, emptyOctree, cameraPosition);
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testNegativeCoordinatesThrowsException() {
        Point3f invalidCameraPos = new Point3f(-10.0f, 10.0f, 10.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            MultiEntityFrustumCullingSearch.findEntitiesInFrustum(testFrustum, multiEntityOctree, invalidCameraPos);
        });
    }

    @Test
    void testAllCullingResults() {
        // Verify all entities are in one of the three culling states
        int[] counts = MultiEntityFrustumCullingSearch.countEntitiesByCullingResult(testFrustum, multiEntityOctree);
        
        int totalFromCounts = counts[0] + counts[1] + counts[2];
        int totalEntities = multiEntityOctree.getStats().entityCount;
        
        assertEquals(totalEntities, totalFromCounts);
    }
}