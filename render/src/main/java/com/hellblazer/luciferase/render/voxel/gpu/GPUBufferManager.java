package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.webgpu.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages GPU buffer allocations, pooling, and memory statistics.
 */
public class GPUBufferManager {
    private static final Logger log = LoggerFactory.getLogger(GPUBufferManager.class);
    
    private final WebGPUContext context;
    private final Map<BufferHandle, BufferInfo> bufferRegistry = new ConcurrentHashMap<>();
    private final Map<Long, Queue<BufferHandle>> bufferPool = new ConcurrentHashMap<>();
    private final AtomicLong totalAllocated = new AtomicLong();
    private final AtomicLong activeBuffers = new AtomicLong();
    
    public GPUBufferManager(WebGPUContext context) {
        this.context = context;
    }
    
    /**
     * Create a GPU buffer with specified size and usage
     */
    public BufferHandle createBuffer(long size, int usage) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        
        BufferHandle buffer = context.createBuffer(size, usage);
        bufferRegistry.put(buffer, new BufferInfo(size, usage));
        totalAllocated.addAndGet(size);
        activeBuffers.incrementAndGet();
        
        log.debug("Created buffer: size={}, usage={}", size, usage);
        return buffer;
    }
    
    /**
     * Write data to a buffer
     */
    public void writeBuffer(BufferHandle buffer, long offset, MemorySegment data) {
        if (data == null) {
            throw new NullPointerException("Data cannot be null");
        }
        
        byte[] bytes = data.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        context.writeBuffer(buffer, bytes, offset);
    }
    
    /**
     * Read data from a buffer
     */
    public CompletableFuture<MemorySegment> readBuffer(BufferHandle buffer, long offset, long size) {
        return CompletableFuture.supplyAsync(() -> {
            byte[] data = context.readBuffer(buffer, size, offset);
            // Create a properly aligned MemorySegment for the data
            // Use Arena to allocate aligned memory and copy the data
            try (var arena = java.lang.foreign.Arena.ofConfined()) {
                // Allocate aligned memory that can handle float/int access
                MemorySegment aligned = arena.allocate(size, 8); // 8-byte alignment for safety
                // Copy the byte data into the aligned segment
                for (int i = 0; i < data.length; i++) {
                    aligned.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, data[i]);
                }
                // Return a slice that will persist beyond the arena scope
                // We need to use an off-heap segment that outlives the arena
                var offHeap = java.lang.foreign.Arena.global().allocate(size, 8);
                offHeap.copyFrom(aligned);
                return offHeap;
            }
        });
    }
    
    /**
     * Release a buffer
     */
    public void releaseBuffer(BufferHandle buffer) {
        BufferInfo info = bufferRegistry.remove(buffer);
        if (info != null) {
            buffer.release();
            activeBuffers.decrementAndGet();
            log.debug("Released buffer: size={}", info.size);
        }
    }
    
    /**
     * Allocate a buffer from the pool or create new
     */
    public BufferHandle allocateFromPool(long size) {
        Queue<BufferHandle> pool = bufferPool.get(size);
        if (pool != null) {
            BufferHandle buffer = pool.poll();
            if (buffer != null) {
                log.debug("Reused pooled buffer: size={}", size);
                activeBuffers.incrementAndGet();
                return buffer;
            }
        }
        
        return createBuffer(size, BufferUsage.STORAGE | BufferUsage.COPY_DST);
    }
    
    /**
     * Return a buffer to the pool
     */
    public void returnToPool(BufferHandle buffer) {
        BufferInfo info = bufferRegistry.get(buffer);
        if (info != null) {
            bufferPool.computeIfAbsent(info.size, k -> new LinkedList<>()).offer(buffer);
            activeBuffers.decrementAndGet();
            log.debug("Returned buffer to pool: size={}", info.size);
        }
    }
    
    /**
     * Create a staging buffer for uploads
     */
    public BufferHandle createStagingBuffer(long size) {
        return createBuffer(size, BufferUsage.MAP_WRITE | BufferUsage.COPY_SRC);
    }
    
    /**
     * Write to a staging buffer
     */
    public void writeToStagingBuffer(BufferHandle buffer, long offset, MemorySegment data) {
        writeBuffer(buffer, offset, data);
    }
    
    /**
     * Copy buffer contents
     */
    public void copyBuffer(BufferHandle source, BufferHandle dest, long size) {
        // In a real implementation, this would use GPU command encoder
        // For now, we simulate with read/write
        byte[] data = context.readBuffer(source, size, 0);
        context.writeBuffer(dest, data, 0);
    }
    
    /**
     * Create a dynamic buffer that can be resized
     */
    public BufferHandle createDynamicBuffer(long initialSize) {
        return createBuffer(initialSize, BufferUsage.STORAGE | BufferUsage.COPY_DST | BufferUsage.COPY_SRC);
    }
    
    /**
     * Resize a buffer
     */
    public BufferHandle resizeBuffer(BufferHandle oldBuffer, long newSize) {
        BufferHandle newBuffer = createDynamicBuffer(newSize);
        
        // Copy old data if exists
        BufferInfo info = bufferRegistry.get(oldBuffer);
        if (info != null && info.size > 0) {
            long copySize = Math.min(info.size, newSize);
            copyBuffer(oldBuffer, newBuffer, copySize);
        }
        
        releaseBuffer(oldBuffer);
        return newBuffer;
    }
    
    /**
     * Create a multi-buffer for double/triple buffering
     */
    public MultiBufferHandle createMultiBuffer(int count, long size, int usage) {
        List<BufferHandle> buffers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            buffers.add(createBuffer(size, usage));
        }
        return new MultiBufferHandle(buffers);
    }
    
    /**
     * Get memory statistics
     */
    public MemoryStatistics getMemoryStatistics() {
        long pooledCount = bufferPool.values().stream()
            .mapToLong(Queue::size)
            .sum();
        
        return new MemoryStatistics(
            totalAllocated.get(),
            activeBuffers.get(),
            pooledCount
        );
    }
    
    /**
     * Cleanup all resources
     */
    public void cleanup() {
        // Release all active buffers
        bufferRegistry.keySet().forEach(BufferHandle::release);
        bufferRegistry.clear();
        
        // Clear pools
        bufferPool.values().forEach(pool -> {
            pool.forEach(BufferHandle::release);
            pool.clear();
        });
        bufferPool.clear();
        
        totalAllocated.set(0);
        activeBuffers.set(0);
        
        log.info("GPUBufferManager cleaned up");
    }
    
    /**
     * Buffer information
     */
    private static class BufferInfo {
        final long size;
        final int usage;
        
        BufferInfo(long size, int usage) {
            this.size = size;
            this.usage = usage;
        }
    }
    
    /**
     * Multi-buffer handle for double/triple buffering
     */
    public static class MultiBufferHandle {
        private final List<BufferHandle> buffers;
        private int currentIndex = 0;
        
        MultiBufferHandle(List<BufferHandle> buffers) {
            this.buffers = buffers;
        }
        
        public BufferHandle getCurrentBuffer() {
            return buffers.get(currentIndex);
        }
        
        public void swapBuffers() {
            currentIndex = (currentIndex + 1) % buffers.size();
        }
        
        public int getBufferCount() {
            return buffers.size();
        }
        
        public void release() {
            buffers.forEach(BufferHandle::release);
        }
    }
    
    /**
     * Memory statistics
     */
    public static class MemoryStatistics {
        private final long totalAllocated;
        private final long activeBuffers;
        private final long pooledBuffers;
        
        MemoryStatistics(long totalAllocated, long activeBuffers, long pooledBuffers) {
            this.totalAllocated = totalAllocated;
            this.activeBuffers = activeBuffers;
            this.pooledBuffers = pooledBuffers;
        }
        
        public long getTotalAllocated() {
            return totalAllocated;
        }
        
        public long getActiveBuffers() {
            return activeBuffers;
        }
        
        public long getPooledBuffers() {
            return pooledBuffers;
        }
    }
}