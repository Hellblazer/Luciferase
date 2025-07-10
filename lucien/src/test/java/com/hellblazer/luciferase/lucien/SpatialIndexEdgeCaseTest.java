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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for edge cases in spatial index operations
 * 
 * @author hal.hildebrand
 */
public class SpatialIndexEdgeCaseTest {
    
    static Stream<SpatialIndex<?, LongEntityID, String>> spatialIndexProvider() {
        // Create indices with spanning enabled for tests that require it
        var spanningPolicy = EntitySpanningPolicy.withSpanning();
        return Stream.of(
            new Octree<>(new SequentialLongIDGenerator(), 1000, (byte) 20, spanningPolicy),
            new Tetree<>(new SequentialLongIDGenerator(), 1000, (byte) 20, spanningPolicy)
        );
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testSingleEntityInEmptyIndex(SpatialIndex<?, LongEntityID, String> index) {
        // Test operations on index with single entity
        var position = new Point3f(100, 100, 100);
        var id = index.insert(position, (byte) 10, "test");
        
        // Verify entity exists
        assertTrue(index.containsEntity(id));
        assertEquals("test", index.getEntity(id));
        assertEquals(position, index.getEntityPosition(id));
        
        // Test k-NN with single entity
        var neighbors = index.kNearestNeighbors(new Point3f(110, 110, 110), 5, 1000);
        assertEquals(1, neighbors.size());
        assertEquals(id, neighbors.get(0));
        
        // Test range query
        var inRegion = index.entitiesInRegion(new Spatial.Cube(0, 0, 0, 1000));
        assertEquals(1, inRegion.size());
        assertEquals(id, inRegion.get(0));
        
        // Test removal
        assertTrue(index.removeEntity(id));
        assertFalse(index.containsEntity(id));
        assertEquals(0, index.entityCount());
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testIdenticalPositions(SpatialIndex<?, LongEntityID, String> index) {
        // Insert multiple entities at exact same position
        var position = new Point3f(500, 500, 500);
        byte level = 15;
        
        var ids = new ArrayList<LongEntityID>();
        for (int i = 0; i < 100; i++) {
            ids.add(index.insert(position, level, "entity" + i));
        }
        
        // All entities should exist
        assertEquals(100, index.entityCount());
        
        // Lookup should return all entities
        var found = index.lookup(position, level);
        assertEquals(100, found.size());
        assertTrue(found.containsAll(ids));
        
        // k-NN should handle ties correctly
        var neighbors = index.kNearestNeighbors(position, 10, 100);
        assertEquals(10, neighbors.size());
        
        // Remove half the entities
        for (int i = 0; i < 50; i++) {
            assertTrue(index.removeEntity(ids.get(i)));
        }
        
        assertEquals(50, index.entityCount());
        found = index.lookup(position, level);
        assertEquals(50, found.size());
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testBoundaryValues(SpatialIndex<?, LongEntityID, String> index) {
        // Test at coordinate system boundaries
        var maxCoord = Constants.MAX_COORD;
        var testCases = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(0, 0, maxCoord),
            new Point3f(0, maxCoord, 0),
            new Point3f(maxCoord, 0, 0),
            new Point3f(maxCoord, maxCoord, maxCoord),
            new Point3f(maxCoord / 2, maxCoord / 2, maxCoord / 2)
        };
        
        var ids = new ArrayList<LongEntityID>();
        for (var pos : testCases) {
            ids.add(index.insert(pos, (byte) 10, "boundary"));
        }
        
        // All should be retrievable
        assertEquals(testCases.length, index.entityCount());
        for (int i = 0; i < testCases.length; i++) {
            assertEquals(testCases[i], index.getEntityPosition(ids.get(i)));
        }
        
        // Test range query covering entire space
        var all = index.entitiesInRegion(new Spatial.Cube(0, 0, 0, Constants.MAX_COORD));
        assertEquals(testCases.length, all.size());
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testZeroSizedBounds(SpatialIndex<?, LongEntityID, String> index) {
        // Test entities with zero-sized bounds
        var position = new Point3f(100, 100, 100);
        var bounds = EntityBounds.point(position);
        
        var id = new LongEntityID(1);
        index.insert(id, position, (byte) 10, "point", bounds);
        
        // Should still be retrievable
        assertTrue(index.containsEntity(id));
        assertEquals(position, index.getEntityPosition(id));
        assertEquals(bounds, index.getEntityBounds(id));
        
        // Should not span multiple nodes
        assertEquals(1, index.getEntitySpanCount(id));
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testMaxLevelOperations(SpatialIndex<?, LongEntityID, String> index) {
        // Test at maximum refinement level
        // Note: While MortonCurve supports level 21, spatial index implementation limits to level 20
        byte maxLevel = 20;
        var position = new Point3f(100.5f, 100.5f, 100.5f);
        
        var id = index.insert(position, maxLevel, "maxLevel");
        
        // Should work normally
        assertTrue(index.containsEntity(id));
        assertEquals("maxLevel", index.getEntity(id));
        
        // Test at minimum level
        var id2 = index.insert(new Point3f(200, 200, 200), (byte) 0, "minLevel");
        assertTrue(index.containsEntity(id2));
        
        // Both should exist
        assertEquals(2, index.entityCount());
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testEmptyOperations(SpatialIndex<?, LongEntityID, String> index) {
        // Test operations on empty index
        assertEquals(0, index.entityCount());
        assertEquals(0, index.nodeCount());
        
        // k-NN on empty index
        var neighbors = index.kNearestNeighbors(new Point3f(100, 100, 100), 10, 1000);
        assertTrue(neighbors.isEmpty());
        
        // Range query on empty index
        var inRegion = index.entitiesInRegion(new Spatial.Cube(0, 0, 0, 1000));
        assertTrue(inRegion.isEmpty());
        
        // Ray intersection on empty index
        var ray = new Ray3D(new Point3f(0, 0, 0), new javax.vecmath.Vector3f(1, 0, 0));
        var intersections = index.rayIntersectAll(ray);
        assertTrue(intersections.isEmpty());
        
        // Remove non-existent entity
        assertFalse(index.removeEntity(new LongEntityID(999)));
        
        // Get non-existent entity
        assertNull(index.getEntity(new LongEntityID(999)));
        assertNull(index.getEntityPosition(new LongEntityID(999)));
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testLargeSpanningEntity(SpatialIndex<?, LongEntityID, String> index) {
        // Entity that spans entire space
        var maxCoord = Constants.MAX_COORD;
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(maxCoord, maxCoord, maxCoord));
        var position = new Point3f(maxCoord / 2, maxCoord / 2, maxCoord / 2);
        
        var id = new LongEntityID(1);
        index.insert(id, position, (byte) 5, "huge", bounds);
        
        // Should span multiple nodes
        assertTrue(index.getEntitySpanCount(id) > 1);
        
        // Should be found in range queries anywhere
        var corner = index.entitiesInRegion(new Spatial.Cube(0, 0, 0, 100));
        assertTrue(corner.contains(id));
        
        var opposite = index.entitiesInRegion(
            new Spatial.Cube(Constants.MAX_COORD - 100, Constants.MAX_COORD - 100, Constants.MAX_COORD - 100, 100)
        );
        assertTrue(opposite.contains(id));
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testUpdateToSamePosition(SpatialIndex<?, LongEntityID, String> index) {
        // Insert entity
        var position = new Point3f(100, 100, 100);
        var id = index.insert(position, (byte) 10, "test");
        
        // Update to same position
        index.updateEntity(id, position, (byte) 10);
        
        // Should still exist
        assertTrue(index.containsEntity(id));
        assertEquals(position, index.getEntityPosition(id));
        assertEquals("test", index.getEntity(id));
        assertEquals(1, index.entityCount());
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testBatchInsertWithDuplicates(SpatialIndex<?, LongEntityID, String> index) {
        // Batch insert with some duplicate positions
        var positions = List.of(
            new Point3f(100, 100, 100),
            new Point3f(100, 100, 100), // duplicate
            new Point3f(200, 200, 200),
            new Point3f(100, 100, 100), // duplicate
            new Point3f(300, 300, 300)
        );
        
        var contents = List.of("A", "B", "C", "D", "E");
        
        var ids = index.insertBatch(positions, contents, (byte) 10);
        
        // All should be inserted
        assertEquals(5, ids.size());
        assertEquals(5, index.entityCount());
        
        // Check duplicates are at same position
        assertEquals(index.getEntityPosition(ids.get(0)), index.getEntityPosition(ids.get(1)));
        assertEquals(index.getEntityPosition(ids.get(0)), index.getEntityPosition(ids.get(3)));
        
        // All should have correct content
        for (int i = 0; i < ids.size(); i++) {
            assertEquals(contents.get(i), index.getEntity(ids.get(i)));
        }
    }
    
    @ParameterizedTest
    @MethodSource("spatialIndexProvider")
    void testRayIntersectionAtNodeBoundaries(SpatialIndex<?, LongEntityID, String> index) {
        // Place entities at node boundaries
        float cellSize = Constants.lengthAtLevel((byte) 10);
        var positions = new ArrayList<Point3f>();
        
        // Create grid of entities at cell boundaries
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                positions.add(new Point3f(i * cellSize, j * cellSize, 500));
            }
        }
        
        var contents = new ArrayList<String>();
        for (int i = 0; i < positions.size(); i++) {
            contents.add("entity" + i);
        }
        
        index.insertBatch(positions, contents, (byte) 10);
        
        // Ray along node boundary
        var ray = new Ray3D(new Point3f(cellSize, cellSize, 500), new javax.vecmath.Vector3f(1, 0, 0));
        var intersections = index.rayIntersectAll(ray);
        
        // Should find entities along the ray
        assertFalse(intersections.isEmpty());
    }
}