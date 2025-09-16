package com.hellblazer.luciferase.resource;

import com.hellblazer.luciferase.resource.gpu.*;
import com.hellblazer.luciferase.resource.opencl.*;
import com.hellblazer.luciferase.resource.memory.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Unified resource manager for all GPU resources across OpenGL and OpenCL
 */
public class UnifiedResourceManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(UnifiedResourceManager.class);
    
    private final Map<UUID, GPUResource> resources = new ConcurrentHashMap<>();
    private final Map<ByteBuffer, UUID> bufferToIdMap = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<GPUResourceType, AtomicLong> allocatedBytesPerType = new ConcurrentHashMap<>();
    private final ResourceTracker tracker;
    private final MemoryPool memoryPool;
    private final ResourceConfiguration config;
    private volatile boolean closed = false;
    
    /**
     * Create a unified resource manager with default configuration
     */
    public UnifiedResourceManager() {
        this(ResourceConfiguration.defaultConfig());
    }
    
    /**
     * Create a unified resource manager with custom configuration
     */
    public UnifiedResourceManager(ResourceConfiguration config) {
        this.config = config;
        this.tracker = new ResourceTracker(config.getMaxIdleTime().toMillis(), config.isLeakDetectionEnabled());
        this.memoryPool = new MemoryPool(
            config.getMaxPoolSizeBytes(),
            config.getMaxIdleTime()
        );
        
        // Initialize per-type counters
        for (GPUResourceType type : GPUResourceType.values()) {
            allocatedBytesPerType.put(type, new AtomicLong(0));
        }
        
        log.info("Unified resource manager initialized with config: {}", config);
    }
    
    /**
     * Register a resource with the manager
     */
    public void register(GPUResource resource) {
        ensureNotClosed();
        
        UUID id = UUID.fromString(resource.getId());
        if (resources.putIfAbsent(id, resource) == null) {
            GPUResourceType type = resource.getType();
            long size = resource.getSizeBytes();
            allocatedBytesPerType.get(type).addAndGet(size);
            
            log.debug("Registered {} resource: {} ({} bytes)", type, id, size);
        }
    }
    
    /**
     * Unregister a resource from the manager
     */
    public void unregister(GPUResource resource) {
        UUID id = UUID.fromString(resource.getId());
        if (resources.remove(id) != null) {
            GPUResourceType type = resource.getType();
            long size = resource.getSizeBytes();
            allocatedBytesPerType.get(type).addAndGet(-size);
            
            log.debug("Unregistered {} resource: {} ({} bytes)", type, id, size);
        }
    }
    
    /**
     * Get a resource by ID
     */
    public GPUResource getResource(String id) {
        return resources.get(UUID.fromString(id));
    }
    
    /**
     * Get all resources of a specific type
     */
    public List<GPUResource> getResourcesByType(GPUResourceType type) {
        return resources.values().stream()
            .filter(r -> r.getType() == type)
            .collect(Collectors.toList());
    }
    
    /**
     * Get total allocated bytes for a resource type
     */
    public long getAllocatedBytes(GPUResourceType type) {
        return allocatedBytesPerType.get(type).get();
    }
    
    /**
     * Get total allocated bytes across all resources
     */
    public long getTotalAllocatedBytes() {
        return allocatedBytesPerType.values().stream()
            .mapToLong(AtomicLong::get)
            .sum();
    }
    
    /**
     * Get resource statistics
     */
    public ResourceManagerStatistics getStatistics() {
        Map<GPUResourceType, TypeStatistics> typeStats = new HashMap<>();
        
        for (GPUResourceType type : GPUResourceType.values()) {
            List<GPUResource> typeResources = getResourcesByType(type);
            if (!typeResources.isEmpty()) {
                long totalAllocated = getAllocatedBytes(type);
                long totalUsed = typeResources.stream()
                    .mapToLong(r -> r.getStatistics().getUsedBytes())
                    .sum();
                int count = typeResources.size();
                double avgUtilization = typeResources.stream()
                    .mapToDouble(r -> r.getStatistics().getUtilizationPercent())
                    .average()
                    .orElse(0.0);
                
                typeStats.put(type, new TypeStatistics(
                    count, totalAllocated, totalUsed, avgUtilization
                ));
            }
        }
        
        return new ResourceManagerStatistics(
            resources.size(),
            getTotalAllocatedBytes(),
            typeStats,
            memoryPool.getPoolStatistics(),
            tracker.getActiveCount()
        );
    }
    
    /**
     * Clean up old or unused resources
     */
    public int cleanupUnused(long maxAgeMillis) {
        ensureNotClosed();
        
        // Note: BufferResources are immediately removed on releaseMemory(),
        // so this mainly cleans up other resource types that might be leaked
        List<GPUResource> toRemove = resources.values().stream()
            .filter(r -> r.getAgeMillis() > maxAgeMillis)
            .collect(Collectors.toList());
        
        int removed = 0;
        for (GPUResource resource : toRemove) {
            try {
                resource.close();
                resources.remove(resource.getId());
                
                // Also clean up buffer mapping if it's a BufferResource
                if (resource instanceof BufferResource) {
                    var bufferResource = (BufferResource) resource;
                    var buffer = bufferResource.getBuffer();
                    if (buffer != null) {
                        bufferToIdMap.remove(buffer);
                    }
                }
                
                removed++;
            } catch (Exception e) {
                log.error("Failed to cleanup resource: {}", resource.getId(), e);
            }
        }
        
        if (removed > 0) {
            log.info("Cleaned up {} unused resources", removed);
        }
        
        return removed;
    }
    
    /**
     * Force close all resources
     */
    public void closeAll() {
        log.warn("Force closing {} resources", resources.size());
        
        List<GPUResource> toClose = new ArrayList<>(resources.values());
        for (GPUResource resource : toClose) {
            try {
                resource.close();
            } catch (Exception e) {
                log.error("Failed to close resource: {}", resource.getId(), e);
            }
        }
        
        resources.clear();
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        
        closed = true;
        
        // Close all resources
        closeAll();
        
        // Clear memory pool
        try {
            memoryPool.clear();
        } catch (Exception e) {
            log.error("Failed to clear memory pool", e);
        }
        
        // Shutdown tracker
        tracker.shutdown();
        
        log.info("Unified resource manager closed");
    }
    
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("Resource manager is closed");
        }
    }
    
    /**
     * Get the memory pool
     */
    public MemoryPool getMemoryPool() {
        return memoryPool;
    }
    
    /**
     * Get the resource tracker
     */
    public ResourceTracker getTracker() {
        return tracker;
    }
    
    /**
     * Get the configuration
     */
    public ResourceConfiguration getConfiguration() {
        return config;
    }
    
    /**
     * Allocate memory from the pool
     */
    public ByteBuffer allocateMemory(int size) {
        ensureNotClosed();
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }
        
        var buffer = memoryPool.allocate(size);
        if (buffer != null) {
            // Track the allocated buffer
            var resourceId = UUID.randomUUID();
            var handle = new BufferResource(resourceId, buffer, tracker);
            resources.put(resourceId, handle);
            bufferToIdMap.put(buffer, resourceId);
            tracker.register(handle);
            allocatedBytesPerType.get(GPUResourceType.MEMORY_POOL).addAndGet(size);
        }
        return buffer;
    }
    
    /**
     * Return memory to the pool
     */
    public void releaseMemory(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        
        // Remove from tracking - this is thread-safe
        var resourceId = bufferToIdMap.remove(buffer);
        if (resourceId != null) {
            var resource = resources.remove(resourceId);
            if (resource != null) {
                // Close the resource handle (which will unregister from tracker)
                try {
                    resource.close();
                } catch (Exception e) {
                    log.debug("Error closing buffer resource", e);
                }
                allocatedBytesPerType.get(GPUResourceType.MEMORY_POOL).addAndGet(-buffer.capacity());
            }
        } else {
            log.debug("Buffer {} not found in bufferToIdMap", System.identityHashCode(buffer));
        }
        
        // Return to pool
        memoryPool.returnToPool(buffer);
    }
    
    /**
     * Get the count of active resources
     */
    public int getActiveResourceCount() {
        ensureNotClosed();
        return resources.size();
    }
    
    /**
     * Get the total memory usage
     */
    public long getTotalMemoryUsage() {
        ensureNotClosed();
        return getTotalAllocatedBytes();
    }
    
    /**
     * Perform maintenance operations
     */
    public void performMaintenance() {
        ensureNotClosed();
        memoryPool.evictExpired();
        cleanupUnused(config.getMaxIdleTime().toMillis());
    }
    
    /**
     * Shutdown the resource manager
     */
    public void shutdown() {
        close();
    }
    
    /**
     * Statistics for the resource manager
     */
    public static class ResourceManagerStatistics {
        private final int totalResources;
        private final long totalAllocatedBytes;
        private final Map<GPUResourceType, TypeStatistics> typeStatistics;
        private final MemoryPool.PoolStatistics poolStatistics;
        private final int trackedResources;
        
        public ResourceManagerStatistics(int totalResources, long totalAllocatedBytes,
                                        Map<GPUResourceType, TypeStatistics> typeStatistics,
                                        MemoryPool.PoolStatistics poolStatistics,
                                        int trackedResources) {
            this.totalResources = totalResources;
            this.totalAllocatedBytes = totalAllocatedBytes;
            this.typeStatistics = typeStatistics;
            this.poolStatistics = poolStatistics;
            this.trackedResources = trackedResources;
        }
        
        public int getTotalResources() {
            return totalResources;
        }
        
        public long getTotalAllocatedBytes() {
            return totalAllocatedBytes;
        }
        
        public Map<GPUResourceType, TypeStatistics> getTypeStatistics() {
            return typeStatistics;
        }
        
        public MemoryPool.PoolStatistics getPoolStatistics() {
            return poolStatistics;
        }
        
        public int getTrackedResources() {
            return trackedResources;
        }
        
        @Override
        public String toString() {
            var sb = new StringBuilder();
            sb.append("Resource Manager Statistics\n");
            sb.append("===========================\n");
            sb.append(String.format("Total Resources: %d\n", totalResources));
            sb.append(String.format("Total Allocated: %d bytes\n", totalAllocatedBytes));
            sb.append(String.format("Tracked Resources: %d\n", trackedResources));
            
            if (!typeStatistics.isEmpty()) {
                sb.append("\nPer-Type Statistics:\n");
                for (var entry : typeStatistics.entrySet()) {
                    sb.append(String.format("  %s: %s\n", entry.getKey(), entry.getValue()));
                }
            }
            
            if (poolStatistics != null) {
                sb.append(String.format("\nMemory Pool: %s\n", poolStatistics));
            }
            
            return sb.toString();
        }
    }
    
    /**
     * Statistics for a resource type
     */
    public static class TypeStatistics {
        private final int count;
        private final long allocatedBytes;
        private final long usedBytes;
        private final double avgUtilization;
        
        public TypeStatistics(int count, long allocatedBytes, long usedBytes, double avgUtilization) {
            this.count = count;
            this.allocatedBytes = allocatedBytes;
            this.usedBytes = usedBytes;
            this.avgUtilization = avgUtilization;
        }
        
        public int getCount() {
            return count;
        }
        
        public long getAllocatedBytes() {
            return allocatedBytes;
        }
        
        public long getUsedBytes() {
            return usedBytes;
        }
        
        public double getAvgUtilization() {
            return avgUtilization;
        }
        
        @Override
        public String toString() {
            return String.format("count=%d, allocated=%d, used=%d, utilization=%.1f%%",
                count, allocatedBytes, usedBytes, avgUtilization);
        }
    }
}