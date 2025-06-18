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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TetreeAdaptiveNode
 *
 * @author hal.hildebrand
 */
class TetreeAdaptiveNodeTest {

    @Test
    void testAdaptiveStorageSwitching() {
        // Configure to switch to array at 5 entities
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .arrayThreshold(5)
            .arrayInitialCapacity(8)
            .build();
            
        TetreeAdaptiveNode<LongEntityID> node = new TetreeAdaptiveNode<>(10, config);
        
        // Should start with Set storage
        assertEquals("Set", node.getStorageType());
        
        // Add entities up to threshold
        for (long i = 1; i <= 4; i++) {
            node.addEntity(new LongEntityID(i));
        }
        assertEquals("Set", node.getStorageType());
        assertEquals(4, node.getEntityCount());
        
        // Add one more to trigger switch
        node.addEntity(new LongEntityID(5L));
        assertEquals("Array", node.getStorageType());
        assertEquals(5, node.getEntityCount());
        
        // Verify all entities still present after switch
        for (long i = 1; i <= 5; i++) {
            assertTrue(node.containsEntity(new LongEntityID(i)));
        }
    }

    @Test
    void testAlwaysUseArrayNodes() {
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .alwaysUseArrayNodes(true)
            .arrayInitialCapacity(10)
            .build();
            
        TetreeAdaptiveNode<LongEntityID> node = new TetreeAdaptiveNode<>(10, config);
        
        // Should start with Array storage
        assertEquals("Array", node.getStorageType());
        
        // Add entities
        node.addEntity(new LongEntityID(1L));
        assertEquals("Array", node.getStorageType());
        
        // Clear and still should be array
        node.clearEntities();
        assertEquals("Array", node.getStorageType());
    }

    @Test
    void testSwitchBackToSet() {
        // Configure to switch at 10, switch back at 5
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .arrayThreshold(10)
            .build();
            
        TetreeAdaptiveNode<LongEntityID> node = new TetreeAdaptiveNode<>(20, config);
        
        // Add entities to trigger array mode
        for (long i = 1; i <= 10; i++) {
            node.addEntity(new LongEntityID(i));
        }
        assertEquals("Array", node.getStorageType());
        
        // Remove entities to drop below threshold/2
        for (long i = 10; i > 5; i--) {
            node.removeEntity(new LongEntityID(i));
        }
        
        // Should switch back to Set when below threshold/2
        node.removeEntity(new LongEntityID(5L));
        assertEquals("Set", node.getStorageType());
        assertEquals(4, node.getEntityCount());
        
        // Verify remaining entities
        for (long i = 1; i <= 4; i++) {
            assertTrue(node.containsEntity(new LongEntityID(i)));
        }
    }

    @Test
    void testStorageStats() {
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .arrayThreshold(5)
            .arrayInitialCapacity(10)
            .build();
            
        TetreeAdaptiveNode<LongEntityID> node = new TetreeAdaptiveNode<>(10, config);
        
        // Test Set stats
        node.addEntity(new LongEntityID(1L));
        TetreeAdaptiveNode.StorageStats stats = node.getStorageStats();
        assertEquals("Set", stats.type());
        assertEquals(1, stats.entityCount());
        assertEquals(1, stats.capacity());
        assertEquals(1.0f, stats.fillRatio());
        
        // Switch to array and test stats
        for (long i = 2; i <= 5; i++) {
            node.addEntity(new LongEntityID(i));
        }
        
        stats = node.getStorageStats();
        assertEquals("Array", stats.type());
        assertEquals(5, stats.entityCount());
        assertEquals(10, stats.capacity()); // Initial capacity
        assertEquals(0.5f, stats.fillRatio());
    }

    @Test
    void testCompactionOnRemove() {
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .alwaysUseArrayNodes(true)
            .arrayInitialCapacity(20)
            .enableNodeCompaction(true)
            .compactionThreshold(0.3f) // Compact when fill ratio < 30%
            .build();
            
        TetreeAdaptiveNode<LongEntityID> node = new TetreeAdaptiveNode<>(50, config);
        
        // Add entities
        for (long i = 1; i <= 10; i++) {
            node.addEntity(new LongEntityID(i));
        }
        
        TetreeAdaptiveNode.StorageStats stats = node.getStorageStats();
        assertEquals(20, stats.capacity());
        assertEquals(0.5f, stats.fillRatio());
        
        // Remove entities to trigger compaction
        for (long i = 10; i > 5; i--) {
            node.removeEntity(new LongEntityID(i));
        }
        
        stats = node.getStorageStats();
        assertEquals(5, stats.entityCount());
        // Capacity should be compacted since fill ratio dropped below 0.3
        assertTrue(stats.capacity() < 20);
    }

    @Test
    void testDisabledArrayNodes() {
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .useArrayNodes(false)
            .build();
            
        TetreeAdaptiveNode<LongEntityID> node = new TetreeAdaptiveNode<>(10, config);
        
        // Should always use Set
        assertEquals("Set", node.getStorageType());
        
        // Add many entities
        for (long i = 1; i <= 50; i++) {
            node.addEntity(new LongEntityID(i));
        }
        
        // Still should be Set
        assertEquals("Set", node.getStorageType());
        assertEquals(50, node.getEntityCount());
    }

    @Test
    void testGetEntityIdsAsSet() {
        TetreeConfiguration config = new TetreeConfiguration.Builder()
            .arrayThreshold(3)
            .build();
            
        TetreeAdaptiveNode<LongEntityID> node = new TetreeAdaptiveNode<>(10, config);
        
        // Test with Set storage
        node.addEntity(new LongEntityID(1L));
        node.addEntity(new LongEntityID(2L));
        
        var setIds = node.getEntityIdsAsSet();
        assertEquals(2, setIds.size());
        assertTrue(setIds.contains(new LongEntityID(1L)));
        assertTrue(setIds.contains(new LongEntityID(2L)));
        
        // Switch to array and test again
        node.addEntity(new LongEntityID(3L));
        node.addEntity(new LongEntityID(4L));
        assertEquals("Array", node.getStorageType());
        
        setIds = node.getEntityIdsAsSet();
        assertEquals(4, setIds.size());
        for (long i = 1; i <= 4; i++) {
            assertTrue(setIds.contains(new LongEntityID(i)));
        }
    }
}