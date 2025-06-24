/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.OctreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test deferred subdivision manager functionality
 *
 * @author hal.hildebrand
 */
public class DeferredSubdivisionManagerTest {

    private DeferredSubdivisionManager<?, LongEntityID, OctreeNode<LongEntityID>> manager;

    @BeforeEach
    void setUp() {
        manager = new DeferredSubdivisionManager<>();
    }

    @Test
    void testBatchProcessing() {
        // Add many deferred subdivisions
        for (int i = 0; i < 10; i++) {
            OctreeNode<LongEntityID> node = new OctreeNode<>();
            for (int j = 0; j < i + 5; j++) {
                node.addEntity(new LongEntityID(i * 100 + j));
            }
            manager.deferSubdivision(i, node, i + 5, (byte) 5);
        }

        // Process in batches
        List<DeferredSubdivisionManager.SubdivisionResult> results = manager.processBatches(
        new DeferredSubdivisionManager.SubdivisionProcessor<LongEntityID, OctreeNode<LongEntityID>>() {
            @Override
            public Result subdivideNode(long nodeIndex, OctreeNode<LongEntityID> node, byte level) {
                // Subdivide nodes with >8 entities
                if (node.getEntityCount() > 8) {
                    return new Result(true, 8, node.getEntityCount());
                }
                return new Result(false, 0, 0);
            }
        }, 3 // Batch size of 3
                                                                                           );

        // Should have processed 4 batches (3 + 3 + 3 + 1)
        assertEquals(4, results.size());

        // Verify total nodes processed
        int totalProcessed = results.stream().mapToInt(r -> r.nodesProcessed).sum();
        assertEquals(10, totalProcessed);

        // Verify total subdivided (nodes 4-9 have >8 entities)
        int totalSubdivided = results.stream().mapToInt(r -> r.nodesSubdivided).sum();
        assertEquals(6, totalSubdivided);
    }

    @Test
    void testClearWithoutProcessing() {
        // Add some deferred subdivisions
        OctreeNode<LongEntityID> node = new OctreeNode<>();
        manager.deferSubdivision(1L, node, 10, (byte) 5);
        manager.deferSubdivision(2L, node, 20, (byte) 6);

        assertEquals(2, manager.getStats().currentDeferred);

        // Clear without processing
        manager.clear();

        assertEquals(0, manager.getStats().currentDeferred);
        assertFalse(manager.isDeferred(1L));
        assertFalse(manager.isDeferred(2L));
    }

    @Test
    void testDeferAndProcess() {
        // Create test nodes
        OctreeNode<LongEntityID> node1 = new OctreeNode<>();
        OctreeNode<LongEntityID> node2 = new OctreeNode<>();
        OctreeNode<LongEntityID> node3 = new OctreeNode<>();

        // Add some entities to nodes
        for (int i = 0; i < 10; i++) {
            node1.addEntity(new LongEntityID(i));
        }
        for (int i = 10; i < 25; i++) {
            node2.addEntity(new LongEntityID(i));
        }
        for (int i = 25; i < 30; i++) {
            node3.addEntity(new LongEntityID(i));
        }

        // Defer subdivisions
        manager.deferSubdivision(100L, node1, 10, (byte) 5);
        manager.deferSubdivision(200L, node2, 15, (byte) 6);
        manager.deferSubdivision(300L, node3, 5, (byte) 4);

        // Check deferred status
        assertTrue(manager.isDeferred(100L));
        assertTrue(manager.isDeferred(200L));
        assertTrue(manager.isDeferred(300L));
        assertFalse(manager.isDeferred(400L));

        // Get stats before processing
        DeferredSubdivisionManager.DeferredStats statsBefore = manager.getStats();
        assertEquals(3, statsBefore.currentDeferred);
        assertEquals(3, statsBefore.totalDeferred);
        assertEquals(0, statsBefore.totalProcessed);
        assertEquals(10.0, statsBefore.averageEntityCount, 0.01);

        // Process all deferred subdivisions
        AtomicInteger subdivisionCount = new AtomicInteger(0);
        DeferredSubdivisionManager.SubdivisionResult<MortonKey> result = manager.processAll(
        new DeferredSubdivisionManager.SubdivisionProcessor() {

            @Override
            public Result subdivideNode(SpatialKey nodeIndex, Object node, byte level) {
                return null;
            }

            @Override
            public Result subdivideNode(MortonKey nodeIndex, OctreeNode<?> node, byte level) {
                subdivisionCount.incrementAndGet();
                // Simulate subdivision - only subdivide nodes with >10 entities
                if (node.getEntityCount() > 10) {
                    return new Result(true, 8, node.getEntityCount());
                }
                return new Result(false, 0, 0);
            }
        });

        // Verify results
        assertEquals(3, result.nodesProcessed);
        assertEquals(1, result.nodesSubdivided); // Only node2 has >10 entities
        assertEquals(8, result.newNodesCreated);
        assertTrue(result.getProcessingTimeMs() >= 0);
        assertEquals(0.33, result.getSubdivisionRate(), 0.01);

        // Check that all deferred nodes were cleared
        assertFalse(manager.isDeferred(100L));
        assertFalse(manager.isDeferred(200L));
        assertFalse(manager.isDeferred(300L));

        // Get stats after processing
        DeferredSubdivisionManager.DeferredStats statsAfter = manager.getStats();
        assertEquals(0, statsAfter.currentDeferred);
        assertEquals(3, statsAfter.totalDeferred);
        assertEquals(3, statsAfter.totalProcessed);
    }

    @Test
    void testEmptyProcessing() {
        // Process with no deferred subdivisions
        DeferredSubdivisionManager.SubdivisionResult result = manager.processAll(
        (nodeIndex, node, level) -> new DeferredSubdivisionManager.SubdivisionProcessor.Result(false, 0, 0));

        assertEquals(0, result.nodesProcessed);
        assertEquals(0, result.nodesSubdivided);
        assertEquals(0, result.newNodesCreated);
        assertEquals(0, result.getProcessingTimeMs(), 0.01);
    }

    @Test
    void testPriorityBasedProcessing() {
        // Create manager with priority processing
        manager = new DeferredSubdivisionManager<>(2, true, 0.8);

        OctreeNode<LongEntityID> smallNode = new OctreeNode<>();
        OctreeNode<LongEntityID> mediumNode = new OctreeNode<>();
        OctreeNode<LongEntityID> largeNode = new OctreeNode<>();

        // Add entities
        for (int i = 0; i < 5; i++) {
            smallNode.addEntity(new LongEntityID(i));
        }
        for (int i = 0; i < 20; i++) {
            mediumNode.addEntity(new LongEntityID(100 + i));
        }
        for (int i = 0; i < 50; i++) {
            largeNode.addEntity(new LongEntityID(200 + i));
        }

        // Defer subdivisions - should hit limit after 2
        manager.deferSubdivision(1L, smallNode, 5, (byte) 5);
        manager.deferSubdivision(2L, mediumNode, 20, (byte) 5);

        // This should cause the smallest node to be evicted
        manager.deferSubdivision(3L, largeNode, 50, (byte) 5);

        // Small node should have been evicted
        assertFalse(manager.isDeferred(1L));
        assertTrue(manager.isDeferred(2L));
        assertTrue(manager.isDeferred(3L));
    }
}
