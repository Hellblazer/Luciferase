package com.hellblazer.luciferase.resource.memory;

import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Memory pool for efficient allocation and reuse of native memory buffers.
 * Reduces allocation overhead and fragmentation by reusing buffers.
 */
public class MemoryPool implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MemoryPool.class);
    
    /**
     * Pool configuration.
     */
    public static class Config {
        public final int minBufferSize;
        public final int maxBufferSize;
        public final int maxPoolSize;
        public final int maxBuffersPerSize;
        public final boolean alignBuffers;
        public final int alignment;
        
        private Config(Builder builder) {
            this.minBufferSize = builder.minBufferSize;
            this.maxBufferSize = builder.maxBufferSize;
            this.maxPoolSize = builder.maxPoolSize;
            this.maxBuffersPerSize = builder.maxBuffersPerSize;
            this.alignBuffers = builder.alignBuffers;
            this.alignment = builder.alignment;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private int minBufferSize = 256;
            private int maxBufferSize = 16 * 1024 * 1024; // 16MB
            private int maxPoolSize = 100;
            private int maxBuffersPerSize = 10;
            private boolean alignBuffers = false;
            private int alignment = 64;
            
            public Builder minBufferSize(int size) {
                this.minBufferSize = size;
                return this;
            }
            
            public Builder maxBufferSize(int size) {
                this.maxBufferSize = size;
                return this;
            }
            
            public Builder maxPoolSize(int size) {
                this.maxPoolSize = size;
                return this;
            }
            
            public Builder maxBuffersPerSize(int count) {
                this.maxBuffersPerSize = count;
                return this;
            }
            
            public Builder alignBuffers(boolean align) {
                this.alignBuffers = align;
                return this;
            }
            
            public Builder alignment(int alignment) {
                this.alignment = alignment;
                return this;
            }
            
            public Config build() {
                return new Config(this);
            }
        }
    }
    
    /**
     * Pooled buffer wrapper that tracks usage.
     */
    private static class PooledBuffer {
        final ByteBuffer buffer;
        final long address;
        final int size;
        final boolean aligned;
        volatile long lastUsed;
        volatile int useCount;
        
        PooledBuffer(ByteBuffer buffer, long address, int size, boolean aligned) {
            this.buffer = buffer;
            this.address = address;
            this.size = size;
            this.aligned = aligned;
            this.lastUsed = System.nanoTime();
            this.useCount = 0;
        }
        
        void markUsed() {
            lastUsed = System.nanoTime();
            useCount++;
        }
        
        long getIdleTime() {
            return System.nanoTime() - lastUsed;
        }
    }
    
    /**
     * Handle for a borrowed buffer that returns to pool on close.
     */
    public class BorrowedBuffer extends NativeMemoryHandle {
        private final PooledBuffer pooledBuffer;
        private volatile boolean returned = false;
        
        private BorrowedBuffer(PooledBuffer pooledBuffer, ResourceTracker tracker) {
            super(pooledBuffer.buffer.duplicate().clear(), pooledBuffer.address, 
                  pooledBuffer.size, pooledBuffer.aligned, tracker);
            this.pooledBuffer = pooledBuffer;
            pooledBuffer.markUsed();
        }
        
        @Override
        protected void doCleanup(ByteBuffer buffer) {
            returnToPool();
        }
        
        private void returnToPool() {
            if (!returned) {
                returned = true;
                MemoryPool.this.returnBuffer(pooledBuffer);
            }
        }
    }
    
    private final Config config;
    private final ResourceTracker tracker;
    private final Map<Integer, ConcurrentLinkedDeque<PooledBuffer>> pools;
    private final Set<PooledBuffer> borrowed;
    private final ReentrantLock lock;
    
    private final AtomicInteger totalBuffers;
    private final AtomicLong totalMemory;
    private final AtomicLong allocations;
    private final AtomicLong poolHits;
    private final AtomicLong poolMisses;
    
    private volatile boolean closed = false;
    
    /**
     * Create a memory pool with default configuration.
     */
    public MemoryPool(ResourceTracker tracker) {
        this(Config.builder().build(), tracker);
    }
    
    /**
     * Create a memory pool with custom configuration.
     */
    public MemoryPool(Config config, ResourceTracker tracker) {
        this.config = config;
        this.tracker = tracker;
        this.pools = new HashMap<>();
        this.borrowed = new HashSet<>();
        this.lock = new ReentrantLock();
        
        this.totalBuffers = new AtomicInteger(0);
        this.totalMemory = new AtomicLong(0);
        this.allocations = new AtomicLong(0);
        this.poolHits = new AtomicLong(0);
        this.poolMisses = new AtomicLong(0);
        
        log.info("Created memory pool with config: minSize={}, maxSize={}, maxPool={}", 
            config.minBufferSize, config.maxBufferSize, config.maxPoolSize);
    }
    
    /**
     * Borrow a buffer from the pool.
     * 
     * @param size Requested size in bytes
     * @return A borrowed buffer handle
     */
    public BorrowedBuffer borrow(int size) {
        if (closed) {
            throw new IllegalStateException("Pool is closed");
        }
        
        if (size < config.minBufferSize || size > config.maxBufferSize) {
            // Size out of range, allocate directly without pooling
            log.trace("Size {} out of pool range, allocating directly", size);
            poolMisses.incrementAndGet();
            
            if (config.alignBuffers) {
                return new BorrowedBuffer(
                    new PooledBuffer(
                        MemoryUtil.memAlignedAlloc(config.alignment, size),
                        0, size, true
                    ), 
                    tracker
                );
            } else {
                return new BorrowedBuffer(
                    new PooledBuffer(
                        MemoryUtil.memAlloc(size),
                        0, size, false
                    ),
                    tracker
                );
            }
        }
        
        allocations.incrementAndGet();
        
        // Round up to power of 2 for better reuse
        int poolSize = roundUpToPowerOf2(size);
        
        lock.lock();
        try {
            var pool = pools.computeIfAbsent(poolSize, k -> new ConcurrentLinkedDeque<>());
            
            // Try to get from pool
            PooledBuffer buffer = pool.poll();
            if (buffer != null) {
                poolHits.incrementAndGet();
                log.trace("Reusing buffer of size {} from pool", poolSize);
                
                // Clear buffer before reuse
                buffer.buffer.clear();
                MemoryUtil.memSet(buffer.buffer, 0);
                
                borrowed.add(buffer);
                return new BorrowedBuffer(buffer, tracker);
            }
            
            // Need to allocate new buffer
            poolMisses.incrementAndGet();
            
            if (totalBuffers.get() >= config.maxPoolSize) {
                // Pool is full, try to evict old buffers
                evictOldBuffers();
            }
            
            // Allocate new buffer
            ByteBuffer newBuffer;
            if (config.alignBuffers) {
                newBuffer = MemoryUtil.memAlignedAlloc(config.alignment, poolSize);
            } else {
                newBuffer = MemoryUtil.memAlloc(poolSize);
            }
            
            var pooledBuffer = new PooledBuffer(
                newBuffer,
                MemoryUtil.memAddress(newBuffer),
                poolSize,
                config.alignBuffers
            );
            
            totalBuffers.incrementAndGet();
            totalMemory.addAndGet(poolSize);
            
            log.trace("Allocated new buffer of size {} for pool", poolSize);
            
            borrowed.add(pooledBuffer);
            return new BorrowedBuffer(pooledBuffer, tracker);
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Return a buffer to the pool.
     */
    private void returnBuffer(PooledBuffer buffer) {
        if (closed) {
            // Pool is closed, free the buffer
            freeBuffer(buffer);
            return;
        }
        
        lock.lock();
        try {
            borrowed.remove(buffer);
            
            var pool = pools.get(buffer.size);
            if (pool != null && pool.size() < config.maxBuffersPerSize) {
                pool.offer(buffer);
                log.trace("Returned buffer of size {} to pool", buffer.size);
            } else {
                // Pool for this size is full, free the buffer
                freeBuffer(buffer);
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Evict old buffers that haven't been used recently.
     */
    private void evictOldBuffers() {
        long evictionAge = 60_000_000_000L; // 60 seconds in nanoseconds
        
        for (var pool : pools.values()) {
            var iterator = pool.iterator();
            while (iterator.hasNext()) {
                var buffer = iterator.next();
                if (buffer.getIdleTime() > evictionAge) {
                    iterator.remove();
                    freeBuffer(buffer);
                    log.trace("Evicted idle buffer of size {}", buffer.size);
                }
            }
        }
    }
    
    /**
     * Free a pooled buffer.
     */
    private void freeBuffer(PooledBuffer buffer) {
        if (buffer.aligned) {
            MemoryUtil.memAlignedFree(buffer.buffer);
        } else {
            MemoryUtil.memFree(buffer.buffer);
        }
        
        totalBuffers.decrementAndGet();
        totalMemory.addAndGet(-buffer.size);
        
        log.trace("Freed buffer of size {}", buffer.size);
    }
    
    /**
     * Round up to nearest power of 2.
     */
    private static int roundUpToPowerOf2(int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }
    
    /**
     * Get pool statistics.
     */
    public String getStatistics() {
        double hitRate = allocations.get() > 0 
            ? (double) poolHits.get() / allocations.get() * 100 
            : 0;
        
        return String.format(
            "MemoryPool[buffers=%d, memory=%s, allocations=%d, hitRate=%.1f%%, borrowed=%d]",
            totalBuffers.get(),
            formatBytes(totalMemory.get()),
            allocations.get(),
            hitRate,
            borrowed.size()
        );
    }
    
    /**
     * Get detailed pool statistics.
     */
    public PoolStatistics getPoolStatistics() {
        lock.lock();
        try {
            return new PoolStatistics(
                totalBuffers.get(),
                totalMemory.get(),
                allocations.get(),
                poolHits.get(),
                poolMisses.get(),
                borrowed.size(),
                pools.size()
            );
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Statistics snapshot for the memory pool.
     */
    public static class PoolStatistics {
        public final int totalBuffers;
        public final long totalMemoryBytes;
        public final long totalAllocations;
        public final long poolHits;
        public final long poolMisses;
        public final int currentlyBorrowed;
        public final int poolSizes;
        
        public PoolStatistics(int totalBuffers, long totalMemoryBytes, long totalAllocations,
                            long poolHits, long poolMisses, int currentlyBorrowed, int poolSizes) {
            this.totalBuffers = totalBuffers;
            this.totalMemoryBytes = totalMemoryBytes;
            this.totalAllocations = totalAllocations;
            this.poolHits = poolHits;
            this.poolMisses = poolMisses;
            this.currentlyBorrowed = currentlyBorrowed;
            this.poolSizes = poolSizes;
        }
        
        public double getHitRate() {
            return totalAllocations > 0 ? (double) poolHits / totalAllocations : 0;
        }
        
        public long getTotalMemoryMB() {
            return totalMemoryBytes / (1024 * 1024);
        }
    }
    
    /**
     * Format bytes for display.
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    /**
     * Clear all pooled buffers.
     */
    public void clear() {
        lock.lock();
        try {
            for (var pool : pools.values()) {
                PooledBuffer buffer;
                while ((buffer = pool.poll()) != null) {
                    freeBuffer(buffer);
                }
            }
            pools.clear();
            
            log.info("Cleared memory pool: {}", getStatistics());
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        
        closed = true;
        
        lock.lock();
        try {
            // Log warning if buffers still borrowed
            if (!borrowed.isEmpty()) {
                log.warn("Closing pool with {} buffers still borrowed", borrowed.size());
            }
            
            // Free all pooled buffers
            clear();
            
            // Free borrowed buffers (leak prevention)
            for (var buffer : borrowed) {
                freeBuffer(buffer);
            }
            borrowed.clear();
            
            log.info("Closed memory pool: total allocations={}, hit rate={:.1f}%",
                allocations.get(),
                allocations.get() > 0 ? (double) poolHits.get() / allocations.get() * 100 : 0);
            
        } finally {
            lock.unlock();
        }
    }
}