package com.hellblazer.luciferase.render.voxel.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.lang.foreign.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FFM memory pool.
 */
public class FFMMemoryPoolTest {
    
    private FFMMemoryPool pool;
    private static final long SEGMENT_SIZE = 4096;
    
    @BeforeEach
    public void setUp() {
        pool = new FFMMemoryPool(SEGMENT_SIZE);
    }
    
    @AfterEach
    public void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }
    
    @Test
    @DisplayName("Test basic acquire and release")
    public void testAcquireAndRelease() {
        // Initial state
        assertEquals(0, pool.getAvailableCount());
        assertEquals(0, pool.getAllocatedCount());
        assertEquals(0, pool.getBorrowedCount());
        
        // Acquire segment
        var segment = pool.acquire();
        assertNotNull(segment);
        assertEquals(SEGMENT_SIZE, segment.byteSize());
        assertEquals(0, pool.getAvailableCount());
        assertEquals(1, pool.getAllocatedCount());
        assertEquals(1, pool.getBorrowedCount());
        
        // Write to segment
        segment.set(ValueLayout.JAVA_INT, 0, 42);
        assertEquals(42, segment.get(ValueLayout.JAVA_INT, 0));
        
        // Release segment
        pool.release(segment);
        assertEquals(1, pool.getAvailableCount());
        assertEquals(1, pool.getAllocatedCount());
        assertEquals(0, pool.getBorrowedCount());
        
        // Reacquire should return same segment (cleared)
        var segment2 = pool.acquire();
        assertEquals(0, segment2.get(ValueLayout.JAVA_INT, 0), 
            "Segment should be cleared on release");
    }
    
    @Test
    @DisplayName("Test pre-allocation")
    public void testPreallocation() {
        // Pre-allocate segments
        pool.preallocate(10);
        
        assertEquals(10, pool.getAvailableCount());
        assertEquals(10, pool.getAllocatedCount());
        assertEquals(0, pool.getBorrowedCount());
        
        // Acquire should not allocate new segments
        var segments = new ArrayList<MemorySegment>();
        for (int i = 0; i < 10; i++) {
            segments.add(pool.acquire());
        }
        
        assertEquals(0, pool.getAvailableCount());
        assertEquals(10, pool.getAllocatedCount());
        assertEquals(10, pool.getBorrowedCount());
        
        // Release all
        segments.forEach(pool::release);
        assertEquals(10, pool.getAvailableCount());
    }
    
    @Test
    @DisplayName("Test max pool size limit")
    public void testMaxPoolSize() {
        var limitedPool = new FFMMemoryPool(SEGMENT_SIZE, 5, true, Arena.ofShared());
        
        try {
            // Acquire and release more than max
            var segments = new ArrayList<MemorySegment>();
            for (int i = 0; i < 10; i++) {
                segments.add(limitedPool.acquire());
            }
            
            assertEquals(10, limitedPool.getAllocatedCount());
            
            // Release all
            segments.forEach(limitedPool::release);
            
            // Only max should be kept in pool
            assertEquals(5, limitedPool.getAvailableCount(), 
                "Pool should only keep up to maxPoolSize segments");
        } finally {
            limitedPool.close();
        }
    }
    
    @Test
    @DisplayName("Test clear on release option")
    public void testClearOnRelease() {
        // Test with clear enabled (default)
        var segment = pool.acquire();
        segment.set(ValueLayout.JAVA_LONG, 0, 0xDEADBEEFL);
        pool.release(segment);
        
        var reacquired = pool.acquire();
        assertEquals(0, reacquired.get(ValueLayout.JAVA_LONG, 0), 
            "Segment should be cleared");
        pool.release(reacquired);
        
        // Test with clear disabled
        var noClearPool = new FFMMemoryPool(SEGMENT_SIZE, 128, false, Arena.ofShared());
        try {
            var segment2 = noClearPool.acquire();
            segment2.set(ValueLayout.JAVA_LONG, 0, 0xCAFEBABEL);
            noClearPool.release(segment2);
            
            var reacquired2 = noClearPool.acquire();
            assertEquals(0xCAFEBABEL, reacquired2.get(ValueLayout.JAVA_LONG, 0), 
                "Segment should not be cleared when clearOnRelease is false");
        } finally {
            noClearPool.close();
        }
    }
    
    @Test
    @DisplayName("Test concurrent access")
    public void testConcurrentAccess() throws InterruptedException, ExecutionException {
        int threadCount = 10;
        int operationsPerThread = 100;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(1);
        var successCount = new AtomicInteger(0);
        
        try {
            var futures = new ArrayList<Future<Void>>();
            
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    latch.await();
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        var segment = pool.acquire();
                        assertNotNull(segment);
                        
                        // Do some work
                        segment.set(ValueLayout.JAVA_INT, 0, j);
                        assertEquals(j, segment.get(ValueLayout.JAVA_INT, 0));
                        
                        pool.release(segment);
                        successCount.incrementAndGet();
                    }
                    return null;
                }));
            }
            
            // Start all threads
            latch.countDown();
            
            // Wait for completion
            for (var future : futures) {
                future.get();
            }
            
            assertEquals(threadCount * operationsPerThread, successCount.get(), 
                "All operations should complete successfully");
            
            // Pool should be in consistent state
            assertTrue(pool.getBorrowedCount() >= 0);
            assertTrue(pool.getAvailableCount() >= 0);
        } finally {
            executor.shutdown();
        }
    }
    
    @Test
    @DisplayName("Test segment size validation")
    public void testSegmentSizeValidation() {
        var segment = pool.acquire();
        pool.release(segment);
        
        // Try to release segment with wrong size
        try (var arena = Arena.ofConfined()) {
            var wrongSizeSegment = arena.allocate(SEGMENT_SIZE * 2);
            
            assertThrows(IllegalArgumentException.class, 
                () -> pool.release(wrongSizeSegment),
                "Should reject segment with wrong size");
        }
    }
    
    @Test
    @DisplayName("Test statistics")
    public void testStatistics() {
        pool.preallocate(5);
        var segment1 = pool.acquire();
        var segment2 = pool.acquire();
        
        var stats = pool.getStatistics();
        assertTrue(stats.contains("segmentSize=" + SEGMENT_SIZE));
        assertTrue(stats.contains("available=3"));
        assertTrue(stats.contains("borrowed=2"));
        assertTrue(stats.contains("allocated=5"));
        
        pool.release(segment1);
        pool.release(segment2);
    }
    
    @Test
    @DisplayName("Test builder pattern")
    public void testBuilder() {
        var customPool = new FFMMemoryPool.Builder()
            .segmentSize(8192)
            .maxPoolSize(20)
            .clearOnRelease(false)
            .arena(Arena.ofShared())
            .build();
        
        try {
            assertEquals(8192, customPool.getSegmentSize());
            
            var segment = customPool.acquire();
            assertEquals(8192, segment.byteSize());
            customPool.release(segment);
        } finally {
            customPool.close();
        }
    }
    
    @Test
    @DisplayName("Test memory alignment")
    public void testMemoryAlignment() {
        var segment = pool.acquire();
        
        // Check 256-byte alignment (as specified in implementation)
        long address = segment.address();
        assertEquals(0, address % 256, 
            "Segments should be 256-byte aligned for GPU compatibility");
        
        pool.release(segment);
    }
    
    @Test
    @DisplayName("Test pool clear")
    public void testPoolClear() {
        pool.preallocate(10);
        assertEquals(10, pool.getAvailableCount());
        
        pool.clear();
        assertEquals(0, pool.getAvailableCount());
        
        // Should still be able to acquire new segments
        var segment = pool.acquire();
        assertNotNull(segment);
        pool.release(segment);
    }
}