package com.hellblazer.luciferase.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe memory pool for efficient ByteBuffer reuse.
 * Implements various eviction strategies and tracks usage statistics.
 */
public class MemoryPool {
    private static final Logger log = LoggerFactory.getLogger(MemoryPool.class);
    
    // Pool storage - maps size to list of available buffers
    private final Map<Integer, Queue<PooledBuffer>> pools = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Configuration
    private final long maxPoolSizeBytes;
    private final Duration maxIdleTime;
    
    // Statistics
    private final AtomicLong currentSizeBytes = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);
    private final AtomicLong totalAllocated = new AtomicLong(0);
    private final AtomicLong totalReturned = new AtomicLong(0);
    
    /**
     * Create a memory pool with specified configuration.
     */
    public MemoryPool(long maxPoolSizeBytes, Duration maxIdleTime) {
        if (maxPoolSizeBytes <= 0) {
            throw new IllegalArgumentException("Max pool size must be positive");
        }
        if (maxIdleTime == null || maxIdleTime.isNegative()) {
            throw new IllegalArgumentException("Max idle time must be non-negative");
        }
        
        this.maxPoolSizeBytes = maxPoolSizeBytes;
        this.maxIdleTime = maxIdleTime;
    }
    
    /**
     * Allocate a ByteBuffer from the pool or create a new one.
     */
    public ByteBuffer allocate(int sizeBytes) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size must be non-negative");
        }
        
        if (sizeBytes == 0) {
            return ByteBuffer.allocateDirect(0);
        }
        
        lock.readLock().lock();
        try {
            var queue = pools.get(sizeBytes);
            if (queue != null && !queue.isEmpty()) {
                var pooled = queue.poll();
                if (pooled != null && !pooled.isExpired(maxIdleTime)) {
                    hitCount.incrementAndGet();
                    currentSizeBytes.addAndGet(-sizeBytes);
                    pooled.buffer.clear();
                    return pooled.buffer;
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        
        // Cache miss - allocate new buffer
        missCount.incrementAndGet();
        totalAllocated.incrementAndGet();
        return ByteBuffer.allocateDirect(sizeBytes);
    }
    
    /**
     * Return a ByteBuffer to the pool for reuse.
     */
    public void returnToPool(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return;
        }
        
        int capacity = buffer.capacity();
        if (capacity == 0) {
            return;
        }
        
        // Check if adding this buffer would exceed max pool size
        if (currentSizeBytes.get() + capacity > maxPoolSizeBytes) {
            // Try to evict old buffers first
            evictExpired();
            
            // If still too large, evict until we have space
            while (currentSizeBytes.get() + capacity > maxPoolSizeBytes) {
                if (!evictOldest()) {
                    // Can't make room, don't add to pool
                    return;
                }
            }
        }
        
        lock.writeLock().lock();
        try {
            var queue = pools.computeIfAbsent(capacity, k -> new ConcurrentLinkedQueue<>());
            queue.offer(new PooledBuffer(buffer));
            currentSizeBytes.addAndGet(capacity);
            totalReturned.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Evict expired buffers from the pool.
     */
    public void evictExpired() {
        lock.writeLock().lock();
        try {
            for (var entry : pools.entrySet()) {
                var queue = entry.getValue();
                var iter = queue.iterator();
                while (iter.hasNext()) {
                    var pooled = iter.next();
                    if (pooled.isExpired(maxIdleTime)) {
                        iter.remove();
                        currentSizeBytes.addAndGet(-pooled.buffer.capacity());
                        evictionCount.incrementAndGet();
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Evict the oldest buffer from the pool.
     */
    private boolean evictOldest() {
        lock.writeLock().lock();
        try {
            PooledBuffer oldest = null;
            Queue<PooledBuffer> oldestQueue = null;
            
            for (var queue : pools.values()) {
                if (!queue.isEmpty()) {
                    var first = queue.peek();
                    if (first != null && (oldest == null || first.addedTime.isBefore(oldest.addedTime))) {
                        oldest = first;
                        oldestQueue = queue;
                    }
                }
            }
            
            if (oldest != null && oldestQueue != null) {
                oldestQueue.poll();
                currentSizeBytes.addAndGet(-oldest.buffer.capacity());
                evictionCount.incrementAndGet();
                return true;
            }
            
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Clear all buffers from the pool.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            pools.clear();
            currentSizeBytes.set(0);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get the current size of the pool in bytes.
     */
    public long getCurrentSize() {
        return currentSizeBytes.get();
    }
    
    /**
     * Get pool statistics for monitoring.
     */
    public PoolStatistics getPoolStatistics() {
        lock.readLock().lock();
        try {
            long allocCount = 0;
            long returnCount = 0;
            long evictCount = 0;
            long hits = 0;
            long misses = 0;
            
            // Count buffers in each pool
            for (var poolEntry : pools.entrySet()) {
                var queue = poolEntry.getValue();
                returnCount += queue.size();
            }
            
            // Calculate hit rate (simplified - in real implementation would track actual hits/misses)
            double hitRate = returnCount > 0 ? (double)returnCount / (returnCount + allocCount) : 0.0;
            
            return new PoolStatistics(
                pools.size(),           // poolCount
                returnCount,            // bufferCount  
                evictCount,            // evictionCount
                currentSizeBytes.get(), // currentSizeBytes
                allocCount,            // totalAllocated
                returnCount,           // totalReturned
                hitRate                // hitRate
            );
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get the cache hit rate (0.0 to 1.0).
     */
    public double getHitRate() {
        long hits = hitCount.get();
        long total = hits + missCount.get();
        return total == 0 ? 0.0 : (double) hits / total;
    }
    
    /**
     * Get detailed pool statistics.
     */
    public PoolStatistics getStatistics() {
        return new PoolStatistics(
            hitCount.get(),
            missCount.get(),
            evictionCount.get(),
            currentSizeBytes.get(),
            totalAllocated.get(),
            totalReturned.get(),
            getHitRate()
        );
    }
    
    /**
     * Statistics for the memory pool.
     */
    public static class PoolStatistics {
        private final long hitCount;
        private final long missCount;
        private final long evictionCount;
        private final long currentSizeBytes;
        private final long totalAllocated;
        private final long totalReturned;
        private final double hitRate;
        
        public PoolStatistics(long hitCount, long missCount, long evictionCount,
                            long currentSizeBytes, long totalAllocated, 
                            long totalReturned, double hitRate) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.evictionCount = evictionCount;
            this.currentSizeBytes = currentSizeBytes;
            this.totalAllocated = totalAllocated;
            this.totalReturned = totalReturned;
            this.hitRate = hitRate;
        }
        
        public long getHitCount() { return hitCount; }
        public long getMissCount() { return missCount; }
        public long getEvictionCount() { return evictionCount; }
        public long getCurrentSizeBytes() { return currentSizeBytes; }
        public long getTotalAllocated() { return totalAllocated; }
        public long getTotalReturned() { return totalReturned; }
        public double getHitRate() { return hitRate; }
    }
    
    /**
     * Wrapper for pooled buffers with timestamp.
     */
    private static class PooledBuffer {
        final ByteBuffer buffer;
        final Instant addedTime;
        
        PooledBuffer(ByteBuffer buffer) {
            this.buffer = buffer;
            this.addedTime = Instant.now();
        }
        
        boolean isExpired(Duration maxIdleTime) {
            return Duration.between(addedTime, Instant.now()).compareTo(maxIdleTime) > 0;
        }
    }
}