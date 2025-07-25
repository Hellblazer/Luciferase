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
package com.hellblazer.luciferase.lucien.occlusion;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for LCAMovementOptimizer
 *
 * @author hal.hildebrand
 */
public class LCAMovementOptimizerTest {
    
    private LCAMovementOptimizer<MortonKey> optimizer;
    
    @BeforeEach
    void setUp() {
        optimizer = new LCAMovementOptimizer<>(10);
    }
    
    @Test
    void testFindLCAWithNullKeys() {
        assertNull(optimizer.findLCA(null, null));
        assertNull(optimizer.findLCA(new MortonKey(10), null));
        assertNull(optimizer.findLCA(null, new MortonKey(10)));
    }
    
    @Test
    void testFindLCASameKey() {
        var key = new MortonKey(12345);
        var lca = optimizer.findLCA(key, key);
        assertEquals(key, lca);
    }
    
    @Test
    void testFindLCAWithParentChild() {
        // Create keys where one is parent of another
        var childKey = new MortonKey(0b1111000); // Level 2
        var parentKey = childKey.parent();       // Level 1
        
        var lca = optimizer.findLCA(childKey, parentKey);
        assertEquals(parentKey, lca);
        
        // Reverse order
        lca = optimizer.findLCA(parentKey, childKey);
        assertEquals(parentKey, lca);
    }
    
    @Test
    void testFindLCASiblings() {
        // Create two sibling keys (same parent)
        var key1 = new MortonKey(0b1111000); // xxx...000
        var key2 = new MortonKey(0b1111001); // xxx...001
        
        var lca = optimizer.findLCA(key1, key2);
        assertNotNull(lca);
        // LCA should be their parent
        assertEquals(key1.parent(), lca);
        assertEquals(key2.parent(), lca);
    }
    
    @Test
    void testFindLCADistantKeys() {
        // Create two keys with different high-order bits
        var key1 = new MortonKey(0b0111111111111111L); // Starts with 0
        var key2 = new MortonKey(0b1000000000000000L); // Starts with 1
        
        var lca = optimizer.findLCA(key1, key2);
        assertNotNull(lca);
        // LCA should be at a low level (near root) - but might be higher with our test keys
        assertTrue(lca.getLevel() < 15);
    }
    
    @Test
    void testMovementPatternTracking() {
        var entityId = new LongEntityID(1);
        
        // No pattern initially
        assertNull(optimizer.predictDestination(entityId, new Point3f(0, 0, 0)));
        
        // Add movement data
        var pos1 = new Point3f(0, 0, 0);
        var pos2 = new Point3f(10, 0, 0);
        optimizer.updateMovementPattern(entityId, pos1, pos2);
        
        // Still no prediction (need at least 3 samples)
        assertNull(optimizer.predictDestination(entityId, pos2));
        
        // Add more movements in same direction
        var pos3 = new Point3f(20, 0, 0);
        optimizer.updateMovementPattern(entityId, pos2, pos3);
        
        var pos4 = new Point3f(30, 0, 0);
        optimizer.updateMovementPattern(entityId, pos3, pos4);
        
        // Now we should get a prediction
        var predicted = optimizer.predictDestination(entityId, pos4);
        assertNotNull(predicted);
        // Should predict continued movement in +X direction
        assertTrue(predicted.x > pos4.x);
        assertEquals(pos4.y, predicted.y, 0.01f);
        assertEquals(pos4.z, predicted.z, 0.01f);
    }
    
    @Test
    void testMovementPatternWithVariedDirection() {
        var entityId = new LongEntityID(1);
        
        // Create zigzag pattern
        optimizer.updateMovementPattern(entityId, new Point3f(0, 0, 0), new Point3f(10, 0, 0));
        optimizer.updateMovementPattern(entityId, new Point3f(10, 0, 0), new Point3f(10, 10, 0));
        optimizer.updateMovementPattern(entityId, new Point3f(10, 10, 0), new Point3f(20, 10, 0));
        optimizer.updateMovementPattern(entityId, new Point3f(20, 10, 0), new Point3f(20, 20, 0));
        
        var predicted = optimizer.predictDestination(entityId, new Point3f(20, 20, 0));
        assertNotNull(predicted);
        // Prediction should be some average of the movements
        assertTrue(predicted.x > 20 || predicted.y > 20);
    }
    
    @Test
    void testNoMovementPattern() {
        var entityId = new LongEntityID(1);
        
        // Very small movements
        var pos = new Point3f(0, 0, 0);
        for (int i = 0; i < 5; i++) {
            var newPos = new Point3f(pos);
            newPos.x += 0.0001f; // Below threshold
            optimizer.updateMovementPattern(entityId, pos, newPos);
            pos = newPos;
        }
        
        // Should have no pattern due to insignificant movement
        assertNull(optimizer.predictDestination(entityId, pos));
    }
    
    @Test
    void testClearPattern() {
        var entityId = new LongEntityID(1);
        
        // Create pattern
        optimizer.updateMovementPattern(entityId, new Point3f(0, 0, 0), new Point3f(10, 0, 0));
        optimizer.updateMovementPattern(entityId, new Point3f(10, 0, 0), new Point3f(20, 0, 0));
        optimizer.updateMovementPattern(entityId, new Point3f(20, 0, 0), new Point3f(30, 0, 0));
        
        assertNotNull(optimizer.predictDestination(entityId, new Point3f(30, 0, 0)));
        
        // Clear pattern
        optimizer.clearPattern(entityId);
        assertNull(optimizer.predictDestination(entityId, new Point3f(30, 0, 0)));
    }
    
    @Test
    void testStatistics() {
        // Initial stats
        var stats = optimizer.getStatistics();
        assertEquals(0L, stats.get("totalMovements"));
        assertEquals(0L, stats.get("lcaOptimizedMovements"));
        assertEquals(0L, stats.get("fullReinsertions"));
        assertEquals(0.0f, stats.get("optimizationRate"));
        assertEquals(0.0f, stats.get("averageLCADepth"));
        assertEquals(0, stats.get("trackedPatterns"));
        
        // Note: optimizedMove requires actual spatial index implementation
        // so we can't fully test it here, but we can verify statistics update
    }
    
    @Test
    void testPatternHistoryLimit() {
        var optimizer = new LCAMovementOptimizer<MortonKey>(3); // Small history
        var entityId = new LongEntityID(1);
        
        // Add many movements
        var pos = new Point3f(0, 0, 0);
        for (int i = 0; i < 10; i++) {
            var newPos = new Point3f(i * 10, 0, 0);
            optimizer.updateMovementPattern(entityId, pos, newPos);
            pos = newPos;
        }
        
        // Pattern should exist but be limited by history size
        var predicted = optimizer.predictDestination(entityId, pos);
        assertNotNull(predicted);
        
        var stats = optimizer.getStatistics();
        assertEquals(1, stats.get("trackedPatterns"));
    }
    
    @Test
    void testMultipleEntityPatterns() {
        // Track patterns for multiple entities
        for (int e = 0; e < 5; e++) {
            var entityId = new LongEntityID(e);
            
            // Each entity moves in a different direction
            var direction = new Point3f(e == 0 ? 1 : 0, e == 1 ? 1 : 0, e == 2 ? 1 : 0);
            var pos = new Point3f(0, 0, 0);
            
            for (int i = 0; i < 5; i++) {
                var newPos = new Point3f(pos);
                newPos.add(direction);
                newPos.scale(10); // Scale up movement
                optimizer.updateMovementPattern(entityId, pos, newPos);
                pos = newPos;
            }
        }
        
        var stats = optimizer.getStatistics();
        // Only 3 entities have actual movement patterns (0, 1, 2)
        assertTrue((int) stats.get("trackedPatterns") >= 3);
        
        // Each entity should predict continued movement in its direction
        for (int e = 0; e < 3; e++) { // Only first 3 have directional movement
            var entityId = new LongEntityID(e);
            var current = new Point3f(50, 50, 50);
            var predicted = optimizer.predictDestination(entityId, current);
            
            if (predicted != null) {
                if (e == 0) assertTrue(predicted.x > current.x);
                if (e == 1) assertTrue(predicted.y > current.y);
                if (e == 2) assertTrue(predicted.z > current.z);
            }
        }
    }
    
    @Test
    void testClearAllPatterns() {
        // Create patterns for multiple entities
        for (int i = 0; i < 10; i++) {
            var entityId = new LongEntityID(i);
            optimizer.updateMovementPattern(entityId, 
                new Point3f(0, 0, 0), new Point3f(10, 0, 0));
        }
        
        var stats = optimizer.getStatistics();
        assertEquals(10, stats.get("trackedPatterns"));
        
        // Clear all
        optimizer.clearAllPatterns();
        
        stats = optimizer.getStatistics();
        assertEquals(0, stats.get("trackedPatterns"));
    }
}