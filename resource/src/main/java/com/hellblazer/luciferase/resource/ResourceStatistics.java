package com.hellblazer.luciferase.resource;

/**
 * Simple resource statistics container
 */
public class ResourceStatistics {
    private final int totalResources;
    private final long totalAllocatedBytes;
    private final int activeResourceCount;
    private final long poolAllocatedBytes;
    private final long poolUsedBytes;
    
    public ResourceStatistics(int totalResources, long totalAllocatedBytes, 
                            int activeResourceCount, long poolAllocatedBytes, 
                            long poolUsedBytes) {
        this.totalResources = totalResources;
        this.totalAllocatedBytes = totalAllocatedBytes;
        this.activeResourceCount = activeResourceCount;
        this.poolAllocatedBytes = poolAllocatedBytes;
        this.poolUsedBytes = poolUsedBytes;
    }
    
    public int getTotalResources() {
        return totalResources;
    }
    
    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes;
    }
    
    public int getActiveResourceCount() {
        return activeResourceCount;
    }
    
    public long getPoolAllocatedBytes() {
        return poolAllocatedBytes;
    }
    
    public long getPoolUsedBytes() {
        return poolUsedBytes;
    }
    
    public int activeResources() {
        return activeResourceCount;
    }
    
    public double getMemoryUtilization() {
        if (totalAllocatedBytes == 0) {
            return 0.0;
        }
        return (double) poolUsedBytes / totalAllocatedBytes * 100.0;
    }
    
    @Override
    public String toString() {
        return String.format(
            "ResourceStatistics{total=%d, allocated=%d bytes, active=%d, poolAlloc=%d, poolUsed=%d, utilization=%.1f%%}",
            totalResources, totalAllocatedBytes, activeResourceCount, 
            poolAllocatedBytes, poolUsedBytes, getMemoryUtilization()
        );
    }
}