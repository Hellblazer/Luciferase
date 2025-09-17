package com.hellblazer.luciferase.resource;

/**
 * Unified interface for GPU resources across different APIs
 */
public interface GPUResource extends AutoCloseable {
    
    /**
     * Get the type of this resource
     */
    GPUResourceType getType();
    
    /**
     * Get the size of this resource in bytes
     */
    long getSizeBytes();
    
    /**
     * Check if this resource is still valid (not closed)
     */
    boolean isValid();
    
    /**
     * Get the unique identifier for this resource
     */
    String getId();
    
    /**
     * Get the age of this resource in milliseconds
     */
    long getAgeMillis();
    
    /**
     * Get usage statistics for this resource
     */
    ResourceStatistics getStatistics();
    
    /**
     * Get the API-specific handle
     */
    Object getNativeHandle();
    
    /**
     * Get a human-readable description of this resource
     */
    String getDescription();
    
    /**
     * Check if this resource is closed
     */
    boolean isClosed();
    
    /**
     * Resource usage statistics
     */
    public static class ResourceStatistics {
        private final long allocatedBytes;
        private final long usedBytes;
        private final int accessCount;
        private final long lastAccessTime;
        private final double utilizationPercent;
        
        public ResourceStatistics(long allocatedBytes, long usedBytes, int accessCount, 
                                 long lastAccessTime, double utilizationPercent) {
            this.allocatedBytes = allocatedBytes;
            this.usedBytes = usedBytes;
            this.accessCount = accessCount;
            this.lastAccessTime = lastAccessTime;
            this.utilizationPercent = utilizationPercent;
        }
        
        public long getAllocatedBytes() {
            return allocatedBytes;
        }
        
        public long getUsedBytes() {
            return usedBytes;
        }
        
        public int getAccessCount() {
            return accessCount;
        }
        
        public long getLastAccessTime() {
            return lastAccessTime;
        }
        
        public double getUtilizationPercent() {
            return utilizationPercent;
        }
        
        @Override
        public String toString() {
            return String.format("ResourceStats[allocated=%d, used=%d, accesses=%d, utilization=%.1f%%]",
                allocatedBytes, usedBytes, accessCount, utilizationPercent);
        }
    }
}