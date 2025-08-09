package com.hellblazer.luciferase.render.voxel.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive test suite for PageAllocator functionality.
 * Tests allocation, deallocation, alignment guarantees, thread safety,
 * memory arena lifecycle, statistics tracking, and stress scenarios.
 */
public class PageAllocatorTest {
    
    private static final Logger log = LoggerFactory.getLogger(PageAllocatorTest.class);
    
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
    
    // ================================================================================
    // Basic Allocation and Deallocation Tests
    // ================================================================================
    
    @Test
    void testSinglePageAllocation() {
        MemorySegment page = allocator.allocatePage();
        
        assertNotNull(page, "Allocated page should not be null");
        assertEquals(PageAllocator.PAGE_SIZE_BYTES, page.byteSize(), 
                    "Page size should be 8KB");
        assertEquals(1, allocator.getTotalAllocated(), 
                    "Total allocated count should be 1");
        assertEquals(1, allocator.getActivePageCount(), 
                    "Active page count should be 1");
    }
    
    @Test
    void testMultiplePageAllocations() {
        var pageCount = 10;
        List<MemorySegment> pages = new ArrayList<>();
        
        for (int i = 0; i < pageCount; i++) {
            pages.add(allocator.allocatePage());
        }
        
        assertEquals(pageCount, pages.size(), "Should allocate correct number of pages");
        assertEquals(pageCount, allocator.getTotalAllocated(), 
                    "Total allocated should match page count");
        assertEquals(pageCount, allocator.getActivePageCount(), 
                    "Active pages should match page count");
        
        // Verify all pages are different
        for (int i = 0; i < pages.size(); i++) {
            for (int j = i + 1; j < pages.size(); j++) {
                assertNotEquals(pages.get(i).address(), pages.get(j).address(),
                               "Pages should have different addresses");
            }
        }
    }
    
    @Test
    void testPageDeallocation() {
        MemorySegment page = allocator.allocatePage();
        assertEquals(1, allocator.getActivePageCount(), "Should have 1 active page");
        
        allocator.freePage(page);
        
        assertEquals(0, allocator.getActivePageCount(), "Should have 0 active pages");
        assertEquals(1, allocator.getTotalFreed(), "Should have freed 1 page");
        assertEquals(1, allocator.getFreeListSize(), "Free list should contain 1 page");
    }
    
    @Test
    void testPageRecycling() {
        // Allocate and free a page
        MemorySegment page1 = allocator.allocatePage();
        allocator.freePage(page1);
        
        // Allocate another page - should reuse the freed one
        MemorySegment page2 = allocator.allocatePage();
        
        assertEquals(page1.address(), page2.address(), 
                    "Second allocation should reuse first page");
        assertEquals(2, allocator.getTotalAllocated(), 
                    "Total allocated should be 2 (includes recycled)");
        assertEquals(0, allocator.getFreeListSize(), 
                    "Free list should be empty after reuse");
    }
    
    // ================================================================================
    // Alignment Tests
    // ================================================================================
    
    @Test
    void testPageAlignment() {
        var pageCount = 50;
        
        for (int i = 0; i < pageCount; i++) {
            MemorySegment page = allocator.allocatePage();
            long address = page.address();
            
            assertEquals(0, address % PageAllocator.PAGE_ALIGNMENT,
                        String.format("Page %d address 0x%x not aligned to %d bytes", 
                                    i, address, PageAllocator.PAGE_ALIGNMENT));
        }
    }
    
    @Test
    void testAlignmentWithRecycling() {
        List<MemorySegment> pages = new ArrayList<>();
        
        // Allocate pages
        for (int i = 0; i < 10; i++) {
            pages.add(allocator.allocatePage());
        }
        
        // Free all pages
        for (MemorySegment page : pages) {
            allocator.freePage(page);
        }
        
        // Reallocate and verify alignment is maintained
        for (int i = 0; i < 10; i++) {
            MemorySegment page = allocator.allocatePage();
            long address = page.address();
            
            assertEquals(0, address % PageAllocator.PAGE_ALIGNMENT,
                        String.format("Recycled page %d address 0x%x not aligned to %d bytes", 
                                    i, address, PageAllocator.PAGE_ALIGNMENT));
        }
    }
    
    // ================================================================================
    // Thread Safety Tests
    // ================================================================================
    
    @Test
    @Timeout(30)
    void testConcurrentAllocations() throws InterruptedException {
        var threadCount = 8;
        var allocationsPerThread = 100;
        var totalExpectedAllocations = threadCount * allocationsPerThread;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var countDownLatch = new CountDownLatch(threadCount);
        var allocatedPages = Collections.synchronizedList(new ArrayList<MemorySegment>());
        var exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        
        // Start allocation threads
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < allocationsPerThread; i++) {
                        MemorySegment page = allocator.allocatePage();
                        allocatedPages.add(page);
                        
                        // Verify page properties
                        assertEquals(PageAllocator.PAGE_SIZE_BYTES, page.byteSize());
                        assertEquals(0, page.address() % PageAllocator.PAGE_ALIGNMENT);
                    }
                } catch (Throwable e) {
                    exceptions.add(e);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        
        assertTrue(countDownLatch.await(25, TimeUnit.SECONDS), 
                  "All allocation threads should complete");
        executor.shutdown();
        
        assertTrue(exceptions.isEmpty(), 
                  "No exceptions should occur during concurrent allocations: " + exceptions);
        assertEquals(totalExpectedAllocations, allocatedPages.size(), 
                    "Should allocate expected number of pages");
        assertEquals(totalExpectedAllocations, allocator.getTotalAllocated(), 
                    "Allocator statistics should match");
        
        // Verify all pages have unique addresses
        var uniqueAddresses = allocatedPages.stream()
                                          .mapToLong(MemorySegment::address)
                                          .distinct()
                                          .count();
        assertEquals(totalExpectedAllocations, uniqueAddresses, 
                    "All pages should have unique addresses");
    }
    
    @Test
    @Timeout(30) 
    void testConcurrentAllocationAndDeallocation() throws InterruptedException {
        var threadCount = 6;
        var operationsPerThread = 200;
        var duration = 10; // seconds
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var stopFlag = new AtomicBoolean(false);
        var allocCounter = new AtomicLong(0);
        var freeCounter = new AtomicLong(0);
        var exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        
        // Start mixed allocation/deallocation threads
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                var threadPages = new ArrayList<MemorySegment>();
                var random = ThreadLocalRandom.current();
                
                try {
                    while (!stopFlag.get()) {
                        if (threadPages.isEmpty() || random.nextBoolean()) {
                            // Allocate
                            MemorySegment page = allocator.allocatePage();
                            threadPages.add(page);
                            allocCounter.incrementAndGet();
                            
                            // Verify page properties
                            assertEquals(PageAllocator.PAGE_SIZE_BYTES, page.byteSize());
                            assertEquals(0, page.address() % PageAllocator.PAGE_ALIGNMENT);
                            
                        } else {
                            // Deallocate
                            int index = random.nextInt(threadPages.size());
                            MemorySegment page = threadPages.remove(index);
                            allocator.freePage(page);
                            freeCounter.incrementAndGet();
                        }
                        
                        // Small delay to allow thread interleaving
                        if (random.nextInt(100) == 0) {
                            Thread.yield();
                        }
                    }
                    
                    // Free remaining pages
                    for (MemorySegment page : threadPages) {
                        allocator.freePage(page);
                        freeCounter.incrementAndGet();
                    }
                    
                } catch (Throwable e) {
                    exceptions.add(e);
                }
            });
        }
        
        // Let threads run for specified duration
        Thread.sleep(duration * 1000L);
        stopFlag.set(true);
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), 
                  "All threads should terminate");
        
        assertTrue(exceptions.isEmpty(), 
                  "No exceptions should occur: " + exceptions);
        assertTrue(allocCounter.get() > 0, "Should have performed allocations");
        assertTrue(freeCounter.get() > 0, "Should have performed deallocations");
        
        log.info("Concurrent test completed: {} allocations, {} deallocations", 
                allocCounter.get(), freeCounter.get());
    }
    
    // ================================================================================
    // Memory Arena Lifecycle Tests
    // ================================================================================
    
    @Test
    void testArenaClosureInvalidatesPages() {
        MemorySegment page;
        
        try (Arena testArena = Arena.ofShared()) {
            PageAllocator testAllocator = new PageAllocator(testArena);
            page = testAllocator.allocatePage();
            
            // Page should be valid while arena is open
            assertDoesNotThrow(() -> page.get(ValueLayout.JAVA_INT, 0));
        }
        
        // Page should be invalid after arena is closed
        assertThrows(IllegalStateException.class, 
                    () -> page.get(ValueLayout.JAVA_INT, 0),
                    "Page should be invalid after arena closure");
    }
    
    @Test
    void testMultipleAllocatorsWithSameArena() {
        PageAllocator allocator2 = new PageAllocator(arena);
        
        MemorySegment page1 = allocator.allocatePage();
        MemorySegment page2 = allocator2.allocatePage();
        
        assertNotNull(page1);
        assertNotNull(page2);
        assertNotEquals(page1.address(), page2.address(), 
                       "Different allocators should allocate different pages");
        
        // Both allocators should work independently
        assertEquals(1, allocator.getTotalAllocated());
        assertEquals(1, allocator2.getTotalAllocated());
    }
    
    // ================================================================================
    // Statistics Tracking Tests
    // ================================================================================
    
    @Test
    void testStatisticsTracking() {
        assertEquals(0, allocator.getTotalAllocated(), "Initial allocated count should be 0");
        assertEquals(0, allocator.getTotalFreed(), "Initial freed count should be 0");
        assertEquals(0, allocator.getActivePageCount(), "Initial active count should be 0");
        assertEquals(0, allocator.getFreeListSize(), "Initial free list size should be 0");
        
        // Allocate pages
        List<MemorySegment> pages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pages.add(allocator.allocatePage());
        }
        
        assertEquals(5, allocator.getTotalAllocated());
        assertEquals(0, allocator.getTotalFreed());
        assertEquals(5, allocator.getActivePageCount());
        assertEquals(0, allocator.getFreeListSize());
        assertEquals(5L * PageAllocator.PAGE_SIZE_BYTES, allocator.getTotalBytesAllocated());
        
        // Free some pages
        allocator.freePage(pages.get(0));
        allocator.freePage(pages.get(1));
        
        assertEquals(5, allocator.getTotalAllocated());
        assertEquals(2, allocator.getTotalFreed());
        assertEquals(3, allocator.getActivePageCount());
        assertEquals(2, allocator.getFreeListSize());
        
        // Verify efficiency calculation
        double expectedEfficiency = (3.0 / 5.0) * 100.0;
        assertEquals(expectedEfficiency, allocator.getMemoryEfficiency(), 0.1);
    }
    
    @Test
    void testStatisticsConsistency() throws InterruptedException {
        var threadCount = 4;
        var operationsPerThread = 100;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var countDownLatch = new CountDownLatch(threadCount);
        var totalOperations = new AtomicInteger(0);
        
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    List<MemorySegment> threadPages = new ArrayList<>();
                    
                    // Allocate pages
                    for (int i = 0; i < operationsPerThread; i++) {
                        threadPages.add(allocator.allocatePage());
                        totalOperations.incrementAndGet();
                    }
                    
                    // Free half of them
                    for (int i = 0; i < operationsPerThread / 2; i++) {
                        allocator.freePage(threadPages.get(i));
                        totalOperations.incrementAndGet();
                    }
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        
        assertTrue(countDownLatch.await(15, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Verify statistics consistency
        long expectedAllocated = threadCount * operationsPerThread;
        long expectedFreed = threadCount * (operationsPerThread / 2);
        long expectedActive = expectedAllocated - expectedFreed;
        
        assertEquals(expectedAllocated, allocator.getTotalAllocated(), 
                    "Total allocated should match expected");
        assertEquals(expectedFreed, allocator.getTotalFreed(), 
                    "Total freed should match expected");
        assertEquals(expectedActive, allocator.getActivePageCount(), 
                    "Active pages should match expected");
        
        // Active + Free list should not exceed total allocated
        assertTrue(allocator.getActivePageCount() + allocator.getFreeListSize() 
                  <= allocator.getTotalAllocated(),
                  "Active + free list should not exceed total allocated");
    }
    
    // ================================================================================
    // Bulk Operations Tests
    // ================================================================================
    
    @Test
    void testBulkAllocation() {
        var pageCount = 20;
        MemorySegment[] pages = allocator.allocatePages(pageCount);
        
        assertEquals(pageCount, pages.length, "Should allocate correct number of pages");
        assertEquals(pageCount, allocator.getTotalAllocated(), 
                    "Statistics should reflect bulk allocation");
        
        // Verify all pages are valid and aligned
        for (int i = 0; i < pages.length; i++) {
            assertNotNull(pages[i], "Page " + i + " should not be null");
            assertEquals(PageAllocator.PAGE_SIZE_BYTES, pages[i].byteSize(), 
                        "Page " + i + " should have correct size");
            assertEquals(0, pages[i].address() % PageAllocator.PAGE_ALIGNMENT,
                        "Page " + i + " should be aligned");
        }
    }
    
    @Test
    void testBulkDeallocation() {
        var pageCount = 15;
        MemorySegment[] pages = allocator.allocatePages(pageCount);
        
        allocator.freePages(pages);
        
        assertEquals(pageCount, allocator.getTotalFreed(), 
                    "Should have freed all pages");
        assertEquals(0, allocator.getActivePageCount(), 
                    "Should have no active pages");
        assertTrue(allocator.getFreeListSize() > 0, 
                  "Free list should contain pages");
    }
    
    // ================================================================================
    // Error Handling Tests
    // ================================================================================
    
    @Test
    void testNullArenaRejection() {
        assertThrows(IllegalArgumentException.class, 
                    () -> new PageAllocator(null),
                    "Should reject null arena");
    }
    
    @Test
    void testNullPageFreeRejection() {
        assertThrows(IllegalArgumentException.class, 
                    () -> allocator.freePage(null),
                    "Should reject null page for freeing");
    }
    
    @Test
    void testInvalidPageSizeFreeRejection() {
        try (Arena testArena = Arena.ofShared()) {
            MemorySegment invalidPage = testArena.allocate(1024); // Wrong size
            
            assertThrows(IllegalArgumentException.class, 
                        () -> allocator.freePage(invalidPage),
                        "Should reject page with wrong size");
        }
    }
    
    @Test
    void testBulkAllocationWithZeroPages() {
        assertThrows(IllegalArgumentException.class, 
                    () -> allocator.allocatePages(0),
                    "Should reject zero page count");
    }
    
    @Test
    void testBulkAllocationWithNegativePages() {
        assertThrows(IllegalArgumentException.class, 
                    () -> allocator.allocatePages(-5),
                    "Should reject negative page count");
    }
    
    @Test
    void testBulkFreeWithNullArray() {
        assertThrows(IllegalArgumentException.class, 
                    () -> allocator.freePages(null),
                    "Should reject null pages array");
    }
    
    @Test
    void testBulkFreeWithNullElements() {
        MemorySegment[] pages = new MemorySegment[3];
        pages[0] = allocator.allocatePage();
        pages[1] = null; // Null element
        pages[2] = allocator.allocatePage();
        
        assertThrows(IllegalArgumentException.class, 
                    () -> allocator.freePages(pages),
                    "Should reject array with null elements");
    }
    
    // ================================================================================
    // Stress Tests
    // ================================================================================
    
    @Test
    @Timeout(60)
    void testHighVolumeAllocations() {
        var pageCount = 10000;
        List<MemorySegment> pages = new ArrayList<>();
        
        log.info("Starting high volume allocation test: {} pages", pageCount);
        long startTime = System.nanoTime();
        
        // Allocate many pages
        for (int i = 0; i < pageCount; i++) {
            pages.add(allocator.allocatePage());
            
            if (i > 0 && i % 1000 == 0) {
                log.debug("Allocated {} pages", i);
            }
        }
        
        long allocationTime = System.nanoTime() - startTime;
        startTime = System.nanoTime();
        
        // Free all pages
        for (MemorySegment page : pages) {
            allocator.freePage(page);
        }
        
        long freeTime = System.nanoTime() - startTime;
        
        assertEquals(pageCount, allocator.getTotalAllocated());
        assertEquals(pageCount, allocator.getTotalFreed());
        assertEquals(0, allocator.getActivePageCount());
        
        log.info("High volume test completed: {} pages allocated in {} ms, freed in {} ms", 
                pageCount, allocationTime / 1_000_000, freeTime / 1_000_000);
    }
    
    @Test
    @Timeout(45)
    void testMixedWorkloadStress() throws InterruptedException {
        var duration = 20; // seconds
        var threadCount = 6;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var stopFlag = new AtomicBoolean(false);
        var operationCounts = new ConcurrentHashMap<String, AtomicLong>();
        operationCounts.put("allocations", new AtomicLong(0));
        operationCounts.put("deallocations", new AtomicLong(0));
        operationCounts.put("bulk_ops", new AtomicLong(0));
        
        var exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        
        log.info("Starting mixed workload stress test for {} seconds with {} threads", 
                duration, threadCount);
        
        // Start worker threads with different workload patterns
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            
            executor.submit(() -> {
                var threadPages = new ArrayList<MemorySegment>();
                var random = ThreadLocalRandom.current();
                
                try {
                    while (!stopFlag.get()) {
                        int operation = random.nextInt(10);
                        
                        if (operation < 4) {
                            // 40% - Single allocation
                            MemorySegment page = allocator.allocatePage();
                            threadPages.add(page);
                            operationCounts.get("allocations").incrementAndGet();
                            
                            // Verify page immediately
                            assertEquals(PageAllocator.PAGE_SIZE_BYTES, page.byteSize());
                            
                        } else if (operation < 7 && !threadPages.isEmpty()) {
                            // 30% - Single deallocation
                            int index = random.nextInt(threadPages.size());
                            MemorySegment page = threadPages.remove(index);
                            allocator.freePage(page);
                            operationCounts.get("deallocations").incrementAndGet();
                            
                        } else if (operation < 8) {
                            // 10% - Bulk allocation
                            int bulkSize = random.nextInt(10) + 1;
                            MemorySegment[] bulkPages = allocator.allocatePages(bulkSize);
                            for (MemorySegment page : bulkPages) {
                                threadPages.add(page);
                            }
                            operationCounts.get("allocations").addAndGet(bulkSize);
                            operationCounts.get("bulk_ops").incrementAndGet();
                            
                        } else if (operation < 9 && threadPages.size() >= 5) {
                            // 10% - Bulk deallocation
                            int bulkSize = Math.min(5, threadPages.size());
                            MemorySegment[] bulkPages = new MemorySegment[bulkSize];
                            for (int i = 0; i < bulkSize; i++) {
                                bulkPages[i] = threadPages.remove(threadPages.size() - 1);
                            }
                            allocator.freePages(bulkPages);
                            operationCounts.get("deallocations").addAndGet(bulkSize);
                            operationCounts.get("bulk_ops").incrementAndGet();
                            
                        } else {
                            // 10% - Statistics query (stress concurrent reads)
                            allocator.getStatistics();
                            allocator.getMemoryEfficiency();
                        }
                        
                        // Occasional yield to allow thread switching
                        if (random.nextInt(50) == 0) {
                            Thread.yield();
                        }
                    }
                    
                    // Cleanup remaining pages
                    for (MemorySegment page : threadPages) {
                        allocator.freePage(page);
                        operationCounts.get("deallocations").incrementAndGet();
                    }
                    
                } catch (Throwable e) {
                    log.error("Thread {} encountered exception", threadId, e);
                    exceptions.add(e);
                }
            });
        }
        
        // Let the stress test run
        Thread.sleep(duration * 1000L);
        stopFlag.set(true);
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), 
                  "All threads should complete cleanup");
        
        assertTrue(exceptions.isEmpty(), 
                  "No exceptions should occur during stress test: " + exceptions);
        
        long totalAllocations = operationCounts.get("allocations").get();
        long totalDeallocations = operationCounts.get("deallocations").get();
        long bulkOperations = operationCounts.get("bulk_ops").get();
        
        assertTrue(totalAllocations > 0, "Should have performed allocations");
        assertTrue(totalDeallocations > 0, "Should have performed deallocations");
        
        log.info("Stress test completed: {} allocations, {} deallocations, {} bulk ops", 
                totalAllocations, totalDeallocations, bulkOperations);
        log.info("Final statistics: {}", allocator.getStatistics());
        
        // Verify statistics consistency
        assertEquals(totalAllocations, allocator.getTotalAllocated(), 
                    "Statistics should match actual operations");
        assertEquals(totalDeallocations, allocator.getTotalFreed(), 
                    "Statistics should match actual operations");
    }
    
    @Test
    @Timeout(30)
    void testMemoryPressureHandling() {
        log.info("Testing memory pressure handling");
        
        List<MemorySegment> pages = new ArrayList<>();
        var maxAttempts = 50000;
        var actualAllocations = 0;
        
        try {
            // Allocate until we hit memory pressure
            for (int i = 0; i < maxAttempts; i++) {
                pages.add(allocator.allocatePage());
                actualAllocations++;
                
                if (i > 0 && i % 5000 == 0) {
                    log.debug("Allocated {} pages, {} MB", 
                             i, (i * PageAllocator.PAGE_SIZE_BYTES) / (1024 * 1024));
                }
            }
        } catch (OutOfMemoryError e) {
            log.info("Memory pressure reached after {} allocations", actualAllocations);
        }
        
        assertTrue(actualAllocations > 1000, 
                  "Should be able to allocate reasonable number of pages");
        assertEquals(actualAllocations, allocator.getTotalAllocated());
        
        // Free half the pages and verify recycling works
        int pagesToFree = actualAllocations / 2;
        for (int i = 0; i < pagesToFree; i++) {
            allocator.freePage(pages.get(i));
        }
        
        assertEquals(pagesToFree, allocator.getTotalFreed());
        assertTrue(allocator.getFreeListSize() > 0, "Free list should contain pages");
        
        // Allocate some more pages to test recycling under pressure
        for (int i = 0; i < Math.min(100, pagesToFree); i++) {
            MemorySegment page = allocator.allocatePage();
            assertNotNull(page);
        }
        
        log.info("Memory pressure test completed successfully");
    }
    
    // ================================================================================
    // Utility and Information Tests
    // ================================================================================
    
    @Test
    void testPageWriteAndRead() {
        MemorySegment page = allocator.allocatePage();
        
        // Write test data
        for (int i = 0; i < PageAllocator.PAGE_SIZE_BYTES / 4; i++) {
            page.setAtIndex(ValueLayout.JAVA_INT, i, i * 2);
        }
        
        // Read and verify
        for (int i = 0; i < PageAllocator.PAGE_SIZE_BYTES / 4; i++) {
            int value = page.getAtIndex(ValueLayout.JAVA_INT, i);
            assertEquals(i * 2, value, "Data should be preserved");
        }
    }
    
    @Test
    void testAllocatorInfo() {
        assertEquals(PageAllocator.PAGE_SIZE_BYTES, allocator.getPageSize());
        assertEquals(PageAllocator.PAGE_ALIGNMENT, allocator.getPageAlignment());
        assertEquals(arena, allocator.getArena());
        
        String stats = allocator.getStatistics();
        assertNotNull(stats);
        assertTrue(stats.contains("PageAllocator Statistics"));
        assertTrue(stats.contains("Page Size: " + PageAllocator.PAGE_SIZE_BYTES));
    }
    
    @Test
    void testToString() {
        String toString = allocator.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("PageAllocator"));
        assertTrue(toString.contains("pageSize=" + PageAllocator.PAGE_SIZE_BYTES));
    }
    
    @Test
    void testFreeListManagement() {
        var maxFreePages = 1025; // Just over the limit
        List<MemorySegment> pages = new ArrayList<>();
        
        // Allocate pages
        for (int i = 0; i < maxFreePages; i++) {
            pages.add(allocator.allocatePage());
        }
        
        // Free all pages
        for (MemorySegment page : pages) {
            allocator.freePage(page);
        }
        
        // Free list should be capped
        assertTrue(allocator.getFreeListSize() <= 1024, 
                  "Free list should be capped at maximum size");
        
        // Clear free list and verify
        allocator.clearFreeList();
        assertEquals(0, allocator.getFreeListSize(), 
                    "Free list should be empty after clear");
    }
    
    @Test
    void testStatisticsValidationAndCorrection() {
        // Test the new statistics validation functionality
        MemorySegment[] pages = allocator.allocatePages(10);
        allocator.freePages(pages);
        
        // Statistics should be consistent
        assertFalse(allocator.validateAndCorrectStatistics(), 
                   "Statistics should be consistent initially");
        
        // Verify the corrected bulk operations maintain accurate counts
        assertEquals(10, allocator.getTotalAllocated(), 
                    "Bulk allocation should update total correctly");
        assertEquals(10, allocator.getTotalFreed(), 
                    "Bulk deallocation should update total correctly");
        assertEquals(0, allocator.getActivePageCount(), 
                    "Active count should be correct after bulk operations");
        assertTrue(allocator.getFreeListSize() > 0, 
                  "Free list should contain pages after bulk deallocation");
    }
    
    @Test
    void testConcurrentBulkOperationsStatistics() throws InterruptedException {
        var threadCount = 4;
        var pagesPerThread = 50;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var countDownLatch = new CountDownLatch(threadCount);
        var exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        
        // Concurrent bulk operations
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    // Bulk allocate
                    MemorySegment[] pages = allocator.allocatePages(pagesPerThread);
                    
                    // Bulk free
                    allocator.freePages(pages);
                    
                } catch (Throwable e) {
                    exceptions.add(e);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        
        assertTrue(countDownLatch.await(15, TimeUnit.SECONDS), 
                  "All threads should complete");
        executor.shutdown();
        
        assertTrue(exceptions.isEmpty(), 
                  "No exceptions should occur: " + exceptions);
        
        // Verify statistics are accurate after concurrent bulk operations
        long expectedAllocated = threadCount * pagesPerThread;
        long expectedFreed = threadCount * pagesPerThread;
        
        assertEquals(expectedAllocated, allocator.getTotalAllocated(), 
                    "Total allocated should be accurate");
        assertEquals(expectedFreed, allocator.getTotalFreed(), 
                    "Total freed should be accurate");
        assertEquals(0, allocator.getActivePageCount(), 
                    "Active count should be zero");
        
        // Statistics should be consistent
        assertFalse(allocator.validateAndCorrectStatistics(), 
                   "Statistics should remain consistent");
    }
}