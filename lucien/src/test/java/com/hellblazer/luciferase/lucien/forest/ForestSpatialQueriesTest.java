/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.*;
import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.Ray3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ForestSpatialQueries
 */
public class ForestSpatialQueriesTest {
    
    private Forest<MortonKey, LongEntityID, String> forest;
    private ForestEntityManager<MortonKey, LongEntityID, String> entityManager;
    private ForestSpatialQueries<MortonKey, LongEntityID, String> queries;
    private SequentialLongIDGenerator idGenerator;
    private List<LongEntityID> allEntityIds;
    
    @BeforeEach
    void setUp() {
        var config = ForestConfig.defaultConfig();
        forest = new Forest<>(config);
        idGenerator = new SequentialLongIDGenerator();
        allEntityIds = new ArrayList<>();
        
        // Create a 2x2 grid of trees
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                var tree = new Octree<LongEntityID, String>(idGenerator);
                var bounds = new EntityBounds(
                    new Point3f(x * 100, 0, z * 100),
                    new Point3f((x + 1) * 100, 100, (z + 1) * 100)
                );
                ForestTestUtil.addTreeWithBounds(forest, tree, bounds, "tree_" + x + "_" + z);
            }
        }
        
        entityManager = new ForestEntityManager<>(forest, idGenerator);
        
        // Populate with entities
        populateForest();
        
        queries = new ForestSpatialQueries<>(forest);
    }
    
    private void populateForest() {
        // Add entities in a grid pattern across all trees
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                var pos = new Point3f(x * 10, 50, z * 10);
                var id = (LongEntityID) idGenerator.generateID();
                entityManager.insert(id, "Entity at " + pos, pos, null);
                allEntityIds.add(id);
            }
        }
    }
    
    @Test
    void testFindKNearestNeighbors() {
        var center = new Point3f(100, 50, 100); // Center of the grid
        int k = 10;
        
        var nearest = queries.findKNearestNeighbors(center, k, Float.MAX_VALUE);
        
        assertEquals(k, nearest.size());
        
        // Verify ordering by distance
        Point3f prevPos = null;
        float prevDist = -1;
        for (var id : nearest) {
            var pos = entityManager.getEntityPosition(id);
            assertNotNull(pos);
            float dist = center.distance(pos);
            
            if (prevDist >= 0) {
                assertTrue(dist >= prevDist, "Results should be ordered by distance");
            }
            prevDist = dist;
            prevPos = pos;
        }
    }
    
    @Test
    void testFindKNearestNeighborsAcrossTreeBoundary() {
        // Query point exactly on tree boundary
        var boundaryPoint = new Point3f(100, 50, 100);
        int k = 20;
        
        var nearest = queries.findKNearestNeighbors(boundaryPoint, k, Float.MAX_VALUE);
        
        assertEquals(k, nearest.size());
        
        // Should include entities from multiple trees
        Set<String> treesRepresented = new HashSet<>();
        for (var id : nearest) {
            var location = entityManager.getEntityLocation(id);
            if (location != null) {
                treesRepresented.add(location.getTreeId());
            }
        }
        
        assertTrue(treesRepresented.size() > 1, "K-NN should span multiple trees");
    }
    
    @Test
    void testFindEntitiesWithinDistance() {
        var center = new Point3f(100, 50, 100);
        float radius = 30.0f;
        
        var withinRadius = queries.findEntitiesWithinDistance(center, radius);
        
        // Verify all results are within radius
        for (var id : withinRadius) {
            var pos = entityManager.getEntityPosition(id);
            assertNotNull(pos);
            float dist = center.distance(pos);
            assertTrue(dist <= radius, "Entity should be within radius");
        }
        
        // Verify no entities outside radius are included
        for (var id : allEntityIds) {
            var pos = entityManager.getEntityPosition(id);
            if (pos != null) {
                float dist = center.distance(pos);
                if (dist > radius) {
                    assertFalse(withinRadius.contains(id), "Entity outside radius should not be included");
                }
            }
        }
    }
    
    @Test
    void testRayIntersectionAcrossTrees() {
        // Ray crossing multiple trees
        var origin = new Point3f(-10, 50, 50);
        var direction = new Vector3f(1, 0, 0); // Shooting along X axis
        var ray = new Ray3D(origin, direction);
        
        var hits = queries.rayIntersectAll(ray);
        
        // Should hit entities along the X axis
        assertFalse(hits.isEmpty());
        
        // Verify hits are ordered by distance
        float prevDist = -1;
        for (var hit : hits) {
            assertTrue(hit.distance() >= prevDist, "Hits should be ordered by distance");
            prevDist = hit.distance();
        }
    }
    
    @Test
    void testRayIntersectFirst() {
        var origin = new Point3f(-10, 50, 100);
        var direction = new Vector3f(1, 0, 0);
        var ray = new Ray3D(origin, direction);
        
        var firstHit = queries.rayIntersectFirst(ray);
        
        assertTrue(firstHit.isPresent());
        
        // Verify it's actually the first hit
        var allHits = queries.rayIntersectAll(ray);
        if (!allHits.isEmpty()) {
            assertEquals(allHits.get(0).entityId(), firstHit.get().entityId());
            assertEquals(allHits.get(0).distance(), firstHit.get().distance());
        }
    }
    
    @Test
    void testFrustumCullVisible() {
        // Create frustum looking along positive X axis
        var frustum = createTestFrustum();
        
        var visible = queries.frustumCullVisible(frustum);
        
        assertFalse(visible.isEmpty());
        
        // Verify all visible entities are actually in the frustum
        for (var id : visible) {
            var pos = entityManager.getEntityPosition(id);
            assertNotNull(pos);
            assertTrue(frustum.containsPoint(pos), "Visible entity should be in frustum");
        }
    }
    
    @Test
    void testFrustumCullVisibleSorted() {
        var cameraPos = new Point3f(0, 50, 100);
        var frustum = createTestFrustum();
        
        var sorted = queries.frustumCullVisibleSorted(frustum, cameraPos);
        
        assertFalse(sorted.isEmpty());
        
        // Verify sorting by distance from camera
        float prevDist = -1;
        for (var id : sorted) {
            var pos = entityManager.getEntityPosition(id);
            assertNotNull(pos);
            float dist = cameraPos.distance(pos);
            assertTrue(dist >= prevDist, "Results should be sorted by distance from camera");
            prevDist = dist;
        }
    }
    
    @Test
    void testEmptyResults() {
        // Query outside all entities
        var farPoint = new Point3f(1000, 1000, 1000);
        
        // K-NN should still return k results (the k closest)
        var knn = queries.findKNearestNeighbors(farPoint, 5, Float.MAX_VALUE);
        assertEquals(5, knn.size());
        
        // Range query should return empty
        var range = queries.findEntitiesWithinDistance(farPoint, 10.0f);
        assertTrue(range.isEmpty());
        
        // Ray miss should return empty
        var ray = new Ray3D(farPoint, new Vector3f(0, 1, 0)); // Shooting up
        var hits = queries.rayIntersectAll(ray);
        assertTrue(hits.isEmpty());
        
        var firstHit = queries.rayIntersectFirst(ray);
        assertFalse(firstHit.isPresent());
    }
    
    @Test
    void testLargeKValue() {
        var center = new Point3f(100, 50, 100);
        int k = 1000; // More than total entities (400)
        
        var nearest = queries.findKNearestNeighbors(center, k, Float.MAX_VALUE);
        
        // Should return all entities
        assertEquals(allEntityIds.size(), nearest.size());
    }
    
    @Test
    void testParallelVsSequentialConsistency() {
        var center = new Point3f(100, 50, 100);
        
        // Configure for sequential execution
        var seqConfig = ForestSpatialQueries.QueryConfig.defaultConfig()
            .withParallelism(1)
            .withMinTreesForParallel(Integer.MAX_VALUE);
        var seqQueries = new ForestSpatialQueries<>(forest, seqConfig);
        
        // Configure for parallel execution
        var parConfig = ForestSpatialQueries.QueryConfig.defaultConfig()
            .withParallelism(4)
            .withMinTreesForParallel(1);
        var parQueries = new ForestSpatialQueries<>(forest, parConfig);
        
        // Compare results
        var seqKnn = seqQueries.findKNearestNeighbors(center, 20, Float.MAX_VALUE);
        var parKnn = parQueries.findKNearestNeighbors(center, 20, Float.MAX_VALUE);
        
        assertEquals(new HashSet<>(seqKnn), new HashSet<>(parKnn), 
            "Parallel and sequential results should contain same entities");
        
        var seqRange = seqQueries.findEntitiesWithinDistance(center, 50.0f);
        var parRange = parQueries.findEntitiesWithinDistance(center, 50.0f);
        
        assertEquals(new HashSet<>(seqRange), new HashSet<>(parRange),
            "Parallel and sequential range queries should match");
        
        // Clean up
        seqQueries.shutdown();
        parQueries.shutdown();
    }
    
    @Test
    void testShutdown() {
        var customQueries = new ForestSpatialQueries<>(forest);
        
        // Perform some queries
        customQueries.findKNearestNeighbors(new Point3f(100, 50, 100), 10, Float.MAX_VALUE);
        
        // Shutdown should complete without hanging
        assertDoesNotThrow(() -> customQueries.shutdown());
    }
    
    private Frustum3D createTestFrustum() {
        // Create a simple orthographic frustum for testing
        var cameraPosition = new Point3f(100, 50, 50);
        var lookAt = new Point3f(100, 50, 150);
        var up = new Vector3f(0, 1, 0);
        
        return Frustum3D.createOrthographic(
            cameraPosition, lookAt, up,
            50, 150,    // left, right
            25, 75,     // bottom, top  
            10, 200     // near, far
        );
    }
}