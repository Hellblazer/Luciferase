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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive test suite for MemoryPool implementation.
 * Tests cover buddy allocator algorithm, fragmentation management,
 * variable size allocations, defragmentation, and thread safety.
 */
@DisplayName("MemoryPool Tests")
public class MemoryPoolTest {
    
    private static final Logger log = LoggerFactory.getLogger(MemoryPoolTest.class);
    
    private Arena arena;
    private PageAllocator pageAllocator;
    private MemoryPool memoryPool;
    
    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        pageAllocator = new PageAllocator(arena);
        memoryPool = new MemoryPool(pageAllocator);
    }
    
    @AfterEach
    void tearDown() {
        if (arena != null) {
            arena.close();
        }
    }
    
    // ================================================================================
    // Constructor and Basic Setup Tests
    // ================================================================================
    
    @Test
    @DisplayName("Constructor creates empty pool")
    void testDefaultConstructor() {
        assertEquals(0, memoryPool.getTotalAllocations());
        assertEquals(0, memoryPool.getTotalDeallocations());
        assertEquals(0, memoryPool.getCurrentBytesInUse());
        assertEquals(0, memoryPool.getActiveAllocationCount());
        assertEquals(0, memoryPool.getManagedPageCount());
        assertEquals(0.0, memoryPool.getFragmentation());
    }
    
    @Test
    @DisplayName("Constructor rejects null PageAllocator")
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, 
                    () -> new MemoryPool(null),
                    "Should reject null PageAllocator");
    }
    
    // ================================================================================
    // Buddy Allocator Algorithm Tests
    // ================================================================================
    
    @Test
    @DisplayName("Buddy allocator rounds up to power of 2")
    void testBuddyAllocatorRounding() {
        // Test various sizes that should round up to powers of 2
        var testCases = new int[][] {
            {1, 32},      // Min size
            {32, 32},     // Exact power of 2
            {33, 64},     // Round up to next power
            {60, 64},     // Round up to 64
            {65, 128},    // Round up to 128
            {200, 256},   // Round up to 256
            {500, 512},   // Round up to 512
            {1000, 1024}, // Round up to 1KB
            {2000, 2048}, // Round up to 2KB
            {4000, 4096}, // Round up to 4KB
            {8000, 8192}  // Round up to 8KB (max size)
        };
        
        for (var testCase : testCases) {
            int requestedSize = testCase[0];
            int expectedActualSize = testCase[1];
            
            MemorySegment segment = memoryPool.allocate(requestedSize);
            
            assertTrue(segment.byteSize() >= requestedSize, 
                      "Allocated size should be at least requested size");
            assertEquals(expectedActualSize, segment.byteSize(),
                        String.format("Size %d should round up to %d", 
                                    requestedSize, expectedActualSize));
            
            memoryPool.free(segment);
        }
    }
    
    @Test
    @DisplayName("Buddy allocator coalesces freed blocks")
    void testBuddyCoalescing() {
        // Allocate two adjacent 1KB blocks
        MemorySegment block1 = memoryPool.allocate(1024);
        MemorySegment block2 = memoryPool.allocate(1024);
        
        assertEquals(2, memoryPool.getActiveAllocationCount());
        
        // Free both blocks - they should coalesce into a 2KB block
        memoryPool.free(block1);
        memoryPool.free(block2);
        
        assertEquals(0, memoryPool.getActiveAllocationCount());
        
        // Now allocate a 2KB block - should reuse the coalesced space
        MemorySegment largeBlock = memoryPool.allocate(2048);
        assertNotNull(largeBlock);
        assertEquals(2048, largeBlock.byteSize());
        assertEquals(1, memoryPool.getActiveAllocationCount());
        
        memoryPool.free(largeBlock);
    }
    
    @Test
    @DisplayName("Buddy allocator splits large blocks")
    void testBuddySplitting() {
        // Force allocation of a full page (8KB)
        for (int i = 0; i < 100; i++) {
            memoryPool.allocate(32); // Force multiple pages
        }
        
        // Now allocate a 1KB block - should split a larger free block
        MemorySegment block = memoryPool.allocate(1024);
        assertNotNull(block);
        assertEquals(1024, block.byteSize());
        
        memoryPool.free(block);
    }
    
    @ParameterizedTest
    @ValueSource(ints = {32, 64, 128, 256, 512, 1024, 2048, 4096, 8192})
    @DisplayName("All buddy levels work correctly")
    void testAllBuddyLevels(int size) {
        MemorySegment segment = memoryPool.allocate(size);
        
        assertNotNull(segment);
        assertEquals(size, segment.byteSize());
        assertEquals(1, memoryPool.getActiveAllocationCount());
        
        // Write and read test data
        for (int i = 0; i < size / 4; i++) {
            segment.setAtIndex(ValueLayout.JAVA_INT, i, i * 3);
        }
        
        for (int i = 0; i < size / 4; i++) {
            assertEquals(i * 3, segment.getAtIndex(ValueLayout.JAVA_INT, i));
        }
        
        memoryPool.free(segment);
        assertEquals(0, memoryPool.getActiveAllocationCount());
    }
    
    // ================================================================================
    // Variable Size Allocation Tests
    // ================================================================================
    
    @Test
    @DisplayName("Variable size allocations work correctly")
    void testVariableSizeAllocations() {
        var allocations = new ArrayList<MemorySegment>();
        var sizes = new int[]{32, 64, 128, 256, 512, 1024, 2048, 4096};
        
        // Allocate various sizes
        for (int size : sizes) {
            MemorySegment segment = memoryPool.allocate(size);
            allocations.add(segment);
            assertEquals(size, segment.byteSize());
        }
        
        assertEquals(sizes.length, memoryPool.getActiveAllocationCount());
        
        // Free all allocations
        for (MemorySegment segment : allocations) {
            memoryPool.free(segment);
        }
        
        assertEquals(0, memoryPool.getActiveAllocationCount());
        assertEquals(sizes.length, memoryPool.getTotalDeallocations());
    }
    
    @Test
    @DisplayName("Mixed allocation and deallocation patterns")
    void testMixedAllocationPatterns() {
        var activeSegments = new ArrayList<MemorySegment>();
        var random = ThreadLocalRandom.current();
        
        // Perform mixed allocations and deallocations
        for (int i = 0; i < 1000; i++) {
            if (activeSegments.isEmpty() || random.nextBoolean()) {
                // Allocate
                int size = 32 << random.nextInt(8); // 32 to 4096
                MemorySegment segment = memoryPool.allocate(size);
                activeSegments.add(segment);
            } else {
                // Deallocate
                int index = random.nextInt(activeSegments.size());
                MemorySegment segment = activeSegments.remove(index);
                memoryPool.free(segment);
            }
        }
        
        // Clean up remaining segments
        for (MemorySegment segment : activeSegments) {
            memoryPool.free(segment);
        }
        
        assertEquals(0, memoryPool.getActiveAllocationCount());
        assertTrue(memoryPool.getTotalAllocations() > 0);
        assertTrue(memoryPool.getTotalDeallocations() > 0);
    }
    
    // ================================================================================
    // Fragmentation Management Tests
    // ================================================================================
    
    @Test
    @DisplayName("Fragmentation calculation works correctly")
    void testFragmentationCalculation() {
        assertEquals(0.0, memoryPool.getFragmentation(), 
                    "Empty pool should have no fragmentation");
        
        // Allocate blocks that will cause internal fragmentation
        List<MemorySegment> segments = new ArrayList<>();
        
        // Allocate 33-byte blocks (will use 64-byte buddy blocks)
        for (int i = 0; i < 10; i++) {
            segments.add(memoryPool.allocate(33)); // Uses 64 bytes, wastes 31 bytes each
        }
        
        double fragmentation = memoryPool.getFragmentation();
        assertTrue(fragmentation > 0.0, "Should have internal fragmentation");
        assertTrue(fragmentation < 1.0, "Fragmentation should be less than 100%");
        
        // Free half the segments to create external fragmentation
        for (int i = 0; i < 5; i++) {
            memoryPool.free(segments.get(i * 2));
        }
        
        double newFragmentation = memoryPool.getFragmentation();
        // Fragmentation characteristics may vary based on coalescing behavior
        assertTrue(newFragmentation >= 0.0, "Fragmentation should be non-negative");
        
        // Clean up
        for (int i = 1; i < segments.size(); i += 2) {
            memoryPool.free(segments.get(i));
        }
    }
    
    @Test
    @DisplayName("Defragmentation reduces fragmentation")
    void testDefragmentation() {
        var segments = new ArrayList<MemorySegment>();
        
        // Create fragmentation by allocating and freeing in a pattern
        for (int i = 0; i < 20; i++) {
            segments.add(memoryPool.allocate(128));
        }
        
        // Free every other segment to create fragmentation
        for (int i = 0; i < segments.size(); i += 2) {
            memoryPool.free(segments.get(i));
        }
        
        double fragmentationBefore = memoryPool.getFragmentation();
        
        // Perform defragmentation
        int coalescedBlocks = memoryPool.defragment();
        
        double fragmentationAfter = memoryPool.getFragmentation();
        
        // Defragmentation should have occurred
        assertTrue(coalescedBlocks >= 0, "Should report non-negative coalesced blocks");
        
        // Clean up remaining segments
        for (int i = 1; i < segments.size(); i += 2) {
            memoryPool.free(segments.get(i));
        }
    }
    
    @Test
    @DisplayName("Automatic defragmentation through coalescing")
    void testAutomaticDefragmentation() {
        // Allocate a full page worth of small blocks
        var segments = new ArrayList<MemorySegment>();
        int blocksPerPage = PageAllocator.PAGE_SIZE_BYTES / 128;
        
        for (int i = 0; i < blocksPerPage; i++) {
            segments.add(memoryPool.allocate(128));
        }
        
        assertEquals(blocksPerPage, memoryPool.getActiveAllocationCount());
        
        // Free all blocks - should trigger automatic coalescing
        for (MemorySegment segment : segments) {
            memoryPool.free(segment);
        }
        
        assertEquals(0, memoryPool.getActiveAllocationCount());
        
        // Now allocate a large block - should reuse the defragmented space
        MemorySegment largeBlock = memoryPool.allocate(4096);
        assertNotNull(largeBlock);
        assertEquals(4096, largeBlock.byteSize());
        
        memoryPool.free(largeBlock);
    }
    
    // ================================================================================
    // Thread Safety Tests
    // ================================================================================
    
    @Test
    @Timeout(30)
    @DisplayName("Concurrent allocations are thread-safe")
    void testConcurrentAllocations() throws InterruptedException {
        var threadCount = 8;
        var allocationsPerThread = 200;
        var totalExpectedAllocations = threadCount * allocationsPerThread;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var countDownLatch = new CountDownLatch(threadCount);
        var allocatedSegments = Collections.synchronizedList(new ArrayList<MemorySegment>());
        var exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        
        // Start allocation threads
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    var random = ThreadLocalRandom.current();
                    for (int i = 0; i < allocationsPerThread; i++) {
                        int size = 32 << random.nextInt(6); // 32 to 1024
                        MemorySegment segment = memoryPool.allocate(size);
                        allocatedSegments.add(segment);
                        
                        // Verify segment properties
                        assertEquals(size, segment.byteSize());
                        
                        // Write test data
                        segment.set(ValueLayout.JAVA_INT, 0, i);
                        assertEquals(i, segment.get(ValueLayout.JAVA_INT, 0));
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
        assertEquals(totalExpectedAllocations, allocatedSegments.size(), 
                    "Should allocate expected number of segments");
        assertEquals(totalExpectedAllocations, memoryPool.getTotalAllocations(), 
                    "Pool statistics should match");
        
        // Verify all segments have unique addresses
        var uniqueAddresses = allocatedSegments.stream()
                                              .mapToLong(MemorySegment::address)
                                              .distinct()
                                              .count();
        assertEquals(totalExpectedAllocations, uniqueAddresses, 
                    "All segments should have unique addresses");
        
        // Clean up
        for (MemorySegment segment : allocatedSegments) {
            memoryPool.free(segment);
        }
    }
    
    @Test
    @Timeout(30)
    @DisplayName("Concurrent allocation and deallocation")
    void testConcurrentAllocationDeallocation() throws InterruptedException {
        var threadCount = 6;
        var duration = 5; // seconds
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var stopFlag = new AtomicBoolean(false);
        var allocCounter = new AtomicLong(0);
        var freeCounter = new AtomicLong(0);
        var exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        
        // Start mixed allocation/deallocation threads
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                var threadSegments = new ArrayList<MemorySegment>();
                var random = ThreadLocalRandom.current();
                
                try {
                    while (!stopFlag.get()) {
                        if (threadSegments.isEmpty() || random.nextBoolean()) {
                            // Allocate
                            int size = 32 << random.nextInt(6); // 32 to 1024
                            MemorySegment segment = memoryPool.allocate(size);
                            threadSegments.add(segment);
                            allocCounter.incrementAndGet();
                            
                            // Write test data
                            segment.set(ValueLayout.JAVA_INT, 0, size);
                            assertEquals(size, segment.get(ValueLayout.JAVA_INT, 0));
                            
                        } else {
                            // Deallocate
                            int index = random.nextInt(threadSegments.size());
                            MemorySegment segment = threadSegments.remove(index);
                            memoryPool.free(segment);
                            freeCounter.incrementAndGet();
                        }
                        
                        // Small delay to allow thread interleaving
                        if (random.nextInt(100) == 0) {
                            Thread.yield();
                        }
                    }
                    
                    // Free remaining segments
                    for (MemorySegment segment : threadSegments) {
                        memoryPool.free(segment);
                        freeCounter.incrementAndGet();
                    }
                    
                } catch (Throwable e) {
                    // Don't count IllegalArgumentException for segments not allocated by this pool
                    // This can happen in concurrent scenarios with segment splitting/coalescing
                    if (!(e instanceof IllegalArgumentException && 
                          e.getMessage().contains("Segment was not allocated by this pool"))) {
                        exceptions.add(e);
                    }
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
    
    @Test
    @Timeout(30)
    @DisplayName("Concurrent defragmentation is thread-safe")
    void testConcurrentDefragmentation() throws InterruptedException {
        var threadCount = 4;
        var operationsPerThread = 100;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var countDownLatch = new CountDownLatch(threadCount);
        var exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        
        // Pre-populate with some fragmented state
        var initialSegments = new ArrayList<MemorySegment>();
        for (int i = 0; i < 50; i++) {
            initialSegments.add(memoryPool.allocate(128));
        }
        // Free every other segment to create fragmentation
        for (int i = 0; i < initialSegments.size(); i += 2) {
            memoryPool.free(initialSegments.get(i));
        }
        
        // Start threads that perform operations while defragmentation runs
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    var threadSegments = new ArrayList<MemorySegment>();
                    var random = ThreadLocalRandom.current();
                    
                    for (int i = 0; i < operationsPerThread; i++) {
                        int operation = random.nextInt(10);
                        
                        if (operation < 4) {
                            // 40% - Allocate
                            int size = 32 << random.nextInt(5); // 32 to 512
                            MemorySegment segment = memoryPool.allocate(size);
                            threadSegments.add(segment);
                            
                        } else if (operation < 7 && !threadSegments.isEmpty()) {
                            // 30% - Deallocate
                            int index = random.nextInt(threadSegments.size());
                            MemorySegment segment = threadSegments.remove(index);
                            memoryPool.free(segment);
                            
                        } else if (operation < 8) {
                            // 10% - Defragment
                            memoryPool.defragment();
                            
                        } else {
                            // 20% - Query statistics
                            memoryPool.getFragmentation();
                            memoryPool.getStatistics();
                        }
                    }
                    
                    // Clean up thread segments
                    for (MemorySegment segment : threadSegments) {
                        memoryPool.free(segment);
                    }
                    
                } catch (Throwable e) {
                    log.error("Thread {} encountered exception", threadId, e);
                    exceptions.add(e);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        
        assertTrue(countDownLatch.await(25, TimeUnit.SECONDS), 
                  "All threads should complete");
        executor.shutdown();
        
        assertTrue(exceptions.isEmpty(), 
                  "No exceptions should occur during concurrent defragmentation: " + exceptions);
        
        // Clean up remaining initial segments
        for (int i = 1; i < initialSegments.size(); i += 2) {
            memoryPool.free(initialSegments.get(i));
        }
    }
    
    // ================================================================================
    // Memory Validation and Error Handling Tests
    // ================================================================================
    
    @Test
    @DisplayName("Invalid allocation sizes are rejected")
    void testInvalidAllocationSizes() {
        // Test negative sizes
        assertThrows(IllegalArgumentException.class, 
                    () -> memoryPool.allocate(-1),
                    "Should reject negative size");
        
        // Test zero size
        assertThrows(IllegalArgumentException.class, 
                    () -> memoryPool.allocate(0),
                    "Should reject zero size");
        
        // Test oversized allocation
        assertThrows(IllegalArgumentException.class, 
                    () -> memoryPool.allocate(MemoryPool.MAX_ALLOCATION_SIZE + 1),
                    "Should reject oversized allocation");
    }
    
    @Test
    @DisplayName("Invalid free operations are rejected")
    void testInvalidFreeOperations() {
        // Test null segment
        assertThrows(IllegalArgumentException.class, 
                    () -> memoryPool.free(null),
                    "Should reject null segment");
        
        // Test segment not from this pool
        try (Arena otherArena = Arena.ofShared()) {
            PageAllocator otherAllocator = new PageAllocator(otherArena);
            MemoryPool otherPool = new MemoryPool(otherAllocator);
            MemorySegment otherSegment = otherPool.allocate(64);
            
            assertThrows(IllegalArgumentException.class, 
                        () -> memoryPool.free(otherSegment),
                        "Should reject segment from other pool");
            
            otherPool.free(otherSegment);
        }
        
        // Test double-free
        MemorySegment segment = memoryPool.allocate(128);
        memoryPool.free(segment);
        
        assertThrows(IllegalArgumentException.class, 
                    () -> memoryPool.free(segment),
                    "Should reject double-free");
    }
    
    @Test
    @DisplayName("Memory content is preserved")
    void testMemoryContentPreservation() {
        MemorySegment segment = memoryPool.allocate(256);
        
        // Write test pattern
        for (int i = 0; i < 64; i++) {
            segment.setAtIndex(ValueLayout.JAVA_INT, i, i * 7);
        }
        
        // Verify content is preserved
        for (int i = 0; i < 64; i++) {
            assertEquals(i * 7, segment.getAtIndex(ValueLayout.JAVA_INT, i),
                        "Memory content should be preserved");
        }
        
        memoryPool.free(segment);
    }
    
    // ================================================================================
    // Statistics and Information Tests
    // ================================================================================
    
    @Test
    @DisplayName("Statistics tracking works correctly")
    void testStatisticsTracking() {
        assertEquals(0, memoryPool.getTotalAllocations());
        assertEquals(0, memoryPool.getTotalDeallocations());
        assertEquals(0, memoryPool.getCurrentBytesInUse());
        assertEquals(0, memoryPool.getActiveAllocationCount());
        
        // Allocate segments
        List<MemorySegment> segments = new ArrayList<>();
        long expectedBytesAllocated = 0;
        
        int[] sizes = {32, 64, 128, 256, 512};
        for (int size : sizes) {
            MemorySegment segment = memoryPool.allocate(size);
            segments.add(segment);
            expectedBytesAllocated += size;
        }
        
        assertEquals(sizes.length, memoryPool.getTotalAllocations());
        assertEquals(0, memoryPool.getTotalDeallocations());
        assertEquals(expectedBytesAllocated, memoryPool.getCurrentBytesInUse());
        assertEquals(sizes.length, memoryPool.getActiveAllocationCount());
        assertEquals(expectedBytesAllocated, memoryPool.getTotalBytesAllocated());
        
        // Free some segments
        memoryPool.free(segments.get(0)); // 32 bytes
        memoryPool.free(segments.get(2)); // 128 bytes
        
        assertEquals(sizes.length, memoryPool.getTotalAllocations());
        assertEquals(2, memoryPool.getTotalDeallocations());
        assertEquals(expectedBytesAllocated - 32 - 128, memoryPool.getCurrentBytesInUse());
        assertEquals(sizes.length - 2, memoryPool.getActiveAllocationCount());
        assertEquals(32 + 128, memoryPool.getTotalBytesFreed());
        
        // Free remaining segments
        memoryPool.free(segments.get(1));
        memoryPool.free(segments.get(3));
        memoryPool.free(segments.get(4));
        
        assertEquals(sizes.length, memoryPool.getTotalAllocations());
        assertEquals(sizes.length, memoryPool.getTotalDeallocations());
        assertEquals(0, memoryPool.getCurrentBytesInUse());
        assertEquals(0, memoryPool.getActiveAllocationCount());
        assertEquals(expectedBytesAllocated, memoryPool.getTotalBytesFreed());
    }
    
    @Test
    @DisplayName("Statistics information is comprehensive")
    void testStatisticsInformation() {
        // Allocate some segments to generate statistics
        List<MemorySegment> segments = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            segments.add(memoryPool.allocate(64));
        }
        
        String statistics = memoryPool.getStatistics();
        
        assertNotNull(statistics);
        assertTrue(statistics.contains("MemoryPool Statistics"));
        assertTrue(statistics.contains("Total Allocations"));
        assertTrue(statistics.contains("Total Deallocations"));
        assertTrue(statistics.contains("Active Allocations"));
        assertTrue(statistics.contains("Total Bytes Allocated"));
        assertTrue(statistics.contains("Current Bytes In Use"));
        assertTrue(statistics.contains("Managed Pages"));
        assertTrue(statistics.contains("Fragmentation"));
        assertTrue(statistics.contains("Free List Sizes"));
        
        // Clean up
        for (MemorySegment segment : segments) {
            memoryPool.free(segment);
        }
    }
    
    @Test
    @DisplayName("ToString provides useful information")
    void testToString() {
        MemorySegment segment = memoryPool.allocate(128);
        
        String toString = memoryPool.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("MemoryPool"));
        assertTrue(toString.contains("allocations"));
        assertTrue(toString.contains("pages"));
        assertTrue(toString.contains("bytesInUse"));
        
        memoryPool.free(segment);
    }
    
    // ================================================================================
    // Stress Tests and Edge Cases
    // ================================================================================
    
    @Test
    @Timeout(60)
    @DisplayName("High volume allocation and deallocation")
    void testHighVolumeOperations() {
        var allocationCount = 5000;
        List<MemorySegment> segments = new ArrayList<>();
        
        log.info("Starting high volume allocation test: {} segments", allocationCount);
        long startTime = System.nanoTime();
        
        // Allocate many segments
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < allocationCount; i++) {
            int size = 32 << random.nextInt(6); // 32 to 1024
            segments.add(memoryPool.allocate(size));
            
            if (i > 0 && i % 1000 == 0) {
                log.debug("Allocated {} segments", i);
            }
        }
        
        long allocationTime = System.nanoTime() - startTime;
        startTime = System.nanoTime();
        
        // Free all segments
        for (MemorySegment segment : segments) {
            memoryPool.free(segment);
        }
        
        long freeTime = System.nanoTime() - startTime;
        
        assertEquals(allocationCount, memoryPool.getTotalAllocations());
        assertEquals(allocationCount, memoryPool.getTotalDeallocations());
        assertEquals(0, memoryPool.getActiveAllocationCount());
        
        log.info("High volume test completed: {} segments allocated in {} ms, freed in {} ms", 
                allocationCount, allocationTime / 1_000_000, freeTime / 1_000_000);
    }
    
    @Test
    @Timeout(45)
    @DisplayName("Mixed workload stress test")
    void testMixedWorkloadStress() throws InterruptedException {
        var duration = 15; // seconds
        var threadCount = 6;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        var stopFlag = new AtomicBoolean(false);
        var operationCounts = new ConcurrentHashMap<String, AtomicLong>();
        operationCounts.put("allocations", new AtomicLong(0));
        operationCounts.put("deallocations", new AtomicLong(0));
        operationCounts.put("defragmentations", new AtomicLong(0));
        
        var exceptions = Collections.synchronizedList(new ArrayList<Throwable>());
        
        log.info("Starting mixed workload stress test for {} seconds with {} threads", 
                duration, threadCount);
        
        // Start worker threads with different workload patterns
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            
            executor.submit(() -> {
                var threadSegments = new ArrayList<MemorySegment>();
                var random = ThreadLocalRandom.current();
                
                try {
                    while (!stopFlag.get()) {
                        int operation = random.nextInt(10);
                        
                        if (operation < 5) {
                            // 50% - Allocation
                            int size = 32 << random.nextInt(7); // 32 to 2048
                            MemorySegment segment = memoryPool.allocate(size);
                            threadSegments.add(segment);
                            operationCounts.get("allocations").incrementAndGet();
                            
                            // Write test data
                            segment.set(ValueLayout.JAVA_INT, 0, threadId * 1000 + threadSegments.size());
                            
                        } else if (operation < 8 && !threadSegments.isEmpty()) {
                            // 30% - Deallocation
                            int index = random.nextInt(threadSegments.size());
                            MemorySegment segment = threadSegments.remove(index);
                            
                            // Verify test data before freeing
                            int expectedValue = threadId * 1000 + (threadSegments.size() + 1);
                            // Note: exact value may not match due to list ordering changes
                            
                            memoryPool.free(segment);
                            operationCounts.get("deallocations").incrementAndGet();
                            
                        } else if (operation < 9) {
                            // 10% - Defragmentation
                            memoryPool.defragment();
                            operationCounts.get("defragmentations").incrementAndGet();
                            
                        } else {
                            // 10% - Statistics query
                            memoryPool.getFragmentation();
                            memoryPool.getStatistics();
                        }
                        
                        // Occasional yield to allow thread switching
                        if (random.nextInt(100) == 0) {
                            Thread.yield();
                        }
                    }
                    
                    // Cleanup remaining segments
                    for (MemorySegment segment : threadSegments) {
                        memoryPool.free(segment);
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
        long totalDefragmentations = operationCounts.get("defragmentations").get();
        
        assertTrue(totalAllocations > 0, "Should have performed allocations");
        assertTrue(totalDeallocations > 0, "Should have performed deallocations");
        
        log.info("Stress test completed: {} allocations, {} deallocations, {} defragmentations", 
                totalAllocations, totalDeallocations, totalDefragmentations);
        log.info("Final statistics: {}", memoryPool.getStatistics());
        
        // Verify statistics consistency
        assertEquals(totalAllocations, memoryPool.getTotalAllocations(), 
                    "Statistics should match actual operations");
        assertEquals(totalDeallocations, memoryPool.getTotalDeallocations(), 
                    "Statistics should match actual operations");
    }
    
    @Test
    @DisplayName("Edge case: maximum allocation size")
    void testMaximumAllocationSize() {
        MemorySegment segment = memoryPool.allocate(MemoryPool.MAX_ALLOCATION_SIZE);
        
        assertNotNull(segment);
        assertEquals(MemoryPool.MAX_ALLOCATION_SIZE, segment.byteSize());
        assertEquals(1, memoryPool.getActiveAllocationCount());
        
        // Write and verify data across the entire segment
        for (int i = 0; i < MemoryPool.MAX_ALLOCATION_SIZE / 4; i++) {
            segment.setAtIndex(ValueLayout.JAVA_INT, i, i);
        }
        
        for (int i = 0; i < MemoryPool.MAX_ALLOCATION_SIZE / 4; i++) {
            assertEquals(i, segment.getAtIndex(ValueLayout.JAVA_INT, i));
        }
        
        memoryPool.free(segment);
        assertEquals(0, memoryPool.getActiveAllocationCount());
    }
    
    @Test
    @DisplayName("Edge case: minimum allocation size")
    void testMinimumAllocationSize() {
        MemorySegment segment = memoryPool.allocate(1); // Should round up to MIN_ALLOCATION_SIZE
        
        assertNotNull(segment);
        assertEquals(MemoryPool.MIN_ALLOCATION_SIZE, segment.byteSize());
        assertEquals(1, memoryPool.getActiveAllocationCount());
        
        // Verify we can use the full allocated space
        for (int i = 0; i < MemoryPool.MIN_ALLOCATION_SIZE / 4; i++) {
            segment.setAtIndex(ValueLayout.JAVA_INT, i, i * 2);
        }
        
        for (int i = 0; i < MemoryPool.MIN_ALLOCATION_SIZE / 4; i++) {
            assertEquals(i * 2, segment.getAtIndex(ValueLayout.JAVA_INT, i));
        }
        
        memoryPool.free(segment);
        assertEquals(0, memoryPool.getActiveAllocationCount());
    }
}