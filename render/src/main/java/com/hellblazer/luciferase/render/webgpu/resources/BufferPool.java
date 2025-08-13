package com.hellblazer.luciferase.render.webgpu.resources;

import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import com.hellblazer.luciferase.webgpu.wrapper.Device;
import com.hellblazer.luciferase.webgpu.wrapper.Device.BufferDescriptor;
import com.hellblazer.luciferase.webgpu.builder.WebGPUBuilder.BufferUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Efficient buffer pool for WebGPU buffers.
 * Reuses buffers to minimize allocation overhead.
 */
public class BufferPool {
    private static final Logger log = LoggerFactory.getLogger(BufferPool.class);
    
    private final Device device;
    private final Map<Long, Queue<Buffer>> availableBuffers = new ConcurrentHashMap<>();
    private final Map<Buffer, BufferInfo> bufferInfoMap = new ConcurrentHashMap<>();
    private final Set<Buffer> inUseBuffers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Statistics
    private long totalAllocated = 0;
    private long totalReused = 0;
    private int peakBuffers = 0;
    
    private static class BufferInfo {
        final long size;
        final int usage;
        long lastUsed;
        int useCount;
        
        BufferInfo(long size, int usage) {
            this.size = size;
            this.usage = usage;
            this.lastUsed = System.currentTimeMillis();
            this.useCount = 0;
        }
    }
    
    public BufferPool(Device device) {
        this.device = device;
    }
    
    /**
     * Acquire a buffer with the specified size and usage.
     * Will reuse an existing buffer if available, otherwise creates a new one.
     */
    public Buffer acquire(long size, int usage) {
        long alignedSize = alignTo256(size);
        Queue<Buffer> pool = availableBuffers.computeIfAbsent(alignedSize, 
            k -> new ConcurrentLinkedQueue<>());
        
        Buffer buffer = null;
        
        // Try to find a compatible buffer
        int attempts = Math.min(pool.size(), 5); // Check up to 5 buffers
        for (int i = 0; i < attempts; i++) {
            Buffer candidate = pool.poll();
            if (candidate != null) {
                BufferInfo info = bufferInfoMap.get(candidate);
                if (info != null && (info.usage & usage) == usage) {
                    buffer = candidate;
                    totalReused++;
                    log.debug("Reusing buffer of size {} (reuse #{}))", alignedSize, totalReused);
                    break;
                } else {
                    // Put it back if not compatible
                    pool.offer(candidate);
                }
            }
        }
        
        // Create new buffer if none available
        if (buffer == null) {
            buffer = createBuffer(alignedSize, usage);
            totalAllocated++;
            log.debug("Created new buffer of size {} (total: {})", alignedSize, totalAllocated);
        }
        
        // Update tracking
        BufferInfo info = bufferInfoMap.get(buffer);
        if (info != null) {
            info.lastUsed = System.currentTimeMillis();
            info.useCount++;
        }
        
        inUseBuffers.add(buffer);
        peakBuffers = Math.max(peakBuffers, inUseBuffers.size());
        
        return buffer;
    }
    
    /**
     * Release a buffer back to the pool for reuse.
     */
    public void release(Buffer buffer) {
        if (buffer == null) {
            return;
        }
        
        if (!inUseBuffers.remove(buffer)) {
            log.warn("Attempting to release buffer that wasn't tracked as in use");
            return;
        }
        
        BufferInfo info = bufferInfoMap.get(buffer);
        if (info != null) {
            Queue<Buffer> pool = availableBuffers.get(info.size);
            if (pool != null) {
                pool.offer(buffer);
                log.debug("Released buffer of size {} back to pool", info.size);
            }
        }
    }
    
    /**
     * Create a buffer with specific usage flags combination.
     */
    public Buffer createVertexBuffer(long size) {
        return acquire(size, BufferUsage.VERTEX.getValue() | BufferUsage.COPY_DST.getValue());
    }
    
    public Buffer createIndexBuffer(long size) {
        return acquire(size, BufferUsage.INDEX.getValue() | BufferUsage.COPY_DST.getValue());
    }
    
    public Buffer createUniformBuffer(long size) {
        return acquire(size, BufferUsage.UNIFORM.getValue() | BufferUsage.COPY_DST.getValue());
    }
    
    public Buffer createStorageBuffer(long size) {
        return acquire(size, BufferUsage.STORAGE.getValue() | BufferUsage.COPY_DST.getValue());
    }
    
    public Buffer createStagingBuffer(long size) {
        return acquire(size, BufferUsage.MAP_WRITE.getValue() | BufferUsage.COPY_SRC.getValue());
    }
    
    /**
     * Trim the pool by removing buffers that haven't been used recently.
     */
    public void trim(long maxIdleTimeMs) {
        long now = System.currentTimeMillis();
        int removed = 0;
        
        for (Map.Entry<Long, Queue<Buffer>> entry : availableBuffers.entrySet()) {
            Queue<Buffer> pool = entry.getValue();
            Iterator<Buffer> iter = pool.iterator();
            
            while (iter.hasNext()) {
                Buffer buffer = iter.next();
                BufferInfo info = bufferInfoMap.get(buffer);
                
                if (info != null && (now - info.lastUsed) > maxIdleTimeMs) {
                    iter.remove();
                    bufferInfoMap.remove(buffer);
                    // Buffer cleanup - destroy() not available in current wrapper
                    removed++;
                }
            }
        }
        
        if (removed > 0) {
            log.info("Trimmed {} idle buffers from pool", removed);
        }
    }
    
    /**
     * Clear all buffers from the pool.
     */
    public void clear() {
        log.info("Clearing buffer pool (allocated: {}, reused: {}, peak: {})",
            totalAllocated, totalReused, peakBuffers);
        
        // Destroy all available buffers
        for (Queue<Buffer> pool : availableBuffers.values()) {
            for (Buffer buffer : pool) {
                // Buffer cleanup - destroy() not available in current wrapper
            }
        }
        availableBuffers.clear();
        
        // Destroy in-use buffers (careful - they might still be in use!)
        for (Buffer buffer : inUseBuffers) {
            log.warn("Destroying in-use buffer - may cause errors!");
            // Buffer cleanup - destroy() not available in current wrapper
        }
        inUseBuffers.clear();
        
        bufferInfoMap.clear();
    }
    
    /**
     * Get pool statistics.
     */
    public String getStatistics() {
        int totalAvailable = availableBuffers.values().stream()
            .mapToInt(Queue::size)
            .sum();
        
        return String.format(
            "BufferPool[allocated=%d, reused=%d, inUse=%d, available=%d, peak=%d]",
            totalAllocated, totalReused, inUseBuffers.size(), totalAvailable, peakBuffers
        );
    }
    
    private Buffer createBuffer(long size, int usage) {
        BufferDescriptor desc = new BufferDescriptor(size, usage)
            .withMappedAtCreation(false)
            .withLabel("PooledBuffer_" + size);
        
        Buffer buffer = device.createBuffer(desc);
        bufferInfoMap.put(buffer, new BufferInfo(size, usage));
        
        return buffer;
    }
    
    private long alignTo256(long size) {
        // Align to 256 bytes for better GPU memory alignment
        return ((size + 255) / 256) * 256;
    }
}