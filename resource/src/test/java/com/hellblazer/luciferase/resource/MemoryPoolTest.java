package com.hellblazer.luciferase.resource;

import com.hellblazer.luciferase.resource.memory.MemoryPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MemoryPool functionality
 */
public class MemoryPoolTest {
    
    private MemoryPool pool;
    
    @BeforeEach
    void setUp() {
        pool = new MemoryPool(1024 * 1024, Duration.ofMinutes(1)); // 1MB pool, 1 min idle
    }
    
    @Test
    void testPoolCreation() {
        assertNotNull(pool);
        assertEquals(0, pool.getCurrentSize());
        assertEquals(0, pool.getHitRate(), 0.01);
    }
    
    @Test
    void testBasicAllocationAndReturn() {
        var buffer = pool.allocate(1024);
        assertNotNull(buffer);
        assertEquals(1024, buffer.capacity());
        assertEquals(0, pool.getCurrentSize()); // Not in pool until returned
        
        pool.returnToPool(buffer);
        assertEquals(1024, pool.getCurrentSize()); // Now in pool
    }
    
    @Test
    void testPoolReuse() {
        var buffer1 = pool.allocate(1024);
        assertNotNull(buffer1);
        pool.returnToPool(buffer1);
        
        var buffer2 = pool.allocate(1024);
        assertNotNull(buffer2);
        assertSame(buffer1, buffer2); // Should get the same buffer back
        assertEquals(0.5, pool.getHitRate(), 0.01); // 1 hit, 2 requests
    }
    
    @Test
    void testDifferentSizes() {
        var small = pool.allocate(512);
        var medium = pool.allocate(1024);
        var large = pool.allocate(2048);
        
        assertNotNull(small);
        assertNotNull(medium);
        assertNotNull(large);
        
        assertEquals(512, small.capacity());
        assertEquals(1024, medium.capacity());
        assertEquals(2048, large.capacity());
        
        pool.returnToPool(small);
        pool.returnToPool(medium);
        pool.returnToPool(large);
        
        assertEquals(512 + 1024 + 2048, pool.getCurrentSize());
    }
    
    @Test
    void testPoolEviction() throws InterruptedException {
        // Create pool with very short idle time
        var shortPool = new MemoryPool(1024 * 1024, Duration.ofMillis(100));
        
        var buffer = shortPool.allocate(1024);
        shortPool.returnToPool(buffer);
        assertEquals(1024, shortPool.getCurrentSize());
        
        // Wait for expiration
        Thread.sleep(200);
        shortPool.evictExpired();
        
        assertEquals(0, shortPool.getCurrentSize());
    }
    
    @Test
    void testMaxPoolSize() {
        // Create small pool
        var smallPool = new MemoryPool(2048, Duration.ofMinutes(1));
        
        var buffer1 = smallPool.allocate(1024);
        var buffer2 = smallPool.allocate(1024);
        
        
        smallPool.returnToPool(buffer1);
        
        smallPool.returnToPool(buffer2);
        
        assertEquals(2048, smallPool.getCurrentSize());
        
        // Try to add more - should trigger eviction
        var buffer3 = smallPool.allocate(1024);
        smallPool.returnToPool(buffer3);
        
        // Pool should have evicted something to stay under max
        assertTrue(smallPool.getCurrentSize() <= 2048);
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await(); // Wait for all threads to be ready
                    var buffer = pool.allocate(1024);
                    Thread.sleep(ThreadLocalRandom.current().nextInt(10));
                    pool.returnToPool(buffer);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }
        
        latch.countDown(); // Start all threads
        
        for (var future : futures) {
            assertTrue(future.get());
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        // Pool should still be in valid state
        assertTrue(pool.getCurrentSize() >= 0);
        assertTrue(pool.getCurrentSize() <= 1024 * 1024);
    }
    
    @Test
    void testPoolStatistics() {
        var stats = pool.getPoolStatistics();
        assertNotNull(stats);
        assertEquals(0, stats.getHitCount());
        assertEquals(0, stats.getMissCount());
        assertEquals(0, stats.getEvictionCount());
        
        // Allocate and return
        var buffer = pool.allocate(1024);
        pool.returnToPool(buffer);
        
        // Reuse
        pool.allocate(1024);
        
        stats = pool.getPoolStatistics();
        assertEquals(1, stats.getHitCount());
        assertEquals(1, stats.getMissCount());
    }
    
    @Test
    void testClearPool() {
        pool.allocate(1024);
        pool.allocate(2048);
        
        // Return buffers to pool
        var buffer1 = pool.allocate(1024);
        var buffer2 = pool.allocate(2048);
        pool.returnToPool(buffer1);
        pool.returnToPool(buffer2);
        
        assertTrue(pool.getCurrentSize() > 0);
        
        pool.clear();
        
        assertEquals(0, pool.getCurrentSize());
    }
    
    @Test
    void testDirectBufferAllocation() {
        var buffer = pool.allocate(1024);
        assertNotNull(buffer);
        assertTrue(buffer.isDirect());
    }
    
    @Test
    void testZeroSizeAllocation() {
        var buffer = pool.allocate(0);
        assertNotNull(buffer);
        assertEquals(0, buffer.capacity());
    }
    
    @Test
    void testNegativeSizeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> {
            pool.allocate(-1);
        });
    }
    
    @Test
    void testPoolWithNoEviction() {
        // Pool with infinite idle time
        var noEvictPool = new MemoryPool(1024 * 1024, Duration.ofDays(365));
        
        var buffer = noEvictPool.allocate(1024);
        noEvictPool.returnToPool(buffer);
        
        noEvictPool.evictExpired();
        assertEquals(1024, noEvictPool.getCurrentSize()); // Should not evict
    }
}