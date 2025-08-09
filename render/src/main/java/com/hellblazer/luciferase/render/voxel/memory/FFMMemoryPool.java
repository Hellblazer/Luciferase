package com.hellblazer.luciferase.render.voxel.memory;

import java.lang.foreign.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance memory pool using Java 24 FFM API.
 * 
 * Provides efficient memory segment pooling for GPU operations,
 * reducing allocation overhead and improving cache locality.
 */
public class FFMMemoryPool implements AutoCloseable {
    
    private final Arena arena;
    private final Queue<MemorySegment> available;
    private final long segmentSize;
    private final int maxPoolSize;
    private final AtomicLong allocatedCount;
    private final AtomicLong borrowedCount;
    private final boolean clearOnRelease;
    
    /**
     * Creates a new memory pool with default settings.
     * 
     * @param segmentSize Size of each memory segment in bytes
     */
    public FFMMemoryPool(long segmentSize) {
        this(segmentSize, 128, true, Arena.ofShared());
    }
    
    /**
     * Creates a new memory pool with custom settings.
     * 
     * @param segmentSize Size of each memory segment in bytes
     * @param maxPoolSize Maximum number of segments to keep in pool
     * @param clearOnRelease Whether to clear segments when released
     * @param arena Arena for memory allocation (shared for multi-threaded access)
     */
    public FFMMemoryPool(long segmentSize, int maxPoolSize, boolean clearOnRelease, Arena arena) {
        this.segmentSize = segmentSize;
        this.maxPoolSize = maxPoolSize;
        this.clearOnRelease = clearOnRelease;
        this.arena = arena;
        this.available = new ConcurrentLinkedQueue<>();
        this.allocatedCount = new AtomicLong(0);
        this.borrowedCount = new AtomicLong(0);
    }
    
    /**
     * Acquires a memory segment from the pool.
     * If no segments are available, allocates a new one.
     * 
     * @return A memory segment of the configured size
     */
    public MemorySegment acquire() {
        var segment = available.poll();
        if (segment == null) {
            // Allocate new segment with 256-byte alignment for GPU compatibility
            segment = arena.allocate(segmentSize, 256);
            allocatedCount.incrementAndGet();
        }
        borrowedCount.incrementAndGet();
        return segment;
    }
    
    /**
     * Releases a memory segment back to the pool.
     * 
     * @param segment The segment to release
     */
    public void release(MemorySegment segment) {
        if (segment == null) {
            return;
        }
        
        // Verify segment size matches
        if (segment.byteSize() != segmentSize) {
            throw new IllegalArgumentException(
                String.format("Segment size mismatch: expected %d, got %d", 
                    segmentSize, segment.byteSize()));
        }
        
        // Clear segment if configured
        if (clearOnRelease) {
            segment.fill((byte) 0);
        }
        
        // Only return to pool if under max size
        if (available.size() < maxPoolSize) {
            available.offer(segment);
        }
        // Otherwise let it be garbage collected
        
        borrowedCount.decrementAndGet();
    }
    
    /**
     * Pre-allocates segments in the pool for better performance.
     * 
     * @param count Number of segments to pre-allocate
     */
    public void preallocate(int count) {
        for (int i = 0; i < count && available.size() < maxPoolSize; i++) {
            var segment = arena.allocate(segmentSize, 256);
            available.offer(segment);
            allocatedCount.incrementAndGet();
        }
    }
    
    /**
     * Clears all segments from the pool.
     * Note: This doesn't free memory, just empties the pool.
     */
    public void clear() {
        available.clear();
    }
    
    /**
     * Gets the number of segments currently available in the pool.
     */
    public int getAvailableCount() {
        return available.size();
    }
    
    /**
     * Gets the total number of segments allocated.
     */
    public long getAllocatedCount() {
        return allocatedCount.get();
    }
    
    /**
     * Gets the number of segments currently borrowed.
     */
    public long getBorrowedCount() {
        return borrowedCount.get();
    }
    
    /**
     * Gets the size of each segment in bytes.
     */
    public long getSegmentSize() {
        return segmentSize;
    }
    
    /**
     * Gets pool statistics as a formatted string.
     */
    public String getStatistics() {
        return String.format(
            "FFMMemoryPool[segmentSize=%d, available=%d, borrowed=%d, allocated=%d, maxPool=%d]",
            segmentSize, getAvailableCount(), getBorrowedCount(), getAllocatedCount(), maxPoolSize);
    }
    
    @Override
    public void close() {
        clear();
        // Arena will be closed when it goes out of scope
    }
    
    /**
     * Builder for creating memory pools with custom configuration.
     */
    public static class Builder {
        private long segmentSize = 4096;
        private int maxPoolSize = 128;
        private boolean clearOnRelease = true;
        private Arena arena = null;
        
        public Builder segmentSize(long size) {
            this.segmentSize = size;
            return this;
        }
        
        public Builder maxPoolSize(int size) {
            this.maxPoolSize = size;
            return this;
        }
        
        public Builder clearOnRelease(boolean clear) {
            this.clearOnRelease = clear;
            return this;
        }
        
        public Builder arena(Arena arena) {
            this.arena = arena;
            return this;
        }
        
        public FFMMemoryPool build() {
            var finalArena = arena != null ? arena : Arena.ofShared();
            return new FFMMemoryPool(segmentSize, maxPoolSize, clearOnRelease, finalArena);
        }
    }
}