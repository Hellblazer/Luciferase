package com.hellblazer.luciferase.render.voxel.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PageAllocator manages 8KB memory pages using the Foreign Function & Memory (FFM) API.
 * This allocator provides thread-safe allocation and deallocation of memory pages with
 * guaranteed 8KB alignment for optimal GPU memory access patterns and cache efficiency.
 * 
 * <h2>Features</h2>
 * <ul>
 * <li><b>Fixed Page Size</b>: All pages are exactly 8KB (8192 bytes)</li>
 * <li><b>Alignment Guarantee</b>: Pages are aligned to 8KB boundaries</li>
 * <li><b>Thread Safety</b>: All operations are thread-safe using concurrent data structures</li>
 * <li><b>Efficient Free List</b>: Uses lock-free queue for page recycling</li>
 * <li><b>Memory Arena Integration</b>: Manages lifecycle through FFM Arena</li>
 * <li><b>Statistics Tracking</b>: Monitors allocation patterns and memory usage</li>
 * </ul>
 * 
 * <h2>Memory Layout</h2>
 * Each allocated page is a contiguous 8KB block aligned to 8KB boundaries.
 * This alignment ensures optimal performance for:
 * <ul>
 * <li>GPU memory transfers (matches typical GPU memory page sizes)</li>
 * <li>CPU cache line efficiency</li>
 * <li>Virtual memory subsystem optimization</li>
 * <li>SIMD operations on voxel data</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * try (Arena arena = Arena.ofShared()) {
 *     PageAllocator allocator = new PageAllocator(arena);
 *     
 *     // Allocate a page
 *     MemorySegment page = allocator.allocatePage();
 *     
 *     // Use the page for voxel data storage
 *     page.set(ValueLayout.JAVA_INT, 0, 42);
 *     
 *     // Free the page when done
 *     allocator.freePage(page);
 * }
 * }</pre>
 * 
 * <h2>Thread Safety</h2>
 * This class is fully thread-safe and can be used concurrently from multiple threads.
 * The implementation uses lock-free data structures where possible and fine-grained
 * locking for statistics updates.
 * 
 * @author Claude (Generated)
 * @version 1.0
 * @since Luciferase 0.0.1
 */
public final class PageAllocator {
    
    private static final Logger log = LoggerFactory.getLogger(PageAllocator.class);
    
    // ================================================================================
    // Constants
    // ================================================================================
    
    /**
     * Page size in bytes (8KB for optimal memory alignment)
     */
    public static final int PAGE_SIZE_BYTES = 8192;
    
    /**
     * Page alignment boundary (same as page size for optimal alignment)
     */
    public static final int PAGE_ALIGNMENT = PAGE_SIZE_BYTES;
    
    /**
     * Maximum number of pages to keep in the free list for reuse.
     * This prevents unbounded memory growth while still providing recycling benefits.
     */
    private static final int MAX_FREE_PAGES = 1024;
    
    // ================================================================================
    // Instance Fields
    // ================================================================================
    
    /**
     * Memory arena for allocating pages. All allocated pages belong to this arena
     * and will be automatically freed when the arena is closed.
     */
    private final Arena memoryArena;
    
    /**
     * Lock-free queue of freed pages available for reuse.
     * This reduces allocation overhead by recycling previously allocated pages.
     */
    private final ConcurrentLinkedQueue<MemorySegment> freePages;
    
    /**
     * Total number of pages allocated since creation (includes recycled pages)
     */
    private final AtomicLong totalAllocated;
    
    /**
     * Total number of pages freed since creation
     */
    private final AtomicLong totalFreed;
    
    /**
     * Current number of pages in the free list
     */
    private final AtomicLong freeListSize;
    
    /**
     * Total bytes allocated from the arena (new pages only, excludes reused pages)
     */
    private final AtomicLong totalBytesAllocated;
    
    /**
     * Read-write lock for protecting statistics consistency during reporting.
     * Most operations use atomic counters, but complex statistics queries need consistency.
     */
    private final ReentrantReadWriteLock statisticsLock;
    
    // ================================================================================
    // Constructors
    // ================================================================================
    
    /**
     * Creates a new PageAllocator using the specified memory arena.
     * The arena will be used for all page allocations and must remain open
     * for the lifetime of this allocator.
     * 
     * @param arena The memory arena to use for allocations
     * @throws IllegalArgumentException if arena is null
     */
    public PageAllocator(Arena arena) {
        if (arena == null) {
            throw new IllegalArgumentException("Memory arena cannot be null");
        }
        
        this.memoryArena = arena;
        this.freePages = new ConcurrentLinkedQueue<>();
        this.totalAllocated = new AtomicLong(0);
        this.totalFreed = new AtomicLong(0);
        this.freeListSize = new AtomicLong(0);
        this.totalBytesAllocated = new AtomicLong(0);
        this.statisticsLock = new ReentrantReadWriteLock();
        
        log.debug("Created PageAllocator with arena: {}", arena);
    }
    
    // ================================================================================
    // Core Allocation Methods
    // ================================================================================
    
    /**
     * Allocates a new 8KB memory page aligned to 8KB boundaries.
     * This method first attempts to reuse a page from the free list before
     * allocating new memory from the arena.
     * 
     * @return A new MemorySegment representing an 8KB page
     * @throws OutOfMemoryError if the arena cannot allocate more memory
     */
    public MemorySegment allocatePage() {
        // Try to reuse a freed page first
        MemorySegment recycledPage = freePages.poll();
        if (recycledPage != null) {
            // Atomically decrement free list size only if we actually got a page
            freeListSize.decrementAndGet();
            
            // Always count pages given to users, including reused ones
            totalAllocated.incrementAndGet();
            
            log.trace("Reused page from free list, remaining free pages: {}", freeListSize.get());
            return recycledPage;
        }
        
        // Allocate new aligned page from arena
        MemorySegment newPage = memoryArena.allocate(PAGE_SIZE_BYTES, PAGE_ALIGNMENT);
        
        // Update statistics atomically
        totalAllocated.incrementAndGet();
        totalBytesAllocated.addAndGet(PAGE_SIZE_BYTES);
        
        log.trace("Allocated new page: {} bytes at address: 0x{}", 
                PAGE_SIZE_BYTES, Long.toHexString(newPage.address()));
        
        return newPage;
    }
    
    /**
     * Frees a previously allocated page, making it available for reuse.
     * The page is added to the free list if there's space, otherwise it's
     * discarded and will be reclaimed when the arena is closed.
     * 
     * @param page The page to free (must be a page allocated by this allocator)
     * @throws IllegalArgumentException if page is null or has incorrect size
     */
    public void freePage(MemorySegment page) {
        if (page == null) {
            throw new IllegalArgumentException("Cannot free null page");
        }
        
        if (page.byteSize() != PAGE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                String.format("Page size mismatch: expected %d bytes, got %d bytes", 
                            PAGE_SIZE_BYTES, page.byteSize()));
        }
        
        // Update statistics first
        totalFreed.incrementAndGet();
        
        // Add to free list if not at capacity
        if (freeListSize.get() < MAX_FREE_PAGES) {
            // Clear the page content for security and debugging
            clearPage(page);
            
            // Add to queue and increment size atomically
            if (freePages.offer(page)) {
                // Only increment if we're still under the limit (double-check to avoid race)
                if (freeListSize.get() < MAX_FREE_PAGES) {
                    freeListSize.incrementAndGet();
                }
                log.trace("Added page to free list, total free pages: {}", freeListSize.get());
            } else {
                log.trace("Failed to add page to free list");
            }
        } else {
            log.trace("Free list at capacity ({}), discarding page", MAX_FREE_PAGES);
        }
    }
    
    // ================================================================================
    // Bulk Operations
    // ================================================================================
    
    /**
     * Allocates multiple pages in a single operation.
     * This can be more efficient than individual allocations for bulk operations.
     * 
     * @param pageCount The number of pages to allocate
     * @return An array of MemorySegment instances, each representing an 8KB page
     * @throws IllegalArgumentException if pageCount is negative or zero
     * @throws OutOfMemoryError if insufficient memory is available
     */
    public MemorySegment[] allocatePages(int pageCount) {
        if (pageCount <= 0) {
            throw new IllegalArgumentException("Page count must be positive, got: " + pageCount);
        }
        
        MemorySegment[] pages = new MemorySegment[pageCount];
        int reusedCount = 0;
        int newCount = 0;
        
        try {
            // First, try to reuse pages from free list
            for (int i = 0; i < pageCount; i++) {
                MemorySegment recycledPage = freePages.poll();
                if (recycledPage != null) {
                    pages[i] = recycledPage;
                    reusedCount++;
                } else {
                    break; // No more recycled pages available
                }
            }
            
            // Then allocate new pages for the remainder
            for (int i = reusedCount; i < pageCount; i++) {
                pages[i] = memoryArena.allocate(PAGE_SIZE_BYTES, PAGE_ALIGNMENT);
                newCount++;
            }
            
            // Update statistics atomically in bulk
            if (reusedCount > 0) {
                freeListSize.addAndGet(-reusedCount);
            }
            if (newCount > 0) {
                totalBytesAllocated.addAndGet((long) newCount * PAGE_SIZE_BYTES);
            }
            // Count all pages given to users (both reused and new)
            totalAllocated.addAndGet(pageCount);
            
            log.debug("Allocated {} pages in bulk operation ({} reused, {} new)", 
                     pageCount, reusedCount, newCount);
            return pages;
            
        } catch (OutOfMemoryError e) {
            // Clean up any partially allocated pages
            for (int i = 0; i < pageCount; i++) {
                if (pages[i] != null) {
                    freePage(pages[i]);
                }
            }
            throw e;
        }
    }
    
    /**
     * Frees multiple pages in a single operation.
     * This is more efficient than individual freePage() calls for bulk operations.
     * 
     * @param pages Array of pages to free
     * @throws IllegalArgumentException if pages array is null or contains null elements
     */
    public void freePages(MemorySegment[] pages) {
        if (pages == null) {
            throw new IllegalArgumentException("Pages array cannot be null");
        }
        
        // Validate all pages first
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == null) {
                throw new IllegalArgumentException("Page at index " + i + " is null");
            }
            if (pages[i].byteSize() != PAGE_SIZE_BYTES) {
                throw new IllegalArgumentException(
                    String.format("Page at index %d has size mismatch: expected %d bytes, got %d bytes", 
                                i, PAGE_SIZE_BYTES, pages[i].byteSize()));
            }
        }
        
        // Update freed count atomically in bulk
        totalFreed.addAndGet(pages.length);
        
        // Process pages for reuse
        int addedToFreeList = 0;
        long currentFreeListSize = freeListSize.get();
        
        for (MemorySegment page : pages) {
            // Add to free list if there's space
            if (currentFreeListSize + addedToFreeList < MAX_FREE_PAGES) {
                clearPage(page);
                if (freePages.offer(page)) {
                    addedToFreeList++;
                }
            }
            // Pages that don't fit in free list are just discarded
        }
        
        // Update free list size atomically
        if (addedToFreeList > 0) {
            freeListSize.addAndGet(addedToFreeList);
        }
        
        log.debug("Freed {} pages in bulk operation ({} added to free list)", 
                 pages.length, addedToFreeList);
    }
    
    // ================================================================================
    // Information and Statistics Methods
    // ================================================================================
    
    /**
     * Returns the page size in bytes (always 8192).
     * 
     * @return The page size in bytes
     */
    public int getPageSize() {
        return PAGE_SIZE_BYTES;
    }
    
    /**
     * Returns the page alignment requirement in bytes (always 8192).
     * 
     * @return The page alignment in bytes
     */
    public int getPageAlignment() {
        return PAGE_ALIGNMENT;
    }
    
    /**
     * Returns the total number of pages allocated since creation.
     * This includes both active pages and pages that have been freed.
     * 
     * @return The total number of pages allocated
     */
    public long getTotalAllocated() {
        return totalAllocated.get();
    }
    
    /**
     * Returns the total number of pages freed since creation.
     * 
     * @return The total number of pages freed
     */
    public long getTotalFreed() {
        return totalFreed.get();
    }
    
    /**
     * Returns the current number of active pages (allocated but not freed).
     * 
     * @return The number of active pages
     */
    public long getActivePageCount() {
        return totalAllocated.get() - totalFreed.get();
    }
    
    /**
     * Returns the total bytes allocated from the arena (new pages only).
     * This does not include reused pages, only fresh arena allocations.
     * 
     * @return The total bytes allocated from the arena
     */
    public long getTotalBytesAllocated() {
        return totalBytesAllocated.get();
    }
    
    /**
     * Returns the current size of the free page list.
     * 
     * @return The number of pages in the free list
     */
    public long getFreeListSize() {
        return freeListSize.get();
    }
    
    /**
     * Returns the memory arena used by this allocator.
     * 
     * @return The memory arena
     */
    public Arena getArena() {
        return memoryArena;
    }
    
    /**
     * Calculates and returns the current memory efficiency ratio.
     * This is the percentage of allocated pages that are currently active
     * (not in the free list or freed).
     * 
     * @return Efficiency ratio as a percentage (0.0 to 100.0)
     */
    public double getMemoryEfficiency() {
        statisticsLock.readLock().lock();
        try {
            long total = totalAllocated.get();
            if (total == 0) {
                return 100.0; // No allocations yet, perfect efficiency
            }
            
            long active = getActivePageCount();
            return (double) active / total * 100.0;
            
        } finally {
            statisticsLock.readLock().unlock();
        }
    }
    
    // ================================================================================
    // Maintenance and Cleanup Methods
    // ================================================================================
    
    /**
     * Clears the free page list, discarding all cached pages.
     * This can be useful for memory pressure situations or testing.
     * Active pages are not affected.
     */
    public void clearFreeList() {
        statisticsLock.writeLock().lock();
        try {
            int clearedCount = 0;
            MemorySegment page;
            while ((page = freePages.poll()) != null) {
                clearedCount++;
            }
            // Set the size to match the actual cleared count
            freeListSize.set(0);
            
            log.debug("Cleared free list, discarded {} pages", clearedCount);
            
        } finally {
            statisticsLock.writeLock().unlock();
        }
    }
    
    /**
     * Returns detailed statistics about this allocator's performance.
     * 
     * @return A formatted string containing allocation statistics
     */
    public String getStatistics() {
        statisticsLock.readLock().lock();
        try {
            long totalAlloc = totalAllocated.get();
            long totalFree = totalFreed.get();
            long active = totalAlloc - totalFree;
            long freeList = freeListSize.get();
            long totalBytes = totalBytesAllocated.get();
            double efficiency = getMemoryEfficiency();
            
            return String.format(
                "PageAllocator Statistics:%n" +
                "  Total Allocated: %d pages (%d bytes)%n" +
                "  Total Freed: %d pages%n" +
                "  Active Pages: %d pages%n" +
                "  Free List Size: %d pages%n" +
                "  Memory Efficiency: %.1f%%%n" +
                "  Page Size: %d bytes%n" +
                "  Page Alignment: %d bytes",
                totalAlloc, totalBytes, totalFree, active, freeList, 
                efficiency, PAGE_SIZE_BYTES, PAGE_ALIGNMENT);
                
        } finally {
            statisticsLock.readLock().unlock();
        }
    }
    
    /**
     * Validates and corrects any inconsistencies in statistics counters.
     * This method should be called periodically in high-concurrency scenarios
     * to ensure statistics remain accurate despite potential race conditions.
     * 
     * @return true if corrections were made, false if statistics were already consistent
     */
    public boolean validateAndCorrectStatistics() {
        statisticsLock.writeLock().lock();
        try {
            // Count actual free list size
            int actualFreeListSize = freePages.size();
            long reportedFreeListSize = freeListSize.get();
            
            boolean corrected = false;
            
            // Correct free list size if inconsistent
            if (actualFreeListSize != reportedFreeListSize) {
                freeListSize.set(actualFreeListSize);
                log.warn("Corrected free list size from {} to {}", reportedFreeListSize, actualFreeListSize);
                corrected = true;
            }
            
            // Ensure no negative values
            if (totalAllocated.get() < 0) {
                totalAllocated.set(0);
                log.warn("Corrected negative totalAllocated counter");
                corrected = true;
            }
            
            if (totalFreed.get() < 0) {
                totalFreed.set(0);
                log.warn("Corrected negative totalFreed counter");
                corrected = true;
            }
            
            if (totalBytesAllocated.get() < 0) {
                totalBytesAllocated.set(0);
                log.warn("Corrected negative totalBytesAllocated counter");
                corrected = true;
            }
            
            // Ensure freed count doesn't exceed allocated count
            long allocated = totalAllocated.get();
            long freed = totalFreed.get();
            if (freed > allocated) {
                totalFreed.set(allocated);
                log.warn("Corrected totalFreed ({}) to not exceed totalAllocated ({})", freed, allocated);
                corrected = true;
            }
            
            return corrected;
            
        } finally {
            statisticsLock.writeLock().unlock();
        }
    }
    
    // ================================================================================
    // Private Helper Methods
    // ================================================================================
    
    /**
     * Clears the content of a page by setting all bytes to zero.
     * This is done for security (clear sensitive data) and debugging
     * (make use-after-free bugs more obvious).
     * 
     * @param page The page to clear
     */
    private void clearPage(MemorySegment page) {
        // Fill the entire page with zeros
        page.fill((byte) 0);
    }
    
    // ================================================================================
    // Object Override Methods
    // ================================================================================
    
    @Override
    public String toString() {
        return String.format("PageAllocator{pageSize=%d, active=%d, free=%d, arena=%s}",
                PAGE_SIZE_BYTES, getActivePageCount(), getFreeListSize(), memoryArena);
    }
}
