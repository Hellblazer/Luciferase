package com.hellblazer.luciferase.render.voxel.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused test for PageAllocator statistics tracking fixes.
 */
public class PageAllocatorStatisticsTest {
    
    private Arena arena;
    private PageAllocator allocator;
    
    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        allocator = new PageAllocator(arena);
    }
    
    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }
    
    @Test
    void testSingleAllocationStatistics() {
        // Test single allocations (both new and reused)
        MemorySegment page1 = allocator.allocatePage();
        assertEquals(1, allocator.getTotalAllocated(), "First allocation should increment total");
        assertEquals(PageAllocator.PAGE_SIZE_BYTES, allocator.getTotalBytesAllocated(), "Bytes should match new allocation");
        
        allocator.freePage(page1);
        assertEquals(1, allocator.getTotalFreed(), "Free should increment freed count");
        assertEquals(1, allocator.getFreeListSize(), "Page should be in free list");
        
        // Reuse the page
        MemorySegment page2 = allocator.allocatePage();
        assertEquals(2, allocator.getTotalAllocated(), "Reused allocation should also increment total");
        assertEquals(PageAllocator.PAGE_SIZE_BYTES, allocator.getTotalBytesAllocated(), "Bytes should not change for reused page");
        assertEquals(0, allocator.getFreeListSize(), "Free list should be empty after reuse");
    }
    
    @Test
    void testBulkAllocationStatistics() {
        // Test bulk operations
        MemorySegment[] pages1 = allocator.allocatePages(10);
        
        assertEquals(10, allocator.getTotalAllocated(), "Bulk allocation should count all pages");
        assertEquals(10L * PageAllocator.PAGE_SIZE_BYTES, allocator.getTotalBytesAllocated(), "Bytes should match new allocations");
        
        allocator.freePages(pages1);
        assertEquals(10, allocator.getTotalFreed(), "Bulk free should count all pages");
        assertEquals(10, allocator.getFreeListSize(), "All pages should be in free list");
        
        // Bulk allocate again (should reuse)
        MemorySegment[] pages2 = allocator.allocatePages(5);
        assertEquals(15, allocator.getTotalAllocated(), "Second bulk allocation should add to total");
        assertEquals(10L * PageAllocator.PAGE_SIZE_BYTES, allocator.getTotalBytesAllocated(), "Bytes should not change for reused pages");
        assertEquals(5, allocator.getFreeListSize(), "Free list should have remaining pages");
    }
    
    @Test
    void testConcurrentStatisticsConsistency() throws InterruptedException {
        var threadCount = 4;
        var pagesPerThread = 25;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var countDownLatch = new CountDownLatch(threadCount);
        
        // Concurrent allocation and deallocation
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    // Each thread allocates and frees pages
                    MemorySegment[] pages = allocator.allocatePages(pagesPerThread);
                    allocator.freePages(pages);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        
        assertTrue(countDownLatch.await(10, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        
        // Verify final statistics
        long expectedAllocated = threadCount * pagesPerThread;
        long expectedFreed = threadCount * pagesPerThread;
        
        assertEquals(expectedAllocated, allocator.getTotalAllocated(), 
                    "Total allocated should be accurate");
        assertEquals(expectedFreed, allocator.getTotalFreed(), 
                    "Total freed should be accurate");
        assertEquals(0, allocator.getActivePageCount(), 
                    "Active count should be zero");
        
        // Statistics should be self-consistent
        assertFalse(allocator.validateAndCorrectStatistics(), 
                   "Statistics should be consistent without corrections");
    }
    
    @Test
    void testStatisticsValidation() {
        // Test the validation method
        MemorySegment[] pages = allocator.allocatePages(5);
        allocator.freePages(pages);
        
        // Should be consistent
        assertFalse(allocator.validateAndCorrectStatistics(), 
                   "Statistics should be consistent initially");
        
        // Verify counts are correct
        assertEquals(5, allocator.getTotalAllocated());
        assertEquals(5, allocator.getTotalFreed());
        assertEquals(0, allocator.getActivePageCount());
        assertEquals(5, allocator.getFreeListSize());
    }
}