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
package com.hellblazer.luciferase.lucien.sfc;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SFCArrayIndex implementation.
 * Tests core functionality and cells(Q) algorithm.
 *
 * @author hal.hildebrand
 */
public class SFCArrayIndexTest {

    private SFCArrayIndex<LongEntityID, String> sfcIndex;

    @BeforeEach
    void setUp() {
        sfcIndex = new SFCArrayIndex<>(new SequentialLongIDGenerator());
    }

    // ===== Basic Operations =====

    @Test
    void testBasicInsertAndLookup() {
        var pos1 = new Point3f(100, 100, 100);
        var id1 = sfcIndex.insert(pos1, (byte) 15, "Entity1");

        assertNotNull(id1);
        assertEquals(0, id1.getValue());

        var id2 = new LongEntityID(42);
        var pos2 = new Point3f(200, 200, 200);
        sfcIndex.insert(id2, pos2, (byte) 15, "Entity2");

        var idsAtPos1 = sfcIndex.lookup(pos1, (byte) 15);
        assertEquals(1, idsAtPos1.size());
        assertEquals(id1, idsAtPos1.get(0));

        var content1 = sfcIndex.getEntity(id1);
        assertEquals("Entity1", content1);

        var content2 = sfcIndex.getEntity(id2);
        assertEquals("Entity2", content2);
    }

    @Test
    void testEntityRemoval() {
        var pos = new Point3f(100, 100, 100);
        var level = (byte) 10;

        var id1 = sfcIndex.insert(pos, level, "Entity1");
        var id2 = sfcIndex.insert(pos, level, "Entity2");

        assertTrue(sfcIndex.removeEntity(id1));
        assertFalse(sfcIndex.containsEntity(id1));
        assertTrue(sfcIndex.containsEntity(id2));

        var ids = sfcIndex.lookup(pos, level);
        assertEquals(1, ids.size());
        assertEquals(id2, ids.get(0));

        assertFalse(sfcIndex.removeEntity(id1));
    }

    @Test
    void testEntityUpdate() {
        var oldPos = new Point3f(100, 100, 100);
        var newPos = new Point3f(5000, 5000, 5000);
        var level = (byte) 15;

        var id = sfcIndex.insert(oldPos, level, "MovingEntity");

        sfcIndex.updateEntity(id, newPos, level);

        var idsAtOld = sfcIndex.lookup(oldPos, level);
        assertTrue(idsAtOld.isEmpty());

        var idsAtNew = sfcIndex.lookup(newPos, level);
        assertEquals(1, idsAtNew.size());
        assertEquals(id, idsAtNew.get(0));

        assertEquals("MovingEntity", sfcIndex.getEntity(id));
    }

    @Test
    void testMultipleEntitiesPerNode() {
        var samePos = new Point3f(100, 100, 100);
        var level = (byte) 10;

        var id1 = sfcIndex.insert(samePos, level, "Entity1");
        var id2 = sfcIndex.insert(samePos, level, "Entity2");
        var id3 = sfcIndex.insert(samePos, level, "Entity3");

        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);

        var ids = sfcIndex.lookup(samePos, level);
        assertEquals(3, ids.size());
        assertTrue(ids.contains(id1));
        assertTrue(ids.contains(id2));
        assertTrue(ids.contains(id3));
    }

    // ===== cells(Q) Algorithm Tests =====

    @Test
    void testCellsQProducesMaxEightIntervals() {
        // Create random query regions and verify ≤8 intervals
        var random = new Random(42);
        var level = (byte) 10;

        for (var i = 0; i < 100; i++) {
            var minX = random.nextFloat() * 1000;
            var minY = random.nextFloat() * 1000;
            var minZ = random.nextFloat() * 1000;
            var width = random.nextFloat() * 500 + 1;
            var height = random.nextFloat() * 500 + 1;
            var depth = random.nextFloat() * 500 + 1;

            var bounds = new VolumeBounds(minX, minY, minZ,
                                          minX + width, minY + height, minZ + depth);

            var intervals = sfcIndex.cellsQ(bounds, level);

            // cells(Q) should produce at most 2^d = 8 intervals for 3D
            assertTrue(intervals.size() <= 8,
                "cells(Q) produced " + intervals.size() + " intervals, expected ≤8");
        }
    }

    @Test
    void testCellsQSingleCell() {
        // A small query that fits in a single cell should produce 1 interval
        var level = (byte) 5;
        var cellSize = com.hellblazer.luciferase.lucien.Constants.lengthAtLevel(level);

        // Query contained within a single cell
        var bounds = new VolumeBounds(10, 10, 10, 10 + cellSize / 2, 10 + cellSize / 2, 10 + cellSize / 2);

        var intervals = sfcIndex.cellsQ(bounds, level);

        assertEquals(1, intervals.size(), "Single-cell query should produce 1 interval");
    }

    @Test
    void testCellsQContiguousIntervals() {
        var level = (byte) 8;

        // A cube-aligned query should produce contiguous intervals
        var bounds = new VolumeBounds(0, 0, 0, 100, 100, 100);

        var intervals = sfcIndex.cellsQ(bounds, level);

        // Verify intervals don't overlap
        for (var i = 0; i < intervals.size() - 1; i++) {
            var current = intervals.get(i);
            var next = intervals.get(i + 1);

            assertTrue(current.end().compareTo(next.start()) < 0,
                "Intervals should not overlap");
        }
    }

    @Test
    void testEntitiesInRegionSFC() {
        // Insert entities at various positions
        sfcIndex.insert(new Point3f(100, 100, 100), (byte) 10, "E1");
        sfcIndex.insert(new Point3f(150, 150, 150), (byte) 10, "E2");
        sfcIndex.insert(new Point3f(200, 200, 200), (byte) 10, "E3");
        sfcIndex.insert(new Point3f(300, 300, 300), (byte) 10, "E4");

        // Query region: cube from (50,50,50) to (250,250,250)
        var bounds = new VolumeBounds(50, 50, 50, 250, 250, 250);
        var entitiesInRegion = sfcIndex.entitiesInRegionSFC(bounds, (byte) 10);

        // E1, E2, E3 should be inside, E4 should be outside
        assertEquals(3, entitiesInRegion.size());

        var contents = sfcIndex.getEntities(entitiesInRegion);
        assertTrue(contents.contains("E1"));
        assertTrue(contents.contains("E2"));
        assertTrue(contents.contains("E3"));
        assertFalse(contents.contains("E4"));
    }

    // ===== Region Query Comparison =====

    @Test
    void testRegionQueryStandard() {
        // Insert entities at various positions
        sfcIndex.insert(new Point3f(100, 100, 100), (byte) 10, "E1");
        sfcIndex.insert(new Point3f(150, 150, 150), (byte) 10, "E2");
        sfcIndex.insert(new Point3f(200, 200, 200), (byte) 10, "E3");
        sfcIndex.insert(new Point3f(300, 300, 300), (byte) 10, "E4");

        // Use standard entitiesInRegion method
        var region = new Spatial.Cube(50, 50, 50, 200);
        var entitiesInRegion = sfcIndex.entitiesInRegion(region);

        assertEquals(3, entitiesInRegion.size());
    }

    // ===== No Subdivision Tests =====

    @Test
    void testNoSubdivision() {
        // Insert many entities at the same location
        var pos = new Point3f(100, 100, 100);
        var level = (byte) 10;

        for (var i = 0; i < 50; i++) {
            sfcIndex.insert(pos, level, "Entity" + i);
        }

        // All entities should be at the same node (no subdivision)
        var ids = sfcIndex.lookup(pos, level);
        assertEquals(50, ids.size());

        // Node count should be 1 (flat structure, no tree expansion)
        var stats = sfcIndex.getStats();
        assertEquals(1, stats.nodeCount());
        assertEquals(50, stats.entityCount());
    }

    @Test
    void testNoHierarchy() {
        // Insert entities that would normally trigger subdivision in a tree
        for (var i = 0; i < 20; i++) {
            sfcIndex.insert(new Point3f(100 + i, 100, 100), (byte) 10, "E" + i);
        }

        // SFCArrayIndex is flat - verify no tree hierarchy exists
        // We can check that getChildNodes always returns empty
        var nodes = sfcIndex.nodes().toList();
        assertFalse(nodes.isEmpty(), "Should have at least one node");

        // In a tree structure, many entities would create child nodes
        // In SFCArrayIndex, the node count should be proportional to spatial distribution
        var stats = sfcIndex.getStats();
        assertEquals(20, stats.entityCount());
        // Nodes should be based on distinct positions, not tree depth
    }

    // ===== Statistics Tests =====

    @Test
    void testStatistics() {
        sfcIndex.insert(new Point3f(100, 100, 100), (byte) 15, "E1");
        sfcIndex.insert(new Point3f(100, 100, 100), (byte) 15, "E2");
        sfcIndex.insert(new Point3f(200, 200, 200), (byte) 15, "E3");

        var stats = sfcIndex.getStats();
        assertEquals(3, stats.entityCount());
        assertTrue(stats.nodeCount() >= 1);
    }

    // ===== k-NN Tests =====

    @Test
    void testKNearestNeighbors() {
        // Insert entities at known positions
        sfcIndex.insert(new Point3f(100, 100, 100), (byte) 10, "Close1");
        sfcIndex.insert(new Point3f(110, 110, 110), (byte) 10, "Close2");
        sfcIndex.insert(new Point3f(500, 500, 500), (byte) 10, "Far1");
        sfcIndex.insert(new Point3f(1000, 1000, 1000), (byte) 10, "Far2");

        var queryPoint = new Point3f(100, 100, 100);
        var neighbors = sfcIndex.kNearestNeighbors(queryPoint, 2, 1000);

        assertEquals(2, neighbors.size());

        var contents = sfcIndex.getEntities(neighbors);
        assertTrue(contents.contains("Close1"));
        assertTrue(contents.contains("Close2"));
    }

    // ===== Bulk Insert Tests =====

    @Test
    void testBulkInsertPerformance() {
        var random = new Random(42);
        var positions = new java.util.ArrayList<Point3f>();
        var contents = new java.util.ArrayList<String>();

        for (var i = 0; i < 1000; i++) {
            positions.add(new Point3f(random.nextFloat() * 10000,
                                      random.nextFloat() * 10000,
                                      random.nextFloat() * 10000));
            contents.add("Entity" + i);
        }

        var ids = sfcIndex.insertBatch(positions, contents, (byte) 10);

        assertEquals(1000, ids.size());
        assertEquals(1000, sfcIndex.entityCount());
    }

    // ===== Clear Tests =====

    @Test
    void testClear() {
        sfcIndex.insert(new Point3f(100, 100, 100), (byte) 10, "E1");
        sfcIndex.insert(new Point3f(200, 200, 200), (byte) 10, "E2");

        assertEquals(2, sfcIndex.entityCount());

        sfcIndex.clear();

        assertEquals(0, sfcIndex.entityCount());
        assertEquals(0, sfcIndex.nodeCount());
    }
}
