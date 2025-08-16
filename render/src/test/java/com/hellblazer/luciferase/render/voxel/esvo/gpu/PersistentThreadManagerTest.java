package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-first development for persistent thread management.
 * Persistent threads improve GPU utilization by keeping threads active across multiple rays.
 */
@DisplayName("Persistent Thread Manager Tests")
class PersistentThreadManagerTest {
    
    private PersistentThreadManager manager;
    
    @BeforeEach
    void setup() {
        manager = new PersistentThreadManager();
    }
    
    @Test
    @DisplayName("Should initialize thread pool")
    void testThreadPoolInitialization() {
        var config = new PersistentThreadConfig()
            .withThreadsPerBlock(64)
            .withMaxActiveThreads(1024)
            .withRayBufferSize(4096);
            
        var pool = manager.createThreadPool(config);
        
        assertNotNull(pool);
        assertEquals(64, pool.getThreadsPerBlock());
        assertEquals(1024, pool.getMaxActiveThreads());
        assertEquals(4096, pool.getRayBufferSize());
        assertEquals(0, pool.getActiveThreadCount());
    }
    
    @Test
    @DisplayName("Should manage ray queue")
    void testRayQueueManagement() {
        var config = new PersistentThreadConfig()
            .withRayBufferSize(100);
            
        var pool = manager.createThreadPool(config);
        
        // Add rays to queue
        float[] origin = {0, 0, 0, 1};
        float[] direction = {0, 0, 1, 0};
        
        for (int i = 0; i < 50; i++) {
            assertTrue(pool.enqueueRay(origin, direction));
        }
        
        assertEquals(50, pool.getQueuedRayCount());
        assertFalse(pool.isQueueEmpty());
        
        // Dequeue rays  
        var batch = pool.dequeueRayBatch(32);
        assertNotNull(batch);
        assertEquals(32, batch.size());
        assertEquals(18, pool.getQueuedRayCount());
        
        // Process the dequeued batch to update metrics
        pool.getMetrics().setRaysProcessed(32);
    }
    
    @Test
    @DisplayName("Should handle queue overflow")
    void testQueueOverflow() {
        var config = new PersistentThreadConfig()
            .withRayBufferSize(10);
            
        var pool = manager.createThreadPool(config);
        
        float[] origin = {0, 0, 0, 1};
        float[] direction = {0, 0, 1, 0};
        
        // Fill queue
        for (int i = 0; i < 10; i++) {
            assertTrue(pool.enqueueRay(origin, direction));
        }
        
        // Should reject when full
        assertFalse(pool.enqueueRay(origin, direction));
        assertEquals(10, pool.getQueuedRayCount());
    }
    
    @Test
    @DisplayName("Should track thread utilization")
    void testThreadUtilization() {
        var config = new PersistentThreadConfig()
            .withMaxActiveThreads(100);
            
        var pool = manager.createThreadPool(config);
        
        // Simulate thread activation
        pool.activateThreads(50);
        assertEquals(50, pool.getActiveThreadCount());
        assertEquals(0.5f, pool.getUtilization());
        
        // Activate more
        pool.activateThreads(30);
        assertEquals(80, pool.getActiveThreadCount());
        assertEquals(0.8f, pool.getUtilization());
        
        // Deactivate some
        pool.deactivateThreads(40);
        assertEquals(40, pool.getActiveThreadCount());
        assertEquals(0.4f, pool.getUtilization());
    }
    
    @Test
    @DisplayName("Should balance work across threads")
    void testWorkBalancing() {
        var config = new PersistentThreadConfig()
            .withThreadsPerBlock(32)
            .withMaxActiveThreads(128);
            
        var pool = manager.createThreadPool(config);
        
        // Add varied workload
        for (int i = 0; i < 200; i++) {
            float[] origin = {i * 0.1f, 0, 0, 1};
            float[] direction = {0, 0, 1, 0};
            pool.enqueueRay(origin, direction);
        }
        
        // Distribute work
        var distribution = pool.distributeWork();
        
        assertNotNull(distribution);
        assertEquals(4, distribution.getBlockCount()); // 128/32 = 4 blocks
        
        // Each block should get balanced work
        for (int i = 0; i < distribution.getBlockCount(); i++) {
            var blockWork = distribution.getBlockWork(i);
            assertTrue(blockWork.getRayCount() > 0);
            assertTrue(blockWork.getRayCount() <= 64); // Reasonable distribution
        }
    }
    
    @Test
    @DisplayName("Should handle thread synchronization")
    void testThreadSynchronization() {
        var config = new PersistentThreadConfig()
            .withThreadsPerBlock(32);
            
        var pool = manager.createThreadPool(config);
        var sync = pool.createSynchronizer();
        
        assertNotNull(sync);
        
        // Test barrier synchronization
        sync.enterBarrier(0); // Thread 0 enters
        assertFalse(sync.isBarrierComplete());
        
        // All threads enter
        for (int i = 1; i < 32; i++) {
            sync.enterBarrier(i);
        }
        
        assertTrue(sync.isBarrierComplete());
        sync.resetBarrier();
        assertFalse(sync.isBarrierComplete());
    }
    
    @Test
    @DisplayName("Should manage thread-local storage")
    void testThreadLocalStorage() {
        var config = new PersistentThreadConfig()
            .withThreadLocalStorageSize(256); // bytes per thread
            
        var pool = manager.createThreadPool(config);
        
        // Allocate storage for thread
        var storage = pool.getThreadLocalStorage(0);
        assertNotNull(storage);
        assertEquals(256, storage.getSize());
        
        // Write and read data
        storage.writeFloat(0, 3.14f);
        storage.writeInt(4, 42);
        
        assertEquals(3.14f, storage.readFloat(0), 0.001f);
        assertEquals(42, storage.readInt(4));
    }
    
    @Test
    @DisplayName("Should compact ray queue")
    void testRayQueueCompaction() {
        var config = new PersistentThreadConfig()
            .withRayBufferSize(100);
            
        var pool = manager.createThreadPool(config);
        
        // Add rays with gaps (simulate processed rays)
        for (int i = 0; i < 50; i++) {
            float[] origin = {i * 0.1f, 0, 0, 1};
            float[] direction = {0, 0, 1, 0};
            pool.enqueueRay(origin, direction);
        }
        
        // Process some rays (creating gaps)
        var batch1 = pool.dequeueRayBatch(10);
        var batch2 = pool.dequeueRayBatch(10);
        
        // Add more rays
        for (int i = 0; i < 20; i++) {
            float[] origin = {i * 0.2f, 0, 0, 1};
            float[] direction = {0, 0, 1, 0};
            pool.enqueueRay(origin, direction);
        }
        
        // Compact queue
        pool.compactQueue();
        
        // Should have continuous queue
        assertEquals(50, pool.getQueuedRayCount()); // 50 - 20 + 20
        assertTrue(pool.isQueueContiguous());
    }
    
    @Test
    @DisplayName("Should report performance metrics")
    void testPerformanceMetrics() {
        var config = new PersistentThreadConfig()
            .withMaxActiveThreads(100);
            
        var pool = manager.createThreadPool(config);
        
        // Simulate work
        pool.activateThreads(75);
        for (int i = 0; i < 1000; i++) {
            float[] origin = {0, 0, 0, 1};
            float[] direction = {0, 0, 1, 0};
            pool.enqueueRay(origin, direction);
        }
        
        pool.dequeueRayBatch(500);
        pool.recordCycleTime(1.5f); // milliseconds
        pool.getMetrics().setRaysProcessed(500);
        
        var metrics = pool.getMetrics();
        
        assertNotNull(metrics);
        assertEquals(0.75f, metrics.getAverageUtilization());
        assertEquals(1.5f, metrics.getAverageCycleTime());
        assertEquals(500, metrics.getRaysProcessed());
        assertTrue(metrics.getThroughput() > 0);
    }
    
    @Test
    @DisplayName("Should adapt thread count based on workload")
    void testAdaptiveThreading() {
        var config = new PersistentThreadConfig()
            .withMaxActiveThreads(256)
            .withAdaptiveScheduling(true);
            
        var pool = manager.createThreadPool(config);
        
        // Low workload
        for (int i = 0; i < 10; i++) {
            pool.enqueueRay(new float[]{0,0,0,1}, new float[]{0,0,1,0});
        }
        pool.adaptThreadCount();
        assertTrue(pool.getActiveThreadCount() <= 32, "Should use few threads for small workload");
        
        // High workload
        for (int i = 0; i < 1000; i++) {
            pool.enqueueRay(new float[]{0,0,0,1}, new float[]{0,0,1,0});
        }
        pool.adaptThreadCount();
        assertTrue(pool.getActiveThreadCount() >= 128, "Should use more threads for large workload");
    }
}