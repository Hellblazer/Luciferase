package com.hellblazer.luciferase.render.voxel.memory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MemoryPool provides variable-size allocation within pages managed by PageAllocator.
 * This pool implements a buddy allocator algorithm for efficient memory management with
 * minimal fragmentation and thread-safe operation with low contention.
 * 
 * <h2>Features</h2>
 * <ul>
 * <li><b>Buddy Allocator</b>: Uses binary buddy system for efficient allocation/deallocation</li>
 * <li><b>Variable Sizes</b>: Supports allocations from 32 bytes to full page size (8KB)</li>
 * <li><b>Thread Safety</b>: All operations are thread-safe with minimal lock contention</li>
 * <li><b>Efficient Packing</b>: Minimizes fragmentation through buddy coalescing</li>
 * <li><b>Small/Large Optimization</b>: Optimized paths for small (&lt; 1KB) and large allocations</li>
 * <li><b>Defragmentation</b>: Provides explicit defragmentation support</li>
 * </ul>
 * 
 * <h2>Allocation Sizes</h2>
 * The buddy allocator supports the following power-of-2 sizes:
 * <ul>
 * <li>32, 64, 128, 256, 512 bytes (small allocations)</li>
 * <li>1KB, 2KB, 4KB, 8KB (large allocations)</li>
 * </ul>
 * 
 * Requested sizes are rounded up to the next power of 2 for buddy system efficiency.
 * 
 * <h2>Thread Safety</h2>
 * This class uses a combination of concurrent data structures and fine-grained locking:
 * <ul>
 * <li>Free lists use lock-free ConcurrentLinkedQueue</li>
 * <li>Page management uses ConcurrentHashMap</li>
 * <li>Statistics use atomic counters</li>
 * <li>Defragmentation uses read-write locks for safety</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * try (Arena arena = Arena.ofShared()) {
 *     PageAllocator pageAllocator = new PageAllocator(arena);
 *     MemoryPool pool = new MemoryPool(pageAllocator);
 *     
 *     // Allocate small buffer
 *     MemorySegment buffer = pool.allocate(256);
 *     buffer.set(ValueLayout.JAVA_INT, 0, 42);
 *     
 *     // Free when done
 *     pool.free(buffer);
 *     
 *     // Check fragmentation
 *     double fragmentation = pool.getFragmentation();
 *     if (fragmentation > 0.3) {
 *         pool.defragment();
 *     }
 * }
 * }</pre>
 * 
 * @author Claude (Generated)
 * @version 1.0
 * @since Luciferase 0.0.1
 */
public final class MemoryPool {
    
    private static final Logger log = LoggerFactory.getLogger(MemoryPool.class);
    
    // ================================================================================
    // Constants
    // ================================================================================
    
    /**
     * Minimum allocation size (32 bytes) - must be power of 2
     */
    public static final int MIN_ALLOCATION_SIZE = 32;
    
    /**
     * Maximum allocation size (8KB) - equals page size
     */
    public static final int MAX_ALLOCATION_SIZE = PageAllocator.PAGE_SIZE_BYTES;
    
    /**
     * Small allocation threshold (1KB) - optimized path for smaller allocations
     */
    public static final int SMALL_ALLOCATION_THRESHOLD = 1024;
    
    /**
     * Number of buddy levels (log2(8192/32) + 1 = 9)
     * Levels: 0=32B, 1=64B, 2=128B, 3=256B, 4=512B, 5=1KB, 6=2KB, 7=4KB, 8=8KB
     */
    private static final int BUDDY_LEVELS = 9;
    
    /**
     * Buddy size array - precomputed powers of 2
     */
    private static final int[] BUDDY_SIZES = {
        32, 64, 128, 256, 512, 1024, 2048, 4096, 8192
    };
    
    // ================================================================================
    // Instance Fields
    // ================================================================================
    
    /**
     * Page allocator for obtaining memory pages
     */
    private final PageAllocator pageAllocator;
    
    /**
     * Free lists for each buddy level - indexed by level (0 = 32 bytes, 7 = 4KB)
     */
    private final ConcurrentLinkedQueue<MemorySegment>[] freeLists;
    
    /**
     * Maps memory segments to their allocation metadata
     */
    private final ConcurrentHashMap<Long, AllocationMetadata> allocations;
    
    /**
     * Maps page addresses to page metadata
     */
    private final ConcurrentHashMap<Long, PageMetadata> pages;
    
    /**
     * Statistics counters
     */
    private final AtomicLong totalAllocations;
    private final AtomicLong totalDeallocations;
    private final AtomicLong totalBytesAllocated;
    private final AtomicLong totalBytesFreed;
    private final AtomicLong currentBytesInUse;
    
    /**
     * Lock for defragmentation operations
     */
    private final ReentrantReadWriteLock defragmentationLock;
    
    /**
     * Flag to track if defragmentation is in progress
     */
    private volatile boolean defragmentationInProgress = false;
    
    // ================================================================================
    // Constructors
    // ================================================================================
    
    /**
     * Creates a new MemoryPool using the specified PageAllocator.
     * 
     * @param pageAllocator The page allocator to use for obtaining memory pages
     * @throws IllegalArgumentException if pageAllocator is null
     */
    @SuppressWarnings("unchecked")
    public MemoryPool(PageAllocator pageAllocator) {
        if (pageAllocator == null) {
            throw new IllegalArgumentException("PageAllocator cannot be null");
        }
        
        this.pageAllocator = pageAllocator;
        this.freeLists = new ConcurrentLinkedQueue[BUDDY_LEVELS];
        for (int i = 0; i < BUDDY_LEVELS; i++) {
            this.freeLists[i] = new ConcurrentLinkedQueue<>();
        }
        
        this.allocations = new ConcurrentHashMap<>();
        this.pages = new ConcurrentHashMap<>();
        
        this.totalAllocations = new AtomicLong(0);
        this.totalDeallocations = new AtomicLong(0);
        this.totalBytesAllocated = new AtomicLong(0);
        this.totalBytesFreed = new AtomicLong(0);
        this.currentBytesInUse = new AtomicLong(0);
        
        this.defragmentationLock = new ReentrantReadWriteLock();
        
        log.debug("Created MemoryPool with PageAllocator: {}", pageAllocator);
    }
    
    // ================================================================================
    // Core Allocation Methods
    // ================================================================================
    
    /**
     * Allocates a memory segment of the requested size.
     * The actual allocated size may be larger due to buddy allocator rounding.
     * 
     * @param size The requested size in bytes (must be positive)
     * @return A MemorySegment of at least the requested size
     * @throws IllegalArgumentException if size is invalid
     * @throws OutOfMemoryError if allocation fails
     */
    public MemorySegment allocate(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Allocation size must be positive, got: " + size);
        }
        
        if (size > MAX_ALLOCATION_SIZE) {
            throw new IllegalArgumentException(
                String.format("Allocation size %d exceeds maximum %d", size, MAX_ALLOCATION_SIZE));
        }
        
        defragmentationLock.readLock().lock();
        try {
            // Find appropriate buddy level
            var buddyLevel = findBuddyLevel(size);
            var actualSize = BUDDY_SIZES[buddyLevel];
            
            // Try to get from free list first
            MemorySegment segment = freeLists[buddyLevel].poll();
            if (segment != null) {
                // Update metadata and statistics
                var metadata = new AllocationMetadata(actualSize, buddyLevel, System.nanoTime());
                allocations.put(segment.address(), metadata);
                
                updateAllocationStats(actualSize);
                
                log.trace("Reused segment from free list: size={}, level={}", actualSize, buddyLevel);
                return segment;
            }
            
            // Need to allocate or split
            return allocateNewSegment(buddyLevel, actualSize);
            
        } finally {
            defragmentationLock.readLock().unlock();
        }
    }
    
    /**
     * Frees a previously allocated memory segment.
     * The segment is returned to the appropriate free list and may be coalesced
     * with its buddy if both are free.
     * 
     * @param segment The segment to free (must have been allocated by this pool)
     * @throws IllegalArgumentException if segment is null or invalid
     */
    public void free(MemorySegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("Cannot free null segment");
        }
        
        var address = segment.address();
        
        defragmentationLock.readLock().lock();
        try {
            // Use atomic remove to prevent double-free in concurrent scenarios  
            var metadata = allocations.remove(address);
            if (metadata == null) {
                // During defragmentation, segments may be moved or coalesced
                // Check if this segment was part of a defragmented block
                if (isDefragmentationInProgress()) {
                    log.debug("Segment already freed during defragmentation: address=0x{}", 
                             Long.toHexString(address));
                    return; // Silently ignore - segment was already handled
                }
                // Segment was already freed or never allocated by this pool
                throw new IllegalArgumentException(
                    "Segment was not allocated by this pool: address=0x" + Long.toHexString(address));
            }
            
            // Update statistics
            updateDeallocationStats(metadata.size);
            
            // Attempt buddy coalescing
            coalesceBuddy(segment, metadata);
            
            log.trace("Freed segment: size={}, level={}", metadata.size, metadata.buddyLevel);
            
        } finally {
            defragmentationLock.readLock().unlock();
        }
    }
    
    // ================================================================================
    // Defragmentation Methods
    // ================================================================================
    
    /**
     * Performs defragmentation to reduce memory fragmentation.
     * This operation coalesces free blocks and may compact memory usage.
     * 
     * @return The number of blocks coalesced during defragmentation
     */
    public int defragment() {
        defragmentationLock.writeLock().lock();
        try {
            defragmentationInProgress = true;
            var coalesced = 0;
            
            // Process each level from smallest to largest
            for (int level = 0; level < BUDDY_LEVELS - 1; level++) {
                coalesced += defragmentLevel(level);
            }
            
            log.debug("Defragmentation completed: {} blocks coalesced", coalesced);
            return coalesced;
            
        } finally {
            defragmentationInProgress = false;
            defragmentationLock.writeLock().unlock();
        }
    }
    
    /**
     * Calculates the current fragmentation ratio.
     * Fragmentation is measured as the ratio of unusable memory to total managed memory.
     * 
     * @return Fragmentation ratio between 0.0 (no fragmentation) and 1.0 (maximum fragmentation)
     */
    public double getFragmentation() {
        defragmentationLock.readLock().lock();
        try {
            var totalManagedMemory = pages.size() * (long) PageAllocator.PAGE_SIZE_BYTES;
            if (totalManagedMemory == 0) {
                return 0.0;
            }
            
            var internalFragmentation = calculateInternalFragmentation();
            var externalFragmentation = calculateExternalFragmentation();
            var totalFragmentation = internalFragmentation + externalFragmentation;
            
            // Ensure fragmentation never exceeds 100%
            return Math.min(1.0, (double) totalFragmentation / totalManagedMemory);
            
        } finally {
            defragmentationLock.readLock().unlock();
        }
    }
    
    // ================================================================================
    // Statistics and Information Methods
    // ================================================================================
    
    /**
     * Returns the total number of allocations performed.
     * 
     * @return Total allocation count
     */
    public long getTotalAllocations() {
        return totalAllocations.get();
    }
    
    /**
     * Returns the total number of deallocations performed.
     * 
     * @return Total deallocation count
     */
    public long getTotalDeallocations() {
        return totalDeallocations.get();
    }
    
    /**
     * Returns the total bytes allocated since creation.
     * 
     * @return Total bytes allocated
     */
    public long getTotalBytesAllocated() {
        return totalBytesAllocated.get();
    }
    
    /**
     * Returns the total bytes freed since creation.
     * 
     * @return Total bytes freed
     */
    public long getTotalBytesFreed() {
        return totalBytesFreed.get();
    }
    
    /**
     * Returns the current bytes in use.
     * 
     * @return Current bytes in use
     */
    public long getCurrentBytesInUse() {
        return currentBytesInUse.get();
    }
    
    /**
     * Returns the number of active allocations.
     * 
     * @return Number of active allocations
     */
    public int getActiveAllocationCount() {
        return allocations.size();
    }
    
    /**
     * Returns the number of pages currently managed by this pool.
     * 
     * @return Number of managed pages
     */
    public int getManagedPageCount() {
        return pages.size();
    }
    
    /**
     * Returns whether defragmentation is currently in progress.
     * 
     * @return true if defragmentation is running
     */
    private boolean isDefragmentationInProgress() {
        return defragmentationInProgress;
    }
    
    /**
     * Returns detailed statistics about this memory pool.
     * 
     * @return Formatted statistics string
     */
    public String getStatistics() {
        defragmentationLock.readLock().lock();
        try {
            var totalAlloc = totalAllocations.get();
            var totalDealloc = totalDeallocations.get();
            var totalBytesAlloc = totalBytesAllocated.get();
            var totalBytesFreedValue = totalBytesFreed.get();
            var currentBytes = currentBytesInUse.get();
            var activeAllocs = allocations.size();
            var managedPages = pages.size();
            var fragmentation = getFragmentation();
            
            return String.format(
                "MemoryPool Statistics:%n" +
                "  Total Allocations: %d%n" +
                "  Total Deallocations: %d%n" +
                "  Active Allocations: %d%n" +
                "  Total Bytes Allocated: %d%n" +
                "  Total Bytes Freed: %d%n" +
                "  Current Bytes In Use: %d%n" +
                "  Managed Pages: %d%n" +
                "  Fragmentation: %.2f%%%n" +
                "  Free List Sizes: %s",
                totalAlloc, totalDealloc, activeAllocs, totalBytesAlloc, totalBytesFreedValue,
                currentBytes, managedPages, fragmentation * 100.0, getFreeListSizes());
                
        } finally {
            defragmentationLock.readLock().unlock();
        }
    }
    
    // ================================================================================
    // Private Helper Methods
    // ================================================================================
    
    /**
     * Finds the appropriate buddy level for the given size.
     */
    private int findBuddyLevel(int size) {
        for (int i = 0; i < BUDDY_SIZES.length; i++) {
            if (BUDDY_SIZES[i] >= size) {
                return i;
            }
        }
        return BUDDY_SIZES.length - 1; // Should not happen due to size validation
    }
    
    /**
     * Finds the buddy level for an exact size match.
     */
    private int findLevelBySize(int size) {
        for (int i = 0; i < BUDDY_SIZES.length; i++) {
            if (BUDDY_SIZES[i] == size) {
                return i;
            }
        }
        // If no exact match, find the smallest level that can contain this size
        return findBuddyLevel(size);
    }
    
    /**
     * Allocates a new segment by splitting larger blocks or allocating new pages.
     */
    private MemorySegment allocateNewSegment(int buddyLevel, int size) {
        // Try to find a suitable segment from any level that's large enough
        for (int level = buddyLevel + 1; level < BUDDY_LEVELS; level++) {
            var largerSegment = freeLists[level].poll();
            if (largerSegment != null) {
                // Find the actual level this segment belongs to based on its size
                int actualLevel = findLevelBySize((int) largerSegment.byteSize());
                if (actualLevel >= buddyLevel) {
                    return splitSegment(largerSegment, actualLevel, buddyLevel, size);
                } else {
                    // This segment is too small, put it back and continue
                    freeLists[level].offer(largerSegment);
                }
            }
        }
        
        // Need to allocate a new page
        return allocateFromNewPage(buddyLevel, size);
    }
    
    /**
     * Splits a larger segment into smaller segments.
     */
    private MemorySegment splitSegment(MemorySegment largerSegment, int fromLevel, int toLevel, int targetSize) {
        var currentSegment = largerSegment;
        var currentLevel = fromLevel;
        
        // Split down to target level, one level at a time
        while (currentLevel > toLevel) {
            var nextLevel = currentLevel - 1;
            var halfSize = BUDDY_SIZES[nextLevel];
            
            // Validate that we can split this segment safely
            if (currentSegment.byteSize() < halfSize * 2) {
                log.error("Cannot split segment of size {} at level {} (expected >= {})", 
                         currentSegment.byteSize(), currentLevel, halfSize * 2);
                throw new IllegalStateException("Segment too small to split");
            }
            
            // Split current segment in half
            var secondHalfAddress = currentSegment.address() + halfSize;
            var secondHalf = MemorySegment.ofAddress(secondHalfAddress).reinterpret(halfSize);
            freeLists[nextLevel].offer(secondHalf);
            
            // Use first half for further splitting
            currentSegment = currentSegment.reinterpret(halfSize);
            currentLevel = nextLevel;
        }
        
        // Update metadata and statistics
        var metadata = new AllocationMetadata(targetSize, toLevel, System.nanoTime());
        allocations.put(currentSegment.address(), metadata);
        updateAllocationStats(targetSize);
        
        log.trace("Split segment: from level {} to level {}, size {}", fromLevel, toLevel, targetSize);
        return currentSegment;
    }
    
    /**
     * Allocates a segment from a new page.
     */
    private MemorySegment allocateFromNewPage(int buddyLevel, int size) {
        var page = pageAllocator.allocatePage();
        var pageMetadata = new PageMetadata(page.address(), System.nanoTime());
        pages.put(page.address(), pageMetadata);
        
        // If we need the full page, return it directly
        if (buddyLevel == BUDDY_LEVELS - 1) {
            var metadata = new AllocationMetadata(size, buddyLevel, System.nanoTime());
            allocations.put(page.address(), metadata);
            updateAllocationStats(size);
            
            log.trace("Allocated full page: size {}", size);
            return page;
        }
        
        // Split the page down to the required level
        return splitSegment(page, BUDDY_LEVELS - 1, buddyLevel, size);
    }
    
    /**
     * Attempts to coalesce the segment with its buddy.
     */
    private void coalesceBuddy(MemorySegment segment, AllocationMetadata metadata) {
        var level = metadata.buddyLevel;
        
        // Cannot coalesce at the page level
        if (level >= BUDDY_LEVELS - 1) {
            freeLists[level].offer(segment);
            return;
        }
        
        var buddyAddress = findBuddyAddress(segment.address(), level);
        var buddySegment = findSegmentInFreeList(buddyAddress, level);
        
        if (buddySegment != null) {
            // Remove buddy from free list
            freeLists[level].remove(buddySegment);
            
            // Create coalesced segment
            var coalescedAddress = Math.min(segment.address(), buddyAddress);
            var coalescedSize = BUDDY_SIZES[level + 1];
            var coalescedSegment = MemorySegment.ofAddress(coalescedAddress).reinterpret(coalescedSize);
            
            // Recursively attempt to coalesce at next level
            var coalescedMetadata = new AllocationMetadata(coalescedSize, level + 1, System.nanoTime());
            coalesceBuddy(coalescedSegment, coalescedMetadata);
            
            log.trace("Coalesced buddies at level {}", level);
        } else {
            // No buddy available, just add to free list
            freeLists[level].offer(segment);
        }
    }
    
    /**
     * Finds the buddy address for a given address and level.
     */
    private long findBuddyAddress(long address, int level) {
        var blockSize = BUDDY_SIZES[level];
        var pageAddress = findPageAddress(address);
        var offset = address - pageAddress;
        var blockIndex = offset / blockSize;
        var buddyIndex = blockIndex ^ 1; // XOR with 1 to get buddy
        return pageAddress + (buddyIndex * blockSize);
    }
    
    /**
     * Finds the page address for a given address.
     */
    private long findPageAddress(long address) {
        var pageSize = PageAllocator.PAGE_SIZE_BYTES;
        return (address / pageSize) * pageSize;
    }
    
    /**
     * Finds a segment with the given address in the specified free list.
     */
    private MemorySegment findSegmentInFreeList(long address, int level) {
        var freeList = freeLists[level];
        for (var segment : freeList) {
            if (segment.address() == address) {
                return segment;
            }
        }
        return null;
    }
    
    /**
     * Defragments a specific level by attempting to coalesce free blocks.
     */
    private int defragmentLevel(int level) {
        var freeList = freeLists[level];
        var coalesced = 0;
        
        // Convert to array to avoid concurrent modification
        var segments = freeList.toArray(new MemorySegment[0]);
        
        for (var segment : segments) {
            if (freeList.remove(segment)) {
                var buddyAddress = findBuddyAddress(segment.address(), level);
                var buddySegment = findSegmentInFreeList(buddyAddress, level);
                
                if (buddySegment != null && freeList.remove(buddySegment)) {
                    // Coalesce with buddy
                    var coalescedAddress = Math.min(segment.address(), buddyAddress);
                    var coalescedSize = BUDDY_SIZES[level + 1];
                    var coalescedSegment = MemorySegment.ofAddress(coalescedAddress).reinterpret(coalescedSize);
                    
                    freeLists[level + 1].offer(coalescedSegment);
                    coalesced++;
                } else {
                    // Put back in free list
                    freeList.offer(segment);
                }
            }
        }
        
        return coalesced;
    }
    
    /**
     * Calculates internal fragmentation (wasted space within allocated blocks).
     */
    private long calculateInternalFragmentation() {
        var fragmentation = 0L;
        for (var metadata : allocations.values()) {
            var allocatedSize = BUDDY_SIZES[metadata.buddyLevel];
            var requestedSize = metadata.size;
            fragmentation += (allocatedSize - requestedSize);
        }
        return fragmentation;
    }
    
    /**
     * Calculates external fragmentation (free space that cannot satisfy allocation requests).
     */
    private long calculateExternalFragmentation() {
        var totalFreeSpace = 0L;
        var largestFreeBlock = 0;
        
        for (int level = 0; level < BUDDY_LEVELS; level++) {
            var count = freeLists[level].size();
            var blockSize = BUDDY_SIZES[level];
            totalFreeSpace += count * blockSize;
            
            if (count > 0) {
                largestFreeBlock = Math.max(largestFreeBlock, blockSize);
            }
        }
        
        // External fragmentation is free space minus largest contiguous block
        return Math.max(0, totalFreeSpace - largestFreeBlock);
    }
    
    /**
     * Updates allocation statistics.
     */
    private void updateAllocationStats(int size) {
        totalAllocations.incrementAndGet();
        totalBytesAllocated.addAndGet(size);
        currentBytesInUse.addAndGet(size);
    }
    
    /**
     * Updates deallocation statistics.
     */
    private void updateDeallocationStats(int size) {
        totalDeallocations.incrementAndGet();
        totalBytesFreed.addAndGet(size);
        // Use compareAndSet loop to ensure atomic decrement of currentBytesInUse
        long current, updated;
        do {
            current = currentBytesInUse.get();
            updated = current - size;
        } while (!currentBytesInUse.compareAndSet(current, updated));
    }
    
    /**
     * Returns a string representation of free list sizes.
     */
    private String getFreeListSizes() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < BUDDY_LEVELS; i++) {
            if (i > 0) sb.append(", ");
            sb.append(freeLists[i].size());
        }
        sb.append("]");
        return sb.toString();
    }
    
    // ================================================================================
    // Inner Classes
    // ================================================================================
    
    /**
     * Metadata for allocated memory segments.
     */
    private static class AllocationMetadata {
        final int size;
        final int buddyLevel;
        final long allocationTime;
        
        AllocationMetadata(int size, int buddyLevel, long allocationTime) {
            this.size = size;
            this.buddyLevel = buddyLevel;
            this.allocationTime = allocationTime;
        }
    }
    
    /**
     * Metadata for memory pages.
     */
    private static class PageMetadata {
        final long address;
        final long allocationTime;
        
        PageMetadata(long address, long allocationTime) {
            this.address = address;
            this.allocationTime = allocationTime;
        }
    }
    
    // ================================================================================
    // Object Override Methods
    // ================================================================================
    
    @Override
    public String toString() {
        return String.format("MemoryPool{allocations=%d, pages=%d, bytesInUse=%d}",
                allocations.size(), pages.size(), currentBytesInUse.get());
    }
}
