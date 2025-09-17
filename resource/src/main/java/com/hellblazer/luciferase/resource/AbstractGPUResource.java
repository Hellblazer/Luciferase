package com.hellblazer.luciferase.resource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base implementation of GPUResource with common functionality
 */
public abstract class AbstractGPUResource<T> extends ResourceHandle<T> implements GPUResource {
    
    protected final GPUResourceType type;
    protected final long sizeBytes;
    protected final AtomicInteger accessCount = new AtomicInteger(0);
    protected final AtomicLong lastAccessTime = new AtomicLong(System.nanoTime());
    protected final String description;
    
    protected AbstractGPUResource(T resource, GPUResourceType type, long sizeBytes, String description) {
        super(resource, ResourceTracker.getGlobalTracker());
        this.type = type;
        this.sizeBytes = sizeBytes;
        this.description = description;
    }
    
    @Override
    public GPUResourceType getType() {
        return type;
    }
    
    @Override
    public long getSizeBytes() {
        return sizeBytes;
    }
    
    @Override
    public ResourceStatistics getStatistics() {
        long allocated = getSizeBytes();
        long used = calculateUsedBytes();
        int accesses = accessCount.get();
        long lastAccess = lastAccessTime.get();
        double utilization = (used * 100.0) / Math.max(allocated, 1);
        
        return new ResourceStatistics(allocated, used, accesses, lastAccess, utilization);
    }
    
    @Override
    public Object getNativeHandle() {
        return get();
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    /**
     * Check if this resource is closed
     */
    public boolean isClosed() {
        return !isValid();
    }
    
    /**
     * Record an access to this resource
     */
    protected void recordAccess() {
        accessCount.incrementAndGet();
        lastAccessTime.set(System.nanoTime());
    }
    
    /**
     * Calculate the actual used bytes (may be overridden by subclasses)
     */
    protected long calculateUsedBytes() {
        // Default implementation assumes all allocated space is used
        return sizeBytes;
    }
    
    @Override
    public String toString() {
        return String.format("%s[id=%s, type=%s, size=%d bytes, age=%d ms]",
            getClass().getSimpleName(), getId(), type, sizeBytes, getAgeMillis());
    }
}