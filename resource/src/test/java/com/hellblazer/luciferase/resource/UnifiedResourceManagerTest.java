package com.hellblazer.luciferase.resource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedResourceManagerTest {
    private UnifiedResourceManager manager;
    private ResourceConfiguration config;

    @BeforeEach
    void setUp() {
        config = new ResourceConfiguration.Builder()
            .withMaxPoolSize(10L * 1024 * 1024)  // 10 MB
            .withHighWaterMark(0.9)
            .withLowWaterMark(0.5)
            .withEvictionPolicy(ResourceConfiguration.EvictionPolicy.LRU)
            .withMaxIdleTime(Duration.ofSeconds(30))
            .withMaxResourceCount(100)
            .build();
        manager = new UnifiedResourceManager(config);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Test
    void testBasicMemoryAllocation() {
        var buffer = manager.allocateMemory(1024);
        assertNotNull(buffer);
        assertEquals(1024, buffer.capacity());
        assertEquals(0, buffer.position());
        assertEquals(1024, buffer.limit());
        
        // Should be tracked
        assertEquals(1, manager.getActiveResourceCount());
        assertTrue(manager.getTotalMemoryUsage() >= 1024);
        
        // Release and verify cleanup
        manager.releaseMemory(buffer);
        
        // After maintenance, should be back in pool but not counted as active
        manager.performMaintenance();
        assertEquals(0, manager.getActiveResourceCount());
    }

    @Test
    void testMemoryPoolReuse() {
        // Allocate and release a buffer
        var buffer1 = manager.allocateMemory(2048);
        assertNotNull(buffer1);
        manager.releaseMemory(buffer1);
        
        // Allocate again with same size - should get same buffer
        var buffer2 = manager.allocateMemory(2048);
        assertSame(buffer1, buffer2);
        
        manager.releaseMemory(buffer2);
    }

    @Test
    void testMultipleAllocations() {
        List<ByteBuffer> buffers = new ArrayList<>();
        int count = 10;
        int size = 1024;
        
        // Allocate multiple buffers
        for (int i = 0; i < count; i++) {
            var buffer = manager.allocateMemory(size);
            assertNotNull(buffer);
            buffers.add(buffer);
        }
        
        assertEquals(count, manager.getActiveResourceCount());
        assertTrue(manager.getTotalMemoryUsage() >= count * size);
        
        // Release all
        for (var buffer : buffers) {
            manager.releaseMemory(buffer);
        }
        
        manager.performMaintenance();
        assertEquals(0, manager.getActiveResourceCount());
    }

    @Test
    void testMaxResourceLimit() {
        List<ByteBuffer> buffers = new ArrayList<>();
        
        // Fill up to max resource count
        for (int i = 0; i < config.getMaxResourceCount(); i++) {
            buffers.add(manager.allocateMemory(100));
        }
        
        assertEquals(config.getMaxResourceCount(), manager.getActiveResourceCount());
        
        // Try to allocate one more - should succeed but might trigger eviction
        var extraBuffer = manager.allocateMemory(100);
        assertNotNull(extraBuffer);
        
        // Clean up
        for (var buffer : buffers) {
            manager.releaseMemory(buffer);
        }
        manager.releaseMemory(extraBuffer);
    }

    @Test
    void testConcurrentAllocation() throws InterruptedException {
        int threadCount = 10;
        int allocationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<ByteBuffer> localBuffers = new ArrayList<>();
                    
                    for (int i = 0; i < allocationsPerThread; i++) {
                        var buffer = manager.allocateMemory(512);
                        if (buffer != null) {
                            localBuffers.add(buffer);
                            successCount.incrementAndGet();
                        }
                    }
                    
                    // Release all local buffers
                    for (var buffer : localBuffers) {
                        manager.releaseMemory(buffer);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for completion
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        
        // Verify all allocations succeeded
        assertEquals(threadCount * allocationsPerThread, successCount.get());
        
        // After cleanup and maintenance
        manager.performMaintenance();
        assertEquals(0, manager.getActiveResourceCount());
        
        executor.shutdown();
    }

    @Test
    void testShutdown() {
        // Allocate some resources
        var buffer1 = manager.allocateMemory(1024);
        var buffer2 = manager.allocateMemory(2048);
        
        assertEquals(2, manager.getActiveResourceCount());
        
        // Shutdown should clean everything
        manager.shutdown();
        
        // Manager should be closed
        assertThrows(IllegalStateException.class, () -> manager.allocateMemory(512));
    }

    @Test
    void testMemoryUsageTracking() {
        long initialUsage = manager.getTotalMemoryUsage();
        assertEquals(0, initialUsage);
        
        var buffer1 = manager.allocateMemory(1024);
        assertTrue(manager.getTotalMemoryUsage() >= 1024);
        
        var buffer2 = manager.allocateMemory(2048);
        assertTrue(manager.getTotalMemoryUsage() >= 3072);
        
        manager.releaseMemory(buffer1);
        manager.releaseMemory(buffer2);
        
        // After maintenance, usage should decrease
        manager.performMaintenance();
        assertTrue(manager.getTotalMemoryUsage() <= 3072);
    }

    @Test
    void testMaintenanceEviction() throws InterruptedException {
        // Create config with very short idle time
        var quickEvictConfig = new ResourceConfiguration.Builder()
            .withMaxPoolSize(1024 * 1024)
            .withHighWaterMark(0.9)
            .withLowWaterMark(0.5)
            .withEvictionPolicy(ResourceConfiguration.EvictionPolicy.LRU)
            .withMaxIdleTime(Duration.ofMillis(100))
            .withMaxResourceCount(100)
            .build();
        
        try (var quickManager = new UnifiedResourceManager(quickEvictConfig)) {
            // Allocate and release
            var buffer = quickManager.allocateMemory(1024);
            quickManager.releaseMemory(buffer);
            
            // Wait for idle time to expire
            Thread.sleep(150);
            
            // Maintenance should evict the idle buffer
            quickManager.performMaintenance();
            
            // Allocate again - should get a new buffer since old one was evicted
            var buffer2 = quickManager.allocateMemory(1024);
            assertNotSame(buffer, buffer2);
            
            quickManager.releaseMemory(buffer2);
        }
    }

    @Test
    void testLargeAllocation() {
        // Test allocation close to pool limit
        long largeSize = config.getMaxPoolSizeBytes() / 2;
        var buffer = manager.allocateMemory((int) largeSize);
        assertNotNull(buffer);
        assertEquals(largeSize, buffer.capacity());
        
        manager.releaseMemory(buffer);
    }

    @Test
    void testZeroSizeAllocation() {
        // Should handle edge case gracefully
        assertThrows(IllegalArgumentException.class, () -> manager.allocateMemory(0));
    }

    @Test
    void testNegativeSizeAllocation() {
        // Should reject negative sizes
        assertThrows(IllegalArgumentException.class, () -> manager.allocateMemory(-1));
    }

    @Test
    void testDoubleRelease() {
        var buffer = manager.allocateMemory(1024);
        manager.releaseMemory(buffer);
        
        // Second release should be safe (idempotent)
        assertDoesNotThrow(() -> manager.releaseMemory(buffer));
    }

    @Test
    void testReleaseNull() {
        // Should handle null gracefully
        assertDoesNotThrow(() -> manager.releaseMemory(null));
    }

    @Test
    void testManagerAfterClose() {
        manager.close();
        
        // All operations should throw after close
        assertThrows(IllegalStateException.class, () -> manager.allocateMemory(1024));
        assertThrows(IllegalStateException.class, () -> manager.performMaintenance());
        assertThrows(IllegalStateException.class, () -> manager.getActiveResourceCount());
        assertThrows(IllegalStateException.class, () -> manager.getTotalMemoryUsage());
        
        // Shutdown should be safe even after close
        assertDoesNotThrow(() -> manager.shutdown());
    }
}