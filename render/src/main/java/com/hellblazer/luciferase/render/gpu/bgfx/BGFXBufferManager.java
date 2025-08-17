package com.hellblazer.luciferase.render.gpu.bgfx;

import com.hellblazer.luciferase.render.gpu.*;
import org.lwjgl.bgfx.BGFX;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Centralized buffer management system for BGFX compute operations.
 * Handles ESVO-specific buffer binding, synchronization, and lifecycle management.
 * 
 * This class abstracts the differences between OpenGL SSBOs and BGFX compute buffers,
 * providing a unified interface for ESVO buffer operations.
 */
public class BGFXBufferManager {
    
    private final BGFXGPUContext context;
    private final Map<BufferSlot, IGPUBuffer> boundBuffers = new ConcurrentHashMap<>();
    private final Map<BufferSlot, AccessType> slotAccess = new ConcurrentHashMap<>();
    private final AtomicBoolean needsBarrier = new AtomicBoolean(false);
    
    // Buffer pools for common sizes
    private final Map<Integer, IGPUBuffer> bufferPool = new ConcurrentHashMap<>();
    
    // Statistics and monitoring
    private int totalBuffersCreated = 0;
    private int totalBuffersDestroyed = 0;
    private long totalMemoryAllocated = 0;
    
    public BGFXBufferManager(BGFXGPUContext context) {
        this.context = context;
    }
    
    /**
     * Create a buffer optimized for the specified slot's typical usage pattern.
     */
    public IGPUBuffer createBufferForSlot(BufferSlot slot, int size) {
        if (!context.isValid()) {
            throw new IllegalStateException("GPU context not initialized");
        }
        
        // Determine optimal buffer configuration for this slot
        var bufferType = getBufferTypeForSlot(slot);
        var usage = getUsageForSlot(slot);
        
        // Create the buffer
        var buffer = context.createBuffer(bufferType, size, usage);
        if (buffer == null) {
            throw new RuntimeException("Failed to create buffer for slot " + slot);
        }
        
        // Update statistics
        totalBuffersCreated++;
        totalMemoryAllocated += size;
        
        return buffer;
    }
    
    /**
     * Bind a buffer to the specified slot with appropriate access pattern.
     */
    public void bindBuffer(BufferSlot slot, IGPUBuffer buffer, AccessType access) {
        if (!context.isValid()) {
            throw new IllegalStateException("GPU context not initialized");
        }
        
        if (buffer == null || !buffer.isValid()) {
            throw new IllegalArgumentException("Buffer is null or invalid");
        }
        
        // Validate access pattern matches slot requirements
        validateAccessPattern(slot, access);
        
        // Bind the buffer to the appropriate slot
        buffer.bind(slot.getSlotIndex(), access);
        
        // Track the binding for synchronization
        boundBuffers.put(slot, buffer);
        slotAccess.put(slot, access);
        
        // Mark that we may need memory barriers
        if (access != AccessType.READ_ONLY) {
            needsBarrier.set(true);
        }
    }
    
    /**
     * Unbind a buffer from the specified slot.
     */
    public void unbindBuffer(BufferSlot slot) {
        var buffer = boundBuffers.remove(slot);
        if (buffer != null) {
            buffer.unbind();
            slotAccess.remove(slot);
        }
    }
    
    /**
     * Unbind all currently bound buffers.
     */
    public void unbindAllBuffers() {
        for (var slot : BufferSlot.values()) {
            unbindBuffer(slot);
        }
    }
    
    /**
     * Issue memory barriers to ensure buffer writes complete before reads.
     * This is critical for compute shaders that write to buffers read by subsequent dispatches.
     */
    public void memoryBarrier() {
        if (!needsBarrier.get()) {
            return; // No write operations, no barrier needed
        }
        
        // Determine what type of barrier we need based on bound buffers
        var barrierType = determineBarrierType();
        context.memoryBarrier(barrierType);
        
        needsBarrier.set(false);
    }
    
    /**
     * Bulk upload operation for multiple buffers.
     * Optimized for ESVO scene loading where multiple buffers are updated together.
     */
    public void bulkUpload(Map<BufferSlot, ByteBuffer> uploads) {
        for (var entry : uploads.entrySet()) {
            var slot = entry.getKey();
            var data = entry.getValue();
            var buffer = boundBuffers.get(slot);
            
            if (buffer != null && buffer.isValid()) {
                buffer.upload(data, 0);
            }
        }
        
        // Issue barrier after all uploads complete
        memoryBarrier();
    }
    
    /**
     * Create standard ESVO buffer set with typical sizes.
     * This creates all buffers needed for a basic ESVO traversal operation.
     */
    public Map<BufferSlot, IGPUBuffer> createESVOBufferSet(int nodeCount, int pageCount, int rayCount) {
        var buffers = new ConcurrentHashMap<BufferSlot, IGPUBuffer>();
        
        // Calculate buffer sizes based on ESVO data structures
        int nodeBufferSize = nodeCount * 8; // 8 bytes per ESVONode
        int pageBufferSize = pageCount * 8192; // 8KB per ESVOPage
        int workQueueSize = Math.max(rayCount, 32768) * 32; // 32 bytes per work item
        
        // Create core ESVO buffers
        buffers.put(BufferSlot.NODE_BUFFER, createBufferForSlot(BufferSlot.NODE_BUFFER, nodeBufferSize));
        buffers.put(BufferSlot.PAGE_BUFFER, createBufferForSlot(BufferSlot.PAGE_BUFFER, pageBufferSize));
        buffers.put(BufferSlot.WORK_QUEUE, createBufferForSlot(BufferSlot.WORK_QUEUE, workQueueSize));
        buffers.put(BufferSlot.COUNTER_BUFFER, createBufferForSlot(BufferSlot.COUNTER_BUFFER, 16));
        
        // Create optional debug and statistics buffers
        buffers.put(BufferSlot.STATISTICS_BUFFER, createBufferForSlot(BufferSlot.STATISTICS_BUFFER, 1024));
        buffers.put(BufferSlot.DEBUG_BUFFER, createBufferForSlot(BufferSlot.DEBUG_BUFFER, 4096));
        
        // Create uniform buffers
        buffers.put(BufferSlot.TRAVERSAL_UNIFORMS, createBufferForSlot(BufferSlot.TRAVERSAL_UNIFORMS, 84));
        
        return buffers;
    }
    
    /**
     * Bind all buffers in an ESVO buffer set with appropriate access patterns.
     */
    public void bindESVOBufferSet(Map<BufferSlot, IGPUBuffer> buffers) {
        for (var entry : buffers.entrySet()) {
            var slot = entry.getKey();
            var buffer = entry.getValue();
            var access = slot.getTypicalAccess();
            
            bindBuffer(slot, buffer, access);
        }
    }
    
    /**
     * Get buffer statistics for monitoring and debugging.
     */
    public BufferManagerStats getStats() {
        return new BufferManagerStats(
            totalBuffersCreated,
            totalBuffersDestroyed,
            totalMemoryAllocated,
            boundBuffers.size(),
            bufferPool.size()
        );
    }
    
    /**
     * Cleanup all managed buffers and resources.
     */
    public void cleanup() {
        // Unbind all buffers
        unbindAllBuffers();
        
        // Clear buffer pool
        bufferPool.values().forEach(IGPUBuffer::destroy);
        bufferPool.clear();
        
        // Reset statistics
        totalBuffersCreated = 0;
        totalBuffersDestroyed = 0;
        totalMemoryAllocated = 0;
    }
    
    /**
     * Determine the appropriate BGFX buffer type for a given slot.
     */
    private BufferType getBufferTypeForSlot(BufferSlot slot) {
        if (slot.isStorageBuffer()) {
            return BufferType.STORAGE;
        } else if (slot.isUniformBuffer()) {
            return BufferType.UNIFORM;
        } else if (slot.isImageSlot()) {
            return BufferType.TEXTURE;
        } else {
            return BufferType.STORAGE; // Default to storage buffer
        }
    }
    
    /**
     * Determine the appropriate buffer usage pattern for a given slot.
     */
    private BufferUsage getUsageForSlot(BufferSlot slot) {
        return switch (slot.getTypicalAccess()) {
            case READ_ONLY -> BufferUsage.STATIC_READ;
            case WRITE_ONLY -> BufferUsage.STREAM_FROM_GPU;
            case READ_WRITE -> slot.requiresAtomicOps() ? 
                BufferUsage.DYNAMIC_WRITE : BufferUsage.READ_WRITE;
        };
    }
    
    /**
     * Validate that the requested access pattern is compatible with the slot.
     */
    private void validateAccessPattern(BufferSlot slot, AccessType access) {
        var typicalAccess = slot.getTypicalAccess();
        
        // Allow more permissive access than typical, but warn about potential issues
        if (access == AccessType.READ_WRITE && typicalAccess == AccessType.READ_ONLY) {
            // This might indicate a design issue, but allow it
            System.err.println("Warning: Binding read-only buffer " + slot + " with read-write access");
        }
        
        // Atomic operations require read-write access
        if (slot.requiresAtomicOps() && access == AccessType.READ_ONLY) {
            throw new IllegalArgumentException(
                "Buffer slot " + slot + " requires atomic operations but bound as read-only"
            );
        }
    }
    
    /**
     * Determine what type of memory barrier we need based on bound buffer access patterns.
     */
    private BarrierType determineBarrierType() {
        // Check if we have any buffers that need shader storage buffer barriers
        for (var entry : slotAccess.entrySet()) {
            var slot = entry.getKey();
            var access = entry.getValue();
            
            if (slot.isStorageBuffer() && access != AccessType.READ_ONLY) {
                return BarrierType.SHADER_STORAGE_BARRIER;
            }
        }
        
        // Default to general memory barrier
        return BarrierType.ALL_BARRIER;
    }
    
    /**
     * Statistics about buffer manager state and performance.
     */
    public record BufferManagerStats(
        int totalBuffersCreated,
        int totalBuffersDestroyed,
        long totalMemoryAllocated,
        int currentlyBoundBuffers,
        int pooledBuffers
    ) {}
}