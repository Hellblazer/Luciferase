package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages GPU buffer allocations, pooling, and memory statistics.
 */
public class GPUBufferManager {
    private static final Logger log = LoggerFactory.getLogger(GPUBufferManager.class);
    
    // Buffer usage flags from WebGPU spec
    public static final int BUFFER_USAGE_MAP_READ = 0x0001;
    public static final int BUFFER_USAGE_MAP_WRITE = 0x0002;
    public static final int BUFFER_USAGE_COPY_SRC = 0x0004;
    public static final int BUFFER_USAGE_COPY_DST = 0x0008;
    public static final int BUFFER_USAGE_INDEX = 0x0010;
    public static final int BUFFER_USAGE_VERTEX = 0x0020;
    public static final int BUFFER_USAGE_UNIFORM = 0x0040;
    public static final int BUFFER_USAGE_STORAGE = 0x0080;
    public static final int BUFFER_USAGE_INDIRECT = 0x0100;
    public static final int BUFFER_USAGE_QUERY_RESOLVE = 0x0200;
    
    // Shader stage flags from WebGPU spec
    public static final int SHADER_STAGE_VERTEX = 0x0001;
    public static final int SHADER_STAGE_FRAGMENT = 0x0002;
    public static final int SHADER_STAGE_COMPUTE = 0x0004;
    
    // Buffer binding types from WebGPU spec
    public static final int BUFFER_BINDING_TYPE_UNIFORM = 0x00000001;
    public static final int BUFFER_BINDING_TYPE_STORAGE = 0x00000002;
    public static final int BUFFER_BINDING_TYPE_READ_ONLY_STORAGE = 0x00000003;
    
    private final WebGPUContext context;
    private final Map<Buffer, BufferInfo> bufferRegistry = new ConcurrentHashMap<>();
    private final Map<Long, Queue<Buffer>> bufferPool = new ConcurrentHashMap<>();
    private final AtomicLong totalAllocated = new AtomicLong();
    private final AtomicLong activeBuffers = new AtomicLong();
    
    public GPUBufferManager(WebGPUContext context) {
        this.context = context;
    }
    
    /**
     * Create a GPU buffer with specified size and usage
     */
    public Buffer createBuffer(long size, int usage) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        
        Buffer buffer = context.createBuffer(size, usage);
        bufferRegistry.put(buffer, new BufferInfo(size, usage));
        totalAllocated.addAndGet(size);
        activeBuffers.incrementAndGet();
        
        log.debug("Created buffer: size={}, usage={}", size, usage);
        return buffer;
    }
    
    /**
     * Write data to a buffer
     */
    public void writeBuffer(Buffer buffer, long offset, MemorySegment data) {
        if (data == null) {
            throw new NullPointerException("Data cannot be null");
        }
        
        byte[] bytes = data.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        context.writeBuffer(buffer, bytes, offset);
    }
    
    /**
     * Write byte array data to a buffer
     */
    public void writeBuffer(Buffer buffer, long offset, byte[] data) {
        if (data == null) {
            throw new NullPointerException("Data cannot be null");
        }
        
        context.writeBuffer(buffer, data, offset);
    }
    
    /**
     * Write ByteBuffer data to a buffer
     */
    public void writeBuffer(Buffer buffer, long offset, ByteBuffer data) {
        if (data == null) {
            throw new NullPointerException("Data cannot be null");
        }
        
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        context.writeBuffer(buffer, bytes, offset);
    }
    
    /**
     * Read data from a buffer
     */
    public CompletableFuture<MemorySegment> readBuffer(Buffer buffer, long offset, long size) {
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
     * Read data from a buffer as byte array
     */
    public CompletableFuture<byte[]> readBufferBytes(Buffer buffer, long offset, long size) {
        return CompletableFuture.supplyAsync(() -> 
            context.readBuffer(buffer, size, offset)
        );
    }
    
    /**
     * Release a buffer
     */
    public void releaseBuffer(Buffer buffer) {
        BufferInfo info = bufferRegistry.remove(buffer);
        if (info != null) {
            buffer.close();
            activeBuffers.decrementAndGet();
            log.debug("Released buffer: size={}", info.size);
        }
    }
    
    /**
     * Allocate a buffer from the pool or create new
     */
    public Buffer allocateFromPool(long size) {
        Queue<Buffer> pool = bufferPool.get(size);
        if (pool != null) {
            Buffer buffer = pool.poll();
            if (buffer != null) {
                log.debug("Reused pooled buffer: size={}", size);
                activeBuffers.incrementAndGet();
                return buffer;
            }
        }
        
        return createBuffer(size, BUFFER_USAGE_STORAGE | BUFFER_USAGE_COPY_DST);
    }
    
    /**
     * Return a buffer to the pool
     */
    public void returnToPool(Buffer buffer) {
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
    public Buffer createStagingBuffer(long size) {
        return createBuffer(size, BUFFER_USAGE_MAP_WRITE | BUFFER_USAGE_COPY_SRC);
    }
    
    /**
     * Write to a staging buffer
     */
    public void writeToStagingBuffer(Buffer buffer, long offset, MemorySegment data) {
        writeBuffer(buffer, offset, data);
    }
    
    /**
     * Copy buffer contents using GPU commands
     */
    public void copyBuffer(Buffer source, Buffer dest, long size) {
        // Use GPU-side copy via command encoder
        context.copyBuffer(source, dest, size);
    }
    
    /**
     * Create a dynamic buffer that can be resized
     */
    public Buffer createDynamicBuffer(long initialSize) {
        return createBuffer(initialSize, BUFFER_USAGE_STORAGE | BUFFER_USAGE_COPY_DST | BUFFER_USAGE_COPY_SRC);
    }
    
    /**
     * Resize a buffer
     */
    public Buffer resizeBuffer(Buffer oldBuffer, long newSize) {
        Buffer newBuffer = createDynamicBuffer(newSize);
        
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
        List<Buffer> buffers = new ArrayList<>();
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
        bufferRegistry.keySet().forEach(Buffer::close);
        bufferRegistry.clear();
        
        // Clear pools
        bufferPool.values().forEach(pool -> {
            pool.forEach(Buffer::close);
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
        private final List<Buffer> buffers;
        private int currentIndex = 0;
        
        MultiBufferHandle(List<Buffer> buffers) {
            this.buffers = buffers;
        }
        
        public Buffer getCurrentBuffer() {
            return buffers.get(currentIndex);
        }
        
        public void swapBuffers() {
            currentIndex = (currentIndex + 1) % buffers.size();
        }
        
        public int getBufferCount() {
            return buffers.size();
        }
        
        public void release() {
            buffers.forEach(Buffer::close);
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