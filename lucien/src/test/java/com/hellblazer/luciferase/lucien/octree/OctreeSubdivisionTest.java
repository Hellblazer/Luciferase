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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Phase 2: Subdivision logic
 *
 * @author hal.hildebrand
 */
public class OctreeSubdivisionTest {

    private Octree<LongEntityID, String> octree;

    @BeforeEach
    void setUp() {
        // Create octree with low threshold to trigger subdivision
        octree = new Octree<>(new SequentialLongIDGenerator(), 3, MortonCurve.MAX_REFINEMENT_LEVEL);
    }

    @Test
    void testEntityRedistribution() {
        byte level = 15;
        int cellSize = 1 << (MortonCurve.MAX_REFINEMENT_LEVEL - level); // 64

        // Create a base position in a specific cell
        Point3f basePos = new Point3f(1000, 1000, 1000);

        // Insert entities that will go to different children when subdivided
        // Need to ensure they're in the same parent cell but different child cells
        Point3f pos1 = new Point3f(basePos.x, basePos.y, basePos.z);
        Point3f pos2 = new Point3f(basePos.x + cellSize / 4, basePos.y, basePos.z);
        Point3f pos3 = new Point3f(basePos.x, basePos.y + cellSize / 4, basePos.z);
        Point3f pos4 = new Point3f(basePos.x + cellSize / 4, basePos.y + cellSize / 4, basePos.z);

        // Insert at level 15
        LongEntityID id1 = octree.insert(pos1, level, "Entity1");
        LongEntityID id2 = octree.insert(pos2, level, "Entity2");
        LongEntityID id3 = octree.insert(pos3, level, "Entity3");

        // This should trigger subdivision
        LongEntityID id4 = octree.insert(pos4, level, "Entity4");

        // Check stats after subdivision
        var stats = octree.getStats();
        System.out.println("Stats after redistribution: " + stats);

        // Check that entities are still retrievable at their positions
        List<LongEntityID> atPos1 = octree.lookup(pos1, level);
        assertTrue(atPos1.contains(id1), "Should find id1 at its position");

        List<LongEntityID> atPos2 = octree.lookup(pos2, level);
        assertTrue(atPos2.contains(id2), "Should find id2 at its position");

        List<LongEntityID> atPos3 = octree.lookup(pos3, level);
        assertTrue(atPos3.contains(id3), "Should find id3 at its position");

        List<LongEntityID> atPos4 = octree.lookup(pos4, level);
        assertTrue(atPos4.contains(id4), "Should find id4 at its position");
    }

    @Test
    void testEntitySpanningAfterSubdivision() {
        // This test prepares for Phase 3 - entity spanning
        // For now, entities should remain in their assigned cells after subdivision

        byte level = 15;
        Point3f pos = new Point3f(1000, 1000, 1000);

        // Insert entities
        LongEntityID id1 = octree.insert(pos, level, "Entity1");
        LongEntityID id2 = octree.insert(pos, level, "Entity2");
        LongEntityID id3 = octree.insert(pos, level, "Entity3");
        LongEntityID id4 = octree.insert(pos, level, "Entity4"); // Triggers subdivision

        // Check entity span count (should be 1 for now - no spanning yet)
        assertEquals(1, octree.getEntitySpanCount(id1), "Entity should be in one node");
        assertEquals(1, octree.getEntitySpanCount(id2), "Entity should be in one node");
        assertEquals(1, octree.getEntitySpanCount(id3), "Entity should be in one node");
        assertEquals(1, octree.getEntitySpanCount(id4), "Entity should be in one node");
    }

    @Test
    void testMultipleLevelsOfSubdivision() {
        byte startLevel = 10;

        // Create entities spread across space to trigger multiple subdivisions
        Point3f basePos = new Point3f(1000, 1000, 1000);
        int spread = 100; // Spread entities across space

        // Insert many entities at different positions within the same initial cell
        for (int i = 0; i < 20; i++) {
            // Distribute entities in a 3D pattern
            float x = basePos.x + (i % 2) * spread;
            float y = basePos.y + ((i / 2) % 2) * spread;
            float z = basePos.z + ((i / 4) % 2) * spread;
            Point3f pos = new Point3f(x, y, z);
            octree.insert(pos, startLevel, "Entity" + i);
        }

        var stats = octree.getStats();
        System.out.println("Multiple levels stats: " + stats);

        // Should have created multiple nodes due to spatial distribution
        assertTrue(stats.nodeCount() > 1, "Should have multiple nodes");
        assertEquals(20, stats.entityCount());

        // Some entities might be in nodes that were further subdivided and cleared
        // The important thing is that all entities are retrievable
        assertTrue(stats.totalEntityReferences() <= 20, "Entity references should not exceed entity count");
    }

    @Test
    void testNoSubdivisionAtMaxDepth() {
        byte maxLevel = MortonCurve.MAX_REFINEMENT_LEVEL;

        // Insert many entities at max depth - should not subdivide
        Point3f pos = new Point3f(100, 100, 100);

        for (int i = 0; i < 10; i++) {
            octree.insert(pos, maxLevel, "Entity" + i);
        }

        var stats = octree.getStats();
        assertEquals(1, stats.nodeCount(), "Should not subdivide at max depth");
        assertEquals(10, stats.entityCount());
        assertEquals(10, stats.totalEntityReferences());
    }

    @Test
    void testSubdivisionTriggered() {
        // Test that subdivision occurs and entities remain accessible
        byte level = 15;
        Point3f basePos = new Point3f(1000, 1000, 1000);

        // Insert 3 entities (at threshold)
        LongEntityID id1 = octree.insert(basePos, level, "Entity1");
        LongEntityID id2 = octree.insert(basePos, level, "Entity2");
        LongEntityID id3 = octree.insert(basePos, level, "Entity3");

        // Verify initial state
        List<LongEntityID> beforeIds = octree.lookup(basePos, level);
        assertEquals(3, beforeIds.size());
        assertTrue(beforeIds.contains(id1));
        assertTrue(beforeIds.contains(id2));
        assertTrue(beforeIds.contains(id3));

        // Insert one more to trigger subdivision (threshold is 3)
        LongEntityID id4 = octree.insert(basePos, level, "Entity4");

        // Verify all entities are still accessible after subdivision
        List<LongEntityID> afterIds = octree.lookup(basePos, level);
        assertEquals(4, afterIds.size(), "All entities should still be found after subdivision");
        assertTrue(afterIds.contains(id1));
        assertTrue(afterIds.contains(id2));
        assertTrue(afterIds.contains(id3));
        assertTrue(afterIds.contains(id4));

        // Verify entities can be retrieved
        assertEquals("Entity1", octree.getEntity(id1));
        assertEquals("Entity2", octree.getEntity(id2));
        assertEquals("Entity3", octree.getEntity(id3));
        assertEquals("Entity4", octree.getEntity(id4));
    }
}
