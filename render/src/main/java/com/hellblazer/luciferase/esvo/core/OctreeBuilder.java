package com.hellblazer.luciferase.esvo.core;

import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import com.hellblazer.luciferase.resource.ByteBufferResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU-based octree builder for ESVO with resource tracking
 * 
 * Manages memory allocations during octree construction:
 * - Triangle voxelization
 * - Parallel subdivision with thread limits
 * - Thread-local batch management
 * - Error metric calculation
 * - Attribute filtering and quantization
 * - Automatic resource cleanup
 */
public class OctreeBuilder implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OctreeBuilder.class);
    
    private final int maxDepth;
    private final List<VoxelData> voxels;
    private final UnifiedResourceManager resourceManager;
    private final AtomicLong totalMemoryAllocated;
    private final AtomicBoolean closed;
    private final List<ByteBuffer> allocatedBuffers;
    
    public OctreeBuilder(int maxDepth) {
        this.maxDepth = maxDepth;
        this.voxels = new ArrayList<>();
        this.resourceManager = UnifiedResourceManager.getInstance();
        this.totalMemoryAllocated = new AtomicLong(0);
        this.closed = new AtomicBoolean(false);
        this.allocatedBuffers = new ArrayList<>();
        
        log.debug("OctreeBuilder created with maxDepth={}", maxDepth);
    }
    
    /**
     * Add a voxel at the specified position and level
     */
    public void addVoxel(int x, int y, int z, int level, float density) {
        ensureNotClosed();
        
        // Calculate position in [1,2] coordinate space
        int resolution = 1 << level;
        float voxelSize = 1.0f / resolution;
        
        Vector3f position = new Vector3f(
            1.0f + (x + 0.5f) * voxelSize,
            1.0f + (y + 0.5f) * voxelSize,
            1.0f + (z + 0.5f) * voxelSize
        );
        
        voxels.add(new VoxelData(position, level, density));
        
        // Track memory usage (approximate)
        totalMemoryAllocated.addAndGet(32); // Approximate size of VoxelData
    }
    
    /**
     * Build and serialize the octree to the provided buffer
     */
    public void serialize(ByteBuffer buffer) {
        ensureNotClosed();
        
        // Stub implementation - just write a simple header
        buffer.putInt(0x4553564F); // "ESVO" magic number
        buffer.putInt(maxDepth);
        buffer.putInt(voxels.size());
        
        // In full implementation, would build octree structure
        // and serialize nodes in breadth-first order
        
        log.debug("Serialized {} voxels to buffer", voxels.size());
    }
    
    /**
     * Allocate a managed buffer for octree construction
     */
    public ByteBuffer allocateBuffer(int sizeBytes, String debugName) {
        ensureNotClosed();
        
        ByteBuffer buffer = resourceManager.allocateMemory(sizeBytes);
        allocatedBuffers.add(buffer);
        totalMemoryAllocated.addAndGet(sizeBytes);
        
        log.info("OctreeBuilder allocated buffer '{}' of size {} bytes, identity: {}, now tracking {} buffers", 
                debugName, sizeBytes, System.identityHashCode(buffer), allocatedBuffers.size());
        return buffer;
    }
    
    /**
     * Get total memory allocated during octree construction
     */
    public long getTotalMemoryAllocated() {
        return totalMemoryAllocated.get();
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.debug("Closing OctreeBuilder, releasing {} bytes of memory from {} buffers", 
                     totalMemoryAllocated.get(), allocatedBuffers.size());
            
            // Release all allocated buffers
            int releasedCount = 0;
            for (ByteBuffer buffer : allocatedBuffers) {
                try {
                    log.info("OctreeBuilder releasing buffer {} (index {})", 
                             System.identityHashCode(buffer), releasedCount);
                    resourceManager.releaseMemory(buffer);
                    releasedCount++;
                    log.info("OctreeBuilder successfully released buffer {}, activeCount now: {}", 
                             System.identityHashCode(buffer), resourceManager.getActiveResourceCount());
                } catch (Exception e) {
                    log.error("Error releasing buffer", e);
                }
            }
            allocatedBuffers.clear();
            
            // Clear voxel data
            voxels.clear();
            
            log.info("OctreeBuilder closed, released {} buffers, {} bytes total", 
                    releasedCount, totalMemoryAllocated.getAndSet(0));
        }
    }
    
    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("OctreeBuilder has been closed");
        }
    }
    
    /**
     * Internal voxel data structure
     */
    private static class VoxelData {
        final Vector3f position;
        final int level;
        final float density;
        
        VoxelData(Vector3f position, int level, float density) {
            this.position = position;
            this.level = level;
            this.density = density;
        }
    }
}