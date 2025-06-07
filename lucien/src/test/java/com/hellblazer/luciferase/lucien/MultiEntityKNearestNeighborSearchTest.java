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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for multi-entity k-Nearest Neighbor search functionality
 * Tests scenarios with multiple entities at the same spatial location
 *
 * @author hal.hildebrand
 */
public class MultiEntityKNearestNeighborSearchTest {

    private final byte testLevel = 15; // Higher resolution for testing smaller coordinates
    private OctreeWithEntities<LongEntityID, String> multiEntityOctree;

    @BeforeEach
    void setUp() {
        // Create test data with multiple entities at same locations
        List<MultiEntityTestUtils.MultiEntityLocation<String>> locations = new ArrayList<>();
        
        // Multiple entities at the same exact position
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(32.0f, 32.0f, 32.0f),
            testLevel,
            "Entity1A", "Entity1B", "Entity1C"
        ));
        
        // Another location with multiple entities
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(96.0f, 96.0f, 96.0f),
            testLevel,
            "Entity2A", "Entity2B"
        ));
        
        // Single entity locations
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(160.0f, 160.0f, 160.0f),
            testLevel,
            "Entity3"
        ));
        
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(224.0f, 224.0f, 224.0f),
            testLevel,
            "Entity4"
        ));
        
        // Dense cluster
        locations.add(new MultiEntityTestUtils.MultiEntityLocation<>(
            new Point3f(288.0f, 288.0f, 288.0f),
            testLevel,
            "Entity5A", "Entity5B", "Entity5C", "Entity5D"
        ));
        
        multiEntityOctree = MultiEntityTestUtils.createMultiEntityOctree(locations);
    }

    @Test
    void testFindNearestWithMultipleEntitiesAtSameLocation() {
        Point3f queryPoint = new Point3f(30.0f, 30.0f, 30.0f);
        
        // Find 5 nearest entities
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> results = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 5, multiEntityOctree);
        
        assertEquals(5, results.size());
        
        // First 3 should be from the closest location (32, 32, 32)
        assertTrue(results.get(0).content.startsWith("Entity1"));
        assertTrue(results.get(1).content.startsWith("Entity1"));
        assertTrue(results.get(2).content.startsWith("Entity1"));
        
        // All first 3 should have the same distance
        assertEquals(results.get(0).distance, results.get(1).distance, 0.001f);
        assertEquals(results.get(1).distance, results.get(2).distance, 0.001f);
        
        // Entity IDs should be different
        assertNotEquals(results.get(0).id, results.get(1).id);
        assertNotEquals(results.get(1).id, results.get(2).id);
        assertNotEquals(results.get(0).id, results.get(2).id);
    }

    @Test
    void testFindAllEntitiesInDenseCluster() {
        Point3f queryPoint = new Point3f(288.0f, 288.0f, 288.0f);
        
        // Find 10 nearest entities (should get all 4 from the dense cluster first)
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> results = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 10, multiEntityOctree);
        
        // Should get at least 4 entities from the dense cluster
        long entity5Count = results.stream()
            .filter(r -> r.content.startsWith("Entity5"))
            .count();
        assertEquals(4, entity5Count);
        
        // All Entity5 entries should have distance 0 (query point is at their location)
        results.stream()
            .filter(r -> r.content.startsWith("Entity5"))
            .forEach(r -> assertEquals(0.0f, r.distance, 0.001f));
    }

    @Test
    void testDistanceOrderingWithMultipleEntities() {
        Point3f queryPoint = new Point3f(64.0f, 64.0f, 64.0f);
        
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> results = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 20, multiEntityOctree);
        
        // Results should be sorted by distance
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).distance <= results.get(i + 1).distance);
        }
        
        // Should have found all entities
        assertEquals(multiEntityOctree.getStats().entityCount, results.size());
    }

    @Test
    void testSearchRadiusWithMultipleEntities() {
        Point3f queryPoint = new Point3f(32.0f, 32.0f, 32.0f);
        float searchRadius = 100.0f; // Should only include first two locations
        
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> results = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 10, multiEntityOctree, searchRadius);
        
        // Should only find entities within radius (Entity1* and Entity2*)
        assertTrue(results.stream().allMatch(r -> 
            r.content.startsWith("Entity1") || r.content.startsWith("Entity2")));
        
        // Should have found 3 + 2 = 5 entities
        assertEquals(5, results.size());
    }

    @Test
    void testEntityIDUniqueness() {
        Point3f queryPoint = new Point3f(0.0f, 0.0f, 0.0f);
        
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> results = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 20, multiEntityOctree);
        
        // All entity IDs should be unique
        long uniqueIdCount = results.stream()
            .map(r -> r.id)
            .distinct()
            .count();
        assertEquals(results.size(), uniqueIdCount);
    }

    @Test
    void testEmptyMultiEntityOctree() {
        OctreeWithEntities<LongEntityID, String> emptyOctree = 
            MultiEntityTestUtils.createMultiEntityOctree(new ArrayList<>());
        Point3f queryPoint = new Point3f(10.0f, 10.0f, 10.0f);
        
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> results = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 3, emptyOctree);
        
        assertTrue(results.isEmpty());
    }

    @Test
    void testNegativeCoordinatesThrowsException() {
        Point3f invalidQueryPoint = new Point3f(-10.0f, 10.0f, 10.0f);
        
        assertThrows(IllegalArgumentException.class, () -> {
            MultiEntityKNearestNeighborSearch.findKNearestEntities(invalidQueryPoint, 1, multiEntityOctree);
        });
    }

    @Test
    void testComparisonWithSingleContentAdapter() {
        // Create single content adapter for comparison
        SingleContentAdapter<String> adapter = new SingleContentAdapter<>();
        
        // Insert only first entity from each location
        adapter.insert(new Point3f(32.0f, 32.0f, 32.0f), testLevel, "Entity1A");
        adapter.insert(new Point3f(96.0f, 96.0f, 96.0f), testLevel, "Entity2A");
        adapter.insert(new Point3f(160.0f, 160.0f, 160.0f), testLevel, "Entity3");
        adapter.insert(new Point3f(224.0f, 224.0f, 224.0f), testLevel, "Entity4");
        adapter.insert(new Point3f(288.0f, 288.0f, 288.0f), testLevel, "Entity5A");
        
        Point3f queryPoint = new Point3f(100.0f, 100.0f, 100.0f);
        
        // Get results from single content adapter
        List<KNearestNeighborSearch.KNNCandidate<String>> singleResults = 
            KNearestNeighborSearch.findKNearestNeighbors(queryPoint, 3, adapter);
        
        // Get results from multi-entity search
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> multiResults = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 3, multiEntityOctree);
        
        // Both should find nearest locations in same order (though multi-entity might have more entities)
        assertEquals(3, singleResults.size());
        assertEquals(3, multiResults.size());
    }

    @Test
    void testLargeKValue() {
        Point3f queryPoint = new Point3f(150.0f, 150.0f, 150.0f);
        
        // Request more neighbors than exist
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> results = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 100, multiEntityOctree);
        
        // Should return all entities
        assertEquals(multiEntityOctree.getStats().entityCount, results.size());
    }

    @Test
    void testResultConsistencyWithMultipleEntities() {
        Point3f queryPoint = new Point3f(50.0f, 50.0f, 50.0f);
        
        // Multiple calls should return consistent results
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> results1 = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 5, multiEntityOctree);
        List<MultiEntityKNearestNeighborSearch.MultiEntityKNNCandidate<LongEntityID, String>> results2 = 
            MultiEntityKNearestNeighborSearch.findKNearestEntities(queryPoint, 5, multiEntityOctree);
        
        assertEquals(results1.size(), results2.size());
        
        // Results should be in the same order with same distances
        for (int i = 0; i < results1.size(); i++) {
            assertEquals(results1.get(i).id, results2.get(i).id);
            assertEquals(results1.get(i).content, results2.get(i).content);
            assertEquals(results1.get(i).distance, results2.get(i).distance, 0.001f);
        }
    }
}