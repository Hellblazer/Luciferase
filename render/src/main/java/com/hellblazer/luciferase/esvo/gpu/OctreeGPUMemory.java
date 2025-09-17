package com.hellblazer.luciferase.esvo.gpu;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import com.hellblazer.luciferase.resource.opengl.BufferResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * LWJGL GPU Memory Management for ESVO Octrees
 * 
 * CRITICAL: Uses LWJGL MemoryUtil.memAlignedAlloc() - NO DirectByteBuffer or Unsafe!
 * This is essential for proper GPU memory management and performance.
 * 
 * Key Requirements:
 * - 64-byte alignment for optimal GPU cache performance
 * - Explicit lifecycle management (must call dispose())
 * - Thread-safe operations where needed
 * - Stack allocation for temporary data
 */
public final class OctreeGPUMemory {
    private static final Logger log = LoggerFactory.getLogger(OctreeGPUMemory.class);
    
    // GPU memory alignment requirements
    private static final int GPU_ALIGNMENT = 64; // Cache line alignment
    private static final int NODE_SIZE_BYTES = 8; // OctreeNode = 8 bytes
    
    private ByteBuffer nodeBuffer;
    private final long bufferSize;
    private final int nodeCount;
    private boolean disposed = false;
    
    // Resource manager for GPU resources
    private final UnifiedResourceManager resourceManager = UnifiedResourceManager.getInstance();
    
    // Managed GPU buffer
    private BufferResource nodeSSBO; // Shader Storage Buffer Object
    
    /**
     * Create GPU memory for octree nodes
     * 
     * @param nodeCount Number of octree nodes to allocate
     */
    public OctreeGPUMemory(int nodeCount) {
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("Node count must be positive");
        }
        
        this.nodeCount = nodeCount;
        this.bufferSize = (long)nodeCount * NODE_SIZE_BYTES;
        
        // CRITICAL: Handle large allocations that may exceed int range
        if (bufferSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                String.format("Octree too large for single allocation: %d bytes (max: %d)", 
                            bufferSize, Integer.MAX_VALUE));
        }
        
        // CRITICAL: Use LWJGL aligned allocation for GPU performance
        nodeBuffer = memAlignedAlloc(GPU_ALIGNMENT, (int)bufferSize);
        if (nodeBuffer == null) {
            throw new OutOfMemoryError(
                String.format("Failed to allocate GPU buffer of size: %d bytes", bufferSize));
        }
        
        // Initialize buffer to zero for consistent state
        memSet(nodeBuffer, (byte)0);
        
        log.debug("Allocated octree GPU memory: {} nodes, {} bytes, {}-byte aligned", 
                 nodeCount, bufferSize, GPU_ALIGNMENT);
    }
    
    /**
     * Upload node data to GPU as Shader Storage Buffer Object
     */
    public void uploadToGPU() {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }
        
        if (nodeSSBO == null) {
            // Create storage buffer using resource manager
            nodeSSBO = resourceManager.createStorageBuffer((int)bufferSize, "OctreeNodeSSBO");
        }
        
        // Upload data to the buffer
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, nodeSSBO.getOpenGLId());
        glBufferData(GL_SHADER_STORAGE_BUFFER, nodeBuffer, GL_STATIC_DRAW);
        
        // Check for OpenGL errors
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new RuntimeException(
                String.format("OpenGL error during buffer upload: 0x%X", error));
        }
        
        log.debug("Uploaded {} bytes to GPU SSBO {}", bufferSize, nodeSSBO.getOpenGLId());
    }
    
    /**
     * Bind the node buffer to a shader storage binding point
     * 
     * @param bindingPoint Binding point index (matches shader layout binding)
     */
    public void bindToShader(int bindingPoint) {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }
        
        if (nodeSSBO == null) {
            throw new IllegalStateException("Buffer not uploaded to GPU yet");
        }
        
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, nodeSSBO.getOpenGLId());
    }
    
    /**
     * Write node data directly to CPU buffer
     * 
     * @param nodeIndex Index of the node to write
     * @param childDescriptor First 32 bits of node
     * @param contourDescriptor Second 32 bits of node
     */
    public void writeNode(int nodeIndex, int childDescriptor, int contourDescriptor) {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }
        
        if (nodeIndex < 0 || nodeIndex >= nodeCount) {
            throw new IndexOutOfBoundsException(
                String.format("Node index %d out of range [0, %d)", nodeIndex, nodeCount));
        }
        
        int offset = nodeIndex * NODE_SIZE_BYTES;
        nodeBuffer.putInt(offset, childDescriptor);
        nodeBuffer.putInt(offset + 4, contourDescriptor);
    }
    
    /**
     * Read node data from CPU buffer
     * 
     * @param nodeIndex Index of the node to read
     * @return Array of [childDescriptor, contourDescriptor]
     */
    public int[] readNode(int nodeIndex) {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }
        
        if (nodeIndex < 0 || nodeIndex >= nodeCount) {
            throw new IndexOutOfBoundsException(
                String.format("Node index %d out of range [0, %d)", nodeIndex, nodeCount));
        }
        
        int offset = nodeIndex * NODE_SIZE_BYTES;
        int childDescriptor = nodeBuffer.getInt(offset);
        int contourDescriptor = nodeBuffer.getInt(offset + 4);
        
        return new int[]{childDescriptor, contourDescriptor};
    }
    
    /**
     * Get direct access to the underlying ByteBuffer
     * WARNING: Use with caution - direct buffer manipulation can corrupt data
     */
    public ByteBuffer getNodeBuffer() {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }
        return nodeBuffer.asReadOnlyBuffer(); // Return read-only view for safety
    }
    
    /**
     * Get the GPU buffer object ID (for direct OpenGL use)
     */
    public int getSSBO() {
        return nodeSSBO != null ? nodeSSBO.getOpenGLId() : 0;
    }
    
    /**
     * Get number of nodes allocated
     */
    public int getNodeCount() {
        return nodeCount;
    }
    
    /**
     * Get total buffer size in bytes
     */
    public long getBufferSize() {
        return bufferSize;
    }
    
    /**
     * Check if memory has been disposed
     */
    public boolean isDisposed() {
        return disposed;
    }
    
    /**
     * Dispose GPU memory - MUST be called to avoid memory leaks
     * This method is idempotent and thread-safe
     */
    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        
        try {
            // Delete GPU buffer using resource manager
            if (nodeSSBO != null) {
                nodeSSBO.close();
                nodeSSBO = null;
            }
        } catch (Exception e) {
            log.error("Error disposing GPU buffer", e);
        }
        
        // Free CPU memory
        if (nodeBuffer != null) {
            memAlignedFree(nodeBuffer);
            nodeBuffer = null;
        }
        
        disposed = true;
        log.debug("Disposed octree GPU memory: {} nodes, {} bytes", nodeCount, bufferSize);
    }
    
    @Override
    protected void finalize() throws Throwable {
        if (!disposed) {
            log.warn("OctreeGPUMemory was not properly disposed - memory leak detected!");
            dispose();
        }
        super.finalize();
    }
    
    // === Static Utility Methods ===
    
    /**
     * Execute an operation with stack-allocated temporary memory
     * Perfect for short-lived GPU operations and uniform uploads
     * 
     * @param operation Operation to execute with memory stack
     */
    public static void withStackAllocation(Consumer<MemoryStack> operation) {
        try (MemoryStack stack = stackPush()) {
            operation.accept(stack);
        }
    }
    
    /**
     * Calculate optimal buffer size with alignment
     * 
     * @param requestedSize Requested size in bytes
     * @param alignment Required alignment (must be power of 2)
     * @return Aligned size >= requestedSize
     */
    public static long calculateAlignedSize(long requestedSize, int alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("Alignment must be positive power of 2");
        }
        return (requestedSize + alignment - 1) & ~(alignment - 1);
    }
    
    /**
     * Validate that an address is properly aligned
     * 
     * @param address Memory address to check
     * @param alignment Required alignment
     * @return true if address is aligned
     */
    public static boolean isAligned(long address, int alignment) {
        return (address & (alignment - 1)) == 0;
    }
    
    /**
     * Create a memory pool for managing multiple GPU buffers
     * Useful for dynamic octree operations with frequent allocation/deallocation
     */
    public static final class MemoryPool {
        private static final Logger log = LoggerFactory.getLogger(MemoryPool.class);
        
        // Memory pool implementation would go here
        // This is a placeholder for future optimization if needed
        
        public OctreeGPUMemory acquire(int nodeCount) {
            // For now, just create new memory - can optimize later with pooling
            return new OctreeGPUMemory(nodeCount);
        }
        
        public void release(OctreeGPUMemory memory) {
            if (memory != null && !memory.isDisposed()) {
                memory.dispose();
            }
        }
    }
}