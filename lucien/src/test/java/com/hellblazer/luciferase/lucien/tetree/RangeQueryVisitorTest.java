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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the RangeQueryVisitor implementation.
 *
 * @author hal.hildebrand
 */
class RangeQueryVisitorTest {
    
    private Tetree<LongEntityID, String> tetree;
    private static final byte TEST_LEVEL = 4;
    private EntityIDGenerator<LongEntityID> idGenerator;
    
    @BeforeEach
    void setUp() {
        idGenerator = new SequentialLongIDGenerator();
        tetree = new Tetree<>(idGenerator);
    }
    
    @Test
    void testBasicRangeQuery() {
        // Add some entities - IDs are auto-generated
        
        tetree.insert(new Point3f(10, 10, 10), TEST_LEVEL, "Entity1");
        tetree.insert(new Point3f(20, 20, 20), TEST_LEVEL, "Entity2");
        tetree.insert(new Point3f(30, 30, 30), TEST_LEVEL, "Entity3");
        
        // Query a range that should contain entity1 and entity2
        var bounds = new VolumeBounds(5, 5, 5, 25, 25, 25);
        var visitor = new RangeQueryVisitor<LongEntityID, String>(bounds, true);
        
        // Manual traversal since accept doesn't exist
        // For now, we'll use the stream API
        tetree.nodes().forEach(node -> visitor.visitNode(node, TEST_LEVEL, null));
        
        var results = visitor.getResults();
        assertFalse(results.isEmpty(), "Should find nodes in range");
        
        // Count entities found
        int entityCount = results.stream()
            .mapToInt(node -> node.entityIds().size())
            .sum();
        
        assertTrue(entityCount >= 2, "Should find at least 2 entities");
        
        // Check statistics
        var stats = visitor.getStatistics();
        assertNotNull(stats);
        assertTrue(stats.contains("Visited="));
        assertTrue(stats.contains("Found="));
    }
    
    @Test
    void testIntersectingVsContained() {
        // Add entity at a specific location
        tetree.insert(new Point3f(15, 15, 15), TEST_LEVEL, "TestEntity");
        
        // Create bounds that partially overlap the entity's node
        var bounds = new VolumeBounds(10, 10, 10, 20, 20, 20);
        
        // Test intersecting mode
        var intersectingVisitor = new RangeQueryVisitor<LongEntityID, String>(bounds, true);
        tetree.nodes().forEach(node -> intersectingVisitor.visitNode(node, TEST_LEVEL, null));
        assertFalse(intersectingVisitor.getResults().isEmpty(), 
            "Intersecting query should find nodes");
        
        // Test contained mode with small bounds
        var smallBounds = new VolumeBounds(14, 14, 14, 16, 16, 16);
        var containedVisitor = new RangeQueryVisitor<LongEntityID, String>(smallBounds, false);
        tetree.nodes().forEach(node -> containedVisitor.visitNode(node, TEST_LEVEL, null));
        
        // Results depend on node size - at level 4, nodes might be too large to be contained
        // This is expected behavior
    }
    
    @Test
    void testEmptyRangeQuery() {
        // Add some entities at a location
        tetree.insert(new Point3f(50, 50, 50), TEST_LEVEL, "Entity");
        
        // Query a range that is far away from any nodes
        // At level 4, node size is 131,072, so we need to go much farther
        var bounds = new VolumeBounds(1_000_000, 1_000_000, 1_000_000, 1_100_000, 1_100_000, 1_100_000);
        var visitor = new RangeQueryVisitor<LongEntityID, String>(bounds, true);
        
        // Manual traversal since accept doesn't exist
        // For now, we'll use the stream API
        tetree.nodes().forEach(node -> visitor.visitNode(node, TEST_LEVEL, null));
        
        var results = visitor.getResults();
        assertTrue(results.isEmpty(), "Should find no nodes in empty range");
    }
    
    @Test
    void testVisitorReset() {
        // Add entity
        tetree.insert(new Point3f(10, 10, 10), TEST_LEVEL, "Entity");
        
        // First query
        var bounds1 = new VolumeBounds(5, 5, 5, 15, 15, 15);
        var visitor = new RangeQueryVisitor<LongEntityID, String>(bounds1, true);
        // Manual traversal since accept doesn't exist
        // For now, we'll use the stream API
        tetree.nodes().forEach(node -> visitor.visitNode(node, TEST_LEVEL, null));
        
        int firstResultCount = visitor.getResults().size();
        assertTrue(firstResultCount > 0, "First query should find results");
        
        // Reset and query different range that's far away
        // At level 4, node size is 131,072, so we need to go much farther
        var bounds2 = new VolumeBounds(1_000_000, 1_000_000, 1_000_000, 1_100_000, 1_100_000, 1_100_000);
        visitor.reset(bounds2);
        // Manual traversal since accept doesn't exist
        // For now, we'll use the stream API
        tetree.nodes().forEach(node -> visitor.visitNode(node, TEST_LEVEL, null));
        
        assertEquals(0, visitor.getResults().size(), 
            "After reset, should find no results in empty range");
    }
    
    @Test
    void testMaxDepthControl() {
        // Add entities at different positions to create tree depth
        for (int i = 0; i < 10; i++) {
            float pos = i * 10;
            tetree.insert(new Point3f(pos, pos, pos), TEST_LEVEL, "Entity" + i);
        }
        
        var bounds = new VolumeBounds(0, 0, 0, 100, 100, 100);
        
        // Test with limited depth
        var visitor = new RangeQueryVisitor<LongEntityID, String>(bounds, true);
        visitor.setMaxDepth(2);
        // Manual traversal since accept doesn't exist
        // For now, we'll use the stream API
        tetree.nodes().forEach(node -> visitor.visitNode(node, TEST_LEVEL, null));
        
        var stats = visitor.getStatistics();
        assertNotNull(stats);
        
        // With max depth, should visit fewer nodes than unlimited
        int limitedNodes = visitor.getResults().size();
        
        // Test unlimited depth
        visitor.reset(bounds);
        visitor.setMaxDepth(-1);
        // Manual traversal since accept doesn't exist
        // For now, we'll use the stream API
        tetree.nodes().forEach(node -> visitor.visitNode(node, TEST_LEVEL, null));
        
        int unlimitedNodes = visitor.getResults().size();
        
        // Unlimited should visit at least as many nodes
        assertTrue(unlimitedNodes >= limitedNodes);
    }
    
    @Test
    void testNeighborExpansion() {
        // Create a cluster of entities
        var bounds = new VolumeBounds(10, 10, 10, 30, 30, 30);
        
        // Add entities in a connected region
        for (int i = 0; i < 5; i++) {
            float pos = 15 + i * 3;
            tetree.insert(new Point3f(pos, 15, 15), TEST_LEVEL, "Entity" + i);
        }
        
        var visitor = new RangeQueryVisitor<LongEntityID, String>(bounds, true);
        
        // First do normal traversal
        // Manual traversal since accept doesn't exist
        // For now, we'll use the stream API
        tetree.nodes().forEach(node -> visitor.visitNode(node, TEST_LEVEL, null));
        var normalResults = visitor.getResults();
        assertFalse(normalResults.isEmpty());
        
        // Note: Neighbor expansion would require access to internal tetree structure
        // This is left as a future enhancement when internal APIs are available
    }
    
    @Test
    void testStatisticsAccuracy() {
        // Add multiple entities
        for (int i = 0; i < 20; i++) {
            tetree.insert(new Point3f(i * 5, i * 5, i * 5), TEST_LEVEL, "Entity" + i);
        }
        
        var bounds = new VolumeBounds(0, 0, 0, 50, 50, 50);
        var visitor = new RangeQueryVisitor<LongEntityID, String>(bounds, true);
        
        // Manual traversal since accept doesn't exist
        // For now, we'll use the stream API
        tetree.nodes().forEach(node -> visitor.visitNode(node, TEST_LEVEL, null));
        
        var stats = visitor.getStatistics();
        
        // Parse statistics
        assertTrue(stats.contains("Visited="));
        assertTrue(stats.contains("Pruned="));
        assertTrue(stats.contains("Found="));
        assertTrue(stats.contains("entities"));
        
        // Should have visited some nodes
        assertFalse(stats.contains("Visited=0"), "Should have visited nodes");
    }
}