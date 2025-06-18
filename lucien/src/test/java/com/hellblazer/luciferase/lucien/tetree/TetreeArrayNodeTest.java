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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TetreeArrayNode
 *
 * @author hal.hildebrand
 */
class TetreeArrayNodeTest {

    @Test
    void testBasicOperations() {
        TetreeArrayNode<LongEntityID> node = new TetreeArrayNode<>();
        
        // Test empty node
        assertTrue(node.isEmpty());
        assertEquals(0, node.getEntityCount());
        assertEquals(16, node.getCapacity()); // Default capacity
        
        // Add entities
        LongEntityID id1 = new LongEntityID(1L);
        LongEntityID id2 = new LongEntityID(2L);
        LongEntityID id3 = new LongEntityID(3L);
        
        node.addEntity(id1);
        node.addEntity(id2);
        node.addEntity(id3);
        
        assertEquals(3, node.getEntityCount());
        assertFalse(node.isEmpty());
        
        // Test contains
        assertTrue(node.containsEntity(id1));
        assertTrue(node.containsEntity(id2));
        assertTrue(node.containsEntity(id3));
        assertFalse(node.containsEntity(new LongEntityID(4L)));
        
        // Test getEntityIds
        Set<LongEntityID> ids = new HashSet<>(node.getEntityIds());
        assertEquals(3, ids.size());
        assertTrue(ids.contains(id1));
        assertTrue(ids.contains(id2));
        assertTrue(ids.contains(id3));
        
        // Test getEntityIdsAsSet
        Set<LongEntityID> idsAsSet = node.getEntityIdsAsSet();
        assertEquals(3, idsAsSet.size());
        assertTrue(idsAsSet.contains(id1));
        assertTrue(idsAsSet.contains(id2));
        assertTrue(idsAsSet.contains(id3));
    }

    @Test
    void testRemoveEntity() {
        TetreeArrayNode<LongEntityID> node = new TetreeArrayNode<>();
        
        LongEntityID id1 = new LongEntityID(1L);
        LongEntityID id2 = new LongEntityID(2L);
        LongEntityID id3 = new LongEntityID(3L);
        
        node.addEntity(id1);
        node.addEntity(id2);
        node.addEntity(id3);
        
        // Remove middle entity
        assertTrue(node.removeEntity(id2));
        assertEquals(2, node.getEntityCount());
        assertFalse(node.containsEntity(id2));
        assertTrue(node.containsEntity(id1));
        assertTrue(node.containsEntity(id3));
        
        // Remove non-existent entity
        assertFalse(node.removeEntity(new LongEntityID(4L)));
        assertEquals(2, node.getEntityCount());
        
        // Remove remaining entities
        assertTrue(node.removeEntity(id1));
        assertTrue(node.removeEntity(id3));
        assertTrue(node.isEmpty());
    }

    @Test
    void testArrayGrowth() {
        TetreeArrayNode<LongEntityID> node = new TetreeArrayNode<>(10, 4); // Small initial capacity
        
        assertEquals(4, node.getCapacity());
        
        // Add entities to trigger growth
        for (long i = 1; i <= 10; i++) {
            node.addEntity(new LongEntityID(i));
        }
        
        // Capacity should have grown
        assertTrue(node.getCapacity() > 4);
        assertEquals(10, node.getEntityCount());
        
        // Verify all entities are still present
        for (long i = 1; i <= 10; i++) {
            assertTrue(node.containsEntity(new LongEntityID(i)));
        }
    }

    @Test
    void testDuplicateAdd() {
        TetreeArrayNode<LongEntityID> node = new TetreeArrayNode<>();
        
        LongEntityID id1 = new LongEntityID(1L);
        
        node.addEntity(id1);
        assertEquals(1, node.getEntityCount());
        
        // Add same entity again - should not duplicate
        node.addEntity(id1);
        assertEquals(1, node.getEntityCount());
    }

    @Test
    void testClearEntities() {
        TetreeArrayNode<LongEntityID> node = new TetreeArrayNode<>();
        
        // Add entities
        for (long i = 1; i <= 5; i++) {
            node.addEntity(new LongEntityID(i));
        }
        
        assertEquals(5, node.getEntityCount());
        
        // Clear
        node.clearEntities();
        assertEquals(0, node.getEntityCount());
        assertTrue(node.isEmpty());
        
        // Verify entities are gone
        for (long i = 1; i <= 5; i++) {
            assertFalse(node.containsEntity(new LongEntityID(i)));
        }
    }

    @Test
    void testCompaction() {
        TetreeArrayNode<LongEntityID> node = new TetreeArrayNode<>(10, 20);
        
        assertEquals(20, node.getCapacity());
        
        // Add a few entities
        node.addEntity(new LongEntityID(1L));
        node.addEntity(new LongEntityID(2L));
        
        // Compact
        node.compact();
        assertEquals(2, node.getCapacity());
        assertEquals(2, node.getEntityCount());
        
        // Verify entities still present
        assertTrue(node.containsEntity(new LongEntityID(1L)));
        assertTrue(node.containsEntity(new LongEntityID(2L)));
    }

    @Test
    void testFillRatio() {
        TetreeArrayNode<LongEntityID> node = new TetreeArrayNode<>(10, 10);
        
        assertEquals(0.0f, node.getFillRatio());
        
        // Add 5 entities to 10-capacity array
        for (long i = 1; i <= 5; i++) {
            node.addEntity(new LongEntityID(i));
        }
        
        assertEquals(0.5f, node.getFillRatio());
        
        // Fill completely
        for (long i = 6; i <= 10; i++) {
            node.addEntity(new LongEntityID(i));
        }
        
        assertEquals(1.0f, node.getFillRatio());
    }

    @Test
    void testHasChildren() {
        TetreeArrayNode<LongEntityID> node = new TetreeArrayNode<>();
        
        assertFalse(node.hasChildren());
        
        node.setHasChildren(true);
        assertTrue(node.hasChildren());
        
        node.setHasChildren(false);
        assertFalse(node.hasChildren());
    }

    @Test
    void testShouldSplit() {
        TetreeArrayNode<LongEntityID> node = new TetreeArrayNode<>(3); // Split after 3 entities
        
        assertFalse(node.shouldSplit());
        
        node.addEntity(new LongEntityID(1L));
        assertFalse(node.shouldSplit());
        
        node.addEntity(new LongEntityID(2L));
        assertFalse(node.shouldSplit());
        
        node.addEntity(new LongEntityID(3L));
        assertFalse(node.shouldSplit()); // At threshold, not over
        
        node.addEntity(new LongEntityID(4L));
        assertTrue(node.shouldSplit()); // Over threshold
    }
}