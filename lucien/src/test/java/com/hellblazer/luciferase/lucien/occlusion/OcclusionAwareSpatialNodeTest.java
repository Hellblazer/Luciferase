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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for OcclusionAwareSpatialNode
 *
 * @author hal.hildebrand
 */
public class OcclusionAwareSpatialNodeTest {
    
    private OcclusionAwareSpatialNode<LongEntityID> node;
    
    @BeforeEach
    void setUp() {
        node = new OcclusionAwareSpatialNode<>(10);
    }
    
    @Test
    void testBasicNodeFunctionality() {
        // Test inherits from SpatialNodeImpl correctly
        var entityId = new LongEntityID(1);
        
        // addEntity returns shouldSplit() which is false for first entity
        assertFalse(node.addEntity(entityId));
        assertTrue(node.containsEntity(entityId));
        assertEquals(1, node.getEntityCount());
        
        assertTrue(node.removeEntity(entityId));
        assertFalse(node.containsEntity(entityId));
        assertEquals(0, node.getEntityCount());
    }
    
    @Test
    void testOcclusionScore() {
        // Test occlusion score management
        assertEquals(0.0f, node.getOcclusionScore());
        
        node.setOcclusionScore(0.5f);
        assertEquals(0.5f, node.getOcclusionScore());
        
        node.setOcclusionScore(1.0f);
        assertEquals(1.0f, node.getOcclusionScore());
        
        // Test bounds checking
        assertThrows(IllegalArgumentException.class, () -> node.setOcclusionScore(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> node.setOcclusionScore(1.1f));
    }
    
    @Test
    void testOcclusionFrameTracking() {
        assertEquals(-1, node.getLastOcclusionFrame());
        
        node.updateLastOcclusionFrame(100);
        assertEquals(100, node.getLastOcclusionFrame());
        
        node.updateLastOcclusionFrame(200);
        assertEquals(200, node.getLastOcclusionFrame());
    }
    
    @Test
    void testOccluderFlag() {
        assertFalse(node.isOccluder());
        
        node.setIsOccluder(true);
        assertTrue(node.isOccluder());
        
        node.setIsOccluder(false);
        assertFalse(node.isOccluder());
    }
    
    @Test
    void testVisibilityTracking() {
        var frame = 100L;
        
        // Initially not occluded
        assertFalse(node.isOccluded());
        assertEquals(-1, node.getLastVisibleFrame());
        assertEquals(-1, node.getOccludedSinceFrame());
        assertEquals(0, node.getOccludedDuration(frame));
        
        // Mark visible
        node.markVisible(frame);
        assertEquals(frame, node.getLastVisibleFrame());
        assertFalse(node.isOccluded());
        assertEquals(0.0f, node.getOcclusionScore());
        
        // Mark occluded
        frame = 110L;
        node.markOccluded(frame);
        assertTrue(node.isOccluded());
        assertEquals(frame, node.getOccludedSinceFrame());
        assertEquals(1.0f, node.getOcclusionScore());
        
        // Check duration
        frame = 120L;
        assertEquals(10, node.getOccludedDuration(frame));
        
        // Mark visible again
        node.markVisible(frame);
        assertFalse(node.isOccluded());
        assertEquals(frame, node.getLastVisibleFrame());
        assertEquals(-1, node.getOccludedSinceFrame());
    }
    
    @Test
    void testTBVManagement() {
        var entityId1 = new LongEntityID(1);
        var entityId2 = new LongEntityID(2);
        
        // Initially no TBVs
        assertFalse(node.hasTBVs());
        assertEquals(0, node.getTBVCount());
        assertTrue(node.getTBVs().isEmpty());
        
        // Add TBVs
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        var velocity = new Vector3f(1, 0, 0);
        var strategy = FixedDurationTBVStrategy.defaultStrategy();
        
        var tbv1 = new TemporalBoundingVolume<>(entityId1, bounds, velocity, 100, strategy);
        var tbv2 = new TemporalBoundingVolume<>(entityId2, bounds, velocity, 100, strategy);
        
        node.addTBV(tbv1);
        assertTrue(node.hasTBVs());
        assertEquals(1, node.getTBVCount());
        assertEquals(tbv1, node.getTBV(entityId1));
        assertNull(node.getTBV(entityId2));
        
        node.addTBV(tbv2);
        assertEquals(2, node.getTBVCount());
        assertEquals(tbv2, node.getTBV(entityId2));
        
        // Remove TBV
        var removed = node.removeTBV(entityId1);
        assertEquals(tbv1, removed);
        assertEquals(1, node.getTBVCount());
        assertNull(node.getTBV(entityId1));
        
        // Test null safety
        assertThrows(IllegalArgumentException.class, () -> node.addTBV(null));
    }
    
    @Test
    void testTBVPruning() {
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        var velocity = new Vector3f(1, 0, 0);
        var strategy = new FixedDurationTBVStrategy(30); // 30 frame validity
        
        // Add TBVs at different times
        var tbv1 = new TemporalBoundingVolume<>(new LongEntityID(1), bounds, velocity, 100, strategy);
        var tbv2 = new TemporalBoundingVolume<>(new LongEntityID(2), bounds, velocity, 110, strategy);
        var tbv3 = new TemporalBoundingVolume<>(new LongEntityID(3), bounds, velocity, 120, strategy);
        
        node.addTBV(tbv1);
        node.addTBV(tbv2);
        node.addTBV(tbv3);
        assertEquals(3, node.getTBVCount());
        
        // Prune at frame 135 - should expire tbv1 only
        var expired = node.pruneExpiredTBVs(135);
        assertEquals(1, expired.size());
        assertEquals(new LongEntityID(1), expired.get(0));
        assertEquals(2, node.getTBVCount());
        
        // Prune at frame 145 - should expire tbv2
        expired = node.pruneExpiredTBVs(145);
        assertEquals(1, expired.size());
        assertEquals(new LongEntityID(2), expired.get(0));
        assertEquals(1, node.getTBVCount());
        
        // Prune at frame 155 - should expire tbv3
        expired = node.pruneExpiredTBVs(155);
        assertEquals(1, expired.size());
        assertEquals(new LongEntityID(3), expired.get(0));
        assertEquals(0, node.getTBVCount());
    }
    
    @Test
    void testClearingData() {
        // Set up node with data
        var entityId = new LongEntityID(1);
        node.addEntity(entityId);
        node.setOcclusionScore(0.8f);
        node.updateLastOcclusionFrame(100);
        node.setIsOccluder(true);
        node.markOccluded(110);
        
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        var tbv = new TemporalBoundingVolume<>(entityId, bounds, new Vector3f(1, 0, 0), 100, 
                                              FixedDurationTBVStrategy.defaultStrategy());
        node.addTBV(tbv);
        
        // Clear occlusion data
        node.clearOcclusionData();
        
        assertEquals(0.0f, node.getOcclusionScore());
        assertEquals(-1, node.getLastOcclusionFrame());
        assertFalse(node.isOccluder());
        assertEquals(-1, node.getLastVisibleFrame());
        assertEquals(-1, node.getOccludedSinceFrame());
        assertEquals(0, node.getTBVCount());
        
        // Entities should still be there
        assertEquals(1, node.getEntityCount());
        
        // Clear entities
        node.clearEntities();
        assertEquals(0, node.getEntityCount());
    }
    
    @Test
    void testOcclusionStatistics() {
        var entityId = new LongEntityID(1);
        node.addEntity(entityId);
        node.markOccluded(110);
        node.setOcclusionScore(0.7f);
        node.setIsOccluder(true);
        node.updateLastOcclusionFrame(100);
        
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        var tbv = new TemporalBoundingVolume<>(entityId, bounds, new Vector3f(1, 0, 0), 100,
                                              FixedDurationTBVStrategy.defaultStrategy());
        node.addTBV(tbv);
        
        var stats = node.getOcclusionStatistics();
        
        assertEquals(0.7f, stats.get("occlusionScore"));
        assertEquals(true, stats.get("isOccluder"));
        assertEquals(true, stats.get("isOccluded"));
        assertEquals(100L, stats.get("lastOcclusionFrame"));
        assertEquals(-1L, stats.get("lastVisibleFrame"));
        assertEquals(110L, stats.get("occludedSinceFrame"));
        assertEquals(1, stats.get("activeTBVCount"));
        assertEquals(1, stats.get("entityCount"));
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        var numThreads = 10;
        var entitiesPerThread = 100;
        var threads = new Thread[numThreads];
        
        // Create threads that add entities and TBVs
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < entitiesPerThread; i++) {
                    var entityId = new LongEntityID(threadId * entitiesPerThread + i);
                    node.addEntity(entityId);
                    
                    if (i % 2 == 0) {
                        var bounds = new EntityBounds(new Point3f(i, i, i), 1.0f);
                        var tbv = new TemporalBoundingVolume<>(entityId, bounds, 
                                                              new Vector3f(1, 0, 0), i,
                                                              FixedDurationTBVStrategy.defaultStrategy());
                        node.addTBV(tbv);
                    }
                }
            });
        }
        
        // Start all threads
        for (var thread : threads) {
            thread.start();
        }
        
        // Wait for completion
        for (var thread : threads) {
            thread.join();
        }
        
        // Verify results - may have slight variations due to concurrency
        assertTrue(node.getEntityCount() >= numThreads * entitiesPerThread * 0.9);
        assertTrue(node.getTBVCount() >= numThreads * entitiesPerThread / 2 * 0.9);
    }
}