/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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

import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the on-demand {@code SpatialIndex.subdivide(Key)} API (Luciferase-m27q, 2:1 balance B10c).
 *
 * @author hal.hildebrand
 */
class SpatialIndexSubdivideTest {

    @Test
    void subdivide_distributesEntitiesToChildren() {
        var octree = new Octree<>(new SequentialLongIDGenerator());
        byte level = 10; // level-10 cell spans 2048 units; child split at 1024.

        // Two entities in the SAME level-10 cell but different level-11 child octants along x (split at 1024).
        octree.insert(new Point3f(100, 100, 100), level, "A");
        octree.insert(new Point3f(1100, 100, 100), level, "B");

        assertEquals(1, octree.getNodeCount(), "both entities share one level-10 node before subdivide");
        var key = octree.getSpatialKeys().iterator().next();

        assertTrue(octree.subdivide(key), "subdivide refines the node and creates finer children");
        assertTrue(octree.getNodeCount() > 1, "subdivision created finer child node(s)");
    }

    @Test
    void subdivide_absentKey_isNoOp() {
        var octree = new Octree<>(new SequentialLongIDGenerator());
        var absent = new MortonKey(123_456L, (byte) 10);

        assertFalse(octree.subdivide(absent), "subdivide on an absent key is a no-op returning false");
    }

    @Test
    void subdivide_atMaxDepth_isNoOp() {
        var octree = new Octree<>(new SequentialLongIDGenerator());
        byte maxLevel = com.hellblazer.luciferase.lucien.Constants.getMaxRefinementLevel();

        octree.insert(new Point3f(100, 100, 100), maxLevel, "A");
        var key = octree.getSpatialKeys().iterator().next();

        assertFalse(octree.subdivide(key), "a node already at maximum depth cannot be refined");
    }
}
