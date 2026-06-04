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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpatialNodePool focusing on the stale-childrenMask recycle bug
 * (Luciferase-7wzml.15): a pooled node released then re-acquired must have childrenMask == 0
 * so that hasChildren()/hasChild() return correct results on the recycled node.
 *
 * @author hal.hildebrand
 */
public class SpatialNodePoolTest {

    private SpatialNodePool<LongEntityID> pool;

    @BeforeEach
    void setUp() {
        pool = new SpatialNodePool<>(
            () -> new SpatialNodeImpl<>(10),
            new SpatialNodePool.PoolConfig()
                .withInitialSize(0)
                .withMaxSize(100)
                .withPreAllocation(false)
                .withStatistics(true)
        );
    }

    /**
     * Core regression test: a node with childrenMask != 0 that is released and
     * re-acquired must report no children and childrenMask == 0.
     */
    @Test
    void acquiredNodeAfterRelease_hasChildrenMaskReset() {
        // Acquire and dirty the node
        SpatialNodeImpl<LongEntityID> node = pool.acquire();
        node.setChildBit(3);
        node.setChildBit(5);
        assertTrue(node.hasChildren(), "precondition: node should have children after setChildBit");
        assertEquals((byte) ((1 << 3) | (1 << 5)), node.getChildrenMask(),
                     "precondition: childrenMask bits 3 and 5 should be set");

        // Also add an entity so clearEntities() is exercised
        node.addEntity(new LongEntityID(42L));
        assertFalse(node.isEmpty(), "precondition: node should have entity");

        // Release back to pool
        pool.release(node);

        // Acquire the same node (pool has exactly one node at this point)
        SpatialNodeImpl<LongEntityID> recycled = pool.acquire();

        // Both childrenMask and entity list must be clean
        assertEquals(0, recycled.getChildrenMask(), "recycled node must have childrenMask == 0");
        assertFalse(recycled.hasChildren(), "recycled node must report hasChildren() == false");
        assertFalse(recycled.hasChild(3), "recycled node must report hasChild(3) == false");
        assertFalse(recycled.hasChild(5), "recycled node must report hasChild(5) == false");
        assertTrue(recycled.isEmpty(), "recycled node must have no entities");
    }

    /**
     * Verify that cleanupEmptyNode-style reuse works correctly: a node whose bits were
     * all cleared via clearChildBit paths still round-trips cleanly through the pool.
     */
    @Test
    void acquiredNodeAfterRelease_allChildBitsCleared_stillSane() {
        SpatialNodeImpl<LongEntityID> node = pool.acquire();
        // Set all 8 bits then clear them individually (simulates cleanupEmptyNode pattern)
        for (int i = 0; i < 8; i++) {
            node.setChildBit(i);
        }
        assertEquals((byte) 0xFF, node.getChildrenMask(), "precondition: all bits set");
        for (int i = 0; i < 8; i++) {
            node.clearChildBit(i);
        }
        assertEquals(0, node.getChildrenMask(), "after clearChildBit all: mask must be 0");

        // Set one bit again to leave dirty state, then pool-cycle
        node.setChildBit(2);
        pool.release(node);

        SpatialNodeImpl<LongEntityID> recycled = pool.acquire();
        assertEquals(0, recycled.getChildrenMask(), "recycled node must have childrenMask == 0 even after partial dirty");
        assertFalse(recycled.hasChildren());
    }

    /**
     * Pool hit counter sanity: verify the pool correctly records a hit on re-acquire
     * after release, confirming node identity (same object) is returned from the pool.
     */
    @Test
    void poolHitCountIncrements_onRecycledAcquire() {
        SpatialNodeImpl<LongEntityID> node = pool.acquire();
        node.setChildBit(0);
        pool.release(node);

        long hitsBefore = pool.getStats().getHits();
        SpatialNodeImpl<LongEntityID> recycled = pool.acquire();
        assertEquals(hitsBefore + 1, pool.getStats().getHits(), "pool should record one hit on recycled acquire");
        assertSame(node, recycled, "pool should return the same node instance");
        assertEquals(0, recycled.getChildrenMask(), "recycled node childrenMask must be 0");
    }
}
