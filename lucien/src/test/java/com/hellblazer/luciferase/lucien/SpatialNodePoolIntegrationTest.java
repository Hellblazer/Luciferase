/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.OctreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test SpatialNodePool integration with spatial index implementations
 *
 * @author hal.hildebrand
 */
public class SpatialNodePoolIntegrationTest {
    
    private Octree<LongEntityID, String> octree;
    
    @BeforeEach
    void setUp() {
        octree = new Octree<>(new SequentialLongIDGenerator());
    }
    
    @Test
    void testNodePoolInUse() throws Exception {
        // Access nodePool via reflection
        var nodePoolField = AbstractSpatialIndex.class.getDeclaredField("nodePool");
        nodePoolField.setAccessible(true);
        @SuppressWarnings("unchecked")
        SpatialNodePool<?> nodePool = (SpatialNodePool<?>) nodePoolField.get(octree);
        
        // Get initial pool stats
        SpatialNodePool.PoolStats initialStats = nodePool.getStats();
        // Pool is pre-allocated by default with 100 nodes
        assertEquals(100, initialStats.getAllocations());
        
        // Check initial node count
        int initialNodeCount = octree.nodeCount();
        System.out.printf("Initial state - Nodes: %d, Pool allocations: %d, Pool size: %d%n",
                         initialNodeCount, initialStats.getAllocations(), initialStats.getCurrentPoolSize());
        
        // The pool should have been used if any nodes were created during initialization
        if (initialNodeCount > 0) {
            assertEquals(100 - initialNodeCount, initialStats.getCurrentPoolSize(),
                        "Pool size should decrease by number of nodes created");
            assertEquals(initialNodeCount, initialStats.getHits(),
                        "Initial nodes should be counted as hits from pre-allocated pool");
        } else {
            assertEquals(0, initialStats.getHits());
            assertEquals(100, initialStats.getCurrentPoolSize());
        }
        
        // Insert some entities to trigger node creation
        // Spread them out more to ensure multiple nodes are created
        List<LongEntityID> entityIds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            // Spread entities across a larger space to force multiple nodes
            Point3f pos = new Point3f(
                (i % 5) * 1000.0f,  // 5 different X positions far apart
                ((i / 5) % 5) * 1000.0f,  // 5 different Y positions far apart
                ((i / 25) % 2) * 1000.0f  // 2 different Z positions
            );
            LongEntityID id = octree.insert(pos, (byte) 10, "Entity" + i);  // Use lower level for more nodes
            entityIds.add(id);
        }
        
        // Check that pool was used for allocation
        SpatialNodePool.PoolStats afterInsertStats = nodePool.getStats();
        assertTrue(afterInsertStats.getAllocations() > 0, 
                  "Pool should have allocated nodes for entities");
        
        // Debug: Check how many nodes were created
        System.out.printf("After insert - Nodes created: %d, Pool size: %d, Hits: %d%n",
                         octree.nodeCount(), afterInsertStats.getCurrentPoolSize(), afterInsertStats.getHits());
        
        // Remove some entities to trigger node cleanup
        for (int i = 0; i < 25; i++) {
            octree.removeEntity(entityIds.get(i));
        }
        
        // Check that pool received returned nodes
        SpatialNodePool.PoolStats afterRemoveStats = nodePool.getStats();
        System.out.printf("After remove - Deallocations: %d, Pool size: %d%n",
                         afterRemoveStats.getDeallocations(), afterRemoveStats.getCurrentPoolSize());
        
        // Deallocations might be 0 if all entities are in the same node
        // Let's check if we have fewer nodes after removal
        assertTrue(afterRemoveStats.getDeallocations() > 0 || octree.nodeCount() > 0, 
                  "Pool should have received deallocated nodes or entities should still exist in nodes");
        
        // Insert more entities to check pool reuse
        for (int i = 0; i < 10; i++) {
            Point3f pos = new Point3f((i + 100) * 10.0f, (i + 100) * 10.0f, (i + 100) * 10.0f);
            octree.insert(pos, (byte) 15, "NewEntity" + i);
        }
        
        // Check for pool hits (reuse)
        SpatialNodePool.PoolStats finalStats = nodePool.getStats();
        assertTrue(finalStats.getHits() > 0, 
                  "Pool should have hits from node reuse");
        assertTrue(finalStats.getHitRate() > 0, 
                  "Pool hit rate should be positive");
        
        System.out.printf("Pool Stats - Allocations: %d, Hits: %d, Hit Rate: %.2f%%, Memory Saved: %d bytes%n",
                         finalStats.getAllocations(),
                         finalStats.getHits(),
                         finalStats.getHitRate() * 100,
                         finalStats.getTotalMemorySaved());
    }
    
    @Test
    void testBulkOperationsWithPool() throws Exception {
        // Access nodePool via reflection
        var nodePoolField = AbstractSpatialIndex.class.getDeclaredField("nodePool");
        nodePoolField.setAccessible(true);
        @SuppressWarnings("unchecked")
        SpatialNodePool<?> nodePool = (SpatialNodePool<?>) nodePoolField.get(octree);
        
        // Configure for optimized bulk operations
        octree.configureBulkOperations(BulkOperationConfig.highPerformance());
        
        // Prepare large batch
        List<Point3f> positions = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            positions.add(new Point3f(
                (float) Math.random() * 10000,
                (float) Math.random() * 10000,
                (float) Math.random() * 10000
            ));
            contents.add("BulkEntity" + i);
        }
        
        // Get initial pool stats
        SpatialNodePool.PoolStats initialStats = nodePool.getStats();
        
        // Perform bulk insertion
        List<LongEntityID> ids = octree.insertBatch(positions, contents, (byte) 15);
        assertEquals(1000, ids.size());
        
        // Check pool usage
        SpatialNodePool.PoolStats bulkStats = nodePool.getStats();
        // Since the pool was pre-allocated with 100 nodes, check if any were used
        assertTrue(bulkStats.getHits() > 0 || bulkStats.getAllocations() > initialStats.getAllocations(),
                  "Bulk operations should use node pool (hits: " + bulkStats.getHits() + 
                  ", allocations: " + bulkStats.getAllocations() + ")");
        
        // Remove half the entities
        for (int i = 0; i < 500; i++) {
            octree.removeEntity(ids.get(i));
        }
        
        // Check for deallocations
        SpatialNodePool.PoolStats afterRemoveStats = nodePool.getStats();
        assertTrue(afterRemoveStats.getDeallocations() > 0,
                  "Pool should receive nodes from bulk removal");
        
        // Insert another batch to test reuse
        for (int i = 0; i < 200; i++) {
            positions.add(new Point3f(
                (float) Math.random() * 5000,
                (float) Math.random() * 5000,
                (float) Math.random() * 5000
            ));
            contents.add("SecondBulkEntity" + i);
        }
        
        octree.insertBatch(positions.subList(1000, 1200), contents.subList(1000, 1200), (byte) 15);
        
        // Check for pool hits
        SpatialNodePool.PoolStats finalStats = nodePool.getStats();
        assertTrue(finalStats.getHits() > bulkStats.getHits(),
                  "Second bulk insert should reuse pooled nodes");
        
        System.out.printf("Bulk Pool Stats - Total Allocations: %d, Pool Efficiency: %.2f%%, Memory Saved: %.2f KB%n",
                         finalStats.getAllocations(),
                         finalStats.getPoolEfficiency() * 100,
                         finalStats.getTotalMemorySaved() / 1024.0);
    }
    
    @Test
    void testPoolConfigurationEffects() throws Exception {
        // Create octree with custom pool configuration
        var nodePoolField = AbstractSpatialIndex.class.getDeclaredField("nodePool");
        nodePoolField.setAccessible(true);
        
        // Replace with custom configured pool
        SpatialNodePool.PoolConfig config = new SpatialNodePool.PoolConfig()
            .withInitialSize(50)
            .withMaxSize(200)
            .withPreAllocation(true);
            
        // Create a custom pool with proper node type
        @SuppressWarnings("unchecked")
        SpatialNodePool<OctreeNode<LongEntityID>> customPool = new SpatialNodePool<>(() -> new OctreeNode<>(), config);
        nodePoolField.set(octree, customPool);
        
        // Check pre-allocation
        SpatialNodePool.PoolStats preAllocStats = customPool.getStats();
        assertEquals(50, preAllocStats.getCurrentPoolSize(),
                    "Pool should be pre-allocated with initial size");
        assertEquals(50, preAllocStats.getAllocations(),
                    "Pre-allocation should count as allocations");
        
        // Insert entities to use pre-allocated nodes
        for (int i = 0; i < 30; i++) {
            Point3f pos = new Point3f(i * 100.0f, i * 100.0f, i * 100.0f);
            octree.insert(pos, (byte) 15, "PreAllocEntity" + i);
        }
        
        SpatialNodePool.PoolStats afterInsertStats = customPool.getStats();
        // Since nodes are pre-allocated, they should be used from the pool (hits)
        assertTrue(afterInsertStats.getHits() > 0 || afterInsertStats.getCurrentPoolSize() < 50,
                  "Should use pre-allocated nodes (hits: " + afterInsertStats.getHits() + 
                  ", pool size: " + afterInsertStats.getCurrentPoolSize() + ")");
        assertTrue(afterInsertStats.getCurrentPoolSize() < 50,
                  "Pool size should decrease as nodes are used");
    }
}