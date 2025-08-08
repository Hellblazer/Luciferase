package com.hellblazer.luciferase.render.memory;

import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.List;
import java.util.ArrayList;

/**
 * Advanced GPU memory management for WebGPU rendering pipeline.
 * 
 * Features:
 * - Buffer pooling for frequent allocations
 * - Memory usage tracking and monitoring
 * - Automatic garbage collection of unused buffers
 * - Memory pressure handling with adaptive strategies
 * - Allocation size optimization
 * - Resource lifetime management
 */
public class GPUMemoryManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GPUMemoryManager.class);
    
    // Memory pool configuration
    private static final long[] POOL_SIZES = {
        1024,           // 1KB - small uniform buffers
        4 * 1024,       // 4KB - medium uniform buffers  
        16 * 1024,      // 16KB - shader storage
        64 * 1024,      // 64KB - vertex/index data
        256 * 1024,     // 256KB - texture data
        1024 * 1024,    // 1MB - large textures
        4 * 1024 * 1024,   // 4MB - octree data
        16 * 1024 * 1024,  // 16MB - massive datasets
        64 * 1024 * 1024   // 64MB - ultra large buffers
    };
    
    private static final int MAX_POOLED_BUFFERS_PER_SIZE = 16;
    private static final long MEMORY_PRESSURE_THRESHOLD = 512 * 1024 * 1024; // 512MB
    private static final long GC_INTERVAL_MS = 30000; // 30 seconds
    
    private final WebGPUContext context;
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<PooledBuffer>> bufferPools;
    private final ConcurrentHashMap<Buffer, BufferMetadata> activeBuffers;
    private final ReentrantReadWriteLock memoryLock;
    
    // Memory tracking
    private final AtomicLong totalAllocatedMemory;
    private final AtomicLong peakMemoryUsage;
    private final AtomicLong allocationCount;
    private final AtomicLong poolHitCount;
    private final AtomicLong poolMissCount;
    
    // Memory management thread
    private volatile boolean isShutdown = false;
    private final Thread memoryManagerThread;
    
    public static class MemoryConfiguration {
        public boolean enablePooling = true;
        public boolean enableGarbageCollection = true;
        public boolean enableMemoryPressureHandling = true;
        public int maxPooledBuffersPerSize = MAX_POOLED_BUFFERS_PER_SIZE;
        public long memoryPressureThreshold = MEMORY_PRESSURE_THRESHOLD;
        public long gcInterval = GC_INTERVAL_MS;
        public boolean enableDetailedLogging = false;
    }
    
    private final MemoryConfiguration config;
    
    public GPUMemoryManager(WebGPUContext context, MemoryConfiguration config) {
        this.context = context;
        this.config = config;
        this.bufferPools = new ConcurrentHashMap<>();
        this.activeBuffers = new ConcurrentHashMap<>();
        this.memoryLock = new ReentrantReadWriteLock();
        
        // Initialize memory tracking
        this.totalAllocatedMemory = new AtomicLong(0);
        this.peakMemoryUsage = new AtomicLong(0);
        this.allocationCount = new AtomicLong(0);
        this.poolHitCount = new AtomicLong(0);
        this.poolMissCount = new AtomicLong(0);
        
        // Initialize buffer pools
        for (long size : POOL_SIZES) {
            bufferPools.put(size, new ConcurrentLinkedQueue<>());
        }
        
        // Start memory management thread
        this.memoryManagerThread = new Thread(this::memoryManagementLoop, "GPU-Memory-Manager");
        this.memoryManagerThread.setDaemon(true);
        this.memoryManagerThread.start();
        
        log.info("GPU Memory Manager initialized with {} pool sizes", POOL_SIZES.length);
    }
    
    /**
     * Allocate a GPU buffer with optimized pooling strategy.
     */
    public Buffer allocateBuffer(long size, int usage, String label) {
        allocationCount.incrementAndGet();
        
        // Try to get from pool first
        if (config.enablePooling) {
            PooledBuffer pooled = getFromPool(size, usage);
            if (pooled != null) {
                poolHitCount.incrementAndGet();
                
                // Update metadata
                BufferMetadata metadata = new BufferMetadata(pooled.buffer, size, usage, label, 
                    System.currentTimeMillis(), true);
                activeBuffers.put(pooled.buffer, metadata);
                
                if (config.enableDetailedLogging) {
                    log.debug("Pool hit for buffer {} ({}KB)", label, size / 1024);
                }
                
                return pooled.buffer;
            }
        }
        
        poolMissCount.incrementAndGet();
        
        // Create new buffer
        Buffer buffer = createNewBuffer(size, usage, label);
        
        // Track allocation
        long currentTotal = totalAllocatedMemory.addAndGet(size);
        updatePeakMemory(currentTotal);
        
        // Store metadata
        BufferMetadata metadata = new BufferMetadata(buffer, size, usage, label, 
            System.currentTimeMillis(), false);
        activeBuffers.put(buffer, metadata);
        
        if (config.enableDetailedLogging) {
            log.debug("Created new buffer {} ({}KB), total memory: {}MB", 
                label, size / 1024, currentTotal / (1024 * 1024));
        }
        
        // Check memory pressure
        if (config.enableMemoryPressureHandling && currentTotal > config.memoryPressureThreshold) {
            handleMemoryPressure();
        }
        
        return buffer;
    }
    
    /**
     * Release a buffer back to the pool or free it.
     */
    public void releaseBuffer(Buffer buffer) {
        BufferMetadata metadata = activeBuffers.remove(buffer);
        if (metadata == null) {
            log.warn("Attempted to release unknown buffer");
            return;
        }
        
        totalAllocatedMemory.addAndGet(-metadata.size);
        
        // Try to return to pool
        if (config.enablePooling && shouldReturnToPool(metadata)) {
            if (returnToPool(buffer, metadata)) {
                if (config.enableDetailedLogging) {
                    log.debug("Returned buffer {} to pool", metadata.label);
                }
                return;
            }
        }
        
        // Free the buffer
        buffer.close();
        
        if (config.enableDetailedLogging) {
            log.debug("Released buffer {} ({}KB)", metadata.label, metadata.size / 1024);
        }
    }
    
    /**
     * Get memory usage statistics.
     */
    public MemoryStats getMemoryStats() {
        long currentMemory = totalAllocatedMemory.get();
        long peakMemory = peakMemoryUsage.get();
        long allocations = allocationCount.get();
        long hits = poolHitCount.get();
        long misses = poolMissCount.get();
        
        double hitRate = (allocations > 0) ? (double) hits / allocations : 0.0;
        
        // Count pooled buffers
        int totalPooledBuffers = 0;
        for (var queue : bufferPools.values()) {
            totalPooledBuffers += queue.size();
        }
        
        return new MemoryStats(
            currentMemory,
            peakMemory,
            allocations,
            activeBuffers.size(),
            totalPooledBuffers,
            hitRate,
            hits,
            misses
        );
    }
    
    /**
     * Force garbage collection of unused pooled buffers.
     */
    public void performGarbageCollection() {
        if (!config.enableGarbageCollection) {
            return;
        }
        
        int freedBuffers = 0;
        long freedMemory = 0;
        long currentTime = System.currentTimeMillis();
        
        memoryLock.writeLock().lock();
        try {
            for (var entry : bufferPools.entrySet()) {
                var queue = entry.getValue();
                var iterator = queue.iterator();
                
                while (iterator.hasNext()) {
                    PooledBuffer pooled = iterator.next();
                    
                    // Free buffers that have been pooled for too long
                    if (currentTime - pooled.pooledTime > config.gcInterval) {
                        iterator.remove();
                        pooled.buffer.close();
                        freedBuffers++;
                        freedMemory += entry.getKey();
                    }
                }
            }
        } finally {
            memoryLock.writeLock().unlock();
        }
        
        if (freedBuffers > 0) {
            log.info("GC freed {} buffers, reclaimed {}MB", 
                freedBuffers, freedMemory / (1024 * 1024));
        }
    }
    
    /**
     * Handle memory pressure by aggressively freeing resources.
     */
    private void handleMemoryPressure() {
        log.warn("Memory pressure detected ({}MB), performing aggressive cleanup", 
            totalAllocatedMemory.get() / (1024 * 1024));
        
        // Clear all pools
        int freedBuffers = 0;
        for (var queue : bufferPools.values()) {
            while (!queue.isEmpty()) {
                PooledBuffer pooled = queue.poll();
                if (pooled != null) {
                    pooled.buffer.close();
                    freedBuffers++;
                }
            }
        }
        
        log.info("Memory pressure handling freed {} pooled buffers", freedBuffers);
    }
    
    /**
     * Get buffer from appropriate pool.
     */
    private PooledBuffer getFromPool(long requestedSize, int usage) {
        // Find best matching pool size
        long poolSize = findBestPoolSize(requestedSize);
        if (poolSize == -1) {
            return null; // Too large for pooling
        }
        
        var queue = bufferPools.get(poolSize);
        if (queue == null) {
            return null;
        }
        
        // Try to find a compatible buffer
        PooledBuffer candidate;
        while ((candidate = queue.poll()) != null) {
            if (isBufferCompatible(candidate, usage)) {
                return candidate;
            } else {
                // Buffer not compatible, release it
                candidate.buffer.close();
            }
        }
        
        return null;
    }
    
    /**
     * Return buffer to appropriate pool.
     */
    private boolean returnToPool(Buffer buffer, BufferMetadata metadata) {
        long poolSize = findBestPoolSize(metadata.size);
        if (poolSize == -1) {
            return false; // Too large for pooling
        }
        
        var queue = bufferPools.get(poolSize);
        if (queue == null) {
            return false;
        }
        
        // Check if pool is full
        if (queue.size() >= config.maxPooledBuffersPerSize) {
            return false;
        }
        
        PooledBuffer pooled = new PooledBuffer(buffer, metadata.usage, 
            System.currentTimeMillis());
        return queue.offer(pooled);
    }
    
    /**
     * Create a new GPU buffer.
     */
    private Buffer createNewBuffer(long size, int usage, String label) {
        // Create buffer using WebGPUContext's abstraction
        return context.createBuffer(size, usage);
    }
    
    /**
     * Find the best pool size for requested size.
     */
    private long findBestPoolSize(long requestedSize) {
        for (long poolSize : POOL_SIZES) {
            if (requestedSize <= poolSize) {
                return poolSize;
            }
        }
        return -1; // Too large for pooling
    }
    
    /**
     * Check if buffer should be returned to pool.
     */
    private boolean shouldReturnToPool(BufferMetadata metadata) {
        // Don't pool very large buffers
        if (metadata.size > POOL_SIZES[POOL_SIZES.length - 1]) {
            return false;
        }
        
        // Don't pool mapped buffers
        // Check if buffer has MAP_READ or MAP_WRITE flags
        if ((metadata.usage & 0x0001) != 0 || // MAP_READ
            (metadata.usage & 0x0002) != 0) { // MAP_WRITE
            return false;
        }
        
        return true;
    }
    
    /**
     * Check if pooled buffer is compatible with requested usage.
     */
    private boolean isBufferCompatible(PooledBuffer pooled, int requestedUsage) {
        return pooled.usage == requestedUsage;
    }
    
    /**
     * Update peak memory usage tracking.
     */
    private void updatePeakMemory(long currentMemory) {
        long currentPeak = peakMemoryUsage.get();
        while (currentMemory > currentPeak) {
            if (peakMemoryUsage.compareAndSet(currentPeak, currentMemory)) {
                break;
            }
            currentPeak = peakMemoryUsage.get();
        }
    }
    
    /**
     * Memory management background thread.
     */
    private void memoryManagementLoop() {
        while (!isShutdown) {
            try {
                Thread.sleep(config.gcInterval);
                
                if (!isShutdown && config.enableGarbageCollection) {
                    performGarbageCollection();
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in memory management loop", e);
            }
        }
    }
    
    public void shutdown() {
        close();
    }
    
    @Override
    public void close() {
        isShutdown = true;
        
        // Interrupt management thread
        if (memoryManagerThread != null) {
            memoryManagerThread.interrupt();
        }
        
        // Release all active buffers
        for (Buffer buffer : activeBuffers.keySet()) {
            buffer.close();
        }
        activeBuffers.clear();
        
        // Release all pooled buffers
        for (var queue : bufferPools.values()) {
            while (!queue.isEmpty()) {
                PooledBuffer pooled = queue.poll();
                if (pooled != null) {
                    pooled.buffer.close();
                }
            }
            queue.clear();
        }
        bufferPools.clear();
        
        log.info("GPU Memory Manager shutdown complete");
    }
    
    // Data classes
    
    private static class PooledBuffer {
        final Buffer buffer;
        final int usage;
        final long pooledTime;
        
        PooledBuffer(Buffer buffer, int usage, long pooledTime) {
            this.buffer = buffer;
            this.usage = usage;
            this.pooledTime = pooledTime;
        }
    }
    
    private static class BufferMetadata {
        final Buffer buffer;
        final long size;
        final int usage;
        final String label;
        final long createTime;
        final boolean fromPool;
        
        BufferMetadata(Buffer buffer, long size, int usage, String label, 
                      long createTime, boolean fromPool) {
            this.buffer = buffer;
            this.size = size;
            this.usage = usage;
            this.label = label;
            this.createTime = createTime;
            this.fromPool = fromPool;
        }
    }
    
    public static class MemoryStats {
        public final long currentMemoryBytes;
        public final long peakMemoryBytes;
        public final long totalAllocations;
        public final int activeBuffers;
        public final int pooledBuffers;
        public final double poolHitRate;
        public final long poolHits;
        public final long poolMisses;
        
        MemoryStats(long currentMemoryBytes, long peakMemoryBytes, long totalAllocations,
                   int activeBuffers, int pooledBuffers, double poolHitRate,
                   long poolHits, long poolMisses) {
            this.currentMemoryBytes = currentMemoryBytes;
            this.peakMemoryBytes = peakMemoryBytes;
            this.totalAllocations = totalAllocations;
            this.activeBuffers = activeBuffers;
            this.pooledBuffers = pooledBuffers;
            this.poolHitRate = poolHitRate;
            this.poolHits = poolHits;
            this.poolMisses = poolMisses;
        }
        
        public long getAllocatedBytes() {
            return currentMemoryBytes;
        }
        
        public int getActiveBuffers() {
            return activeBuffers;
        }
        
        public double getPoolHitRate() {
            return poolHitRate;
        }
        
        public String getFormattedStats() {
            return String.format(
                "GPU Memory Stats:\n" +
                "  Current: %.1f MB (Peak: %.1f MB)\n" +
                "  Active Buffers: %d, Pooled: %d\n" +
                "  Total Allocations: %d\n" +
                "  Pool Hit Rate: %.1f%% (%d hits, %d misses)",
                currentMemoryBytes / (1024.0 * 1024.0),
                peakMemoryBytes / (1024.0 * 1024.0),
                activeBuffers, pooledBuffers,
                totalAllocations,
                poolHitRate * 100.0,
                poolHits, poolMisses
            );
        }
    }
}