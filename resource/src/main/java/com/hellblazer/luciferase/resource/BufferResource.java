package com.hellblazer.luciferase.resource;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * A GPU resource wrapper for ByteBuffers
 */
public class BufferResource extends ResourceHandle<ByteBuffer> implements GPUResource {
    private long lastAccessTime;
    private long accessCount;
    
    public BufferResource(UUID id, ByteBuffer buffer, ResourceTracker tracker) {
        super(buffer, tracker);
        this.lastAccessTime = System.currentTimeMillis();
        this.accessCount = 1; // Mark as accessed on allocation
    }
    
    @Override
    protected void doCleanup(ByteBuffer resource) {
        // Cleanup will be handled by UnifiedResourceManager returning buffer to pool
    }
    
    @Override
    public GPUResourceType getType() {
        return GPUResourceType.MEMORY_POOL;
    }
    
    @Override
    public long getSizeBytes() {
        if (isValid()) {
            var buffer = get();
            return buffer != null ? buffer.capacity() : 0;
        }
        return 0;
    }
    
    @Override
    public boolean isValid() {
        return getState() == State.ALLOCATED && super.isValid();
    }
    
    public void bind() {
        if (!isValid()) {
            throw new IllegalStateException("Resource is closed");
        }
        lastAccessTime = System.currentTimeMillis();
        accessCount++;
    }
    
    public void unbind() {
        // No-op for buffers
    }
    
    @Override
    public GPUResource.ResourceStatistics getStatistics() {
        return new GPUResource.ResourceStatistics(
            getSizeBytes(),
            getSizeBytes(), // For buffers, used == allocated
            (int) accessCount,
            lastAccessTime,
            1.0 // 100% utilization for simple buffers
        );
    }
    
    public long getCreatedMillis() {
        return getAllocationTime();
    }
    
    @Override
    public long getAgeMillis() {
        return System.currentTimeMillis() - getAllocationTime();
    }
    
    public ByteBuffer getBuffer() {
        return isValid() ? get() : null;
    }
    
    @Override
    public Object getNativeHandle() {
        return isValid() ? get() : null;
    }
    
    @Override
    public String getDescription() {
        if (isValid()) {
            var buffer = get();
            if (buffer != null) {
                return String.format("ByteBuffer[id=%s, size=%d bytes, direct=%s]",
                    getId(), buffer.capacity(), buffer.isDirect());
            }
        }
        return String.format("ByteBuffer[id=%s, closed]", getId());
    }
}