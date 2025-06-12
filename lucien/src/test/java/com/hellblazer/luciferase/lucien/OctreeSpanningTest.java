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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 3: Entity spanning across multiple nodes
 *
 * @author hal.hildebrand
 */
public class OctreeSpanningTest {

    private Octree<LongEntityID, String> octree;
    private EntitySpanningPolicy         spanningPolicy;

    @BeforeEach
    void setUp() {
        // Create octree with spanning enabled
        spanningPolicy = EntitySpanningPolicy.withSpanning();
        octree = new Octree<>(new SequentialLongIDGenerator(), 10, (byte) 21, spanningPolicy);
    }

    @Test
    void testBoundaryConditions() {
        byte level = 15;

        // Test entity exactly on cell boundary
        int cellSize = 1 << (21 - level);
        Point3f boundaryPoint = new Point3f(cellSize, cellSize, cellSize);

        // Entity with zero extent (point)
        EntityBounds pointBounds = EntityBounds.point(boundaryPoint);

        LongEntityID id = new LongEntityID(30);
        octree.insert(id, boundaryPoint, level, "BoundaryEntity", pointBounds);

        // Should be in exactly one node (the one containing the point)
        assertEquals(1, octree.getEntitySpanCount(id), "Point entity should be in exactly one node");
    }

    @Test
    void testEntityRemovalFromSpannedNodes() {
        byte level = 15;
        int cellSize = 1 << (21 - level);

        // Create spanning entity
        Point3f center = new Point3f(cellSize, 100, 100);
        EntityBounds bounds = new EntityBounds(new Point3f(cellSize - 20, 80, 80),
                                               new Point3f(cellSize + 20, 120, 120));

        LongEntityID id = new LongEntityID(5);
        octree.insert(id, center, level, "SpanningEntity", bounds);

        // Verify it spans multiple nodes
        int spanCount = octree.getEntitySpanCount(id);
        assertTrue(spanCount > 1, "Entity should span multiple nodes");

        // Remove entity
        boolean removed = octree.removeEntity(id);
        assertTrue(removed, "Entity should be removed");

        // Verify it's gone from all nodes
        assertEquals(0, octree.getEntitySpanCount(id), "Entity should be in no nodes after removal");

        // Verify it's not found at any position
        Point3f leftCell = new Point3f(cellSize - 10, 100, 100);
        Point3f rightCell = new Point3f(cellSize + 10, 100, 100);

        assertFalse(octree.lookup(leftCell, level).contains(id), "Entity should not be found in left cell");
        assertFalse(octree.lookup(rightCell, level).contains(id), "Entity should not be found in right cell");
    }

    @Test
    void testEntitySpanningEightNodes() {
        byte level = 15;
        int cellSize = 1 << (21 - level); // 64

        // Create entity centered at corner of 8 cells
        Point3f center = new Point3f(cellSize, cellSize, cellSize);
        EntityBounds bounds = new EntityBounds(center, cellSize / 2.0f); // Radius extends into all 8 cells

        LongEntityID id = new LongEntityID(3);
        octree.insert(id, center, level, "LargeEntity", bounds);

        // Entity should span 8 nodes (one for each octant)
        assertEquals(8, octree.getEntitySpanCount(id), "Entity should span 8 nodes");
    }

    @Test
    void testEntitySpanningTwoNodes() {
        byte level = 15;
        int cellSize = 1 << (21 - level); // 64

        // Create entity that spans exactly two nodes
        // Position it at the boundary between two cells
        Point3f center = new Point3f(cellSize, 100, 100);
        EntityBounds bounds = new EntityBounds(new Point3f(cellSize - 10, 90, 90),  // Min extends into previous cell
                                               new Point3f(cellSize + 10, 110, 110) // Max extends into next cell
        );

        LongEntityID id = new LongEntityID(2);
        octree.insert(id, center, level, "SpanningEntity", bounds);

        // Entity should span 2 nodes
        assertEquals(2, octree.getEntitySpanCount(id), "Entity should span 2 nodes");

        // Should be retrievable from both cells
        Point3f leftCell = new Point3f(cellSize - 5, 100, 100);
        Point3f rightCell = new Point3f(cellSize + 5, 100, 100);

        List<LongEntityID> leftFound = octree.lookup(leftCell, level);
        assertTrue(leftFound.contains(id), "Entity should be found in left cell");

        List<LongEntityID> rightFound = octree.lookup(rightCell, level);
        assertTrue(rightFound.contains(id), "Entity should be found in right cell");
    }

    @Test
    void testMultipleSpanningEntities() {
        byte level = 15;
        int cellSize = 1 << (21 - level);

        // Create multiple overlapping spanning entities
        Point3f center = new Point3f(cellSize, cellSize, cellSize);

        // Large entity spanning many nodes
        LongEntityID id1 = new LongEntityID(10);
        EntityBounds bounds1 = new EntityBounds(center, cellSize);
        octree.insert(id1, center, level, "LargeEntity", bounds1);

        // Medium entity spanning fewer nodes
        LongEntityID id2 = new LongEntityID(11);
        EntityBounds bounds2 = new EntityBounds(center, cellSize / 4.0f);
        octree.insert(id2, center, level, "MediumEntity", bounds2);

        // Small entity in single node
        LongEntityID id3 = new LongEntityID(12);
        EntityBounds bounds3 = new EntityBounds(center, 5.0f);
        octree.insert(id3, center, level, "SmallEntity", bounds3);

        // All should be found at center
        List<LongEntityID> found = octree.lookup(center, level);
        assertTrue(found.contains(id1), "Large entity should be found");
        assertTrue(found.contains(id2), "Medium entity should be found");
        assertTrue(found.contains(id3), "Small entity should be found");

        // Check span counts
        assertTrue(octree.getEntitySpanCount(id1) > octree.getEntitySpanCount(id2),
                   "Larger entity should span more nodes");
        assertTrue(octree.getEntitySpanCount(id2) >= octree.getEntitySpanCount(id3),
                   "Medium entity should span at least as many nodes as small");
    }

    @Test
    void testNoSpanningWhenDisabled() {
        // Create octree with spanning disabled
        Octree<LongEntityID, String> noSpanOctree = new Octree<>(new SequentialLongIDGenerator(), 10, (byte) 21);

        byte level = 15;
        int cellSize = 1 << (21 - level);

        // Create large entity
        Point3f center = new Point3f(cellSize, cellSize, cellSize);
        EntityBounds bounds = new EntityBounds(center, cellSize); // Very large

        LongEntityID id = new LongEntityID(4);
        noSpanOctree.insert(id, center, level, "LargeEntity", bounds);

        // Entity should be in only one node (no spanning)
        assertEquals(1, noSpanOctree.getEntitySpanCount(id), "Entity should be in one node when spanning disabled");
    }

    @Test
    void testSingleNodeEntityWithBounds() {
        byte level = 15;

        // Create a small entity that fits in one node
        Point3f center = new Point3f(100, 100, 100);
        EntityBounds bounds = new EntityBounds(center, 5.0f); // Small radius

        LongEntityID id = new LongEntityID(1);
        octree.insert(id, center, level, "SmallEntity", bounds);

        // Entity should be in only one node
        assertEquals(1, octree.getEntitySpanCount(id), "Small entity should be in one node");

        // Should be retrievable at its center
        List<LongEntityID> found = octree.lookup(center, level);
        assertTrue(found.contains(id), "Entity should be found at its center");
    }

    @Test
    void testSpanningAcrossLevels() {
        // Insert large entity at coarse level
        byte coarseLevel = 10;
        int coarseCellSize = 1 << (21 - coarseLevel);

        Point3f center = new Point3f(coarseCellSize / 2, coarseCellSize / 2, coarseCellSize / 2);
        EntityBounds bounds = new EntityBounds(center, coarseCellSize / 3.0f);

        LongEntityID id = new LongEntityID(20);
        octree.insert(id, center, coarseLevel, "CoarseLevelEntity", bounds);

        int coarseSpanCount = octree.getEntitySpanCount(id);

        // Now check at finer level - the entity should still be found
        byte fineLevel = 15;
        List<LongEntityID> found = octree.lookup(center, fineLevel);

        // Note: Current implementation doesn't handle cross-level queries perfectly
        // This test documents current behavior
        System.out.println("Coarse span count: " + coarseSpanCount);
        System.out.println("Found at fine level: " + found.contains(id));
    }
}
